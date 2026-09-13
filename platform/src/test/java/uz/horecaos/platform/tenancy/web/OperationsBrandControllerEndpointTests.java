package uz.horecaos.platform.tenancy.web;

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
 * Wave P26, second-pass adversarial review: {@code OperationsBrandController}
 * was new this integration with no HTTP-level test — {@code
 * OperationsBrandOrderCountsEndpointTests} covers a different, pre-existing
 * controller under a coincidentally overlapping URL prefix (see the review's
 * own note on that confusion). This proves the {@code BRAND_READ} vs {@code
 * LOCATION_READ} split declared per-endpoint actually holds: a role granted
 * only {@code LOCATION_READ} (a real, narrower role, not a hypothetical) is
 * refused the brand-level reads and still served the location-level ones.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsBrandControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-8000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9b20-8000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9b20-8000-7000-8000-0000000000c1");

    private static final String FULL = "operations-brand-full";
    private static final String LOCATION_ONLY = "operations-brand-location-only";

    private static final String BRANDS = "/api/v1/operations/tenants/" + TENANT + "/brands";

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
        insertTenancy();
        // TENANT_OWNER carries both BRAND_READ and LOCATION_READ, at TENANT scope.
        grant(FULL, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        // LOCATION_MANAGER carries LOCATION_READ but never BRAND_READ -- a real,
        // narrower role, not a hypothetical gap. Granted at BRAND scope (rather
        // than its own native LOCATION scope) so its LOCATION_READ actually
        // covers the brand-wide locations()/locationServiceStates() reads,
        // which are declared at BRAND scope precisely because they list every
        // location in the brand, not one.
        grant(LOCATION_ONLY, PlatformRole.LOCATION_MANAGER, "BRAND", BRAND);
    }

    @Test
    void locationOnlyIsRefusedTheBrandLevelReads() throws Exception {
        MvcResult listRefused =
                mvc.perform(get(BRANDS).with(tokenFor(LOCATION_ONLY))).andReturn();
        assertThat(listRefused.getResponse().getStatus()).isEqualTo(403);
        assertThat(listRefused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.BRAND_READ.code());

        MvcResult getRefused = mvc.perform(
                        get(BRANDS + "/" + BRAND).with(tokenFor(LOCATION_ONLY)))
                .andReturn();
        assertThat(getRefused.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void locationOnlyCanStillReadTheLocationLevelEndpoints() throws Exception {
        MvcResult locations = mvc.perform(
                        get(BRANDS + "/" + BRAND + "/locations").with(tokenFor(LOCATION_ONLY)))
                .andReturn();
        assertThat(locations.getResponse().getStatus()).isEqualTo(200);
        assertThat(locations.getResponse().getContentAsString()).contains(LOCATION.toString());

        MvcResult states = mvc.perform(get(BRANDS + "/" + BRAND + "/locations/service-states")
                        .with(tokenFor(LOCATION_ONLY)))
                .andReturn();
        assertThat(states.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void fullCanReadEveryEndpoint() throws Exception {
        assertThat(mvc.perform(get(BRANDS).with(tokenFor(FULL)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(mvc.perform(get(BRANDS + "/" + BRAND).with(tokenFor(FULL)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(mvc.perform(get(BRANDS + "/" + BRAND + "/locations").with(tokenFor(FULL)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(mvc.perform(get(BRANDS + "/" + BRAND + "/locations/service-states")
                                .with(tokenFor(FULL)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    // ------------------------------------------------------------------ fixtures

    private void insertTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'operations-brand-endpoint', 'Operations Brand', 'Operations Brand',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.location_service_state (location_id, tenant_id, brand_id, mode)
                VALUES (:locationId, :tenantId, :brandId, 'FOLLOW_SCHEDULE')
                """)
                .param("locationId", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'operations brand endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * Also carries the {@code platform-admin} realm role, scoped to the
     * {@code horecaos-api} client the same way every capability-bearing role
     * here is. {@code TenantAccessPolicy.requireTenantRead} -- the second,
     * independent gate {@code getBrands}/{@code getBrand}/{@code getLocations}
     * call beneath this controller's own {@code @RequiresCapability} -- asks
     * whether the actor belongs to the tenant's Keycloak organization, which
     * this fixture does not model; {@code platform-admin} is that policy's own
     * documented bypass for exactly this case, and it does not touch the
     * DB-backed capability grants this test is actually about: {@code
     * JdbcAuthorizationService} only lets {@code platform-admin} bypass {@code
     * IAM_GRANT_MANAGE}, never {@code BRAND_READ}/{@code LOCATION_READ}, so
     * {@code LOCATION_ONLY}'s refusal on the brand-level reads is still the
     * real capability check, not this bypass.
     */
    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder -> builder.subject(subject)
                .claim(
                        "resource_access",
                        Map.of("horecaos-api", Map.of("roles", List.of("platform-admin")))));
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
