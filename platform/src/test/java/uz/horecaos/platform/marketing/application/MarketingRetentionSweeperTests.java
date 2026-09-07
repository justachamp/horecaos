package uz.horecaos.platform.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.customers.application.ConsentService;
import uz.horecaos.platform.customers.application.RecipientContactService;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAudienceStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAudienceStore.CandidateRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCustomerMetricStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@link MarketingRetentionSweeper} against a real PostgreSQL and a clock that
 * actually moves. CLAUDE.md's own rule for this genre: a lifetime or sweep
 * asserted without advancing the clock is asserted against an instant, not a
 * duration — every snapshot here is written through the real store calls with
 * an explicit {@code completed_at}, and the sweep's own read of "now" comes
 * from a separate {@link MutableClock} that is genuinely advanced before a
 * pass runs, never inserted pre-aged by hand.
 */
class MarketingRetentionSweeperTests {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final String CHANNEL = "SMS";
    private static final String PURPOSE = "MARKETING_PROMOTIONS";

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private MutableClock clock;
    private JdbcAudienceStore audienceStore;
    private AudienceService audienceService;
    private MarketingRetentionSweeper sweeper;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the retention sweep test");
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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        truncate();

        clock = new MutableClock(T0);
        SecretResolver secrets = new EnvironmentSecretResolver(
                Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key")::get, clock);
        FieldProtection protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(secrets, "local"));

        JdbcCustomerStore customerStore = new JdbcCustomerStore(jdbc);
        JdbcCustomerMetricStore metricStore = new JdbcCustomerMetricStore(jdbc);
        JdbcEngagementStore engagementStore = new JdbcEngagementStore(jdbc);
        audienceStore = new JdbcAudienceStore(jdbc, JsonMapper.builder().build());

        ConsentService consent = new ConsentService(customerStore, clock);
        RecipientContactService contacts = new RecipientContactService(customerStore, protection);
        MarketingEligibility eligibility = new MarketingEligibility(consent, contacts, engagementStore);

