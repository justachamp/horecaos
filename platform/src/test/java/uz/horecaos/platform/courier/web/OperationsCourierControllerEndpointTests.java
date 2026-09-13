package uz.horecaos.platform.courier.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

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
 * IA 3.3's courier roster surface, reached through the HTTP/capability layer
 * rather than by calling {@code CourierRosterService} or
 * {@code CourierEngagementService} directly.
 *
 * <p>{@code CourierComplianceFileTests} proves the persistence and encryption
 * behaviour of this wave's new relations, but every one of its tests
 * constructs the service objects as plain Java and never goes near {@code
 * OperationsCourierController} or {@code CapabilityEnforcementInterceptor} —
 * which is exactly how the P19 adversarial review found that {@code
 * unbindCourierFromBranch} 500'd on every real call while
 * {@code atMostOneBranchIsPrimary} passed cleanly. This suite is the missing
 * half: every new route driven through {@link MockMvc} with a real JWT, an
 * authorized caller (2xx/202), a caller missing the required capability
 * (403 {@code INSUFFICIENT_CAPABILITY}), and — the assertion that matters
 * most on any tenant-reachable surface — a grant scoped to one tenant unable
 * to reach another tenant's courier, group, or binding through this
 * controller.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OperationsCourierControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9c20-3000-7000-8000-0000000000d1");
    private static final UUID BRAND = UUID.fromString("018f9c20-3000-7000-8000-0000000000d2");
    private static final UUID LOCATION = UUID.fromString("018f9c20-3000-7000-8000-0000000000d3");
    private static final UUID SECOND_LOCATION = UUID.fromString("018f9c20-3000-7000-8000-0000000000d4");
    private static final UUID COURIER_TYPE = UUID.fromString("018f9c20-3000-7000-8000-0000000000d5");
    private static final UUID COURIER = UUID.fromString("018f9c20-3000-7000-8000-0000000000d6");

    private static final UUID OTHER_TENANT = UUID.fromString("018f9c20-3000-7000-8000-0000000000e1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9c20-3000-7000-8000-0000000000e2");
    private static final UUID OTHER_LOCATION = UUID.fromString("018f9c20-3000-7000-8000-0000000000e3");
    private static final UUID OTHER_COURIER_TYPE = UUID.fromString("018f9c20-3000-7000-8000-0000000000e4");

    // TENANT_ADMIN is the only bundle holding COURIER_ENGAGEMENT_MANAGE
    // (PlatformRole.java); TENANT_OWNER holds COURIER_READ but deliberately
    // not the engagement-manage or pii-reveal capabilities, so it doubles as
    // the "authorized to read, refused to write or reveal" negative case
    // below rather than needing a synthetic no-grant principal.
    private static final String ADMIN = "018f9c20-4000-7000-8000-0000000000f1";
    private static final String OWNER = "018f9c20-4000-7000-8000-0000000000f2";
    private static final String BRAND_MANAGER = "018f9c20-4000-7000-8000-0000000000f3";
    private static final String OTHER_TENANT_ADMIN = "018f9c20-4000-7000-8000-0000000000f4";
    private static final String REVEALER = "018f9c20-4000-7000-8000-0000000000f5";
    private static final String ADJUSTER = "018f9c20-4000-7000-8000-0000000000f6";

    // T17: the real PlatformRole bundles the roster surface is granted to
    // (LOCATION_MANAGER at LOCATION, COURIER_DISPATCHER at BRAND) — distinct
    // from ADJUSTER above, which stands in for a capability no bundle grants
    // below TENANT and so needs a hand-authored custom role instead.
    private static final String LOCATION_MANAGER_SUBJECT = "018f9c20-4000-7000-8000-0000000000f7";
    private static final String BRAND_DISPATCHER = "018f9c20-4000-7000-8000-0000000000f8";

    // ADR 0042: courier.pii.reveal is granted per person, not through any
    // PlatformRole bundle (see COURIER_PII_REVEAL's own doc) — so REVEALER's
    // standing comes from a hand-authored tenant-scoped custom role rather
    // than from RoleRegistrySynchronizer, which only ever touches the
    // platform-defined roles named in PlatformRole.
    private static final UUID REVEALER_ROLE = UUID.fromString("018f9c20-5000-7000-8000-0000000000a1");

    // ADR 0108: courier.adjustment.create is held by LOCATION_MANAGER (location
    // scope) and COURIER_DISPATCHER (brand scope) in PlatformRole.java, neither
    // of which this file's grant() helper (TENANT scope only) can hand out
    // directly. A hand-authored TENANT-scoped custom role is the same pattern
    // REVEALER already uses for courier.pii.reveal, and TENANT is broader than
    // either bundle's own scope, so it satisfies the same check.
    private static final UUID ADJUSTER_ROLE = UUID.fromString("018f9c20-5000-7000-8000-0000000000a2");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the courier endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // ADR 0029: the compliance file's envelope encryption needs a real key
        // to protect/reveal against, the same preset CustomerSessionSurfaceTests
        // uses for the same reason.
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    private static String couriersPath(UUID tenantId) {
        return "/api/v1/operations/tenants/" + tenantId + "/couriers";
    }

    private static String groupsPath(UUID tenantId) {
        return "/api/v1/operations/tenants/" + tenantId + "/courier-groups";
    }

    private static String rosterPath(UUID tenantId) {
        return "/api/v1/operations/tenants/" + tenantId + "/courier-roster-entries";
    }

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_ledger_entries CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_settlement_periods CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_adjustment_reasons CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_branch_bindings CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_group_members CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_groups CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_engagements CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.couriers CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.courier_types CASCADE").update();
        // CASCADE also clears iam.roles/iam.grants through fk_role_tenant and
        // fk_grant_tenant, wiping the platform-defined roles too; synchronize()
        // below rebuilds those from code, exactly as RoleRegistrySynchronizer
        // does at application startup.
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        seedTenancy(TENANT, BRAND, LOCATION, SECOND_LOCATION, COURIER_TYPE);
        seedTenancy(OTHER_TENANT, OTHER_BRAND, OTHER_LOCATION, null, OTHER_COURIER_TYPE);
        insertCourier(COURIER, TENANT, COURIER_TYPE, "K-100");

        grant(ADMIN, PlatformRole.TENANT_ADMIN, TENANT);
        grant(OWNER, PlatformRole.TENANT_OWNER, TENANT);
        grant(BRAND_MANAGER, PlatformRole.BRAND_MANAGER, TENANT);
        grant(OTHER_TENANT_ADMIN, PlatformRole.TENANT_ADMIN, OTHER_TENANT);

        jdbc.sql("""
                INSERT INTO iam.roles (id, tenant_id, code, name, scope_type, status, is_platform_defined)
                VALUES (:id, :tenantId, 'courier-pii-revealer', 'Courier PII revealer', 'TENANT', 'ACTIVE', false)
                """).param("id", REVEALER_ROLE).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO iam.role_capabilities (role_id, capability_code) VALUES (:roleId, :capability)
                """)
                .param("roleId", REVEALER_ROLE)
                .param("capability", Capability.COURIER_PII_REVEAL.code())
                .update();
        grantCustomRole(REVEALER, REVEALER_ROLE, TENANT);

        jdbc.sql("""
                INSERT INTO iam.roles (id, tenant_id, code, name, scope_type, status, is_platform_defined)
                VALUES (:id, :tenantId, 'courier-adjuster', 'Courier adjuster', 'TENANT', 'ACTIVE', false)
                """).param("id", ADJUSTER_ROLE).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO iam.role_capabilities (role_id, capability_code) VALUES (:roleId, :capability)
                """)
                .param("roleId", ADJUSTER_ROLE)
                .param("capability", Capability.COURIER_ADJUSTMENT_CREATE.code())
                .update();
        grantCustomRole(ADJUSTER, ADJUSTER_ROLE, TENANT);

        seedActiveEngagement(COURIER, TENANT);
        seedAdjustmentReason(TENANT, "GOODWILL_BONUS", "BONUS", "DELIVERED_VOLUME");
    }

    // ------------------------------------------------------------- compliance file

    @Test
    void anAuthorizedManagerFilesAndRevealsTheComplianceFile() throws Exception {
        MvcResult written = mvc.perform(post(couriersPath(TENANT) + "/" + COURIER + "/compliance-file")
                        .with(tokenFor(ADMIN))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "file-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"passport":"AA1234567","reason":"onboarding"}
                                """))
                .andReturn();
        assertThat(written.getResponse().getStatus()).isEqualTo(202);

        MvcResult revealed = mvc.perform(get(couriersPath(TENANT) + "/" + COURIER + "/compliance-file")
                        .with(tokenFor(REVEALER))
                        .param("purpose", "a compliance check"))
                .andReturn();
        assertThat(revealed.getResponse().getStatus()).isEqualTo(200);
        assertThat(revealed.getResponse().getContentAsString()).contains("AA1234567");
    }

    @Test
    void aCallerWithoutEngagementManageCannotFileTheComplianceFile() throws Exception {
        MvcResult refused = mvc.perform(post(couriersPath(TENANT) + "/" + COURIER + "/compliance-file")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "file-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"passport":"AA1234567","reason":"onboarding"}
                                """))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.COURIER_ENGAGEMENT_MANAGE.code());
    }

    @Test
    void aCallerWithoutPiiRevealCannotRevealTheComplianceFile() throws Exception {
        mvc.perform(post(couriersPath(TENANT) + "/" + COURIER + "/compliance-file")
                .with(tokenFor(ADMIN))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "file-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"passport":"AA1234567","reason":"onboarding"}
                        """));

        // ADMIN holds courier.engagement.manage, which is exactly the
        // capability the reveal must NOT accept in its place — the two are
        // deliberately separate (Capability.COURIER_PII_REVEAL's own doc).
        MvcResult refused = mvc.perform(get(couriersPath(TENANT) + "/" + COURIER + "/compliance-file")
                        .with(tokenFor(ADMIN))
                        .param("purpose", "curiosity"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.COURIER_PII_REVEAL.code());
    }

    // ------------------------------------------------------ adjustments (ADR 0108)

    /**
     * The critical regression the wave's brief named: before this wave, {@code
     * AdjustmentRequest} carried its own {@code origin} field, and a client
     * sending {@code "origin":"RULE"} skipped the {@code MANUAL} branch of
     * {@code CourierAdjustmentService.request}'s four-eyes check. {@code
     * AdjustmentRequest} no longer declares the field at all — Spring Boot's
     * default Jackson configuration ignores an unrecognised property rather
     * than failing the request, so this proves the stronger claim: even a
     * caller who still remembers the old field name cannot make it reach the
     * ledger. The written entry's {@code origin} column, read back directly
     * from the database rather than from any response DTO, is the only
     * evidence that matters here.
     */
    @Test
    void aRequestSuppliedOriginIsIgnoredAndTheLedgerEntryIsAlwaysManual() throws Exception {
        // Without an explicit courier.compensation policy, resolving one falls
        // through to Optional.empty() (CourierPolicyResolver's documented
        // fallback to ADR 0042's provisional defaults) — but Spring's
        // @Cacheable on JdbcPolicyResolver.resolve unwraps that empty Optional
        // to a bare null before CourierPolicyResolver ever sees it, and the
        // tenant.policy_current cache is configured to refuse null values, so
        // every brand-new tenant's first policy-gated call 500s (surfaces here
        // as 400) until an admin authors *some* policy. That is a real,
        // pre-existing defect outside this wave's scope (JdbcPolicyResolver /
        // CacheRegistry, not the courier module) — flagged in the wave report
        // rather than fixed here. Seeding one sidesteps it for this test.
        seedCourierCompensationPolicy(TENANT);

        MvcResult written = mvc.perform(post(couriersPath(TENANT) + "/" + COURIER + "/adjustments")
                        .with(tokenFor(ADJUSTER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "adjust-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locationId":"%s","amountMinor":5000,"currency":"UZS",
                                 "reasonCode":"GOODWILL_BONUS","origin":"RULE",
                                 "idempotencyKey":"adjust-goodwill-1","reason":"a one-off goodwill bonus"}
                                """.formatted(LOCATION)))
                .andReturn();

        assertThat(written.getResponse().getStatus()).isEqualTo(200);
        assertThat(written.getResponse().getContentAsString())
                .as("a bonus needs no approval, so this is written immediately")
                .contains("\"written\":true");

        String storedOrigin = jdbc.sql("""
                SELECT origin FROM fulfillment.courier_ledger_entries
                 WHERE tenant_id = :tenantId AND courier_id = :courierId AND reason_code = 'GOODWILL_BONUS'
                """)
                .param("tenantId", TENANT)
                .param("courierId", COURIER)
                .query(String.class)
                .single();
        assertThat(storedOrigin)
                .as("origin=RULE in the request body never reaches the ledger")
                .isEqualTo("MANUAL");
    }

    @Test
    void aCallerWithoutAdjustmentCreateCannotRecordABonus() throws Exception {
        MvcResult refused = mvc.perform(post(couriersPath(TENANT) + "/" + COURIER + "/adjustments")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "adjust-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"locationId":"%s","amountMinor":5000,"currency":"UZS",
                                 "reasonCode":"GOODWILL_BONUS",
                                 "idempotencyKey":"adjust-goodwill-2","reason":"a one-off goodwill bonus"}
                                """.formatted(LOCATION)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.COURIER_ADJUSTMENT_CREATE.code());
    }

    // ------------------------------------------------- courier types & reasons (T16 audit)

    /**
     * The T16 adversarial-review finding: {@code updateType} took {@code
     * body.reason()} on its {@code @NotBlank} request but never read it, and
     * wrote no audit fact at all — a dispatch-ceiling change left no record of
     * who changed it or why. This proves both are now true: the correction
     * lands, and an {@code audit.audit_events} row names the actor, the
     * reason, and the changed fields.
     */
    @Test
    void correctingACourierTypeIsAudited() throws Exception {
        MvcResult corrected = mvc.perform(put("/api/v1/operations/tenants/" + TENANT + "/courier-types/" + COURIER_TYPE)
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "type-correct-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"SCOOTER","displayName":"Scooter (corrected)","vehicleClass":"SCOOTER",
                                 "minDistanceMeters":0,"maxDistanceMeters":8000,"maxConcurrentAssignments":2,
                                 "offerTtlSeconds":90,"startingMinuteOffset":0,"workMode":"SHIFT",
                                 "expectedVersion":1,"reason":"widening the offer window after driver feedback"}
                                """))
                .andReturn();
        assertThat(corrected.getResponse().getStatus()).isEqualTo(200);
        assertThat(corrected.getResponse().getContentAsString()).contains("Scooter (corrected)");

        Map<String, String> event = jdbc.sql("""
                        SELECT action_code, reason, target_id, change_document::text AS change_document
                          FROM audit.audit_events
                         WHERE tenant_id = :tenantId AND action_code = 'courier-type.updated'
                        """)
                .param("tenantId", TENANT)
                .query((row, n) -> Map.of(
                        "actionCode", row.getString("action_code"),
                        "reason", row.getString("reason"),
                        "targetId", row.getString("target_id"),
                        "changeDocument", row.getString("change_document")))
                .single();
        assertThat(event.get("reason")).isEqualTo("widening the offer window after driver feedback");
        assertThat(event.get("targetId")).isEqualTo(COURIER_TYPE.toString());
        // Postgres's own jsonb-to-text cast, not Jackson's compact form -- it
        // inserts a space after the colon (see OwnerInvitationControllerEndpointTests'
        // "\"revealedCount\": 2" for the same convention read from this column
        // elsewhere in the suite).
        assertThat(event.get("changeDocument")).contains("\"maxConcurrentAssignments\": 2");
    }

    @Test
    void archivingACourierTypeIsAudited() throws Exception {
        MvcResult archived = mvc.perform(
                        post("/api/v1/operations/tenants/" + TENANT + "/courier-types/" + COURIER_TYPE + "/archival")
                                .with(tokenFor(OWNER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "type-archive-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                        {"reason":"this vehicle class is being retired fleet-wide"}
                                        """))
                .andReturn();
        assertThat(archived.getResponse().getStatus()).isEqualTo(202);

        String reason = jdbc.sql("""
                        SELECT reason FROM audit.audit_events
                         WHERE tenant_id = :tenantId AND action_code = 'courier-type.archived' AND target_id = :typeId
                        """)
                .param("tenantId", TENANT)
                .param("typeId", COURIER_TYPE)
                .query(String.class)
                .single();
        assertThat(reason).isEqualTo("this vehicle class is being retired fleet-wide");
    }

    @Test
    void archivingAnAdjustmentReasonIsAudited() throws Exception {
        UUID reasonId = jdbc.sql(
                        "SELECT id FROM fulfillment.courier_adjustment_reasons WHERE tenant_id = :tenantId AND code = 'GOODWILL_BONUS'")
                .param("tenantId", TENANT)
                .query(UUID.class)
                .single();

        MvcResult archived = mvc.perform(
                        post("/api/v1/operations/tenants/" + TENANT + "/adjustment-reasons/" + reasonId + "/archival")
                                .with(tokenFor(OWNER))
                                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "reason-archive-1")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("""
                                {"reason":"superseded by a tighter goodwill policy"}
                                """))
                .andReturn();
        assertThat(archived.getResponse().getStatus()).isEqualTo(202);

        Map<String, String> event = jdbc.sql("""
                        SELECT reason, change_document::text AS change_document FROM audit.audit_events
                         WHERE tenant_id = :tenantId AND action_code = 'adjustment-reason.archived' AND target_id = :reasonId
                        """)
                .param("tenantId", TENANT)
                .param("reasonId", reasonId)
                .query((row, n) ->
                        Map.of("reason", row.getString("reason"), "changeDocument", row.getString("change_document")))
                .single();
        assertThat(event.get("reason")).isEqualTo("superseded by a tighter goodwill policy");
        assertThat(event.get("changeDocument"))
                .as("archival silently stops rule evaluation for a rule-wired reason; the audit trail says so")
                .contains("rule");
    }

    // --------------------------------------------------------------------- groups

    @Test
    void anAuthorizedManagerCreatesAndReadsAGroup() throws Exception {
        UUID groupId = createGroup(ADMIN, "NIGHT", "Ночная смена");

        MvcResult listed =
                mvc.perform(get(groupsPath(TENANT)).with(tokenFor(OWNER))).andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        assertThat(listed.getResponse().getContentAsString())
                .contains(groupId.toString())
                .contains("NIGHT");
    }

    @Test
    void aCallerWithoutCourierReadCannotListGroups() throws Exception {
        MvcResult refused = mvc.perform(get(groupsPath(TENANT)).with(tokenFor(BRAND_MANAGER)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.COURIER_READ.code());
    }

    @Test
    void aCallerWithoutEngagementManageCannotCreateAGroup() throws Exception {
        MvcResult refused = mvc.perform(post(groupsPath(TENANT))
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "group-refused")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"NIGHT","displayName":"Ночная смена","reason":"planning"}
                                """))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.courier_groups")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void aCallerWithoutEngagementManageCannotArchiveAGroup() throws Exception {
        UUID groupId = createGroup(ADMIN, "NIGHT", "Ночная смена");

        MvcResult refused = mvc.perform(post(groupsPath(TENANT) + "/" + groupId + "/archival")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "archive-refused")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"the shift is retired"}
                                """))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT status FROM fulfillment.courier_groups WHERE id = :id")
                        .param("id", groupId)
                        .query(String.class)
                        .single())
                .as("a refused archival must leave the group ACTIVE")
                .isEqualTo("ACTIVE");
    }

    @Test
    void aCallerWithoutEngagementManageCannotJoinACourierToAGroup() throws Exception {
        UUID groupId = createGroup(ADMIN, "NIGHT", "Ночная смена");

        MvcResult refused = mvc.perform(post(couriersPath(TENANT) + "/" + COURIER + "/groups")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "join-refused")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + groupId + "\",\"reason\":\"he asked for nights\"}"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.courier_group_members WHERE courier_id = :courierId")
                        .param("courierId", COURIER)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void aCallerWithoutEngagementManageCannotRemoveACourierFromAGroup() throws Exception {
        UUID groupId = createGroup(ADMIN, "NIGHT", "Ночная смена");
        mvc.perform(post(couriersPath(TENANT) + "/" + COURIER + "/groups")
                .with(tokenFor(ADMIN))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "join-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"groupId\":\"" + groupId + "\",\"reason\":\"he asked for nights\"}"));

        MvcResult refused = mvc.perform(post(couriersPath(TENANT) + "/" + COURIER + "/groups/" + groupId + "/removal")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "leave-refused")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"he moved to days"}
                                """))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.courier_group_members WHERE courier_id = :courierId "
                                + "AND group_id = :groupId")
                        .param("courierId", COURIER)
                        .param("groupId", groupId)
                        .query(Long.class)
                        .single())
                .as("a refused removal must leave the membership in place")
                .isEqualTo(1);
    }

    @Test
    void aCallerWithoutEngagementManageCannotBindABranch() throws Exception {
        MvcResult refused = mvc.perform(post(couriersPath(TENANT) + "/" + COURIER + "/branch-bindings")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "bind-refused")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"brandId":"%s","locationId":"%s","primary":true,"reason":"his home branch"}
                                """.formatted(BRAND, LOCATION)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.courier_branch_bindings WHERE courier_id = :courierId")
                        .param("courierId", COURIER)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void aManagerJoinsLeavesAndArchivesAGroup() throws Exception {
        UUID groupId = createGroup(ADMIN, "NIGHT", "Ночная смена");

        MvcResult joined = mvc.perform(post(couriersPath(TENANT) + "/" + COURIER + "/groups")
                        .with(tokenFor(ADMIN))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "join-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + groupId + "\",\"reason\":\"he asked for nights\"}"))
                .andReturn();
        assertThat(joined.getResponse().getStatus()).isEqualTo(202);

        MvcResult detail = mvc.perform(get(couriersPath(TENANT) + "/" + COURIER).with(tokenFor(OWNER)))
                .andReturn();
        assertThat(detail.getResponse().getContentAsString()).contains(groupId.toString());

        MvcResult left = mvc.perform(post(couriersPath(TENANT) + "/" + COURIER + "/groups/" + groupId + "/removal")
                        .with(tokenFor(ADMIN))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "leave-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"he moved to days"}
                                """))
                .andReturn();
        assertThat(left.getResponse().getStatus()).isEqualTo(202);

        MvcResult archived = mvc.perform(post(groupsPath(TENANT) + "/" + groupId + "/archival")
                        .with(tokenFor(ADMIN))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "archive-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"the shift is retired"}
                                """))
                .andReturn();
        assertThat(archived.getResponse().getStatus()).isEqualTo(202);
    }

    // ----------------------------------------------------------- branch bindings

    /**
     * The P19 review's headline finding: {@code unbindCourierFromBranch} was
     * declared with a {@code LOCATION} scope the route has no {@code brandId}
     * to resolve, so {@code CapabilityEnforcementInterceptor} threw an
     * unmapped 500 on every real call. This test would have failed red
     * against that declaration — it now proves the endpoint an authorized
     * manager reaches actually answers 202, not 500.
     */
    @Test
    void anAuthorizedManagerBindsAndUnbindsABranch() throws Exception {
        MvcResult bound = mvc.perform(post(couriersPath(TENANT) + "/" + COURIER + "/branch-bindings")
                        .with(tokenFor(ADMIN))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "bind-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"brandId":"%s","locationId":"%s","primary":true,"reason":"his home branch"}
                                """.formatted(BRAND, LOCATION)))
                .andReturn();
        assertThat(bound.getResponse().getStatus()).isEqualTo(202);

        MvcResult detail = mvc.perform(get(couriersPath(TENANT) + "/" + COURIER).with(tokenFor(OWNER)))
                .andReturn();
        assertThat(detail.getResponse().getContentAsString()).contains(LOCATION.toString());

        MvcResult unbound = mvc.perform(post(couriersPath(TENANT) + "/" + COURIER + "/branch-bindings/" + BRAND + "/"
                                + LOCATION + "/removal")
                        .with(tokenFor(ADMIN))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "unbind-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"he never rides there"}
                                """))
                .andReturn();
        assertThat(unbound.getResponse().getStatus())
                .as("this is the exact call that 500'd before the LOCATION-scope fix")
                .isEqualTo(202);

        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.courier_branch_bindings WHERE courier_id = :courierId")
                        .param("courierId", COURIER)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void aCallerWithoutEngagementManageCannotUnbindABranch() throws Exception {
        mvc.perform(post(couriersPath(TENANT) + "/" + COURIER + "/branch-bindings")
                .with(tokenFor(ADMIN))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "bind-2")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"brandId":"%s","locationId":"%s","primary":true,"reason":"his home branch"}
                        """.formatted(BRAND, LOCATION)));

        MvcResult refused = mvc.perform(post(couriersPath(TENANT) + "/" + COURIER + "/branch-bindings/" + BRAND + "/"
                                + LOCATION + "/removal")
                        .with(tokenFor(OWNER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "unbind-refused")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"he never rides there"}
                                """))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.courier_branch_bindings WHERE courier_id = :courierId")
                        .param("courierId", COURIER)
                        .query(Long.class)
                        .single())
                .as("a refused unbind must leave the binding exactly as it was")
                .isEqualTo(1);
    }

    @Test
    void aCourierCannotBeBoundToAnotherTenantsBranchThroughTheController() throws Exception {
        // ADMIN's grant covers TENANT, which is what authorises the call at
        // all; the refusal has to come from fk_courier_binding_location
        // (V0221), not from the capability check, because OTHER_BRAND and
        // OTHER_LOCATION are real rows -- just not TENANT's.
        MvcResult refused = mvc.perform(post(couriersPath(TENANT) + "/" + COURIER + "/branch-bindings")
                        .with(tokenFor(ADMIN))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cross-tenant-bind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"brandId":"%s","locationId":"%s","primary":true,"reason":"cross-tenant binding attempt"}
                                """.formatted(OTHER_BRAND, OTHER_LOCATION)))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("no (tenant_id, brand_id, location_id) row exists for TENANT + OTHER_BRAND + OTHER_LOCATION")
                .isEqualTo(409);
        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.courier_branch_bindings WHERE courier_id = :courierId")
                        .param("courierId", COURIER)
                        .query(Long.class)
                        .single())
                .isZero();
    }

    // --------------------------------------------------------- roster entries (T17)

    /**
     * The adversarial review's critical finding: the five roster endpoints
     * declared no {@code scope}, so {@code RequiresCapability}'s TENANT default
     * applied — but {@code COURIER_SHIFT_READ}/{@code COURIER_SHIFT_APPROVE}
     * are granted only to {@link PlatformRole#LOCATION_MANAGER} (LOCATION) and
     * {@link PlatformRole#COURIER_DISPATCHER} (BRAND) in {@code
     * PlatformRole.java}; no TENANT-scoped bundle holds either capability. A
     * real location manager's grant, at LOCATION, can never satisfy a TENANT
     * requirement ({@code ResourceScope.covers} only lets a broader scope
     * satisfy a narrower one), so every real caller of this grid was refused
     * with 403 in production. This test uses the actual {@code
     * LOCATION_MANAGER} bundle at a real LOCATION-scoped grant — not the
     * TENANT-scoped custom-role workaround {@link #ADJUSTER_ROLE} needed for a
     * different endpoint — and would fail red against the pre-fix TENANT
     * default.
     */
    @Test
    void aLocationManagerCanReadPlanPublishAndCancelTheRoster() throws Exception {
        grantAtLocation(LOCATION_MANAGER_SUBJECT, PlatformRole.LOCATION_MANAGER, TENANT, LOCATION);

        MvcResult listedBefore = mvc.perform(get(rosterPath(TENANT))
                        .with(tokenFor(LOCATION_MANAGER_SUBJECT))
                        .param("brandId", BRAND.toString())
                        .param("locationId", LOCATION.toString()))
                .andReturn();
        assertThat(listedBefore.getResponse().getStatus()).isEqualTo(200);
        assertThat(listedBefore.getResponse().getContentAsString()).isEqualTo("[]");

        Instant start = Instant.now().plus(Duration.ofDays(1));
        Instant end = start.plus(Duration.ofHours(8));
        MvcResult drafted = mvc.perform(post(rosterPath(TENANT))
                        .with(tokenFor(LOCATION_MANAGER_SUBJECT))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "roster-draft-1")
                        .param("brandId", BRAND.toString())
                        .param("locationId", LOCATION.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courierId":"%s","plannedStart":"%s","plannedEnd":"%s","reason":"next week's cover"}
                                """.formatted(COURIER, start, end)))
                .andReturn();
        assertThat(drafted.getResponse().getStatus())
                .as("a real LOCATION_MANAGER grant, not a 403, is the whole point of this test")
                .isEqualTo(200);

        UUID entryId = jdbc.sql("""
                        SELECT id FROM fulfillment.courier_roster_entries
                         WHERE tenant_id = :tenantId AND courier_id = :courierId AND status = 'DRAFT'
                        """)
                .param("tenantId", TENANT)
                .param("courierId", COURIER)
                .query(UUID.class)
                .single();

        MvcResult published = mvc.perform(post(rosterPath(TENANT) + "/" + entryId + "/publish")
                        .with(tokenFor(LOCATION_MANAGER_SUBJECT))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "roster-publish-1")
                        .param("brandId", BRAND.toString())
                        .param("locationId", LOCATION.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"publishing next week's cover"}
                                """))
                .andReturn();
        assertThat(published.getResponse().getStatus()).isEqualTo(202);

        MvcResult compared = mvc.perform(get(rosterPath(TENANT) + "/comparison")
                        .with(tokenFor(LOCATION_MANAGER_SUBJECT))
                        .param("brandId", BRAND.toString())
                        .param("locationId", LOCATION.toString())
                        .param("from", start.minus(Duration.ofDays(1)).toString())
                        .param("to", end.plus(Duration.ofDays(1)).toString()))
                .andReturn();
        assertThat(compared.getResponse().getStatus()).isEqualTo(200);
        assertThat(compared.getResponse().getContentAsString()).contains(entryId.toString());

        MvcResult cancelled = mvc.perform(post(rosterPath(TENANT) + "/" + entryId + "/cancel")
                        .with(tokenFor(LOCATION_MANAGER_SUBJECT))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "roster-cancel-1")
                        .param("brandId", BRAND.toString())
                        .param("locationId", LOCATION.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"cover no longer needed"}
                                """))
                .andReturn();
        assertThat(cancelled.getResponse().getStatus()).isEqualTo(202);

        assertThat(jdbc.sql("SELECT status FROM fulfillment.courier_roster_entries WHERE id = :id")
                        .param("id", entryId)
                        .query(String.class)
                        .single())
                .isEqualTo("CANCELLED");
    }

    /** {@link PlatformRole#COURIER_DISPATCHER} holds the same two capabilities at BRAND, one level broader. */
    @Test
    void aBrandScopedDispatcherCanAlsoReadAndPlanTheRoster() throws Exception {
        grantAtBrand(BRAND_DISPATCHER, PlatformRole.COURIER_DISPATCHER, TENANT, BRAND);

        MvcResult listed = mvc.perform(get(rosterPath(TENANT))
                        .with(tokenFor(BRAND_DISPATCHER))
                        .param("brandId", BRAND.toString())
                        .param("locationId", LOCATION.toString()))
                .andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);

        MvcResult drafted = mvc.perform(post(rosterPath(TENANT))
                        .with(tokenFor(BRAND_DISPATCHER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "roster-draft-2")
                        .param("brandId", BRAND.toString())
                        .param("locationId", LOCATION.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courierId":"%s","plannedStart":"%s","plannedEnd":"%s","reason":"dispatcher-planned cover"}
                                """.formatted(
                                        COURIER,
                                        Instant.now().plus(Duration.ofDays(2)),
                                        Instant.now().plus(Duration.ofDays(2)).plus(Duration.ofHours(6)))))
                .andReturn();
        assertThat(drafted.getResponse().getStatus()).isEqualTo(200);
    }

    /**
     * The mirror image of the two tests above: a LOCATION_MANAGER grant at
     * {@link #SECOND_LOCATION} must not reach {@link #LOCATION}'s roster —
     * proving the fix narrowed the check to LOCATION rather than accidentally
     * widening it back to "anyone with this capability anywhere".
     */
    @Test
    void aManagerOfADifferentLocationCannotReadThisLocationsRoster() throws Exception {
        grantAtLocation(LOCATION_MANAGER_SUBJECT, PlatformRole.LOCATION_MANAGER, TENANT, SECOND_LOCATION);

        MvcResult refused = mvc.perform(get(rosterPath(TENANT))
                        .with(tokenFor(LOCATION_MANAGER_SUBJECT))
                        .param("brandId", BRAND.toString())
                        .param("locationId", LOCATION.toString()))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.COURIER_SHIFT_READ.code());
    }

    /**
     * The defence-in-depth half of the fix: a LOCATION_MANAGER's capability
     * grant is checked against the branch named in the request, not against
     * the entry named in the path — so {@code PlannedShiftService} re-asserts
     * the entry it found actually belongs to that branch. Without that
     * re-check, a manager at {@link #LOCATION} naming their own branch could
     * publish or cancel an entry that in fact lives at {@link
     * #SECOND_LOCATION} merely by knowing its id.
     */
    @Test
    void aLocationManagerCannotPublishAnotherLocationsRosterEntry() throws Exception {
        grantAtLocation(LOCATION_MANAGER_SUBJECT, PlatformRole.LOCATION_MANAGER, TENANT, LOCATION);
        grantAtLocation(ADMIN, PlatformRole.LOCATION_MANAGER, TENANT, SECOND_LOCATION);

        // Drafted by a manager who actually holds SECOND_LOCATION.
        MvcResult drafted = mvc.perform(post(rosterPath(TENANT))
                        .with(tokenFor(ADMIN))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "roster-draft-3")
                        .param("brandId", BRAND.toString())
                        .param("locationId", SECOND_LOCATION.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"courierId":"%s","plannedStart":"%s","plannedEnd":"%s","reason":"north branch cover"}
                                """.formatted(
                                        COURIER,
                                        Instant.now().plus(Duration.ofDays(3)),
                                        Instant.now().plus(Duration.ofDays(3)).plus(Duration.ofHours(5)))))
                .andReturn();
        assertThat(drafted.getResponse().getStatus()).isEqualTo(200);

        UUID entryId = jdbc.sql("""
                        SELECT id FROM fulfillment.courier_roster_entries
                         WHERE tenant_id = :tenantId AND location_id = :locationId AND status = 'DRAFT'
                        """)
                .param("tenantId", TENANT)
                .param("locationId", SECOND_LOCATION)
                .query(UUID.class)
                .single();

        MvcResult refused = mvc.perform(post(rosterPath(TENANT) + "/" + entryId + "/publish")
                        .with(tokenFor(LOCATION_MANAGER_SUBJECT))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "roster-publish-cross")
                        .param("brandId", BRAND.toString())
                        .param("locationId", LOCATION.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"trying to publish someone else's branch"}
                                """))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("the entry belongs to SECOND_LOCATION, not the LOCATION named in the request")
                .isEqualTo(404);
        assertThat(jdbc.sql("SELECT status FROM fulfillment.courier_roster_entries WHERE id = :id")
                        .param("id", entryId)
                        .query(String.class)
                        .single())
                .as("the cross-location attempt must not have taken effect")
                .isEqualTo("DRAFT");
    }

    // ------------------------------------------------------------- cross-tenant

    @Test
    void aGrantScopedToOneTenantCannotReachAnotherTenantsCourierGroupsOrBindings() throws Exception {
        UUID groupId = createGroup(ADMIN, "NIGHT", "Ночная смена");
        mvc.perform(post(couriersPath(TENANT) + "/" + COURIER + "/branch-bindings")
                .with(tokenFor(ADMIN))
                .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "bind-3")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"brandId":"%s","locationId":"%s","primary":true,"reason":"his home branch"}
                        """.formatted(BRAND, LOCATION)));

        // OTHER_TENANT_ADMIN's grant covers only OTHER_TENANT. Pointed at
        // TENANT's own real courier and group, the capability check itself
        // must refuse -- the grant simply does not cover this resource.
        MvcResult readRefused = mvc.perform(
                        get(couriersPath(TENANT) + "/" + COURIER).with(tokenFor(OTHER_TENANT_ADMIN)))
                .andReturn();
        assertThat(readRefused.getResponse().getStatus())
                .as("a tenant's own admin grant must not reach a different tenant's real courier")
                .isEqualTo(403);

        MvcResult groupsRefused = mvc.perform(get(groupsPath(TENANT)).with(tokenFor(OTHER_TENANT_ADMIN)))
                .andReturn();
        assertThat(groupsRefused.getResponse().getStatus()).isEqualTo(403);

        MvcResult joinRefused = mvc.perform(post(couriersPath(TENANT) + "/" + COURIER + "/groups")
                        .with(tokenFor(OTHER_TENANT_ADMIN))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cross-tenant-join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"groupId\":\"" + groupId + "\",\"reason\":\"poaching\"}"))
                .andReturn();
        assertThat(joinRefused.getResponse().getStatus()).isEqualTo(403);

        MvcResult unbindRefused = mvc.perform(post(couriersPath(TENANT) + "/" + COURIER + "/branch-bindings/" + BRAND
                                + "/" + LOCATION + "/removal")
                        .with(tokenFor(OTHER_TENANT_ADMIN))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "cross-tenant-unbind")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason":"not yours to release"}
                                """))
                .andReturn();
        assertThat(unbindRefused.getResponse().getStatus()).isEqualTo(403);

        assertThat(jdbc.sql("SELECT count(*) FROM fulfillment.courier_branch_bindings WHERE courier_id = :courierId")
                        .param("courierId", COURIER)
                        .query(Long.class)
                        .single())
                .as("every refused cross-tenant call above must have left the real binding untouched")
                .isEqualTo(1);
    }

    // ------------------------------------------------------------------ fixtures

    private UUID createGroup(String subject, String code, String displayName) throws Exception {
        MvcResult created = mvc.perform(post(groupsPath(TENANT))
                        .with(tokenFor(subject))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, "create-group-" + code)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\",\"displayName\":\"" + displayName
                                + "\",\"reason\":\"planning\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isEqualTo(200);

        return jdbc.sql("SELECT id FROM fulfillment.courier_groups WHERE tenant_id = :tenantId AND code = :code")
                .param("tenantId", TENANT)
                .param("code", code)
                .query(UUID.class)
                .single();
    }

    private void insertCourier(UUID courierId, UUID tenantId, UUID courierTypeId, String reference) {
        jdbc.sql("""
                INSERT INTO fulfillment.couriers
                    (id, tenant_id, courier_type_id, principal_subject, display_reference, protected_full_name, status)
                VALUES (:id, :tenantId, :typeId, :subject, :reference, 'ciphertext-not-exercised-here', 'ACTIVE')
                """)
                .param("id", courierId)
                .param("tenantId", tenantId)
                .param("typeId", courierTypeId)
                .param("subject", "keycloak-" + reference)
                .param("reference", reference)
                .update();
    }

    /** A minimal ACTIVE engagement — every column {@code ck_engagement_active_is_verified} requires, and nothing more. */
    private void seedActiveEngagement(UUID courierId, UUID tenantId) {
        Instant now = Instant.now();
        jdbc.sql("""
                INSERT INTO fulfillment.courier_engagements (
                    id, tenant_id, courier_id, engagement_type, status, engaged_from,
                    protected_registration_ref, registration_valid_until,
                    registration_verified_at, registration_verified_by, verification_method,
                    reverification_due_on, warning_state, version, created_at, updated_at)
                VALUES (:id, :tenantId, :courierId, 'SELF_EMPLOYED', 'ACTIVE', :engagedFrom,
                    'ciphertext-not-exercised-here', :validUntil,
                    :verifiedAt, 'test-fixture', 'MANUAL_ATTESTATION',
                    :reverificationDue, 'VALID', 1, :now, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("courierId", courierId)
                .param("engagedFrom", now.atOffset(ZoneOffset.UTC).toLocalDate().minusMonths(1))
                .param("validUntil", now.atOffset(ZoneOffset.UTC).toLocalDate().plusYears(1))
                .param("verifiedAt", now.atOffset(ZoneOffset.UTC))
                .param(
                        "reverificationDue",
                        now.atOffset(ZoneOffset.UTC).toLocalDate().plusMonths(6))
                .param("now", now.atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * A TENANT-scope {@code courier.compensation} policy, ACTIVE and current.
     *
     * <p>Without this, {@code JdbcPolicyResolver.resolve} answers {@code
     * Optional.empty()} for a tenant that has never had one authored — exactly
     * ADR 0042's documented fallback case — but Spring's {@code @Cacheable}
     * unwraps that empty {@code Optional} to a bare {@code null} before {@code
     * CourierPolicyResolver}'s own {@code .orElseGet(...)} ever runs, and the
     * {@code tenant.policy_current} cache refuses null values. See the one
     * test that calls this for what that looks like from the outside.
     */
    private void seedCourierCompensationPolicy(UUID tenantId) {
        UUID policyId = UUID.randomUUID();
        String document = """
                {"reverificationDays":180,"warningDays":30,"settlementPeriodDays":14,
                 "cashCeilingMinor":5000000,"penaltyApprovalThresholdMinor":200000,
                 "shiftEnforcement":"ADVISORY","graceSeconds":300,"confirmationPointRetentionDays":30}
                """.replaceAll("\\s+", " ").trim();
        jdbc.sql("""
                INSERT INTO tenant.policies (
                    id, key_code, scope_type, tenant_id, version, status, document, document_hash,
                    valid_from, created_by)
                VALUES (:id, 'courier.compensation', 'TENANT', :tenantId, 1, 'ACTIVE', :document::jsonb,
                    repeat('a', 64), now(), 'test-fixture')
                """)
                .param("id", policyId)
                .param("tenantId", tenantId)
                .param("document", document)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.policy_current (
                    key_code, scope_type, tenant_id, policy_id, policy_version, activated_by)
                VALUES ('courier.compensation', 'TENANT', :tenantId, :policyId, 1, 'test-fixture')
                """).param("tenantId", tenantId).param("policyId", policyId).update();
    }

    /** A manual-only reason (every {@code rule_*} column left null). */
    private void seedAdjustmentReason(UUID tenantId, String code, String kind, String outcomeBasis) {
        jdbc.sql("""
                INSERT INTO fulfillment.courier_adjustment_reasons (
                    id, tenant_id, code, kind, outcome_basis, display_name, status, rule_version, created_at)
                VALUES (:id, :tenantId, :code, :kind, :basis, :code, 'ACTIVE', 1, now())
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("code", code)
                .param("kind", kind)
                .param("basis", outcomeBasis)
                .update();
    }

    private void seedTenancy(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            @org.jspecify.annotations.Nullable UUID secondLocationId,
            UUID courierTypeId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "courier-endpoint-" + tenantId)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", brandId).param("tenantId", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();
        if (secondLocationId != null) {
            jdbc.sql("""
                    INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                        timezone, status, version)
                    VALUES (:id, :tenantId, :brandId, 'NORTH', 'north', 'North', 'Asia/Tashkent', 'ACTIVE', 0)
                    """)
                    .param("id", secondLocationId)
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .update();
        }
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name, vehicle_class)
                VALUES (:id, :tenantId, 'SCOOTER', 'Scooter', 'SCOOTER')
                """).param("id", courierTypeId).param("tenantId", tenantId).update();
    }

    private void grant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'courier endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + tenantId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    /** A real LOCATION-scoped grant of a platform-defined role — LOCATION_MANAGER's own shape (T17). */
    private void grantAtLocation(String subject, PlatformRole role, UUID tenantId, UUID locationId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :locationId,
                        'ACTIVE', 'test-fixture', 'courier roster endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + locationId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("locationId", locationId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    /** A real BRAND-scoped grant of a platform-defined role — COURIER_DISPATCHER's own shape (T17). */
    private void grantAtBrand(String subject, PlatformRole role, UUID tenantId, UUID brandId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'BRAND', :brandId,
                        'ACTIVE', 'test-fixture', 'courier roster endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + brandId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("brandId", brandId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void grantCustomRole(String subject, UUID roleId, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, false, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'courier pii reveal, granted per person', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + roleId + tenantId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", roleId)
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
