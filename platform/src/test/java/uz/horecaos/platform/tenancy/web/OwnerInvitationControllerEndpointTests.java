package uz.horecaos.platform.tenancy.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts;
import uz.horecaos.platform.iam.api.accounts.StaffAccounts.StaffAccount;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0100's cross-tenant overview over HTTP, and what each caller is allowed
 * to read off it (ADR 0025, ADR 0029).
 *
 * <p>Two callers carry the argument. A platform administrator holds
 * {@code tenant.onboarding.manage} at platform scope — the capability that
 * typed these addresses into onboarding — and gets the list with the addresses
 * whole, leaving one reveal fact behind. Platform support holds
 * {@code tenant.read} and nothing else: it is refused the overview outright,
 * and on the tenant's own panel, which it may read, every address is masked.
 * A role that can read a screen is not a role that may read the people on it.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OwnerInvitationControllerEndpointTests {

    private static final UUID WAITING = UUID.fromString("018f9a10-3000-7000-8000-0000000000a1");
    private static final UUID ACCEPTED = UUID.fromString("018f9a10-3000-7000-8000-0000000000a2");

    private static final String ADMIN = "owner-invitation-platform-admin";
    private static final String SUPPORT = "owner-invitation-platform-support";

    /** Neither platform role: two tenant-scoped grants, one per tenant. */
    private static final String TENANT_OPERATOR = "owner-invitation-tenant-operator";

    private static final String OVERVIEW = "/api/v1/control-plane/owner-invitations";

    // NullAway does not recognise @DynamicPropertySource as a field initializer the way
    // it does @BeforeAll/@BeforeEach; `db` is always set there before any @Test method runs.
    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the owner invitation endpoint test");
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

        tenant(WAITING, "waiting-kafe", "Waiting Kafe", "PROVISIONING");
        tenant(ACCEPTED, "accepted-osh", "Accepted Osh", "ACTIVE");
        invitation(WAITING, "waiting-owner", "SENT");
        invitation(ACCEPTED, "accepted-owner", "ACCEPTED");

        grantPlatform(ADMIN, PlatformRole.PLATFORM_ADMIN);
        grantPlatform(SUPPORT, PlatformRole.PLATFORM_SUPPORT);
    }

    @Test
    @DisplayName("the onboarding capability gets every tenant with the addresses whole, and leaves a reveal fact")
    void anOnboardingOperatorSeesTheWholeList() throws Exception {
        MvcResult listed = mvc.perform(get(OVERVIEW).with(tokenFor(ADMIN))).andReturn();

        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        String body = listed.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"tenantSlug\":\"waiting-kafe\"")
                .contains("\"tenantSlug\":\"accepted-osh\"")
                .contains("\"recipient\":\"dilnoza.karimova@example.uz\"")
                .contains("\"emailMasked\":\"d***a@example.uz\"");

        assertThat(jdbc.sql("""
                                SELECT reason FROM audit.audit_events
                                 WHERE action_code = 'tenant.owner_invitation.recipient_revealed'
                                """).query(String.class).list())
                .as("one fact per screen load, naming the purpose and not a person")
                .containsExactly("tenancy.onboarding.invitation.recipient");
        assertThat(jdbc.sql("""
                                SELECT change_document::text FROM audit.audit_events
                                 WHERE action_code = 'tenant.owner_invitation.recipient_revealed'
                                """).query(String.class).single())
                .contains("\"revealedCount\": 2")
                .doesNotContain("example.uz");
        assertThat(jdbc.sql("""
                                SELECT capability_used || '|' || scope_type || '|' || coalesce(scope_id::text, 'none')
                                  FROM audit.audit_events
                                 WHERE action_code = 'tenant.owner_invitation.recipient_revealed'
                                """).query(String.class).single())
                .as("a reveal that does not say under which capability, or where, attributes nothing")
                .isEqualTo(Capability.TENANT_ONBOARDING_MANAGE.code() + "|PLATFORM|none");
    }

    /**
     * The reveal on the tenant's own panel, which the mask test below cannot
     * reach: it is the direction ADR 0100's exit criterion turns on, and the
     * only assertion that pins {@code OwnerInvitationView}'s serialized shape
     * and the tenant-scope authorization behind it at the same time.
     */
    @Test
    @DisplayName("the onboarding capability reads the whole address off the tenant's own panel, and leaves a fact")
    void theOnboardingCapabilitySeesTheWholeAddressOnThePanel() throws Exception {
        MvcResult panel = mvc.perform(get("/api/v1/control-plane/tenants/" + WAITING + "/owner-invitation")
                        .with(tokenFor(ADMIN)))
                .andReturn();

        assertThat(panel.getResponse().getStatus()).isEqualTo(200);
        assertThat(panel.getResponse().getContentAsString())
                .contains("\"recipient\":\"dilnoza.karimova@example.uz\"")
                .as("the mask stays alongside it")
                .contains("\"emailMasked\":\"d***a@example.uz\"");

        assertThat(jdbc.sql("""
                                SELECT change_document::text FROM audit.audit_events
                                 WHERE action_code = 'tenant.owner_invitation.recipient_revealed'
                                """).query(String.class).list())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("\"revealedCount\": 1")
                .doesNotContain("example.uz");
        assertThat(jdbc.sql("""
                                SELECT capability_used || '|' || scope_type || '|' || coalesce(scope_id::text, 'none')
                                  FROM audit.audit_events
                                 WHERE action_code = 'tenant.owner_invitation.recipient_revealed'
                                """).query(String.class).single())
                .as("recorded on the tenant, so an auditor asking who read this tenant's owner address finds it")
                .isEqualTo(Capability.TENANT_ONBOARDING_MANAGE.code() + "|TENANT|" + WAITING);
    }

    /**
     * The gate is per tenant, not per capability. One principal, two
     * tenant-scoped grants: an administrator's on the tenant it is onboarding,
     * and a finance role's -- which reads tenants and never invitations -- on
     * the other.
     */
    @Test
    @DisplayName("a tenant-scoped operator reads the address on the tenant it holds it for, and the mask elsewhere")
    void aTenantScopedOperatorSeesOneAddressAndNotTheOther() throws Exception {
        grantTenant(TENANT_OPERATOR, PlatformRole.TENANT_ADMIN, WAITING);
        grantTenant(TENANT_OPERATOR, PlatformRole.TENANT_FINANCE, ACCEPTED);

        MvcResult here = mvc.perform(get("/api/v1/control-plane/tenants/" + WAITING + "/owner-invitation")
                        .with(tokenFor(TENANT_OPERATOR)))
                .andReturn();
        assertThat(here.getResponse().getStatus()).isEqualTo(200);
        assertThat(here.getResponse().getContentAsString()).contains("\"recipient\":\"dilnoza.karimova@example.uz\"");

        MvcResult elsewhere = mvc.perform(get("/api/v1/control-plane/tenants/" + ACCEPTED + "/owner-invitation")
                        .with(tokenFor(TENANT_OPERATOR)))
                .andReturn();
        assertThat(elsewhere.getResponse().getStatus()).isEqualTo(200);
        assertThat(elsewhere.getResponse().getContentAsString())
                .as("reading a tenant is not reading its owner, one tenant at a time")
                .contains("\"emailMasked\":\"a***r@example.uz\"")
                .doesNotContain("\"recipient\":\"anvar@example.uz\"");
    }

    /**
     * ADR 0100 decision 6: the tenant directory marks which owners are not set
     * up and renders no address, so it reads a projection that has none to
     * render. The assertion that matters is the absence of a reveal fact --
     * a directory page view that recorded one would dilute the count until
     * "who has seen this owner's address" could not be answered at all.
     */
    @Test
    @DisplayName("the address-free projection carries states only, and records no reveal")
    void theWaitingProjectionRevealsNothing() throws Exception {
        MvcResult waiting =
                mvc.perform(get(OVERVIEW + "/waiting").with(tokenFor(ADMIN))).andReturn();

        assertThat(waiting.getResponse().getStatus()).isEqualTo(200);
        assertThat(waiting.getResponse().getContentAsString())
                .contains("\"tenantId\":\"" + WAITING + "\"")
                .contains("\"state\":\"SENT\"")
                .contains("\"state\":\"ACCEPTED\"")
                .as("no address reaches the browser for a column that renders a marker")
                .doesNotContain("example.uz");

        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code LIKE '%recipient_revealed'")
                        .query(Long.class)
                        .single())
                .as("nothing was revealed, so nothing is recorded")
                .isZero();

        assertThat(mvc.perform(get(OVERVIEW + "/waiting").with(tokenFor(SUPPORT)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("the same gate as the overview it replaces on that screen")
                .isEqualTo(403);
    }

    @Test
    @DisplayName("the state filter answers the question the screen is opened with")
    void theStateFilterNarrowsTheList() throws Exception {
        MvcResult outstanding = mvc.perform(
                        get(OVERVIEW).queryParam("state", "OUTSTANDING").with(tokenFor(ADMIN)))
                .andReturn();

        assertThat(outstanding.getResponse().getStatus()).isEqualTo(200);
        assertThat(outstanding.getResponse().getContentAsString())
                .contains("waiting-kafe")
                .doesNotContain("accepted-osh");
    }

    @Test
    @DisplayName("reading a tenant is not reading its owner: support is refused the overview")
    void platformSupportIsRefusedTheOverview() throws Exception {
        MvcResult refused = mvc.perform(get(OVERVIEW).with(tokenFor(SUPPORT))).andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.TENANT_ONBOARDING_MANAGE.code());
        assertThat(jdbc.sql("SELECT count(*) FROM audit.audit_events WHERE action_code LIKE '%recipient_revealed'")
                        .query(Long.class)
                        .single())
                .as("a refused caller reveals nothing, so records nothing")
                .isZero();
    }

    @Test
    @DisplayName("the tenant's own panel carries the timeline, and masks the address from a reader")
    void theTenantPanelIsReadableWithTheAddressMasked() throws Exception {
        MvcResult panel = mvc.perform(get("/api/v1/control-plane/tenants/" + WAITING + "/owner-invitation")
                        .with(tokenFor(SUPPORT)))
                .andReturn();

        assertThat(panel.getResponse().getStatus()).isEqualTo(200);
        assertThat(panel.getResponse().getContentAsString())
                .as("ADR 0097's mask is what a caller without the onboarding capability keeps")
                .contains("\"emailMasked\":\"d***a@example.uz\"")
                .doesNotContain("dilnoza.karimova@example.uz")
                .contains("\"timeline\":[")
                .contains("\"type\":\"SENT\"");
    }

    @Test
    @DisplayName("an anonymous caller gets 401, not a list of tenant owners")
    void theOverviewIsNotPublic() throws Exception {
        assertThat(mvc.perform(get(OVERVIEW)).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    // ------------------------------------------------------------- fixtures

    private void tenant(UUID id, String slug, String displayName, String status) {
        jdbc.sql("""
                        INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                            default_timezone, status, version)
                        VALUES (:id, :slug, :name, :name, 'UZS', 'Asia/Tashkent', :status, 0)
                        """)
                .param("id", id)
                .param("slug", slug)
                .param("name", displayName)
                .param("status", status)
                .update();
    }

    /**
     * An invitation and the history of it, written directly: this suite is
     * about who may read them over HTTP, and {@code OwnerInvitationFlowTests}
     * is about how they come to be written.
     */
    private void invitation(UUID tenantId, String subjectId, String status) {
        UUID id = Ids.newId();
        boolean sent = "SENT".equals(status);
        OffsetDateTime queuedAt = Instant.now().minus(Duration.ofHours(2)).atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO tenant.owner_invitations (id, tenant_id, subject_id, locale, status,
                            token_hash, expires_at, attempts, queued_by, queued_at, sent_at, accepted_at, version)
                        VALUES (:id, :tenantId, :subjectId, 'ru', :status, :tokenHash, :expiresAt, 1,
                            'test-fixture', :queuedAt, :sentAt, :acceptedAt, 0)
                        """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("subjectId", subjectId)
                .param("status", status)
                // A SENT invitation must carry a live link hash and an expiry; an accepted
                // one must carry neither. The table's own CHECK constraints say so, which is
                // why this fixture cannot just write the status it wants.
                .param("tokenHash", sent ? "a".repeat(64) : null)
                .param(
                        "expiresAt",
                        sent ? Instant.now().plus(Duration.ofHours(70)).atOffset(ZoneOffset.UTC) : null)
                .param("queuedAt", queuedAt)
                .param("sentAt", queuedAt)
                .param("acceptedAt", "ACCEPTED".equals(status) ? queuedAt : null)
                .update();
        event(tenantId, id, "QUEUED", 0, queuedAt);
        event(tenantId, id, "SENT", 1, queuedAt);
    }

    private void event(UUID tenantId, UUID invitationId, String type, int attempt, OffsetDateTime at) {
        jdbc.sql("""
                        INSERT INTO tenant.owner_invitation_events (id, tenant_id, invitation_id, event_type,
                            attempt, locale, actor_type, actor_reference, occurred_at)
                        VALUES (:id, :tenantId, :invitationId, :type, :attempt, 'ru', 'SYSTEM_JOB',
                            'owner-invitation-relay', :at)
                        """)
                .param("id", Ids.newId())
                .param("tenantId", tenantId)
                .param("invitationId", invitationId)
                .param("type", type)
                .param("attempt", attempt)
                .param("at", at)
                .update();
    }

    private void grantPlatform(String subject, PlatformRole role) {
        jdbc.sql("""
                        INSERT INTO iam.grants
                            (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                             status, granted_by, reason, valid_from)
                        VALUES (:id, NULL, :subject, :roleId, true, 'PLATFORM', NULL,
                                'ACTIVE', 'test-fixture', 'owner invitation endpoint test', :validFrom)
                        ON CONFLICT DO NOTHING
                        """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                // Backdated rather than the column's own now(): a grant read back through
                // JdbcAuthorizationService.grantsFor compares valid_from against this JVM's
                // Clock.systemUTC(), and under concurrent fork load the container's own
                // wall clock can momentarily skew against it.
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    /**
     * A grant on one tenant. Unlike {@link #grantPlatform}, the table insists a
     * non-platform scope carries both {@code scope_id} and {@code tenant_id}
     * (V0008's {@code ck_grant_scope_id}), which is why this cannot reuse it.
     */
    private void grantTenant(String subject, PlatformRole role, UUID tenantId) {
        jdbc.sql("""
                        INSERT INTO iam.grants
                            (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                             status, granted_by, reason, valid_from)
                        VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                                'ACTIVE', 'test-fixture', 'owner invitation endpoint test', :validFrom)
                        ON CONFLICT DO NOTHING
                        """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + tenantId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    /** Carries no realm role, so the ADR 0025 grant decides and not a bootstrap bypass. */
    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Stubs {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }

        /**
         * The addresses live in Keycloak and nowhere the platform owns, so this
         * suite has to stand one up; that is the ADR 0097 storage decision this
         * wave deliberately did not change.
         */
        @Bean
        @Primary
        StaffAccounts stubStaffAccounts() {
            Map<String, StaffAccount> accounts = new HashMap<>();
            accounts.put(
                    "waiting-owner", new StaffAccount("waiting-owner", "dilnoza.karimova@example.uz", false, false));
            accounts.put("accepted-owner", new StaffAccount("accepted-owner", "anvar@example.uz", true, true));
            return new StaffAccounts() {
                @Override
                public Optional<StaffAccount> find(String subjectId) {
                    return Optional.ofNullable(accounts.get(subjectId));
                }

                @Override
                public void completeSetup(String subjectId, String firstName, String lastName, String password) {
                    throw new UnsupportedOperationException("nothing under test sets a password");
                }
            };
        }
    }
}
