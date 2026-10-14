package uz.horecaos.platform.iam.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0186 over the real HTTP stack: a company defines, edits, gives away and closes a job, with
 * the bodies the console actually sends (a job without a {@code copiedFrom}, a version in
 * {@code If-Match}) - Jackson 3 refuses a missing primitive, and a hand-built request in a test
 * is the surest way to miss that.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantRoleEndpointTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final UUID TENANT = UUID.fromString("018fb200-3000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb200-3000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fb200-3000-7000-8000-0000000000c1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fb200-3000-7000-8000-0000000000a2");

    private static final String OWNER = "role-owner";
    private static final String ADMIN = "role-admin";
    private static final String OTHER_OWNER = "role-other-owner";
    private static final String BRANCH_MANAGER = "role-branch-manager";
    private static final String COOK = "role-cook";

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

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE iam.roles CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        for (Object[] tenant : new Object[][] {{TENANT, "role-endpoint-a"}, {OTHER_TENANT, "role-endpoint-b"}}) {
            jdbc.sql("""
                    INSERT INTO tenant.tenants
                        (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                    VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """).param("id", tenant[0]).param("slug", tenant[1]).update();
        }
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

        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);
        grant(ADMIN, PlatformRole.TENANT_ADMIN, "TENANT", TENANT, TENANT);
        grant(OTHER_OWNER, PlatformRole.TENANT_OWNER, "TENANT", OTHER_TENANT, OTHER_TENANT);
        grant(BRANCH_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", LOCATION, TENANT);
        grant(COOK, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION, TENANT);
    }

    // ------------------------------------------------------------------- create

    @Test
    @DisplayName("an owner defines a job with the body the console sends, and gets it back with its version")
    void anOwnerDefinesAJob() throws Exception {
        MvcResult created = post(rolesPath(), OWNER, """
                {"name":"Старший кассир","scopeType":"LOCATION",
                 "capabilities":["order.read","order.cancel"],
                 "reason":"The evening shift needs a cashier who can cancel"}
                """);

        assertThat(created.getResponse().getStatus()).isEqualTo(201);
        assertThat(created.getResponse().getHeader("ETag")).isEqualTo("W/\"1\"");
        JsonNode job = json(created);
        assertThat(job.get("code").asText()).matches("job-[0-9a-f]{10}");
        assertThat(job.get("name").asText()).isEqualTo("Старший кассир");
        assertThat(job.get("scopeType").asText()).isEqualTo("LOCATION");
        assertThat(texts(job.get("capabilities"))).containsExactly("order.cancel", "order.read");
        assertThat(job.get("version").asLong()).isEqualTo(1L);
        assertThat(job.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(job.get("holders").asInt()).isZero();

        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = 'iam.role.created'")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a create needs an Idempotency-Key, a reason, a level and at least one capability")
    void aCreateIsHeldToTheConventions() throws Exception {
        MvcResult noKey = mvc.perform(MockMvcRequestBuilders.post(rolesPath())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody("No key"))
                        .with(tokenFor(OWNER)))
                .andReturn();
        assertThat(noKey.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(noKey).get("code").asText()).isEqualTo("IDEMPOTENCY_KEY_REQUIRED");

        assertThat(post(rolesPath(), OWNER, """
                        {"name":"No reason","scopeType":"LOCATION","capabilities":["order.read"]}
                        """).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(post(rolesPath(), OWNER, """
                        {"name":"No level","capabilities":["order.read"],"reason":"x"}
                        """).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(post(rolesPath(), OWNER, """
                        {"name":"Nothing","scopeType":"LOCATION","capabilities":[],"reason":"x"}
                        """).getResponse().getStatus())
                .isEqualTo(400);
    }

    @Test
    @DisplayName("only iam.role.manage defines a job: an owner and an administrator may, a branch manager and a cook may not")
    void onlyTheRoleManagersMay() throws Exception {
        assertThat(post(rolesPath(), OWNER, createBody("From the owner")).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(post(rolesPath(), ADMIN, createBody("From the administrator")).getResponse().getStatus())
                .isEqualTo(201);
        assertThat(post(rolesPath(), BRANCH_MANAGER, createBody("From a branch manager"))
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(post(rolesPath(), COOK, createBody("From a cook")).getResponse().getStatus())
                .isEqualTo(403);
    }

    @Test
    @DisplayName("a capability the author does not hold is a 403 naming it, and the deny-list is a 400 even for the owner")
    void theAuthorHoldsWhatSheWritesAndTheDenyListHolds() throws Exception {
        MvcResult notHeld = post(rolesPath(), ADMIN, """
                {"name":"Выгрузка","scopeType":"TENANT","capabilities":["order.read","customer.pii.export"],"reason":"x"}
                """);
        assertThat(notHeld.getResponse().getStatus()).isEqualTo(403);
        assertThat(notHeld.getResponse().getContentAsString(UTF_8)).contains("customer.pii.export");

        MvcResult denied = post(rolesPath(), OWNER, """
                {"name":"Владелец 2","scopeType":"TENANT","capabilities":["order.read","iam.grant.manage"],"reason":"x"}
                """);
        assertThat(denied.getResponse().getStatus()).isEqualTo(400);
        assertThat(json(denied).get("code").asText()).isEqualTo("VALIDATION_FAILED");
    }

    // --------------------------------------------------------------- edit, close

    @Test
    @DisplayName("an edit carries If-Match, bumps the version, and a stale one is a 409")
    void anEditIsVersioned() throws Exception {
        String code = create(OWNER, "Старший кассир");

        assertThat(put(rolePath(code), OWNER, null, editBody("No version")).getResponse().getStatus())
                .isEqualTo(400);

        MvcResult edited = put(rolePath(code), OWNER, "W/\"1\"", editBody("Главный кассир"));
        assertThat(edited.getResponse().getStatus()).isEqualTo(200);
        assertThat(edited.getResponse().getHeader("ETag")).isEqualTo("W/\"2\"");
        assertThat(json(edited).get("name").asText()).isEqualTo("Главный кассир");

        MvcResult stale = put(rolePath(code), ADMIN, "W/\"1\"", editBody("Lost update"));
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(stale).get("code").asText()).isEqualTo("STALE_VERSION");
    }

    @Test
    @DisplayName("a job can be given through the ordinary grant route, shows its holder, and cannot be closed while held")
    void aJobIsGivenHeldAndCannotBeClosedWhileHeld() throws Exception {
        String code = create(OWNER, "Старший кассир");

        MvcResult granted = post(grantsPath(), OWNER, """
                {"principalSubject":"cashier-1","roleCode":"%s","brandId":"%s","locationId":"%s","reason":"Hired"}
                """.formatted(code, BRAND, LOCATION));
        assertThat(granted.getResponse().getStatus()).isEqualTo(200);

        MvcResult listed = get(rolesPath(), OWNER);
        JsonNode mine = custom(json(listed), code);
        assertThat(mine.get("name").asText()).isEqualTo("Старший кассир");
        assertThat(mine.get("custom").asBoolean()).isTrue();
        assertThat(mine.get("grantable").asBoolean()).isTrue();

        MvcResult refused = delete(rolePath(code), OWNER, "W/\"1\"", "{\"reason\":\"Closing\"}");
        assertThat(refused.getResponse().getStatus()).isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString(UTF_8)).contains("1 person");

        String grantId = jdbc.sql("SELECT id FROM iam.grants WHERE principal_subject = 'cashier-1'")
                .query(String.class)
                .single();
        MvcResult revoked = delete(grantsPath() + "/" + grantId, OWNER, null, "{\"reason\":\"Left\"}");
        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);

        MvcResult closed = delete(rolePath(code), OWNER, "W/\"1\"", "{\"reason\":\"Closing\"}");
        assertThat(closed.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(closed).get("status").asText()).isEqualTo("RETIRED");
        assertThat(custom(json(get(rolesPath(), OWNER)), code)).as("a closed job leaves the list").isNull();
    }

    // -------------------------------------------------------- reads, isolation

    @Test
    @DisplayName("the roles list carries the code-owned jobs and the company's own, and the capability catalogue says what is held")
    void theReadSide() throws Exception {
        String code = create(OWNER, "Старший кассир");

        JsonNode roles = json(get(rolesPath(), ADMIN));
        List<String> codes = texts(roles, "code");
        assertThat(codes).contains("tenant-owner", "location-staff", code);
        assertThat(roles.get(0).has("custom")).isTrue();
        JsonNode owner = byCode(roles, "tenant-owner");
        assertThat(owner.get("custom").asBoolean()).isFalse();
        assertThat(owner.get("grantable").asBoolean())
                .as("an administrator cannot give a job that carries the owner's authority")
                .isFalse();

        JsonNode catalogue = json(get(capabilitiesPath(), ADMIN));
        JsonNode pii = byCode(catalogue, "customer.pii.export");
        assertThat(pii.get("held").asBoolean()).isFalse();
        assertThat(pii.get("composable").asBoolean()).isTrue();
        JsonNode grantManage = byCode(catalogue, "iam.grant.manage");
        assertThat(grantManage.get("held").asBoolean()).isTrue();
        assertThat(grantManage.get("composable").asBoolean()).isFalse();
        assertThat(texts(catalogue, "code")).doesNotContain("platform.admin", "print.job.claim");

        assertThat(get(capabilitiesPath(), COOK).getResponse().getStatus()).isEqualTo(403);
        assertThat(get(rolesPath(), BRANCH_MANAGER).getResponse().getStatus())
                .as("the company-wide list is the tenant-wide granter's; a branch manager reads her own route")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("a branch manager sees the company's jobs on her own branch route, with grantable read back from the grant rule")
    void aBranchManagersRouteIncludesTheJobs() throws Exception {
        String branchJob = create(OWNER, "Старший кассир");
        MvcResult companyJob = post(rolesPath(), OWNER, """
                {"name":"Аналитик","scopeType":"TENANT","capabilities":["order.read"],"reason":"Reports"}
                """);
        String companyCode = json(companyJob).get("code").asText();

        MvcResult routed = get("/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION
                + "/grant-roles", BRANCH_MANAGER);

        assertThat(routed.getResponse().getStatus()).isEqualTo(200);
        JsonNode roles = json(routed);
        assertThat(byCode(roles, branchJob).get("grantable").asBoolean()).isTrue();
        assertThat(byCode(roles, companyCode).get("grantable").asBoolean())
                .as("a company-level job is not hers to give at a branch (ADR 0103)")
                .isFalse();
        assertThat(byCode(roles, "tenant-owner").get("grantable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("another company's owner can neither read, edit nor close this company's job")
    void anotherCompanyIsRefused() throws Exception {
        String code = create(OWNER, "Старший кассир");

        assertThat(put(rolePath(code), OTHER_OWNER, "W/\"1\"", editBody("Stolen")).getResponse().getStatus())
                .isEqualTo(403);
        assertThat(delete(rolePath(code), OTHER_OWNER, "W/\"1\"", "{\"reason\":\"x\"}")
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);

        // Through their own tenant's path, the same code is simply not a job there.
        MvcResult ownPath = put(
                "/api/v1/control-plane/tenants/" + OTHER_TENANT + "/roles/" + code,
                OTHER_OWNER,
                "W/\"1\"",
                editBody("Stolen"));
        assertThat(ownPath.getResponse().getStatus()).isEqualTo(404);
        assertThat(json(get("/api/v1/control-plane/tenants/" + OTHER_TENANT + "/roles", OTHER_OWNER))
                        .findValuesAsString("code"))
                .doesNotContain(code);
    }

    // ----------------------------------------------------------------- helpers

    private static String rolesPath() {
        return "/api/v1/control-plane/tenants/" + TENANT + "/roles";
    }

    private static String rolePath(String code) {
        return rolesPath() + "/" + code;
    }

    private static String capabilitiesPath() {
        return "/api/v1/control-plane/tenants/" + TENANT + "/capabilities";
    }

    private static String grantsPath() {
        return "/api/v1/control-plane/tenants/" + TENANT + "/grants";
    }

    private static String createBody(String name) {
        return """
                {"name":"%s","scopeType":"LOCATION","capabilities":["order.read","order.cancel"],"reason":"Needed"}
                """.formatted(name);
    }

    private static String editBody(String name) {
        return """
                {"name":"%s","capabilities":["order.read","order.cancel"],"reason":"Renamed"}
                """.formatted(name);
    }

    private String create(String subject, String name) throws Exception {
        return json(post(rolesPath(), subject, createBody(name))).get("code").asText();
    }

    private MvcResult get(String path, String subject) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).with(tokenFor(subject)))
                .andReturn();
    }

    private MvcResult post(String path, String subject, String body) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body)
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(tokenFor(subject)))
                .andReturn();
    }

    private MvcResult put(String path, String subject, String ifMatch, String body) throws Exception {
        var request = MockMvcRequestBuilders.put(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(tokenFor(subject));
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mvc.perform(request).andReturn();
    }

    private MvcResult delete(String path, String subject, String ifMatch, String body) throws Exception {
        var request = MockMvcRequestBuilders.delete(path)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(tokenFor(subject));
        if (ifMatch != null) {
            request.header("If-Match", ifMatch);
        }
        return mvc.perform(request).andReturn();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(UTF_8));
    }

    private static List<String> texts(JsonNode array) {
        return array.valueStream().map(JsonNode::asText).toList();
    }

    private static List<String> texts(JsonNode array, String field) {
        return array.valueStream().map(node -> node.get(field).asText()).toList();
    }

    private static JsonNode byCode(JsonNode array, String code) {
        return array.valueStream()
                .filter(node -> code.equals(node.get("code").asText()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no entry " + code));
    }

    private static JsonNode custom(JsonNode roles, String code) {
        return roles.valueStream()
                .filter(node -> code.equals(node.get("code").asText()))
                .findFirst()
                .orElse(null);
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'role endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeType + scopeId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
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
