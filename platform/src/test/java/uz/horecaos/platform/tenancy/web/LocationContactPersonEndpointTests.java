package uz.horecaos.platform.tenancy.web;

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.staff.StaffMemberRegistry;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Gap map row 9.2b, the branch half: a branch's named contact persons over the
 * real HTTP stack and a real database (ADR 0139).
 *
 * <p>Held here: the capability is the registry's {@code location.read} /
 * {@code location.write} at the branch's own scope; a colleague is stored as a
 * staff member id and nothing else; an outside person is a sealed name and phone
 * that is never in an audit fact; the colleague must be one of <em>this</em>
 * tenant's people, and the answer for a colleague of another tenant is the answer
 * for one that does not exist; and the set is replaced under the location's
 * version.
 */
@SpringBootTest
@AutoConfigureMockMvc
class LocationContactPersonEndpointTests {

    private static final UUID TENANT = UUID.fromString("018fb400-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb400-4000-7000-8000-0000000000b1");
    private static final UUID LOC_1 = UUID.fromString("018fb400-4000-7000-8000-0000000000c1");
    private static final UUID LOC_2 = UUID.fromString("018fb400-4000-7000-8000-0000000000c2");

    private static final UUID OTHER_TENANT = UUID.fromString("018fb400-4000-7000-8000-0000000000a2");
    private static final UUID OTHER_BRAND = UUID.fromString("018fb400-4000-7000-8000-0000000000b2");
    private static final UUID OTHER_LOC = UUID.fromString("018fb400-4000-7000-8000-0000000000c3");

    private static final String L1_MANAGER = "contacts-l1-manager";
    private static final String L1_COOK = "contacts-l1-cook";
    private static final String L2_MANAGER = "contacts-l2-manager";
    private static final String OWNER = "contacts-owner";
    private static final String OUTSIDER = "contacts-outsider";
    private static final String FOREIGN_COLLEAGUE = "contacts-foreign";

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
    private StaffMemberRegistry registry;

    @Autowired
    @SuppressWarnings("NullAway")
    private TransactionTemplate tx;

    private UUID colleagueId = UUID.randomUUID();
    private UUID foreignColleagueId = UUID.randomUUID();

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        seed(TENANT, BRAND, LOC_1, "contacts-a");
        seedLocation(TENANT, BRAND, LOC_2, "L2", "contacts-a-l2");
        seed(OTHER_TENANT, OTHER_BRAND, OTHER_LOC, "contacts-b");

        grant(L1_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", LOC_1);
        grant(L1_COOK, PlatformRole.LOCATION_STAFF, "LOCATION", LOC_1);
        grant(L2_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", LOC_2);
        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);

        member(TENANT, L1_MANAGER, "Madina", "Yusupova", "+998 90 555 66 77");
        colleagueId = memberId(TENANT, L1_MANAGER);
        member(OTHER_TENANT, FOREIGN_COLLEAGUE, "Foreign", "Colleague", "+998 90 000 00 00");
        foreignColleagueId = memberId(OTHER_TENANT, FOREIGN_COLLEAGUE);
    }

    @Test
    @DisplayName(
            "the branch manager lists, replaces and re-reads the branch's contacts: a colleague by member, an outsider by name and phone")
    void theBranchManagerKeepsTheBranchsContacts() throws Exception {
        MvcResult empty = get(path(LOC_1), OWNER);
        assertThat(empty.getResponse().getStatus()).isEqualTo(200);
        long version = versionOf(empty);

        MvcResult replaced = put(path(LOC_1), OWNER, """
                {"contacts":[
                  {"relationshipCode":"MANAGER","staffMemberId":"%s"},
                  {"relationshipCode":"LANDLORD","name":"Anvar Ergashev","phone":"+998 71 200 30 40"}],
                 "reason":"head office asked for the landlord's number"}
                """.formatted(colleagueId), version);

        assertThat(replaced.getResponse().getStatus()).isEqualTo(200);
        String body = replaced.getResponse().getContentAsString(UTF_8);
        assertThat(body)
                .as("a colleague shows the name and the contact phone the tenant keeps for them now")
                .contains("Madina Yusupova", "+998905556677")
                .contains("S-0001")
                .contains("Anvar Ergashev", "+998712003040");
        assertThat(versionOf(replaced)).isEqualTo(version + 1);

        List<Map<String, Object>> rows = jdbc.sql(
                        "SELECT staff_member_id, protected_name, protected_phone FROM tenant.location_contact_persons ORDER BY relationship_code")
                .query()
                .listOfRows();
        assertThat(rows).hasSize(2);
        Map<String, Object> landlord = rows.get(0);
        Map<String, Object> manager = rows.get(1);
        assertThat(String.valueOf(landlord.get("protected_name")) + landlord.get("protected_phone"))
                .as("the outsider's name and phone are sealed")
                .doesNotContain("Anvar", "Ergashev", "200 30");
        assertThat(manager.get("protected_name"))
                .as("no copy of a colleague's name")
                .isNull();
        assertThat(manager.get("protected_phone"))
                .as("no copy of a colleague's phone")
                .isNull();
        assertThat(manager.get("staff_member_id")).isEqualTo(colleagueId);

        MvcResult again = get(path(LOC_1), L1_COOK);
        assertThat(again.getResponse().getStatus())
                .as("location.read at the branch: the cook reads who to call about it")
                .isEqualTo(200);
        assertThat(again.getResponse().getContentAsString(UTF_8)).contains("Anvar Ergashev");
    }

