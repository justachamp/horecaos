package uz.horecaos.platform.inventory.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * Fix4 kitchen-catalog finding P16/P47/P45: {@code InventoryController} had
 * zero MockMvc/HTTP-level test coverage at all — {@code
 * InventoryBulkAvailabilityServiceTests} calls the service directly and never
 * touches {@code @RequiresCapability}. This covers the wave's new bulk
 * endpoint the same way {@code CatalogAuthoringControllerEndpointTests}
 * covers its siblings: a caller with no grant is refused
 * 403/INSUFFICIENT_CAPABILITY naming {@code INVENTORY_AVAILABILITY_MANAGE},
 * and a caller with the real grant reaches the real logic.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9f20-3000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f20-3000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9f20-3000-7000-8000-0000000000c1");
    private static final UUID PRODUCT = UUID.fromString("018f9f20-3000-7000-8000-0000000000d1");
    private static final UUID VARIANT = UUID.fromString("018f9f20-3000-7000-8000-0000000000d2");

    private static final String OWNER = "inventory-endpoint-owner";
    private static final String NO_GRANT = "inventory-endpoint-no-grant";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE inventory.reservation_lines, inventory.reservations, "
                        + "inventory.movements, inventory.positions, inventory.stock_items CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.variants, catalog.products CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertFixtures();
        grant(OWNER, PlatformRole.TENANT_OWNER);
    }

    @Test
    void bulkAvailabilityRefusedWithoutInventoryAvailabilityManage() throws Exception {
        MvcResult refused = mvc.perform(post(bulkAvailabilityPath())
                        .with(tokenFor(NO_GRANT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"variantIds":["%s"],"available":false,"reasonCode":"OUT_OF_STOCK"}
                                """.formatted(VARIANT)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.INVENTORY_AVAILABILITY_MANAGE.code());
    }

    @Test
    void bulkAvailabilityRejectsFreeTextReasonCodes() throws Exception {
        // Finding P16: reasonCode is a short enumerated code, never the free
        // text an operator typed — @Pattern("^[A-Z_]{1,48}$") refuses it
        // before the request ever reaches the service or the audit trail.
        MvcResult rejected = mvc.perform(post(bulkAvailabilityPath())
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "bulk-bad-reason")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"variantIds":["%s"],"available":false,"reasonCode":"the fridge broke"}
                                """.formatted(VARIANT)))
                .andReturn();

        assertThat(rejected.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void bulkAvailabilityAppliesWithTheRealGrant() throws Exception {
        mvc.perform(post("/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION
                        + "/inventory/stock-items")
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "list-variant")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"variantId":"%s","trackingMode":"BINARY"}
                        """.formatted(VARIANT)));

        MvcResult applied = mvc.perform(post(bulkAvailabilityPath())
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "bulk-apply")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"variantIds":["%s"],"available":false,"reasonCode":"OUT_OF_STOCK"}
                                """.formatted(VARIANT)))
                .andReturn();

        assertThat(applied.getResponse().getStatus()).isEqualTo(200);
        assertThat(applied.getResponse().getContentAsString())
                .contains("\"appliedCount\":1")
                .contains("\"status\":\"APPLIED\"");
    }

    private static String bulkAvailabilityPath() {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION
                + "/inventory/variants/bulk-availability";
    }

    private void insertFixtures() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'inventory-controller-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CHI', 'chilonzor', 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, 'BURGER', 'ACTIVE')
                """)
                .param("id", PRODUCT)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, is_default, status)
                VALUES (:id, :tenantId, :brandId, :productId, true, 'ACTIVE')
                """)
                .param("id", VARIANT)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("productId", PRODUCT)
                .update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'inventory controller endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubIssuer {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }
    }
}
