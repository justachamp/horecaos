package uz.horecaos.platform.tenancy.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

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
 * ADR 0038's legal entities and their location assignments, over HTTP (ADR
 * 0025, ADR 0031).
 *
 * <p>{@code LEGAL_ENTITY_MANAGE} decides whose name appears on every fiscal
 * receipt a branch issues, so the test that matters here is the same shape
 * {@code ApprovalPolicyEndpointTests} uses for the second-signature bar: a
 * tenant administrator holds most of this tenant's authority and must still be
 * refused, because a capability every senior role happens to carry has decided
 * nothing.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LegalEntityControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9a10-2000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9a10-2000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9a10-2000-7000-8000-0000000000c1");

    private static final String OWNER = "legal-entity-owner";
    private static final String ADMINISTRATOR = "legal-entity-administrator";
    private static final String FINANCE = "legal-entity-finance";

    private static final String ENTITIES = "/api/v1/control-plane/tenants/" + TENANT + "/legal-entities";

    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the legal entity endpoint test");
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
        // CASCADE reaches iam.roles (a foreign key to tenants) and, through
        // tenant.locations, tenant.legal_entities and
        // tenant.location_fiscal_assignments as well.
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant();
        insertBrandAndLocation();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        grant(ADMINISTRATOR, PlatformRole.TENANT_ADMIN);
        grant(FINANCE, PlatformRole.TENANT_FINANCE);
    }

    @Test
    void anOwnerCanRegisterActivateAndAssignAnEntity() throws Exception {
        MvcResult registered = mvc.perform(post(ENTITIES)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("OSHXONA", "123456789")))
                .andReturn();

        assertThat(registered.getResponse().getStatus()).isEqualTo(201);
        assertThat(registered.getResponse().getHeader("Location")).contains(ENTITIES);
        assertThat(registered.getResponse().getContentAsString())
                .contains("\"status\":\"DRAFT\"")
                .contains("\"tin\":\"123456789\"");
        UUID entityId = entityId();

        MvcResult activated = mvc.perform(post(ENTITIES + "/" + entityId + "/activate")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "activate-1")
                        .queryParam("expectedVersion", "1"))
                .andReturn();

        assertThat(activated.getResponse().getStatus()).isEqualTo(200);
        assertThat(activated.getResponse().getContentAsString()).contains("\"status\":\"ACTIVE\"");

        MvcResult assigned = mvc.perform(post(ENTITIES + "/" + entityId + "/assignments")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "assign-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"brandId":"%s","locationId":"%s","effectiveFrom":"2026-01-01"}
                                """.formatted(BRAND, LOCATION)))
                .andReturn();

        assertThat(assigned.getResponse().getStatus()).isEqualTo(201);
        assertThat(assigned.getResponse().getContentAsString())
                .as("the approver is the authenticated caller, never a client-supplied field")
                .contains("\"approvedBy\":\"" + OWNER + "\"")
                .contains("\"legalEntityId\":\"" + entityId + "\"");

        MvcResult history = mvc.perform(get(ENTITIES + "/brands/" + BRAND + "/locations/" + LOCATION + "/assignments")
                        .with(tokenFor(OWNER)))
                .andReturn();
        assertThat(history.getResponse().getStatus()).isEqualTo(200);
        assertThat(history.getResponse().getContentAsString()).contains(entityId.toString());
    }

    @Test
    void anAdministratorCannotRegisterAnEntity() throws Exception {
        MvcResult refused = mvc.perform(post(ENTITIES)
                        .with(tokenFor(ADMINISTRATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("REFUSED", "223456789")))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.LEGAL_ENTITY_MANAGE.code());
        assertThat(entityCount())
                .as("a refused caller must leave the registry exactly as it was")
                .isZero();
    }

    @Test
    void financeCanReadTheRegistryButCannotRegister() throws Exception {
        mvc.perform(post(ENTITIES)
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("READABLE", "323456789")));

        assertThat(mvc.perform(get(ENTITIES).with(tokenFor(FINANCE)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("finance reads the registry for the same reason it reads a fiscal document")
                .isEqualTo(200);

        MvcResult refused = mvc.perform(post(ENTITIES)
                        .with(tokenFor(FINANCE))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("FINANCE-CANNOT", "423456789")))
                .andReturn();
        assertThat(refused.getResponse().getStatus())
                .as("registering a company is the owner's alone, the same as a merchant binding")
                .isEqualTo(403);
    }

    @Test
    void registeringWithoutAnIdempotencyKeyIsRejected() throws Exception {
        MvcResult result = mvc.perform(post(ENTITIES)
                        .with(tokenFor(OWNER))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("NOKEY", "523456789")))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("IDEMPOTENCY_KEY_REQUIRED");
        assertThat(entityCount()).isZero();
    }

    @Test
    void aDuplicateTaxpayerNumberIsAProblemDetailsConflictNotA500() throws Exception {
        mvc.perform(post(ENTITIES)
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-dup-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("FIRST", "623456789")));

        MvcResult conflict = mvc.perform(post(ENTITIES)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-dup-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("SECOND", "623456789")))
                .andReturn();

        assertThat(conflict.getResponse().getStatus())
                .as("JdbcLegalEntityStore.explain raises a bare IllegalStateException for this; "
                        + "TenantApiErrorHandler must still answer Problem Details and not a 500")
                .isEqualTo(409);
        assertThat(conflict.getResponse().getContentAsString())
                .contains("RESOURCE_CONFLICT")
                .contains("taxpayer number");
        assertThat(entityCount()).isEqualTo(1);
    }

    @Test
    void anUnknownEntityIsNotFound() throws Exception {
        MvcResult result = mvc.perform(get(ENTITIES + "/" + UUID.randomUUID()).with(tokenFor(OWNER)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");
    }

    private UUID entityId() {
        return jdbc.sql("SELECT id FROM tenant.legal_entities WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT)
                .query(UUID.class)
                .single();
    }

    private long entityCount() {
        return jdbc.sql("SELECT count(*) FROM tenant.legal_entities WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT)
                .query(Long.class)
                .single();
    }

    private static String registerBody(String code, String tin) {
        return """
                {"code":"%s","legalName":"%s MCHJ","tin":"%s","vatRegistered":false,
                 "registeredAddress":"Tashkent","contactPhone":"+998901234567"}
                """.formatted(code, code, tin);
    }

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'legal-entity-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
    }

    private void insertBrandAndLocation() {
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
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'legal entity endpoint test')
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .update();
    }

    /**
     * Carries no realm role, so a refusal proves the ADR 0025 grant decided it
     * and not the bootstrap bypass a platform-admin token gets.
     */
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
