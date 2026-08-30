package uz.qoida.platform.tenancy.infrastructure.persistence;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;

import uz.qoida.platform.support.TestDatabase;
import uz.qoida.platform.tenancy.api.BrandId;
import uz.qoida.platform.tenancy.api.ConfigurationKey;
import uz.qoida.platform.tenancy.api.LocationId;
import uz.qoida.platform.iam.api.ResourceScope;
import uz.qoida.platform.iam.api.ResourceScope.ScopeType;
import uz.qoida.platform.tenancy.api.TenantId;

/**
 * ADR 0030 at the SQL boundary: precedence over real rows, and the ancestry and
 * shape constraints that stop a scoped value referencing another tenant.
 */
class JdbcConfigurationResolverTests {

    private static final ConfigurationKey<Integer> TIMEOUT =
            ConfigurationKey.of("ordering.approval_timeout_seconds", Integer.class)
                    .defaultValue(600)
                    .build();

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120301");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120302");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120303");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac120401");

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;
    private JdbcConfigurationResolver resolver;

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
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        resolver = new JdbcConfigurationResolver(jdbc);

        insertTenantHierarchy(TENANT, "tenant-cfg", BRAND, "BRAND_CFG", "brand-cfg", LOCATION, "LOC_CFG", "loc-cfg");
        insertTenantHierarchy(OTHER_TENANT, "tenant-other", null, null, null, null, null, null);
    }

    @Test
    void resolvesTheMostSpecificStoredValue() {
        insertInteger(ScopeType.PLATFORM, null, null, null, 100);
        insertInteger(ScopeType.TENANT, TENANT, null, null, 200);
        insertInteger(ScopeType.LOCATION, TENANT, BRAND, LOCATION, 400);

        assertThat(resolver.resolve(TIMEOUT, locationScope()).value()).isEqualTo(400);
    }

    @Test
    void inheritsFromTheTenantWhenNothingNarrowerIsSet() {
        insertInteger(ScopeType.PLATFORM, null, null, null, 100);
        insertInteger(ScopeType.TENANT, TENANT, null, null, 200);

        assertThat(resolver.resolve(TIMEOUT, locationScope()).value()).isEqualTo(200);
        assertThat(resolver.explain(TIMEOUT, locationScope()).winningScope()).isEqualTo(ScopeType.TENANT);
    }

    @Test
    void fallsBackToTheCodeDefaultWhenNothingIsStored() {
        assertThat(resolver.resolve(TIMEOUT, locationScope()).value()).isEqualTo(600);
        assertThat(resolver.resolve(TIMEOUT, locationScope()).cameFromDefault()).isTrue();
    }

    @Test
    void anotherTenantsValueIsNeverVisible() {
        insertInteger(ScopeType.PLATFORM, null, null, null, 100);
        insertInteger(ScopeType.TENANT, OTHER_TENANT, null, null, 999);

        assertThat(resolver.resolve(TIMEOUT, locationScope()).value())
                .as("a value set for another tenant must not leak into this resolution")
                .isEqualTo(100);
    }

    @Test
    void anExplicitNullContinuesResolution() {
        insertInteger(ScopeType.TENANT, TENANT, null, null, 200);
        jdbc.sql("""
                INSERT INTO tenant.configuration_values
                    (id, key_code, scope_type, tenant_id, brand_id, location_id,
                     value_type, is_explicit_null, set_by)
                VALUES (:id, :keyCode, 'LOCATION', :tenantId, :brandId, :locationId,
                        'INTEGER', true, 'test')
                """)
                .param("id", UUID.randomUUID())
                .param("keyCode", TIMEOUT.code())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .update();

        assertThat(resolver.resolve(TIMEOUT, locationScope()).value()).isEqualTo(200);
    }

    @Test
    void aLocationValueCannotReferenceAnotherTenantsLocation() {
        assertThatThrownBy(() -> insertInteger(ScopeType.LOCATION, OTHER_TENANT, BRAND, LOCATION, 1))
                .as("composite ancestry keys must reject a cross-tenant scope")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aScopeShapeThatContradictsItsTypeIsRejected() {
        assertThatThrownBy(() -> insertInteger(ScopeType.TENANT, TENANT, BRAND, null, 1))
                .as("a TENANT row carrying a brand identifier is malformed")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void twoValuesForOneKeyAndScopeCannotCoexist() {
        insertInteger(ScopeType.TENANT, TENANT, null, null, 200);

        assertThatThrownBy(() -> insertInteger(ScopeType.TENANT, TENANT, null, null, 300))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void anExplicitNullCarryingAValueIsRejected() {
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO tenant.configuration_values
                    (id, key_code, scope_type, tenant_id, value_type, integer_value, is_explicit_null, set_by)
                VALUES (:id, :keyCode, 'TENANT', :tenantId, 'INTEGER', 5, true, 'test')
                """)
                .param("id", UUID.randomUUID())
                .param("keyCode", TIMEOUT.code())
                .param("tenantId", TENANT)
                .update())
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ResourceScope locationScope() {
        return ResourceScope.location(TENANT, BRAND, LOCATION);
    }

    private void insertInteger(ScopeType scopeType, UUID tenantId, UUID brandId, UUID locationId, int value) {
        jdbc.sql("""
                INSERT INTO tenant.configuration_values
                    (id, key_code, scope_type, tenant_id, brand_id, location_id,
                     value_type, integer_value, set_by)
                VALUES (:id, :keyCode, :scopeType, :tenantId, :brandId, :locationId,
                        'INTEGER', :value, 'test')
                """)
                .param("id", UUID.randomUUID())
                .param("keyCode", TIMEOUT.code())
                .param("scopeType", scopeType.name())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("value", value)
                .update();
    }

    private void insertTenantHierarchy(
            UUID tenantId, String tenantSlug,
            UUID brandId, String brandCode, String brandSlug,
            UUID locationId, String locationCode, String locationSlug) {

        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", tenantSlug)
                .update();

        if (brandId == null) {
            return;
        }
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("code", brandCode)
                .param("slug", brandSlug)
                .update();

        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, 'Location', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", locationCode)
                .param("slug", locationSlug)
                .update();
    }
}
