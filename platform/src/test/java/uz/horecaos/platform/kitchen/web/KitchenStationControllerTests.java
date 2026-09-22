package uz.horecaos.platform.kitchen.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@link KitchenStationController}'s station-capacity and routing-rule
 * PUT/DELETE/GET endpoints, exercised through the real HTTP stack —
 * mirroring {@code ReservationControllerLocationIsolationHttpTests}' own
 * style for the analogous gap.
 *
 * <p><b>wave139-integration adversarial review, batch 5, T02/medium.</b> Every
 * assertion for {@code updateCapacityWindow}/{@code deleteCapacityWindow} in
 * {@code KitchenExecutionTests} calls {@code KitchenStationService} directly,
 * so {@code @RequiresCapability(KITCHEN_STATION_MANAGE, LOCATION, mutating =
 * true)} on these two endpoints, and {@code @Valid} on their request bodies,
 * were never proven at the HTTP layer. This class proves both: a caller
 * holding only {@code kitchen.ticket.read} (the capability the sibling GET
 * accepts) is refused, a body missing {@code expectedVersion} is refused, and
 * a caller who actually holds {@code kitchen.station.manage} reaches the
 * service and gets the ordinary 200/204.
 *
 * <p><b>Gap map row 4.2g.</b> The routing-rules {@code GET} and {@code PUT}
 * added alongside {@code KitchenExecutionTests}'s direct-service coverage get
 * the same HTTP-layer proof: {@code KITCHEN_TICKET_READ} reaches the read,
 * {@code KITCHEN_STATION_MANAGE} is required for the edit, and the edit
 * actually changes the department rather than the second {@code POST}'s 409.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KitchenStationControllerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();

    /** Holds {@code KITCHEN_TICKET_READ} only — the same grant the sibling GET /station-capacity accepts. */
    private static final String READ_ONLY_STAFF = "station-capacity-http-read-only";
    /** Holds {@code KITCHEN_STATION_MANAGE} — the grant PUT/DELETE actually require. */
    private static final String MANAGER = "station-capacity-http-manager";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the kitchen station-capacity HTTP test");
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

    private UUID stationId;

    @BeforeEach
    void reset() {
        // kitchen.stations/station_capacity and iam.grants all chain back to
        // tenant.tenants by foreign key, the same TRUNCATE ... CASCADE
        // KitchenDeviceServiceTests and the reservation isolation test use.
        // catalog.catalogs/categories carry tenant_id/brand_id as plain columns
        // with no foreign key back to tenant.* (V0016's own comment: everything
        // is "brand-owned" by convention, not by constraint), so the row-4.2g
        // fixtures below need their own explicit truncate rather than riding
        // the tenant cascade.
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.categories, catalog.catalogs CASCADE").update();

        seedTenancyAndStation();
        roleRegistry.synchronize();
        grant(READ_ONLY_STAFF, PlatformRole.LOCATION_STAFF, LOCATION);
        grant(MANAGER, PlatformRole.LOCATION_MANAGER, LOCATION);
    }

    @Test
    @DisplayName("PUT .../station-capacity/{id} refuses a caller holding only kitchen.ticket.read")
    void updateRefusesACallerWithoutStationManage() throws Exception {
        UUID windowId = seedCapacityWindow();

        MvcResult attempt = mvc.perform(put(capacityPath(windowId))
                        .with(tokenFor(READ_ONLY_STAFF))
                        .header("Idempotency-Key", "station-capacity-update-403-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("09:00:00", "12:00:00", 40, 1)))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("READ_ONLY_STAFF holds kitchen.ticket.read, not kitchen.station.manage")
                .isEqualTo(403);
        assertThat(capacityWindowRow(windowId))
                .as("a refused update must leave the window exactly as it was")
                .containsEntry("portions_per_hour", 30);
    }

    @Test
    @DisplayName("DELETE .../station-capacity/{id} refuses a caller holding only kitchen.ticket.read")
    void deleteRefusesACallerWithoutStationManage() throws Exception {
        UUID windowId = seedCapacityWindow();

        MvcResult attempt = mvc.perform(delete(capacityPath(windowId))
                        .with(tokenFor(READ_ONLY_STAFF))
                        .header("Idempotency-Key", "station-capacity-delete-403-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deleteBody(1)))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("READ_ONLY_STAFF holds kitchen.ticket.read, not kitchen.station.manage")
                .isEqualTo(403);
        assertThat(capacityWindowExists(windowId))
                .as("a refused delete must not remove the row")
                .isTrue();
    }

    @Test
    @DisplayName("PUT .../station-capacity/{id} refuses a body with no expectedVersion")
    void updateRefusesABodyMissingExpectedVersion() throws Exception {
        UUID windowId = seedCapacityWindow();

        MvcResult attempt = mvc.perform(put(capacityPath(windowId))
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "station-capacity-update-400-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"windowStart":"09:00:00","windowEnd":"12:00:00","portionsPerHour":40}
                                """))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("expectedVersion is @NotNull on UpdateStationCapacityRequest")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("DELETE .../station-capacity/{id} refuses a body with no expectedVersion")
    void deleteRefusesABodyMissingExpectedVersion() throws Exception {
        UUID windowId = seedCapacityWindow();

        MvcResult attempt = mvc.perform(delete(capacityPath(windowId))
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "station-capacity-delete-400-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("expectedVersion is @NotNull on DeleteStationCapacityRequest")
                .isEqualTo(400);
    }

    @Test
    @DisplayName("PUT .../station-capacity/{id} corrects the window for a caller holding kitchen.station.manage")
    void updateSucceedsForAManager() throws Exception {
        UUID windowId = seedCapacityWindow();

        MvcResult result = mvc.perform(put(capacityPath(windowId))
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "station-capacity-update-200-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateBody("17:00:00", "21:00:00", 55, 1)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        Map<String, Object> row = capacityWindowRow(windowId);
        assertThat(row).containsEntry("portions_per_hour", 55);
        assertThat(row.get("version")).isEqualTo(2);
    }

    @Test
    @DisplayName("DELETE .../station-capacity/{id} removes the window for a caller holding kitchen.station.manage")
    void deleteSucceedsForAManager() throws Exception {
        UUID windowId = seedCapacityWindow();

        MvcResult result = mvc.perform(delete(capacityPath(windowId))
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "station-capacity-delete-204-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deleteBody(1)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(204);
        assertThat(capacityWindowExists(windowId)).isFalse();
    }

    // ---------------------------------------------------------- routing rules (row 4.2g)

    @Test
    @DisplayName("GET .../routing-rules answers the brand rule route() wrote, for a caller "
            + "holding only kitchen.ticket.read")
    void getRoutingRuleSucceedsForReadOnlyStaff() throws Exception {
        UUID categoryId = seedCategory();
        UUID ruleId = seedBrandRule(categoryId, "GRILL");

        MvcResult result = mvc.perform(get(routingRulesPath())
                        .queryParam("categoryId", categoryId.toString())
                        .with(tokenFor(READ_ONLY_STAFF)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString();
        assertThat(body).contains(ruleId.toString()).contains("GRILL").contains("\"layer\":\"BRAND\"");
        assertThat(body).as("no location override was written").contains("\"locationRule\":null");
    }

    @Test
    @DisplayName("GET .../routing-rules answers empty for a node nothing routes yet")
    void getRoutingRuleAnswersEmptyForAnUnroutedNode() throws Exception {
        UUID categoryId = seedCategory();

        MvcResult result = mvc.perform(get(routingRulesPath())
                        .queryParam("categoryId", categoryId.toString())
                        .with(tokenFor(READ_ONLY_STAFF)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains("\"brandRule\":null")
                .contains("\"locationRule\":null");
    }

    @Test
    @DisplayName("PUT .../routing-rules/{id} refuses a caller holding only kitchen.ticket.read")
    void updateRoutingRuleRefusesACallerWithoutStationManage() throws Exception {
        UUID categoryId = seedCategory();
        UUID ruleId = seedBrandRule(categoryId, "GRILL");

        MvcResult attempt = mvc.perform(put(routingRulePath(ruleId))
                        .with(tokenFor(READ_ONLY_STAFF))
                        .header("Idempotency-Key", "routing-rule-update-403-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRoutingRuleBody("COLD", null, 1)))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("READ_ONLY_STAFF holds kitchen.ticket.read, not kitchen.station.manage")
                .isEqualTo(403);
        assertThat(brandRuleRole(ruleId)).isEqualTo("GRILL");
    }

    @Test
    @DisplayName("PUT .../routing-rules/{id} changes the department for a caller holding "
            + "kitchen.station.manage, instead of the second POST's 409")
    void updateRoutingRuleSucceedsForAManager() throws Exception {
        UUID categoryId = seedCategory();
        UUID ruleId = seedBrandRule(categoryId, "GRILL");

        MvcResult result = mvc.perform(put(routingRulePath(ruleId))
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "routing-rule-update-200-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateRoutingRuleBody("COLD", null, 1)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(brandRuleRole(ruleId)).isEqualTo("COLD");
    }

    @Test
    @DisplayName("PUT .../routing-rules/{id} refuses a body with no expectedVersion")
    void updateRoutingRuleRefusesABodyMissingExpectedVersion() throws Exception {
        UUID categoryId = seedCategory();
        UUID ruleId = seedBrandRule(categoryId, "GRILL");

        MvcResult attempt = mvc.perform(put(routingRulePath(ruleId))
                        .with(tokenFor(MANAGER))
                        .header("Idempotency-Key", "routing-rule-update-400-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"stationRole":"COLD"}
                                """))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("expectedVersion is @NotNull on UpdateRoutingRuleRequest")
                .isEqualTo(400);
    }

    // ------------------------------------------------------------------ fixtures

    private static String updateBody(String start, String end, int portionsPerHour, int expectedVersion) {
        return "{\"windowStart\":\"%s\",\"windowEnd\":\"%s\",\"portionsPerHour\":%d,\"expectedVersion\":%d}"
                .formatted(start, end, portionsPerHour, expectedVersion);
    }

    private static String deleteBody(int expectedVersion) {
        return "{\"expectedVersion\":%d}".formatted(expectedVersion);
    }

    private static String capacityPath(UUID windowId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION
                + "/kitchen/station-capacity/" + windowId;
    }

    private static String routingRulesPath() {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION + "/kitchen/routing-rules";
    }

    private static String routingRulePath(UUID ruleId) {
        return routingRulesPath() + "/" + ruleId;
    }

    private static String updateRoutingRuleBody(
            @Nullable String stationRole, @Nullable UUID stationId, int expectedVersion) {
        String role = stationRole == null ? "null" : "\"" + stationRole + "\"";
        String station = stationId == null ? "null" : "\"" + stationId + "\"";
        return "{\"stationRole\":%s,\"stationId\":%s,\"expectedVersion\":%d}".formatted(role, station, expectedVersion);
    }

    private UUID seedCategory() {
        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status, version)
                        VALUES (:id, :t, :b, 'MAIN', 'Main', 'ACTIVE', 1)
                        """)
                .param("id", catalogId)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();
        UUID categoryId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO catalog.categories
                            (id, tenant_id, brand_id, catalog_id, code, sort_order, status, version)
                        VALUES (:id, :t, :b, :c, 'MAINS', 1, 'ACTIVE', 1)
                        """)
                .param("id", categoryId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("c", catalogId)
                .update();
        return categoryId;
    }

    /** A brand-layer rule for one category, exactly what {@code route()} writes. */
    private UUID seedBrandRule(UUID categoryId, String role) {
        UUID ruleId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO kitchen.brand_routing_rules
                            (id, tenant_id, brand_id, category_id, station_role, version)
                        VALUES (:id, :t, :b, :c, :role, 1)
                        """)
                .param("id", ruleId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("c", categoryId)
                .param("role", role)
                .update();
        return ruleId;
    }

    private String brandRuleRole(UUID ruleId) {
        return jdbc.sql("SELECT station_role FROM kitchen.brand_routing_rules WHERE id = :id")
                .param("id", ruleId)
                .query(String.class)
                .single();
    }

    private Map<String, Object> capacityWindowRow(UUID windowId) {
        return jdbc.sql("SELECT portions_per_hour, version FROM kitchen.station_capacity WHERE id = :id")
                .param("id", windowId)
                .query((rs, n) -> Map.<String, Object>of(
                        "portions_per_hour", rs.getInt("portions_per_hour"), "version", rs.getInt("version")))
                .single();
    }

    private boolean capacityWindowExists(UUID windowId) {
        return jdbc.sql("SELECT count(*) FROM kitchen.station_capacity WHERE id = :id")
                        .param("id", windowId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    /** A fresh 09:00-12:00, 30-portion-an-hour window on a Tuesday (weekday 2), one per test. */
    private UUID seedCapacityWindow() {
        UUID windowId = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO kitchen.station_capacity
                            (id, tenant_id, brand_id, location_id, station_id, weekday,
                             window_start, window_end, portions_per_hour, version)
                        VALUES (:id, :tenantId, :brandId, :locationId, :stationId, 2,
                                '09:00:00', '12:00:00', 30, 1)
                        """)
                .param("id", windowId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("stationId", stationId)
                .update();
        return windowId;
    }

    private void seedTenancyAndStation() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'station-capacity-http', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
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

        stationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO kitchen.stations (id, tenant_id, brand_id, location_id, code, role,
                    display_name_ru, display_name_uz, display_name_en, sort_order, is_fallback,
                    status, version)
                VALUES (:id, :t, :b, :l, 'GRILL', 'GRILL', 'Гриль', 'Gril', 'Grill', 1, true,
                        'ACTIVE', 1)
                """)
                .param("id", stationId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("l", LOCATION)
                .update();
    }

    private void grant(String subject, PlatformRole role, UUID locationId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :scopeId,
                        'ACTIVE', 'test-fixture', 'kitchen station-capacity http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + locationId).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeId", locationId)
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
