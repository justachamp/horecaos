package uz.horecaos.platform.tenancy.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
 * ADR 0038's legal-entity registry, reachable from a tenant's own operations
 * surface (ADR 0057) rather than only from platform-staff control-plane.
 *
 * <p>Mirrors {@link LegalEntityControllerEndpointTests}, over {@link
 * OperationsLegalEntityController} instead: {@code LEGAL_ENTITY_MANAGE} is
 * owner-only, so this suite proves the operations path enforces exactly the
 * same single-role gate the control-plane one does — not a wider one, and not
 * a narrower one — plus the cross-tenant refusal that matters most on any
 * tenant-reachable surface.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsLegalEntityControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9d10-2000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9d10-2000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9d10-2000-7000-8000-0000000000c1");

    private static final UUID OTHER_TENANT = UUID.fromString("018f9d10-2000-7000-8000-0000000000a2");

    private static final String OWNER = "ops-legal-entity-owner";
    private static final String ADMINISTRATOR = "ops-legal-entity-administrator";
    private static final String OTHER_TENANT_OWNER = "ops-legal-entity-other-owner";

    private static final String ENTITIES = "/api/v1/operations/tenants/" + TENANT + "/legal-entities";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the operations legal entity endpoint test");
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
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant(TENANT);
        insertBrandAndLocation(TENANT, BRAND, LOCATION);
        insertTenant(OTHER_TENANT);
        grant(OWNER, PlatformRole.TENANT_OWNER, TENANT);
        grant(ADMINISTRATOR, PlatformRole.TENANT_ADMIN, TENANT);
        grant(OTHER_TENANT_OWNER, PlatformRole.TENANT_OWNER, OTHER_TENANT);
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

        assertThat(auditActionCounts())
                .as("ADR 0027 evidence for each mutation, on the operations surface exactly as on control-plane")
                .containsEntry("legal-entity.registered", 1L)
                .containsEntry("legal-entity.activated", 1L)
                .containsEntry("legal-entity.assigned", 1L);
    }

    @Test
    void anAdministratorCanReadButNotRegisterAnEntity() throws Exception {
        mvc.perform(post(ENTITIES)
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("READABLE", "223456789")));

        assertThat(mvc.perform(get(ENTITIES).with(tokenFor(ADMINISTRATOR)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("LEGAL_ENTITY_READ is held by tenant-admin, unlike -manage")
                .isEqualTo(200);

        MvcResult refused = mvc.perform(post(ENTITIES)
                        .with(tokenFor(ADMINISTRATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("REFUSED", "323456789")))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.LEGAL_ENTITY_MANAGE.code());
    }

    @Test
    void anOwnerCorrectsSuspendsAndArchivesAnEntity() throws Exception {
        mvc.perform(post(ENTITIES)
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-lifecycle")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("LIFECYCLE", "623456789")));
        UUID entityId = entityId();

        MvcResult updated = mvc.perform(put(ENTITIES + "/" + entityId)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "update-1")
                        .queryParam("expectedVersion", "1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"legalName":"Corrected Name MCHJ","shortName":"Corrected",
                                 "vatRegistered":true,"vatCertificateReference":"CERT-1",
                                 "registeredAddress":"Yunusabad","contactPhone":"+998907654321"}
                                """))
                .andReturn();
        assertThat(updated.getResponse().getStatus())
                .as("a registered entity could never be corrected before this endpoint existed")
                .isEqualTo(200);
        assertThat(updated.getResponse().getContentAsString())
                .contains("\"legalName\":\"Corrected Name MCHJ\"")
                .contains("\"vatCertificateReference\":\"CERT-1\"")
                .contains("\"contactPhone\":\"+998907654321\"");

        MvcResult activated = mvc.perform(post(ENTITIES + "/" + entityId + "/activate")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "activate-lifecycle")
                        .queryParam("expectedVersion", "2"))
                .andReturn();
        assertThat(activated.getResponse().getStatus()).isEqualTo(200);

        MvcResult suspended = mvc.perform(post(ENTITIES + "/" + entityId + "/suspend")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "suspend-1")
                        .queryParam("expectedVersion", "3"))
                .andReturn();
        assertThat(suspended.getResponse().getStatus())
                .as("a retired company could only be left ACTIVE before this endpoint existed")
                .isEqualTo(200);
        assertThat(suspended.getResponse().getContentAsString()).contains("\"status\":\"SUSPENDED\"");

        MvcResult archived = mvc.perform(post(ENTITIES + "/" + entityId + "/archive")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "archive-1")
                        .queryParam("expectedVersion", "4"))
                .andReturn();
        assertThat(archived.getResponse().getStatus()).isEqualTo(200);
        assertThat(archived.getResponse().getContentAsString()).contains("\"status\":\"ARCHIVED\"");

        assertThat(auditActionCounts())
                .containsEntry("legal-entity.updated", 1L)
                .containsEntry("legal-entity.suspended", 1L)
                .containsEntry("legal-entity.archived", 1L);
    }

    @Test
    void archivingAnActiveEntityIsRefusedRatherThanSilentlyDroppingItsSeller() throws Exception {
        mvc.perform(post(ENTITIES)
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-active-archive")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("STILLACTIVE", "723456789")));
        UUID entityId = entityId();
        mvc.perform(post(ENTITIES + "/" + entityId + "/activate")
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "activate-active-archive")
                .queryParam("expectedVersion", "1"));

        MvcResult refused = mvc.perform(post(ENTITIES + "/" + entityId + "/archive")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "archive-refused")
                        .queryParam("expectedVersion", "2"))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("LegalEntity.archive() only permits DRAFT or SUSPENDED, and "
                        + "TenantApiErrorHandler.stateConflict must map that bare "
                        + "IllegalStateException to Problem Details on this surface too — "
                        + "wave P34 added OperationsLegalEntityController to its assignableTypes, "
                        + "which it had not been on before")
                .isEqualTo(409);
        assertThat(refused.getResponse().getContentAsString()).contains("RESOURCE_CONFLICT");
    }

    @Test
    void aBrandManagerCannotCorrectSuspendOrArchiveAnEntity() throws Exception {
        mvc.perform(post(ENTITIES)
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-guard")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("GUARDED", "823456789")));
        UUID entityId = entityId();

        MvcResult suspendRefused = mvc.perform(post(ENTITIES + "/" + entityId + "/suspend")
                        .with(tokenFor(ADMINISTRATOR))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "suspend-refused")
                        .queryParam("expectedVersion", "1"))
                .andReturn();
        assertThat(suspendRefused.getResponse().getStatus())
                .as("suspend is LEGAL_ENTITY_MANAGE, owner-only, exactly as register is")
                .isEqualTo(403);
    }

    @Test
    void anUnknownEntityIsNotFoundRatherThanAnUnmapped500() throws Exception {
        MvcResult result = mvc.perform(get(ENTITIES + "/" + UUID.randomUUID()).with(tokenFor(OWNER)))
                .andReturn();

        assertThat(result.getResponse().getStatus())
                .as("TenantResourceNotFoundException had no handler on this surface before wave "
                        + "P34 added OperationsLegalEntityController to TenantApiErrorHandler")
                .isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");
    }

    @Test
    void aGrantScopedToOneTenantCannotReadOrRegisterInAnotherTenant() throws Exception {
        mvc.perform(post(ENTITIES)
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "register-4")
                .contentType(MediaType.APPLICATION_JSON)
                .content(registerBody("ISOLATED", "423456789")));

        MvcResult readRefused =
                mvc.perform(get(ENTITIES).with(tokenFor(OTHER_TENANT_OWNER))).andReturn();
        assertThat(readRefused.getResponse().getStatus())
                .as("OTHER_TENANT_OWNER's grant does not cover TENANT")
                .isEqualTo(403);

        MvcResult writeRefused = mvc.perform(post(ENTITIES)
                        .with(tokenFor(OTHER_TENANT_OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cross-tenant-register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerBody("HACK", "523456789")))
                .andReturn();
        assertThat(writeRefused.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM tenant.legal_entities WHERE code = 'HACK'")
                        .query(Long.class)
                        .single())
                .as("the refused cross-tenant register must leave the registry exactly as it was")
                .isZero();
    }

    private UUID entityId() {
        return jdbc.sql("SELECT id FROM tenant.legal_entities WHERE tenant_id = :tenantId")
                .param("tenantId", TENANT)
                .query(UUID.class)
                .single();
    }

    private Map<String, Long> auditActionCounts() {
        return jdbc
                .sql("SELECT action_code, count(*) c FROM audit.audit_events GROUP BY action_code")
                .query((rs, n) -> Map.entry(rs.getString("action_code"), rs.getLong("c")))
                .list()
                .stream()
                .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    private static String registerBody(String code, String tin) {
        return """
                {"code":"%s","legalName":"%s MCHJ","tin":"%s","vatRegistered":false,
                 "registeredAddress":"Tashkent","contactPhone":"+998901234567"}
                """.formatted(code, code, tin);
    }

    private void insertTenant(UUID tenantId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "ops-legal-entity-" + tenantId)
                .update();
    }

    private void insertBrandAndLocation(UUID tenantId, UUID brandId, UUID locationId) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", brandId).param("tenantId", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CHI', 'chilonzor', 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'operations legal entity endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + tenantId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
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
