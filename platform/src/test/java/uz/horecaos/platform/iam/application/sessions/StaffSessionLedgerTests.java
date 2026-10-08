package uz.horecaos.platform.iam.application.sessions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffSignInStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0176 against a real database with a clock the test moves: the row written per tenant, the
 * ten-minute touch, the one place a row is ended, and the two sweeps. A fixture that wrote a row
 * in the state production never produces would prove nothing, so every row here is made by the
 * ledger's own call.
 */
class StaffSessionLedgerTests {

    private static final UUID TENANT_A = UUID.fromString("018fb100-6100-7000-8000-0000000000a1");
    private static final UUID TENANT_B = UUID.fromString("018fb100-6100-7000-8000-0000000000a2");
    private static final String ORG_A = "org-ledger-a";
    private static final String ORG_B = "org-ledger-b";
    private static final String SUBJECT = "subject-ledger-1";
    private static final String OTHER_SUBJECT = "subject-ledger-2";
    private static final DeviceLabel PHONE =
            new DeviceLabel(DeviceLabel.Browser.SAFARI, DeviceLabel.Os.IOS, DeviceLabel.Form.PHONE);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private MovableClock clock;
    private JdbcStaffSignInStore store;
    private StaffSessionLedger ledger;
    private SimpleMeterRegistry meters;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
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
        clock = new MovableClock(Instant.parse("2026-10-01T09:00:00Z"));
        store = new JdbcStaffSignInStore(jdbc);
        meters = new SimpleMeterRegistry();
        ledger = new StaffSessionLedger(store, new DeviceLabelParser(), clock, meters);
        tenant(TENANT_A, "ledger-a", ORG_A);
        tenant(TENANT_B, "ledger-b", ORG_B);
        member(TENANT_A, SUBJECT, "S-0001");
        member(TENANT_B, SUBJECT, "S-0001");
        member(TENANT_A, OTHER_SUBJECT, "S-0002");
    }

    @Test
    @DisplayName("a sign-in writes a row per named tenant that keeps a record, once per session")
    void oneRowPerTenantPerSession() {
        ledger.recordSignIn(
                SUBJECT, Set.of(ORG_A, ORG_B, "org-unknown"), "kc-1", "Mozilla/5.0 (iPhone) Mobile Safari/604.1");
        ledger.recordSignIn(SUBJECT, Set.of(ORG_A, ORG_B), "kc-1", "Mozilla/5.0 (iPhone) Mobile Safari/604.1");

        assertThat(count("SELECT count(*) FROM iam.staff_sign_ins")).isEqualTo(2);
        assertThat(store.recent(TENANT_A, SUBJECT, 10)).singleElement().satisfies(row -> {
            assertThat(row.sessionId()).isEqualTo("kc-1");
            assertThat(row.label().form()).isEqualTo(DeviceLabel.Form.PHONE);
            assertThat(row.signedInAt()).isEqualTo(clock.instant());
        });
        assertThat(store.recent(TENANT_A, OTHER_SUBJECT, 10)).isEmpty();
    }

    @Test
    @DisplayName("a person with no record in a named tenant gets no row there, and the sign-in is not an error")
    void noRecordNoRow() {
        ledger.recordSignIn(OTHER_SUBJECT, Set.of(ORG_A, ORG_B), "kc-2", null);

        assertThat(store.recent(TENANT_A, OTHER_SUBJECT, 10)).hasSize(1);
        assertThat(count("SELECT count(*) FROM iam.staff_sign_ins WHERE tenant_id = '" + TENANT_B + "'"))
                .isZero();
    }

    @Test
    @DisplayName("a token with no session id or no organization is not recorded")
    void nothingToPointAt() {
        ledger.recordSignIn(SUBJECT, Set.of(ORG_A), null, null);
        ledger.recordSignIn(SUBJECT, Set.of(), "kc-3", null);

        assertThat(count("SELECT count(*) FROM iam.staff_sign_ins")).isZero();
    }

    @Test
    @DisplayName("last_seen_at moves at most once in ten minutes, and never on an ended row")
    void touchIsCoarse() {
        ledger.recordSignIn(SUBJECT, Set.of(ORG_A), "kc-4", null);
        Instant signedIn = clock.instant();

        clock.advance(Duration.ofMinutes(9));
        ledger.touch("kc-4");
        assertThat(lastSeen("kc-4")).as("inside ten minutes: no write").isEqualTo(signedIn);

        clock.advance(Duration.ofMinutes(2));
        ledger.touch("kc-4");
        assertThat(lastSeen("kc-4")).as("past ten minutes: moved").isEqualTo(clock.instant());

        Instant seen = lastSeen("kc-4");
        ledger.signedOut("kc-4");
        clock.advance(Duration.ofMinutes(30));
        ledger.touch("kc-4");
        assertThat(lastSeen("kc-4"))
                .as("an ended row is not resurrected by a late refresh")
                .isEqualTo(seen);
    }

    @Test
    @DisplayName("every way of ending a row records why, and a reset never rewrites an earlier reason")
    void theReasonIsRecorded() {
        ledger.recordSignIn(SUBJECT, Set.of(ORG_A, ORG_B), "kc-5", null);
        ledger.recordSignIn(SUBJECT, Set.of(ORG_A, ORG_B), "kc-6", null);
        ledger.recordSignIn(SUBJECT, Set.of(ORG_A, ORG_B), "kc-7", null);

        ledger.signedOut("kc-5");
        store.endSessionOf(SUBJECT, "kc-6", EndReason.ENDED_ONE, clock.instant());
        ledger.endedByPasswordReset(SUBJECT);

        assertThat(reasons("kc-5")).containsOnly("SIGNED_OUT");
        assertThat(reasons("kc-6")).containsOnly("ENDED_ONE");
        assertThat(reasons("kc-7"))
                .as("a reset marks the subject's rows in every tenant")
                .containsOnly("PASSWORD_RESET");
        assertThat(count("SELECT count(*) FROM iam.staff_sign_ins WHERE keycloak_session_id = 'kc-7'"))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("ending in one tenant leaves the other tenant's rows and another person's rows open")
    void endingIsScopedToTenantAndPerson() {
        ledger.recordSignIn(SUBJECT, Set.of(ORG_A, ORG_B), "kc-8", null);
        ledger.recordSignIn(OTHER_SUBJECT, Set.of(ORG_A), "kc-9", null);

        int ended = ledger.endAllInTenant(TENANT_A, SUBJECT);

        assertThat(ended).isEqualTo(1);
        assertThat(reasons("kc-8")).containsExactlyInAnyOrder("SIGNED_OUT_EVERYWHERE", "<open>");
        assertThat(reasons("kc-9")).containsOnly("<open>");
    }

    @Test
    @DisplayName(
            "a store that throws never throws out of the sign-in, touch, sign-out or reset paths, and each failure is counted")
    void theLedgerSwallowsItsOwnFailures() {
        JdbcStaffSignInStore broken = mock(JdbcStaffSignInStore.class);
        when(broken.recordSignIn(anyString(), any(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("boom"));
        when(broken.touch(anyString(), any(), any())).thenThrow(new IllegalStateException("boom"));
        when(broken.endSession(anyString(), any(), any())).thenThrow(new IllegalStateException("boom"));
        when(broken.endAllEverywhere(anyString(), any(), any())).thenThrow(new IllegalStateException("boom"));
        StaffSessionLedger failing = new StaffSessionLedger(broken, new DeviceLabelParser(), clock, meters);

        assertThatCode(() -> {
                    failing.recordSignIn(SUBJECT, Set.of(ORG_A), "kc-x", "UA");
                    failing.touch("kc-x");
                    failing.signedOut("kc-x");
                    failing.endedByPasswordReset(SUBJECT);
                })
                .doesNotThrowAnyException();
        assertThat(meters.find("horecaos.iam.staff_session.ledger").tag("outcome", "failed").counters().stream()
                        .mapToDouble(counter -> counter.count())
                        .sum())
                .isEqualTo(4);
    }

    // ---------------------------------------------------------------------------------- sweeps

    @Test
    @DisplayName("the idle sweep closes a row not seen for thirty days as EXPIRED and leaves a recent one open")
    void idleExpiry() {
        ledger.recordSignIn(SUBJECT, Set.of(ORG_A), "kc-old", null);
        clock.advance(Duration.ofDays(29));
        ledger.recordSignIn(SUBJECT, Set.of(ORG_A), "kc-recent", null);
        clock.advance(Duration.ofDays(2));

        int expired = sweeper(StaffSignInSweeper.Mode.REPORT_ONLY).expireIdle();

        assertThat(expired).isEqualTo(1);
        assertThat(reasons("kc-old")).containsOnly("EXPIRED");
        assertThat(reasons("kc-recent")).containsOnly("<open>");
    }

    @Test
    @DisplayName("retention in report-only mode counts what it would delete and deletes nothing")
    void retentionReportsFirst() {
        ledger.recordSignIn(SUBJECT, Set.of(ORG_A), "kc-ancient", null);
        clock.advance(Duration.ofDays(181));

        int would = sweeper(StaffSignInSweeper.Mode.REPORT_ONLY).applyRetention();

        assertThat(would).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM iam.staff_sign_ins"))
                .as("report-only writes nothing")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("enforcing deletes rows past 180 days and keeps those inside it")
    void retentionEnforces() {
        ledger.recordSignIn(SUBJECT, Set.of(ORG_A), "kc-ancient", null);
        clock.advance(Duration.ofDays(2));
        ledger.recordSignIn(SUBJECT, Set.of(ORG_A), "kc-179", null);
        clock.advance(Duration.ofDays(179));

        int deleted = sweeper(StaffSignInSweeper.Mode.ENFORCE).applyRetention();

        assertThat(deleted).isEqualTo(1);
        assertThat(reasons("kc-179")).isNotEmpty();
        assertThat(count("SELECT count(*) FROM iam.staff_sign_ins WHERE keycloak_session_id = 'kc-ancient'"))
                .isZero();
    }

    @Test
    @DisplayName("a tenant that stored a longer window lengthens the global pass, a shorter one cannot shorten it")
    void aTenantCanOnlyLengthen() {
        ledger.recordSignIn(SUBJECT, Set.of(ORG_A), "kc-ancient", null);
        clock.advance(Duration.ofDays(200));
        configuration(TENANT_A, 400);
        configuration(TENANT_B, 30);

        int deleted = sweeper(StaffSignInSweeper.Mode.ENFORCE).applyRetention();

        assertThat(deleted)
                .as("400 days is the longest stored: a 200-day-old row stays")
                .isZero();
        assertThat(sweeper(StaffSignInSweeper.Mode.ENFORCE).effectiveRetentionDays())
                .isEqualTo(400);
    }

    // ---------------------------------------------------------------------------------- helpers

    private StaffSignInSweeper sweeper(StaffSignInSweeper.Mode mode) {
        return new StaffSignInSweeper(store, clock, meters, mode, 180, Duration.ofDays(30), 500);
    }

    private void configuration(UUID tenantId, long days) {
        jdbc.sql("""
                INSERT INTO tenant.configuration_values
                    (id, key_code, scope_type, tenant_id, value_type, integer_value, set_by)
                VALUES (:id, :key, 'TENANT', :tenant, 'INTEGER', :days, 'test')
                """)
                .param("id", UUID.randomUUID())
                .param("key", StaffSignInSweeper.RETENTION_KEY_CODE)
                .param("tenant", tenantId)
                .param("days", days)
                .update();
    }

    private void tenant(UUID id, String slug, String organization) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version, keycloak_organization_id)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0, :org)
                """)
                .param("id", id)
                .param("slug", slug)
                .param("org", organization)
                .update();
    }

    private void member(UUID tenantId, String subject, String reference) {
        jdbc.sql("""
                INSERT INTO iam.staff_members (id, tenant_id, principal_subject, display_reference, employment_status)
                VALUES (:id, :tenant, :subject, :reference, 'ACTIVE')
                """)
                .param("id", UUID.randomUUID())
                .param("tenant", tenantId)
                .param("subject", subject)
                .param("reference", reference)
                .update();
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    private Instant lastSeen(String sessionId) {
        return jdbc.sql("SELECT last_seen_at FROM iam.staff_sign_ins WHERE keycloak_session_id = :sid LIMIT 1")
                .param("sid", sessionId)
                .query((rs, n) ->
                        rs.getObject(1, java.time.OffsetDateTime.class).toInstant())
                .single();
    }

    private java.util.List<String> reasons(String sessionId) {
        return jdbc.sql(
                        "SELECT coalesce(end_reason, '<open>') FROM iam.staff_sign_ins WHERE keycloak_session_id = :sid")
                .param("sid", sessionId)
                .query(String.class)
                .list();
    }

    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
