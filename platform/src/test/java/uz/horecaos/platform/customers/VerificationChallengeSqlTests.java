package uz.horecaos.platform.customers;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;

import uz.horecaos.platform.customers.application.VerificationChallengeStore.Attempt;
import uz.horecaos.platform.customers.application.VerificationChallengeStore.IssuanceWindow;
import uz.horecaos.platform.customers.application.VerificationChallengeStore.NewChallenge;
import uz.horecaos.platform.customers.application.VerificationChallengeStore.RedeemedGrant;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcVerificationChallengeStore;
import uz.horecaos.platform.support.TestDatabase;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The statements and the constraints, against a real PostgreSQL (ADR 0015).
 *
 * <p>{@link CustomerVerificationTests} proves the rules; this proves that the SQL
 * and the DDL express them. The two are separable on purpose — the rules are
 * conditions and can be asserted in a map, but "the condition is in the
 * {@code WHERE} clause" and "the constraint rejects a row that contradicts the
 * status" are claims only a database can settle.
 *
 * <p><strong>The DDL is created here as well as handed over.</strong>
 * {@code customer.verification_challenges} belongs to a Flyway migration this
 * agent may not write, so the table is created here with {@code IF NOT EXISTS}
 * from exactly the text given in the handover. Until the migration lands, this
 * suite is what proves that text is valid and that the statements run against it;
 * once it lands, Flyway wins the race and these become tests of the real schema
 * without anybody having to remember to come back and change them.
 */
class VerificationChallengeSqlTests {

    /**
     * Verbatim the handover DDL, less its comments and grants.
     *
     * <p>Kept as one string rather than assembled, so that what is exercised here
     * and what goes into the migration cannot drift apart by an edit to one of
     * them.
     */
    private static final String DDL = """
            CREATE TABLE IF NOT EXISTS customer.verification_challenges (
                id uuid PRIMARY KEY,
                tenant_id uuid NOT NULL,
                brand_id uuid NOT NULL,

                purpose varchar(32) NOT NULL,
                contact_type varchar(16) NOT NULL,

                destination_hash varchar(64) NOT NULL,
                destination_encrypted text NOT NULL,

                code_hash varchar(64) NOT NULL,

                attempts_used smallint NOT NULL DEFAULT 0,
                max_attempts smallint NOT NULL,

                status varchar(16) NOT NULL DEFAULT 'PENDING',

                issued_at timestamptz NOT NULL,
                expires_at timestamptz NOT NULL,
                settled_at timestamptz,

                grant_hash varchar(64),
                grant_expires_at timestamptz,
                grant_redeemed_at timestamptz,

                created_at timestamptz NOT NULL DEFAULT now(),
                updated_at timestamptz NOT NULL DEFAULT now(),

                CONSTRAINT ck_verification_purpose CHECK (purpose IN ('SIGN_IN')),
                CONSTRAINT ck_verification_contact_type CHECK (contact_type = 'PHONE'),
                CONSTRAINT ck_verification_status CHECK (
                    status IN ('PENDING', 'VERIFIED', 'EXHAUSTED', 'SUPERSEDED', 'EXPIRED')
                ),
                CONSTRAINT ck_verification_max_attempts CHECK (max_attempts BETWEEN 1 AND 10),
                CONSTRAINT ck_verification_attempts CHECK (
                    attempts_used >= 0 AND attempts_used <= max_attempts
                ),
                CONSTRAINT ck_verification_window CHECK (expires_at > issued_at),
                CONSTRAINT ck_verification_settled CHECK (
                    (status = 'PENDING') = (settled_at IS NULL)
                ),
                CONSTRAINT ck_verification_grant CHECK (
                    (status = 'VERIFIED') = (grant_hash IS NOT NULL)
                ),
                CONSTRAINT ck_verification_grant_window CHECK (
                    (grant_hash IS NULL) = (grant_expires_at IS NULL)
                ),
                CONSTRAINT ck_verification_grant_redeemed CHECK (
                    grant_redeemed_at IS NULL OR grant_hash IS NOT NULL
                )
            )
            """;

