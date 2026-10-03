package uz.horecaos.platform.inventory.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * ADR 0141's HTTP surface with the console's real JSON: who may stop at which reach, the
 * per-item outcome contract, the lift's {@code If-Match}, the freeze answer, and the explainer.
 *
 * <p>Bodies are written the way the operations console sends them — optional fields omitted,
 * not null — because Jackson 3 refuses a missing primitive: a request that works with every
 * field present proves nothing about the one the console actually sends.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InventoryStopControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9f20-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f20-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9f20-4000-7000-8000-0000000000c1");
    private static final UUID OTHER_LOCATION = UUID.fromString("018f9f20-4000-7000-8000-0000000000c2");
    private static final UUID PRODUCT = UUID.fromString("018f9f20-4000-7000-8000-0000000000d1");
    private static final UUID VARIANT = UUID.fromString("018f9f20-4000-7000-8000-0000000000d2");
    private static final UUID SECOND_VARIANT = UUID.fromString("018f9f20-4000-7000-8000-0000000000d3");
    private static final UUID CHANNEL = UUID.fromString("018f9f20-4000-7000-8000-0000000000e1");

    private static final String OWNER = "stop-endpoint-owner";
    private static final String BRAND_MANAGER = "stop-endpoint-brand-manager";
    private static final String LOCATION_MANAGER = "stop-endpoint-location-manager";
    private static final String OTHER_LOCATION_MANAGER = "stop-endpoint-other-location-manager";
    private static final String NO_GRANT = "stop-endpoint-no-grant";

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

    private int idempotencySequence;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE inventory.availability_stops, inventory.reservation_lines, inventory.reservations, "
                        + "inventory.movements, inventory.positions, inventory.stock_items CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.variants, catalog.products CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertFixtures();
        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        grant(BRAND_MANAGER, PlatformRole.BRAND_MANAGER, "BRAND", BRAND);
        grant(LOCATION_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", LOCATION);
        grant(OTHER_LOCATION_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", OTHER_LOCATION);
    }

    @Test
    void aLocationManagerCannotStopAtBrandScope() throws Exception {
        MvcResult refused = mvc.perform(post(brandPath("/inventory/stops"))
                        .with(tokenFor(LOCATION_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(brandStop(VARIANT)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.INVENTORY_STOP_MANAGE.code());
        assertThat(count()).isZero();
    }

    @Test
    void aBrandManagerStopsAtBrandScopeWithTheConsolesRealJson() throws Exception {
        // Exactly what the console sends: no endsAt, no menuId, no channelId, no
        // untilEndOfTradingDay -- every optional field omitted rather than null.
        MvcResult applied = mvc.perform(post(brandPath("/inventory/stops"))
                        .with(tokenFor(BRAND_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(brandStop(VARIANT)))
                .andReturn();

        assertThat(applied.getResponse().getStatus()).isEqualTo(200);
        assertThat(applied.getResponse().getContentAsString())
                .contains("\"appliedCount\":1")
                .contains("\"failedCount\":0")
                .contains("\"status\":\"APPLIED\"");
        assertThat(jdbc.sql("SELECT scope_type || '/' || source || '/' || status FROM inventory.availability_stops")
                        .query(String.class)
                        .list())
                .containsExactly("BRAND/OPERATOR/ACTIVE");
    }

    @Test
    void aLocationManagerStopsTheirOwnBranchAndCannotNameAnotherScope() throws Exception {
        MvcResult applied = mvc.perform(post(locationPath(LOCATION, "/inventory/stops"))
                        .with(tokenFor(LOCATION_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"variantIds":["%s"],"scope":"LOCATION","reasonCode":"FRYER_DOWN","untilEndOfTradingDay":true}
                                """.formatted(VARIANT)))
                .andReturn();
        assertThat(applied.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT ends_at IS NOT NULL FROM inventory.availability_stops")
                        .query(Boolean.class)
                        .single())
                .as("until the end of the trading day computed an end")
                .isTrue();

        MvcResult tooWide = mvc.perform(post(locationPath(LOCATION, "/inventory/stops"))
                        .with(tokenFor(LOCATION_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"variantIds":["%s"],"scope":"BRAND","reasonCode":"RECALL"}
                                """.formatted(SECOND_VARIANT)))
                .andReturn();
        assertThat(tooWide.getResponse().getStatus()).isEqualTo(400);

        MvcResult wrongBranch = mvc.perform(post(locationPath(OTHER_LOCATION, "/inventory/stops"))
                        .with(tokenFor(LOCATION_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"variantIds":["%s"],"scope":"LOCATION","reasonCode":"FRYER_DOWN"}
                                """.formatted(VARIANT)))
                .andReturn();
        assertThat(wrongBranch.getResponse().getStatus())
                .as("a grant at one branch does not reach another")
                .isEqualTo(403);
    }

    @Test
    void aTerminalStopIsRefusedByName() throws Exception {
        MvcResult refused = mvc.perform(post(locationPath(LOCATION, "/inventory/stops"))
                        .with(tokenFor(LOCATION_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"variantIds":["%s"],"scope":"TERMINAL","reasonCode":"FRYER_DOWN"}
                                """.formatted(VARIANT)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isIn(400, 422);
        assertThat(count()).isZero();
    }

    @Test
    void oneBadVariantNeverRollsBackTheRest() throws Exception {
        UUID stranger = UUID.fromString("018f9f20-4000-7000-8000-0000000000ff");
        MvcResult mixed = mvc.perform(post(brandPath("/inventory/stops"))
                        .with(tokenFor(BRAND_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"variantIds":["%s","%s","%s"],"scope":"BRAND","reasonCode":"RECALL"}
                                """.formatted(VARIANT, stranger, SECOND_VARIANT)))
                .andReturn();

        assertThat(mixed.getResponse().getStatus()).isEqualTo(200);
        assertThat(mixed.getResponse().getContentAsString())
                .contains("\"requestedCount\":3")
                .contains("\"appliedCount\":2")
                .contains("\"failedCount\":1")
                .contains("TARGET_NOT_FOUND");
        assertThat(count()).as("the two good variants were stopped").isEqualTo(2);
    }

    @Test
    void aLiftCarriesIfMatchAndTheLocationRouteCannotLiftABrandWideStop() throws Exception {
        mvc.perform(post(brandPath("/inventory/stops"))
                .with(tokenFor(BRAND_MANAGER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(brandStop(VARIANT)));
        UUID stopId = jdbc.sql("SELECT id FROM inventory.availability_stops")
                .query(UUID.class)
                .single();

        MvcResult noPrecondition = mvc.perform(delete(brandPath("/inventory/stops/" + stopId))
                        .with(tokenFor(BRAND_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key()))
                .andReturn();
        assertThat(noPrecondition.getResponse().getStatus())
                .as("a lift with no If-Match is refused, not treated as no precondition")
                .isEqualTo(400);

        MvcResult stale = mvc.perform(delete(brandPath("/inventory/stops/" + stopId))
                        .with(tokenFor(BRAND_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .header("If-Match", "W/\"7\""))
                .andReturn();
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("STALE_VERSION");

        MvcResult fromTheBranch = mvc.perform(delete(locationPath(LOCATION, "/inventory/stops/" + stopId))
                        .with(tokenFor(LOCATION_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .header("If-Match", "W/\"1\""))
                .andReturn();
        assertThat(fromTheBranch.getResponse().getStatus())
                .as("a branch manager cannot lift the brand's stop; it does not even exist for them")
                .isEqualTo(404);

        MvcResult lifted = mvc.perform(delete(brandPath("/inventory/stops/" + stopId))
                        .with(tokenFor(BRAND_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .header("If-Match", "W/\"1\""))
                .andReturn();
        assertThat(lifted.getResponse().getStatus()).isEqualTo(200);
        assertThat(lifted.getResponse().getContentAsString()).contains("\"status\":\"LIFTED\"");
        assertThat(lifted.getResponse().getHeader("ETag")).isEqualTo("W/\"2\"");
    }

    @Test
    void aFrozenTenantAnswersStopsFrozenAndStillLifts() throws Exception {
        mvc.perform(post(brandPath("/inventory/stops"))
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(brandStop(VARIANT)));
        UUID stopId = jdbc.sql("SELECT id FROM inventory.availability_stops")
                .query(UUID.class)
                .single();
        jdbc.sql("""
                INSERT INTO tenant.configuration_values (id, key_code, scope_type, tenant_id,
                    value_type, boolean_value, set_by)
                VALUES (:id, 'inventory.stops.creation_enabled', 'TENANT', :tenantId, 'BOOLEAN', false, 'a-test')
                """).param("id", UUID.randomUUID()).param("tenantId", TENANT).update();

        MvcResult frozen = mvc.perform(post(brandPath("/inventory/stops"))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(brandStop(SECOND_VARIANT)))
                .andReturn();
        assertThat(frozen.getResponse().getStatus()).isEqualTo(409);
        assertThat(frozen.getResponse().getContentAsString())
                .contains("RESOURCE_CONFLICT")
                .contains("STOPS_FROZEN");
        assertThat(count()).as("nothing new was written").isEqualTo(1);

        MvcResult lifted = mvc.perform(delete(brandPath("/inventory/stops/" + stopId))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .header("If-Match", "W/\"1\""))
                .andReturn();
        assertThat(lifted.getResponse().getStatus())
                .as("a lift succeeds while frozen")
                .isEqualTo(200);
    }

    @Test
    void theExplainerListsEveryCoveringStopAndTheListingIsTenantScoped() throws Exception {
        jdbc.sql("""
                INSERT INTO inventory.stock_items (id, tenant_id, brand_id, location_id, variant_id, tracking_mode)
                VALUES (:id, :t, :b, :l, :v, 'BINARY')
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("l", LOCATION)
                .param("v", VARIANT)
                .update();
        jdbc.sql("""
                INSERT INTO inventory.positions (stock_item_id, tenant_id, brand_id, location_id, binary_available)
                SELECT id, tenant_id, brand_id, location_id, true FROM inventory.stock_items
                """).update();
        mvc.perform(post(brandPath("/inventory/stops"))
                .with(tokenFor(BRAND_MANAGER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                .contentType(MediaType.APPLICATION_JSON)
                .content(brandStop(VARIANT)));
        mvc.perform(post(locationPath(LOCATION, "/inventory/stops"))
                .with(tokenFor(LOCATION_MANAGER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"variantIds":["%s"],"scope":"CHANNEL","channelId":"%s","reasonCode":"FRYER_DOWN"}
                        """.formatted(VARIANT, CHANNEL)));

        MvcResult explained = mvc.perform(
                        get(locationPath(LOCATION, "/inventory/variants/" + VARIANT + "/availability-explanation"))
                                .param("channel", "STOREFRONT")
                                .with(tokenFor(LOCATION_MANAGER)))
                .andReturn();
        assertThat(explained.getResponse().getStatus()).isEqualTo(200);
        assertThat(explained.getResponse().getContentAsString())
                .contains("\"sellable\":false")
                .contains("ON_STOP")
                .contains("\"scopeType\":\"BRAND\"")
                .contains("\"scopeType\":\"CHANNEL\"");

        MvcResult listing = mvc.perform(get(brandPath("/inventory/stops")).with(tokenFor(BRAND_MANAGER)))
                .andReturn();
        assertThat(listing.getResponse().getStatus()).isEqualTo(200);
        assertThat(listing.getResponse().getContentAsString()).contains("\"scopeType\":\"BRAND\"");

        MvcResult refused = mvc.perform(get(brandPath("/inventory/stops")).with(tokenFor(NO_GRANT)))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ helpers

    private String key() {
        return "stop-endpoint-" + (++idempotencySequence);
    }

    private static String brandStop(UUID variant) {
        return """
                {"variantIds":["%s"],"scope":"BRAND","reasonCode":"RECALL"}
                """.formatted(variant);
    }

    private static String brandPath(String suffix) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + suffix;
    }

    private static String locationPath(UUID location, String suffix) {
        return brandPath("/locations/" + location + suffix);
    }

    private long count() {
        return jdbc.sql("SELECT count(*) FROM inventory.availability_stops")
                .query(Long.class)
                .single();
    }

    private void insertFixtures() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'inventory-stop-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        for (UUID location : List.of(LOCATION, OTHER_LOCATION)) {
            jdbc.sql("""
                    INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                        timezone, status, version)
                    VALUES (:id, :tenantId, :brandId, :code, :slug, 'Branch', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", location)
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .param("code", location.equals(LOCATION) ? "CHI" : "YUN")
                    .param("slug", location.equals(LOCATION) ? "chilonzor" : "yunusobod")
                    .update();
        }
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront')
                """).param("id", CHANNEL).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, 'BURGER', 'ACTIVE')
                """)
                .param("id", PRODUCT)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        for (UUID variant : List.of(VARIANT, SECOND_VARIANT)) {
            jdbc.sql("""
                    INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, is_default, status, sku)
                    VALUES (:id, :tenantId, :brandId, :productId, :isDefault, 'ACTIVE', :sku)
                    """)
                    .param("id", variant)
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .param("productId", PRODUCT)
                    .param("isDefault", variant.equals(VARIANT))
                    .param("sku", "SKU-" + variant.toString().substring(30))
                    .update();
        }
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'inventory stop endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
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
