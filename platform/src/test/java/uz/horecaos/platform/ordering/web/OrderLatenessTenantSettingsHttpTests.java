package uz.horecaos.platform.ordering.web;

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
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.application.port.ConfigurationValueCache;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * Gap map row {@code X.39}: a tenant's at-risk threshold and late colour, set
 * through the ordinary ADR 0030 configuration surface the order-policy card
 * already uses, and read back where both boards read it --
 * {@code GET .../orders/lateness-policy}.
 *
 * <p>End to end and over HTTP on purpose. The two halves are owned by different
 * modules (tenancy stores and versions the value, ordering overlays it on the
 * {@code ordering.lateness} document), so a passing test on either half alone
 * says nothing about whether a value an owner types into the card reaches the
 * board.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OrderLatenessTenantSettingsHttpTests {

    private static final UUID TENANT = UUID.fromString("018f9d10-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9d10-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9d10-4000-7000-8000-0000000000c1");
    private static final UUID SIBLING_LOCATION = UUID.fromString("018f9d10-4000-7000-8000-0000000000c2");

    private static final String OWNER = "lateness-settings-owner";

    private static final String CONFIG = "/api/v1/operations/tenants/" + TENANT + "/configuration";
    private static final String AT_RISK = "ordering.at_risk_before_minutes";
    private static final String LATE_COLOUR = "ordering.late_colour";
    private static final String LATE_THRESHOLD = "ordering.late_order_threshold_minutes";

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
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    private ConfigurationValueCache configurationCache;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'lateness-settings', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        insertLocation(LOCATION, "CHI", "chilonzor");
        insertLocation(SIBLING_LOCATION, "YUN", "yunusobod");
        grant(OWNER, PlatformRole.TENANT_OWNER, TENANT);

        // The fixture reuses one tenant id across tests and truncates the table underneath the
        // resolver's cache, so an earlier test's value would otherwise keep resolving here for up
        // to the cache's TTL -- a stale read the production writer never leaves (it evicts on set).
        for (String code : List.of(AT_RISK, LATE_COLOUR, LATE_THRESHOLD)) {
            configurationCache.evict(code, ResourceScope.tenant(TENANT));
            configurationCache.evict(code, ResourceScope.brand(TENANT, BRAND));
            configurationCache.evict(code, ResourceScope.location(TENANT, BRAND, LOCATION));
            configurationCache.evict(code, ResourceScope.location(TENANT, BRAND, SIBLING_LOCATION));
        }
    }

    @Test
    @DisplayName("with nothing set the board reads the platform's five minutes and no tenant colour")
    void theBoardReadsThePlatformDefaultsUntilATenantSetsSomething() throws Exception {
        JsonNode policy = latenessPolicy(LOCATION);

        for (String mode : List.of("delivery", "pickup", "dineIn")) {
            assertThat(policy.get(mode).get("atRiskBeforeSeconds").asInt())
                    .as(mode)
                    .isEqualTo(300);
        }
        assertThat(policy.get("lateColour").isNull()).isTrue();
    }

    @Test
    @DisplayName("an at-risk threshold set for the brand reaches every mode of every location under it")
    void aBrandAtRiskThresholdReachesTheBoardForEveryMode() throws Exception {
        setValue(AT_RISK, "BRAND", null, "{\"integerValue\":10}", "brand-wide earlier warning");

        JsonNode policy = latenessPolicy(LOCATION);
        for (String mode : List.of("delivery", "pickup", "dineIn")) {
            assertThat(policy.get(mode).get("atRiskBeforeSeconds").asInt())
                    .as(mode)
                    .isEqualTo(600);
        }
        assertThat(policy.get("delivery").get("lateAfterSeconds").asInt())
                .as("only the at-risk edge moved")
                .isZero();
        assertThat(policy.get("delivery").get("noPromiseFallbackSeconds").asInt())
                .isEqualTo(2700);
    }

    @Test
    @DisplayName(
            "a location override wins for that location alone, and reverting to inherit restores the brand's value")
    void aLocationOverrideBeatsTheBrandAndRevertRestoresIt() throws Exception {
        setValue(AT_RISK, "BRAND", null, "{\"integerValue\":10}", "brand-wide");
        MvcResult located = setValue(AT_RISK, "LOCATION", LOCATION, "{\"integerValue\":2}", "the counter is small");
        long version = JSON.readTree(located.getResponse().getContentAsString())
                .get("version")
                .asLong();

        assertThat(latenessPolicy(LOCATION)
                        .get("pickup")
                        .get("atRiskBeforeSeconds")
                        .asInt())
                .isEqualTo(120);
        assertThat(latenessPolicy(SIBLING_LOCATION)
                        .get("pickup")
                        .get("atRiskBeforeSeconds")
                        .asInt())
                .as("the sibling still inherits the brand's ten minutes")
                .isEqualTo(600);

        // The card's "revert to inherited": an explicit null at exactly the location, with the
        // version read from the resolution -- the If-Match half of the contract.
        MvcResult reverted = mvc.perform(post(CONFIG + "/keys/" + AT_RISK + "/values")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "revert-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("LOCATION", LOCATION, "{\"explicitNull\":true}", version, "back to brand")))
                .andReturn();
        assertThat(reverted.getResponse().getStatus())
                .as(reverted.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(latenessPolicy(LOCATION)
                        .get("pickup")
                        .get("atRiskBeforeSeconds")
                        .asInt())
                .isEqualTo(600);
    }

    @Test
    @DisplayName("zero minutes is a real setting: warn only at the promise itself")
    void zeroMinutesIsAccepted() throws Exception {
        setValue(AT_RISK, "TENANT", null, "{\"integerValue\":0}", "no early warning");

        assertThat(latenessPolicy(LOCATION)
                        .get("delivery")
                        .get("atRiskBeforeSeconds")
                        .asInt())
                .isZero();
    }

    @Test
    @DisplayName("a tenant late colour is served to the board, lower-cased")
    void aLateColourReachesTheBoard() throws Exception {
        setValue(LATE_COLOUR, "TENANT", null, "{\"stringValue\":\"#8A3FFC\"}", "our brand's alarm colour");

        assertThat(latenessPolicy(LOCATION).get("lateColour").asText()).isEqualTo("#8a3ffc");
        assertThat(latenessPolicy(SIBLING_LOCATION).get("lateColour").asText()).isEqualTo("#8a3ffc");

        // The write is audited by the value author with a before/after (ADR 0027), attributed to the
        // owner and carrying the reason typed into the card -- OrderLatenessPolicyService itself makes
        // no write, so this is the audit trail for row X.39's two settings.
        List<String> facts = jdbc.sql("""
                        SELECT row_to_json(e)::text FROM (
                            SELECT actor_subject, reason, change_document::text AS change_document
                            FROM audit.audit_events
                            WHERE action_code = 'tenant.configuration_value.set'
                              AND change_document -> 'keyCode' ->> 'after' = 'ordering.late_colour'
                        ) e
                        """).query(String.class).list();
        assertThat(facts).hasSize(1);
        JsonNode fact = JSON.readTree(facts.getFirst());
        assertThat(fact.get("actor_subject").asText()).isEqualTo(OWNER);
        assertThat(fact.get("reason").asText()).isEqualTo("our brand's alarm colour");
        JsonNode change = JSON.readTree(fact.get("change_document").asText());
        assertThat(change.get("value").get("before").isNull()).isTrue();
        assertThat(change.get("value").get("after").asText()).isEqualTo("#8A3FFC");
    }

    @Test
    @DisplayName("a blank late colour keeps the design-system token: the board is told there is none")
    void aBlankColourMeansNoTenantColour() throws Exception {
        setValue(LATE_COLOUR, "TENANT", null, "{\"stringValue\":\"#8a3ffc\"}", "set");
        long version = 0;
        MvcResult blanked = mvc.perform(post(CONFIG + "/keys/" + LATE_COLOUR + "/values")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "blank-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("TENANT", null, "{\"stringValue\":\"\"}", version, "use the token again")))
                .andReturn();
        assertThat(blanked.getResponse().getStatus())
                .as(blanked.getResponse().getContentAsString())
                .isEqualTo(200);

        assertThat(latenessPolicy(LOCATION).get("lateColour").isNull()).isTrue();
    }

    @Test
    @DisplayName(
            "a value that is not exactly #rrggbb is refused at write time, so nothing else can reach a style binding")
    void aMalformedColourIsRefused() throws Exception {
        for (String bad : List.of("red", "#fff", "#12345g", "url(javascript:alert(1))", "#8a3ffc; color: red")) {
            MvcResult refused = mvc.perform(post(CONFIG + "/keys/" + LATE_COLOUR + "/values")
                            .with(tokenFor(OWNER))
                            .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "bad-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body(
                                    "TENANT",
                                    null,
                                    "{\"stringValue\":\"" + bad.replace("\"", "'") + "\"}",
                                    null,
                                    "attempt")))
                    .andReturn();
            assertThat(refused.getResponse().getStatus()).as(bad).isEqualTo(400);
            assertThat(refused.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
        }
        assertThat(latenessPolicy(LOCATION).get("lateColour").isNull())
                .as("nothing was stored")
                .isTrue();
    }

    @Test
    @DisplayName("a negative or day-plus at-risk threshold is refused rather than stored for the board to trip on")
    void anOutOfRangeThresholdIsRefused() throws Exception {
        for (long bad : new long[] {-1, 1441, 100_000}) {
            MvcResult refused = mvc.perform(post(CONFIG + "/keys/" + AT_RISK + "/values")
                            .with(tokenFor(OWNER))
                            .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "range-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("TENANT", null, "{\"integerValue\":" + bad + "}", null, "attempt")))
                    .andReturn();
            assertThat(refused.getResponse().getStatus()).as("value " + bad).isEqualTo(400);
        }
        assertThat(latenessPolicy(LOCATION)
                        .get("delivery")
                        .get("atRiskBeforeSeconds")
                        .asInt())
                .isEqualTo(300);
    }

    @Test
    @DisplayName(
            "a second writer holding a stale version is refused, so two owners cannot silently overwrite each other")
    void aStaleVersionIsRefused() throws Exception {
        setValue(LATE_COLOUR, "TENANT", null, "{\"stringValue\":\"#8a3ffc\"}", "first");

        MvcResult stale = mvc.perform(post(CONFIG + "/keys/" + LATE_COLOUR + "/values")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "stale-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("TENANT", null, "{\"stringValue\":\"#ff832b\"}", 5L, "second")))
                .andReturn();

        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(latenessPolicy(LOCATION).get("lateColour").asText()).isEqualTo("#8a3ffc");
    }

    // ---------------------------- ADR 0150: the late-order threshold is the no-promise default

    @Test
    @DisplayName("with the late-order threshold unset an unpromised order is late at forty-five minutes: today's line")
    void theNoPromiseFallbackIsFortyFiveMinutesUntilATenantSetsTheThreshold() throws Exception {
        JsonNode policy = latenessPolicy(LOCATION);

        for (String mode : List.of("delivery", "pickup", "dineIn")) {
            assertThat(policy.get(mode).get("noPromiseFallbackSeconds").asInt())
                    .as(mode)
                    .isEqualTo(2700);
        }
    }

    @Test
    @DisplayName("a tenant-wide threshold of 20 moves the no-promise fallback of every mode and nothing else")
    void aTenantThresholdIsTheNoPromiseFallbackOfEveryModeAndNothingElse() throws Exception {
        setValue(LATE_THRESHOLD, "TENANT", null, "{\"integerValue\":20}", "aggregator orders go red sooner");

        JsonNode policy = latenessPolicy(LOCATION);
        for (String mode : List.of("delivery", "pickup", "dineIn")) {
            assertThat(policy.get(mode).get("noPromiseFallbackSeconds").asInt())
                    .as(mode)
                    .isEqualTo(1200);
            assertThat(policy.get(mode).get("lateAfterSeconds").asInt())
                    .as("a promised order's grace is not the scalar's to move")
                    .isZero();
            assertThat(policy.get(mode).get("atRiskBeforeSeconds").asInt()).isEqualTo(300);
        }
    }

    @Test
    @DisplayName("a threshold written at a scope reaches the resolutions already cached beneath it")
    void aScalarWriteIsNotMaskedByTheResolutionsCachedBeneathIt() throws Exception {
        // Both locations are read first, so each holds a cached answer under its own key. A write that
        // evicted only the key it wrote would leave them serving forty-five minutes for up to a minute.
        assertThat(fallbackOf(latenessPolicy(LOCATION))).isEqualTo(2700);
        assertThat(fallbackOf(latenessPolicy(SIBLING_LOCATION))).isEqualTo(2700);

        setValue(LATE_THRESHOLD, "TENANT", null, "{\"integerValue\":20}", "tenant-wide");
        assertThat(fallbackOf(latenessPolicy(LOCATION))).isEqualTo(1200);
        assertThat(fallbackOf(latenessPolicy(SIBLING_LOCATION))).isEqualTo(1200);

        setValue(LATE_THRESHOLD, "BRAND", null, "{\"integerValue\":30}", "this brand is slower");
        assertThat(fallbackOf(latenessPolicy(LOCATION))).isEqualTo(1800);
        assertThat(fallbackOf(latenessPolicy(SIBLING_LOCATION))).isEqualTo(1800);
    }

    @Test
    @DisplayName("a narrower scope's threshold wins, and reverting it restores the one above")
    void aNarrowerScopesThresholdWinsAndRevertRestoresTheOneAbove() throws Exception {
        setValue(LATE_THRESHOLD, "TENANT", null, "{\"integerValue\":30}", "tenant-wide");
        MvcResult located = setValue(LATE_THRESHOLD, "LOCATION", LOCATION, "{\"integerValue\":10}", "a small counter");
        long version = JSON.readTree(located.getResponse().getContentAsString())
                .get("version")
                .asLong();

        assertThat(fallbackOf(latenessPolicy(LOCATION))).isEqualTo(600);
        assertThat(fallbackOf(latenessPolicy(SIBLING_LOCATION)))
                .as("the sibling still inherits the tenant's thirty minutes")
                .isEqualTo(1800);

        MvcResult reverted = mvc.perform(post(CONFIG + "/keys/" + LATE_THRESHOLD + "/values")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "revert-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("LOCATION", LOCATION, "{\"explicitNull\":true}", version, "back to the tenant")))
                .andReturn();
        assertThat(reverted.getResponse().getStatus())
                .as(reverted.getResponse().getContentAsString())
                .isEqualTo(200);
        assertThat(fallbackOf(latenessPolicy(LOCATION))).isEqualTo(1800);
    }

    @Test
    @DisplayName("a threshold outside one minute to a day is refused at write time")
    void anOutOfRangeLateOrderThresholdIsRefused() throws Exception {
        for (long bad : new long[] {0, -1, 601, 100_000}) {
            MvcResult refused = mvc.perform(post(CONFIG + "/keys/" + LATE_THRESHOLD + "/values")
                            .with(tokenFor(OWNER))
                            .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "range-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body("TENANT", null, "{\"integerValue\":" + bad + "}", null, "attempt")))
                    .andReturn();
            assertThat(refused.getResponse().getStatus()).as("value " + bad).isEqualTo(400);
        }
        assertThat(fallbackOf(latenessPolicy(LOCATION))).isEqualTo(2700);
    }

    private static int fallbackOf(JsonNode policy) {
        int delivery = policy.get("delivery").get("noPromiseFallbackSeconds").asInt();
        assertThat(policy.get("pickup").get("noPromiseFallbackSeconds").asInt()).isEqualTo(delivery);
        assertThat(policy.get("dineIn").get("noPromiseFallbackSeconds").asInt()).isEqualTo(delivery);
        return delivery;
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode latenessPolicy(UUID location) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/locations/"
                                + location + "/orders/lateness-policy")
                        .with(tokenFor(OWNER)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult setValue(
            String code, String scopeType, @Nullable UUID locationId, String valueJson, String reason)
            throws Exception {
        MvcResult result = mvc.perform(post(CONFIG + "/keys/" + code + "/values")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "set-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(scopeType, locationId, valueJson, null, reason)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
        return result;
    }

    /** {@code valueJson} is the object body of exactly one value field (or {@code explicitNull}). */
    private static String body(
            String scopeType,
            @Nullable UUID locationId,
            String valueJson,
            @Nullable Long expectedVersion,
            String reason) {
        // The console always sends explicitNull (ConfigurationApi.setValue), and Jackson 3 refuses a
        // body that omits the primitive -- so this sends what the console's real JSON carries.
        String inner = valueJson.substring(1, valueJson.length() - 1)
                + (valueJson.contains("explicitNull") ? "" : ",\"explicitNull\":false");
        return "{\"scopeType\":\"" + scopeType + "\""
                + (scopeType.equals("TENANT") ? "" : ",\"brandId\":\"" + BRAND + "\"")
                + (locationId == null ? "" : ",\"locationId\":\"" + locationId + "\"")
                + "," + inner
                + (expectedVersion == null ? "" : ",\"expectedVersion\":" + expectedVersion)
                + ",\"reason\":\"" + reason + "\"}";
    }

    private void insertLocation(UUID locationId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, 'Location', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'lateness settings endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", role.scopeType().name())
                .param("scopeId", tenantId)
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
