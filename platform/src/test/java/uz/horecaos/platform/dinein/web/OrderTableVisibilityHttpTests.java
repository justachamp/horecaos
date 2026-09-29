package uz.horecaos.platform.dinein.web;

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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.dinein.api.OrderTablesPort;
import uz.horecaos.platform.dinein.application.FloorPlanService;
import uz.horecaos.platform.dinein.application.TableSessionService;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.SessionRow;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.TableRow;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.StubJwtIssuer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The table beside an order, through the real HTTP stack (ADR 0047, gap map rows
 * {@code 10.5b}/{@code X.36}'s dine-in visibility follow-up): the operations
 * order board row and order detail, and the kitchen ticket, each carry the table
 * (or joined tables) and session a DINE_IN order was seated at -- read through
 * {@link OrderTablesPort}, never a join into {@code dinein.*} from ordering or
 * the kitchen.
 *
 * <p>Two tenants are seeded on purpose. The port answers from a statement whose
 * tenant is a predicate; the isolation case proves an order id that belongs to
 * another tenant, with a real seated session behind it, answers as if it did not
 * exist -- the property a screen standing open in one tenant's branch relies on.
 *
 * <p>Also covers the floor-plan settings read's {@code storefrontHostname}, the
 * address a printed table card sends a phone to: verified hostnames only, exactly
 * one candidate per tier, the tenant's own {@code QR_TABLE} channel before its
 * {@code WEB} one.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(StubJwtIssuer.class)
class OrderTableVisibilityHttpTests {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final UUID TENANT_A = UUID.fromString("018fd800-4000-7000-8000-0000000000a1");
    private static final UUID BRAND_A = UUID.fromString("018fd800-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION_A = UUID.fromString("018fd800-4000-7000-8000-0000000000c1");

    private static final UUID TENANT_B = UUID.fromString("018fd800-4000-7000-8000-0000000000a2");
    private static final UUID BRAND_B = UUID.fromString("018fd800-4000-7000-8000-0000000000b2");
    private static final UUID LOCATION_B = UUID.fromString("018fd800-4000-7000-8000-0000000000c2");

    /** Holds the location manager bundle (ORDER_READ, KITCHEN_TICKET_READ, DINEIN_FLOORPLAN_MANAGE) at tenant A's branch. */
    private static final String MANAGER_A = "order-table-http-manager-a";

    /** The same role at tenant B's branch: a different tenant entirely. */
    private static final String MANAGER_B = "order-table-http-manager-b";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the order-table visibility HTTP test");
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
    private FloorPlanService floorPlan;

    @Autowired
    @SuppressWarnings("NullAway")
    private TableSessionService sessions;

    @Autowired
    @SuppressWarnings("NullAway")
    private OrderTablesPort orderTables;

    private Fixture a = new Fixture(TENANT_A, BRAND_A, LOCATION_A);
    private Fixture b = new Fixture(TENANT_B, BRAND_B, LOCATION_B);

    /** One tenant's seeded ids -- filled in by {@link #seed}. */
    private static final class Fixture {
        final UUID tenantId;
        final UUID brandId;
        final UUID locationId;
        UUID qrChannelId = UUID.randomUUID();
        UUID webChannelId = UUID.randomUUID();
        UUID publicationId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();

        Fixture(UUID tenantId, UUID brandId, UUID locationId) {
            this.tenantId = tenantId;
            this.brandId = brandId;
            this.locationId = locationId;
        }
    }

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE dinein.session_orders, dinein.session_tables, "
                        + "dinein.table_sessions, dinein.reservation_tables, dinein.reservations, "
                        + "dinein.qr_guest_sessions, dinein.tables, dinein.sections, "
                        + "dinein.location_settings CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE kitchen.tickets CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.order_lines, ordering.orders, ordering.carts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        a = new Fixture(TENANT_A, BRAND_A, LOCATION_A);
        b = new Fixture(TENANT_B, BRAND_B, LOCATION_B);
        seed(a, "order-table-a");
        seed(b, "order-table-b");
        grant(MANAGER_A, TENANT_A, LOCATION_A);
        grant(MANAGER_B, TENANT_B, LOCATION_B);
    }

    // ---------------------------------------------------------- the operations board and detail

    @Test
    @DisplayName(
            "the order board row and the order detail carry the joined tables and session of a seated DINE_IN order")
    void boardAndDetailCarryTheTablesOfASeatedOrder() throws Exception {
        TableRow t1 = createTable(a, "T1", "Table 1");
        TableRow t2 = createTable(a, "T2", "Table 2");
        SessionRow party = openSession(a, t1.id(), t2.id());
        UUID seated = seedOrder(a, "A-1001", "DINE_IN", 45_000);
        attach(a, party.id(), seated);

        JsonNode boardRow = boardRow(a, MANAGER_A, seated);
        JsonNode table = boardRow.path("table");
        assertThat(table.path("sessionId").asText()).isEqualTo(party.id().toString());
        assertThat(codesOf(table))
                .as("a party pushed together reads in the order the tables were joined")
                .containsExactly("T1", "T2");
        assertThat(table.path("tables").get(0).path("displayName").asText()).isEqualTo("Table 1");

        JsonNode detail = detail(a, MANAGER_A, seated);
        assertThat(detail.path("summary").path("table").path("sessionId").asText())
                .isEqualTo(party.id().toString());
        assertThat(codesOf(detail.path("summary").path("table"))).containsExactly("T1", "T2");
    }

    @Test
    @DisplayName("an order nobody seated -- a keyed-in DINE_IN one, a delivery -- carries no table")
    void anUnseatedOrderCarriesNoTable() throws Exception {
        UUID keyedIn = seedOrder(a, "A-1002", "DINE_IN", 20_000);
        UUID delivery = seedOrder(a, "A-1003", "DELIVERY", 30_000);

        assertThat(boardRow(a, MANAGER_A, keyedIn).path("table").isNull()).isTrue();
        assertThat(boardRow(a, MANAGER_A, delivery).path("table").isNull()).isTrue();
        assertThat(detail(a, MANAGER_A, keyedIn).path("summary").path("table").isNull())
                .isTrue();
    }

    @Test
    @DisplayName("each row of a page gets its own table from one batch, not a neighbour's")
    void eachRowOfAPageGetsItsOwnTable() throws Exception {
        TableRow t1 = createTable(a, "T1", "Table 1");
        TableRow t3 = createTable(a, "T3", "Table 3");
        SessionRow first = openSession(a, t1.id());
        SessionRow second = openSession(a, t3.id());
        UUID atOne = seedOrder(a, "A-1004", "DINE_IN", 10_000);
        UUID atThree = seedOrder(a, "A-1005", "DINE_IN", 12_000);
        attach(a, first.id(), atOne);
        attach(a, second.id(), atThree);

        assertThat(codesOf(boardRow(a, MANAGER_A, atOne).path("table"))).containsExactly("T1");
        assertThat(codesOf(boardRow(a, MANAGER_A, atThree).path("table"))).containsExactly("T3");
    }

    // ------------------------------------------------------------------------- the kitchen ticket

    @Test
    @DisplayName(
            "the KDS ticket of a seated DINE_IN order carries its table; an unseated one and a delivery carry none")
    void theKitchenTicketCarriesTheTable() throws Exception {
        TableRow t7 = createTable(a, "T7", "Table 7");
        SessionRow party = openSession(a, t7.id());
        UUID seated = seedOrder(a, "A-2001", "DINE_IN", 45_000);
        attach(a, party.id(), seated);
        UUID keyedIn = seedOrder(a, "A-2002", "DINE_IN", 20_000);
        UUID delivery = seedOrder(a, "A-2003", "DELIVERY", 30_000);
        seedTicket(a, seated, "A-2001", "DINE_IN");
        seedTicket(a, keyedIn, "A-2002", "DINE_IN");
        seedTicket(a, delivery, "A-2003", "DELIVERY");

        MvcResult result = mvc.perform(get(kitchenPath(a) + "/tickets").with(tokenFor(MANAGER_A)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        JsonNode tickets = json(result).path("tickets");

        JsonNode seatedTicket = ticketFor(tickets, seated);
        assertThat(seatedTicket.path("table").path("sessionId").asText())
                .isEqualTo(party.id().toString());
        assertThat(codesOf(seatedTicket.path("table"))).containsExactly("T7");
        assertThat(ticketFor(tickets, keyedIn).path("table").isNull()).isTrue();
        assertThat(ticketFor(tickets, delivery).path("table").isNull()).isTrue();
    }

    // -------------------------------------------------------------------------- tenant isolation

    @Test
    @DisplayName("another tenant's seated order answers as if it did not exist, whichever tenant asks")
    void anotherTenantsOrderAnswersAsIfItDidNotExist() throws Exception {
        TableRow a1 = createTable(a, "T1", "Table 1");
        SessionRow aParty = openSession(a, a1.id());
        UUID aOrder = seedOrder(a, "A-3001", "DINE_IN", 10_000);
        attach(a, aParty.id(), aOrder);

        TableRow b9 = createTable(b, "T9", "Table 9");
        SessionRow bParty = openSession(b, b9.id());
        UUID bOrder = seedOrder(b, "B-3001", "DINE_IN", 11_000);
        attach(b, bParty.id(), bOrder);

        // The port: each tenant sees its own order, and neither sees the other's,
        // although both are real, seated, and sit in the same table.
        assertThat(orderTables.tablesByOrders(TENANT_A, List.of(aOrder, bOrder)))
                .as("tenant A asking after tenant B's order id gets nothing back for it")
                .containsOnlyKeys(aOrder);
        assertThat(orderTables.tablesByOrders(TENANT_B, List.of(aOrder, bOrder)))
                .containsOnlyKeys(bOrder);
        assertThat(orderTables.tablesByOrders(TENANT_A, List.of(bOrder))).isEmpty();
        assertThat(orderTables.tablesByOrders(TENANT_A, List.of())).isEmpty();

        // HTTP: tenant B's manager reads tenant B's board and sees only B's table.
        JsonNode bRow = boardRow(b, MANAGER_B, bOrder);
        assertThat(codesOf(bRow.path("table"))).containsExactly("T9");
        MvcResult bBoard = mvc.perform(get(ordersPath(b) + "/board").with(tokenFor(MANAGER_B)))
                .andReturn();
        assertThat(bBoard.getResponse().getContentAsString())
                .doesNotContain(aOrder.toString())
                .doesNotContain(aParty.id().toString());

        // Tenant B's manager cannot read tenant A's order detail through tenant A's path.
        MvcResult crossTenant = mvc.perform(get(ordersPath(a) + "/" + aOrder).with(tokenFor(MANAGER_B)))
                .andReturn();
        assertThat(crossTenant.getResponse().getStatus()).isIn(403, 404);
        assertThat(crossTenant.getResponse().getContentAsString()).doesNotContain("T1");
    }

    // ----------------------------------------------------- the storefront hostname for the QR card

    @Test
    @DisplayName("no verified hostname anywhere: the settings read carries none, so the console prints the bare token")
    void noHostnameMeansNoAddress() throws Exception {
        claimHostname(a, a.webChannelId, "orders.acme.uz", false);

        assertThat(settings(a, MANAGER_A).path("storefrontHostname").isNull())
                .as("an unverified custom domain is one whose DNS nobody has proven, so it is not printed")
                .isTrue();
    }

    @Test
    @DisplayName("a verified hostname on the tenant's one WEB channel is the address a scan opens")
    void aVerifiedWebHostnameIsTheAddress() throws Exception {
        claimHostname(a, a.webChannelId, "acme.stores.horecaos.uz", true);

        assertThat(settings(a, MANAGER_A).path("storefrontHostname").asText()).isEqualTo("acme.stores.horecaos.uz");
        assertThat(settings(b, MANAGER_B).path("storefrontHostname").isNull())
                .as("another tenant's hostname is not this tenant's")
                .isTrue();
    }

    @Test
    @DisplayName("the QR_TABLE channel's own verified hostname outranks the WEB channel's")
    void theQrTableChannelsOwnHostnameWins() throws Exception {
        claimHostname(a, a.webChannelId, "acme.stores.horecaos.uz", true);
        claimHostname(a, a.qrChannelId, "table.acme.uz", true);

        assertThat(settings(a, MANAGER_A).path("storefrontHostname").asText()).isEqualTo("table.acme.uz");
    }

    @Test
    @DisplayName("two WEB channels each with a verified hostname are ambiguous: no address is guessed")
    void twoWebHostnamesAreAmbiguous() throws Exception {
        claimHostname(a, a.webChannelId, "acme.stores.horecaos.uz", true);
        UUID secondWeb = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :t, 'WEB2', 'WEB', 'Second web', 'ACTIVE')
                """).param("id", secondWeb).param("t", TENANT_A).update();
        jdbc.sql("""
                INSERT INTO tenant.channel_hostnames (tenant_id, channel_id, hostname, verified)
                VALUES (:t, :c, 'second.acme.uz', true)
                """).param("t", TENANT_A).param("c", secondWeb).update();

        assertThat(settings(a, MANAGER_A).path("storefrontHostname").isNull())
                .as("with two candidates there is no telling which brand's storefront the guest should reach")
                .isTrue();
    }

    // ------------------------------------------------------------------------------ helpers

    private static List<String> codesOf(JsonNode table) {
        return java.util.stream.StreamSupport.stream(table.path("tables").spliterator(), false)
                .map(node -> node.path("code").asText())
                .toList();
    }

    private static JsonNode ticketFor(JsonNode tickets, UUID orderId) {
        for (JsonNode ticket : tickets) {
            if (orderId.toString().equals(ticket.path("orderId").asText())) {
                return ticket;
            }
        }
        throw new AssertionError("no ticket for order " + orderId + " in " + tickets);
    }

    private JsonNode boardRow(Fixture tenant, String subject, UUID orderId) throws Exception {
        MvcResult result = mvc.perform(get(ordersPath(tenant) + "/board").with(tokenFor(subject)))
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

    private JsonNode detail(Fixture tenant, String subject, UUID orderId) throws Exception {
        MvcResult result = mvc.perform(get(ordersPath(tenant) + "/" + orderId).with(tokenFor(subject)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("detail status").isEqualTo(200);
        return json(result);
    }

    private JsonNode settings(Fixture tenant, String subject) throws Exception {
        MvcResult result = mvc.perform(get("/api/v1/tenants/" + tenant.tenantId + "/brands/" + tenant.brandId
                                + "/locations/" + tenant.locationId + "/dine-in/settings")
                        .with(tokenFor(subject)))
                .andReturn();
        assertThat(result.getResponse().getStatus()).as("settings status").isEqualTo(200);
        return json(result);
    }

    private static JsonNode json(MvcResult result) throws Exception {
        return JSON.readTree(result.getResponse().getContentAsString());
    }

    private static String ordersPath(Fixture tenant) {
        return "/api/v1/tenants/" + tenant.tenantId + "/brands/" + tenant.brandId + "/locations/" + tenant.locationId
                + "/orders";
    }

    private static String kitchenPath(Fixture tenant) {
        return "/api/v1/tenants/" + tenant.tenantId + "/brands/" + tenant.brandId + "/locations/" + tenant.locationId
                + "/kitchen";
    }

    private TableRow createTable(Fixture tenant, String code, String displayName) {
        return floorPlan.createTable(new FloorPlanService.NewTable(
                tenant.tenantId,
                tenant.brandId,
                tenant.locationId,
                tenant.sectionId,
                code,
                displayName,
                4,
                false,
                null,
                null));
    }

    private SessionRow openSession(Fixture tenant, UUID... tableIds) {
        return sessions.open(
                new TableSessionService.OpenSession(
                        tenant.tenantId,
                        tenant.brandId,
                        tenant.locationId,
                        null,
                        List.of(tableIds),
                        2,
                        "UZS",
                        "waiter"),
                "Walk-in");
    }

    private void attach(Fixture tenant, UUID sessionId, UUID orderId) {
        sessions.addRound(tenant.tenantId, sessionId, orderId, null, "waiter", "Round attached");
    }

    private void claimHostname(Fixture tenant, UUID channelId, String hostname, boolean verified) {
        jdbc.sql("""
                INSERT INTO tenant.channel_hostnames (tenant_id, channel_id, hostname, verified)
                VALUES (:t, :c, :hostname, :verified)
                ON CONFLICT (tenant_id, channel_id) DO UPDATE SET hostname = :hostname, verified = :verified
                """)
                .param("t", tenant.tenantId)
                .param("c", channelId)
                .param("hostname", hostname)
                .param("verified", verified)
                .update();
    }

    /** A FIRED kitchen ticket for the order, the shape the live board reads. */
    private void seedTicket(Fixture tenant, UUID orderId, String label, String mode) {
        jdbc.sql("""
                INSERT INTO kitchen.tickets (
                    id, tenant_id, brand_id, location_id, order_id, sequence_label,
                    fulfilment_mode, channel_code, status, release_mode, released_at,
                    routing_version, version, created_at, updated_at)
                VALUES (:id, :t, :b, :loc, :orderId, :label, :mode, 'QRTABLE', 'FIRED',
                    'AUTO_ON_CONFIRM', now(), 1, 1, now(), now())
                """)
                .param("id", UUID.randomUUID())
                .param("t", tenant.tenantId)
                .param("b", tenant.brandId)
                .param("loc", tenant.locationId)
                .param("orderId", orderId)
                .param("label", label)
                .param("mode", mode)
                .update();
    }

    private UUID seedOrder(Fixture tenant, String number, String mode, long totalMinor) {
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
                .param("t", tenant.tenantId)
                .param("b", tenant.brandId)
                .param("loc", tenant.locationId)
                .param("pub", tenant.publicationId)
                .param("total", totalMinor)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, guest_reference_hash, expires_at)
                VALUES (:id, :t, :b, :loc, :ch, :mode, 'UZS', 'ACTIVE', :guest,
                        now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("t", tenant.tenantId)
                .param("b", tenant.brandId)
                .param("loc", tenant.locationId)
                .param("ch", tenant.qrChannelId)
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
                .param("t", tenant.tenantId)
                .param("b", tenant.brandId)
                .param("loc", tenant.locationId)
                .param("ch", tenant.qrChannelId)
                .param("guest", guestReference)
                .param("mode", mode)
                .param("total", totalMinor)
                .param("quote", quoteId)
                .param("pub", tenant.publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + number)
                .update();

        return orderId;
    }

    private void seed(Fixture tenant, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenant.tenantId).param("slug", slug).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", tenant.brandId).param("t", tenant.tenantId).update();

        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenant.locationId)
                .param("t", tenant.tenantId)
                .param("b", tenant.brandId)
                .update();

        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :t, 'QRTABLE', 'QR_TABLE', 'QR table', 'ACTIVE')
                """)
                .param("id", tenant.qrChannelId)
                .param("t", tenant.tenantId)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :t, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """)
                .param("id", tenant.webChannelId)
                .param("t", tenant.tenantId)
                .update();

        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", tenant.tenantId)
                .param("b", tenant.brandId)
                .update();

        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'QRTABLE', 'PUBLISHED', 'hash', now())
                """)
                .param("id", tenant.publicationId)
                .param("t", tenant.tenantId)
                .param("b", tenant.brandId)
                .param("cat", catalogId)
                .update();

        tenant.sectionId = floorPlan
                .createSection(new FloorPlanService.NewSection(
                        tenant.tenantId, tenant.brandId, tenant.locationId, "HALL", "Hall", 0))
                .id();
    }

    private void grant(String subject, UUID tenantId, UUID locationId) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'LOCATION', :scopeId,
                        'ACTIVE', 'test-fixture', 'order-table visibility http test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param(
                        "id",
                        UUID.nameUUIDFromBytes(
                                (subject + PlatformRole.LOCATION_MANAGER.code() + locationId).getBytes(UTF_8)))
                .param("tenantId", tenantId)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(PlatformRole.LOCATION_MANAGER))
                .param("scopeId", locationId)
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }
}
