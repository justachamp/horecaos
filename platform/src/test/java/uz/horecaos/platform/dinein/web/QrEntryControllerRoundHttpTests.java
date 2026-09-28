package uz.horecaos.platform.dinein.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.dinein.application.FloorPlanService;
import uz.horecaos.platform.dinein.application.TableSessionService;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SessionRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.TableRow;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@code QrEntryController}'s new guest-scoped round attachment (row {@code
 * 10.5}'s storefront table-QR flow, ADR 0047), exercised through the real HTTP
 * stack the way {@code FloorPlanTableAndQrEndpointTests} exercises the same
 * controller family.
 *
 * <p>{@code POST .../sessions/{sessionId}/rounds} is new this wave. ADR 0047's
 * own "what was not built" section names the gap this closes: checkout does not
 * itself bind a cart to a table ("ordering's cart-to-table binding"), so before
 * this a round could only ever reach a bill through the operator's identical
 * write on {@code TableSessionController} — unreachable from a guest's own
 * device, which holds a table-scoped bearer token and no ADR 0025 capability at
 * all. This proves the guest path: attaching an already-placed DINE_IN order to
 * the guest's own table's session, refusing every table that is not theirs, and
 * behaving idempotently on a retry the way {@code bill-requests} already does.
 */
@SpringBootTest
@AutoConfigureMockMvc
class QrEntryControllerRoundHttpTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID TENANT = UUID.fromString("018fd700-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fd700-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fd700-4000-7000-8000-0000000000c1");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the QR round test");
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
    private FloorPlanService floorPlan;

    @Autowired
    @SuppressWarnings("NullAway")
    private TableSessionService sessions;

    private UUID channelId;
    private UUID publicationId;
    private UUID sectionId;

    @BeforeEach
    void reset() {
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

        seedTenancy();
        sectionId = floorPlan
                .createSection(new FloorPlanService.NewSection(TENANT, BRAND, LOCATION, "HALL", "Hall", 0))
                .id();
    }

    @Test
    @DisplayName("a guest attaches their just-placed order to their own table's bill")
    void guestAttachesTheirOwnRound() throws Exception {
        TableRow table = createTable("T1");
        floorPlan.configure(
                new FloorPlanService.BranchSettings(TENANT, BRAND, LOCATION, "ORDER_AND_PAY", 15, 240, 0),
                "manager",
                "QR ordering on");
        String printed = rotate(table);
        SessionRow session = openWalkIn(table.id());
        UUID orderId = seedDineInOrder("T1-001", 45_000);

        String guestToken = exchange(printed);

        MvcResult attach = mvc.perform(post(roundsPath(session.id()))
                        .header("X-Dine-In-Token", guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\"}"))
                .andReturn();

        assertThat(attach.getResponse().getStatus()).isEqualTo(200);
        JsonNode body = json(attach);
        assertThat(body.path("sessionId").asText()).isEqualTo(session.id().toString());
        assertThat(body.path("totalMinor").asLong()).isEqualTo(45_000L);
        assertThat(body.path("roundCount").asInt()).isEqualTo(1);
        assertThat(orderIdsOf(body)).containsExactly(orderId.toString());

        assertThat(jdbc.sql("SELECT count(*) FROM dinein.session_orders WHERE session_id = :id")
                        .param("id", session.id())
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("attaching the same order twice is idempotent: the bill does not double-count it")
    void attachingTwiceIsIdempotent() throws Exception {
        TableRow table = createTable("T1");
        floorPlan.configure(
                new FloorPlanService.BranchSettings(TENANT, BRAND, LOCATION, "ORDER_AND_PAY", 15, 240, 0),
                "manager",
                "QR ordering on");
        String printed = rotate(table);
        SessionRow session = openWalkIn(table.id());
        UUID orderId = seedDineInOrder("T1-002", 30_000);
        String guestToken = exchange(printed);

        attachRound(session.id(), guestToken, orderId);
        MvcResult retry = mvc.perform(post(roundsPath(session.id()))
                        .header("X-Dine-In-Token", guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\"}"))
                .andReturn();

        assertThat(retry.getResponse().getStatus())
                .as("a dropped-response retry must read as success, not as a conflict")
                .isEqualTo(200);
        JsonNode body = json(retry);
        assertThat(body.path("roundCount").asInt()).isEqualTo(1);
        assertThat(body.path("totalMinor").asLong()).isEqualTo(30_000L);
    }

    @Test
    @DisplayName("a guest cannot attach a round to a neighbouring table's bill")
    void guestCannotReachAnotherTablesBill() throws Exception {
        TableRow tableOne = createTable("T1");
        TableRow tableTwo = createTable("T2");
        floorPlan.configure(
                new FloorPlanService.BranchSettings(TENANT, BRAND, LOCATION, "ORDER_AND_PAY", 15, 240, 0),
                "manager",
                "QR ordering on");
        String printedOne = rotate(tableOne);
        openWalkIn(tableOne.id());
        SessionRow sessionTwo = openWalkIn(tableTwo.id());
        UUID orderId = seedDineInOrder("T2-001", 12_000);

        String guestTokenForTableOne = exchange(printedOne);

        MvcResult attempt = mvc.perform(post(roundsPath(sessionTwo.id()))
                        .header("X-Dine-In-Token", guestTokenForTableOne)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\"}"))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("the session is checked against the table the guest token was minted for")
                .isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM dinein.session_orders WHERE session_id = :id")
                        .param("id", sessionTwo.id())
                        .query(Integer.class)
                        .single())
                .isEqualTo(0);
    }

    @Test
    @DisplayName("a VIEW_ONLY branch refuses the round the same way it refuses the bill")
    void viewOnlyRefusesRounds() throws Exception {
        TableRow table = createTable("T1");
        // Left at the default VIEW_ONLY -- no configure() call.
        String printed = rotate(table);
        String guestToken = exchange(printed);
        UUID fakeSessionId = UUID.randomUUID();

        MvcResult attempt = mvc.perform(post(roundsPath(fakeSessionId))
                        .header("X-Dine-In-Token", guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + UUID.randomUUID() + "\"}"))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("a VIEW_ONLY code carries no ordering capability at all")
                .isEqualTo(404);
    }

    @Test
    @DisplayName("a DELIVERY order is not a round of one, and is refused")
    void nonDineInOrderIsRefused() throws Exception {
        TableRow table = createTable("T1");
        floorPlan.configure(
                new FloorPlanService.BranchSettings(TENANT, BRAND, LOCATION, "ORDER_AND_PAY", 15, 240, 0),
                "manager",
                "QR ordering on");
        String printed = rotate(table);
        SessionRow session = openWalkIn(table.id());
        UUID deliveryOrder = seedOrder("T1-003", 18_000, "DELIVERY");
        String guestToken = exchange(printed);

        MvcResult attempt = mvc.perform(post(roundsPath(session.id()))
                        .header("X-Dine-In-Token", guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + deliveryOrder + "\"}"))
                .andReturn();

        assertThat(attempt.getResponse().getStatus()).isEqualTo(400);
        assertThat(jdbc.sql("SELECT count(*) FROM dinein.session_orders WHERE session_id = :id")
                        .param("id", session.id())
                        .query(Integer.class)
                        .single())
                .isEqualTo(0);
    }

    @Test
    @DisplayName("without a guest token the round is refused, not silently attributed to nobody")
    void missingGuestTokenIsRefused() throws Exception {
        TableRow table = createTable("T1");
        floorPlan.configure(
                new FloorPlanService.BranchSettings(TENANT, BRAND, LOCATION, "ORDER_AND_PAY", 15, 240, 0),
                "manager",
                "QR ordering on");
        SessionRow session = openWalkIn(table.id());

        MvcResult attempt = mvc.perform(post(roundsPath(session.id()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + UUID.randomUUID() + "\"}"))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("the header is required, not optional -- there is no other principal to fall back to")
                .isEqualTo(400);
    }

    // ------------------------------------------------------------------ helpers

    private TableRow createTable(String code) {
        return floorPlan.createTable(
                new FloorPlanService.NewTable(TENANT, BRAND, LOCATION, sectionId, code, code, 4, false, null, null));
    }

    private String rotate(TableRow table) {
        return floorPlan
                .rotateQrToken(TENANT, LOCATION, table.id(), table.version(), "manager", "First printing")
                .plaintext();
    }

    private SessionRow openWalkIn(UUID tableId) {
        return sessions.open(
                new TableSessionService.OpenSession(
                        TENANT, BRAND, LOCATION, null, List.of(tableId), 2, "UZS", "waiter"),
                "Walk-in");
    }

    private String exchange(String printedToken) throws Exception {
        MvcResult exchanged = mvc.perform(post("/api/v1/storefront/dine-in/qr/token-exchanges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tableToken\":\"" + printedToken + "\"}"))
                .andReturn();
        assertThat(exchanged.getResponse().getStatus()).isEqualTo(200);
        return json(exchanged).path("guestToken").asText();
    }

    private void attachRound(UUID sessionId, String guestToken, UUID orderId) throws Exception {
        MvcResult attached = mvc.perform(post(roundsPath(sessionId))
                        .header("X-Dine-In-Token", guestToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\"}"))
                .andReturn();
        assertThat(attached.getResponse().getStatus()).isEqualTo(200);
    }

    private static List<String> orderIdsOf(JsonNode body) {
        List<String> ids = new ArrayList<>();
        body.path("orderIds").forEach(node -> ids.add(node.asText()));
        return ids;
    }

    private static String roundsPath(UUID sessionId) {
        return "/api/v1/storefront/dine-in/sessions/" + sessionId + "/rounds";
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    // ------------------------------------------------------------------ fixtures

    private UUID seedDineInOrder(String number, long totalMinor) {
        return seedOrder(number, totalMinor, "DINE_IN");
    }

    private UUID seedOrder(String number, long totalMinor, String mode) {
        UUID orderId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash',
                        :total, 0, :total, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("publicationId", publicationId)
                .param("total", totalMinor)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, :mode, 'UZS',
                        'ACTIVE', :guest, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("channelId", channelId)
                .param("mode", mode)
                .param("guest", "guest-" + number)
                .update();

        Map<String, Object> order = new HashMap<>();
        order.put("id", orderId);
        order.put("number", number);
        order.put("tenantId", TENANT);
        order.put("brandId", BRAND);
        order.put("locationId", LOCATION);
        order.put("channelId", channelId);
        order.put("quoteId", quoteId);
        order.put("cartId", cartId);
        order.put("publicationId", publicationId);
        order.put("guest", "guest-" + number);
        order.put("mode", mode);
        order.put("total", totalMinor);

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, guest_reference_hash,
                    fulfillment_mode, acceptance_mode_snapshot, acceptance_policy_id,
                    acceptance_policy_version, approval_channel_snapshot,
                    approval_timeout_action_snapshot, status, currency, subtotal_minor, tax_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'QRTABLE',
                    :guest, :mode, 'AUTO_CONFIRM', NULL, 0, 'NONE', NULL, 'CONFIRMED', 'UZS',
                    :total, 0, :total, :quoteId, 'hash', :publicationId, :cartId, :guest,
                    1, now())
                """).params(order).update();

        return orderId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'qr-round-endpoint', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();

        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type,
                    display_name, status)
                VALUES (:id, :t, 'QRTABLE', 'QR_TABLE', 'QR table', 'ACTIVE')
                """).param("id", channelId).param("t", TENANT).update();

        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();

        publicationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :t, :b, :catalogId, 'QRTABLE', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("catalogId", catalogId)
                .update();
    }
}
