package uz.horecaos.platform.reporting.web;

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
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * T14/ADR 0134, 2026-09-14 review: {@code ProductClassificationController} had no HTTP-level
 * test at all — {@code ProductClassificationServiceTests} exercises {@code
 * ProductClassificationService} directly, bypassing {@code @RequiresCapability}'s real
 * interceptor stack entirely, so the one endpoint in this whole reporting area gated by the
 * narrowly-held, mutating {@link Capability#REPORTING_CLASSIFICATION_RUN} had never been proven
 * to actually refuse a principal without it. Mirrors {@link ReportingControllerCapabilityHttpTests}'
 * own shape.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ProductClassificationControllerCapabilityHttpTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-9000-7000-8000-0000000000c1");

    // LOCATION_MANAGER holds both REPORTING_READ and REPORTING_CLASSIFICATION_RUN
    // (PlatformRole.java). TENANT_ADMIN holds REPORTING_READ but not the run
    // capability -- the capability split this controller's own doc names.
    // COURIER_DISPATCHER holds neither, the same negative fixture the sibling
    // suite already uses.
    private static final String MANAGER = "classification-manager";
    private static final String READ_ONLY = "classification-read-only";
    private static final String DISPATCHER = "classification-dispatcher";

    private static final String CLASSIFICATION_RUNS = "/api/v1/tenants/" + TENANT + "/reporting/classification-runs";

    /** Exactly 28 days -- statistics.md S2.7's own floor -- so a run is accepted, not refused. */
    private static final String RUN_BODY = "{\"from\":\"2026-07-01\",\"to\":\"2026-07-28\"}";

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
        jdbc.sql("TRUNCATE TABLE reporting.classification_result, reporting.classification_run")
                .update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant();
        grant(MANAGER, PlatformRole.LOCATION_MANAGER);
        grant(READ_ONLY, PlatformRole.TENANT_ADMIN);
        grant(DISPATCHER, PlatformRole.COURIER_DISPATCHER);
    }

    @Test
    void runRefusesWithoutReportingClassificationRun() throws Exception {
        MvcResult refused = mvc.perform(post(CLASSIFICATION_RUNS)
                        .with(tokenFor(DISPATCHER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "run-refused-dispatcher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RUN_BODY))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_CLASSIFICATION_RUN.code());
        assertThat(runCount()).isZero();
    }

    @Test
    void reportingReadAloneIsNotEnoughToStartARun() throws Exception {
        MvcResult refused = mvc.perform(post(CLASSIFICATION_RUNS)
                        .with(tokenFor(READ_ONLY))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "run-refused-read-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RUN_BODY))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("REPORTING_READ is a read grant; starting a run needs REPORTING_CLASSIFICATION_RUN")
                .isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_CLASSIFICATION_RUN.code());
        assertThat(runCount()).isZero();
    }

    @Test
    void runSucceedsWithReportingClassificationRun() throws Exception {
        MvcResult ok = mvc.perform(post(CLASSIFICATION_RUNS)
                        .with(tokenFor(MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "run-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RUN_BODY))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("\"rows\":[]");
        assertThat(runCount()).isEqualTo(1);
    }

    @Test
    void latestRefusesWithoutReportingRead() throws Exception {
        MvcResult refused = mvc.perform(get(CLASSIFICATION_RUNS + "/latest")
                        .with(tokenFor(DISPATCHER))
                        .queryParam("from", "2026-07-01")
                        .queryParam("to", "2026-07-28"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_READ.code());
    }

    @Test
    void latestSucceedsForAReportingReadOnlyPrincipal() throws Exception {
        // A read never needs REPORTING_CLASSIFICATION_RUN -- ProductClassificationController's
        // own doc: "seeing what was last computed never needs reporting.classification.run,
        // only reporting.read". No run exists yet, so RESOURCE_NOT_FOUND, but that alone proves
        // the read-only principal cleared the capability gate rather than being refused for it.
        MvcResult response = mvc.perform(get(CLASSIFICATION_RUNS + "/latest")
                        .with(tokenFor(READ_ONLY))
                        .queryParam("from", "2026-07-01")
                        .queryParam("to", "2026-07-28"))
                .andReturn();

        assertThat(response.getResponse().getStatus()).isEqualTo(404);
        assertThat(response.getResponse().getContentAsString()).doesNotContain("INSUFFICIENT_CAPABILITY");
    }

    // ------------------------------------------------------------------ fixtures

    private long runCount() {
        return jdbc.sql("SELECT count(*) FROM reporting.classification_run WHERE tenant_id = :t")
                .param("t", TENANT)
                .query(Long.class)
                .single();
    }

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'classification-capability-endpoint', 'Classification', 'Classification',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'classification capability endpoint test', :validFrom)
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