    @Test
    @DisplayName("a colleague's later phone change is shown at once, because no copy was made")
    void aColleaguesNumberFollowsTheirOwnRecord() throws Exception {
        put(
                path(LOC_1),
                OWNER,
                "{\"contacts\":[{\"relationshipCode\":\"MANAGER\",\"staffMemberId\":\"%s\"}]}".formatted(colleagueId),
                versionOf(get(path(LOC_1), L1_MANAGER)));
        tx.executeWithoutResult(
                status -> jdbc.sql("UPDATE iam.staff_members SET employment_status = employment_status WHERE id = :id")
                        .param("id", colleagueId)
                        .update());

        assertThat(get(path(LOC_1), L1_MANAGER).getResponse().getContentAsString(UTF_8))
                .contains("+998905556677");
    }

    @Test
    @DisplayName(
            "a listed colleague whose employment ended is shown as a former colleague: no name, no number, the reference alone")
    void anEndedColleagueIsNoLongerPublishedToTheBranch() throws Exception {
        put(
                path(LOC_1),
                OWNER,
                "{\"contacts\":[{\"relationshipCode\":\"MANAGER\",\"staffMemberId\":\"%s\"}]}".formatted(colleagueId),
                versionOf(get(path(LOC_1), L1_MANAGER)));
        String whileEmployed = get(path(LOC_1), L1_COOK).getResponse().getContentAsString(UTF_8);
        assertThat(whileEmployed)
                .as("while Madina works here the cook sees who to call")
                .contains("Madina Yusupova", "+998905556677");

        endEmployment(colleagueId);

        String afterwards = get(path(LOC_1), L1_COOK).getResponse().getContentAsString(UTF_8);
        assertThat(afterwards)
                .as("a former employee's personal data is not published to the whole branch")
                .doesNotContain("Madina", "Yusupova", "+998905556677", "905556677");
        assertThat(afterwards)
                .as("the row stays, as a reference the manager can recognise and remove")
                .contains("S-0001");
        assertThat(afterwards).as("and says that the person has left").contains("\"formerColleague\":true");
        assertThat(whileEmployed)
                .as("whereas a current colleague is not marked")
                .contains("\"formerColleague\":false");
        assertThat(get(path(LOC_1), OWNER).getResponse().getContentAsString(UTF_8))
                .as("the owner's reading is no different")
                .doesNotContain("Madina", "+998905556677");
    }

