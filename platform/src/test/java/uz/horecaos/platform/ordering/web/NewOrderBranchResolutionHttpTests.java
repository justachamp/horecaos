package uz.horecaos.platform.ordering.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.jayway.jsonpath.JsonPath;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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

/**
 * Gap map rows {@code 1.3} (cross-branch resolver) and {@code 0.1c} (branch
 * load at the moment of choosing), exercised through the real HTTP stack —
 * {@code OperationsOrderController.resolveBranches}/{@code
 * branchOverrideReasons}, following {@code
 * OperationsOrderControllerActionCapabilitiesHttpTests}' own shape.
 *
 * <p><b>Zone ranking and load</b> are proved against two branches of one
 * brand, each bound to its own {@code DELIVERY} zone covering the same test
 * point at a different priority — {@code BRANCH_HIGH_PRIORITY}'s zone
 * outranks {@code BRANCH_LOW_PRIORITY}'s, the identical priority-descending
 * order {@code ZoneCandidate#RANKING} (ADR 0037) already applies for a single
 * branch's own fee resolution. Rows are inserted directly against {@code
 * fulfillment.service_zone_versions}/{@code zone_location_bindings} rather
 * than authored through {@code ServiceZoneService}, because that service
 * needs a request-scoped {@code CurrentActor} this test's hand-built MockMvc
 * calls do not carry for a non-HTTP call — the same reason every other
 * `ordering.web` HTTP test in this package seeds its fixture rows directly
 * rather than through the services under test.
 *
 * <p>Every request names {@code channelCode: "no-such-channel"}, deliberately:
 * {@code BranchResolutionQueryService} answers {@code CHANNEL_NOT_ENABLED} for
 * an unresolved channel without ever calling {@code ServiceabilityResolver}
 * (see that class's own doc), so this suite proves the {@code available}/
 * {@code reason} fields are wired end to end without needing the sales-channel,
 * weekly-schedule and catalog-publication fixture a genuinely open branch would
 * need — {@code ServiceabilityService} itself is exercised elsewhere
 * ({@code SalesChannelAndServiceabilityTests}), and this suite's own job is the
 * cross-branch composition, not a second proof of that resolver's own rules.
 *
 * <p><b>Capability refusal</b> (the resolver read, and separately {@code
 * OperatorOrderingService.place}) is {@code
 * CustomerOrderHistoryReorderHttpTests}' own proof restated for these two
 * endpoints: a grant scoped to one branch does not satisfy a request naming a
 * different one, ADR 0025's {@code covers()} narrowing, and {@code place}'s
 * own override-reason validation refuses cleanly before ever touching
 * {@code CartService} — proved with a line naming a variant that does not
 * exist, since that validation runs first in {@code OperatorOrderingService
 * #place} and a catalog miss is never reached.
 */
@SpringBootTest
@AutoConfigureMockMvc
class NewOrderBranchResolutionHttpTests {

    private static final UUID TENANT = UUID.fromString("018fc900-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fc900-4000-7000-8000-0000000000b1");
    private static final UUID BRANCH_HIGH_PRIORITY = UUID.fromString("018fc900-4000-7000-8000-0000000000c1");
    private static final UUID BRANCH_LOW_PRIORITY = UUID.fromString("018fc900-4000-7000-8000-0000000000c2");
    private static final UUID ZONE_HIGH = UUID.fromString("018fc900-4000-7000-8000-0000000000d1");
    private static final UUID ZONE_LOW = UUID.fromString("018fc900-4000-7000-8000-0000000000d2");
    private static final UUID CREATED_BY = UUID.fromString("018fc900-4000-7000-8000-0000000000e1");

    /** {@code fk_order_channel} needs a real row; the seeded load orders all cite this one. */
    private static final UUID SEED_CHANNEL = UUID.fromString("018fc900-4000-7000-8000-0000000000f1");

    /** {@code fk_order_publication} needs a real row too. */
    private static final UUID SEED_CATALOG = UUID.fromString("018fc900-4000-7000-8000-0000000000f2");

