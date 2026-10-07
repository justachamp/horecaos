package uz.horecaos.platform.iam.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.organizations.OrganizationProvisioner;
import uz.horecaos.platform.iam.infrastructure.authorization.JdbcAuthorizationService;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.mail.api.MailOutcome;
import uz.horecaos.platform.mail.api.OutgoingMail;
import uz.horecaos.platform.mail.api.PlatformMailer;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.idempotency.IdempotencyInterceptor;

/**
 * ADR 0103's exit criterion, over HTTP and with real grants: the Chilonzor
 * manager opens her own branch's team, hands a new hire a job, and cannot reach
 * anything her grant does not cover.
 *
 * <p>Every principal here holds the platform's own {@link PlatformRole} bundle
 * through a real {@code iam.grants} row -- nothing is a hand-built role -- so the
 * test fails if {@code location-manager} stops carrying {@code iam.grant.manage}
 * or if the route's declared scope stops being the one her grant covers.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScopedGrantControllerEndpointTests {

    private static final UUID TENANT = UUID.fromString("018f9e10-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9e10-4000-7000-8000-0000000000b1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9e10-4000-7000-8000-0000000000b2");
    private static final UUID CHILONZOR = UUID.fromString("018f9e10-4000-7000-8000-0000000000c1");
    private static final UUID YUNUSOBOD = UUID.fromString("018f9e10-4000-7000-8000-0000000000c2");
    private static final UUID OTHER_BRAND_BRANCH = UUID.fromString("018f9e10-4000-7000-8000-0000000000c3");
    private static final String ORGANIZATION_ID = "org-scoped-grants-1";

    private static final String OWNER = "scoped-owner";
    private static final String CHILONZOR_MANAGER = "chilonzor-manager";
    private static final String BRAND_MANAGER = "brand-a-manager";
    private static final String CHILONZOR_COOK = "chilonzor-cook";
    private static final String YUNUSOBOD_COOK = "yunusobod-cook";
    private static final String NO_JOB = "no-job-at-all";

    private static final AtomicInteger KEYS = new AtomicInteger();

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
        // The invite response carries a @Classified link, which the idempotency record encrypts.
        registry.add("horecaos.secrets.data_encryption.platform.kek", () -> "a-test-key-encryption-key");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @Autowired
    private JdbcAuthorizationService authorization;

    @Autowired
    private StubBeans.FakeStaffAccounts accounts;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE tenant.staff_invitations").update();
        jdbc.sql("TRUNCATE TABLE iam.grants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        roleRegistry.synchronize();
        accounts.created.clear();
        accounts.byId.clear();

        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status,
                     keycloak_organization_id, version)
                VALUES (:id, 'scoped-grants-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE',
                        :orgId, 0)
                """).param("id", TENANT).param("orgId", ORGANIZATION_ID).update();
        brand(BRAND, "MAIN", "Main brand");
        brand(OTHER_BRAND, "SECOND", "Second brand");
        location(CHILONZOR, BRAND, "CHI", "Chilonzor");
        location(YUNUSOBOD, BRAND, "YUN", "Yunusobod");
        location(OTHER_BRAND_BRANCH, OTHER_BRAND, "OTH", "Other brand branch");

        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT);
        grant(CHILONZOR_MANAGER, PlatformRole.LOCATION_MANAGER, "LOCATION", CHILONZOR);
        grant(BRAND_MANAGER, PlatformRole.BRAND_MANAGER, "BRAND", BRAND);
        grant(CHILONZOR_COOK, PlatformRole.LOCATION_STAFF, "LOCATION", CHILONZOR);
        grant(YUNUSOBOD_COOK, PlatformRole.LOCATION_STAFF, "LOCATION", YUNUSOBOD);
        // The grant cache is keyed by subject and tenant and these rows went in behind its back.
        List.of(OWNER, CHILONZOR_MANAGER, BRAND_MANAGER, CHILONZOR_COOK, YUNUSOBOD_COOK)
                .forEach(subject -> authorization.evictGrants(subject, TENANT));
    }

    // ------------------------------------------------------------------ the team view

    @Test
    void theChilonzorManagerSeesChilonzorsTeamAndNobodyElses() throws Exception {
        MvcResult team = mvc.perform(get(chilonzor("/grants")).with(tokenFor(CHILONZOR_MANAGER)))
                .andReturn();

        assertThat(team.getResponse().getStatus()).isEqualTo(200);
        String body = team.getResponse().getContentAsString();
        assertThat(body)
                .contains(CHILONZOR_COOK)
                .contains(CHILONZOR_MANAGER)
                .as("not Yunusobod's cook, not the brand manager, not the owner")
                .doesNotContain(YUNUSOBOD_COOK)
                .doesNotContain(BRAND_MANAGER)
                .doesNotContain(OWNER);
    }

    @Test
    void theSessionContextOffersHerStaffSectionNow() throws Exception {
        MvcResult context = mvc.perform(get("/api/v1/session/context")
                        .param("tenantId", TENANT.toString())
                        .with(tokenFor(CHILONZOR_MANAGER)))
                .andReturn();

        assertThat(context.getResponse().getContentAsString())
                .as("the console's route guard reads this to admit her to /staff instead of redirecting her; "
                        + "the wire carries the enum name, which navigation.ts also names")
                .contains("\"" + Capability.IAM_GRANT_MANAGE.name() + "\"");
    }

    @Test
    void theChilonzorManagerCannotReadTheCompanyWideRoutes() throws Exception {
        MvcResult tenantWide = mvc.perform(get("/api/v1/control-plane/tenants/" + TENANT + "/grants")
                        .with(tokenFor(CHILONZOR_MANAGER)))
                .andReturn();

        assertThat(tenantWide.getResponse().getStatus())
                .as("her grant stops at the branch: the company-wide list is still the owner's")
                .isEqualTo(403);
        assertThat(tenantWide.getResponse().getContentAsString()).contains("INSUFFICIENT_CAPABILITY");
    }

    @Test
    void theChilonzorManagerCannotReadASiblingBranchesTeam() throws Exception {
        MvcResult sibling = mvc.perform(get(location(YUNUSOBOD, "/grants")).with(tokenFor(CHILONZOR_MANAGER)))
                .andReturn();

        assertThat(sibling.getResponse().getStatus()).isEqualTo(403);
        assertThat(sibling.getResponse().getContentAsString()).doesNotContain(YUNUSOBOD_COOK);
    }

    @Test
    void aCookCannotReadAnyTeamAtAll() throws Exception {
        MvcResult refused = mvc.perform(get(chilonzor("/grants")).with(tokenFor(CHILONZOR_COOK)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.IAM_GRANT_MANAGE.code());
    }

    @Test
    void theBrandManagerSeesHerBrandsWholeTeamButNotTheCompanysOrAnotherBrands() throws Exception {
        grant("second-brand-manager", PlatformRole.BRAND_MANAGER, "BRAND", OTHER_BRAND);

        MvcResult team =
                mvc.perform(get(brand("/grants")).with(tokenFor(BRAND_MANAGER))).andReturn();

        assertThat(team.getResponse().getStatus()).isEqualTo(200);
        assertThat(team.getResponse().getContentAsString())
                .contains(CHILONZOR_COOK)
                .contains(YUNUSOBOD_COOK)
                .contains(CHILONZOR_MANAGER)
                .contains(BRAND_MANAGER)
                .doesNotContain(OWNER)
                .doesNotContain("second-brand-manager");
    }

    @Test
    void theBranchManagerReadsTheNamesOfHerOwnPlacesAndNoOtherBranchesNames() throws Exception {
        MvcResult places = mvc.perform(get(chilonzor("/grant-places")).with(tokenFor(CHILONZOR_MANAGER)))
                .andReturn();

        assertThat(places.getResponse().getContentAsString())
                .contains("Chilonzor")
                .contains("Main brand")
                .doesNotContain("Yunusobod")
                .doesNotContain("Other brand branch");
    }

    // ------------------------------------------------------------------ giving a job

    @Test
    void theChilonzorManagerHandsANewHireTheLineCooksJob() throws Exception {
        MvcResult granted = mvc.perform(post(chilonzor("/grants"))
                        .with(tokenFor(CHILONZOR_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principalSubject":"new-hire","roleCode":"location-staff",
                                 "reason":"Hired this morning"}
                                """))
                .andReturn();

        assertThat(granted.getResponse().getStatus()).isEqualTo(200);
        assertThat(granted.getResponse().getContentAsString()).contains("grantId");
        authorization.evictGrants("new-hire", TENANT);
        assertThat(authorization.has(
                        "new-hire",
                        Capability.KITCHEN_TICKET_ADVANCE,
                        ResourceScope.location(TENANT, BRAND, CHILONZOR)))
                .isTrue();
        assertThat(authorization.has(
                        "new-hire",
                        Capability.KITCHEN_TICKET_ADVANCE,
                        ResourceScope.location(TENANT, BRAND, YUNUSOBOD)))
                .as("at Chilonzor and nowhere else")
                .isFalse();
        assertThat(jdbc.sql("""
                SELECT count(*) FROM audit.audit_events
                 WHERE action_code = 'iam.grant.granted' AND actor_subject = :manager
                   AND target_id IS NOT NULL
                """)
                        .param("manager", CHILONZOR_MANAGER)
                        .query(Long.class)
                        .single())
                .as("the ordinary audited path, with her as the actor")
                .isEqualTo(1L);
    }

    @Test
    void theChilonzorManagerCannotGiveTheOwnersJob() throws Exception {
        MvcResult refused = mvc.perform(post(chilonzor("/grants"))
                        .with(tokenFor(CHILONZOR_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principalSubject":"accomplice","roleCode":"tenant-owner",
                                 "reason":"escalation attempt"}
                                """))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM iam.grants WHERE principal_subject = 'accomplice'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void theChilonzorManagerCannotGiveAJobAtASiblingBranchByNamingItInThePath() throws Exception {
        MvcResult refused = mvc.perform(post(location(YUNUSOBOD, "/grants"))
                        .with(tokenFor(CHILONZOR_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principalSubject":"new-hire","roleCode":"location-staff","reason":"sideways"}
                                """))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
    }

    @Test
    void theBrandManagerCannotNameAnotherBrandsBranchOnAGrant() throws Exception {
        MvcResult refused = mvc.perform(post(brand("/grants"))
                        .with(tokenFor(BRAND_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principalSubject":"someone","roleCode":"brand-manager",
                                 "locationId":"%s","reason":"naming a branch that is not hers"}
                                """.formatted(OTHER_BRAND_BRANCH)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM iam.grants WHERE principal_subject = 'someone'")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    void theBrandManagerGivesTheJobHerBundleHoldsAtOneOfHerBranches() throws Exception {
        MvcResult granted = mvc.perform(post(brand("/grants"))
                        .with(tokenFor(BRAND_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"principalSubject":"deputy","roleCode":"brand-manager",
                                 "reason":"A deputy for the brand"}
                                """))
                .andReturn();

        assertThat(granted.getResponse().getStatus()).isEqualTo(200);
    }

    // ------------------------------------------------------------------ the jobs on offer

    @Test
    void thePickerOffersTheChilonzorManagerExactlyTheJobsTheGrantRouteAccepts() throws Exception {
        MvcResult roles = mvc.perform(get(chilonzor("/grant-roles")).with(tokenFor(CHILONZOR_MANAGER)))
                .andReturn();

        String body = roles.getResponse().getContentAsString();
        assertThat(roles.getResponse().getStatus()).isEqualTo(200);
        assertThat(body).contains("\"code\":\"location-staff\"");
        assertThat(grantableCodes(body)).containsExactlyInAnyOrder("location-manager", "location-staff");
    }

    // ------------------------------------------------------------------ taking a job away

    @Test
    void theChilonzorManagerTakesAwayACooksJobAtHerBranch() throws Exception {
        UUID cookGrant = grantIdOf(CHILONZOR_COOK);

        MvcResult revoked = mvc.perform(delete(chilonzor("/grants/" + cookGrant))
                        .with(tokenFor(CHILONZOR_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Left the branch\"}"))
                .andReturn();

        assertThat(revoked.getResponse().getStatus()).isEqualTo(200);
        assertThat(revoked.getResponse().getContentAsString()).contains("\"changed\":true");
        authorization.evictGrants(CHILONZOR_COOK, TENANT);
        assertThat(authorization.has(
                        CHILONZOR_COOK, Capability.ORDER_APPROVE, ResourceScope.location(TENANT, BRAND, CHILONZOR)))
                .isFalse();
    }

    @Test
    void aGrantIdFromElsewhereIsNotRevocableFromChilonzorsRoute() throws Exception {
        for (String elsewhere : List.of(YUNUSOBOD_COOK, BRAND_MANAGER, OWNER)) {
            UUID grantId = grantIdOf(elsewhere);

            MvcResult revoked = mvc.perform(delete(chilonzor("/grants/" + grantId))
                            .with(tokenFor(CHILONZOR_MANAGER))
                            .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"using an id I saw in a ticket\"}"))
                    .andReturn();

            assertThat(revoked.getResponse().getContentAsString())
                    .as("%s's grant lies outside Chilonzor: answered as if it did not exist", elsewhere)
                    .contains("\"changed\":false");
            assertThat(jdbc.sql("SELECT status FROM iam.grants WHERE id = :id")
                            .param("id", grantId)
                            .query(String.class)
                            .single())
                    .isEqualTo("ACTIVE");
        }
    }

    // ------------------------------------------------------------------ inviting a new hire

    @Test
    void theChilonzorManagerInvitesANewHireIntoHerBranchAndTheJobIsHersToGive() throws Exception {
        MvcResult invited = mvc.perform(post(chilonzor("/staff/invitations"))
                        .with(tokenFor(CHILONZOR_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"firstName":"Aziza","lastName":"Karimova","phone":"+998901234567",
                                 "roleCode":"location-staff","reason":"new hire"}
                                """.replace("\n", "")))
                .andReturn();

        assertThat(invited.getResponse().getStatus()).isEqualTo(200);
        assertThat(accounts.created).hasSize(1);
        assertThat(jdbc.sql("""
                SELECT g.scope_type || ':' || g.scope_id FROM iam.grants g
                 WHERE g.principal_subject = :subject
                """)
                        .param("subject", accounts.created.getFirst())
                        .query(String.class)
                        .single())
                .as("the job is at Chilonzor, the path's place, whatever the body might have said")
                .isEqualTo("LOCATION:" + CHILONZOR);
    }

    @Test
    void theChilonzorManagerCannotInviteAnOwnerNorIntoASiblingBranch() throws Exception {
        String body = """
                {"firstName":"Aziza","lastName":"Karimova","phone":"+998901234567",
                 "roleCode":"%s","reason":"new hire"}
                """.replace("\n", "");

        MvcResult owner = mvc.perform(post(chilonzor("/staff/invitations"))
                        .with(tokenFor(CHILONZOR_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("tenant-owner")))
                .andReturn();
        MvcResult sibling = mvc.perform(post(location(YUNUSOBOD, "/staff/invitations"))
                        .with(tokenFor(CHILONZOR_MANAGER))
                        .header(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER, key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.formatted("location-staff")))
                .andReturn();

        assertThat(owner.getResponse().getStatus()).isEqualTo(403);
        assertThat(sibling.getResponse().getStatus()).isEqualTo(403);
        assertThat(jdbc.sql("SELECT count(*) FROM iam.grants WHERE principal_subject LIKE 'endpoint-staff-%'")
                        .query(Long.class)
                        .single())
                .as("no job was written for either attempt")
                .isZero();
    }

    @Test
    void aPersonWithNoJobAtAllIsRefusedEveryRoute() throws Exception {
        assertThat(mvc.perform(get(chilonzor("/grants")).with(tokenFor(NO_JOB)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(mvc.perform(get(brand("/grants")).with(tokenFor(NO_JOB)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
    }

    // ------------------------------------------------------------------ helpers

    private static List<String> grantableCodes(String json) {
        List<String> codes = new ArrayList<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(
                        "\\{\"code\":\"([a-z-]+)\"[^{}]*?\"grantable\":true")
                .matcher(json);
        while (matcher.find()) {
            codes.add(matcher.group(1));
        }
        return codes;
    }

    private static String key() {
        return "scoped-grants-" + KEYS.incrementAndGet();
    }

    private UUID grantIdOf(String subject) {
        return jdbc.sql("SELECT id FROM iam.grants WHERE principal_subject = :subject AND status = 'ACTIVE'")
                .param("subject", subject)
                .query(UUID.class)
                .single();
    }

    private static String chilonzor(String suffix) {
        return location(CHILONZOR, suffix);
    }

    private static String location(UUID location, String suffix) {
        UUID brand = location.equals(OTHER_BRAND_BRANCH) ? OTHER_BRAND : BRAND;
        return "/api/v1/operations/tenants/" + TENANT + "/brands/" + brand + "/locations/" + location + suffix;
    }

    private static String brand(String suffix) {
        return "/api/v1/operations/tenants/" + TENANT + "/brands/" + BRAND + suffix;
    }

    private void brand(UUID id, String code, String name) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, lower(:code), :name, 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("code", code)
                .param("name", name)
                .update();
    }

    private void location(UUID id, UUID brandId, String code, String name) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, lower(:code), :name, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("code", code)
                .param("name", name)
                .update();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'scoped grant endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeType + scopeId).getBytes(UTF_8)))
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
    static class StubBeans {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }

        @Bean
        @Primary
        FakeStaffAccounts stubStaffAccounts() {
            return new FakeStaffAccounts();
        }

        @Bean
        @Primary
        OrganizationProvisioner stubOrganizations() {
            return new OrganizationProvisioner() {
                @Override
                public OrganizationRef ensureOrganization(EnsureOrganization command) {
                    throw new UnsupportedOperationException("not part of this fixture");
                }

                @Override
                public Optional<OrganizationSnapshot> getOrganization(String organizationId) {
                    throw new UnsupportedOperationException("not part of this fixture");
                }

                @Override
                public MembershipRef ensureMembership(EnsureMembership command) {
                    return new MembershipRef(
                            command.organizationId(),
                            java.util.Objects.requireNonNull(command.existingSubjectId()),
                            false);
                }

                @Override
                public void setOrganizationEnabled(String organizationId, boolean enabled) {
                    throw new UnsupportedOperationException("not part of this fixture");
                }
            };
        }

        @Bean
        @Primary
        PlatformMailer stubMailer() {
            return new PlatformMailer() {
                @Override
                public MailOutcome send(OutgoingMail mail) {
                    return new MailOutcome.NotConfigured();
                }

                @Override
                public boolean configured() {
                    return false;
                }
            };
        }

        /** In-memory; no real Keycloak. */
        static class FakeStaffAccounts implements StaffAccounts {

            final Map<String, StaffAccount> byId = new HashMap<>();
            final List<String> created = new ArrayList<>();

            @Override
            public Optional<StaffAccount> find(String subjectId) {
                return Optional.ofNullable(byId.get(subjectId));
            }

            @Override
            public StaffAccount create(String firstName, String lastName, String phone, @Nullable String email) {
                String subjectId = "endpoint-staff-" + UUID.randomUUID();
                StaffAccount account = new StaffAccount(subjectId, email, false, false, phone);
                byId.put(subjectId, account);
                created.add(subjectId);
                return account;
            }

            @Override
            public Optional<StaffAccount> findByPhone(String phone) {
                return byId.values().stream()
                        .filter(account -> phone.equals(account.username()))
                        .findFirst();
            }

            @Override
            public void delete(String subjectId) {
                byId.remove(subjectId);
            }

            @Override
            public void completeSetup(String subjectId, String firstName, String lastName, String password) {
                throw new UnsupportedOperationException("not part of this fixture");
            }

            @Override
            public Optional<StaffAccount> findByLogin(String usernameOrEmail) {
                throw new UnsupportedOperationException("not part of this fixture");
            }

            @Override
            public Optional<String> findSubjectIdByLogin(String usernameOrEmail) {
                throw new UnsupportedOperationException("not part of this fixture");
            }

            @Override
            public void setPassword(String subjectId, String password) {
                throw new UnsupportedOperationException("not part of this fixture");
            }

            @Override
            public void logoutEverywhere(String subjectId) {
                throw new UnsupportedOperationException("not part of this fixture");
            }
        }
    }
}
