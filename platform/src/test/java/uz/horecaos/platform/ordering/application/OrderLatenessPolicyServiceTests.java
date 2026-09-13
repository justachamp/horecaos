package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcPolicyResolver;

/**
 * The {@code ordering.lateness} document resolved through the real ADR 0030
 * mechanism (gap map rows {@code 1.1g}/{@code X.39}) — against the real
 * resolver and real SQL, matching {@code OrderAcceptancePolicyServiceTests}'
 * own reasoning: the point of ADR 0030 is one precedence implementation, so a
 * stub resolver would test the thing ADR 0030 replaced.
 *
 * <p>No authoring here: {@code OrderLatenessPolicyService} carries no {@code
 * author} method (wave P31's job), so an override is inserted directly, the
 * same way this suite's sibling inserts one for {@code ordering.acceptance}.
 */
class OrderLatenessPolicyServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac131101");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac131102");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac131104");
    private static final UUID SIBLING_LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac131105");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private OrderLatenessPolicyService service;

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
        jdbc.sql("TRUNCATE TABLE tenant.policy_current CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.policies CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        service = new OrderLatenessPolicyService(
                new JdbcPolicyResolver(jdbc, JsonMapper.builder().build()));
        insertHierarchy();
    }

    @Test
    void fallsBackToThePlatformDefaultPerFulfilmentModeWhenNothingIsAuthored() {
        OrderLatenessPolicyService.Effective effective = service.resolve(TENANT, BRAND, LOCATION);

        assertThat(effective.isPlatformDefault())
                .as("that the platform default applied is itself a fact worth carrying")
                .isTrue();
        assertThat(effective.policy()).isEqualTo(OrderLatenessPolicy.platformDefault());
    }

    @Test
    void aLocationOverrideWinsOverTheTenantDefault() {
        activate("TENANT", null, null, thresholdsSet(300, 0, 2700));
        activate("LOCATION", BRAND, LOCATION, thresholdsSet(120, 30, 1800));

        OrderLatenessPolicy atLocation =
                service.resolve(TENANT, BRAND, LOCATION).policy();
        assertThat(atLocation.delivery().atRiskBeforeSeconds()).isEqualTo(120);
        assertThat(atLocation.delivery().lateAfterSeconds()).isEqualTo(30);
        assertThat(atLocation.delivery().noPromiseFallbackSeconds()).isEqualTo(1800);

        OrderLatenessPolicy atSibling =
                service.resolve(TENANT, BRAND, SIBLING_LOCATION).policy();
        assertThat(atSibling.delivery().atRiskBeforeSeconds())
                .as("the override belongs to this location alone")
                .isEqualTo(300);
    }

    @Test
    void eachFulfilmentModeRoundTripsItsOwnNumbersThroughTheStoredDocument() {
        String document = """
                {"delivery":{"atRiskBeforeSeconds":300,"lateAfterSeconds":60,"noPromiseFallbackSeconds":2700},
                 "pickup":{"atRiskBeforeSeconds":180,"lateAfterSeconds":0,"noPromiseFallbackSeconds":1800},
                 "dineIn":{"atRiskBeforeSeconds":90,"lateAfterSeconds":0,"noPromiseFallbackSeconds":1200}}""";
        activate("TENANT", null, null, document);

        OrderLatenessPolicy resolved = service.resolve(TENANT, BRAND, LOCATION).policy();

        assertThat(resolved.delivery().lateAfterSeconds())
                .as("delivery alone carries the grace period")
                .isEqualTo(60);
        assertThat(resolved.pickup().atRiskBeforeSeconds()).isEqualTo(180);
        assertThat(resolved.dineIn().noPromiseFallbackSeconds()).isEqualTo(1200);
    }

    @Test
    void resolveAtReadsTheSameChainAtAnyScope() {
        activate("TENANT", null, null, thresholdsSet(300, 0, 2700));

        OrderLatenessPolicyService.Effective atTenant = service.resolveAt(ResourceScope.tenant(TENANT));

        assertThat(atTenant.isPlatformDefault()).isFalse();
        assertThat(atTenant.policy().pickup().atRiskBeforeSeconds()).isEqualTo(300);
    }

    private String thresholdsSet(int atRiskBefore, int lateAfter, int noPromiseFallback) {
        String single = "{\"atRiskBeforeSeconds\":%d,\"lateAfterSeconds\":%d,\"noPromiseFallbackSeconds\":%d}"
                .formatted(atRiskBefore, lateAfter, noPromiseFallback);
        return "{\"delivery\":%s,\"pickup\":%s,\"dineIn\":%s}".formatted(single, single, single);
    }

    private void activate(String scopeType, @Nullable UUID brandId, @Nullable UUID locationId, String document) {
        UUID id = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO tenant.policies (
                    id, key_code, scope_type, tenant_id, brand_id, location_id, version, status,
                    document, document_hash, valid_from, created_by)
                VALUES (:id, 'ordering.lateness', :scopeType, :tenantId, :brandId, :locationId,
                        1, 'ACTIVE', CAST(:document AS jsonb), :hash, now(), 'test')
                """)
                .param("id", id)
                .param("scopeType", scopeType)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("document", document)
                .param("hash", "%064x".formatted(BigInteger.valueOf(document.hashCode() & 0xFFFFFFFFL)))
                .update();

        jdbc.sql("""
                INSERT INTO tenant.policy_current (
                    key_code, scope_type, tenant_id, brand_id, location_id,
                    policy_id, policy_version, activated_by)
                VALUES ('ordering.lateness', :scopeType, :tenantId, :brandId, :locationId,
                        :id, 1, 'test')
                """)
                .param("scopeType", scopeType)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("id", id)
                .update();
    }

    private static String suffix(UUID id) {
        String text = id.toString().replace("-", "");
        return text.substring(text.length() - 6);
    }

    private void insertHierarchy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'tenant-lateness', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", BRAND)
                .param("tenantId", TENANT)
                .param("code", "B" + suffix(BRAND).toUpperCase(Locale.ROOT))
                .param("slug", "b-" + suffix(BRAND))
                .update();
        for (UUID location : new UUID[] {LOCATION, SIBLING_LOCATION}) {
            jdbc.sql("""
                    INSERT INTO tenant.locations
                        (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                    VALUES (:id, :tenantId, :brandId, :code, :slug, 'Location', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", location)
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .param("code", "L" + suffix(location).toUpperCase(Locale.ROOT))
                    .param("slug", "l-" + suffix(location))
                    .update();
        }
    }
}
