package uz.horecaos.platform.courier.web;

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

    // ADR 0042: courier.pii.reveal is granted per person, not through any
    // PlatformRole bundle (see COURIER_PII_REVEAL's own doc) — so REVEALER's
    // standing comes from a hand-authored tenant-scoped custom role rather
    // than from RoleRegistrySynchronizer, which only ever touches the
    // platform-defined roles named in PlatformRole.
    private static final UUID REVEALER_ROLE = UUID.fromString("018f9c20-5000-7000-8000-0000000000a1");

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

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
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
