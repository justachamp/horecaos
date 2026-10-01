package uz.horecaos.platform.iam.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.staff.StaffPhotos;
import uz.horecaos.platform.iam.application.staff.StaffMemberService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0139 over the real HTTP stack: who reaches which route, by the scope of the
 * grant they hold.
 *
 * <p>The exit criteria are the spine. A branch manager calls the branch's members
 * route and sees the names, masked phones and status of the people of that branch
 * and no one else; the same call for another branch, and the tenant-wide route, are
 * refused. A line cook holding only a location grant edits their own name and phone.
 * And an edit by that cook is on the activity log at the next request, under the new
 * name.
 *
 * <p>The photo pipeline is replaced by a stub because it needs an object store this
 * suite does not run; what is held here is the endpoint's contract with the port.
 */
@SpringBootTest
@AutoConfigureMockMvc
class StaffMemberEndpointTests {

    private static final UUID TENANT = UUID.fromString("018fb100-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb100-4000-7000-8000-0000000000b1");
    private static final UUID LOC_1 = UUID.fromString("018fb100-4000-7000-8000-0000000000c1");
    private static final UUID LOC_2 = UUID.fromString("018fb100-4000-7000-8000-0000000000c2");

    private static final UUID OTHER_TENANT = UUID.fromString("018fb100-4000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018fb100-4000-7000-8000-0000000000b2");
    private static final UUID OTHER_LOC = UUID.fromString("018fb100-4000-7000-8000-0000000000c3");

    private static final String OWNER = "http-owner";
    private static final String ADMIN = "http-admin";
    private static final String BRAND_MANAGER = "http-brand-manager";
    private static final String L1_MANAGER = "http-l1-manager";
    private static final String L2_MANAGER = "http-l2-manager";
    private static final String FINANCE = "http-finance";
    private static final String COOK_1 = "http-cook-1";
    private static final String COOK_2 = "http-cook-2";
    private static final String COOK_3 = "http-cook-3";
    private static final String BOTH = "http-both-branches";
    private static final String IDLE = "http-idle";
    private static final String NO_GRANT = "http-no-grant";
    private static final String OTHER_OWNER = "http-other-owner";

    private static final String COOK_1_NAME = "Shahlo Tursunova";
    private static final String COOK_1_PHONE = "+998 90 888 11 22";

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
        // The staff member record seals names and phones under ADR 0029; a context
        // that serves it needs the platform key-encryption key, as every other
        // suite that reaches FieldProtection supplies.
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

