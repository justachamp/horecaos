package uz.horecaos.platform.inventory.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * The decommission of stops over HTTP with the real JSON (ADR 0141, rollback switch three): the
 * materialisation run, its report, the acknowledgement, and the one thing that gives them teeth --
 * the configuration write of {@code inventory.stops.read_enabled}, refused with {@code 409
 * MATERIALISATION_REQUIRED} until the report was acknowledged.
 *
 * <p>The switch is turned through the platform's own configuration endpoint, not by a row inserted
 * in the test, because the guard lives on that write path and an inserted row would prove nothing
 * about it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StopMaterialisationControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018fb500-6000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb500-6000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fb500-6000-7000-8000-0000000000c1");
    private static final UUID PRODUCT = UUID.fromString("018fb500-6000-7000-8000-0000000000d1");
    private static final UUID BINARY_DISH = UUID.fromString("018fb500-6000-7000-8000-0000000000d2");
    private static final UUID UNTRACKED_DISH = UUID.fromString("018fb500-6000-7000-8000-0000000000d3");

    private static final String PLATFORM_ADMIN = "decommission-platform-admin";
    private static final String BRAND_MANAGER = "decommission-brand-manager";
    private static final String LOCATION_MANAGER = "decommission-location-manager";

    private static final JsonMapper JSON = JsonMapper.builder().build();

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
    @SuppressWarnings("NullAway")
    private MockMvc mvc;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcClient jdbc;

    @Autowired
    @SuppressWarnings("NullAway")
    private RoleRegistrySynchronizer roleRegistry;

    private int idempotencySequence;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("""
                TRUNCATE TABLE inventory.stop_materialisation_lines, inventory.stop_materialisation_stops,
                    inventory.stop_materialisation_runs, inventory.availability_stops,
                    inventory.reservation_lines, inventory.reservations, inventory.movements,
                    inventory.positions, inventory.stock_items CASCADE
                """).update();
        jdbc.sql("TRUNCATE TABLE catalog.variants, catalog.products CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("DELETE FROM tenant.configuration_values WHERE key_code = 'inventory.stops.read_enabled'")
                .update();
        roleRegistry.synchronize();
        insertFixtures();
        grantPlatform(PLATFORM_ADMIN, PlatformRole.PLATFORM_ADMIN);
        grant(BRAND_MANAGER, PlatformRole.BRAND_MANAGER, "BRAND", BRAND);
        grant(LOCATION_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", LOCATION);
    }

    @Test
    @DisplayName(
            "the switch is refused until the report is acknowledged, then turns; stops are ignored, not deleted; turning it back resumes them")
    void theWholeDecommissionOverHttp() throws Exception {
        stopBrandWide(BINARY_DISH);
        stopAtBranch(UNTRACKED_DISH);

        // -- the switch, blind: refused, and nothing is stored.
        MvcResult blind = switchReads(false);
        assertThat(blind.getResponse().getStatus()).isEqualTo(409);
        JsonNode problem = JSON.readTree(blind.getResponse().getContentAsString());
        assertThat(problem.path("conflict").asText()).isEqualTo("MATERIALISATION_REQUIRED");
        assertThat(problem.path("blockedBrandIds").get(0).asText()).isEqualTo(BRAND.toString());
        assertThat(
                        count(
                                "SELECT count(*) FROM tenant.configuration_values WHERE key_code = 'inventory.stops.read_enabled'"))
                .as("a refusal leaves no value behind")
                .isZero();

        // -- a branch manager may not run it; a brand manager does.
        assertThat(run(LOCATION_MANAGER).getResponse().getStatus()).isEqualTo(403);
        MvcResult ran = run(BRAND_MANAGER);
        assertThat(ran.getResponse().getStatus()).isEqualTo(201);
        JsonNode run = JSON.readTree(ran.getResponse().getContentAsString());
        assertThat(run.path("status").asText()).isEqualTo("COMPLETED");
        assertThat(run.path("stopsSeen").asInt()).isEqualTo(2);
        assertThat(run.path("positionsWritten").asInt())
                .as("the BINARY dish landed on its position")
                .isEqualTo(1);
        assertThat(run.path("notCarried").asInt())
                .as("the UNTRACKED dish did not")
                .isEqualTo(1);
        assertThat(run.path("acknowledged").asBoolean()).isFalse();
        String runId = run.path("id").asText();
        String version = ran.getResponse().getHeader("ETag");
        assertThat(version).isEqualTo("W/\"" + run.path("version").asInt() + "\"");

        // -- the report names the dish that will be on sale again; ids and codes only.
        MvcResult reported = mvc.perform(get(runPath(runId) + "/report").with(tokenFor(BRAND_MANAGER)))
                .andReturn();
        assertThat(reported.getResponse().getStatus()).isEqualTo(200);
        JsonNode report = JSON.readTree(reported.getResponse().getContentAsString());
        assertThat(report.path("items")).hasSize(1);
        JsonNode line = report.path("items").get(0);
        assertThat(line.path("reasonCode").asText()).isEqualTo("UNTRACKED_ITEM");
        assertThat(line.path("variantId").asText()).isEqualTo(UNTRACKED_DISH.toString());
        assertThat(line.path("locationId").asText()).isEqualTo(LOCATION.toString());
        assertThat(report.path("nextCursor").isNull()).isTrue();

        // -- run but not acknowledged: still refused.
        assertThat(switchReads(false).getResponse().getStatus()).isEqualTo(409);

        // -- the acknowledgement quotes the version, once.
        assertThat(acknowledge(runId, null, BRAND_MANAGER).getResponse().getStatus())
                .as("no If-Match is refused, not treated as no precondition")
                .isEqualTo(400);
        MvcResult stale = acknowledge(runId, "W/\"99\"", BRAND_MANAGER);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString()).contains("STALE_VERSION");
        assertThat(acknowledge(runId, version, LOCATION_MANAGER).getResponse().getStatus())
                .as("acknowledging is the brand owner's, not a branch manager's")
                .isEqualTo(403);
        MvcResult acknowledged = acknowledge(runId, version, BRAND_MANAGER);
        assertThat(acknowledged.getResponse().getStatus()).isEqualTo(200);
        JsonNode ack = JSON.readTree(acknowledged.getResponse().getContentAsString());
        assertThat(ack.path("acknowledged").asBoolean()).isTrue();
        assertThat(acknowledge(runId, acknowledged.getResponse().getHeader("ETag"), BRAND_MANAGER)
                        .getResponse()
                        .getStatus())
                .as("a report is acknowledged once")
                .isEqualTo(409);

        // -- now the switch turns.
        MvcResult turned = switchReads(false);
        assertThat(turned.getResponse().getStatus())
                .as(turned.getResponse().getContentAsString())
                .isEqualTo(200);

        // -- stops are ignored, not deleted, and the platform says so.
        assertThat(count("SELECT count(*) FROM inventory.availability_stops WHERE status = 'ACTIVE'"))
                .isEqualTo(2);
        JsonNode untracked = explain(UNTRACKED_DISH);
        assertThat(untracked.path("sellable").asBoolean())
                .as("the UNTRACKED dish is on sale again: the report said so")
                .isTrue();
        assertThat(untracked.path("stopsConsulted").asBoolean()).isFalse();
        assertThat(untracked.path("stops")).isEmpty();
        JsonNode binary = explain(BINARY_DISH);
        assertThat(binary.path("sellable").asBoolean())
                .as("the BINARY dish is still refused: its position carries the stop")
                .isFalse();
        assertThat(binary.path("reasons").toString()).contains("SOLD_OUT");
        MvcResult listing = mvc.perform(get(brandPath("/inventory/stops")).with(tokenFor(BRAND_MANAGER)))
                .andReturn();
        JsonNode rows =
                JSON.readTree(listing.getResponse().getContentAsString()).path("items");
        assertThat(rows).hasSize(2);
        rows.forEach(row -> assertThat(row.path("ignored").asBoolean()).isTrue());

        // -- a new stop would only mislead: refused as frozen.
        MvcResult refused = mvc.perform(post(brandPath("/inventory/stops"))
                        .with(tokenFor(BRAND_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantIds\":[\"%s\"],\"scope\":\"BRAND\",\"reasonCode\":\"RECALL\"}"
                                .formatted(UNTRACKED_DISH)))
                .andReturn();
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).contains("STOPS_FROZEN");

        // -- turning it back on is never guarded, and resumes the ignored stops.
        assertThat(switchReads(true).getResponse().getStatus()).isEqualTo(200);
        JsonNode resumed = explain(UNTRACKED_DISH);
        assertThat(resumed.path("sellable").asBoolean()).isFalse();
        assertThat(resumed.path("stopsConsulted").asBoolean()).isTrue();
        assertThat(resumed.path("stops")).hasSize(1);
    }

    @Test
    @DisplayName("a stop made after the run re-blocks the switch, through the same endpoint")
    void aStopAfterTheRunReBlocks() throws Exception {
        stopAtBranch(UNTRACKED_DISH);
        JsonNode run = JSON.readTree(run(BRAND_MANAGER).getResponse().getContentAsString());
        MvcResult ran = acknowledgeLatest(run);
        assertThat(ran.getResponse().getStatus()).isEqualTo(200);

        stopBrandWide(BINARY_DISH);

        MvcResult blocked = switchReads(false);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
        assertThat(blocked.getResponse().getContentAsString()).contains("MATERIALISATION_REQUIRED");
    }

    @Test
    @DisplayName("another brand's run is not found; nobody without the capability reads the report")
    void aRunIsTheBrandsOwn() throws Exception {
        JsonNode run = JSON.readTree(run(BRAND_MANAGER).getResponse().getContentAsString());
        UUID otherBrand = UUID.randomUUID();

        MvcResult elsewhere = mvc.perform(get("/api/v1/tenants/" + TENANT + "/brands/" + otherBrand
                                + "/inventory/stop-materialisation-runs/"
                                + run.path("id").asText())
                        .with(tokenFor(BRAND_MANAGER)))
                .andReturn();

        assertThat(elsewhere.getResponse().getStatus()).isIn(403, 404);
        assertThat(mvc.perform(get(runPath(run.path("id").asText())).with(tokenFor("decommission-stranger")))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    // ------------------------------------------------------------------ helpers

    private MvcResult acknowledgeLatest(JsonNode run) throws Exception {
        return acknowledge(run.path("id").asText(), "W/\"" + run.path("version").asInt() + "\"", BRAND_MANAGER);
    }

    private MvcResult run(String as) throws Exception {
        return mvc.perform(post(brandPath("/inventory/stop-materialisation-runs"))
                        .with(tokenFor(as))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key()))
                .andReturn();
    }

    private MvcResult acknowledge(String runId, @Nullable String ifMatch, String as) throws Exception {
        var request = post(runPath(runId) + "/acknowledgement")
                .with(tokenFor(as))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key());
        if (ifMatch != null) {
            request = request.header("If-Match", ifMatch);
        }
        return mvc.perform(request).andReturn();
    }

    /** The platform's own configuration write, at tenant scope, with the real JSON. */
    private MvcResult switchReads(boolean on) throws Exception {
        return mvc.perform(post("/api/v1/control-plane/configuration/keys/inventory.stops.read_enabled/values")
                        .with(tokenFor(PLATFORM_ADMIN))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scopeType":"TENANT","tenantId":"%s","explicitNull":false,"booleanValue":%s,
                                 "expectedVersion":%s,"reason":"decommission test"}
                                """.formatted(TENANT, on, currentVersion())))
                .andReturn();
    }

    private String currentVersion() {
        List<Long> versions = jdbc.sql("""
                        SELECT version FROM tenant.configuration_values
                        WHERE key_code = 'inventory.stops.read_enabled' AND scope_type = 'TENANT' AND tenant_id = :t
                        """).param("t", TENANT).query(Long.class).list();
        return versions.isEmpty() ? "null" : String.valueOf(versions.get(0));
    }

    private JsonNode explain(UUID variant) throws Exception {
        MvcResult result = mvc.perform(get(brandPath("/locations/" + LOCATION + "/inventory/variants/" + variant
                                + "/availability-explanation"))
                        .with(tokenFor(LOCATION_MANAGER)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private void stopBrandWide(UUID variant) throws Exception {
        MvcResult result = mvc.perform(post(brandPath("/inventory/stops"))
                        .with(tokenFor(BRAND_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantIds\":[\"%s\"],\"scope\":\"BRAND\",\"reasonCode\":\"RECALL\"}"
                                .formatted(variant)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private void stopAtBranch(UUID variant) throws Exception {
        MvcResult result = mvc.perform(post(brandPath("/locations/" + LOCATION + "/inventory/stops"))
                        .with(tokenFor(LOCATION_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variantIds\":[\"%s\"],\"scope\":\"LOCATION\",\"reasonCode\":\"OUT_OF_STOCK\"}"
                                .formatted(variant)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private String key() {
        return "decommission-" + (++idempotencySequence);
    }

    private static String brandPath(String suffix) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + suffix;
    }

    private static String runPath(String runId) {
        return brandPath("/inventory/stop-materialisation-runs/" + runId);
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }

    private void insertFixtures() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'decommission-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CHI', 'chilonzor', 'Branch', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, 'PLOV', 'ACTIVE')
                """)
                .param("id", PRODUCT)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        for (UUID variant : List.of(BINARY_DISH, UNTRACKED_DISH)) {
            jdbc.sql("""
                    INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, is_default, status, sku)
                    VALUES (:id, :tenantId, :brandId, :productId, :isDefault, 'ACTIVE', :sku)
                    """)
                    .param("id", variant)
                    .param("tenantId", TENANT)
                    .param("brandId", BRAND)
                    .param("productId", PRODUCT)
                    .param("isDefault", variant.equals(BINARY_DISH))
                    .param("sku", "SKU-" + variant.toString().substring(30))
                    .update();
            jdbc.sql("""
                    INSERT INTO inventory.stock_items (id, tenant_id, brand_id, location_id, variant_id, tracking_mode)
                    VALUES (:id, :t, :b, :l, :v, :mode)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("t", TENANT)
                    .param("b", BRAND)
                    .param("l", LOCATION)
                    .param("v", variant)
                    .param("mode", variant.equals(BINARY_DISH) ? "BINARY" : "UNTRACKED")
                    .update();
        }
        jdbc.sql("""
                INSERT INTO inventory.positions (stock_item_id, tenant_id, brand_id, location_id, binary_available)
                SELECT id, tenant_id, brand_id, location_id, CASE WHEN tracking_mode = 'BINARY' THEN true END
                FROM inventory.stock_items
                """).update();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'stop decommission endpoint test', :validFrom)
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

    private void grantPlatform(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, NULL, :subject, :roleId, true, 'PLATFORM', NULL,
                        'ACTIVE', 'test-fixture', 'stop decommission endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
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
