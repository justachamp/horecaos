package uz.horecaos.platform.tenancy.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.EntitlementGate;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.staff.StaffDirectory;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * «Find a setting» and «Locked by plan», over HTTP against the real stores (settings.md §10.0 and §1.2, gap
 * map rows {@code 10.0} and {@code X.1} of §10): {@code GET .../configuration/placements} says where each
 * value and each listed policy is set, and a key whose plan feature is missing is reported locked and refuses
 * a write with {@code ENTITLEMENT_REQUIRED}.
 *
 * <p>The entitlement gate is a stand-in the test controls: the real one answers from the tenant's plan and
 * the platform's enforcement mode, which under ADR 0021's meter-only default never refuses a feature, so the
 * lock could not otherwise be shown. Everything else -- the resolver, the author, the capability check, the
 * placements read -- is the real thing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsConfigurationPlacementsEndpointTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID TENANT = UUID.fromString("018fb210-3000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb210-3000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fb210-3000-7000-8000-0000000000c1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fb210-3000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018fb210-3000-7000-8000-0000000000b2");

    private static final String OWNER = "placements-owner";
    private static final String LOCATION_STAFF = "placements-location-staff";
    private static final String OTHER_OWNER = "placements-other-owner";

    private static final String CONFIG = "/api/v1/operations/tenants/" + TENANT + "/configuration";
    private static final String ASSISTANT_ENTITLEMENT = "assistant.answering.enabled";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the configuration placements endpoint test");
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

    @MockitoBean
    @SuppressWarnings("NullAway")
    private EntitlementGate gate;

    @MockitoBean
    @SuppressWarnings("NullAway")
    private StaffDirectory staff;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values").update();
        jdbc.sql("TRUNCATE TABLE tenant.policies CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant(TENANT);
        insertBrand(TENANT, BRAND, "Rayhon");
        insertLocation(TENANT, BRAND, LOCATION, "Chilonzor");
        insertTenant(OTHER_TENANT);
        insertBrand(OTHER_TENANT, OTHER_BRAND, "Other brand");
        grant(OWNER, PlatformRole.TENANT_OWNER, TENANT, TENANT);
        grant(LOCATION_STAFF, PlatformRole.LOCATION_STAFF, LOCATION, TENANT);
        grant(OTHER_OWNER, PlatformRole.TENANT_OWNER, OTHER_TENANT, OTHER_TENANT);

        when(staff.namesOf(eq(TENANT), any())).thenReturn(Map.of(OWNER, "Aziza Karimova"));
        when(gate.checkFeature(any(), any())).thenReturn(Optional.empty());
        when(gate.checkFeature(any(), eq(ASSISTANT_ENTITLEMENT)))
                .thenReturn(Optional.of(new EntitlementGate.Answer(
                        false, "Whether the grounded assistant answers customers", "/api/v1/control-plane/plans")));
    }

    // ----------------------------------------------------- where each value is set

    @Test
    @DisplayName("every visible key is listed, and a value shows at the tenant, the brand and the branch that hold it")
    void placementsSayWhereEachValueIsSet() throws Exception {
        String code = ConfigurationKeys.CART_EXPIRY_MINUTES.code();
        set(code, "TENANT", null, null, 180, "tenant window");
        set(code, "BRAND", BRAND, null, 120, "brand window");
        set(code, "LOCATION", BRAND, LOCATION, 60, "branch window");

        JsonNode body = json(get(CONFIG + "/placements").with(tokenFor(OWNER)), 200);

        assertThat(keyCodes(body))
                .containsExactlyElementsOf(ConfigurationKeys.all().stream()
                        .filter(key -> key.tenantVisible())
                        .map(key -> key.code())
                        .sorted()
                        .toList())
                .doesNotContain(ConfigurationKeys.QUOTE_TTL_SECONDS.code());
        JsonNode cart = entry(body, code);
        assertThat(cart.get("placements")).hasSize(3);
        JsonNode atTenant = cart.get("placements").get(0);
        assertThat(atTenant.get("scopeType").asString()).isEqualTo("TENANT");
        assertThat(atTenant.get("displayValue").asString()).isEqualTo("180");
        assertThat(atTenant.get("changedByName").asString()).isEqualTo("Aziza Karimova");
        JsonNode atBrand = cart.get("placements").get(1);
        assertThat(atBrand.get("scopeType").asString()).isEqualTo("BRAND");
        assertThat(atBrand.get("brandName").asString()).isEqualTo("Rayhon");
        assertThat(atBrand.get("displayValue").asString()).isEqualTo("120");
        JsonNode atBranch = cart.get("placements").get(2);
        assertThat(atBranch.get("scopeType").asString()).isEqualTo("LOCATION");
        assertThat(atBranch.get("brandName").asString()).isEqualTo("Rayhon");
        assertThat(atBranch.get("locationName").asString()).isEqualTo("Chilonzor");
        assertThat(atBranch.get("displayValue").asString()).isEqualTo("60");
        assertThat(entry(body, ConfigurationKeys.CATALOG_USE_STOCK_LOGIC.code()).get("placements"))
                .as("a key nobody set has no placement")
                .isEmpty();
        assertThat(body.toString()).doesNotContain(OWNER);
    }

    @Test
    @DisplayName("another tenant's values and policies never appear in this tenant's answer")
    void anotherTenantsRowsStayThere() throws Exception {
        String code = ConfigurationKeys.CART_EXPIRY_MINUTES.code();
        mvc.perform(post("/api/v1/operations/tenants/" + OTHER_TENANT + "/configuration/keys/" + code + "/values")
                        .with(tokenFor(OTHER_OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "placements-other-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setBody("TENANT", null, null, 77, "theirs")))
                .andReturn();
        insertPolicy(OTHER_TENANT, "ordering.lateness", "TENANT", null, 3, OTHER_OWNER);

        JsonNode body = json(get(CONFIG + "/placements").with(tokenFor(OWNER)), 200);

        assertThat(entry(body, code).get("placements")).isEmpty();
        assertThat(body.toString()).doesNotContain("77");
        assertThat(policy(body, "ordering.lateness").get("placements")).isEmpty();
    }

    @Test
    @DisplayName("a deliberate «unset here» is a placement with no value")
    void anExplicitNull() throws Exception {
        String nulled = ConfigurationKeys.CART_EXPIRY_MINUTES.code();
        MvcResult unset = mvc.perform(post(CONFIG + "/keys/" + nulled + "/values")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "placements-null-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scopeType":"BRAND","brandId":"%s","explicitNull":true,"reason":"unset on purpose"}
                                """.formatted(BRAND)))
                .andReturn();
        assertThat(unset.getResponse().getStatus())
                .as(unset.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        JsonNode body = json(get(CONFIG + "/placements").with(tokenFor(OWNER)), 200);

        JsonNode placement = entry(body, nulled).get("placements").get(0);
        assertThat(placement.get("explicitNull").asBoolean()).isTrue();
        assertThat(placement.get("displayValue").isNull()).isTrue();
        assertThat(placement.get("displayTruncated").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("each listed policy family shows its newest version, who published it and when, per scope")
    void policiesShowTheirNewestVersionPerScope() throws Exception {
        insertPolicy(TENANT, "ordering.lateness", "TENANT", null, 1, OWNER);
        insertPolicy(TENANT, "ordering.lateness", "TENANT", null, 2, OWNER);
        insertPolicy(TENANT, "ordering.lateness", "BRAND", BRAND, 1, OWNER);
        insertPolicy(TENANT, "not.a.listed.family", "TENANT", null, 1, OWNER);

        JsonNode body = json(get(CONFIG + "/placements").with(tokenFor(OWNER)), 200);

        JsonNode lateness = policy(body, "ordering.lateness").get("placements");
        assertThat(lateness).hasSize(2);
        assertThat(lateness.get(0).get("scopeType").asString()).isEqualTo("TENANT");
        assertThat(lateness.get(0).get("version").asInt())
                .as("the newest, not the first")
                .isEqualTo(2);
        assertThat(lateness.get(0).get("changedByName").asString()).isEqualTo("Aziza Karimova");
        assertThat(lateness.get(0).get("effectiveFrom").isNull()).isFalse();
        assertThat(lateness.get(1).get("scopeType").asString()).isEqualTo("BRAND");
        assertThat(lateness.get(1).get("brandName").asString()).isEqualTo("Rayhon");
        assertThat(lateness.get(1).get("version").asInt()).isEqualTo(1);
        assertThat(body.toString()).doesNotContain("not.a.listed.family");
    }

    @Test
    @DisplayName("location staff cannot ask, and a grant at another tenant cannot either")
    void whoMayAsk() throws Exception {
        assertThat(mvc.perform(get(CONFIG + "/placements").with(tokenFor(LOCATION_STAFF)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(mvc.perform(get(CONFIG + "/placements").with(tokenFor(OTHER_OWNER)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    // ------------------------------------------------------------ locked by plan

    @Test
    @DisplayName("a key whose plan feature is missing is reported locked in the list and the resolution")
    void theListAndTheResolutionReportTheLock() throws Exception {
        JsonNode keys = json(get(CONFIG + "/keys").with(tokenFor(OWNER)), 200);

        @Nullable JsonNode assistant = null;
        for (JsonNode key : keys) {
            String code = key.get("code").asString();
            if ("assistant.enabled".equals(code)) {
                assistant = key;
            } else if (code.startsWith("assistant.disclosure_text_")) {
                // The three disclosure wordings are locked with the switch they belong to.
                assertThat(key.get("entitledBy").asString()).as(code).isEqualTo(ASSISTANT_ENTITLEMENT);
                assertThat(key.get("lockedByPlan").get("entitlementKey").asString())
                        .as(code)
                        .isEqualTo(ASSISTANT_ENTITLEMENT);
            } else {
                assertThat(key.path("lockedByPlan").isNull()
                                || key.path("lockedByPlan").isMissingNode())
                        .as(code)
                        .isTrue();
            }
        }
        JsonNode found = Objects.requireNonNull(assistant, "assistant.enabled is in the list");
        assertThat(found.get("entitledBy").asString()).isEqualTo(ASSISTANT_ENTITLEMENT);
        assertThat(found.get("lockedByPlan").get("entitlementKey").asString()).isEqualTo(ASSISTANT_ENTITLEMENT);
        assertThat(found.get("lockedByPlan").get("description").asString()).contains("assistant");

        JsonNode resolution = json(
                get(CONFIG + "/keys/assistant.enabled/resolution")
                        .with(tokenFor(OWNER))
                        .queryParam("scopeType", "BRAND")
                        .queryParam("brandId", BRAND.toString()),
                200);
        assertThat(resolution.get("lockedByPlan").get("entitlementKey").asString())
                .isEqualTo(ASSISTANT_ENTITLEMENT);
    }

    @Test
    @DisplayName("a write to a locked key is a 403 ENTITLEMENT_REQUIRED naming the feature, and stores nothing")
    void aWriteToALockedKeyIsRefusedForThePlan() throws Exception {
        MvcResult refused = mvc.perform(post(CONFIG + "/keys/assistant.enabled/values")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "placements-locked-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scopeType":"BRAND","brandId":"%s","explicitNull":false,"booleanValue":true,"reason":"switch it on"}
                                """.formatted(BRAND)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString(UTF_8))
                .contains("ENTITLEMENT_REQUIRED")
                .contains(ASSISTANT_ENTITLEMENT)
                .doesNotContain("INSUFFICIENT_CAPABILITY");
        assertThat(jdbc.sql("SELECT count(*) FROM tenant.configuration_values WHERE tenant_id = :t")
                        .param("t", TENANT)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("once the plan includes the feature the same key is unlocked and the same write succeeds")
    void theSameWriteSucceedsOncethePlanHasTheFeature() throws Exception {
        when(gate.checkFeature(any(), eq(ASSISTANT_ENTITLEMENT)))
                .thenReturn(
                        Optional.of(new EntitlementGate.Answer(true, "the assistant", "/api/v1/control-plane/plans")));

        MvcResult written = mvc.perform(post(CONFIG + "/keys/assistant.enabled/values")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "placements-unlocked-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scopeType":"BRAND","brandId":"%s","explicitNull":false,"booleanValue":true,"reason":"switch it on"}
                                """.formatted(BRAND)))
                .andReturn();

        assertThat(written.getResponse().getStatus()).isEqualTo(200);
        JsonNode keys = json(get(CONFIG + "/keys").with(tokenFor(OWNER)), 200);
        for (JsonNode key : keys) {
            assertThat(key.path("lockedByPlan").isNull()
                            || key.path("lockedByPlan").isMissingNode())
                    .as(key.get("code").asString())
                    .isTrue();
        }
    }

    // ---------------------------------------------------------------- helpers

    private void set(String code, String scope, @Nullable UUID brand, @Nullable UUID location, int value, String reason)
            throws Exception {
        MvcResult result = mvc.perform(post(CONFIG + "/keys/" + code + "/values")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "placements-" + scope + code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scopeType":"%s","brandId":%s,"locationId":%s,"explicitNull":false,"integerValue":%d,"reason":"%s"}
                                """.formatted(
                                        scope,
                                        brand == null ? "null" : "\"" + brand + "\"",
                                        location == null ? "null" : "\"" + location + "\"",
                                        value,
                                        reason)))
                .andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString())
                .isEqualTo(200);
    }

    private static String setBody(
            String scope, @Nullable UUID brand, @Nullable UUID location, int value, String reason) {
        return """
                {"scopeType":"%s","brandId":%s,"locationId":%s,"explicitNull":false,"integerValue":%d,"reason":"%s"}
                """.formatted(
                        scope,
                        brand == null ? "null" : "\"" + brand + "\"",
                        location == null ? "null" : "\"" + location + "\"",
                        value,
                        reason);
    }

    private JsonNode json(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, int status)
            throws Exception {
        MvcResult result = mvc.perform(request).andReturn();
        assertThat(result.getResponse().getStatus())
                .as(result.getResponse().getContentAsString(UTF_8))
                .isEqualTo(status);
        return JSON.readTree(result.getResponse().getContentAsString(UTF_8));
    }

    private static List<String> keyCodes(JsonNode body) {
        List<String> codes = new java.util.ArrayList<>();
        body.get("keys").forEach(key -> codes.add(key.get("keyCode").asString()));
        return codes;
    }

    private static JsonNode entry(JsonNode body, String code) {
        for (JsonNode key : body.get("keys")) {
            if (code.equals(key.get("keyCode").asString())) {
                return key;
            }
        }
        throw new AssertionError("no placement entry for " + code);
    }

    private static JsonNode policy(JsonNode body, String code) {
        for (JsonNode family : body.get("policies")) {
            if (code.equals(family.get("policyCode").asString())) {
                return family;
            }
        }
        throw new AssertionError("no policy family " + code);
    }

    private void insertPolicy(
            UUID tenantId, String code, String scope, @Nullable UUID brandId, int version, String approvedBy) {
        jdbc.sql("""
                INSERT INTO tenant.policies (id, key_code, scope_type, tenant_id, brand_id, version, status,
                    document, document_hash, valid_from, created_by, approved_by)
                VALUES (:id, :code, :scope, :tenantId, :brandId, :version, 'ACTIVE', '{}'::jsonb,
                    repeat('a', 64), :validFrom, :createdBy, :approvedBy)
                """)
                .param("id", UUID.randomUUID())
                .param("code", code)
                .param("scope", scope)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("version", version)
                .param(
                        "validFrom",
                        Instant.parse("2026-10-01T08:00:00Z")
                                .plusSeconds(version)
                                .atOffset(ZoneOffset.UTC))
                .param("createdBy", approvedBy)
                .param("approvedBy", approvedBy)
                .update();
    }

    private void insertTenant(UUID tenantId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "placements-" + tenantId)
                .update();
    }

    private void insertBrand(UUID tenantId, UUID brandId, String name) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, :name, 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("code", "B" + brandId.toString().substring(33).toUpperCase(java.util.Locale.ROOT))
                .param("slug", "brand-" + brandId)
                .param("name", name)
                .update();
    }

    private void insertLocation(UUID tenantId, UUID brandId, UUID locationId, String name) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CHI', 'chilonzor', :name, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("name", name)
                .update();
    }

    private void grant(String subject, PlatformRole role, UUID scopeId, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'configuration placements endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", role.scopeType().name())
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
