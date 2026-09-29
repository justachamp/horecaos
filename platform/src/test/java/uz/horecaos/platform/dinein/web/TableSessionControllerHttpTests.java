package uz.horecaos.platform.dinein.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.dinein.application.FloorPlanService;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.TableRow;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.StubJwtIssuer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The staff side of a table visit, through the real HTTP stack (ADR 0047): seating
 * a walk-in, the live list that names every party's tables, and attaching an
 * operator-keyed order to a table's bill as a round -- the three calls the console's
 * floor plan and New Order screens make.
 *
 * <p>{@code TableSessionController} had no HTTP test at all; {@code DineInTests}
 * drives the service. What only HTTP can prove is what this class is for: the
 * declared capability and its scope, the branch in the path being the branch the
 * session sits at, and the tenant being a predicate of every read.
 *
 * <p>Two tenants and two branches of one tenant are seeded on purpose. A
 * {@code LOCATION}-scoped grant is a grant for one branch, and a session id is a
 * UUID in the same tenant as its neighbour's: matching it on the tenant alone would
 * let branch A's manager read branch B's bill, attach an order to B's table, or
 * close B's party.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubJwtIssuer.class)
class TableSessionControllerHttpTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID TENANT_A = UUID.fromString("018fd900-4000-7000-8000-0000000000a1");
    private static final UUID BRAND_A = UUID.fromString("018fd900-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION_A1 = UUID.fromString("018fd900-4000-7000-8000-0000000000c1");
    private static final UUID LOCATION_A2 = UUID.fromString("018fd900-4000-7000-8000-0000000000c2");

    private static final UUID TENANT_B = UUID.fromString("018fd900-4000-7000-8000-0000000000a2");
    private static final UUID BRAND_B = UUID.fromString("018fd900-4000-7000-8000-0000000000b2");
    private static final UUID LOCATION_B1 = UUID.fromString("018fd900-4000-7000-8000-0000000000c3");

    /** Holds DINEIN_SESSION_READ/MANAGE (and force-close) at branch A1 only. */
    private static final String MANAGER_A1 = "session-http-manager-a1";

    /** Holds DINEIN_SESSION_READ/MANAGE at branch A1 -- the waiter, without force-close. */
    private static final String STAFF_A1 = "session-http-staff-a1";

    /** The same manager role at the tenant's other branch. */
    private static final String MANAGER_A2 = "session-http-manager-a2";

    /** A brand manager: sees orders across the brand, holds no dine-in capability at all. */
    private static final String BRAND_MANAGER_A = "session-http-brand-manager-a";

    /** The manager role at another tenant entirely. */
    private static final String MANAGER_B1 = "session-http-manager-b1";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the table session HTTP test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
        // IdempotencyInterceptor envelope-encrypts a mutating response before it
        // stores it for replay (ADR 0029).
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
    private FloorPlanService floorPlan;

    private Site a1 = new Site(TENANT_A, BRAND_A, LOCATION_A1, "CENTRE");
    private Site a2 = new Site(TENANT_A, BRAND_A, LOCATION_A2, "NORTH");
    private Site b1 = new Site(TENANT_B, BRAND_B, LOCATION_B1, "CENTRE");

    /** One branch and everything a test needs to put a table and an order in it. */
    private static final class Site {
        final UUID tenantId;
        final UUID brandId;
        final UUID locationId;
        final String code;
        UUID sectionId = UUID.randomUUID();

        Site(UUID tenantId, UUID brandId, UUID locationId, String code) {
            this.tenantId = tenantId;
            this.brandId = brandId;
            this.locationId = locationId;
            this.code = code;
        }
    }

    private UUID channelA = UUID.randomUUID();
    private UUID channelB = UUID.randomUUID();
    private UUID publicationA = UUID.randomUUID();
    private UUID publicationB = UUID.randomUUID();

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE platform.idempotency_records").update();
        jdbc.sql("TRUNCATE TABLE dinein.session_orders, dinein.session_tables, "
                        + "dinein.table_sessions, dinein.reservation_tables, dinein.reservations, "
                        + "dinein.qr_guest_sessions, dinein.tables, dinein.sections, "
                        + "dinein.location_settings CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE ordering.order_lines, ordering.orders, ordering.carts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();

        a1 = new Site(TENANT_A, BRAND_A, LOCATION_A1, "CENTRE");
        a2 = new Site(TENANT_A, BRAND_A, LOCATION_A2, "NORTH");
        b1 = new Site(TENANT_B, BRAND_B, LOCATION_B1, "CENTRE");
        channelA = UUID.randomUUID();
        channelB = UUID.randomUUID();
        publicationA = UUID.randomUUID();
        publicationB = UUID.randomUUID();

        seedTenant(TENANT_A, BRAND_A, "session-a", channelA, publicationA);
        seedTenant(TENANT_B, BRAND_B, "session-b", channelB, publicationB);
        seedLocation(a1);
        seedLocation(a2);
        seedLocation(b1);

        grant(MANAGER_A1, PlatformRole.LOCATION_MANAGER, "LOCATION", TENANT_A, LOCATION_A1);
        grant(STAFF_A1, PlatformRole.LOCATION_STAFF, "LOCATION", TENANT_A, LOCATION_A1);
        grant(MANAGER_A2, PlatformRole.LOCATION_MANAGER, "LOCATION", TENANT_A, LOCATION_A2);
        grant(BRAND_MANAGER_A, PlatformRole.BRAND_MANAGER, "BRAND", TENANT_A, BRAND_A);
        grant(MANAGER_B1, PlatformRole.LOCATION_MANAGER, "LOCATION", TENANT_B, LOCATION_B1);
    }

    // ------------------------------------------------------------------ seating a walk-in

    @Test
    @DisplayName("a waiter seats a walk-in with no booking, and the answer names the table")
    void aWaiterSeatsAWalkIn() throws Exception {
        TableRow t7 = createTable(a1, "T7", "Table 7");

        MvcResult seated = open(a1, STAFF_A1, t7.id());

        assertThat(seated.getResponse().getStatus()).isEqualTo(200);
        JsonNode session = json(seated);
        assertThat(session.path("reservationId").isNull())
                .as("a walk-in names no booking")
                .isTrue();
        assertThat(session.path("partySize").asInt()).isEqualTo(3);
        assertThat(session.path("status").asText()).isEqualTo("OPEN");
        assertThat(session.path("currency").asText()).isEqualTo("UZS");
        assertThat(codes(session)).containsExactly("T7");
        assertThat(session.path("tables").get(0).path("displayName").asText()).isEqualTo("Table 7");
        assertThat(session.path("tables").get(0).path("tableId").asText())
                .isEqualTo(t7.id().toString());
    }

    @Test
    @DisplayName("the live list names every party's tables, in the order they were joined")
    void theLiveListNamesEachPartysTables() throws Exception {
        TableRow t2 = createTable(a1, "T2", "Table 2");
        TableRow t10 = createTable(a1, "T10", "Table 10");
        TableRow t3 = createTable(a1, "T3", "Table 3");
        open(a1, MANAGER_A1, t2.id(), t10.id());
        open(a1, MANAGER_A1, t3.id());

        MvcResult live =
                mvc.perform(get(sessionsPath(a1)).with(tokenFor(STAFF_A1))).andReturn();

        assertThat(live.getResponse().getStatus()).isEqualTo(200);
        JsonNode parties = json(live);
        assertThat(parties).hasSize(2);
        assertThat(codes(parties.get(0)))
                .as("T2 then T10 -- a code sort would put T10 first")
                .containsExactly("T2", "T10");
        assertThat(codes(parties.get(1))).containsExactly("T3");
    }

    @Test
    @DisplayName("a table already sat at answers 409 TABLE_OCCUPIED, not a second party")
    void aSeatedTableCannotBeSeatedAgain() throws Exception {
        TableRow t7 = createTable(a1, "T7", "Table 7");
        assertThat(open(a1, STAFF_A1, t7.id()).getResponse().getStatus()).isEqualTo(200);

        MvcResult second = open(a1, STAFF_A1, t7.id());

        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(second).path("conflict").asText()).isEqualTo("TABLE_OCCUPIED");
    }

    @Test
    @DisplayName("a table of the tenant's other branch cannot be seated through this branch's path")
    void aTableOfAnotherBranchIsRefused() throws Exception {
        TableRow northTable = createTable(a2, "N1", "North 1");

        MvcResult refused = open(a1, MANAGER_A1, northTable.id());

        assertThat(refused.getResponse().getStatus()).isEqualTo(400);
        assertThat(count("SELECT count(*) FROM dinein.table_sessions"))
                .as("nothing was seated")
                .isZero();
    }

    // ------------------------------------------------------------- capability and its scope

    @Test
    @DisplayName("seating, reading the live list and attaching a round each need the session capability")
    void theSessionCapabilityGatesEveryCall() throws Exception {
        TableRow t7 = createTable(a1, "T7", "Table 7");
        UUID sessionId = sessionIdOf(open(a1, STAFF_A1, t7.id()));
        UUID order = seedOrder(a1, "A-1001", "DINE_IN", 20_000);

        // A brand manager holds no dine-in capability: refused on all three.
        assertThat(open(a1, BRAND_MANAGER_A, t7.id()).getResponse().getStatus()).isEqualTo(403);
        assertThat(mvc.perform(get(sessionsPath(a1)).with(tokenFor(BRAND_MANAGER_A)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .isEqualTo(403);
        assertThat(round(a1, BRAND_MANAGER_A, sessionId, order).getResponse().getStatus())
                .isEqualTo(403);

        // Nobody signed in: 401, not a quiet empty list.
        assertThat(mvc.perform(get(sessionsPath(a1))).andReturn().getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    @DisplayName("a manager of another branch, and a manager of another tenant, are refused at this branch")
    void aGrantIsForOneBranchOfOneTenant() throws Exception {
        TableRow t7 = createTable(a1, "T7", "Table 7");
        UUID sessionId = sessionIdOf(open(a1, STAFF_A1, t7.id()));
        UUID order = seedOrder(a1, "A-1002", "DINE_IN", 20_000);

        for (String outsider : List.of(MANAGER_A2, MANAGER_B1)) {
            assertThat(open(a1, outsider, t7.id()).getResponse().getStatus())
                    .as("%s seating at branch A1", outsider)
                    .isEqualTo(403);
            assertThat(mvc.perform(get(sessionsPath(a1)).with(tokenFor(outsider)))
                            .andReturn()
                            .getResponse()
                            .getStatus())
                    .as("%s reading branch A1's live list", outsider)
                    .isEqualTo(403);
            assertThat(round(a1, outsider, sessionId, order).getResponse().getStatus())
                    .as("%s attaching to branch A1's table", outsider)
                    .isEqualTo(403);
        }
        assertThat(count("SELECT count(*) FROM dinein.session_orders"))
                .as("no round landed")
                .isZero();
    }

    @Test
    @DisplayName("a waiter cannot force-close a table: that is its own capability")
    void aWaiterCannotForceClose() throws Exception {
        TableRow t7 = createTable(a1, "T7", "Table 7");
        JsonNode session = json(open(a1, STAFF_A1, t7.id()));
        UUID sessionId = UUID.fromString(session.path("sessionId").asText());

        MvcResult refused = mvc.perform(post(sessionsPath(a1) + "/" + sessionId + "/force-closures")
                        .with(tokenFor(STAFF_A1))
                        .header("Idempotency-Key", key())
                        .header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reasonCode\":\"WALKOUT\",\"reason\":\"Left without paying\"}"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(count("SELECT count(*) FROM dinein.table_sessions WHERE status = 'OPEN'"))
                .as("the party is still seated")
                .isEqualTo(1);
    }

    // ----------------------------------------------------------- a keyed-in order, as a round

    @Test
    @DisplayName("an operator-keyed DINE_IN order attached as a round shows its table on the order board at once")
    void anAttachedRoundShowsItsTableOnTheBoard() throws Exception {
        TableRow t7 = createTable(a1, "T7", "Table 7");
        UUID sessionId = sessionIdOf(open(a1, STAFF_A1, t7.id()));
        UUID keyedIn = seedOrder(a1, "A-1003", "DINE_IN", 45_000);

        assertThat(boardRow(a1, MANAGER_A1, keyedIn).path("table").isNull())
                .as("before the attach, a keyed-in order names no table")
                .isTrue();

        MvcResult attached = round(a1, STAFF_A1, sessionId, keyedIn);

        assertThat(attached.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(attached).path("sequence").asInt()).isEqualTo(1);
        JsonNode table = boardRow(a1, MANAGER_A1, keyedIn).path("table");
        assertThat(table.path("sessionId").asText()).isEqualTo(sessionId.toString());
        assertThat(codes(table)).containsExactly("T7");

        JsonNode detail =
                json(mvc.perform(get(sessionsPath(a1) + "/" + sessionId).with(tokenFor(STAFF_A1)))
                        .andReturn());
        assertThat(detail.path("totalMinor").asLong()).isEqualTo(45_000L);
        assertThat(detail.path("roundCount").asInt()).isEqualTo(1);
        assertThat(codes(detail.path("session"))).containsExactly("T7");
    }

    @Test
    @DisplayName("attaching the same order to the same table twice is one round, not a conflict")
    void attachingTwiceIsOneRound() throws Exception {
        TableRow t7 = createTable(a1, "T7", "Table 7");
        UUID sessionId = sessionIdOf(open(a1, STAFF_A1, t7.id()));
        UUID order = seedOrder(a1, "A-1004", "DINE_IN", 30_000);

        MvcResult first = round(a1, STAFF_A1, sessionId, order);
        // A dropped response: the console asks again under a fresh idempotency key.
        MvcResult retry = round(a1, STAFF_A1, sessionId, order);

        assertThat(first.getResponse().getStatus()).isEqualTo(200);
        assertThat(retry.getResponse().getStatus()).isEqualTo(200);
        assertThat(json(retry).path("sequence").asInt())
                .as("the retry is told where the round already is")
                .isEqualTo(json(first).path("sequence").asInt());
        assertThat(count("SELECT count(*) FROM dinein.session_orders"))
                .as("still one round on the bill")
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM audit.audit_events WHERE action_code = 'dinein.session.round-added'"
                        + " AND target_id = '" + sessionId + "'"))
                .as("and one audit fact: the retry recorded nothing")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an order already on another table's bill is a 409, never a second bill")
    void anOrderOnAnotherBillIsAConflict() throws Exception {
        TableRow t7 = createTable(a1, "T7", "Table 7");
        TableRow t8 = createTable(a1, "T8", "Table 8");
        UUID first = sessionIdOf(open(a1, STAFF_A1, t7.id()));
        UUID second = sessionIdOf(open(a1, STAFF_A1, t8.id()));
        UUID order = seedOrder(a1, "A-1005", "DINE_IN", 30_000);
        assertThat(round(a1, STAFF_A1, first, order).getResponse().getStatus()).isEqualTo(200);

        MvcResult conflict = round(a1, STAFF_A1, second, order);

        assertThat(conflict.getResponse().getStatus()).isEqualTo(409);
        assertThat(json(conflict).path("conflict").asText()).isEqualTo("ORDER_ALREADY_BILLED");
    }

    @Test
    @DisplayName("a pickup order is not a round of a table's bill")
    void aPickupOrderIsNotARound() throws Exception {
        TableRow t7 = createTable(a1, "T7", "Table 7");
        UUID sessionId = sessionIdOf(open(a1, STAFF_A1, t7.id()));
        UUID pickup = seedOrder(a1, "A-1006", "PICKUP", 30_000);

        assertThat(round(a1, STAFF_A1, sessionId, pickup).getResponse().getStatus())
                .isEqualTo(400);
        assertThat(count("SELECT count(*) FROM dinein.session_orders")).isZero();
    }

    // -------------------------------------------------------------------- branch isolation

    @Test
    @DisplayName(
            "a session of the tenant's other branch is invisible through this branch's path, for read and write alike")
    void aSessionOfAnotherBranchIsInvisible() throws Exception {
        TableRow northTable = createTable(a2, "N1", "North 1");
        UUID northSession = sessionIdOf(open(a2, MANAGER_A2, northTable.id()));
        UUID northOrder = seedOrder(a2, "A-2001", "DINE_IN", 12_000);
        String viaA1 = sessionsPath(a1) + "/" + northSession;

        // A1's manager holds a grant for A1 only, but the id is real and in the same tenant.
        assertThat(mvc.perform(get(viaA1).with(tokenFor(MANAGER_A1)))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("reading branch A2's bill through A1's path")
                .isEqualTo(404);
        assertThat(round(a1, MANAGER_A1, northSession, northOrder).getResponse().getStatus())
                .as("attaching A2's order to A2's table through A1's path")
                .isEqualTo(404);
        assertThat(mvc.perform(post(viaA1 + "/state-actions")
                                .with(tokenFor(MANAGER_A1))
                                .header("Idempotency-Key", key())
                                .header("If-Match", "\"1\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"targetStatus\":\"BILL_REQUESTED\",\"reason\":\"Not mine\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("moving A2's party through A1's path")
                .isEqualTo(404);
        assertThat(mvc.perform(post(viaA1 + "/force-closures")
                                .with(tokenFor(MANAGER_A1))
                                .header("Idempotency-Key", key())
                                .header("If-Match", "\"1\"")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reasonCode\":\"WALKOUT\",\"reason\":\"Not mine\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus())
                .as("closing A2's party through A1's path")
                .isEqualTo(404);

        assertThat(count("SELECT count(*) FROM dinein.session_orders")).isZero();
        assertThat(count("SELECT count(*) FROM dinein.table_sessions WHERE status = 'OPEN'"))
                .as("A2's party is still seated, untouched")
                .isEqualTo(1);
        // And A1's live list does not carry it.
        assertThat(mvc.perform(get(sessionsPath(a1)).with(tokenFor(MANAGER_A1)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString())
                .doesNotContain(northSession.toString());
    }

    @Test
    @DisplayName("another tenant's table and order answer as if they did not exist")
    void anotherTenantsTableAndOrderDoNotExist() throws Exception {
        TableRow aTable = createTable(a1, "T1", "Table 1");
        UUID aOrder = seedOrder(a1, "A-3001", "DINE_IN", 10_000);
        TableRow bTable = createTable(b1, "T9", "Table 9");
        UUID bSession = sessionIdOf(open(b1, MANAGER_B1, bTable.id()));

        // Tenant B's manager, on tenant B's own path, names tenant A's table id.
        MvcResult foreignTable = open(b1, MANAGER_B1, aTable.id());
        assertThat(foreignTable.getResponse().getStatus()).isEqualTo(404);

        // ... and tenant A's order id, to tenant B's real session.
        MvcResult foreignOrder = round(b1, MANAGER_B1, bSession, aOrder);
        assertThat(foreignOrder.getResponse().getStatus()).isEqualTo(404);

        assertThat(count("SELECT count(*) FROM dinein.session_orders")).isZero();
        MvcResult bLive =
                mvc.perform(get(sessionsPath(b1)).with(tokenFor(MANAGER_B1))).andReturn();
        assertThat(bLive.getResponse().getContentAsString())
                .doesNotContain(aTable.id().toString())
                .doesNotContain("T1\"");
    }

    // ------------------------------------------------------------------------------ helpers

    private MvcResult open(Site site, String subject, UUID... tableIds) throws Exception {
        String ids = String.join(
                ",",
                java.util.Arrays.stream(tableIds).map(id -> "\"" + id + "\"").toList());
        return mvc.perform(post(sessionsPath(site))
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableIds\":[" + ids + "],\"partySize\":3,\"currency\":\"UZS\","
                                + "\"reason\":\"Walk-in seated at the door\"}"))
                .andReturn();
    }

    private MvcResult round(Site site, String subject, UUID sessionId, UUID orderId) throws Exception {
        return mvc.perform(post(sessionsPath(site) + "/" + sessionId + "/rounds")
                        .with(tokenFor(subject))
                        .header("Idempotency-Key", key())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\",\"reason\":\"Keyed in at the console\"}"))
                .andReturn();
    }

    private JsonNode boardRow(Site site, String subject, UUID orderId) throws Exception {
        MvcResult result = mvc.perform(get(ordersPath(site) + "/board").with(tokenFor(subject)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("board status").isEqualTo(200);
        for (JsonNode row : json(result).path("items")) {
            if (orderId.toString().equals(row.path("orderId").asText())) {
                return row;
            }
        }
        throw new AssertionError("order " + orderId + " is not on the board: "
                + result.getResponse().getContentAsString());
    }

    private static UUID sessionIdOf(MvcResult opened) throws Exception {
        assertThat(opened.getResponse().getStatus()).as("open status").isEqualTo(200);
        return UUID.fromString(json(opened).path("sessionId").asText());
    }

    private static List<String> codes(JsonNode holder) {
        return StreamSupport.stream(holder.path("tables").spliterator(), false)
                .map(node -> node.path("code").asText())
                .toList();
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private long count(String sql) {
        Long value = jdbc.sql(sql).query(Long.class).single();
        return value == null ? 0L : value;
    }

    private static String sessionsPath(Site site) {
        return "/api/v1/tenants/" + site.tenantId + "/brands/" + site.brandId + "/locations/" + site.locationId
                + "/dine-in/sessions";
    }

    private static String ordersPath(Site site) {
        return "/api/v1/tenants/" + site.tenantId + "/brands/" + site.brandId + "/locations/" + site.locationId
                + "/orders";
    }

    private TableRow createTable(Site site, String code, String displayName) {
        return floorPlan.createTable(new FloorPlanService.NewTable(
                site.tenantId, site.brandId, site.locationId, site.sectionId, code, displayName, 4, false, null, null));
    }

    private UUID seedOrder(Site site, String number, String mode, long totalMinor) {
        boolean tenantA = site.tenantId.equals(TENANT_A);
        UUID channelId = tenantA ? channelA : channelB;
        UUID publicationId = tenantA ? publicationA : publicationB;
        UUID orderId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        String guestReference = "guest-" + number;

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :t, :b, :loc, 'UZS', :pub, 1, 'hash', :total, 0, :total,
                        now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("t", site.tenantId)
                .param("b", site.brandId)
                .param("loc", site.locationId)
                .param("pub", publicationId)
                .param("total", totalMinor)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, :mode, 'UZS', 'ACTIVE', :guest,
                        now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("t", site.tenantId)
                .param("b", site.brandId)
                .param("loc", site.locationId)
                .param("ch", channelId)
                .param("mode", mode)
                .param("guest", guestReference)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot, status,
                    currency, subtotal_minor, tax_minor, total_minor, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key, version,
                    confirmed_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'QRTABLE', :guest, :mode, 'AUTO_CONFIRM',
                    'NONE', 'CONFIRMED', 'UZS', :total, 0, :total, :quote, 'hash', :pub, :cart,
                    :key, 1, now())
                """)
                .param("id", orderId)
                .param("number", number)
                .param("t", site.tenantId)
                .param("b", site.brandId)
                .param("loc", site.locationId)
                .param("ch", channelId)
                .param("guest", guestReference)
                .param("mode", mode)
                .param("total", totalMinor)
                .param("quote", quoteId)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + number)
                .update();

        return orderId;
    }

    private void seedTenant(UUID tenantId, UUID brandId, String slug, UUID channelId, UUID publicationId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", brandId).param("t", tenantId).update();

        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :t, 'QRTABLE', 'QR_TABLE', 'QR table', 'ACTIVE')
                """).param("id", channelId).param("t", tenantId).update();

        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", tenantId)
                .param("b", brandId)
                .update();

        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'QRTABLE', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("cat", catalogId)
                .update();
    }

    private void seedLocation(Site site) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, :code, :slug, :code, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", site.locationId)
                .param("t", site.tenantId)
                .param("b", site.brandId)
                .param("code", site.code)
                .param("slug", site.code.toLowerCase())
                .update();

        site.sectionId = floorPlan
                .createSection(new FloorPlanService.NewSection(
                        site.tenantId, site.brandId, site.locationId, "HALL", "Hall", 0))
                .id();
    }

    private void grant(String subject, PlatformRole role, String scopeType, UUID tenantId, UUID scopeId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, :scopeType, :scopeId,
                        'ACTIVE', 'test-fixture', 'table session http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code() + scopeId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
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
}
