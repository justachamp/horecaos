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
import org.jspecify.annotations.Nullable;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.customers.infrastructure.security.PresetVerificationCodeSource;
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

    /** Same preset the OTP dev persona and {@code CustomerSessionSurfaceTests} use. */
    private static final String PRESET_PHONE = "+998000000000";

    private static final String PRESET_CODE = "000000";

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
        // The preset OTP identity, the same way CustomerSessionSurfaceTests signs
        // in: addRound now checks a round against the placing customer's own
        // session, so proving that check needs a real one, not a jwt() shortcut.
        registry.add(PresetVerificationCodeSource.PHONE_PROPERTY, () -> PRESET_PHONE);
        registry.add(PresetVerificationCodeSource.CODE_PROPERTY, () -> PRESET_CODE);
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
        SignedIn customer = signIn();
        UUID orderId = seedDineInOrder("T1-001", 45_000, customer.accountId());

        String guestToken = exchange(printed);

        MvcResult attach = mvc.perform(post(roundsPath(session.id()))
                        .header("X-Dine-In-Token", guestToken)
                        .with(session(customer.token()))
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
        SignedIn customer = signIn();
        UUID orderId = seedDineInOrder("T1-002", 30_000, customer.accountId());
        String guestToken = exchange(printed);

        attachRound(session.id(), guestToken, customer.token(), orderId);
        MvcResult retry = mvc.perform(post(roundsPath(session.id()))
                        .header("X-Dine-In-Token", guestToken)
                        .with(session(customer.token()))
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
        SignedIn customer = signIn();
        UUID deliveryOrder = seedOrder("T1-003", 18_000, "DELIVERY", customer.accountId());
        String guestToken = exchange(printed);

        MvcResult attempt = mvc.perform(post(roundsPath(session.id()))
                        .header("X-Dine-In-Token", guestToken)
                        .with(session(customer.token()))
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
    @DisplayName("a guest cannot attach an order placed by a different customer, even at their own table")
    void guestCannotAttachAnotherCustomersOrder() throws Exception {
        // The vulnerability this closes: the guest token only ever proved "this
        // device is at table T1" -- it said nothing about which order the caller
        // is allowed to attach. Before addRound checked ownership, an attacker
        // holding a valid guest token for their own table and *any* not-yet-billed
        // DINE_IN order id at the branch -- a friend's receipt, a link shared from
        // another table, a guess -- could redirect that order's charge onto their
        // own table's bill, exactly the reach across bills ADR 0047 forbids.
        TableRow table = createTable("T1");
        floorPlan.configure(
                new FloorPlanService.BranchSettings(TENANT, BRAND, LOCATION, "ORDER_AND_PAY", 15, 240, 0),
                "manager",
                "QR ordering on");
        String printed = rotate(table);
        SessionRow session = openWalkIn(table.id());

        SignedIn attacker = signIn();
        UUID victimAccountId = seedAnotherCustomerAccount();
        UUID victimsOrder = seedDineInOrder("T1-004", 99_000, victimAccountId);

        String guestToken = exchange(printed);

        MvcResult attempt = mvc.perform(post(roundsPath(session.id()))
                        .header("X-Dine-In-Token", guestToken)
                        .with(session(attacker.token()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + victimsOrder + "\"}"))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("an order that exists but is not the caller's own reads exactly like one "
                        + "that does not exist -- the same refusal the order lookup itself gives, "
                        + "so a guest fishing for another table's order id learns nothing either way")
                .isEqualTo(404);
        assertThat(jdbc.sql("SELECT count(*) FROM dinein.session_orders WHERE session_id = :id")
                        .param("id", session.id())
                        .query(Integer.class)
                        .single())
                .as("the victim's order must not have been redirected onto the attacker's bill")
                .isEqualTo(0);
    }

    @Test
    @DisplayName("attaching a round with no customer session at all is refused, not attributed to nobody")
    void attachingWithNoCustomerSessionIsRefused() throws Exception {
        TableRow table = createTable("T1");
        floorPlan.configure(
                new FloorPlanService.BranchSettings(TENANT, BRAND, LOCATION, "ORDER_AND_PAY", 15, 240, 0),
                "manager",
                "QR ordering on");
        String printed = rotate(table);
        SessionRow session = openWalkIn(table.id());
        UUID orderId = seedDineInOrder("T1-005", 10_000, seedAnotherCustomerAccount());
        String guestToken = exchange(printed);

        MvcResult attempt = mvc.perform(post(roundsPath(session.id()))
                        .header("X-Dine-In-Token", guestToken)
                        // Deliberately no Authorization header: the guest token alone
                        // is not proof the round is this caller's own.
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\"}"))
                .andReturn();

        assertThat(attempt.getResponse().getStatus())
                .as("no signed-in session means nobody to check the order's ownership against")
                .isEqualTo(401);
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

    private void attachRound(UUID sessionId, String guestToken, String customerToken, UUID orderId) throws Exception {
        MvcResult attached = mvc.perform(post(roundsPath(sessionId))
                        .header("X-Dine-In-Token", guestToken)
                        .with(session(customerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":\"" + orderId + "\"}"))
                .andReturn();
        assertThat(attached.getResponse().getStatus()).isEqualTo(200);
    }

    /** The whole journey: ask for a code, type it, exchange the grant (ADR 0051). */
    private SignedIn signIn() throws Exception {
        MvcResult challenge = mvc.perform(post(identity() + "/verification-challenges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"phone\":\"" + PRESET_PHONE + "\"}"))
                .andReturn();
        assertThat(challenge.getResponse().getStatus()).isEqualTo(202);
        String challengeId = json(challenge).path("challengeId").asText();

        MvcResult attempt = mvc.perform(post(identity() + "/verification-challenges/" + challengeId + "/attempts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + PRESET_CODE + "\"}"))
                .andReturn();
        assertThat(attempt.getResponse().getStatus())
                .as("the preset code is the code the challenge was written with")
                .isEqualTo(200);
        String grant = json(attempt).path("grant").asText();

        MvcResult session = mvc.perform(post(identity() + "/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grant\":\"" + grant + "\"}"))
                .andReturn();
        assertThat(session.getResponse().getStatus()).isIn(200, 201);

        JsonNode body = json(session);
        return new SignedIn(
                body.path("token").asText(),
                UUID.fromString(body.path("accountId").asText()));
    }

    private record SignedIn(String token, UUID accountId) {}

    /**
     * The customer's own credential, in the header a browser sends it in (ADR
     * 0051). Deliberately not a {@code jwt()} post-processor -- that would set a
     * security context directly and step over the same bearer-token resolver
     * {@code addRound} now depends on.
     */
    private static RequestPostProcessor session(String token) {
        return request -> {
            request.addHeader("Authorization", "Bearer " + token);
            return request;
        };
    }

    /** Another account entirely -- inserted directly, the way a real returning
     * customer's row already exists rather than being minted through this test. */
    private UUID seedAnotherCustomerAccount() {
        UUID accountId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status)
                VALUES (:id, :t, 'ACTIVE')
                """).param("id", accountId).param("t", TENANT).update();
        return accountId;
    }

    private static String brand() {
        return "/api/v1/storefront/tenants/" + TENANT + "/brands/" + BRAND;
    }

    private static String identity() {
        return brand() + "/identity";
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

    /** A DINE_IN order placed by nobody in particular -- fine wherever the round
     * is refused before {@code addRound}'s ownership check is even reached. */
    private UUID seedDineInOrder(String number, long totalMinor) {
        return seedOrder(number, totalMinor, "DINE_IN", null);
    }

    /** A DINE_IN order placed by exactly this account -- what a real QR checkout
     * always produces, per {@code POST .../carts} having no anonymous path. */
    private UUID seedDineInOrder(String number, long totalMinor, UUID ownerAccountId) {
        return seedOrder(number, totalMinor, "DINE_IN", ownerAccountId);
    }

    private UUID seedOrder(String number, long totalMinor, String mode, @Nullable UUID ownerAccountId) {
        UUID orderId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        // ck_cart_owner_xor / ck_order_owner: exactly one of the two is set. Every
        // order this suite's guest actually attaches goes through the owner path,
        // matching what checkout always writes today (no anonymous cart at all);
        // the guest-hash path stays available for fixtures the ownership check
        // never reaches.
        String guestReference = ownerAccountId == null ? "guest-" + number : null;

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
                    fulfillment_mode, currency, status, customer_account_id, guest_reference_hash,
                    expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, :mode, 'UZS',
                        'ACTIVE', :owner, :guest, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("channelId", channelId)
                .param("mode", mode)
                .param("owner", ownerAccountId)
                .param("guest", guestReference)
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
        order.put("owner", ownerAccountId);
        order.put("guest", guestReference);
        order.put("mode", mode);
        order.put("total", totalMinor);
        // The idempotency key only has to be unique per tenant here; the order
        // number does too, and both already are since `number` is.
        order.put("idempotencyKey", "idem-" + number);

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, customer_account_id,
                    guest_reference_hash, fulfillment_mode, acceptance_mode_snapshot,
                    acceptance_policy_id, acceptance_policy_version, approval_channel_snapshot,
                    approval_timeout_action_snapshot, status, currency, subtotal_minor, tax_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'QRTABLE',
                    :owner, :guest, :mode, 'AUTO_CONFIRM', NULL, 0, 'NONE', NULL, 'CONFIRMED', 'UZS',
                    :total, 0, :total, :quoteId, 'hash', :publicationId, :cartId, :idempotencyKey,
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
                INSERT INTO tenant.customer_identity_policies (
                    id, tenant_id, version, identity_mode, effective_from)
                VALUES (:id, :t, 1, 'TENANT_SHARED', TIMESTAMPTZ '2020-01-01T00:00:00Z')
                ON CONFLICT DO NOTHING
                """)
                .param(
                        "id",
                        UUID.nameUUIDFromBytes(TENANT.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .param("t", TENANT)
                .update();

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