    private static final UUID SEED_PUBLICATION = UUID.fromString("018fc900-4000-7000-8000-0000000000f3");

    /** The point every DELIVERY zone in this fixture is drawn around. */
    private static final double POINT_LAT = 41.3111;

    private static final double POINT_LON = 69.2401;

    /** Holds ORDER_READ/ORDER_PLACE at {@link #BRANCH_HIGH_PRIORITY} only — the LOCATION_STAFF shape. */
    private static final String LOCATION_STAFF_SUBJECT = "branch-resolution-http-location-staff";

    /**
     * Holds ORDER_READ/ORDER_PLACE at BRAND scope — reaches both branches. No
     * {@link PlatformRole} is naturally granted ORDER_PLACE at BRAND scope
     * (BRAND_MANAGER holds ORDER_READ but not ORDER_PLACE), so this grant row
     * stores LOCATION_MANAGER's own capability set at BRAND scope directly —
     * {@code JdbcAuthorizationService#hasGrant} reads {@code scope_type}/
     * {@code scope_id} off the grant row itself with no reference back to the
     * role's own declared scope type, the same way {@code
     * OperationsOrderControllerActionCapabilitiesHttpTests}' own {@code
     * CROSS_BRANCH}/{@code OVERRIDER} subjects narrow or widen a role's grant
     * away from its natural scope to exercise a specific boundary.
     */
    private static final String BRAND_GRANTED = "branch-resolution-http-brand";

    /** Authenticated, holds no grant at all. */
    private static final String UNGRANTED = "branch-resolution-http-ungranted";

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
        jdbc.sql("TRUNCATE TABLE ordering.orders, ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.zone_location_bindings CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.service_zone_versions CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.service_zones CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.catalogs CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.sales_channels CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenancy();
        bindZone(ZONE_HIGH, "HIGH", 20, BRANCH_HIGH_PRIORITY);
        bindZone(ZONE_LOW, "LOW", 10, BRANCH_LOW_PRIORITY);
        grantAt(LOCATION_STAFF_SUBJECT, PlatformRole.LOCATION_STAFF, "LOCATION", BRANCH_HIGH_PRIORITY);
        grantAt(BRAND_GRANTED, PlatformRole.LOCATION_MANAGER, "BRAND", BRAND);
        // UNGRANTED deliberately gets no grantAt call at all.
    }

    // ----------------------------------------------------------- resolveBranches

