package uz.horecaos.platform.integration.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0106, gap-map row 10.8e: the one public, unauthenticated read a
 * storefront calls to decide which analytics identifiers to inject into a
 * customer's browser.
 *
 * <p>The interesting behaviour is entirely in the SQL: three separate
 * `ANALYTICS` installations (GTM, GA4, Search Console), each carrying one
 * non-secret field, merged into a single flat response by an active binding
 * to the same brand. A partial configuration (only GTM installed) must
 * answer with the other two fields null, not fail; a suspended binding or a
 * DRAFT installation must not contribute at all.
 */
class StorefrontAnalyticsConfigControllerTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac123601");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac123602");
    private static final UUID OTHER_BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac123603");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private StorefrontAnalyticsConfigController controller;

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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE integration.bindings CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.installations CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'storefront-analytics-test', 'Test', 'Test', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'TEST', 'test', 'Test', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'OTHER', 'other', 'Other', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("tenantId", TENANT).update();

        controller = new StorefrontAnalyticsConfigController(jdbc);
    }

    @Test
    void noBindingAtAllAnswersEveryFieldNull() {
        ResponseEntity<StorefrontAnalyticsConfigController.AnalyticsConfigResponse> response =
                controller.analyticsConfig(TENANT, BRAND);

        var body = Objects.requireNonNull(response.getBody());
        assertThat(body.gtmContainerId()).isNull();
        assertThat(body.ga4MeasurementId()).isNull();
        assertThat(body.searchConsoleVerificationToken()).isNull();
    }

    @Test
    void aPartialConfigurationAnswersOnlyWhatIsActuallyBound() {
        bindAnalyticsInstallation("GOOGLE_TAG_MANAGER", "gtmContainerId", "GTM-ABC1234", BRAND, "ACTIVE", "ACTIVE");

        var body =
                Objects.requireNonNull(controller.analyticsConfig(TENANT, BRAND).getBody());

        assertThat(body.gtmContainerId()).isEqualTo("GTM-ABC1234");
        assertThat(body.ga4MeasurementId()).isNull();
        assertThat(body.searchConsoleVerificationToken()).isNull();
    }

    @Test
    void mergesAllThreeAnalyticsInstallationsIntoOneResponse() {
        bindAnalyticsInstallation("GOOGLE_TAG_MANAGER", "gtmContainerId", "GTM-ABC1234", BRAND, "ACTIVE", "ACTIVE");
        bindAnalyticsInstallation("GOOGLE_ANALYTICS_4", "ga4MeasurementId", "G-XYZ987", BRAND, "ACTIVE", "ACTIVE");
        bindAnalyticsInstallation(
                "GOOGLE_SEARCH_CONSOLE", "searchConsoleVerificationToken", "verify-token-1", BRAND, "ACTIVE", "ACTIVE");

        var body =
                Objects.requireNonNull(controller.analyticsConfig(TENANT, BRAND).getBody());

        assertThat(body.gtmContainerId()).isEqualTo("GTM-ABC1234");
        assertThat(body.ga4MeasurementId()).isEqualTo("G-XYZ987");
        assertThat(body.searchConsoleVerificationToken()).isEqualTo("verify-token-1");
    }

    @Test
    void aSuspendedBindingContributesNothing() {
        bindAnalyticsInstallation(
                "GOOGLE_TAG_MANAGER", "gtmContainerId", "GTM-SUSPENDED", BRAND, "ACTIVE", "SUSPENDED");

        var body =
                Objects.requireNonNull(controller.analyticsConfig(TENANT, BRAND).getBody());

        assertThat(body.gtmContainerId()).isNull();
    }

    @Test
    void aDraftInstallationContributesNothingEvenIfSomehowBound() {
        bindAnalyticsInstallation("GOOGLE_TAG_MANAGER", "gtmContainerId", "GTM-DRAFT", BRAND, "DRAFT", "ACTIVE");

        var body =
                Objects.requireNonNull(controller.analyticsConfig(TENANT, BRAND).getBody());

        assertThat(body.gtmContainerId()).isNull();
    }

    @Test
    void oneBrandsAnalyticsNeverLeaksIntoAnothers() {
        bindAnalyticsInstallation("GOOGLE_TAG_MANAGER", "gtmContainerId", "GTM-FOR-BRAND", BRAND, "ACTIVE", "ACTIVE");

        var body = Objects.requireNonNull(
                controller.analyticsConfig(TENANT, OTHER_BRAND).getBody());

        assertThat(body.gtmContainerId()).isNull();
    }

    private void bindAnalyticsInstallation(
            String providerType,
            String configKey,
            String configValue,
            UUID brand,
            String installationStatus,
            String bindingStatus) {

        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES (:code, 'ANALYTICS', :providerType, 'https://example.test', false, '')
                ON CONFLICT (code) DO NOTHING
                """)
                .param("code", "test-" + providerType)
                .param("providerType", providerType)
                .update();

        UUID installationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code,
                     display_name, status, non_sensitive_config)
                VALUES (:id, :tenantId, 'ANALYTICS', :providerType, :env, :name, :status,
                        jsonb_build_object(:key, :value))
                """)
                .param("id", installationId)
                .param("tenantId", TENANT)
                .param("providerType", providerType)
                .param("env", "test-" + providerType)
                .param("name", "Test " + providerType)
                .param("status", installationStatus)
                .param("key", configKey)
                .param("value", configValue)
                .update();

        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, :status)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("installationId", installationId)
                .param("brandId", brand)
                .param("status", bindingStatus)
                .update();
    }
}