    private static final String GRANT_INDEX = """
            CREATE UNIQUE INDEX IF NOT EXISTS ux_verification_grant
                ON customer.verification_challenges (grant_hash)
                WHERE grant_hash IS NOT NULL
            """;

    private static final String DESTINATION_INDEX = """
            CREATE INDEX IF NOT EXISTS ix_verification_by_destination
                ON customer.verification_challenges (tenant_id, destination_hash, issued_at DESC)
            """;

    private static final String PENDING_INDEX = """
            CREATE INDEX IF NOT EXISTS ix_verification_pending_expiry
                ON customer.verification_challenges (expires_at)
                WHERE status = 'PENDING'
            """;

    private static final String SETTLED_INDEX = """
            CREATE INDEX IF NOT EXISTS ix_verification_settled
                ON customer.verification_challenges (settled_at)
                WHERE status <> 'PENDING'
            """;

    /**
     * The grants the migration must carry, run here for the one thing a test can
     * genuinely settle about them: that the statement parses, and that
     * {@code horecaos_application} is the role this schema actually uses. ADR 0015's
     * own migration forgot a grant nine times over, and {@code DELETE} is the one
     * easy to leave out here and not optional — a challenge row holds an encrypted
     * phone number, so retention is a deletion the application performs rather
     * than a policy somebody writes down.
     */
    private static final String GRANTS = """
            GRANT SELECT, INSERT, UPDATE, DELETE
                ON customer.verification_challenges TO horecaos_application
            """;

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final String HASH = "destination-hash-1";
    private static final String CODE_HASH = "code-mac-1";
    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");

    private static TestDatabase.Handle db;
    private static DriverManagerDataSource dataSource;

