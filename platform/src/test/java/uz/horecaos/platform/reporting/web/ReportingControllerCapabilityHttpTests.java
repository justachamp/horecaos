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
 * Wave T12, second-pass adversarial review: no HTTP-level test existed for
 * {@code ReportingController} at all — {@code OperatorReportingTests} and its
 * siblings exercise {@code ReportQueryService}/{@code JdbcReportingStore}
 * directly, and {@code EndpointCapabilityDeclarationTests} only checks by
 * reflection that {@code @RequiresCapability} is present and well-shaped,
 * never that a real request lacking {@code REPORTING_READ} is actually
 * refused. Every route on this controller shares the identical {@code
 * REPORTING_READ}/{@code TENANT} declaration, so this suite covers the
 * controller's capability wiring as a whole through the two endpoints T12
 * added, following {@code CommercialOperationsControllerEndpointTests}' own
 * shape.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportingControllerCapabilityHttpTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-9000-7000-8000-0000000000a1");

    // LOCATION_MANAGER holds REPORTING_READ (PlatformRole.java); COURIER_DISPATCHER
    // holds plenty of other tenant/brand authority but never REPORTING_READ, so
    // it doubles as the "authenticated, but not this capability" negative case.
    private static final String MANAGER = "reporting-manager";
    private static final String DISPATCHER = "reporting-dispatcher";

    private static final String REPORTING = "/api/v1/tenants/" + TENANT + "/reporting";

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
    void operatorLeaderboardRefusesWithoutReportingRead() throws Exception {
        MvcResult refused = mvc.perform(get(REPORTING + "/operator-leaderboard")
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
    void operatorLeaderboardSucceedsWithReportingRead() throws Exception {
        MvcResult ok = mvc.perform(get(REPORTING + "/operator-leaderboard")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("\"rows\":[]");
    }

    @Test
    void operatorProductsRefusesWithoutReportingRead() throws Exception {
        MvcResult refused = mvc.perform(get(REPORTING + "/operator-products")
                        .with(tokenFor(DISPATCHER))
                        .queryParam("operatorPrincipalId", "staff-1")
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_READ.code());
    }

    @Test
    void operatorProductsSucceedsWithReportingRead() throws Exception {
        MvcResult ok = mvc.perform(get(REPORTING + "/operator-products")
                        .with(tokenFor(MANAGER))
                        .queryParam("operatorPrincipalId", "staff-1")
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("\"rows\":[]");
    }

    /**
     * Wave P27: the trap the brief names by name — a money metric queried
     * without {@code groupBy=LEGAL_ENTITY} on a two-entity tenant must come
     * back as a handled {@code ProblemDetail} (ADR 0031/0038), never a 500 or
     * an unhandled exception that renders as an errored page.
     */
    @Test
    void aMoneyMetricWithoutLegalEntityGroupingIsAHandledErrorOnATwoEntityTenant() throws Exception {
        UUID entityA = UUID.randomUUID();
        UUID entityB = UUID.randomUUID();
        insertFactOrder(entityA);
        insertFactOrder(entityB);

        MvcResult refused = mvc.perform(get(REPORTING + "/queries")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-01")
                        .queryParam("metric", "revenue.gross.v1"))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("a refusal, not a crash — ADR 0031's ProblemDetail, not a 500")
                .isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString())
                .contains("LEGAL_ENTITY_GROUPING_REQUIRED")
                .contains("revenue.gross.v1");
    }

    @Test
    void thePerEntityCutOfTheSameMoneyMetricSucceeds() throws Exception {
        UUID entityA = UUID.randomUUID();
        UUID entityB = UUID.randomUUID();
        insertFactOrder(entityA);
        insertFactOrder(entityB);

        MvcResult ok = mvc.perform(get(REPORTING + "/queries")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-01")
                        .queryParam("metric", "revenue.gross.v1")
                        .queryParam("groupBy", "LEGAL_ENTITY"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("\"rows\":[");
    }

    // ------------------------------------------------------------------ fixtures

    private static final java.time.LocalDate FACT_DAY = java.time.LocalDate.of(2026, 9, 1);

    /**
     * {@code GET .../reporting/queries} reads {@code reporting.agg_branch_day}
     * (the typed pipeline's own pre-aggregated table), not {@code fact_order}
     * directly — see {@code JdbcReportingStore#readAggregates}.
     */
    private void insertFactOrder(UUID legalEntityId) {
        jdbc.sql("""
                INSERT INTO reporting.agg_branch_day (
                    tenant_id, business_date, location_id, legal_entity_id, channel_code,
                    fulfilment_type, boundary_version, metric_calculation_version, order_count,
                    cancelled_count, gross_som, discount_som, net_som, refunded_som,
                    promised_count, late_count, distinct_customers, new_customers)
                VALUES (:tenantId, :businessDate, :locationId, :legalEntityId, 'TELEGRAM', 'DELIVERY',
                    1, 1, 1, 0, 100000, 0, 100000, 0, 0, 0, 0, 0)
                """)
                .param("tenantId", TENANT)
                .param("businessDate", FACT_DAY)
                .param("locationId", UUID.randomUUID())
                .param("legalEntityId", legalEntityId)
                .update();
    }

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'reporting-capability-endpoint', 'Reporting', 'Reporting',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'reporting capability endpoint test', :validFrom)
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
