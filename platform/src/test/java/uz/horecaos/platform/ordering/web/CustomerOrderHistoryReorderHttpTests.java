package uz.horecaos.platform.ordering.web;

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
 * Wave P14, row {@code 1.3f}: {@code CustomerOrderHistoryController.reorderPlan}
 * exercised through the real HTTP stack, following {@code
 * ReportingControllerCapabilityHttpTests}' own shape — {@code
 * EndpointCapabilityDeclarationTests} only proves {@code @RequiresCapability}
 * is present and well-shaped by reflection, never that a real request lacking
 * {@code ORDER_READ} is actually refused, and never that {@code LOCATION}-scoped
 * {@code ORDER_READ} (the shape {@code LOCATION_STAFF} actually holds) does
 * not satisfy a {@code BRAND}-scoped requirement.
 *
 * <p>The positive case asks about an order that does not exist, deliberately:
 * proving the wrapper answers {@code RESOURCE_NOT_FOUND} rather than {@code
 * INSUFFICIENT_CAPABILITY} is what proves the capability gate let the request
 * through to {@link uz.horecaos.platform.ordering.application.ReorderPlanService}
 * — {@code ReorderPlanService}'s own resolution logic is that class's concern,
 * not this wrapper's.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CustomerOrderHistoryReorderHttpTests {

    private static final UUID TENANT = UUID.fromString("018fc300-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fc300-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fc300-4000-7000-8000-0000000000c1");
    private static final UUID ACCOUNT = UUID.fromString("018fc300-4000-7000-8000-0000000000d1");
    private static final UUID MISSING_ORDER = UUID.fromString("018fc300-4000-7000-8000-0000000000e1");

    /** Holds {@code ORDER_READ} at {@code BRAND} scope — a support/back-office style grant. */
    private static final String BRAND_READER = "reorder-http-brand-reader";

    /** Holds {@code ORDER_READ}, but only at {@code LOCATION} scope — the exact shape {@code LOCATION_STAFF} holds. */
    private static final String LOCATION_STAFF_SUBJECT = "reorder-http-location-staff";

    /** Authenticated, but holds no grant at all — no {@code iam.grants} row is ever inserted for it. */
    private static final String UNGRANTED = "reorder-http-ungranted";

    private static final String REORDER_PATH = "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/customers/"
            + ACCOUNT + "/orders/" + MISSING_ORDER + "/reorder";

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
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        roleRegistry.synchronize();
        insertTenancy();
        insertCustomer();
        grantAt(BRAND_READER, PlatformRole.LOCATION_MANAGER, "BRAND", BRAND);
        grantAt(LOCATION_STAFF_SUBJECT, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION);
        // UNGRANTED deliberately gets no grantAt call at all.
    }

    @Test
    void refusesAPrincipalWithNoOrderReadAtAll() throws Exception {
        MvcResult refused =
                mvc.perform(get(REORDER_PATH).with(tokenFor(UNGRANTED))).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.ORDER_READ.code());
    }

    /**
     * The exact shape {@code LOCATION_STAFF} holds today (ADR 0039): {@code
     * ORDER_READ} granted at one branch never satisfies a {@code BRAND}-scoped
     * check — {@code JdbcAuthorizationService#has}'s {@code scope().covers(scope)}
     * only widens, never narrows. Proves this wrapper was not accidentally
     * declared at a scope {@code LOCATION_STAFF} could already reach, which
     * would have silently contradicted the wave's own documented gap.
     */
    @Test
    void aLocationScopedOrderReadGrantDoesNotSatisfyTheBrandScopedWrapper() throws Exception {
        MvcResult refused = mvc.perform(get(REORDER_PATH).with(tokenFor(LOCATION_STAFF_SUBJECT)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.ORDER_READ.code());
    }

    @Test
    void aBrandScopedOrderReadGrantReachesThePlanService() throws Exception {
        MvcResult result =
                mvc.perform(get(REORDER_PATH).with(tokenFor(BRAND_READER))).andReturn();

        // Not 403: the capability gate let the request through. Not 200: no such
        // order exists for this account — ReorderPlanService#planFor's own
        // "empty when not this account's, or does not exist" contract, exercised
        // by whichever suite owns that service, not this one.
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString()).contains("RESOURCE_NOT_FOUND");
    }

    // ------------------------------------------------------------------ fixtures

    private void insertTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'reorder-http-endpoint', 'Reorder', 'Reorder', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, status, version, timezone)
                VALUES (:id, :tenantId, :brandId, 'L1', 'l1', 'Location', 'ACTIVE', 0, 'Asia/Tashkent')
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }

    private void insertCustomer() {
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                    identity_policy_version, version)
                VALUES (:id, :tenantId, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", ACCOUNT).param("tenantId", TENANT).update();
    }

    private void grantAt(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'reorder http endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeType).getBytes(UTF_8)))
                .param("tenantId", TENANT)
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
