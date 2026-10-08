package uz.horecaos.platform.reporting.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * ADR 0175: the two telephony reads behind their capability, over a real database.
 *
 * <p>The figures behind the rows are {@code DayCloseAndMetricLayerTests}' business; this class owns
 * the HTTP edge: that {@code REPORTING_READ} is required, that a row closed before the migration is
 * answered with {@code null} and never {@code 0}, and that the coverage read tells a branch with a
 * live provider from one without.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CallStatsControllerHttpTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-9000-7000-8000-0000000000f1");
    private static final UUID BRAND = UUID.fromString("018f9b20-9000-7000-8000-0000000000f2");
    private static final UUID LOCATION = UUID.fromString("018f9b20-9000-7000-8000-0000000000f3");
    private static final UUID OTHER_LOCATION = UUID.fromString("018f9b20-9000-7000-8000-0000000000f4");
    private static final UUID INSTALLATION = UUID.fromString("018f9b20-9000-7000-8000-0000000000f5");

    private static final String MANAGER = "call-stats-manager";
    private static final String DISPATCHER = "call-stats-dispatcher";

    private static final String BASE = "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/locations/";

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
        jdbc.sql("TRUNCATE TABLE reporting.fact_call_hour").update();
        jdbc.sql("TRUNCATE TABLE voice.call_events CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.bindings CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.installations CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant();
        grant(MANAGER, PlatformRole.LOCATION_MANAGER);
        grant(DISPATCHER, PlatformRole.COURIER_DISPATCHER);
    }

    @Test
    @DisplayName("both reads refuse a caller without REPORTING_READ")
    void bothReadsRefuseWithoutReportingRead() throws Exception {
        MvcResult stats = mvc.perform(get(BASE + LOCATION + "/voice/call-stats")
                        .with(tokenFor(DISPATCHER))
                        .queryParam("businessDate", "2026-09-05"))
                .andReturn();
        MvcResult coverage = mvc.perform(get(BASE + LOCATION + "/voice/call-stats/coverage")
                        .with(tokenFor(DISPATCHER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        for (MvcResult refused : List.of(stats, coverage)) {
            assertThat(refused.getResponse().getStatus()).isEqualTo(403);
            assertThat(refused.getResponse().getContentAsString())
                    .contains("INSUFFICIENT_CAPABILITY")
                    .contains(Capability.REPORTING_READ.code());
        }
    }

    @Test
    @DisplayName(
            "a row closed before the migration answers null for every KPI, never zero; a measured row answers its figures")
    void unmeasuredIsNullAndMeasuredIsNumeric() throws Exception {
        insertFactRow("legacy", 9, null, null, null, null, null);
        insertFactRow("measured", 10, 4, 120L, 4, 61, 3);

        MvcResult ok = mvc.perform(get(BASE + LOCATION + "/voice/call-stats")
                        .with(tokenFor(MANAGER))
                        .queryParam("businessDate", "2026-09-05"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String body = ok.getResponse().getContentAsString();
        List<Map<String, Object>> rows = JsonPath.read(body, "$");
        Map<String, Object> legacy = rows.stream()
                .filter(row -> "legacy".equals(row.get("operatorPrincipalId")))
                .findFirst()
                .orElseThrow();
        for (String field : List.of(
                "answeredCallCount", "ringSecondsTotal", "ringSampleCount", "ringSecondsMax", "convertedCount")) {
            assertThat(legacy).as(field).containsKey(field);
            assertThat(legacy.get(field)).as("%s of a pre-migration row", field).isNull();
        }
        assertThat(longOf(legacy.get("talkDurationSeconds"))).isEqualTo(90L);

        Map<String, Object> measured = rows.stream()
                .filter(row -> "measured".equals(row.get("operatorPrincipalId")))
                .findFirst()
                .orElseThrow();
        assertThat(measured.get("answeredCallCount")).isEqualTo(4);
        assertThat(longOf(measured.get("ringSecondsTotal"))).isEqualTo(120L);
        assertThat(measured.get("ringSampleCount")).isEqualTo(4);
        assertThat(measured.get("ringSecondsMax")).isEqualTo(61);
        assertThat(measured.get("convertedCount")).isEqualTo(3);
    }

    @Test
    @DisplayName("a branch with no voice installation and no history is NOT_CONNECTED with no closed days")
    void coverageNotConnected() throws Exception {
        MvcResult ok = mvc.perform(get(BASE + LOCATION + "/voice/call-stats/coverage")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String body = ok.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.connection")).isEqualTo("NOT_CONNECTED");
        assertThat((Integer) JsonPath.read(body, "$.activeInstallationCount")).isZero();
        assertThat((Integer) JsonPath.read(body, "$.closedDaysWithCalls")).isZero();
        assertThat((Object) JsonPath.read(body, "$.firstCallEventAt")).isNull();
        assertThat((Object) JsonPath.read(body, "$.lastCallEventAt")).isNull();
        assertThat((String) JsonPath.read(body, "$.nextCloseAt")).isNotBlank();
    }

    @Test
    @DisplayName("an active voice installation bound to the branch makes it CONNECTED; one bound elsewhere does not")
    void coverageConnectedOnlyAtTheBoundBranch() throws Exception {
        insertVoiceInstallation(INSTALLATION, "ACTIVE", "ACTIVE", LOCATION);

        MvcResult here = mvc.perform(get(BASE + LOCATION + "/voice/call-stats/coverage")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();
        MvcResult elsewhere = mvc.perform(get(BASE + OTHER_LOCATION + "/voice/call-stats/coverage")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat((String) JsonPath.read(here.getResponse().getContentAsString(), "$.connection"))
                .isEqualTo("CONNECTED");
        assertThat((Integer) JsonPath.read(here.getResponse().getContentAsString(), "$.activeInstallationCount"))
                .isEqualTo(1);
        assertThat((String) JsonPath.read(elsewhere.getResponse().getContentAsString(), "$.connection"))
                .isEqualTo("NOT_CONNECTED");
    }

    @Test
    @DisplayName("a draft installation and a suspended binding do not connect the branch")
    void coverageIgnoresInstallationsThatAreNotLive() throws Exception {
        insertVoiceInstallation(UUID.randomUUID(), "DRAFT", "ACTIVE", LOCATION);
        insertVoiceInstallation(UUID.randomUUID(), "ACTIVE", "SUSPENDED", LOCATION);

        MvcResult ok = mvc.perform(get(BASE + LOCATION + "/voice/call-stats/coverage")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat((String) JsonPath.read(ok.getResponse().getContentAsString(), "$.connection"))
                .isEqualTo("NOT_CONNECTED");
    }

    @Test
    @DisplayName("history keeps the picture honest after the provider is gone: closed days with calls are counted")
    void coverageCountsClosedDaysWithCallsEvenWithoutAProvider() throws Exception {
        insertFactRow("alice", 10, 1, 7L, 1, 7, 1);
        insertCallEvent("call-1", "OFFERED", Instant.parse("2026-09-05T05:00:00Z"));
        insertCallEvent("call-1", "ENDED", Instant.parse("2026-09-05T05:03:00Z"));

        MvcResult ok = mvc.perform(get(BASE + LOCATION + "/voice/call-stats/coverage")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        String body = ok.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.connection")).isEqualTo("NOT_CONNECTED");
        assertThat((Integer) JsonPath.read(body, "$.closedDaysWithCalls")).isEqualTo(1);
        assertThat((String) JsonPath.read(body, "$.firstCallEventAt")).startsWith("2026-09-05T05:00");
        assertThat((String) JsonPath.read(body, "$.lastCallEventAt")).startsWith("2026-09-05T05:03");
        assertThat(body)
                .as("no caller number, customer or order id is ever in a coverage body")
                .doesNotContain("caller")
                .doesNotContain("customer")
                .doesNotContain("order");
    }

    @Test
    @DisplayName("a backwards range and a range wider than a year are refused, not answered")
    void coverageRefusesABadRange() throws Exception {
        MvcResult backwards = mvc.perform(get(BASE + LOCATION + "/voice/call-stats/coverage")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-07")
                        .queryParam("to", "2026-09-01"))
                .andReturn();
        MvcResult wide = mvc.perform(get(BASE + LOCATION + "/voice/call-stats/coverage")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2024-01-01")
                        .queryParam("to", "2026-09-01"))
                .andReturn();

        assertThat(backwards.getResponse().getStatus()).isEqualTo(400);
        assertThat(wide.getResponse().getStatus()).isEqualTo(400);
    }

    /** JSON numbers arrive as Integer when small, Long when large. */
    private static long longOf(@Nullable Object number) {
        return ((Number) Objects.requireNonNull(number)).longValue();
    }

    // ------------------------------------------------------------------ fixtures

    private void insertFactRow(
            String operator,
            int hour,
            @Nullable Integer answeredCalls,
            @Nullable Long ringTotal,
            @Nullable Integer ringSamples,
            @Nullable Integer ringMax,
            @Nullable Integer converted) {
        jdbc.sql("""
                INSERT INTO reporting.fact_call_hour
                    (tenant_id, business_date, hour_of_day, location_id, brand_id, operator_principal_id,
                     boundary_version, metric_calculation_version, offered_count, answered_count,
                     missed_count, transferred_count, talk_duration_seconds,
                     answered_call_count, ring_seconds_total, ring_sample_count, ring_seconds_max,
                     converted_count)
                VALUES (:t, DATE '2026-09-05', :hour, :l, :b, :op, 1, 1, 1, 1, 0, 0, 90,
                        :answered, :total, :samples, :max, :converted)
                """)
                .param("t", TENANT)
                .param("hour", hour)
                .param("l", LOCATION)
                .param("b", BRAND)
                .param("op", operator)
                .param("answered", answeredCalls)
                .param("total", ringTotal)
                .param("samples", ringSamples)
                .param("max", ringMax)
                .param("converted", converted)
                .update();
    }

    private void insertCallEvent(String providerCallId, String eventType, Instant occurredAt) {
        jdbc.sql("""
                INSERT INTO voice.call_events
                    (id, tenant_id, brand_id, location_id, installation_id, provider_call_id, event_type,
                     direction, occurred_at)
                VALUES (:id, :t, :b, :l, :installation, :call, :type, 'INBOUND', :at)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("b", BRAND)
                .param("l", LOCATION)
                .param("installation", INSTALLATION)
                .param("call", providerCallId)
                .param("type", eventType)
                .param("at", occurredAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertVoiceInstallation(
            UUID installationId, String installationStatus, String bindingStatus, UUID boundLocation) {
        jdbc.sql("""
                INSERT INTO integration.provider_environments
                    (code, provider_category, provider_type, base_url, is_production, egress_allowlist)
                VALUES ('call-stats-test', 'VOICE', 'HOSTED_PBX', 'https://pbx.example.invalid', false, '')
                ON CONFLICT DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO integration.installations
                    (id, tenant_id, provider_category, provider_type, environment_code, display_name, status,
                     non_sensitive_config)
                VALUES (:id, :t, 'VOICE', 'HOSTED_PBX', 'call-stats-test', 'PBX', :status, '{}')
                """)
                .param("id", installationId)
                .param("t", TENANT)
                .param("status", installationStatus)
                .update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, location_id, status)
                VALUES (:id, :t, :installation, :b, :l, :status)
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("installation", installationId)
                .param("b", BRAND)
                .param("l", boundLocation)
                .param("status", bindingStatus)
                .update();
    }

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'call-stats-endpoint', 'Call stats', 'Call stats', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        for (UUID location : List.of(LOCATION, OTHER_LOCATION)) {
            String code = location.equals(LOCATION) ? "MAIN" : "OTHER";
            jdbc.sql("""
                    INSERT INTO tenant.locations
                        (id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                    VALUES (:id, :t, :b, :code, :slug, 'Location', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", location)
                    .param("t", TENANT)
                    .param("b", BRAND)
                    .param("code", code)
                    .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                    .update();
        }
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'call stats endpoint test', :validFrom)
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