    @MockitoBean
    @SuppressWarnings("NullAway")
    private StaffPhotos photos;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        seedTenancy(TENANT, BRAND, LOC_1, "http-staff-a");
        seedLocation(TENANT, BRAND, LOC_2, "L2");
        seedTenancy(OTHER_TENANT, OTHER_BRAND, OTHER_LOC, "http-staff-b");

        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);
        grant(ADMIN, PlatformRole.TENANT_ADMIN, "TENANT", TENANT, TENANT);
        grant(BRAND_MANAGER, PlatformRole.BRAND_MANAGER, "BRAND", BRAND, TENANT);
        grant(L1_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", LOC_1, TENANT);
        grant(L2_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", LOC_2, TENANT);
        grant(FINANCE, PlatformRole.TENANT_FINANCE, "TENANT", TENANT, TENANT);
        grant(COOK_1, PlatformRole.LOCATION_STAFF, "LOCATION", LOC_1, TENANT);
        grant(COOK_2, PlatformRole.LOCATION_STAFF, "LOCATION", LOC_1, TENANT);
        grant(COOK_3, PlatformRole.LOCATION_STAFF, "LOCATION", LOC_2, TENANT);
        grant(BOTH, PlatformRole.LOCATION_STAFF, "LOCATION", LOC_1, TENANT);
        grant(BOTH, PlatformRole.LOCATION_STAFF, "LOCATION", LOC_2, TENANT);
        grant(OTHER_OWNER, PlatformRole.TENANT_OWNER, "TENANT", OTHER_TENANT, OTHER_TENANT);

        member(TENANT, COOK_1, "Shahlo", "Tursunova", COOK_1_PHONE);
        member(TENANT, COOK_2, "Rustam", "Qodirov", "+998 90 222 33 44");
        member(TENANT, L1_MANAGER, "Madina", "Yusupova", "+998 90 555 66 77");
        member(TENANT, COOK_3, "Sardor", "Aliyev", "+998 90 123 12 34");
        member(TENANT, BOTH, "Jasur", "Nazarov", null);
        member(TENANT, IDLE, "Ilhom", "Idle", null);
        member(TENANT, FINANCE, "Feruza", "Moliya", "+998 90 700 80 90");
        member(OTHER_TENANT, OTHER_OWNER, "Otabek", "Other", null);
    }

    // ================================================================ the exit criteria

    @Test
    @DisplayName(
            "a branch manager sees the names, masked phones and status of their own branch's people, and no one else")
    void aBranchManagerReadsTheirOwnBranch() throws Exception {
        MvcResult list = get(branchMembers(LOC_1), L1_MANAGER);

        assertThat(list.getResponse().getStatus()).isEqualTo(200);
        String body = list.getResponse().getContentAsString(UTF_8);
        assertThat(body)
                .contains("Tursunova", "Qodirov", "Yusupova", "Nazarov")
                .as("masked in a list: the middle of the number never leaves")
                .contains("+998 90 ••• •• 22")
                .doesNotContain("888 11", "8881122", "998908881122");
        assertThat(body)
                .as(
                        "people of the sibling branch, the tenant's other roles and members with no job are not this branch's")
                .doesNotContain("Aliyev", "Idle", "Moliya", "Other");
        assertThat(body).contains("\"employmentStatus\":\"ACTIVE\"");
    }

    @Test
    @DisplayName("the same call for another branch, and the tenant-wide route, are refused to a branch manager")
    void aBranchManagerIsRefusedEverywhereElse() throws Exception {
        assertCapabilityRefusal(get(branchMembers(LOC_2), L1_MANAGER), "staff.profile.read");
        assertCapabilityRefusal(get(tenantMembers(), L1_MANAGER), "staff.profile.read");
        assertCapabilityRefusal(get(brandMembers(), L1_MANAGER), "staff.profile.read");
    }

    @Test
    @DisplayName(
            "a branch answers \"no such member\" for a person of a sibling branch -- the answer an unknown id gets")
    void aBranchIsNoExistenceOracle() throws Exception {
        UUID siblingsCook = memberId(COOK_3);

        MvcResult sibling = get(branchMembers(LOC_1) + "/" + siblingsCook, L1_MANAGER);
        MvcResult unknown = get(branchMembers(LOC_1) + "/" + UUID.randomUUID(), L1_MANAGER);

        assertThat(sibling.getResponse().getStatus()).isEqualTo(404);
        assertThat(unknown.getResponse().getStatus()).isEqualTo(404);
        for (MvcResult answer : List.of(sibling, unknown)) {
            assertThat(answer.getResponse().getContentAsString(UTF_8))
                    .contains("RESOURCE_NOT_FOUND", "No such staff member");
        }
    }

    @Test
    @DisplayName(
            "a line cook with only a location grant reads and edits their own profile, and cannot address anyone else")
    void aCookEditsTheirOwnNameAndPhone() throws Exception {
        MvcResult mine = get(me(), COOK_1);
        assertThat(mine.getResponse().getStatus()).isEqualTo(200);
        assertThat(mine.getResponse().getContentAsString(UTF_8))
                .contains("Shahlo", "Tursunova")
                .as("the full number is the person's own to see")
                .contains("+998908881122");
        int version = versionOf(mine);

        MvcResult edited = put(me(), COOK_1, """
                {"firstName":"Shahlo","lastName":"Tursunova-Karimova","phone":"+998 91 000 11 22",
                 "uiLocale":"uz","spokenLanguages":["uz","ru"],"removePhoto":false}
                """, version);

        assertThat(edited.getResponse().getStatus()).isEqualTo(200);
        assertThat(edited.getResponse().getContentAsString(UTF_8))
                .contains("Tursunova-Karimova", "+998910001122", "\"uiLocale\":\"uz\"");
        assertThat(versionOf(edited)).isEqualTo(version + 1);

        // The same cook cannot read the lists or another member through any route.
        assertThat(get(branchMembers(LOC_1), COOK_1).getResponse().getStatus()).isEqualTo(403);
        assertThat(get(tenantMembers(), COOK_1).getResponse().getStatus()).isEqualTo(403);
        assertThat(get(branchMembers(LOC_1) + "/" + memberId(COOK_2), COOK_1)
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    @Test
    @DisplayName(
            "a self-edit is on the activity log under the new name at the very next request, with no wait for a cache")
    void aSelfEditIsOnTheActivityLogAtOnce() throws Exception {
        // Something COOK_1 did, so the log has a row that names them.
        jdbc.sql("""
                INSERT INTO audit.audit_events
                    (id, recorded_at, tenant_id, audit_class, action_code, actor_type, actor_subject,
                     scope_type, scope_id, outcome, reason, correlation_id, occurred_at)
                VALUES (:id, now(), :t, 'BUSINESS', 'order.cancel', 'USER', :actor, 'TENANT', :t,
                        'SUCCEEDED', 'fixture', 'corr-log', now())
                """)
                .param("id", UUID.randomUUID())
                .param("t", TENANT)
                .param("actor", COOK_1)
                .update();
        String before = get(auditEvents(), OWNER).getResponse().getContentAsString(UTF_8);
        assertThat(before).contains(COOK_1_NAME);

        MvcResult mine = get(me(), COOK_1);
        put(me(), COOK_1, "{\"firstName\":\"Shahlo\",\"lastName\":\"Renamed\"}", versionOf(mine));

        String after = get(auditEvents(), OWNER).getResponse().getContentAsString(UTF_8);
        assertThat(after)
                .as("the write evicted the (tenant, subject) entry once its transaction committed")
                .contains("Shahlo Renamed")
                .doesNotContain(COOK_1_NAME);
    }

    // ======================================================================= self service

    @Test
    @DisplayName(
            "an account with no grant in the tenant, and one whose grants are in another tenant, are refused on /me")
    void meIsRefusedWithoutAJobInThisTenant() throws Exception {
        assertCapabilityRefusal(get(me(), NO_GRANT), "staff.self.manage");
        assertCapabilityRefusal(get(me(), OTHER_OWNER), "staff.self.manage");
        assertCapabilityRefusal(put(me(), NO_GRANT, "{\"firstName\":\"X\"}", 1), "staff.self.manage");
    }

    @Test
    @DisplayName("a person with a job but no record here is told there is no profile, not given someone else's")
    void aJobWithoutARecordHasNoProfile() throws Exception {
        grant("http-unrecorded", PlatformRole.LOCATION_STAFF, "LOCATION", LOC_1, TENANT);

        MvcResult result = get(me(), "http-unrecorded");

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentAsString(UTF_8)).contains("RESOURCE_NOT_FOUND");
    }

    @Test
    @DisplayName("every tenant-visible job reaches its own profile, a finance clerk's included")
    void everyJobReachesItsOwnProfile() throws Exception {
        // The other four jobs already have a record from reset().
        for (String subject : List.of(OWNER, ADMIN, BRAND_MANAGER)) {
            member(TENANT, subject, "Person", subject, null);
        }
        for (String subject : List.of(OWNER, ADMIN, BRAND_MANAGER, L1_MANAGER, COOK_1, FINANCE)) {
            assertThat(get(me(), subject).getResponse().getStatus())
                    .as("%s reaches /me", subject)
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("a body that names employment, the employee number or a status changes none of them")
    void aSelfEditCannotTouchEmployment() throws Exception {
        UUID cook = memberId(COOK_2);
        tx.executeWithoutResult(status -> jdbc.sql(
                        "UPDATE iam.staff_members SET employment_status = 'ON_LEAVE', employed_from = '2026-01-01' WHERE id = :id")
                .param("id", cook)
                .update());
        MvcResult mine = get(me(), COOK_2);

        MvcResult edited = put(me(), COOK_2, """
                {"firstName":"Rustam","lastName":"Qodirov","employmentStatus":"ENDED","employeeNumber":"HACK-1",
                 "employedUntil":"2026-02-02","principalSubject":"http-owner","memberId":"%s"}
                """.formatted(memberId(COOK_3)), versionOf(mine));

        assertThat(edited.getResponse().getStatus()).isEqualTo(200);
        String body = edited.getResponse().getContentAsString(UTF_8);
        assertThat(body).contains("\"employmentStatus\":\"ON_LEAVE\"").doesNotContain("HACK-1");
        assertThat(body).contains("\"principalSubject\":\"" + COOK_2 + "\"");
        assertThat(jdbc.sql("SELECT employment_status FROM iam.staff_members WHERE id = :id")
                        .param("id", memberId(COOK_3))
                        .query(String.class)
                        .single())
                .as("and the body's memberId reached nobody")
                .isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName(
            "the console's own JSON works: a missing optional field is not MALFORMED_BODY (Jackson 3 refuses a missing primitive)")
    void minimalAndFullBodiesBothWork() throws Exception {
        MvcResult minimal = put(me(), COOK_1, "{\"firstName\":\"Shahlo\"}", versionOf(get(me(), COOK_1)));
        assertThat(minimal.getResponse().getStatus()).isEqualTo(200);

        MvcResult full = put(me(), COOK_1, """
                {"firstName":"Shahlo","lastName":null,"phone":null,"uiLocale":null,"spokenLanguages":[],
                 "removePhoto":null}
                """, versionOf(minimal));
        assertThat(full.getResponse().getStatus()).isEqualTo(200);

        MvcResult managerMinimal = put(
                branchMembers(LOC_1) + "/" + memberId(COOK_2),
                L1_MANAGER,
                "{\"firstName\":\"Rustam\"}",
                versionOf(get(branchMembers(LOC_1) + "/" + memberId(COOK_2), L1_MANAGER)));
        assertThat(managerMinimal.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("an edit needs If-Match and an Idempotency-Key, and a stale version is a conflict")
    void preconditionsAreRequired() throws Exception {
        int version = versionOf(get(me(), COOK_1));

        MockHttpServletRequestBuilder noMatch = MockMvcRequestBuilders.put(me())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Shahlo\"}")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(tokenFor(COOK_1));
        assertThat(mvc.perform(noMatch).andReturn().getResponse().getStatus()).isEqualTo(400);

        MockHttpServletRequestBuilder noKey = MockMvcRequestBuilders.put(me())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"Shahlo\"}")
                .header("If-Match", "W/\"" + version + "\"")
                .with(tokenFor(COOK_1));
        MvcResult noKeyResult = mvc.perform(noKey).andReturn();
        assertThat(noKeyResult.getResponse().getStatus()).isEqualTo(400);
        assertThat(noKeyResult.getResponse().getContentAsString(UTF_8)).contains("IDEMPOTENCY_KEY_REQUIRED");

        MvcResult stale = put(me(), COOK_1, "{\"firstName\":\"Shahlo\",\"lastName\":\"Other\"}", version + 5);
        assertThat(stale.getResponse().getStatus()).isEqualTo(409);
        assertThat(stale.getResponse().getContentAsString(UTF_8)).contains("STALE_VERSION");
    }

    @Test
    @DisplayName("a replayed request gets the same answer, and the stored reply holding a name is encrypted")
    void aReplayIsAnsweredFromAnEncryptedRecord() throws Exception {
        int version = versionOf(get(me(), COOK_1));
        String key = UUID.randomUUID().toString();
        String json = "{\"firstName\":\"Shahlo\",\"lastName\":\"Replayed\"}";

        MvcResult first = mvc.perform(MockMvcRequestBuilders.put(me())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .header("If-Match", "W/\"" + version + "\"")
                        .header("Idempotency-Key", key)
                        .with(tokenFor(COOK_1)))
                .andReturn();
        MvcResult replay = mvc.perform(MockMvcRequestBuilders.put(me())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .header("If-Match", "W/\"" + version + "\"")
                        .header("Idempotency-Key", key)
                        .with(tokenFor(COOK_1)))
                .andReturn();

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getStatus()).isEqualTo(200);
        assertThat(replay.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(replay.getResponse().getContentAsString(UTF_8))
                .isEqualTo(first.getResponse().getContentAsString(UTF_8));
        Map<String, Object> record = jdbc.sql(
                        "SELECT response_body, response_body_protected FROM platform.idempotency_records WHERE idempotency_key = :key")
                .param("key", key)
                .query()
                .singleRow();
        assertThat(record.get("response_body_protected")).isEqualTo(true);
        assertThat(String.valueOf(record.get("response_body"))).doesNotContain("Replayed", "Shahlo");
    }

    // ======================================================================= photo

    @Test
    @DisplayName("a photo is the image bytes, goes through the media port, and comes back as a signed URL")
    void aPhotoIsStoredThroughThePort() throws Exception {
        UUID assetId = UUID.randomUUID();
        // The port is a stub, so the asset is a real media row of this tenant.
        jdbc.sql("""
                INSERT INTO media.assets (asset_id, tenant_id, owner_scope, owner_id, bucket,
                    object_key, visibility, status, declared_content_type, declared_size_bytes,
                    verified_content_type, verified_size_bytes, verified_checksum_sha256)
                VALUES (:id, :t, 'TENANT', :t, 'horecaos-media', :key, 'PRIVATE', 'AVAILABLE',
                    'image/png', 10, 'image/png', 10, repeat('0', 64))
                """)
                .param("id", assetId)
                .param("t", TENANT)
                .param("key", TENANT + "/tenant/" + assetId)
                .update();
        when(photos.ingest(eq(TENANT), any(), any(), any())).thenReturn(new StaffPhotos.Ingested(true, assetId, null));
        when(photos.isPrivateTenantAsset(TENANT, assetId)).thenReturn(true);
        when(photos.signedReadUrl(TENANT, assetId)).thenReturn(Optional.of(URI.create("https://signed.example/photo")));

        MvcResult result = mvc.perform(MockMvcRequestBuilders.post(me() + "/photo")
                        .contentType("image/png")
                        .content(new byte[] {(byte) 0x89, 'P', 'N', 'G'})
                        .header("If-Match", "W/\"" + versionOf(get(me(), COOK_1)) + "\"")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(tokenFor(COOK_1)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString(UTF_8);
        assertThat(body).contains("\"hasPhoto\":true").contains("https://signed.example/photo");
        assertThat(jdbc.sql("SELECT photo_asset_id FROM iam.staff_members WHERE id = :id")
                        .param("id", memberId(COOK_1))
                        .query(UUID.class)
                        .single())
                .isEqualTo(assetId);
    }

    @Test
    @DisplayName("a photo that is not an image type is refused before it is read, and an empty one is refused")
    void aPhotoMustBeAnImage() throws Exception {
        MockHttpServletRequestBuilder text = MockMvcRequestBuilders.post(me() + "/photo")
                .contentType(MediaType.TEXT_PLAIN)
                .content("not an image")
                .header("If-Match", "W/\"2\"")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(tokenFor(COOK_1));
        assertThat(mvc.perform(text).andReturn().getResponse().getStatus()).isEqualTo(415);

        MockHttpServletRequestBuilder empty = MockMvcRequestBuilders.post(me() + "/photo")
                .contentType("image/png")
                .content(new byte[0])
                .header("If-Match", "W/\"2\"")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .with(tokenFor(COOK_1));
        assertThat(mvc.perform(empty).andReturn().getResponse().getStatus()).isGreaterThanOrEqualTo(400);
    }

    // ======================================================================= the tenant routes

    @Test
    @DisplayName(
            "the owner and the administrator read the tenant's people; a brand manager reads through brand routes and changes nothing")
    void tenantAndBrandRoutes() throws Exception {
        for (String subject : List.of(OWNER, ADMIN)) {
            MvcResult all = get(tenantMembers(), subject);
            assertThat(all.getResponse().getStatus()).as(subject).isEqualTo(200);
            assertThat(all.getResponse().getContentAsString(UTF_8))
                    .contains("Tursunova", "Aliyev", "Idle", "Moliya")
                    .doesNotContain("Other");
        }

        MvcResult brand = get(brandMembers(), BRAND_MANAGER);
        assertThat(brand.getResponse().getStatus()).isEqualTo(200);
        assertThat(brand.getResponse().getContentAsString(UTF_8))
                .contains("Tursunova", "Aliyev", "Nazarov")
                .doesNotContain("Idle", "Moliya");
        assertThat(get(branchMembers(LOC_2), BRAND_MANAGER).getResponse().getStatus())
                .as("a brand grant covers the branches beneath it")
                .isEqualTo(200);
        assertCapabilityRefusal(get(tenantMembers(), BRAND_MANAGER), "staff.profile.read");
        assertCapabilityRefusal(
                put(branchMembers(LOC_1) + "/" + memberId(COOK_1), BRAND_MANAGER, "{\"firstName\":\"Shahlo\"}", 2),
                "staff.profile.manage");
        assertCapabilityRefusal(get(tenantMembers(), FINANCE), "staff.profile.read");
    }

    @Test
    @DisplayName(
            "another tenant's owner cannot read this tenant's people, and this tenant's member id means nothing on theirs")
    void tenantsAreIsolated() throws Exception {
        assertThat(get(tenantMembers(), OTHER_OWNER).getResponse().getStatus()).isEqualTo(403);
        MvcResult theirs = get("/api/v1/operations/tenants/" + OTHER_TENANT + "/staff/members", OTHER_OWNER);
        assertThat(theirs.getResponse().getStatus()).isEqualTo(200);
        assertThat(theirs.getResponse().getContentAsString(UTF_8))
                .contains("Other")
                .doesNotContain("Tursunova");
        assertThat(get("/api/v1/operations/tenants/" + OTHER_TENANT + "/staff/members/" + memberId(COOK_1), OTHER_OWNER)
                        .getResponse()
                        .getStatus())
                .as("a member id of the first tenant is simply not there on the second")
                .isEqualTo(404);
    }

    @Test
    @DisplayName(
            "a branch manager changes a person of their branch; one who also works elsewhere is not theirs to change")
    void aBranchManagerChangesOnlyTheirOwnPeople() throws Exception {
        String own = branchMembers(LOC_1) + "/" + memberId(COOK_2);
        MvcResult changed = put(own, L1_MANAGER, """
                {"firstName":"Rustam","lastName":"Qodirov","phone":"+998 90 222 33 99","employmentStatus":"ON_LEAVE",
                 "employeeNumber":"E-204","employedFrom":"2026-03-01","reason":"returning after leave"}
                """, versionOf(get(own, L1_MANAGER)));
        assertThat(changed.getResponse().getStatus()).isEqualTo(200);
        assertThat(changed.getResponse().getContentAsString(UTF_8))
                .contains("\"employmentStatus\":\"ON_LEAVE\"", "E-204", "+998902223399");

        String both = branchMembers(LOC_1) + "/" + memberId(BOTH);
        MvcResult refused = put(both, L1_MANAGER, "{\"firstName\":\"Jasur\"}", versionOf(get(both, L1_MANAGER)));
        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString(UTF_8)).contains("INSUFFICIENT_CAPABILITY");
    }

    // ================================================================= emergency contacts

    @Test
    @DisplayName(
            "emergency contacts: read by owner, administrator and the branch's manager (audited), changed by whoever manages the person, closed to everyone else")
    void emergencyContactsAreGatedAndAudited() throws Exception {
        String cookPath = branchMembers(LOC_1) + "/" + memberId(COOK_1) + "/emergency-contacts";
        MvcResult none = get(cookPath, L1_MANAGER);
        assertThat(none.getResponse().getStatus()).isEqualTo(200);
        int version = versionOf(none);

        MvcResult replaced = put(cookPath, L1_MANAGER, """
                {"contacts":[{"relationshipCode":"SPOUSE","name":"Gulnara Tursunova","phone":"+998 71 999 00 11"}],
                 "reason":"on file at hiring"}
                """, version);
        assertThat(replaced.getResponse().getStatus()).isEqualTo(200);

        MvcResult read = get(cookPath, L1_MANAGER);
        assertThat(read.getResponse().getContentAsString(UTF_8)).contains("Gulnara Tursunova", "+998719990011");

        assertThat(get(tenantMemberPath(COOK_1) + "/emergency-contacts", OWNER)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertThat(get(tenantMemberPath(COOK_1) + "/emergency-contacts", ADMIN)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
        assertCapabilityRefusal(get(cookPath, BRAND_MANAGER), "staff.emergency-contact.read");
        assertCapabilityRefusal(get(cookPath, COOK_1), "staff.emergency-contact.read");
        assertCapabilityRefusal(get(cookPath, FINANCE), "staff.emergency-contact.read");
        assertCapabilityRefusal(
                get(branchMembers(LOC_2) + "/" + memberId(COOK_3) + "/emergency-contacts", L1_MANAGER),
                "staff.emergency-contact.read");

        Integer reads = jdbc.sql(
                        "SELECT count(*) FROM audit.audit_events WHERE action_code = 'staff.emergency_contact.read'")
                .query(Integer.class)
                .single();
        assertThat(reads)
                .as("the branch manager's two reads, the owner's and the administrator's each left a fact; "
                        + "the refused reads left none")
                .isEqualTo(4);
        assertThat(jdbc.sql("SELECT string_agg(change_document::text, ' ') FROM audit.audit_events")
                        .query(String.class)
                        .single())
                .doesNotContain("Gulnara", "999 00 11", "9990011");
    }

    // ======================================================================== end employment

    @Test
    @DisplayName("ending employment ends the person's access in the same act; only the owner and the administrator can")
    void endingEmploymentEndsAccess() throws Exception {
        String end = tenantMemberPath(COOK_2) + "/end-employment";
        int version = versionOf(get(tenantMemberPath(COOK_2), OWNER));

        MvcResult refused = post(end, L1_MANAGER, "{\"reason\":\"resigned\"}", version);
        assertThat(refused.getResponse().getStatus())
                .as("the branch has no end-employment route of its own: it also revokes jobs")
                .isIn(403, 404, 405);

        MvcResult ended = post(end, OWNER, "{\"reason\":\"resigned\",\"employedUntil\":\"2026-09-30\"}", version);

        assertThat(ended.getResponse().getStatus()).isEqualTo(200);
        String body = ended.getResponse().getContentAsString(UTF_8);
        assertThat(body)
                .contains("\"revokedGrants\":1", "\"remainingGrants\":0", "\"employmentStatus\":\"ENDED\"")
                .contains("\"employedUntil\":\"2026-09-30\"");
        assertThat(get(me(), COOK_2).getResponse().getStatus())
                .as("with no job left the person cannot reach even their own profile")
                .isEqualTo(403);
        assertThat(get(branchMembers(LOC_1), L1_MANAGER).getResponse().getContentAsString(UTF_8))
                .as("and no longer appears in the branch's list: it lists people with an active job there")
                .doesNotContain("Qodirov");
        assertThat(get(tenantMemberPath(COOK_2), OWNER).getResponse().getContentAsString(UTF_8))
                .as("their history still names them to the tenant")
                .contains("Qodirov");
    }

    // ================================================================================ fixtures

    private static String tenantMembers() {
        return "/api/v1/operations/tenants/" + TENANT + "/staff/members";
    }

    private static String brandMembers() {
        return "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/staff/members";
    }

    private static String branchMembers(UUID location) {
        return "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + location
                + "/staff/members";
    }

    private String tenantMemberPath(String subject) {
        return tenantMembers() + "/" + memberId(subject);
    }

    private static String me() {
        return "/api/v1/operations/tenants/" + TENANT + "/staff/me";
    }

    private static String auditEvents() {
        return "/api/v1/operations/tenants/" + TENANT + "/audit-events";
    }

    private UUID memberId(String subject) {
        UUID tenant = subject.equals(OTHER_OWNER) ? OTHER_TENANT : TENANT;
        return jdbc.sql("SELECT id FROM iam.staff_members WHERE tenant_id = :t AND principal_subject = :s")
                .param("t", tenant)
                .param("s", subject)
                .query(UUID.class)
                .single();
    }

    private MvcResult get(String path, String subject) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).with(tokenFor(subject)))
                .andReturn();
    }

    private MvcResult put(String path, String subject, String json, int ifMatch) throws Exception {
        return send(HttpMethod.PUT, path, subject, json, ifMatch);
    }

    private MvcResult post(String path, String subject, String json, int ifMatch) throws Exception {
        return send(HttpMethod.POST, path, subject, json, ifMatch);
    }

    private MvcResult send(HttpMethod method, String path, String subject, String json, int ifMatch) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.request(method, path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .header("If-Match", "W/\"" + ifMatch + "\"")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(tokenFor(subject)))
                .andReturn();
    }

    private static int versionOf(MvcResult result) throws Exception {
        String etag = result.getResponse().getHeader("ETag");
        assertThat(etag)
                .as("a single-member read carries its version as an ETag")
                .isNotNull();
        return Integer.parseInt(
                java.util.Objects.requireNonNull(etag).replace("W/", "").replace("\"", ""));
    }

    private static void assertCapabilityRefusal(MvcResult result, String capability) throws Exception {
        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(result.getResponse().getContentAsString(UTF_8))
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(capability);
    }

    /** A member the way the invitation flow makes one: registered, then activated with the typed name. */
    private void member(UUID tenantId, String subject, String first, String last, @Nullable String phone) {
        tx.executeWithoutResult(status -> {
            members.registerInvited(tenantId, subject, first, last, phone, "fixture-inviter", "corr-fixture");
            members.activate(tenantId, subject, first, last, "corr-fixture");
        });
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId, UUID tenantId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'staff member endpoint test', :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void seedTenancy(UUID tenantId, UUID brandId, UUID locationId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", brandId).param("t", tenantId).update();
        seedLocation(tenantId, brandId, locationId, "L1");
    }

    private void seedLocation(UUID tenantId, UUID brandId, UUID locationId, String code) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, :code, :slug, :code, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("code", code)
                .param("slug", code.toLowerCase())
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
