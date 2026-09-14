package uz.horecaos.platform.commercial.web;

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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.commercial.application.ModuleCatalogService;
import uz.horecaos.platform.commercial.application.PlanCatalogService;
import uz.horecaos.platform.commercial.application.SubscriptionService;
import uz.horecaos.platform.commercial.domain.BillingUnit;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * T19 (ADR 0127): the tenant-facing purchasable-module catalogue and the
 * tenant's own arrears read, both newly reachable from {@code
 * /api/v1/tenants/{tenantId}/commercial/**} rather than the control-plane
 * paths only HorecaOS staff can call.
 *
 * <p>Drives the real routes through {@link MockMvc} with a real JWT and the
 * real {@code CapabilityEnforcementInterceptor}, the same pattern {@link
 * CommercialOperationsControllerEndpointTests} uses for the statements mirror
 * this wave builds on: a caller who holds the right capability at the right
 * tenant's scope gets 200/204, one who holds it at a <em>different</em>
 * tenant's scope is refused exactly like one who does not hold it at all —
 * scope containment, not merely capability possession, is what is under test.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommercialSelfServiceEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9d50-4000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9d50-4000-7000-8000-0000000000a2");

    private static final ActorRef AUTHOR = ActorRef.user("commercial-self-service-author", null);
    private static final ActorRef APPROVER = ActorRef.user("commercial-self-service-approver", null);

    private static final String OWNER = "self-service-owner";
    private static final String ADMIN_WITHOUT_EXTRAS = "self-service-admin";
    private static final String OTHER_TENANT_OWNER = "self-service-other-owner";

    private static final String MODULES = "/api/v1/tenants/" + TENANT + "/commercial/modules";
    private static final String ARREARS = "/api/v1/tenants/" + TENANT + "/commercial/arrears";

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
    private PlanCatalogService plans;

    @Autowired
    private SubscriptionService subscriptions;

    @Autowired
    private ModuleCatalogService modules;

    private UUID onSaleModuleId;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE commercial.tenant_modules CASCADE").update();
        jdbc.sql("TRUNCATE TABLE commercial.modules CASCADE").update();
        jdbc.sql("TRUNCATE TABLE commercial.subscriptions CASCADE").update();
        jdbc.sql("TRUNCATE TABLE commercial.plan_entitlements, commercial.plan_versions, commercial.plans CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        insertTenant(TENANT, "self-service-tenant");
        insertTenant(OTHER_TENANT, "self-service-other-tenant");

        grant(OWNER, PlatformRole.TENANT_OWNER, TENANT);
        grant(ADMIN_WITHOUT_EXTRAS, PlatformRole.TENANT_ADMIN, TENANT);
        grant(OTHER_TENANT_OWNER, PlatformRole.TENANT_OWNER, OTHER_TENANT);

        UUID planId = plans.createPlan("SELF_SVC", "Self-service", AUTHOR, "the price list", "corr");
        UUID versionId = plans.draftVersion(
                planId, "UZS", 1_200_000, "MONTHLY", null, Map.of(), AUTHOR, "the price list", "corr");
        plans.activate(versionId, APPROVER, "signed off", "corr");
        subscriptions.start(TENANT, versionId, null, AUTHOR, "pilot", "corr");

        UUID draftedModuleId = modules.draft(
                "self-service-kds",
                "Kitchen display",
                null,
                BillingUnit.PER_TENANT,
                "UZS",
                150_000,
                List.of(),
                AUTHOR,
                "new line",
                "corr");
        modules.activate(draftedModuleId, APPROVER, "signed off", "corr");
        onSaleModuleId = draftedModuleId;
    }

    // ---------------------------------------------------------- catalogue

    @Test
    void anOwnerBrowsesTheOnSaleCatalogueWithModuleRead() throws Exception {
        MvcResult listed = mvc.perform(get(MODULES).with(tokenFor(OWNER))).andReturn();

        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(listed.getResponse().getContentAsString()).contains("self-service-kds");
    }

    @Test
    void aCallerWithoutModuleReadCannotBrowseTheCatalogue() throws Exception {
        MvcResult refused =
                mvc.perform(get(MODULES).with(tokenFor(ADMIN_WITHOUT_EXTRAS))).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.COMMERCIAL_MODULE_READ.code());
    }

    @Test
    void anotherTenantsOwnerCannotBrowseThisTenantsCatalogue() throws Exception {
        MvcResult refused =
                mvc.perform(get(MODULES).with(tokenFor(OTHER_TENANT_OWNER))).andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("OTHER_TENANT_OWNER's grant covers OTHER_TENANT, not TENANT")
                .isEqualTo(403);
    }

    @Test
    void anOwnerPurchasesAnOnSaleModuleUnderSubscriptionManage() throws Exception {
        MvcResult purchased = mvc.perform(post(MODULES)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "purchase-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moduleId\":\"" + onSaleModuleId + "\"}"))
                .andReturn();

        assertThat(purchased.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM commercial.tenant_modules WHERE tenant_id = :tenantId AND module_id = :moduleId")
                        .param("tenantId", TENANT)
                        .param("moduleId", onSaleModuleId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void aCallerWithoutSubscriptionManageCannotPurchase() throws Exception {
        MvcResult refused = mvc.perform(post(MODULES)
                        .with(tokenFor(ADMIN_WITHOUT_EXTRAS))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "purchase-refused")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"moduleId\":\"" + onSaleModuleId + "\"}"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM commercial.tenant_modules WHERE tenant_id = :tenantId")
                        .param("tenantId", TENANT)
                        .query(Integer.class)
                        .single())
                .as("a refused purchase writes nothing")
                .isEqualTo(0);
    }

    // -------------------------------------------------------------- arrears

    @Test
    void anOwnerReadsItsOwnArrearsStateInGoodStanding() throws Exception {
        MvcResult read = mvc.perform(get(ARREARS).with(tokenFor(OWNER))).andReturn();

        assertThat(read.getResponse().getStatus()).isEqualTo(200);
        String body = read.getResponse().getContentAsString();
        assertThat(body).contains("\"status\":\"ACTIVE\"").contains("\"additionsBlocked\":false");
    }

    @Test
    void aCallerWithoutArrearsReadCannotReadArrears() throws Exception {
        MvcResult refused =
                mvc.perform(get(ARREARS).with(tokenFor(ADMIN_WITHOUT_EXTRAS))).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.COMMERCIAL_ARREARS_READ.code());
    }

    @Test
    void anotherTenantsOwnerCannotReadThisTenantsArrears() throws Exception {
        MvcResult refused =
                mvc.perform(get(ARREARS).with(tokenFor(OTHER_TENANT_OWNER))).andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("OTHER_TENANT_OWNER's grant does not cover TENANT")
                .isEqualTo(403);
    }

    // ------------------------------------------------------------------ util

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'commercial self-service endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
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