        audienceService = new AudienceService(audienceStore, metricStore, engagementStore, eligibility, fact -> {}, clock);
        sweeper = new MarketingRetentionSweeper(audienceStore, audienceService, clock, 24, 200);
    }

    @Test
    @DisplayName("a snapshot twenty-five months past its send is purged; the header and its counts survive")
    void dueSnapshotsArePurgedAndTheHeaderSurvives() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        seedTenantAndBrand(tenant, brand, "retention-pilot");
        UUID account = insertCustomer(tenant, brand);
        UUID snapshot = buildReadySnapshot(tenant, brand, List.of(account), T0);

        clock.advance(Duration.ofDays(31 * 25)); // 25 months, comfortably past the 24-month window

        var result = sweeper.runOnce();

        assertThat(result.snapshotsDue()).isEqualTo(1);
        assertThat(result.membersPurged()).isEqualTo(1);
        assertThat(memberCount(snapshot)).isZero();
        assertThat(audienceStore.findSnapshot(tenant, snapshot)).hasValueSatisfying(header -> {
            assertThat(header.memberCount()).isEqualTo(1);
            assertThat(header.candidateCount()).isEqualTo(1);
            assertThat(header.membersPurgedAt()).isNotNull();
        });
    }

    @Test
    @DisplayName("a snapshot inside its retention window is left alone, clock advanced but not past the threshold")
    void snapshotsNotYetDueAreLeftAlone() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        seedTenantAndBrand(tenant, brand, "retention-pilot");
        UUID account = insertCustomer(tenant, brand);
        UUID snapshot = buildReadySnapshot(tenant, brand, List.of(account), T0);

        clock.advance(Duration.ofDays(31 * 20)); // 20 months: the clock moved, but not past 24

        var result = sweeper.runOnce();

        assertThat(result.snapshotsDue()).isZero();
        assertThat(result.membersPurged()).isZero();
        assertThat(memberCount(snapshot))
                .as("a snapshot inside its window must survive with its membership intact")
                .isEqualTo(1);
        assertThat(audienceStore.findSnapshot(tenant, snapshot)).hasValueSatisfying(header ->
                assertThat(header.membersPurgedAt()).isNull());
    }

    @Test
    @DisplayName("two tenants' due snapshots are each purged from their own membership, independently")
    void retentionIsolatesTenants() {
        UUID tenantA = UUID.randomUUID();
        UUID brandA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID brandB = UUID.randomUUID();
        seedTenantAndBrand(tenantA, brandA, "tenant-a");
        seedTenantAndBrand(tenantB, brandB, "tenant-b");
        UUID accountA = insertCustomer(tenantA, brandA);
        UUID accountB = insertCustomer(tenantB, brandB);
        UUID snapshotA = buildReadySnapshot(tenantA, brandA, List.of(accountA), T0);
        UUID snapshotB = buildReadySnapshot(tenantB, brandB, List.of(accountB), T0);

        clock.advance(Duration.ofDays(31 * 25));

        var result = sweeper.runOnce();

        assertThat(result.snapshotsDue()).isEqualTo(2);
        assertThat(result.membersPurged()).isEqualTo(2);
        assertThat(memberCount(snapshotA))
                .as("tenant A's snapshot is purged")
                .isZero();
        assertThat(memberCount(snapshotB))
                .as("tenant B's snapshot is purged independently of tenant A's")
                .isZero();
        assertThat(audienceStore.findSnapshot(tenantA, snapshotA))
                .hasValueSatisfying(header -> assertThat(header.membersPurgedAt()).isNotNull());
        assertThat(audienceStore.findSnapshot(tenantB, snapshotB))
                .hasValueSatisfying(header -> assertThat(header.membersPurgedAt()).isNotNull());
    }

    @Test
    @DisplayName("the batch limit holds: one pass purges at most batchSize snapshots")
    void theBatchLimitHolds() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        seedTenantAndBrand(tenant, brand, "retention-batch");
        UUID first = insertCustomer(tenant, brand);
        UUID second = insertCustomer(tenant, brand);
        UUID third = insertCustomer(tenant, brand);
        UUID snapshot1 = buildReadySnapshot(tenant, brand, List.of(first), T0);
        UUID snapshot2 = buildReadySnapshot(tenant, brand, List.of(second), T0);
        UUID snapshot3 = buildReadySnapshot(tenant, brand, List.of(third), T0);
        clock.advance(Duration.ofDays(31 * 25));

        MarketingRetentionSweeper limited = new MarketingRetentionSweeper(audienceStore, audienceService, clock, 24, 2);

        var firstPass = limited.runOnce();
        assertThat(firstPass.snapshotsDue())
                .as("three are due, the batch is limited to two")
                .isEqualTo(2);

        var secondPass = limited.runOnce();
        assertThat(secondPass.snapshotsDue()).isEqualTo(1);

        assertThat(memberCount(snapshot1) + memberCount(snapshot2) + memberCount(snapshot3))
                .as("all three are purged by the second pass, regardless of which two the first claimed")
                .isZero();
    }

    // ------------------------------------------------------------- fixtures

    private UUID buildReadySnapshot(UUID tenant, UUID brand, List<UUID> members, Instant completedAt) {
        UUID audienceId = UUID.randomUUID();
        audienceStore.insertAudience(
                audienceId, tenant, brand, "Retention fixture " + UUID.randomUUID(), null, UUID.randomUUID(), completedAt);

        UUID snapshotId = UUID.randomUUID();
        audienceStore.openSnapshot(
                snapshotId, tenant, brand, audienceId, 1, CHANNEL, PURPOSE, null, 1, UUID.randomUUID(), completedAt);

        for (UUID account : members) {
            audienceStore.recordMember(
                    snapshotId,
                    tenant,
                    account,
                    null,
                    // Only preferredLocale, completedOrderCount, netSpendMinor, and
                    // daysSinceLastOrder reach recordMember's INSERT; accountStatus and
                    // mergedIntoAccountId are read elsewhere (isReachableAccount), so a
                    // placeholder here is fine — 0 and a nil UUID rather than null,
                    // since only anonymizedAt is declared @Nullable on this record.
                    new CandidateRow(account, "ru", 0, 0L, 0, "ACTIVE", new UUID(0L, 0L), null));
        }
        audienceStore.completeSnapshot(tenant, snapshotId, members.size(), members.size(), completedAt);
        return snapshotId;
    }

    private int memberCount(UUID snapshotId) {
        return jdbc.sql("SELECT count(*) FROM marketing.audience_snapshot_members WHERE snapshot_id = :id")
                .param("id", snapshotId)
                .query(Integer.class)
                .single();
    }

    private void seedTenantAndBrand(UUID tenant, UUID brand, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenant)
                .param("slug", slug)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'PILOT', :slug, 'Pilot brand', 'ACTIVE')
                """)
                .param("id", brand)
                .param("tenantId", tenant)
                .param("slug", slug)
                .update();
    }

    private UUID insertCustomer(UUID tenant, UUID brand) {
        UUID account = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, preferred_locale, created_at)
                VALUES (:id, :tenantId, 'ACTIVE', 'ru', :now)
                """)
                .param("id", account)
                .param("tenantId", tenant)
                .param("now", utc(T0))
                .update();
        jdbc.sql("""
                INSERT INTO customer.brand_profiles (id, tenant_id, brand_id, customer_account_id)
                VALUES (:id, :tenantId, :brandId, :accountId)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenant)
                .param("brandId", brand)
                .param("accountId", account)
                .update();
        return account;
    }

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE marketing.audience_snapshot_members, marketing.audience_snapshots, "
                        + "marketing.audience_predicates, marketing.audiences CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.brand_profiles, customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    // ---------------------------------------------------------------- fakes

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