    @Test
    @DisplayName("DELIVERY ranks branches by their own winning zone's priority, ADR 0037's own order")
    void rankedByZoneMatch() throws Exception {
        MvcResult result = mvc.perform(post(branchResolutionPath(BRANCH_HIGH_PRIORITY))
                        .with(tokenFor(LOCATION_STAFF_SUBJECT))
                        .header("Idempotency-Key", "resolve-ranking-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deliveryRequestBody()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString(UTF_8);
        List<String> order = JsonPath.read(body, "$.candidates[*].locationId");
        assertThat(order)
                .as("both branches' zones cover the test point; the higher-priority one ranks first")
                .containsExactly(BRANCH_HIGH_PRIORITY.toString(), BRANCH_LOW_PRIORITY.toString());

        String proposed = JsonPath.read(body, "$.proposedLocationId");
        assertThat(proposed)
                .as("no channel resolves, so every candidate is unavailable — the proposal falls back to the "
                        + "top-ranked candidate rather than leaving the screen with nothing to propose")
                .isEqualTo(BRANCH_HIGH_PRIORITY.toString());

        Boolean firstAvailable = JsonPath.read(body, "$.candidates[0].available");
        String firstReason = JsonPath.read(body, "$.candidates[0].reason");
        assertThat(firstAvailable).isFalse();
        assertThat(firstReason).isEqualTo("CHANNEL_NOT_ENABLED");
    }

    @Test
    @DisplayName("Each candidate carries row 0.1c's own brand-scoped live order count")
    void loadAttachedFromBrandScopedCounts() throws Exception {
        seedNonTerminalOrder(BRANCH_HIGH_PRIORITY, "9001");
        seedNonTerminalOrder(BRANCH_HIGH_PRIORITY, "9002");
        seedNonTerminalOrder(BRANCH_HIGH_PRIORITY, "9003");
        seedTerminalOrder(BRANCH_LOW_PRIORITY, "9004");

        MvcResult result = mvc.perform(post(branchResolutionPath(BRANCH_HIGH_PRIORITY))
                        .with(tokenFor(LOCATION_STAFF_SUBJECT))
                        .header("Idempotency-Key", "resolve-load-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deliveryRequestBody()))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString(UTF_8);
        assertThat(activeOrderCountFor(body, BRANCH_HIGH_PRIORITY))
                .as("three non-terminal orders at the high-priority branch")
                .isEqualTo(3);
        assertThat(activeOrderCountFor(body, BRANCH_LOW_PRIORITY))
                .as("the low-priority branch's own order is terminal (COMPLETED) and does not count")
                .isEqualTo(0);
    }

    /** {@code $.candidates[?(...)].field[0]} chains badly through Jayway's own filter+index syntax; this reads plainly instead. */
    private static long activeOrderCountFor(String responseBody, UUID locationId) {
        List<Map<String, Object>> candidates = JsonPath.read(responseBody, "$.candidates");
        return candidates.stream()
                .filter(candidate -> locationId.toString().equals(candidate.get("locationId")))
                .findFirst()
                .map(candidate ->
                        ((Number) java.util.Objects.requireNonNull(candidate.get("activeOrderCount"))).longValue())
                .orElseThrow(() -> new AssertionError("No candidate named " + locationId));
    }

    @Test
    @DisplayName("PICKUP has no zone to rank by; every active branch of the brand is a candidate, ranked by load")
    void pickupCandidatesRankedByLoad() throws Exception {
        seedNonTerminalOrder(BRANCH_HIGH_PRIORITY, "9101");
        seedNonTerminalOrder(BRANCH_HIGH_PRIORITY, "9102");

        MvcResult result = mvc.perform(post(branchResolutionPath(BRANCH_HIGH_PRIORITY))
                        .with(tokenFor(LOCATION_STAFF_SUBJECT))
                        .header("Idempotency-Key", "resolve-pickup-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillmentMode\":\"PICKUP\",\"channelCode\":\"no-such-channel\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        String body = result.getResponse().getContentAsString(UTF_8);
        List<String> order = JsonPath.read(body, "$.candidates[*].locationId");
        assertThat(order)
                .as("the less busy branch (zero active orders) ranks first for PICKUP")
                .containsExactly(BRANCH_LOW_PRIORITY.toString(), BRANCH_HIGH_PRIORITY.toString());
        List<Number> zonePriorities = JsonPath.read(body, "$.candidates[*].zonePriority");
        assertThat(zonePriorities)
                .as("PICKUP candidates carry no zone match at all")
                .containsOnlyNulls();
    }

    @Test
    @DisplayName("A DELIVERY request with no point is refused, not silently resolved as PICKUP")
    void deliveryWithoutAPointIsRefused() throws Exception {
        MvcResult result = mvc.perform(post(branchResolutionPath(BRANCH_HIGH_PRIORITY))
                        .with(tokenFor(LOCATION_STAFF_SUBJECT))
                        .header("Idempotency-Key", "resolve-no-point-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fulfillmentMode\":\"DELIVERY\",\"channelCode\":\"no-such-channel\"}"))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(400);
        assertThat(result.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("POST .../branch-resolution refuses a principal with no ORDER_READ at all")
    void resolveBranchesRefusesAnUngrantedPrincipal() throws Exception {
        MvcResult refused = mvc.perform(post(branchResolutionPath(BRANCH_HIGH_PRIORITY))
                        .with(tokenFor(UNGRANTED))
                        .header("Idempotency-Key", "resolve-ungranted-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deliveryRequestBody()))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.ORDER_READ.code());
    }

    @Test
    @DisplayName("POST .../branch-resolution refuses a LOCATION-scoped grant at a branch other than this request's own")
    void resolveBranchesRefusesAGrantAtADifferentBranch() throws Exception {
        MvcResult refused = mvc.perform(post(branchResolutionPath(BRANCH_LOW_PRIORITY))
                        .with(tokenFor(LOCATION_STAFF_SUBJECT))
                        .header("Idempotency-Key", "resolve-wrong-branch-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(deliveryRequestBody()))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("LOCATION_STAFF_SUBJECT holds ORDER_READ only at BRANCH_HIGH_PRIORITY")
                .isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString()).contains("INSUFFICIENT_CAPABILITY");
    }

    // ------------------------------------------------------- branchOverrideReasons

    @Test
    @DisplayName("GET .../branch-override-reasons answers V0423's five seeded reasons")
    void branchOverrideReasonsAnswersTheCuratedList() throws Exception {
        MvcResult result = mvc.perform(get(ordersPath(BRANCH_HIGH_PRIORITY) + "/branch-override-reasons")
                        .with(tokenFor(LOCATION_STAFF_SUBJECT)))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        List<String> codes = JsonPath.read(result.getResponse().getContentAsString(UTF_8), "$[*].code");
        assertThat(codes)
                .containsExactlyInAnyOrder(
                        "CUSTOMER_REQUESTED_BRANCH",
                        "PROPOSED_BRANCH_TOO_BUSY",
                        "PROPOSED_BRANCH_CLOSED_OR_UNAVAILABLE",
                        "LOCAL_KNOWLEDGE_BETTER_MATCH",
                        "OTHER");
    }

    // --------------------------------------------------------------------- place()

    @Test
    @DisplayName("POST .../orders refuses a LOCATION-scoped grant placing at a branch other than the one it holds")
    void placeRefusesACrossBranchLocationScopedGrant() throws Exception {
        MvcResult refused = mvc.perform(post(ordersPath(BRANCH_LOW_PRIORITY))
                        .with(tokenFor(LOCATION_STAFF_SUBJECT))
                        .header("Idempotency-Key", "place-cross-branch-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placeOrderBody(null, null, null)))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("LOCATION_STAFF_SUBJECT holds ORDER_PLACE only at BRANCH_HIGH_PRIORITY, not BRANCH_LOW_PRIORITY — "
                        + "row 1.3's own capability decision: refuse, never silently widen")
                .isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.ORDER_PLACE.code());
    }

    @Test
    @DisplayName("A BRAND-scoped grant reaches place() at either branch — covers() widens, never narrows")
    void placeReachesEitherBranchForABrandScopedGrant() throws Exception {
        // Not 403: the capability gate lets the request through to
        // OperatorOrderingService, which then fails on the customer id this
        // test never created — proving the gate opened, not that an order
        // was actually placed (CartCheckoutAndOrderTests owns that).
        MvcResult attempt = mvc.perform(post(ordersPath(BRANCH_LOW_PRIORITY))
                        .with(tokenFor(BRAND_GRANTED))
                        .header("Idempotency-Key", "place-brand-scoped-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placeOrderBody(null, null, null)))
                .andReturn();

        assertThat(attempt.getResponse().getStatus()).isNotEqualTo(403);
    }

    @Test
    @DisplayName("An override with no proposedLocationId given at all places normally — no reason required")
    void placingWithNoProposedLocationIsNotAnOverride() throws Exception {
        MvcResult attempt = mvc.perform(post(ordersPath(BRANCH_HIGH_PRIORITY))
                        .with(tokenFor(LOCATION_STAFF_SUBJECT))
                        .header("Idempotency-Key", "place-no-proposal-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placeOrderBody(null, null, null)))
                .andReturn();

        // Not the override's own VALIDATION_FAILED ("needs a reason") — this
        // request reaches CartService and fails on the missing customer
        // instead. The request body's own proposedLocationId is never
        // consulted for this decision any more (OperatorOrderingService
        // re-resolves the branch itself): this passes because the server's
        // own PICKUP resolution, with both branches equally idle, proposes
        // BRANCH_HIGH_PRIORITY (the alphabetically-first display name) —
        // the same branch this request names — not because the client left
        // the field out. placeAtABranchTheResolverWouldNotHaveProposedStillRequiresAReason
        // below proves the field is genuinely ignored.
        assertThat(attempt.getResponse().getContentAsString())
                .doesNotContain("Placing at a branch other than the one the resolver proposed needs a reason");
    }

    @Test
    @DisplayName("Placing at a branch the resolver would not have proposed still requires a reason, "
            + "even when the request never names a proposedLocationId at all")
    void placeAtABranchTheResolverWouldNotHaveProposedStillRequiresAReason() throws Exception {
        // BRANCH_HIGH_PRIORITY is made busier than BRANCH_LOW_PRIORITY, so
        // PICKUP's own load-ascending ranking proposes the low-priority
        // branch — a fact this request's body says nothing about at all.
        seedNonTerminalOrder(BRANCH_HIGH_PRIORITY, "9301");
        seedNonTerminalOrder(BRANCH_HIGH_PRIORITY, "9302");

        MvcResult refused = mvc.perform(post(ordersPath(BRANCH_HIGH_PRIORITY))
                        .with(tokenFor(LOCATION_STAFF_SUBJECT))
                        .header("Idempotency-Key", "place-hidden-override-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placeOrderBody(null, null, null)))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("a caller cannot make a real cross-branch placement read as an ordinary one just by "
                        + "omitting proposedLocationId from the request body")
                .isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString())
                .contains("VALIDATION_FAILED")
                .contains("needs a reason");
    }

    /**
     * All four tests below place at {@link #BRANCH_LOW_PRIORITY} using {@link
     * #BRAND_GRANTED} (which reaches either branch): with both branches
     * equally idle, PICKUP's own load-then-name ranking proposes {@link
     * #BRANCH_HIGH_PRIORITY} instead — a genuine, server-resolved override of
     * the chosen branch, regardless of what {@code proposedLocationId} the
     * request body does or does not carry (it is never consulted for this
     * decision any more; {@code null} here on purpose).
     */
    @Test
    @DisplayName("Overriding the resolver's proposal with no reason code is refused before CartService is ever reached")
    void placeRefusesAnOverrideWithNoReason() throws Exception {
        MvcResult refused = mvc.perform(post(ordersPath(BRANCH_LOW_PRIORITY))
                        .with(tokenFor(BRAND_GRANTED))
                        .header("Idempotency-Key", "place-override-no-reason-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placeOrderBody(null, null, null)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString())
                .contains("VALIDATION_FAILED")
                .contains("needs a reason");
    }

    @Test
    @DisplayName("Overriding with an unknown reason code is refused")
    void placeRefusesAnOverrideWithAnUnknownReason() throws Exception {
        MvcResult refused = mvc.perform(post(ordersPath(BRANCH_LOW_PRIORITY))
                        .with(tokenFor(BRAND_GRANTED))
                        .header("Idempotency-Key", "place-override-unknown-reason-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placeOrderBody(null, "NOT_A_REAL_REASON", null)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString())
                .contains("VALIDATION_FAILED")
                .contains("No branch override reason");
    }

    @Test
    @DisplayName("Overriding with OTHER and no note is refused")
    void placeRefusesAnOtherOverrideWithNoNote() throws Exception {
        MvcResult refused = mvc.perform(post(ordersPath(BRANCH_LOW_PRIORITY))
                        .with(tokenFor(BRAND_GRANTED))
                        .header("Idempotency-Key", "place-override-other-no-note-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placeOrderBody(null, "OTHER", null)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString())
                .contains("VALIDATION_FAILED")
                .contains("needs a short note");
    }

    @Test
    @DisplayName("Overriding with a valid, active reason passes validation and reaches CartService")
    void placeAcceptsAValidOverrideReason() throws Exception {
        MvcResult attempt = mvc.perform(post(ordersPath(BRANCH_LOW_PRIORITY))
                        .with(tokenFor(BRAND_GRANTED))
                        .header("Idempotency-Key", "place-override-valid-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(placeOrderBody(null, "PROPOSED_BRANCH_TOO_BUSY", null)))
                .andReturn();

        assertThat(attempt.getResponse().getContentAsString())
                .as("the override's own validation passed; the request failed on the missing customer instead")
                .doesNotContain("Placing at a branch other than the one the resolver proposed needs a reason")
                .doesNotContain("No branch override reason")
                .doesNotContain("needs a short note");
    }

    // ------------------------------------------------------------------ fixtures

    private void insertTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'branch-resolution-http', 'Branch Resolution', 'Branch Resolution', 'UZS',
                        'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        insertLocation(BRANCH_HIGH_PRIORITY, "HIGH");
        insertLocation(BRANCH_LOW_PRIORITY, "LOW");
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'TESTCH', 'WEB', 'Test channel', 'ACTIVE')
                """).param("id", SEED_CHANNEL).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", SEED_CATALOG)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'TESTCH', 'PUBLISHED', 'hash', now())
                """)
                .param("id", SEED_PUBLICATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", SEED_CATALOG)
                .update();
    }

    private void insertLocation(UUID locationId, String code) {
        jdbc.sql("""
                INSERT INTO tenant.locations
                    (id, tenant_id, brand_id, code, slug, display_name, status, version, timezone)
                VALUES (:id, :tenantId, :brandId, :code, :slug, :name, 'ACTIVE', 0, 'Asia/Tashkent')
                """)
                .param("id", locationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("code", code)
                .param("slug", code.toLowerCase(java.util.Locale.ROOT))
                .param("name", "Branch " + code)
                .update();
    }

    /** A {@code DELIVERY} zone, live and bound to {@code locationId}, drawn as a circle around the test point. */
    private void bindZone(UUID zoneId, String code, int priority, UUID locationId) {
        jdbc.sql("""
                INSERT INTO fulfillment.service_zones
                    (id, tenant_id, brand_id, zone_role, code, display_name_ru, display_name_uz, display_name_en, status)
                VALUES (:id, :tenantId, :brandId, 'DELIVERY', :code, 'Zone', 'Zone', 'Zone', 'ACTIVE')
                """)
                .param("id", zoneId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("code", code)
                .update();
        jdbc.sql("""
                WITH shape AS (
                    SELECT ST_Multi(ST_Buffer(
                        ST_SetSRID(ST_MakePoint(:lon, :lat), 4326)::geography, 500)::geometry)::geography AS area
                )
                INSERT INTO fulfillment.service_zone_versions
                    (id, tenant_id, zone_id, zone_role, version, status, area, authoring_shape, priority,
                     area_sq_meters, currency, created_by, created_at, activated_by, activated_at)
                SELECT :versionId, :tenantId, :zoneId, 'DELIVERY', 1, 'ACTIVE', shape.area, '{}'::jsonb, :priority,
                       ST_Area(shape.area), 'UZS', :createdBy, now(), :createdBy, now()
                FROM shape
                """)
                .param("versionId", UUID.nameUUIDFromBytes((zoneId + "-v1").getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("zoneId", zoneId)
                .param("priority", priority)
                .param("lat", POINT_LAT)
                .param("lon", POINT_LON)
                .param("createdBy", CREATED_BY)
                .update();
        jdbc.sql("""
                INSERT INTO fulfillment.zone_location_bindings (tenant_id, brand_id, zone_id, location_id, valid_from)
                VALUES (:tenantId, :brandId, :zoneId, :locationId, now() - interval '1 hour')
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("zoneId", zoneId)
                .param("locationId", locationId)
                .update();
    }

    private void seedNonTerminalOrder(UUID locationId, String publicNumber) {
        seedOrder(locationId, publicNumber, "RECEIVED", null);
    }

    private void seedTerminalOrder(UUID locationId, String publicNumber) {
        seedOrder(locationId, publicNumber, "COMPLETED", Instant.now());
    }

    private void seedOrder(
            UUID locationId, String publicNumber, String status, @org.jspecify.annotations.Nullable Instant closedAt) {
        UUID orderId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        String guest = "guest-" + orderId;

        // fk_order_cart and fk_order_quote both need a real row — neither
        // table's id column is decorative here, unlike catalog_publication_id
        // (no FK; OperationsOrderControllerActionCapabilitiesHttpTests' own
        // fixture leaves it a bare random id for the identical reason).
        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'PICKUP', 'UZS',
                    'ACTIVE', :guest, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", locationId)
                .param("channelId", SEED_CHANNEL)
                .param("guest", guest)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, :hash,
                    0, 0, 0, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", locationId)
                .param("publicationId", SEED_PUBLICATION)
                .param("hash", "hash-" + orderId)
                .update();

        // ck_order_confirmed_at: any status from CONFIRMED onward needs a
        // non-null confirmed_at — COMPLETED (seedTerminalOrder) does, RECEIVED
        // (seedNonTerminalOrder) does not, so this is set whenever closedAt is
        // (the two load-count statuses this fixture ever seeds happen to agree).
        jdbc.sql("""
                INSERT INTO ordering.orders (
                    id, public_order_number, tenant_id, brand_id, location_id, channel_id,
                    channel_code_snapshot, guest_reference_hash, fulfillment_mode,
                    acceptance_mode_snapshot, approval_channel_snapshot, status, currency,
                    subtotal_minor, tax_minor, fee_minor, total_minor, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key,
                    version, confirmed_at, closed_at, created_at)
                VALUES (
                    :id, :publicNumber, :tenantId, :brandId, :locationId, :channelId,
                    'test-channel', :guest, 'PICKUP', 'AUTO_CONFIRM', 'NONE', :status, 'UZS',
                    0, 0, 0, 0, :quoteId, :hash, :publicationId, :cartId, :idempotencyKey, 1,
                    :confirmedAt, :closedAt, now())
                """)
                .param("id", orderId)
                .param("publicNumber", publicNumber)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", locationId)
                .param("channelId", SEED_CHANNEL)
                .param("guest", guest)
                .param("status", status)
                .param("quoteId", quoteId)
                .param("hash", "hash-" + orderId)
                .param("publicationId", SEED_PUBLICATION)
                .param("confirmedAt", closedAt == null ? null : closedAt.atOffset(ZoneOffset.UTC))
                .param("cartId", cartId)
                .param("idempotencyKey", "idem-" + orderId)
                .param("closedAt", closedAt == null ? null : closedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void grantAt(String subject, PlatformRole role, String scopeType, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'branch resolution http endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeType).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static String ordersPath(UUID locationId) {
        return "/api/v1/tenants/" + TENANT + "/brands/" + BRAND + "/locations/" + locationId + "/orders";
    }

    private static String branchResolutionPath(UUID locationId) {
        return ordersPath(locationId) + "/branch-resolution";
    }

    private static String deliveryRequestBody() {
        return "{\"fulfillmentMode\":\"DELIVERY\",\"point\":{\"latitude\":" + POINT_LAT + ",\"longitude\":" + POINT_LON
                + "},\"channelCode\":\"no-such-channel\"}";
    }

    private static String placeOrderBody(
            @org.jspecify.annotations.Nullable String proposedLocationId,
            @org.jspecify.annotations.Nullable String overrideReasonCode,
            @org.jspecify.annotations.Nullable String overrideNote) {
        StringBuilder json = new StringBuilder();
        json.append("{\"customerAccountId\":\"").append(UUID.randomUUID()).append("\",");
        json.append("\"channelCode\":\"no-such-channel\",");
        json.append("\"fulfillmentMode\":\"PICKUP\",");
        // A variant that does not exist: place() validates the branch
        // override before CartService ever looks this up, so this body
        // reaches (and is refused by) exactly the code under test here.
        json.append("\"lines\":[{\"variantId\":\"").append(UUID.randomUUID()).append("\",\"quantity\":1}],");
        json.append("\"paymentMethodCode\":\"CASH\"");
        if (proposedLocationId != null) {
            json.append(",\"proposedLocationId\":\"").append(proposedLocationId).append('"');
        }
        if (overrideReasonCode != null) {
            json.append(",\"overrideReasonCode\":\"").append(overrideReasonCode).append('"');
        }
        if (overrideNote != null) {
            json.append(",\"overrideNote\":\"").append(overrideNote).append('"');
        }
        json.append('}');
        return json.toString();
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
