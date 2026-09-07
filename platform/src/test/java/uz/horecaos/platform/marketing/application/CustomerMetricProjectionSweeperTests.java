package uz.horecaos.platform.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCustomerMetricStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@link CustomerMetricProjectionSweeper} against a real PostgreSQL.
 *
 * <p>Unlike a retention or erasure sweep, the projection sweep carries no
 * age-based "due" predicate to prove a not-yet-due case against: {@code
 * CustomerMetricProjectionService#sweep} recomputes every brand on every tick,
 * by design (see that class's own doc — it is, today, the whole maintenance
 * path). What stands in for "leaves alone what is not due" here is worklist
 * scoping instead of a clock: a brand nobody has registered under never
 * appears in {@code JdbcCustomerMetricStore#brandsWithProfiles} and gets no
 * {@code marketing.customer_metrics} row, however many ticks pass.
 */
class CustomerMetricProjectionSweeperTests {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCustomerMetricStore metricStore;
    private CustomerMetricProjectionSweeper sweeper;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the projection sweep test");
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

        metricStore = new JdbcCustomerMetricStore(jdbc);
        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        CustomerMetricProjectionService projection = new CustomerMetricProjectionService(metricStore, clock);
        sweeper = new CustomerMetricProjectionSweeper(metricStore, projection);
    }

    @Test
    @DisplayName("the sweep builds the projection row for a registered brand, then repairs drift it finds")
    void sweepUpdatesWhatItShould() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        seedTenantAndBrand(tenant, brand, "sweep-pilot");
        UUID account = insertCustomer(tenant, brand, "ru");

        var first = sweeper.runOnce();
        assertThat(first.brandsSwept()).isEqualTo(1);
        assertThat(first.driftObservations())
                .as("an empty projection has nothing to disagree with yet")
                .isZero();
        assertThat(metricStore.find(tenant, brand, account)).isPresent();

        corrupt(tenant, brand, account);

        var second = sweeper.runOnce();
        assertThat(second.brandsSwept()).isEqualTo(1);
        assertThat(second.driftObservations())
                .as("the corrupted row disagrees with a fresh recomputation on all three metrics touched")
                .isEqualTo(3);
        assertThat(metricStore.find(tenant, brand, account)).hasValueSatisfying(row -> {
            assertThat(row.orderCount())
                    .as("recompute overwrites the corruption rather than leaving it standing")
                    .isZero();
            assertThat(row.netSpendMinor()).isZero();
        });
    }

    @Test
    @DisplayName("a brand nobody has registered under is left alone, however many ticks pass")
    void sweepLeavesAnUnregisteredBrandAlone() {
        UUID tenant = UUID.randomUUID();
        UUID registeredBrand = UUID.randomUUID();
        UUID emptyBrand = UUID.randomUUID();
        seedTenantAndBrand(tenant, registeredBrand, "with-customers");
        seedBrandOnly(tenant, emptyBrand, "without-customers");
        insertCustomer(tenant, registeredBrand, "ru");

        var firstPass = sweeper.runOnce();
        var secondPass = sweeper.runOnce();

        assertThat(firstPass.brandsSwept()).isEqualTo(1);
        assertThat(secondPass.brandsSwept())
                .as("a brand with no registered customer never joins the worklist, tick after tick")
                .isEqualTo(1);
        assertThat(countMetricRows(tenant, emptyBrand)).isZero();
        assertThat(countMetricRows(tenant, registeredBrand)).isEqualTo(1);
    }

    @Test
    @DisplayName("two tenants are swept independently, each seeing only its own customer")
    void sweepIsolatesTenants() {
        UUID tenantA = UUID.randomUUID();
        UUID brandA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        UUID brandB = UUID.randomUUID();
        seedTenantAndBrand(tenantA, brandA, "tenant-a");
        seedTenantAndBrand(tenantB, brandB, "tenant-b");
        UUID accountA = insertCustomer(tenantA, brandA, "ru");
        UUID accountB = insertCustomer(tenantB, brandB, "uz-Latn");

        var result = sweeper.runOnce();

        assertThat(result.brandsSwept()).isEqualTo(2);
        assertThat(metricStore.find(tenantA, brandA, accountA))
                .hasValueSatisfying(row -> assertThat(row.preferredLocale()).isEqualTo("ru"));
        assertThat(metricStore.find(tenantB, brandB, accountB))
                .hasValueSatisfying(row -> assertThat(row.preferredLocale()).isEqualTo("uz-Latn"));
        assertThat(countMetricRows(tenantA, brandA))
                .as("tenant A's brand sees exactly its own customer, not tenant B's")
                .isEqualTo(1);
        assertThat(countMetricRows(tenantB, brandB))
                .as("tenant B's brand sees exactly its own customer, not tenant A's")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------- fixtures

    private void seedTenantAndBrand(UUID tenant, UUID brand, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenant).param("slug", slug).update();
        seedBrandOnly(tenant, brand, slug);
    }

    private void seedBrandOnly(UUID tenant, UUID brand, String slug) {
        // code is unique per (tenant_id, code) (uq_brands_tenant_code): a slug-derived
        // code so two brands of the same tenant, as the isolation fixtures need, do
        // not collide the way two 'PILOT' literals would.
        String code = slug.toUpperCase(java.util.Locale.ROOT).replace('-', '_');
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, :code, :slug, 'Pilot brand', 'ACTIVE')
                """)
                .param("id", brand)
                .param("tenantId", tenant)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private UUID insertCustomer(UUID tenant, UUID brand, String locale) {
        UUID account = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, preferred_locale, created_at)
                VALUES (:id, :tenantId, 'ACTIVE', :locale, :now)
                """)
                .param("id", account)
                .param("tenantId", tenant)
                .param("locale", locale)
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

    /** Corrupts the projection the way a bad incremental fold would. */
    private void corrupt(UUID tenant, UUID brand, UUID account) {
        jdbc.sql("""
                UPDATE marketing.customer_metrics
                   SET order_count = 7, completed_order_count = 7, net_spend_minor = 99
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND customer_account_id = :accountId
                """)
                .param("tenantId", tenant)
                .param("brandId", brand)
                .param("accountId", account)
                .update();
    }

    private int countMetricRows(UUID tenant, UUID brand) {
        return jdbc.sql("""
                SELECT count(*) FROM marketing.customer_metrics WHERE tenant_id = :tenantId AND brand_id = :brandId
                """)
                .param("tenantId", tenant)
                .param("brandId", brand)
                .query(Integer.class)
                .single();
    }

    private void truncate() {
        jdbc.sql("TRUNCATE TABLE marketing.metric_drift_observations, marketing.customer_metrics CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.brand_profiles, customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
