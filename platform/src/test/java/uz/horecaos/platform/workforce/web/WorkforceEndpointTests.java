package uz.horecaos.platform.workforce.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.application.staff.StaffMemberService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.application.port.ConfigurationValueCache;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;
import uz.horecaos.platform.workforce.api.WorkforceConfigurationKeys;

/**
 * ADR 0156 over the real HTTP stack: the flag, the capabilities, the JSON a console really sends,
 * the idempotent double tap, names and the payroll export's gate.
 *
 * <p>The services are proven against a database in {@code StaffShiftServiceTests} and its siblings;
 * what only this suite can show is what the routes do with a request: whether a missing optional
 * field is a 400 (Jackson 3 refuses an omitted primitive), whether a route answers a person who
 * should not know it exists, and whether the capability the controller declares is the one the
 * handler needs.
 */
@SpringBootTest
@AutoConfigureMockMvc
class WorkforceEndpointTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final UUID TENANT = UUID.fromString("018fb200-6000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb200-6000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fb200-6000-7000-8000-0000000000c1");
    private static final UUID SECOND_LOCATION = UUID.fromString("018fb200-6000-7000-8000-0000000000c2");

    private static final String OWNER = "wf-owner";
    private static final String MANAGER = "wf-manager";
    private static final String COOK = "wf-cook";
    private static final String FINANCE = "wf-finance";
    private static final String STRANGER = "wf-stranger";

    private static final String OPERATIONS = "/api/v1/operations/tenants/" + TENANT;
    private static final String BRANCH = OPERATIONS + "/brands/" + BRAND + "/locations/" + LOCATION + "/staff";
    private static final String SELF = OPERATIONS + "/staff";

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
    @SuppressWarnings("NullAway")
    private MockMvc mvc;

    @Autowired
    @SuppressWarnings("NullAway")
    private JdbcClient jdbc;

    @Autowired
    @SuppressWarnings("NullAway")
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    @SuppressWarnings("NullAway")
    private StaffMemberService members;

    @Autowired
    @SuppressWarnings("NullAway")
    private TransactionTemplate tx;

    @Autowired
    @SuppressWarnings("NullAway")
    private ConfigurationValueCache configurationCache;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE reporting.fact_staff_shift, reporting.report_exports")
                .update();
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.configuration_values").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        configurationCache.evict(WorkforceConfigurationKeys.ATTENDANCE_ENABLED_CODE, ResourceScope.tenant(TENANT));
        roleRegistry.synchronize();
        seedTenancy();
        member(OWNER, "Olim", "Owner");
        member(MANAGER, "Mavlon", "Manager");
        member(COOK, "Aziza", "Karimova");
        member(FINANCE, "Farrukh", "Finance");
        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        grant(MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", LOCATION);
        grant(COOK, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION);
        grant(FINANCE, PlatformRole.TENANT_FINANCE, "TENANT", TENANT);
    }

    // ================================================================================== the flag

    @Test
    @DisplayName("while attendance is off every workforce route is absent, and the status read says so")
    void offMeansAbsent() throws Exception {
        assertThat(get(BRANCH + "/roster?from=2026-10-05&to=2026-10-11", MANAGER)
                        .getResponse()
                        .getStatus())
                .isEqualTo(404);
        MvcResult clock = post(SELF + "/me/attendance/clock-ins", "{\"locationId\":\"" + LOCATION + "\"}", COOK);
        assertThat(clock.getResponse().getStatus()).isEqualTo(404);
        assertThat(json(get(SELF + "/attendance", COOK)).get("enabled").asBoolean())
                .isFalse();

        enable();

        assertThat(get(BRANCH + "/roster?from=2026-10-05&to=2026-10-11", MANAGER)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(json(get(SELF + "/attendance", COOK)).get("enabled").asBoolean())
                .isTrue();
    }

    @Test
    @DisplayName("the payroll export is an unknown report while attendance is off")
    void theExportIsUnknownWhileOff() throws Exception {
        MvcResult refused = exportAttendance(FINANCE);

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString(UTF_8)).contains("Unknown report key");
    }

    // ================================================================================ the clock

    @Test
    @DisplayName(
            "a clock-in takes {locationId} and a clock-out takes {}: no optional field is required, and a tap sends no time")
    void theClockWithTheConsolesRealJson() throws Exception {
        enable();
        String key = "tap-" + UUID.randomUUID();

        MvcResult in = postWithKey(SELF + "/me/attendance/clock-ins", clockIn(LOCATION), COOK, key);
        assertThat(in.getResponse().getStatus()).isEqualTo(201);
        assertThat(in.getResponse().getHeader("ETag")).isNotBlank();
        JsonNode opened = json(in);
        assertThat(opened.get("status").asText()).isEqualTo("OPEN");
        assertThat(opened.get("openSource").asText()).isEqualTo("CONSOLE_SELF");

        MvcResult replay = postWithKey(SELF + "/me/attendance/clock-ins", clockIn(LOCATION), COOK, key);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(json(replay).get("id").asText()).isEqualTo(opened.get("id").asText());
        assertThat(shiftCount()).as("a repeated key is one shift").isEqualTo(1);

        MvcResult second = post(SELF + "/me/attendance/clock-ins", clockIn(LOCATION), COOK);
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(second.getResponse().getContentAsString(UTF_8)).contains("SHIFT_ALREADY_OPEN");

        MvcResult out = post(SELF + "/me/attendance/clock-outs", "{}", COOK);
        assertThat(out.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(out).get("status").asText()).isIn("CLOSED", "AWAITING_APPROVAL");

        MvcResult nothing = post(SELF + "/me/attendance/clock-outs", "{}", COOK);
        assertThat(nothing.getResponse().getStatus()).isEqualTo(422);
        assertThat(nothing.getResponse().getContentAsString(UTF_8)).contains("NO_OPEN_SHIFT");
    }

    @Test
    @DisplayName("a branch where the person holds no job is the answer an unknown branch gets")
    void aBranchWithoutAJobIsAbsent() throws Exception {
        enable();

        MvcResult elsewhere = post(SELF + "/me/attendance/clock-ins", clockIn(SECOND_LOCATION), COOK);

        assertThat(elsewhere.getResponse().getStatus()).isEqualTo(404);
        assertThat(shiftCount()).isZero();
    }

    @Test
    @DisplayName("a clock call without an Idempotency-Key is refused, so a double tap cannot open two shifts")
    void aClockCallNeedsAKey() throws Exception {
        enable();

        MvcResult refused = mvc.perform(MockMvcRequestBuilders.post(SELF + "/me/attendance/clock-ins")
                        .with(tokenFor(COOK))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(clockIn(LOCATION)))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .isGreaterThanOrEqualTo(400)
                .isLessThan(500);
        assertThat(shiftCount()).isZero();
    }

    @Test
    @DisplayName("the clock is rate limited per person: ten a minute, then 429")
    void theClockIsRateLimited() throws Exception {
        enable();
        // Its own person: the limiter's budget outlives a test, and the other tests must find theirs full.
        String tapper = "wf-tapper";
        member(tapper, "Timur", "Tapper");
        grant(tapper, PlatformRole.LOCATION_STAFF, "LOCATION", LOCATION);
        int last = 0;
        for (int call = 0; call < 11; call++) {
            last = post(SELF + "/me/attendance/clock-ins", clockIn(LOCATION), tapper)
                    .getResponse()
                    .getStatus();
        }

        assertThat(last).isEqualTo(429);
    }

    // ============================================================================ capabilities

    @Test
    @DisplayName("a cook can clock and read their own shifts, and cannot read the rota or write to it")
    void aCookIsRefusedTheManagersRoutes() throws Exception {
        enable();

        assertThat(get(SELF + "/me/attendance", COOK).getResponse().getStatus()).isEqualTo(200);
        MvcResult rota = get(BRANCH + "/roster?from=2026-10-05&to=2026-10-11", COOK);
        assertThat(rota.getResponse().getStatus()).isEqualTo(403);
        assertThat(rota.getResponse().getContentAsString(UTF_8)).contains("staff.shift.read");
        MvcResult write = post(
                BRANCH + "/roster/entries",
                "{\"memberId\":\"" + memberId(COOK) + "\",\"plannedStart\":\"2026-10-08T04:00:00Z\","
                        + "\"plannedEnd\":\"2026-10-08T12:00:00Z\"}",
                COOK);
        assertThat(write.getResponse().getStatus()).isEqualTo(403);
        assertThat(write.getResponse().getContentAsString(UTF_8)).contains("staff.shift.manage");
        MvcResult open = post(
                BRANCH + "/shifts",
                "{\"memberId\":\"" + memberId(COOK) + "\",\"startedAt\":\"" + Instant.now() + "\"}",
                COOK);
        assertThat(open.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("an account with no job in the tenant is refused the clock")
    void aStrangerCannotClock() throws Exception {
        enable();

        MvcResult refused = post(SELF + "/me/attendance/clock-ins", clockIn(LOCATION), STRANGER);

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(shiftCount()).isZero();
    }

    @Test
    @DisplayName(
            "a name appears only for a caller who also holds staff.profile.read; finance reads the reference alone")
    void namesFollowTheProfileCapability() throws Exception {
        enable();
        post(SELF + "/me/attendance/clock-ins", clockIn(LOCATION), COOK);
        post(SELF + "/me/attendance/clock-outs", "{}", COOK);

        String asManager = get(BRANCH + "/shifts", MANAGER).getResponse().getContentAsString(UTF_8);
        String asFinance = get(BRANCH + "/shifts", FINANCE).getResponse().getContentAsString(UTF_8);

        assertThat(asManager).contains("Aziza");
        assertThat(asFinance)
                .as("finance holds staff.shift.read and not staff.profile.read")
                .contains("displayReference")
                .doesNotContain("Aziza", "Karimova");
        assertThat(json(get(BRANCH + "/shifts", FINANCE)).get("items")).hasSize(1);
    }

    // ================================================================================== the rota

    @Test
    @DisplayName(
            "copying a week onto one that is partly occupied copies the free days and counts the others, through the real transaction")
    void copyingOntoAPartlyOccupiedWeek() throws Exception {
        enable();
        UUID cook = memberId(COOK);
        // The 05th to the 08th are copied a week on. The target week already holds the 13th and the 15th,
        // which clash with the copies of the 06th and the 08th: the copy meets an overlap between two
        // entries and another after the last one, and must go on past the first and finish after the second.
        for (String day : List.of("05", "06", "07", "08", "13", "15")) {
            MvcResult drafted = post(BRANCH + "/roster/entries", entry(cook, day), MANAGER);
            assertThat(drafted.getResponse().getStatus()).isEqualTo(201);
        }

        MvcResult copied =
                post(BRANCH + "/roster/copies", "{\"fromWeek\":\"2026-10-05\",\"toWeek\":\"2026-10-12\"}", MANAGER);

        assertThat(copied.getResponse().getStatus())
                .as(copied.getResponse().getContentAsString(UTF_8))
                .isEqualTo(200);
        assertThat(json(copied).get("created").asInt())
                .as("the 05th and the 07th, a week on")
                .isEqualTo(2);
        assertThat(json(copied).get("skipped").asInt())
                .as("the 06th and the 08th")
                .isEqualTo(2);
        assertThat(jdbc.sql("SELECT count(*) FROM workforce.staff_roster_entries WHERE planned_start >= :from")
                        .param("from", java.time.OffsetDateTime.parse("2026-10-12T00:00:00Z"))
                        .query(Long.class)
                        .single())
                .isEqualTo(4L);
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code = 'workforce.roster.drafted'")
                        .query(Long.class)
                        .single())
                .as("the six drafts and the one fact for the copy")
                .isEqualTo(7L);
    }

    // ============================================================================ the timesheet

    @Test
    @DisplayName("a manager records a start and an end with only the required fields, and the end needs If-Match")
    void aManagerWritesWithMinimalJson() throws Exception {
        enable();
        String started = Instant.now().minusSeconds(120).toString();

        MvcResult opened = post(
                BRANCH + "/shifts",
                "{\"memberId\":\"" + memberId(COOK) + "\",\"startedAt\":\"" + started + "\"}",
                MANAGER);
        assertThat(opened.getResponse().getStatus()).isEqualTo(201);
        JsonNode shift = json(opened);
        String id = shift.get("id").asText();
        String etag = opened.getResponse().getHeader("ETag");
        assertThat(etag).isNotBlank();
        String ended = "{\"endedAt\":\"" + Instant.now() + "\"}";

        MvcResult noVersion = post(BRANCH + "/shifts/" + id + "/closures", ended, MANAGER);
        assertThat(noVersion.getResponse().getStatus()).isEqualTo(400);

        MvcResult closed =
                postWithHeaders(BRANCH + "/shifts/" + id + "/closures", ended, MANAGER, Map.of("If-Match", etag));
        assertThat(closed.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(closed).get("endedAt").isNull()).isFalse();
    }

    @Test
    @DisplayName("a correction against a stale version is a conflict, not an overwrite")
    void aStaleVersionConflicts() throws Exception {
        enable();
        MvcResult opened = post(
                BRANCH + "/shifts",
                "{\"memberId\":\"" + memberId(COOK) + "\",\"startedAt\":\""
                        + Instant.now().minusSeconds(60) + "\"}",
                MANAGER);
        String id = json(opened).get("id").asText();

        MvcResult stale = postWithHeaders(
                BRANCH + "/shifts/" + id + "/closures",
                "{\"endedAt\":\"" + Instant.now() + "\"}",
                MANAGER,
                Map.of("If-Match", "\"99\""));

        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
    }

    // =============================================================================== the export

    @Test
    @DisplayName(
            "STAFF_ATTENDANCE needs staff.shift.read besides report.export: a role that exports reports and cannot read hours is refused")
    void theExportNeedsTheHoursCapability() throws Exception {
        enable();
        customRole("wf-exporter-only", Capability.REPORT_EXPORT);
        customRole("wf-exporter-reader", Capability.REPORT_EXPORT, Capability.STAFF_SHIFT_READ);

        MvcResult refused = exportAttendance("wf-exporter-only");
        MvcResult accepted = exportAttendance("wf-exporter-reader");

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString(UTF_8)).contains("staff.shift.read");
        assertThat(accepted.getResponse().getStatus())
                .as(
                        "the same role with the hours capability added is let through, so the refusal above was about hours")
                .isEqualTo(202);
    }

    @Test
    @DisplayName("finance, who holds the hours and the export, is queued")
    void financeIsQueued() throws Exception {
        enable();

        MvcResult queued = exportAttendance(FINANCE);

        assertThat(queued.getResponse().getStatus()).isEqualTo(202);
        assertThat(queued.getResponse().getContentAsString(UTF_8)).contains("QUEUED");
    }

    // ================================================================================ fixtures

    private MvcResult exportAttendance(String subject) throws Exception {
        String body = "{\"reportKey\":\"STAFF_ATTENDANCE\",\"columns\":[\"staffDisplayReference\",\"paidSeconds\"],"
                + "\"from\":\"2026-10-01T00:00:00Z\",\"to\":\"2026-10-08T00:00:00Z\","
                + "\"purpose\":\"endpoint test\"}";
        return post("/api/v1/tenants/" + TENANT + "/reporting/exports", body, subject);
    }

    /** A draft for {@code member} on the given day of October 2026, 08:00 to 16:00 in Tashkent. */
    private static String entry(UUID member, String day) {
        return "{\"memberId\":\"" + member + "\",\"plannedStart\":\"2026-10-" + day + "T03:00:00Z\","
                + "\"plannedEnd\":\"2026-10-" + day + "T11:00:00Z\"}";
    }

    private static String clockIn(UUID location) {
        return "{\"locationId\":\"" + location + "\"}";
    }

    private long shiftCount() {
        return jdbc.sql("SELECT count(*) FROM workforce.staff_shifts WHERE tenant_id = :t")
                .param("t", TENANT)
                .query(Long.class)
                .single();
    }

    private UUID memberId(String subject) {
        return jdbc.sql("SELECT id FROM iam.staff_members WHERE tenant_id = :t AND principal_subject = :s")
                .param("t", TENANT)
                .param("s", subject)
                .query(UUID.class)
                .single();
    }

    private void enable() {
        jdbc.sql("""
                INSERT INTO tenant.configuration_values
                    (id, key_code, scope_type, tenant_id, value_type, boolean_value, set_by)
                VALUES (:id, :key, 'TENANT', :tenantId, 'BOOLEAN', true, 'a-test')
                """)
                .param("id", UUID.randomUUID())
                .param("key", WorkforceConfigurationKeys.ATTENDANCE_ENABLED_CODE)
                .param("tenantId", TENANT)
                .update();
        configurationCache.evict(WorkforceConfigurationKeys.ATTENDANCE_ENABLED_CODE, ResourceScope.tenant(TENANT));
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'workforce-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        for (Object[] location : new Object[][] {{LOCATION, "L1", "l1"}, {SECOND_LOCATION, "L2", "l2"}}) {
            jdbc.sql("""
                    INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                        timezone, status, version)
                    VALUES (:id, :t, :b, :code, :slug, 'Branch', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", location[0])
                    .param("t", TENANT)
                    .param("b", BRAND)
                    .param("code", location[1])
                    .param("slug", location[2])
                    .update();
        }
    }

    private void member(String subject, String first, String last) {
        tx.executeWithoutResult(status -> {
            members.registerInvited(TENANT, subject, first, last, null, "fixture-inviter", "corr-fixture");
            members.activate(TENANT, subject, first, last, "corr-fixture");
        });
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'workforce endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeId).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    /** A tenant-defined role holding exactly these capabilities, granted to {@code subject} across the tenant. */
    private void customRole(String subject, Capability... capabilities) {
        UUID roleId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO iam.roles (id, tenant_id, code, name, scope_type, status, is_platform_defined)
                VALUES (:id, :tenantId, :code, :code, 'TENANT', 'ACTIVE', false)
                """)
                .param("id", roleId)
                .param("tenantId", TENANT)
                .param("code", subject + "-role")
                .update();
        for (Capability capability : capabilities) {
            jdbc.sql("INSERT INTO iam.role_capabilities (role_id, capability_code) VALUES (:role, :code)")
                    .param("role", roleId)
                    .param("code", capability.code())
                    .update();
        }
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, false, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'workforce endpoint test', :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", roleId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private MvcResult get(String path, String subject) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).with(tokenFor(subject)))
                .andReturn();
    }

    private MvcResult post(String path, String body, String subject) throws Exception {
        return postWithKey(path, body, subject, UUID.randomUUID().toString());
    }

    private MvcResult postWithKey(String path, String body, String subject, String key) throws Exception {
        return postWithHeaders(path, body, subject, Map.of(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key));
    }

    private MvcResult postWithHeaders(String path, String body, String subject, Map<String, String> headers)
            throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.post(path)
                .with(tokenFor(subject))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        Map<String, String> all = new java.util.LinkedHashMap<>(headers);
        all.putIfAbsent(
                IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, UUID.randomUUID().toString());
        all.forEach(request::header);
        return mvc.perform(request).andReturn();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString(UTF_8));
    }

    private static RequestPostProcessor tokenFor(@Nullable String subject) {
        return jwt().jwt(builder -> builder.subject(String.valueOf(subject))
                .claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
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
