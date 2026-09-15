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
 * P28/ADR 0043/ADR 0029, 2026-09-14 review: {@code ReportExportController} had no HTTP-level test
 * at all — {@code ReportExportServiceTests} calls {@code ReportExportService} directly, bypassing
 * {@code @RequiresCapability} entirely, which is exactly why the cross-viewer PII leak this same
 * review found (a {@code REPORT_EXPORT}-only viewer could poll or list another principal's
 * PII-included export) went uncaught. This suite proves {@code REPORT_EXPORT} is enforced on all
 * three endpoints; the PII-redaction behaviour itself is covered at the service layer by {@code
 * ReportExportServiceTests#statusAndHistoryRedactPiiFromAViewerWhoLacksTheCapability}, so the one
 * check repeated here is only that a REPORT_EXPORT-only viewer's history read comes back redacted
 * end to end through the real HTTP stack.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportExportControllerCapabilityHttpTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-9000-7000-8000-0000000000d1");

    // TENANT_OWNER holds both REPORT_EXPORT and CUSTOMER_PII_EXPORT. TENANT_ADMIN
    // holds REPORT_EXPORT only -- "the PII column group stays with the owner
    // alone" (PlatformRole.java). COURIER_DISPATCHER holds neither.
    private static final String OWNER = "export-owner";
    private static final String ADMIN_NO_PII = "export-admin-no-pii";
    private static final String DISPATCHER = "export-dispatcher";

    private static final String REPORTING = "/api/v1/tenants/" + TENANT + "/reporting";

    private static final String REQUEST_BODY = "{\"reportKey\":\"CUSTOMER_DIRECTORY\","
            + "\"columns\":[\"accountId\",\"status\",\"displayName\",\"phone\"],"
            + "\"purpose\":\"endpoint capability test\"}";

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
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE reporting.report_exports").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant();
        grant(OWNER, PlatformRole.TENANT_OWNER);
        grant(ADMIN_NO_PII, PlatformRole.TENANT_ADMIN);
        grant(DISPATCHER, PlatformRole.COURIER_DISPATCHER);
    }

    @Test
    void requestExportRefusesWithoutReportExport() throws Exception {
        MvcResult refused = mvc.perform(post(REPORTING + "/exports")
                        .with(tokenFor(DISPATCHER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "export-refused-dispatcher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORT_EXPORT.code());
        assertThat(exportCount()).isZero();
    }

    @Test
    void requestExportSucceedsWithReportExport() throws Exception {
        MvcResult queued = mvc.perform(post(REPORTING + "/exports")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "export-ok")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(REQUEST_BODY))
                .andReturn();

        assertThat(queued.getResponse().getStatus()).isEqualTo(202);
        assertThat(queued.getResponse().getContentAsString()).contains("\"status\":\"QUEUED\"");
        assertThat(exportCount()).isEqualTo(1);
    }

    @Test
    void reportExportStatusRefusesWithoutReportExport() throws Exception {
        UUID id = queueAsOwner();

        MvcResult refused = mvc.perform(get(REPORTING + "/reports/" + id).with(tokenFor(DISPATCHER)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORT_EXPORT.code());
    }

    @Test
    void reportExportStatusSucceedsWithReportExportButRedactsPiiForAViewerWithoutTheGrant() throws Exception {
        UUID id = queueAsOwner();

        MvcResult ok = mvc.perform(get(REPORTING + "/reports/" + id).with(tokenFor(ADMIN_NO_PII)))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String body = ok.getResponse().getContentAsString();
        assertThat(body)
                .as("a REPORT_EXPORT-only viewer never sees another principal's PII column group")
                .contains("\"includesPiiColumns\":false")
                .doesNotContain("\"phone\"");
    }

    @Test
    void recentExportsRefusesWithoutReportExport() throws Exception {
        MvcResult refused = mvc.perform(get(REPORTING + "/exports").with(tokenFor(DISPATCHER)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORT_EXPORT.code());
    }

    @Test
    void recentExportsSucceedsWithReportExport() throws Exception {
        queueAsOwner();

        MvcResult ok =
                mvc.perform(get(REPORTING + "/exports").with(tokenFor(OWNER))).andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("\"status\":\"QUEUED\"");
    }

    // ------------------------------------------------------------------ fixtures

    private UUID queueAsOwner() throws Exception {
        mvc.perform(post(REPORTING + "/exports")
                .with(tokenFor(OWNER))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "queue-" + UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(REQUEST_BODY));
        return jdbc.sql("SELECT id FROM reporting.report_exports WHERE tenant_id = :t")
                .param("t", TENANT)
                .query(UUID.class)
                .single();
    }

    private long exportCount() {
        return jdbc.sql("SELECT count(*) FROM reporting.report_exports WHERE tenant_id = :t")
                .param("t", TENANT)
                .query(Long.class)
                .single();
    }

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'report-export-capability-endpoint', 'Report Export', 'Report Export',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'report export capability endpoint test', :validFrom)
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
