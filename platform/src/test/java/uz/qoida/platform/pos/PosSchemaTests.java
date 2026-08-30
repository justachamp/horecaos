package uz.qoida.platform.pos;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;

import uz.qoida.platform.support.TestDatabase;

/**
 * The two rules V0036 puts in the database rather than in an application layer
 * (ADR 0011).
 *
 * <p>Both could have been checks in a service. Both are here because a check in a
 * service is a check one migration, one fixture, or one support script at two in
 * the morning can route around — and the consequences are a screen that lies to a
 * manager and a kitchen that cooks a dinner twice.
 */
class PosSchemaTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121801");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121802");
    private static final UUID INSTALLATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121803");
    private static final UUID BINDING = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac121804");

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
        jdbcUrl = db.jdbcUrl();
        username = db.username();
        password = db.password();
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

        jdbc.sql("DELETE FROM integration.binding_capabilities WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT).update();
        jdbc.sql("DELETE FROM integration.bindings WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT).update();
        jdbc.sql("DELETE FROM integration.installations WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT).update();
        jdbc.sql("DELETE FROM tenant.brands WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT).update();
        jdbc.sql("DELETE FROM tenant.tenants WHERE id = :tenantId")
                .param("tenantId", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone,
                     status, version)
                VALUES (:id, 'pos-schema-test', 'Legal', 'POS schema test', 'UZS',
                        'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands
                    (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'POS_BRAND', 'pos-brand', 'POS brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code,
                     display_name, status)
                VALUES (:id, :tenantId, 'POS', 'clopos', 'clopos-open-api-v2', 'Pilot brand', 'ACTIVE')
                """).param("id", INSTALLATION).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.bindings
                    (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'SUSPENDED')
                """)
                .param("id", BINDING).param("tenantId", TENANT)
                .param("installationId", INSTALLATION).param("brandId", BRAND)
                .update();
    }

    @Test
    @DisplayName("the environment catalogue carries the one approved Clopos host")
    void theHostIsPlatformOwnedAndNotTenantSupplied() {
        String baseUrl = jdbc.sql("""
                SELECT base_url FROM integration.provider_environments
                 WHERE code = 'clopos-open-api-v2'
                """).query(String.class).single();

        assertThat(baseUrl).isEqualTo("https://integrations.clopos.com/open-api/v2");
    }

    @Test
    @DisplayName("a capability the provider does not have cannot be enabled on a binding")
    void preparationStatusIsUnconfigurable() {
        assertThatThrownBy(() -> enable("PREPARATION_STATUS", true))
                .as("the only preparation-shaped field on this vendor is one we write, so a "
                        + "screen fed from it would be reporting our own writes to a manager")
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("does not support");
    }

    @Test
    @DisplayName("a capability nobody has assessed is refused rather than assumed")
    void anUnassessedCapabilityIsNotAPermissiveDefault() {
        assertThatThrownBy(() -> enable("SOMETHING_NOBODY_ASSESSED", true))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("no assessed support");
    }

    @Test
    @DisplayName("a supported capability is enabled without complaint")
    void theRuleDoesNotBlockTheOrdinaryCase() {
        enable("ORDER_EXPORT", true);

        assertThat(jdbc.sql("""
                SELECT count(*) FROM integration.binding_capabilities
                 WHERE binding_id = :bindingId AND capability_code = 'ORDER_EXPORT'
                """).param("bindingId", BINDING).query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a partial capability is configurable, because refusing it would leave no path")
    void partialIsUsable() {
        enable("ORDER_CANCELLATION", true);

        assertThat(jdbc.sql("""
                SELECT count(*) FROM integration.binding_capabilities
                 WHERE binding_id = :bindingId AND capability_code = 'ORDER_CANCELLATION'
                """).param("bindingId", BINDING).query(Integer.class).single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("the rule does not touch a delivery or payment binding")
    void nonPosCategoriesPassThroughUntouched() {
        UUID paymentInstallation = UUID.randomUUID();
        UUID paymentBinding = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('test-payment-env', 'PAYMENT', 'click', 'https://example.test', false,
                        'example.test')
                ON CONFLICT (code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code,
                     display_name, status)
                VALUES (:id, :tenantId, 'PAYMENT', 'click', 'test-payment-env', 'Click', 'ACTIVE')
                """).param("id", paymentInstallation).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.bindings
                    (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :tenantId, :installationId, :brandId, 'SUSPENDED')
                """)
                .param("id", paymentBinding).param("tenantId", TENANT)
                .param("installationId", paymentInstallation).param("brandId", BRAND)
                .update();

        jdbc.sql("""
                INSERT INTO integration.binding_capabilities
                    (binding_id, tenant_id, capability_code, enabled, is_primary)
                VALUES (:bindingId, :tenantId, 'CollectPayment', true, false)
                """)
                .param("bindingId", paymentBinding).param("tenantId", TENANT)
                .update();

        assertThat(jdbc.sql("""
                SELECT count(*) FROM integration.binding_capabilities WHERE binding_id = :bindingId
                """).param("bindingId", paymentBinding).query(Integer.class).single())
                .as("the generic ADR 0026 table carries delivery and payment codes too, and the "
                        + "POS rule must let every one of them through")
                .isEqualTo(1);

        jdbc.sql("DELETE FROM integration.binding_capabilities WHERE binding_id = :id")
                .param("id", paymentBinding).update();
        jdbc.sql("DELETE FROM integration.bindings WHERE id = :id").param("id", paymentBinding).update();
        jdbc.sql("DELETE FROM integration.installations WHERE id = :id")
                .param("id", paymentInstallation).update();
    }

    @Test
    @DisplayName("one order has one export, whatever asks for a second")
    void theUniquenessIsTheOnlyIdempotencyThisIntegrationHas() {
        // The order row this export would reference does not exist in this
        // fixture, so the uniqueness is proved directly on the constraint rather
        // than through a full ordering fixture. What is asserted is the shape:
        // (tenant_id, order_id) is unique, so a duplicated command converges on
        // one row instead of producing a second sendable export.
        String definition = jdbc.sql("""
                SELECT pg_get_constraintdef(oid)
                  FROM pg_constraint
                 WHERE conname = 'uq_pos_export_per_order'
                """).query(String.class).single();

        assertThat(definition).isEqualTo("UNIQUE (tenant_id, order_id)");
    }

    @Test
    @DisplayName("an export that claims to have landed must name the order it landed as")
    void aLandedExportCannotBeAnonymous() {
        String definition = jdbc.sql("""
                SELECT pg_get_constraintdef(oid)
                  FROM pg_constraint
                 WHERE conname = 'ck_pos_export_landed_names_order'
                """).query(String.class).single();

        assertThat(definition)
                .contains("ACCEPTED")
                .contains("RESOLVED_LANDED")
                .contains("external_order_id IS NOT NULL");
    }

    @Test
    @DisplayName("a resolution states its kind, its time, and its author or none of the three")
    void pairCompletenessIsStatedAsAnEqualityOfNullness() {
        String definition = jdbc.sql("""
                SELECT pg_get_constraintdef(oid)
                  FROM pg_constraint
                 WHERE conname = 'ck_pos_export_resolution_complete'
                """).query(String.class).single();

        assertThat(definition)
                .as("the disjunctive form leaves a three-valued hole a single null slips through")
                .contains("=");
    }

    @Test
    @DisplayName("Clopos's assessed ceiling records preparation status as unsupported, with a reason")
    void theCeilingCarriesItsReasoning() {
        var row = jdbc.sql("""
                SELECT support_level, rationale FROM integration.pos_provider_capabilities
                 WHERE provider_type = 'clopos' AND capability_code = 'PREPARATION_STATUS'
                """)
                .query((rs, n) -> rs.getString("support_level") + "|" + rs.getString("rationale"))
                .single();

        assertThat(row).startsWith("UNSUPPORTED|");
        assertThat(row).contains("PATCH");
    }

    private void enable(String capabilityCode, boolean enabled) {
        try {
            jdbc.sql("""
                    INSERT INTO integration.binding_capabilities
                        (binding_id, tenant_id, capability_code, enabled, is_primary)
                    VALUES (:bindingId, :tenantId, :capability, :enabled, false)
                    """)
                    .param("bindingId", BINDING)
                    .param("tenantId", TENANT)
                    .param("capability", capabilityCode)
                    .param("enabled", enabled)
                    .update();
        } catch (DataIntegrityViolationException violation) {
            // Re-thrown unchanged; the assertion is on the message the database
            // produced, because that message is what an operator will be shown.
            throw violation;
        }
    }
}