    private JdbcClient jdbc;
    private JdbcVerificationChallengeStore store;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
        dataSource = new DriverManagerDataSource(
                db.jdbcUrl(), db.username(), db.password());
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);

        jdbc.sql(DDL).update();
        jdbc.sql(GRANT_INDEX).update();
        jdbc.sql(DESTINATION_INDEX).update();
        jdbc.sql(PENDING_INDEX).update();
        jdbc.sql(SETTLED_INDEX).update();
        jdbc.sql(GRANTS).update();
        jdbc.sql("TRUNCATE TABLE customer.verification_challenges").update();

        store = new JdbcVerificationChallengeStore(jdbc);
    }

    @Test
    @DisplayName("the attempt limit is the WHERE clause, not a comparison in Java")
    void attemptsRunOutInTheDatabase() {
        UUID challengeId = insert(NOW, NOW.plus(Duration.ofMinutes(5)), 3);

        for (int spent = 1; spent <= 3; spent++) {
            Optional<Attempt> attempt = store.consumeAttempt(TENANT, challengeId, NOW);
            assertThat(attempt).isPresent();
            assertThat(attempt.orElseThrow().attemptsRemaining()).isEqualTo(3 - spent);
            assertThat(attempt.orElseThrow().codeHash()).isEqualTo(CODE_HASH);
        }

        assertThat(store.consumeAttempt(TENANT, challengeId, NOW))
                .as("a fourth attempt against a three-attempt challenge is not offered")
                .isEmpty();
    }

    @Test
    @DisplayName("an expired challenge yields no attempt, and another tenant's yields none either")
    void anAttemptNeedsALiveChallengeInTheRightTenant() {
        UUID challengeId = insert(NOW, NOW.plus(Duration.ofMinutes(5)), 5);

        assertThat(store.consumeAttempt(OTHER_TENANT, challengeId, NOW)).isEmpty();
        assertThat(store.consumeAttempt(TENANT, challengeId, NOW.plus(Duration.ofMinutes(6))))
                .isEmpty();
        assertThat(store.consumeAttempt(TENANT, challengeId, NOW)).isPresent();
    }

    @Test
    @DisplayName("exactly one of two racing verifications settles the challenge")
    void aCodeSettlesOnce() {
        UUID challengeId = insert(NOW, NOW.plus(Duration.ofMinutes(5)), 5);
        Instant grantExpiry = NOW.plus(Duration.ofMinutes(10));

        assertThat(store.markVerified(TENANT, challengeId, "grant-a", grantExpiry, NOW)).isTrue();
        assertThat(store.markVerified(TENANT, challengeId, "grant-b", grantExpiry, NOW))
                .as("the loser of the race gets nothing, rather than a second grant")
                .isFalse();
    }

    @Test
    @DisplayName("a grant redeems exactly once, and hands back the row's own tenant and brand")
    void aGrantRedeemsOnce() {
        UUID challengeId = insert(NOW, NOW.plus(Duration.ofMinutes(5)), 5);
        Instant grantExpiry = NOW.plus(Duration.ofMinutes(10));
        store.markVerified(TENANT, challengeId, "grant-a", grantExpiry, NOW);

        Optional<RedeemedGrant> first = store.redeemGrant("grant-a", NOW);
        assertThat(first).isPresent();
        assertThat(first.orElseThrow().tenantId()).isEqualTo(TENANT);
        assertThat(first.orElseThrow().brandId()).isEqualTo(BRAND);

        assertThat(store.redeemGrant("grant-a", NOW)).isEmpty();
        assertThat(store.redeemGrant("grant-a", grantExpiry.plusSeconds(1))).isEmpty();
    }

    @Test
    @DisplayName("two rows cannot hold the same grant digest")
    void grantDigestsAreUnique() {
        UUID first = insert(NOW, NOW.plus(Duration.ofMinutes(5)), 5);
        UUID second = insert(NOW, NOW.plus(Duration.ofMinutes(5)), 5);
        Instant grantExpiry = NOW.plus(Duration.ofMinutes(10));

        store.markVerified(TENANT, first, "grant-a", grantExpiry, NOW);

        assertThat(catchThrowable(
                () -> store.markVerified(TENANT, second, "grant-a", grantExpiry, NOW)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("the budget counts every challenge for a number, and reports the last one")
    void theIssuanceWindowCountsEverything() {
        insert(NOW.minus(Duration.ofHours(2)), NOW.minus(Duration.ofHours(2)).plusSeconds(300), 5);
        insert(NOW.minus(Duration.ofMinutes(30)), NOW.plusSeconds(300), 5);
        UUID latest = insert(NOW, NOW.plusSeconds(300), 5);
        store.markExhausted(TENANT, latest, NOW);

        IssuanceWindow window = store.issuanceWindow(TENANT, HASH, NOW.minus(Duration.ofHours(1)));

        assertThat(window.issuedInWindow())
                .as("an exhausted challenge still counts; burning one must not refund the budget")
                .isEqualTo(2);
        assertThat(window.lastIssuedAt()).contains(NOW);
    }

    @Test
    @DisplayName("issuing again retires every live challenge for that number")
    void supersedingIsScopedToOneNumberInOneTenant() {
        UUID mine = insert(NOW, NOW.plusSeconds(300), 5);
        UUID theirs = insertFor(OTHER_TENANT, NOW, NOW.plusSeconds(300), 5, HASH);

        assertThat(store.supersedePending(TENANT, "PHONE", HASH, NOW)).isEqualTo(1);
        assertThat(store.consumeAttempt(TENANT, mine, NOW)).isEmpty();
        assertThat(store.consumeAttempt(OTHER_TENANT, theirs, NOW))
                .as("one tenant's issuance must not disturb another's, on the same number")
                .isPresent();
    }

    @Test
    @DisplayName("a challenge whose code was never sent can be withdrawn; a used one cannot")
    void onlyAnUntouchedChallengeIsWithdrawn() {
        UUID untouched = insert(NOW, NOW.plusSeconds(300), 5);
        UUID attempted = insert(NOW, NOW.plusSeconds(300), 5);
        store.consumeAttempt(TENANT, attempted, NOW);

        assertThat(store.deleteUnsent(TENANT, untouched)).isTrue();
        assertThat(store.deleteUnsent(TENANT, attempted))
                .as("withdrawing a challenge somebody has guessed at would erase the evidence")
                .isFalse();
    }

    @Test
    @DisplayName("the sweeper closes lapsed challenges and then removes settled ones")
    void theSweepRunsInTwoStages() {
        UUID lapsed = insert(NOW.minus(Duration.ofHours(1)), NOW.minus(Duration.ofMinutes(55)), 5);

        assertThat(store.expirePending(NOW, 100)).isEqualTo(1);
        assertThat(status(lapsed)).isEqualTo("EXPIRED");

        assertThat(store.purgeSettledBefore(NOW.minus(Duration.ofDays(30)), 100))
                .as("a challenge settled a moment ago is inside its retention")
                .isZero();
        assertThat(store.purgeSettledBefore(NOW.plus(Duration.ofDays(31)), 100)).isEqualTo(1);
    }

    @Test
    @DisplayName("the constraints refuse a row that contradicts itself")
    void theConstraintsHoldTheInvariants() {
        UUID valid = insert(NOW, NOW.plusSeconds(300), 5);

        // A grant on a row that is not verified. Without this, a bug that wrote a
        // grant while leaving the status pending would produce a challenge that is
        // simultaneously guessable and redeemable.
        assertThat(catchThrowable(() -> jdbc.sql("""
                INSERT INTO customer.verification_challenges (
                    id, tenant_id, brand_id, purpose, contact_type, destination_hash,
                    destination_encrypted, code_hash, max_attempts, status, issued_at,
                    expires_at, grant_hash, grant_expires_at)
                VALUES (:id, :tenantId, :brandId, 'SIGN_IN', 'PHONE', :hash, 'x', :codeHash,
                    5, 'PENDING', :issuedAt, :expiresAt, 'grant-x', :grantExpiry)
                """)
                .param("id", UUID.randomUUID()).param("tenantId", TENANT).param("brandId", BRAND)
                .param("hash", HASH).param("codeHash", CODE_HASH)
                .param("issuedAt", offset(NOW)).param("expiresAt", offset(NOW.plusSeconds(300)))
                .param("grantExpiry", offset(NOW.plusSeconds(600)))
                .update()))
                .isInstanceOf(DataIntegrityViolationException.class);

        // A window that closes before it opens.
        assertThat(catchThrowable(() -> insert(NOW, NOW.minusSeconds(1), 5)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // More attempts than the challenge allows.
        assertThat(catchThrowable(() -> jdbc.sql("""
                UPDATE customer.verification_challenges
                SET attempts_used = max_attempts + 1 WHERE id = :id
                """).param("id", valid).update()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private String status(UUID challengeId) {
        return jdbc.sql("SELECT status FROM customer.verification_challenges WHERE id = :id")
                .param("id", challengeId)
                .query(String.class)
                .single();
    }

    private UUID insert(Instant issuedAt, Instant expiresAt, int maxAttempts) {
        return insertFor(TENANT, issuedAt, expiresAt, maxAttempts, HASH);
    }

    private UUID insertFor(UUID tenantId, Instant issuedAt, Instant expiresAt, int maxAttempts,
            String destinationHash) {
        UUID id = UUID.randomUUID();
        store.insert(new NewChallenge(id, tenantId, BRAND, "SIGN_IN", "PHONE", destinationHash,
                "ciphertext", CODE_HASH, maxAttempts, issuedAt, expiresAt));
        return id;
    }

    private static java.time.OffsetDateTime offset(Instant instant) {
        return java.time.OffsetDateTime.ofInstant(instant, java.time.ZoneOffset.UTC);
    }
}
