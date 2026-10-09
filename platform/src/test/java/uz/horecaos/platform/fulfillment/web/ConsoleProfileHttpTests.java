package uz.horecaos.platform.fulfillment.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.application.ModuleCatalogService;
import uz.horecaos.platform.commercial.application.PlanCatalogService;
import uz.horecaos.platform.commercial.application.SubscriptionService;
import uz.horecaos.platform.commercial.domain.BillingUnit;
import uz.horecaos.platform.commercial.domain.SubscriptionStatus;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0164, gap map row {@code 3.1b}: how the operations console opens for a tenant, through the real
 * HTTP stack and a real PostgreSQL.
 *
 * <p>The profile is a presentation of the one console, carried by a purchased module and not by the
 * tenant's business type. What this proves is each half of that sentence: the {@code deliveries-console}
 * module is what changes the answer, the business type changes nothing, a subscription that no longer
 * grants entitlements takes the profile back, and the read serves the principal the console exists for,
 * a location dispatcher whose only grant is at one branch (the case the tenant-wide flags endpoint
 * fails).
 */
@SpringBootTest
@AutoConfigureMockMvc
class ConsoleProfileHttpTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID TENANT = UUID.fromString("018fe200-4000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018fe200-4000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018fe200-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fe200-4000-7000-8000-0000000000c1");

    private static final ActorRef AUTHOR = ActorRef.user("console-profile-author", null);
    private static final ActorRef APPROVER = ActorRef.user("console-profile-approver", null);

    private static final String OWNER = "console-profile-owner";
    private static final String DISPATCHER = "console-profile-dispatcher";
    private static final String OUTSIDER = "console-profile-outsider";
    private static final String OTHER_OWNER = "console-profile-other-owner";
    private static final String PLATFORM_ADMIN = "console-profile-platform-admin";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the console profile HTTP test");
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
    private PlanCatalogService plans;

    @Autowired
    @SuppressWarnings("NullAway")
    private SubscriptionService subscriptions;

    @Autowired
    @SuppressWarnings("NullAway")
    private ModuleCatalogService modules;

    private UUID deliveriesConsoleModule;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE commercial.tenant_modules CASCADE").update();
        jdbc.sql("TRUNCATE TABLE commercial.modules CASCADE").update();
        jdbc.sql("TRUNCATE TABLE commercial.subscriptions CASCADE").update();
        jdbc.sql("TRUNCATE TABLE commercial.plan_entitlements, commercial.plan_versions, commercial.plans CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        insertTenant(TENANT, "console-profile-tenant");
        insertTenant(OTHER_TENANT, "console-profile-other");
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();

        grant(OWNER, PlatformRole.TENANT_OWNER, "TENANT", TENANT, TENANT);
        grant(DISPATCHER, PlatformRole.COURIER_DISPATCHER, "LOCATION", LOCATION, TENANT);
        grant(OTHER_OWNER, PlatformRole.TENANT_OWNER, "TENANT", OTHER_TENANT, OTHER_TENANT);
        grant(PLATFORM_ADMIN, PlatformRole.PLATFORM_ADMIN, "PLATFORM", null, null);

        UUID planId = plans.createPlan("PROFILE", "Profile plan", AUTHOR, "the price list", "corr");
        UUID versionId = plans.draftVersion(
                planId, "UZS", 1_200_000, "MONTHLY", null, Map.of(), AUTHOR, "the price list", "corr");
        plans.activate(versionId, APPROVER, "signed off", "corr");
        subscriptions.start(TENANT, versionId, null, AUTHOR, "pilot", "corr");
        subscriptions.start(OTHER_TENANT, versionId, null, AUTHOR, "pilot", "corr");

        // The module as finance drafts it and a second person activates it: price 0, so that
        // activating it invents no price (ADR 0164, open input «The module's commercial terms»).
        deliveriesConsoleModule = modules.draft(
                "deliveries-console",
                "Deliveries console",
                "Changes how the console opens, and can be ended.",
                BillingUnit.PER_TENANT,
                "UZS",
                0,
                List.of(EntitlementKeys.DELIVERY_DELIVERIES_CONSOLE_ENABLED.code()),
                AUTHOR,
                "new shape",
                "corr");
        modules.activate(deliveriesConsoleModule, APPROVER, "signed off", "corr");
    }

    // ----------------------------------------------------------------- the profile

    @Test
    @DisplayName("a tenant that has bought nothing keeps the standard console it always had")
    void standardByDefault() throws Exception {
        JsonNode profile = profile(TENANT, OWNER);

        assertThat(profile.path("variant").asText()).isEqualTo("STANDARD");
        assertThat(profile.path("landing").asText()).isEqualTo("/today");
        assertThat(profile.path("hiddenSections")).isEmpty();
        assertThat(texts(profile.path("handovers"))).containsExactlyInAnyOrder("DELIVERY", "PICKUP", "DINE_IN");
    }

    @Test
    @DisplayName("a tenant given the deliveries-console module opens on deliveries with the restaurant sections "
            + "put away, and ending the module takes the profile back")
    void theModuleIsWhatChangesTheAnswer() throws Exception {
        MvcResult before = read(TENANT, OWNER);
        String standardEtag = before.getResponse().getHeader(HttpHeaders.ETAG);

        UUID tenantModule =
                modules.purchase(TENANT, deliveriesConsoleModule, null, APPROVER, "owner bought it", "corr");

        MvcResult after = read(TENANT, OWNER);
        JsonNode profile = JSON.readTree(after.getResponse().getContentAsString());
        assertThat(profile.path("variant").asText()).isEqualTo("DELIVERIES");
        assertThat(profile.path("landing").asText()).isEqualTo("/deliveries");
        assertThat(texts(profile.path("hiddenSections"))).containsExactly("/kitchen", "/marketing", "/inbox");
        assertThat(texts(profile.path("handovers"))).containsExactly("DELIVERY");
        assertThat(after.getResponse().getHeader(HttpHeaders.ETAG))
                .as("the ETag is the entitlement snapshot's version: it moves when the profile does")
                .isNotNull()
                .isNotEqualTo(standardEtag);

        modules.end(TENANT, tenantModule, APPROVER, "ended by the owner", "corr");

        JsonNode ended = profile(TENANT, OWNER);
        assertThat(ended.path("variant").asText()).isEqualTo("STANDARD");
        assertThat(ended.path("landing").asText()).isEqualTo("/today");
    }

    @Test
    @DisplayName("a subscription that no longer grants entitlements takes the profile back with it (ADR 0087)")
    void aSuspendedSubscriptionResolvesStandard() throws Exception {
        modules.purchase(TENANT, deliveriesConsoleModule, null, APPROVER, "owner bought it", "corr");
        assertThat(profile(TENANT, OWNER).path("variant").asText()).isEqualTo("DELIVERIES");

        long version = subscriptions.live(TENANT).orElseThrow().version();
        subscriptions.transition(
                TENANT, SubscriptionStatus.SUSPENDED, version, "unpaid statement", null, APPROVER, "arrears", "corr");

        assertThat(profile(TENANT, OWNER).path("variant").asText()).isEqualTo("STANDARD");
    }

    @Test
    @DisplayName("the business type decides nothing: COURIER_SERVICE without the module is standard, "
            + "the module on a RESTAURANT is the deliveries profile")
    void theBusinessTypeGrantsNothing() throws Exception {
        setBusinessType(TENANT, "COURIER_SERVICE");
        assertThat(profile(TENANT, OWNER).path("variant").asText()).isEqualTo("STANDARD");

        setBusinessType(TENANT, "RESTAURANT");
        modules.purchase(TENANT, deliveriesConsoleModule, null, APPROVER, "owner bought it", "corr");
        assertThat(profile(TENANT, OWNER).path("variant").asText()).isEqualTo("DELIVERIES");
    }

    // ----------------------------------------------------------------- who may ask

    @Test
    @DisplayName("a dispatcher whose only grant is at one branch reads the profile of their tenant, "
            + "and sees the same one the owner does")
    void aBranchGrantIsEnough() throws Exception {
        modules.purchase(TENANT, deliveriesConsoleModule, null, APPROVER, "owner bought it", "corr");

        JsonNode profile = profile(TENANT, DISPATCHER);

        assertThat(profile.path("variant").asText()).isEqualTo("DELIVERIES");
        assertThat(profile.path("landing").asText()).isEqualTo("/deliveries");
    }

    @Test
    @DisplayName("a principal with no grant in the tenant, and another tenant's owner, get 404, "
            + "so the read cannot ask whether a tenant bought a module")
    void noGrantIsNotFound() throws Exception {
        modules.purchase(TENANT, deliveriesConsoleModule, null, APPROVER, "owner bought it", "corr");

        assertThat(read(TENANT, OUTSIDER).getResponse().getStatus()).isEqualTo(404);
        assertThat(read(TENANT, OTHER_OWNER).getResponse().getStatus()).isEqualTo(404);
        assertThat(read(UUID.randomUUID(), OWNER).getResponse().getStatus())
                .as("a tenant that does not exist answers like one the caller has no grant in")
                .isEqualTo(404);
        assertThat(profile(OTHER_TENANT, OTHER_OWNER).path("variant").asText())
                .as("another tenant never inherits this one's module")
                .isEqualTo("STANDARD");
    }

    @Test
    @DisplayName("the profile grants nothing: the hidden sections stay open to a holder of the capability")
    void theProfileIsNeverAnAuthorizationDecision() throws Exception {
        modules.purchase(TENANT, deliveriesConsoleModule, null, APPROVER, "owner bought it", "corr");

        // The kitchen board is a "put away" section for this profile. The server neither knows nor
        // cares: an owner still reads it, and a dispatcher is refused for the reason they always were.
        MvcResult owner = mvc.perform(get("/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + LOCATION
                                + "/kitchen/tickets")
                        .with(tokenFor(OWNER)))
                .andReturn();
        assertThat(owner.getResponse().getStatus()).isNotEqualTo(403);
        MvcResult dispatcher = mvc.perform(get("/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/"
                                + LOCATION + "/kitchen/tickets")
                        .with(tokenFor(DISPATCHER)))
                .andReturn();
        assertThat(dispatcher.getResponse().getStatus()).isEqualTo(403);
    }

    // ----------------------------------------------------------------- the directory note

    @Test
    @DisplayName("the control-plane directory notes COURIER_SERVICE without the module and the module on another "
            + "kind, and says nothing when they match")
    void theDirectoryNotesHalfConfiguredTenants() throws Exception {
        setBusinessType(TENANT, "COURIER_SERVICE");
        assertThat(directoryNote(TENANT)).isEqualTo("COURIER_SERVICE_WITHOUT_DELIVERIES_CONSOLE");

        modules.purchase(TENANT, deliveriesConsoleModule, null, APPROVER, "owner bought it", "corr");
        assertThat(directoryNote(TENANT)).as("type and module agree").isNull();

        setBusinessType(OTHER_TENANT, "RESTAURANT");
        assertThat(directoryNote(OTHER_TENANT)).as("neither").isNull();

        setBusinessType(TENANT, "RESTAURANT");
        assertThat(directoryNote(TENANT)).isEqualTo("DELIVERIES_CONSOLE_WITHOUT_COURIER_SERVICE");
    }

    // ----------------------------------------------------------------- helpers

    private @Nullable String directoryNote(UUID tenantId) throws Exception {
        MvcResult listed = mvc.perform(get("/api/v1/control-plane/tenants").with(platformAdminToken(PLATFORM_ADMIN)))
                .andReturn();
        assertThat(listed.getResponse().getStatus()).isEqualTo(200);
        for (JsonNode item :
                JSON.readTree(listed.getResponse().getContentAsString()).path("items")) {
            if (item.path("id").asText().equals(tenantId.toString())) {
                JsonNode note = item.path("consoleProfileNote");
                return note.isNull() || note.isMissingNode() ? null : note.asText();
            }
        }
        throw new AssertionError("tenant " + tenantId + " is not in the directory");
    }

    private JsonNode profile(UUID tenantId, String subject) throws Exception {
        MvcResult result = read(tenantId, subject);
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private MvcResult read(UUID tenantId, String subject) throws Exception {
        return mvc.perform(get("/api/v1/session/console-profile")
                        .param("tenantId", tenantId.toString())
                        .with(tokenFor(subject)))
                .andReturn();
    }

    private static List<String> texts(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(JsonNode::asText)
                .toList();
    }

    private void setBusinessType(UUID tenantId, String type) {
        jdbc.sql("UPDATE tenant.tenants SET business_type = :type WHERE id = :id")
                .param("type", type)
                .param("id", tenantId)
                .update();
    }

    private void insertTenant(UUID id, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", id).param("slug", slug).update();
    }

    private void grant(
            String subject, PlatformRole role, String scopeType, @Nullable UUID scopeId, @Nullable UUID tenant) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'console profile http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeId).getBytes(UTF_8)))
                .param("tenantId", tenant)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    /** The control-plane directory checks the Keycloak realm role as well as the grant (TenantAccessPolicy). */
    private static RequestPostProcessor platformAdminToken(String subject) {
        return jwt().jwt(builder -> builder.subject(subject)
                .claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of("platform-admin")))));
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }
}
