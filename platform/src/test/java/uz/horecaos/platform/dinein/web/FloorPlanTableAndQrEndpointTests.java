package uz.horecaos.platform.dinein.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@code FloorPlanController}'s table-layout write and QR rotation, exercised
 * through the real HTTP stack (gap map rows {@code 10.2d}/{@code X.36}/{@code
 * 10.5b}, wave P38) — mirroring {@code
 * ReservationControllerLocationIsolationHttpTests}' own style for the same
 * controller family.
 *
 * <p>{@code PUT .../tables/{tableId}} is new this wave: table coordinates were
 * write-only through {@code POST .../tables} before, with no way to save a
 * drag on the canvas and no field in {@code TableResponse} to read one back.
 * The QR rotation endpoint already existed; what is new here is proving, at
 * the HTTP layer and not merely at the service layer {@code DineInTests}
 * already covers, that a stale {@code If-Match} refuses a second rotation
 * rather than silently reusing the version the caller happened to send.
 */
@SpringBootTest
@AutoConfigureMockMvc
class FloorPlanTableAndQrEndpointTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID TENANT = UUID.fromString("018fd500-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fd500-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fd500-4000-7000-8000-0000000000c1");

    /** Holds {@code DINEIN_FLOORPLAN_MANAGE}/{@code DINEIN_QR_ROTATE}/{@code RESERVATION_READ} at LOCATION. */
    private static final String MANAGER = "floorplan-manager";

    /** No grant anywhere — the capability-refused negative case. */
    private static final String NOBODY = "floorplan-nobody";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the floor plan endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // The rotation endpoint's response carries a live QR token, and
        // IdempotencyInterceptor envelope-encrypts a mutating response before
        // storing it for replay (ADR 0029) — the same preset
        // OperationsCourierControllerEndpointTests uses for its own
        // PII-carrying endpoint.
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
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

    private UUID tableId;

    @BeforeEach
    void reset() throws Exception {
        // Every test method's fixtures reuse the same literal Idempotency-Key
        // strings (create-section-1, create-table-1, ...) against the same
        // tenant/brand/location path, so IdempotencyInterceptor's scope key —
        // built from the handler and path variables, not the request body —
        // is identical across test methods too. Without this truncate, the
        // second test method's "create-table-1" call replays the first
        // method's cached response (a tableId whose row this TRUNCATE just
        // removed) instead of creating a fresh row, and every request against
        // that stale id then 404s.
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();

        // dinein.tables/sections/location_settings and tenant.brands/locations
        // all chain back to tenant.tenants by foreign key, so TRUNCATE ...
        // CASCADE from the root clears every one of them in one statement, the
        // same way ReservationControllerLocationIsolationHttpTests does.
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        seedTenancy();
        roleRegistry.synchronize();
        grant(MANAGER, PlatformRole.LOCATION_MANAGER, LOCATION);

        UUID sectionId = createSection();
        tableId = createTable(sectionId);
    }

    @Test
    @DisplayName("PUT .../tables/{id} moves a table, and the response — and a later GET — carry the new coordinates")
    void movingATablePersistsAndIsReadable() throws Exception {
        MvcResult moved = mvc.perform(put(tablePath(tableId))
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "move-table-1")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"layoutX\":120.5,\"layoutY\":80,\"reason\":\"Dragged to the window\"}"))
                .andReturn();

        assertThat(moved.getResponse().getStatus()).isEqualTo(200);
        JsonNode movedBody = json(moved);
        assertThat(movedBody.path("layoutX").decimalValue()).isEqualByComparingTo("120.5");
        assertThat(movedBody.path("layoutY").decimalValue()).isEqualByComparingTo("80");
        assertThat(movedBody.path("version").asInt()).isEqualTo(2);

        MvcResult listed =
                mvc.perform(get(tablesPath()).with(tokenFor(MANAGER))).andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        List<JsonNode> rows = new ArrayList<>();
        json(listed).forEach(rows::add);
        Optional<JsonNode> row = rows.stream()
                .filter(candidate -> candidate.path("tableId").asText().equals(tableId.toString()))
                .findFirst();
        assertThat(row).as("the moved table appears in the branch's table list").isPresent();
        assertThat(row.orElseThrow().path("layoutX").decimalValue()).isEqualByComparingTo("120.5");
        assertThat(row.orElseThrow().path("layoutY").decimalValue()).isEqualByComparingTo("80");
    }

    @Test
    @DisplayName("PUT .../tables/{id} with a stale If-Match is refused, and the table keeps its old position")
    void movingWithAStaleVersionIsRefused() throws Exception {
        mvc.perform(put(tablePath(tableId))
                .with(tokenFor(MANAGER))
                .header("Idempotency-Key", "move-table-2")
                .header("If-Match", "\"1\"")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"layoutX\":10,\"layoutY\":10,\"reason\":\"First move\"}"));

        MvcResult staleAttempt = mvc.perform(put(tablePath(tableId))
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "move-table-3")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"layoutX\":999,\"layoutY\":999,\"reason\":\"Replayed stale version\"}"))
                .andReturn();

        assertThat(staleAttempt.getResponse().getStatus())
                .as("version 1 was already consumed by the first move; this is a stale precondition")
                .isEqualTo(409);
        assertThat(layoutXOf(tableId)).isEqualByComparingTo(BigDecimal.valueOf(10));
    }

    @Test
    @DisplayName("PUT .../tables/{id} is refused for a caller holding no capability")
    void movingATableIsRefusedWithoutCapability() throws Exception {
        MvcResult attempt = mvc.perform(put(tablePath(tableId))
                        .with(tokenFor(NOBODY))
                        .header("Idempotency-Key", "move-table-4")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"layoutX\":1,\"layoutY\":1,\"reason\":\"Should not happen\"}"))
                .andReturn();

        assertThat(attempt.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("QR rotation is one-shot: a second rotation replaying the same If-Match is refused")
    void rotationIsOneShotUnderIfMatch() throws Exception {
        MvcResult first = mvc.perform(post(tablePath(tableId) + "/qr-token-rotations")
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "rotate-1")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"First issue\"}"))
                .andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        String firstToken = json(first).path("qrToken").asText();
        assertThat(firstToken).isNotBlank();

        MvcResult replay = mvc.perform(post(tablePath(tableId) + "/qr-token-rotations")
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "rotate-2")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Replayed stale version\"}"))
                .andReturn();

        assertThat(replay.getResponse().getStatus())
                .as("the table moved to version 2 on the first rotation; If-Match \"1\" is now stale")
                .isEqualTo(409);

        // The second, correctly-versioned rotation still works and mints a
        // different token — one-shot means "not replayable", not "locked forever".
        MvcResult second = mvc.perform(post(tablePath(tableId) + "/qr-token-rotations")
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "rotate-3")
                        .header("If-Match", "\"2\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Code photographed\"}"))
                .andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        String secondToken = json(second).path("qrToken").asText();
        assertThat(secondToken).isNotBlank().isNotEqualTo(firstToken);
    }

    @Test
    @DisplayName("QR rotation is refused for a caller holding no capability")
    void rotationIsRefusedWithoutCapability() throws Exception {
        MvcResult attempt = mvc.perform(post(tablePath(tableId) + "/qr-token-rotations")
                        .with(tokenFor(NOBODY))
                        .header("Idempotency-Key", "rotate-refused-1")
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Should not happen\"}"))
                .andReturn();

        assertThat(attempt.getResponse().getStatus()).isEqualTo(403);
    }

    // ------------------------------------------------------------------ fixtures

    private UUID createSection() throws Exception {
        MvcResult created = mvc.perform(post(basePath() + "/sections")
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "create-section-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"MAIN\",\"displayName\":\"Main hall\",\"sortOrder\":1}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        return UUID.fromString(json(created).path("sectionId").asText());
    }

    private UUID createTable(UUID section) throws Exception {
        MvcResult created = mvc.perform(post(basePath() + "/tables")
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "create-table-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(("{\"sectionId\":\"%s\",\"code\":\"T1\",\"displayName\":\"Table 1\","
                                        + "\"seats\":4,\"joinable\":false}")
                                .formatted(section)))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(200);
        return UUID.fromString(json(created).path("tableId").asText());
    }

    private BigDecimal layoutXOf(UUID id) {
        return jdbc.sql("SELECT layout_x FROM dinein.tables WHERE id = :id")
                .param("id", id)
                .query(BigDecimal.class)
                .single();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static String basePath() {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/dine-in";
    }

    private static String tablesPath() {
        return basePath() + "/tables";
    }

    private static String tablePath(UUID tableId) {
        return tablesPath() + "/" + tableId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'floorplan-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();
    }

    private void grant(String subject, PlatformRole role, UUID locationId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :locationId,
                        'ACTIVE', 'test-fixture', 'floor plan endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + locationId).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("locationId", locationId)
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
