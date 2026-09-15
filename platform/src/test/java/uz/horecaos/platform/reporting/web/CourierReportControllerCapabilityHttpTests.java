package uz.horecaos.platform.reporting.web;

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
 * 2026-09-14 review: no HTTP-level test existed for {@link
 * CourierReportController} at all — {@code CourierReportControllerMappingTests}
 * is a pure unit test of one response mapper and never starts MockMvc, and
 * {@code CourierTariffAuditAndExternalCostTests} exercises {@code
 * ReportQueryService} directly, not the controller or its {@code
 * @RequiresCapability} wiring. Every one of this controller's four endpoints
 * could have had its capability annotation silently removed or loosened and
 * no test in the diff that introduced them would have failed.
 *
 * <p>Follows {@code ReportingControllerCapabilityHttpTests}' own shape (same
 * MANAGER/DISPATCHER fixture and {@code tokenFor} helper) — that suite
 * covers {@link ReportingController}; this one covers this controller, both
 * declaring the identical {@code REPORTING_READ}/{@code TENANT} capability.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CourierReportControllerCapabilityHttpTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-9000-7000-8000-0000000000b2");

    // LOCATION_MANAGER holds REPORTING_READ (PlatformRole.java); COURIER_DISPATCHER
    // holds plenty of other tenant/brand authority but never REPORTING_READ, so
    // it doubles as the "authenticated, but not this capability" negative case.
    private static final String MANAGER = "courier-reporting-manager";
    private static final String DISPATCHER = "courier-reporting-dispatcher";

    private static final String COURIERS = "/api/v1/tenants/" + TENANT + "/reporting/couriers";

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
        insertTenant();
        grant(MANAGER, PlatformRole.LOCATION_MANAGER);
        grant(DISPATCHER, PlatformRole.COURIER_DISPATCHER);
    }

    @Test
    void leaderboardRefusesWithoutReportingRead() throws Exception {
        MvcResult refused = mvc.perform(get(COURIERS + "/leaderboard")
                        .with(tokenFor(DISPATCHER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_READ.code());
    }

    @Test
    void leaderboardSucceedsWithReportingRead() throws Exception {
        MvcResult ok = mvc.perform(get(COURIERS + "/leaderboard")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("\"rows\":[]");
    }

    @Test
    void slaBucketsRefusesWithoutReportingRead() throws Exception {
        MvcResult refused = mvc.perform(get(COURIERS + "/sla-buckets")
                        .with(tokenFor(DISPATCHER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_READ.code());
    }

    @Test
    void slaBucketsSucceedsWithReportingRead() throws Exception {
        MvcResult ok = mvc.perform(get(COURIERS + "/sla-buckets")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("\"buckets\":[]");
    }

    @Test
    void tariffAuditRefusesWithoutReportingRead() throws Exception {
        MvcResult refused = mvc.perform(get(COURIERS + "/tariff-audit")
                        .with(tokenFor(DISPATCHER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_READ.code());
    }

    @Test
    void tariffAuditSucceedsWithReportingRead() throws Exception {
        MvcResult ok = mvc.perform(get(COURIERS + "/tariff-audit")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("\"rows\":[]");
    }

    @Test
    void externalDeliveryCostRefusesWithoutReportingRead() throws Exception {
        MvcResult refused = mvc.perform(get(COURIERS + "/external-delivery-cost")
                        .with(tokenFor(DISPATCHER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_READ.code());
    }

    @Test
    void externalDeliveryCostSucceedsWithReportingRead() throws Exception {
        MvcResult ok = mvc.perform(get(COURIERS + "/external-delivery-cost")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("\"rows\":[]");
    }

    // ------------------------------------------------------------------ fixtures

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'courier-reporting-capability-endpoint', 'Reporting', 'Reporting',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'courier reporting capability endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
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
