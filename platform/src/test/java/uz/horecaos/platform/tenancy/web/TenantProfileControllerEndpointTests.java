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
 * Gap map row {@code 10.1}: the brand profile screen had no way to show the
 * tenant's own country/currency/timezone — {@code
 * TenantProfileController.residency} read the same {@code tenant.tenants}
 * facts, but only for a {@code PLATFORM_ADMIN} across every tenant. Proves
 * the new {@code GET .../operations/tenants/{tenantId}/profile} answers a
 * tenant's own operators with only their own tenant's facts, and enforces
 * {@code BRAND_READ} at {@code TENANT} scope — the same capability and scope
 * {@code OperationsBrandController.list} already requires for the scope
 * bar's brand picker on the same screen.
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantProfileControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-a000-7000-8000-0000000000f1");

    private static final String OWNER = "tenant-profile-owner";
    private static final String DISPATCHER = "tenant-profile-dispatcher";

    private static final String PROFILE = "/api/v1/operations/tenants/" + TENANT + "/profile";

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
                    (id, slug, legal_name, display_name, default_currency, default_timezone,
                     country_code, status, version)
                VALUES (:id, 'tenant-profile-endpoint', 'Tenant Profile', 'Tenant Profile',
                    'UZS', 'Asia/Tashkent', 'UZ', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        grant(DISPATCHER, PlatformRole.COURIER_DISPATCHER, "TENANT", TENANT);
    }

    @Test
    void readsTheTenantsOwnMarketFacts() throws Exception {
        MvcResult result = mvc.perform(get(PROFILE).with(tokenFor(OWNER))).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString())
                .contains("\"countryCode\":\"UZ\"")
                .contains("\"defaultCurrency\":\"UZS\"")
                .contains("\"defaultTimezone\":\"Asia/Tashkent\"");
    }

    @Test
    void isRefusedWithoutBrandReadAtTenantScope() throws Exception {
        MvcResult refused = mvc.perform(get(PROFILE).with(tokenFor(DISPATCHER))).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.BRAND_READ.code());
    }

    // ------------------------------------------------------------------ fixtures

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'tenant profile endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeId).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
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
