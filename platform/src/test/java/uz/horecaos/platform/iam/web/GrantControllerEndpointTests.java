package uz.horecaos.platform.iam.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

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

/**
 * W03 adversarial-review finding: {@code GrantController.accessCheck} (Staff
 * 9.5) had zero direct test coverage — not even through {@link
 * GrantControllerTests}' mocked-service unit tests, let alone through HTTP.
 * {@code AccessCheckService}'s own scope-containment refusal ({@code
 * AccessCheckServiceTests.scopeContainmentRefusesACrossBrandProbe}) was
 * proven only at the service-unit level, never through the {@code
 * @RequiresCapability} interceptor stack the real route actually sits behind.
 * This suite is that missing HTTP layer, mirroring the pattern {@code
 * OperationsCourierControllerEndpointTests}/{@code
 * OperationsFiscalTerminalControllerEndpointTests} already use elsewhere.
 */
@SpringBootTest
@AutoConfigureMockMvc
class GrantControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9e10-3000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9e10-3000-7000-8000-0000000000b1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9e10-3000-7000-8000-0000000000b2");
    private static final UUID LOCATION = UUID.fromString("018f9e10-3000-7000-8000-0000000000c1");

    private static final UUID BRAND_A_ROLE = UUID.fromString("018f9e10-3000-7000-8000-0000000000d1");

    private static final String OWNER = "access-check-owner";
    private static final String BRAND_A_MANAGER = "access-check-brand-a-manager";
    private static final String NO_GRANT_MANAGE = "access-check-no-grant-manage";

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

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'access-check-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'SECOND', 'second', 'Second Brand', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CHI', 'chilonzor', 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);

        // A real least-privilege "manage grants at my own brand only" role —
        // iam.grant.manage is only ever bundled at TENANT scope in
        // PlatformRole.java, so this is hand-authored, the same way the
        // courier and fiscal endpoint tests build a narrower-than-bundle grant.
        jdbc.sql("""
                INSERT INTO iam.roles (id, tenant_id, code, name, scope_type, status, is_platform_defined)
                VALUES (:id, :tenantId, 'brand-a-grant-manager', 'Brand A grant manager', 'BRAND', 'ACTIVE', false)
                """).param("id", BRAND_A_ROLE).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO iam.role_capabilities (role_id, capability_code) VALUES (:roleId, :capability)
                """)
                .param("roleId", BRAND_A_ROLE)
                .param("capability", Capability.IAM_GRANT_MANAGE.code())
                .update();
        grant(BRAND_A_MANAGER, BRAND_A_ROLE, "BRAND", BRAND);
    }

    @Test
    void aCallerWithoutGrantManageIsRefused() throws Exception {
        MvcResult refused = mvc.perform(get(accessCheckPath())
                        .with(tokenFor(NO_GRANT_MANAGE))
                        .param("subject", "colleague")
                        .param("capability", Capability.ORDER_APPROVE.name())
                        .param("scopeType", "TENANT"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.IAM_GRANT_MANAGE.code());
    }

    /**
     * The scope-containment case, as it is actually reachable today: {@code
     * @RequiresCapability(IAM_GRANT_MANAGE)} on this route carries no {@code
     * scope}, so it defaults to TENANT and — because the route's only path
     * variable is {@code tenantId} — {@code CapabilityEnforcementInterceptor}
     * can only ever check TENANT-scope coverage (this is documented in {@code
     * AccessCheckService}'s own class doc). A real least-privilege grant of
     * {@code iam.grant.manage} narrower than TENANT (this fixture's brand-only
     * custom role, the same shape {@code
     * OperationsFiscalTerminalControllerEndpointTests} builds for {@code
     * fiscal.terminal.manage}) is therefore refused by the controller's own
     * declaration before {@link uz.horecaos.platform.iam.application.AccessCheckService#check}
     * — and its finer {@code requireScopeContainment} target-scope check —
     * ever runs. {@code AccessCheckServiceTests} exercises that finer check
     * directly against the service for exactly this reason.
     */
    @Test
    void aBrandScopedGrantManagerCannotReachThisRouteAtAll() throws Exception {
        MvcResult refused = mvc.perform(get(accessCheckPath())
                        .with(tokenFor(BRAND_A_MANAGER))
                        .param("subject", "colleague")
                        .param("capability", Capability.ORDER_APPROVE.name())
                        .param("scopeType", "BRAND")
                        .param("brandId", BRAND.toString()))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.IAM_GRANT_MANAGE.code());
    }

    @Test
    void aTenantOwnerReadsAnAllowedAnswerForAGrantTheSubjectHolds() throws Exception {
        grant("colleague", PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION);

        MvcResult answered = mvc.perform(get(accessCheckPath())
                        .with(tokenFor(OWNER))
                        .param("subject", "colleague")
                        .param("capability", Capability.ORDER_APPROVE.name())
                        .param("scopeType", "LOCATION")
                        .param("brandId", BRAND.toString())
                        .param("locationId", LOCATION.toString()))
                .andReturn();

        assertThat(answered.getResponse().getStatus()).isEqualTo(200);
        assertThat(answered.getResponse().getContentAsString())
                .contains("\"verdict\":\"ALLOWED\"")
                .contains("\"scopeType\":\"LOCATION\"");
    }

    /**
     * A TENANT-scoped caller's own grant covers every brand and location
     * beneath it, so {@code heldElsewhere} correctly shows the subject's
     * grants at both brands — this is the one shape of caller that can reach
     * the route today, and confirms the W03 leak fix ({@code
     * AccessCheckService.check}'s new filter on {@code heldElsewhere}) does
     * not over-filter a caller who is genuinely entitled to see everything.
     * The filter actually withholding a sibling-scope grant from a narrower
     * caller is proven at the service level ({@code
     * AccessCheckServiceTests.heldElsewhereNeverLeaksAGrantTheCallerCannotSee}),
     * because — see {@link #aBrandScopedGrantManagerCannotReachThisRouteAtAll}
     * — no caller narrower than TENANT can reach this route to demonstrate it
     * through HTTP.
     */
    @Test
    void aTenantOwnerSeesHeldElsewhereAcrossEveryBrandTheyManage() throws Exception {
        grant("colleague", PlatformRole.LOCATION_STAFF, "BRAND", BRAND);
        grant("colleague", PlatformRole.LOCATION_STAFF, "BRAND", OTHER_BRAND);

        MvcResult answered = mvc.perform(get(accessCheckPath())
                        .with(tokenFor(OWNER))
                        .param("subject", "colleague")
                        .param("capability", Capability.ORDER_APPROVE.name())
                        .param("scopeType", "TENANT"))
                .andReturn();

        assertThat(answered.getResponse().getStatus()).isEqualTo(200);
        String body = answered.getResponse().getContentAsString();
        assertThat(body).contains(BRAND.toString()).contains(OTHER_BRAND.toString());
    }

    private static String accessCheckPath() {
        return "/api/v1/tenants/" + TENANT + "/access-check";
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'access-check endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeType + scopeId).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void grant(String subject, UUID customRoleId, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, false, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'access-check endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + customRoleId + scopeType + scopeId).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", customRoleId)
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