    @Test
    @DisplayName("a colleague whose employment ended cannot be listed as a contact, with a reason that says so")
    void anEndedColleagueCannotBeAddedAsAContact() throws Exception {
        endEmployment(colleagueId);
        long version = versionOf(get(path(LOC_1), L1_MANAGER));

        MvcResult refused = put(
                path(LOC_1),
                OWNER,
                "{\"contacts\":[{\"relationshipCode\":\"MANAGER\",\"staffMemberId\":\"%s\"}]}".formatted(colleagueId),
                version);

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString(UTF_8)).contains("no longer works");
        assertThat(jdbc.sql("SELECT count(*) FROM tenant.location_contact_persons")
                        .query(Long.class)
                        .single())
                .as("nothing was written")
                .isZero();
        assertThat(versionOf(get(path(LOC_1), L1_MANAGER)))
                .as("and the location's version did not move")
                .isEqualTo(version);
    }

    @Test
    @DisplayName("the fact says that contacts changed and how many, and never a name or a number")
    void theAuditFactCarriesNoPersonalValue() throws Exception {
        put(path(LOC_1), OWNER, """
                {"contacts":[{"relationshipCode":"SECURITY","name":"Gulom Security","phone":"+998 93 444 55 66"}]}
                """, versionOf(get(path(LOC_1), L1_MANAGER)));

        String document = jdbc.sql(
                        "SELECT change_document::text FROM audit.audit_events WHERE action_code = 'iam.location_contact.updated'")
                .query(String.class)
                .single();
        assertThat(document).contains("contactCount").contains("SECURITY").doesNotContain("Gulom", "444 55", "4445566");
        assertThat(jdbc.sql(
                                "SELECT capability_used FROM audit.audit_events WHERE action_code = 'iam.location_contact.updated'")
                        .query(String.class)
                        .single())
                .isEqualTo("location.write");
    }

    @Test
    @DisplayName(
            "a manager of another branch, an unrelated account and the owner of nothing here are refused the branch's contacts")
    void theBranchsContactsBelongToTheBranch() throws Exception {
        assertThat(get(path(LOC_1), L2_MANAGER).getResponse().getStatus()).isEqualTo(403);
        assertThat(get(path(LOC_1), OUTSIDER).getResponse().getStatus()).isEqualTo(403);
        assertThat(put(path(LOC_1), L2_MANAGER, "{\"contacts\":[]}", 0)
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(get(path(LOC_1), OWNER).getResponse().getStatus())
                .as("the owner's tenant grant covers every branch")
                .isEqualTo(200);
    }

    @Test
    @DisplayName(
            "a branch manager and a line cook read the branch's contacts but cannot change them: location.write is the owner's and the administrator's")
    void onlyTheOwnerAndAdministratorWrite() throws Exception {
        long version = versionOf(get(path(LOC_1), L1_COOK));

        for (String reader : List.of(L1_COOK, L1_MANAGER)) {
            MvcResult refused = put(path(LOC_1), reader, "{\"contacts\":[]}", version);
            assertThat(refused.getResponse().getStatus()).as(reader).isEqualTo(403);
            assertThat(refused.getResponse().getContentAsString(UTF_8)).contains("location.write");
        }
        assertThat(put(path(LOC_1), OWNER, "{\"contacts\":[]}", version)
                        .getResponse()
                        .getStatus())
                .isEqualTo(200);
    }

    @Test
    @DisplayName("a colleague of another tenant is refused with the same answer as one that does not exist")
    void aForeignColleagueIsNoOracle() throws Exception {
        long version = versionOf(get(path(LOC_1), L1_MANAGER));

        MvcResult foreign = put(
                path(LOC_1),
                OWNER,
                "{\"contacts\":[{\"relationshipCode\":\"MANAGER\",\"staffMemberId\":\"%s\"}]}"
                        .formatted(foreignColleagueId),
                version);
        MvcResult missing = put(
                path(LOC_1),
                OWNER,
                "{\"contacts\":[{\"relationshipCode\":\"MANAGER\",\"staffMemberId\":\"%s\"}]}"
                        .formatted(UUID.randomUUID()),
                version);

        assertThat(foreign.getResponse().getStatus()).isEqualTo(400);
        assertThat(missing.getResponse().getStatus()).isEqualTo(400);
        assertThat(foreign.getResponse().getContentAsString(UTF_8)).contains("That staff member is not available");
        assertThat(missing.getResponse().getContentAsString(UTF_8)).contains("That staff member is not available");
    }

    @Test
    @DisplayName(
            "the database refuses a contact that names another tenant's colleague even when the service is bypassed")
    void theDatabaseRefusesAForeignColleague() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO tenant.location_contact_persons
                                    (id, tenant_id, location_id, relationship_code, staff_member_id)
                                VALUES (:id, :t, :l, 'MANAGER', :member)
                                """)
                        .param("id", UUID.randomUUID())
                        .param("t", TENANT)
                        .param("l", LOC_1)
                        .param("member", foreignColleagueId)
                        .update())
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("a row is a colleague or an outsider, never both and never neither")
    void aRowIsOneShapeOrTheOther() throws Exception {
        long version = versionOf(get(path(LOC_1), L1_MANAGER));

        MvcResult both = put(
                path(LOC_1),
                OWNER,
                "{\"contacts\":[{\"relationshipCode\":\"MANAGER\",\"staffMemberId\":\"%s\",\"name\":\"Copy\",\"phone\":\"+998 90 000 00 01\"}]}"
                        .formatted(colleagueId),
                version);
        MvcResult neither = put(path(LOC_1), OWNER, "{\"contacts\":[{\"relationshipCode\":\"MANAGER\"}]}", version);
        MvcResult badRole = put(
                path(LOC_1),
                OWNER,
                "{\"contacts\":[{\"relationshipCode\":\"CEO\",\"name\":\"Someone\",\"phone\":\"+998 90 000 00 01\"}]}",
                version);

        for (MvcResult refused : List.of(both, neither, badRole)) {
            assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        }
    }

    @Test
    @DisplayName(
            "the set is replaced under the location's version: a stale version is a conflict, and a replace moves it")
    void theSetIsGuardedByTheLocationsVersion() throws Exception {
        long version = versionOf(get(path(LOC_1), L1_MANAGER));
        assertThat(put(path(LOC_1), OWNER, "{\"contacts\":[]}", version + 3)
                        .getResponse()
                        .getStatus())
                .isEqualTo(409);

        MvcResult ok = put(path(LOC_1), OWNER, "{\"contacts\":[]}", version);
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.sql("SELECT version FROM tenant.locations WHERE id = :id")
                        .param("id", LOC_1)
                        .query(Long.class)
                        .single())
                .isEqualTo(version + 1);
        assertThat(put(path(LOC_1), OWNER, "{\"contacts\":[]}", version)
                        .getResponse()
                        .getStatus())
                .as("the version that was just replaced is stale")
                .isEqualTo(409);
    }

    @Test
    @DisplayName("a location of another brand or tenant is simply not there")
    void aForeignLocationIsNotThere() throws Exception {
        String foreign = "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + OTHER_LOC
                + "/contact-persons";

        MvcResult result = get(foreign, OWNER);

        assertThat(result.getResponse().getStatus()).isIn(403, 404);
    }

    // ------------------------------------------------------------------- fixtures

    private static String path(UUID location) {
        return "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + location
                + "/contact-persons";
    }

    private MvcResult get(String path, String subject) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.get(path).with(tokenFor(subject)))
                .andReturn();
    }

    private MvcResult put(String path, String subject, String json, long ifMatch) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json)
                        .header("If-Match", "W/\"" + ifMatch + "\"")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .with(tokenFor(subject)))
                .andReturn();
    }

    private static long versionOf(MvcResult result) {
        String etag = result.getResponse().getHeader("ETag");
        assertThat(etag).isNotNull();
        return Long.parseLong(
                java.util.Objects.requireNonNull(etag).replace("W/", "").replace("\"", ""));
    }

    private void member(UUID tenantId, String subject, String first, String last, @Nullable String phone) {
        tx.executeWithoutResult(status -> {
            registry.registerInvited(tenantId, subject, first, last, phone, "fixture-inviter", "corr");
            registry.activate(tenantId, subject, first, last, "corr");
        });
    }

    /** What ending employment leaves in the record; the grants are not this test's subject. */
    private void endEmployment(UUID memberId) {
        tx.executeWithoutResult(status -> jdbc.sql("""
                        UPDATE iam.staff_members
                        SET employment_status = 'ENDED', employed_until = current_date
                        WHERE id = :id
                        """).param("id", memberId).update());
    }

    private UUID memberId(UUID tenantId, String subject) {
        return jdbc.sql("SELECT id FROM iam.staff_members WHERE tenant_id = :t AND principal_subject = :s")
                .param("t", tenantId)
                .param("s", subject)
                .query(UUID.class)
                .single();
    }

    private void seed(UUID tenantId, UUID brandId, UUID locationId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", brandId).param("t", tenantId).update();
        seedLocation(tenantId, brandId, locationId, "L1", slug + "-l1");
    }

    private void seedLocation(UUID tenantId, UUID brandId, UUID locationId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, :code, :slug, :code, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'contact persons endpoint test', :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
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
