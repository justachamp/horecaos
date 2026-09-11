package uz.horecaos.platform.tenancy.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The guarded writes on {@code tenant.owner_invitations} (ADR 0097, V0210), on
 * their own.
 *
 * <p>Every write after the claim carries {@code AND attempts = :attempt} and
 * returns whether it matched, and {@code OwnerInvitationRelay.settle} appends
 * the history line only when it did. That return is the whole mechanism, and at
 * the flow level only {@code markFailed}'s was ever observed: {@code markRetry}
 * and {@code markNotNeeded} could each have been written {@code return true}
 * with the suite still green. {@code markRetry} is not a copy of its siblings
 * either -- its SET clause moves {@code attempts}, and an unguarded one would
 * move a counter that belongs to a newer attempt.
 *
 * <p>The race these guards are for is a resend: {@link
 * JdbcOwnerInvitationStore#requeue} puts the row back at {@code attempts = 0}
 * while a send is still in flight, so the in-flight attempt's outcome names an
 * attempt that no longer exists.
 */
class JdbcOwnerInvitationStoreTests {

    private static final Duration LEASE = Duration.ofMinutes(5);

    private static final Instant NOW = Instant.parse("2026-09-11T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcOwnerInvitationStore store;
    private UUID tenantId;
    private UUID invitationId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        store = new JdbcOwnerInvitationStore(jdbc);

        tenantId = Ids.newId();
        jdbc.sql("""
                        INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                            default_timezone, status, version)
                        VALUES (:id, 'qoida', 'Qoida MCHJ', 'Qoida & Co', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                        """).param("id", tenantId).update();

        invitationId = Ids.newId();
        assertThat(store.queueIfAbsent(invitationId, tenantId, "owner-subject", "ru", "onboarding-run", NOW))
                .isTrue();
    }

    /**
     * The claim counts the attempt; the resend puts the row back at zero. Every
     * outcome the in-flight attempt could report has to miss, or it writes a
     * code, a state or a counter over a newer attempt's -- and the relay appends
     * a history line for it, into a table the application role only holds {@code
     * SELECT, INSERT} on.
     */
    @Test
    @DisplayName("an outcome for an attempt a resend replaced matches nothing and moves nothing")
    void anOvertakenOutcomeMatchesNothing() {
        List<JdbcOwnerInvitationStore.Row> claimed = store.claimDue(NOW, LEASE, 10);
        assertThat(claimed)
                .singleElement()
                .satisfies(row -> assertThat(row.attempts())
                        .as("the claim is the attempt")
                        .isEqualTo(1));

        assertThat(store.requeue(invitationId, "en", "operator", NOW.plusSeconds(1)))
                .as("the operator's resend, while attempt 1 is still in flight")
                .isTrue();

        assertThat(store.markRetry(invitationId, 1, NOW.plusSeconds(60), "SMTP_UNAVAILABLE", true))
                .as("attempt 1 no longer exists, so its deferral applies to nothing")
                .isFalse();
        assertThat(store.markRetry(invitationId, 1, NOW.plusSeconds(60), "MAIL_NOT_CONFIGURED", false))
                .as("and the branch that gives an attempt back must not give back one it does not own")
                .isFalse();
        assertThat(store.markNotNeeded(invitationId, 1))
                .as("nor may it settle a row a person has just asked to be sent again")
                .isFalse();
        assertThat(store.markFailed(invitationId, 1, "ADDRESS_REJECTED")).isFalse();
        assertThat(store.markSent(invitationId, 1, "a".repeat(64), NOW.plusSeconds(600), NOW.plusSeconds(2)))
                .isFalse();

        JdbcOwnerInvitationStore.Row row = store.latestFor(tenantId).orElseThrow();
        assertThat(row.status()).isEqualTo("QUEUED");
        assertThat(row.attempts())
                .as("the resend's counter, untouched: an unguarded markRetry would spend or refund it")
                .isZero();
        assertThat(row.lastErrorCode())
                .as("the resend cleared it and no overtaken attempt wrote its code back")
                .isNull();
        assertThat(row.locale()).isEqualTo("en");
    }

    /**
     * The other direction, without which every assertion above would also hold
     * for a method that simply returned false.
     */
    @Test
    @DisplayName("the attempt that is still the current one applies, and says so")
    void theCurrentAttemptApplies() {
        store.claimDue(NOW, LEASE, 10);

        assertThat(store.markRetry(invitationId, 1, NOW.plusSeconds(60), "SMTP_UNAVAILABLE", true))
                .isTrue();
        assertThat(store.latestFor(tenantId).orElseThrow())
                .satisfies(row -> assertThat(row.attempts()).isEqualTo(1))
                .satisfies(row -> assertThat(row.lastErrorCode()).isEqualTo("SMTP_UNAVAILABLE"));

        assertThat(store.markNotNeeded(invitationId, 1)).isTrue();
        JdbcOwnerInvitationStore.Row settled = store.latestFor(tenantId).orElseThrow();
        assertThat(settled.status()).isEqualTo("NOT_NEEDED");
        assertThat(settled.nextAttemptAt())
                .as("a settled invitation is never claimed again")
                .isNull();
        assertThat(settled.lastErrorCode()).isNull();
    }

    /**
     * The branch that exists so a deployment waiting for its mail settings does
     * not spend the attempts a real outage would need.
     */
    @Test
    @DisplayName("a retry that attempted nothing gives the attempt back")
    void aRetryThatAttemptedNothingGivesTheAttemptBack() {
        store.claimDue(NOW, LEASE, 10);

        assertThat(store.markRetry(invitationId, 1, NOW.plusSeconds(900), "MAIL_NOT_CONFIGURED", false))
                .isTrue();

        assertThat(store.latestFor(tenantId).orElseThrow().attempts())
                .as("nothing was attempted, so nothing was used up")
                .isZero();
    }
}
