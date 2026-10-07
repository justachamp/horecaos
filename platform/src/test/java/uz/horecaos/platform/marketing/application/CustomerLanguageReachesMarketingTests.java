package uz.horecaos.platform.marketing.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.customers.application.CustomerProfileService;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerStore;
import uz.horecaos.platform.iam.infrastructure.protection.DataEncryptionKeyProvider;
import uz.horecaos.platform.iam.infrastructure.protection.EnvelopeFieldProtection;
import uz.horecaos.platform.iam.infrastructure.secrets.EnvironmentSecretResolver;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCustomerMetricStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0149, the defect the record found on the day it was written: the storefront's language screen
 * writes the id of the language it offers, a bare {@code uz}, into
 * {@code customer.customer_accounts.preferred_locale}; the marketing metrics refresh copies that
 * column verbatim into {@code marketing.customer_metrics.preferred_locale}, whose constraint admits
 * {@code uz-Latn} and not {@code uz}. A customer who picked Uzbek handed that statement a value its
 * target refused, and no test met the two together because the metrics tests seed {@code uz-Latn}.
 *
 * <p>This one goes the whole way: through the profile service the storefront's endpoint calls, then
 * through the projection the sweep runs, against the migrated schema.
 */
class CustomerLanguageReachesMarketingTests {

    private static final Instant T0 = Instant.parse("2026-10-07T00:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private CustomerProfileService profiles;
    private CustomerMetricProjectionSweeper sweeper;
    private JdbcCustomerMetricStore metricStore;

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
        jdbc.sql("TRUNCATE TABLE marketing.metric_drift_observations, marketing.customer_metrics CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.brand_profiles, customer.customer_accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(T0, ZoneOffset.UTC);
        var objectMapper = JsonMapper.builder().build();
        var protection = new EnvelopeFieldProtection(new DataEncryptionKeyProvider(
                new EnvironmentSecretResolver(
                        Map.of("horecaos.secrets.data_encryption.platform.kek", "a-test-key-encryption-key")::get,
                        clock),
                "local"));
        profiles = new CustomerProfileService(
                new JdbcCustomerStore(jdbc),
                protection,
                objectMapper,
                clock,
                new JdbcAuditRecorder(jdbc, objectMapper));
        metricStore = new JdbcCustomerMetricStore(jdbc);
        sweeper = new CustomerMetricProjectionSweeper(
                metricStore, new CustomerMetricProjectionService(metricStore, clock));
    }

    @Test
    @DisplayName(
            "a customer who picks Uzbek by the id the storefront sends reads back uz-Latn, and the marketing refresh keeps it")
    void aBareUzFromTheStorefrontSurvivesTheMarketingCopy() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        UUID account = seed(tenant, brand);

        profiles.updateProfile(tenant, account, 1, "Ozod", "uz", "Asia/Tashkent");

        assertThat(storedLocale(account))
                .as("the platform's spelling, never the alias: a client that still sends uz is read as uz-Latn")
                .isEqualTo("uz-Latn");
        assertThat(sweeper.runOnce().brandsSwept()).isEqualTo(1);
        assertThat(metricStore.find(tenant, brand, account))
                .as("the statement that used to be refused by ck_customer_metrics_locale")
                .hasValueSatisfying(row -> assertThat(row.preferredLocale()).isEqualTo("uz-Latn"));
    }

    @Test
    @DisplayName(
            "any casing of the tag is the same language, and a language the registry does not know is kept as the customer sent it")
    void casingNormalisesAndAnUnknownLanguageIsKept() {
        UUID tenant = UUID.randomUUID();
        UUID brand = UUID.randomUUID();
        UUID account = seed(tenant, brand);

        profiles.updateProfile(tenant, account, 1, null, "UZ-LATN", null);
        assertThat(storedLocale(account)).isEqualTo("uz-Latn");

        profiles.updateProfile(tenant, account, 2, null, "kk", null);
        assertThat(storedLocale(account))
                .as("a preference for a language the platform does not send in yet is still the customer's preference;"
                        + " the notification path falls back for it")
                .isEqualTo("kk");
        assertThat(sweeper.runOnce().brandsSwept()).isEqualTo(1);
        assertThat(metricStore.find(tenant, brand, account))
                .hasValueSatisfying(row -> assertThat(row.preferredLocale()).isEqualTo("kk"));
    }

    // ------------------------------------------------------------- fixtures

    private UUID seed(UUID tenant, UUID brand) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenant).param("slug", "language-" + tenant).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status)
                VALUES (:id, :tenantId, 'LANG', 'lang', 'Pilot brand', 'ACTIVE')
                """).param("id", brand).param("tenantId", tenant).update();
        UUID account = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, created_at)
                VALUES (:id, :tenantId, 'ACTIVE', :now)
                """)
                .param("id", account)
                .param("tenantId", tenant)
                .param("now", java.time.OffsetDateTime.ofInstant(T0, ZoneOffset.UTC))
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

    private String storedLocale(UUID account) {
        return jdbc.sql("SELECT preferred_locale FROM customer.customer_accounts WHERE id = :id")
                .param("id", account)
                .query(String.class)
                .single();
    }
}
