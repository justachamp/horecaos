package uz.horecaos.platform.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderBoardRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderListQuery;
import uz.horecaos.platform.ordering.web.OperationsOrderController;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcGlobalLookup;
import uz.horecaos.platform.web.api.Cursor;

/**
 * The order board's query (ADR 0102, orders.md §2.4-§2.8).
 *
 * <p>Every filter is asserted the same way: a fixture holding orders that differ
 * in exactly the one attribute under test, and an assertion naming both the
 * order that must come back and the ones that must not. Asserting only the
 * former passes for a predicate that was never applied at all, which is how a
 * filter that quietly returns everything survives a green suite.
 *
 * <p>The store is exercised directly rather than through the controller because
 * the controller's own job here is thin — decode a cursor, map rows, mint the
 * next cursor — and the thing that can be wrong is the SQL.
 */
class OrderBoardQueryTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-3000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f6f4e-3000-7000-8000-0000000000a2");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-3000-7000-8000-0000000000a3");
    private static final UUID OTHER_LOCATION = UUID.fromString("018f6f4e-3000-7000-8000-0000000000a4");
    private static final UUID CUSTOMER = UUID.fromString("018f6f4e-3000-7000-8000-0000000000a5");
    private static final UUID COURIER_TYPE = UUID.fromString("018f6f4e-3000-7000-8000-0000000000a6");
    private static final UUID COURIER = UUID.fromString("018f6f4e-3000-7000-8000-0000000000a7");
    private static final UUID OTHER_COURIER = UUID.fromString("018f6f4e-3000-7000-8000-0000000000a8");

    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-3000-7000-8000-0000000000b1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f6f4e-3000-7000-8000-0000000000b2");
    private static final UUID OTHER_TENANT_LOCATION = UUID.fromString("018f6f4e-3000-7000-8000-0000000000b3");

    /** Every fixture order is placed relative to this, newest last. */
    private static final Instant NOON = Instant.parse("2026-09-10T12:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcOrderStore store;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);

        jdbc.sql("TRUNCATE TABLE fulfillment.shipments, fulfillment.delivery_plans CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE payments.payment_intents CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.orders CASCADE").update();
        jdbc.sql("TRUNCATE TABLE fulfillment.couriers, fulfillment.courier_types CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.catalogs CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        store = new JdbcOrderStore(jdbc);
        seedTenancy();
    }

    // ------------------------------------------------------------- the filters

    @Test
    @DisplayName("the period is half-open: the lower bound is included and the upper is not")
    void thePeriodIsHalfOpen() {
        UUID early = insertOrder(order("E-1").createdAt(NOON));
        UUID onTheBoundary = insertOrder(order("E-2").createdAt(NOON.plusSeconds(3600)));
        UUID late = insertOrder(order("E-3").createdAt(NOON.plusSeconds(7200)));

        assertThat(idsOf(query().from(NOON).to(NOON.plusSeconds(3600))))
                .as("from is inclusive, to is exclusive, so two adjacent periods share no order")
                .containsExactly(early)
                .doesNotContain(onTheBoundary, late);

        assertThat(idsOf(query().from(NOON.plusSeconds(3600))))
                .as("an open upper bound still excludes what is before the lower one")
                .containsExactly(late, onTheBoundary);
    }

    @Test
    @DisplayName("the channel filter matches the snapshot the order carries, not the live channel")
    void theChannelCodeFilterMatchesTheSnapshot() {
        UUID telegram = insertOrder(order("C-1").channelCode("TELEGRAM"));
        UUID web = insertOrder(order("C-2").channelCode("WEB"));

        assertThat(idsOf(query().channelCode("TELEGRAM")))
                .containsExactly(telegram)
                .doesNotContain(web);
        assertThat(idsOf(query().channelCode("WEB"))).containsExactly(web);
        assertThat(idsOf(query().channelCode("NOT_A_CHANNEL"))).isEmpty();
    }

    @Test
    @DisplayName("the fulfilment mode filter separates delivery from pickup")
    void theFulfillmentModeFilter() {
        UUID delivery = insertOrder(order("F-1").mode("DELIVERY"));
        UUID pickup = insertOrder(order("F-2").mode("PICKUP"));

        assertThat(idsOf(query().fulfillmentMode("DELIVERY")))
                .containsExactly(delivery)
                .doesNotContain(pickup);
        assertThat(idsOf(query().fulfillmentMode("PICKUP"))).containsExactly(pickup);
    }

    @Test
    @DisplayName("the courier filter returns the order that courier carries and no other")
    void theCourierFilterFindsOnlyTheOrdersThatCourierCarries() {
        UUID mine = insertOrder(order("K-1"));
        UUID theirs = insertOrder(order("K-2"));
        UUID unassigned = insertOrder(order("K-3"));
        assignCourier(mine, COURIER);
        assignCourier(theirs, OTHER_COURIER);

        assertThat(idsOf(query().courierId(COURIER))).containsExactly(mine).doesNotContain(theirs, unassigned);
        assertThat(idsOf(query().courierId(OTHER_COURIER))).containsExactly(theirs);
    }

    @Test
    @DisplayName("the payment method filter reads the order's intent, not the channel's matrix")
    void thePaymentMethodFilter() {
        UUID cash = insertOrder(order("P-1"));
        UUID card = insertOrder(order("P-2"));
        UUID nothing = insertOrder(order("P-3"));
        insertPaymentIntent(cash, "CASH", "CASH");
        insertPaymentIntent(card, "PROVIDER", "CLICK");

        assertThat(idsOf(query().paymentMethodCode("CASH")))
                .containsExactly(cash)
                .doesNotContain(card, nothing);
        assertThat(idsOf(query().paymentMethodCode("CLICK"))).containsExactly(card);
    }

    @Test
    @DisplayName("Мои заказы: the creating actor filter")
    void theCreatingActorFilter() {
        UUID mine = insertOrder(order("A-1").createdByActorId("operator-7"));
        UUID theirs = insertOrder(order("A-2").createdByActorId("operator-9"));

        assertThat(idsOf(query().createdByActorId("operator-7")))
                .containsExactly(mine)
                .doesNotContain(theirs);
    }

    @Test
    @DisplayName("the status filter still narrows, and an empty one still returns everything")
    void theStatusFilterStillWorks() {
        UUID received = insertOrder(order("S-1").status("RECEIVED"));
        UUID ready = insertOrder(order("S-2").status("READY").confirmed());

        assertThat(idsOf(query().statuses("READY"))).containsExactly(ready).doesNotContain(received);
        assertThat(idsOf(query())).containsExactlyInAnyOrder(received, ready);
    }

    @Test
    @DisplayName("a reference is matched in its normalised form, however the operator typed it")
    void theReferenceFilterMatchesTheNormalisedForm() {
        UUID wolt = insertOrder(order("R-1"));
        UUID other = insertOrder(order("R-2"));
        insertReference(TENANT, wolt, "YE-2291-04");
        insertReference(TENANT, other, "WLT-88213");

        for (String typed : List.of("YE-2291-04", "ye 2291 04", "#YE229104", "  ye229104  ")) {
            assertThat(idsOf(query().reference(typed)))
                    .as("an operator reading \"%s\" off a courier's phone finds the same order", typed)
                    .containsExactly(wolt)
                    .doesNotContain(other);
        }

        assertThat(idsOf(query().reference("nothing-like-it"))).isEmpty();
    }

    @Test
    @DisplayName("the same parameter finds an order by its own number, however the operator typed it")
    void theReferenceFilterAlsoMatchesTheOrdersOwnNumber() {
        // orders.md §2.8 resolution kind 1. The operator holding a number does
        // not know whether it is ours or the aggregator's, and asking them to
        // pick the right box before they can search is the thing that makes a
        // four-second answer a four-minute one.
        UUID ours = insertOrder(order("0911-142"));
        UUID neighbour = insertOrder(order("0911-143"));
        insertReference(TENANT, neighbour, "YE-2291-04");

        for (String typed : List.of("0911-142", "0911 142", "#0911142", " 0911142 ")) {
            assertThat(idsOf(query().reference(typed)))
                    .as("\"%s\" is the same number to the person reading it out", typed)
                    .containsExactly(ours)
                    .doesNotContain(neighbour);
        }

        assertThat(idsOf(query().reference("YE-2291-04")))
                .as("and the aggregator's own id still reaches the order it was issued for")
                .containsExactly(neighbour);

        assertThat(idsOf(query().reference("142")))
                .as("the counter alone is not the number: matching it would need a prefix or "
                        + "suffix rule, which is §2.8's search endpoint's job and not a filter's")
                .isEmpty();
    }

    // -------------------------------------------------------------- the scope

    @Test
    @DisplayName("a reference belonging to another tenant's order is not found here")
    void aReferenceFromAnotherTenantIsRefused() {
        UUID mine = insertOrder(order("X-1"));
        UUID theirs = insertOrderForOtherTenant("X-2");
        insertReference(TENANT, mine, "WLT-100");
        insertReference(OTHER_TENANT, theirs, "WLT-200");

        assertThat(idsOf(query().reference("WLT-200")))
                .as("the reference exists, at another tenant, and this location's board must not see it")
                .isEmpty();
        assertThat(idsOf(query().reference("WLT-100"))).containsExactly(mine);
        assertThat(idsOf(query().reference("X-2")))
                .as("nor the other tenant's order number, which the same parameter also matches")
                .isEmpty();

        // The case V0038's own index comment describes: two aggregators issue the
        // same short code on the same day, at two tenants. A board that answered
        // this from the reference table alone — without correlating the row back
        // to an order of its own — would hand one tenant the other's order, and
        // the assertion above would not notice because those two references
        // differ.
        insertReference(TENANT, mine, "SAME-77");
        insertReference(OTHER_TENANT, theirs, "SAME-77");
        assertThat(idsOf(query().reference("same 77")))
                .as("one code, two tenants, and each board sees only its own order")
                .containsExactly(mine);

        // And the reverse, so the assertion above cannot pass because the second
        // tenant's row was never written.
        OrderListQuery theirBoard = new OrderListQuery(
                OTHER_TENANT,
                OTHER_BRAND,
                OTHER_TENANT_LOCATION,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                "WLT-200");
        assertThat(store.listForLocation(theirBoard, null, null, 50).stream()
                        .map(row -> row.order().orderId())
                        .toList())
                .containsExactly(theirs);
    }

    @Test
    @DisplayName("a reference belonging to another location of the same brand is not found here")
    void aReferenceFromAnotherLocationIsRefused() {
        UUID hereOrder = insertOrder(order("L-1"));
        UUID thereOrder = insertOrder(order("L-2").location(OTHER_LOCATION));
        insertReference(TENANT, thereOrder, "WLT-300");

        assertThat(idsOf(query().reference("WLT-300")))
                .as("orders.md §2.8's tenant-wide search is a different endpoint at a different scope")
                .isEmpty();
        // And the order-number half of the same parameter, which is the one that
        // would otherwise reach across branches: `public_order_number` is scoped
        // per location per business date and therefore repeats, so a predicate
        // that forgot the location would answer with the wrong branch's order
        // rather than with none.
        assertThat(idsOf(query().reference("L-2")))
                .as("another branch's order number is another branch's order")
                .isEmpty();
        assertThat(idsOf(query().reference("L-1"))).containsExactly(hereOrder);
        assertThat(idsOf(query())).containsExactly(hereOrder);
    }

    @Test
    @DisplayName("a cursor naming another tenant's order resolves to nothing")
    void aCursorFromAnotherTenantResolvesToNothing() {
        UUID mine = insertOrder(order("Z-1"));
        UUID theirs = insertOrderForOtherTenant("Z-2");

        assertThat(store.locationOrderCursor(TENANT, BRAND, LOCATION, mine)).isPresent();
        assertThat(store.locationOrderCursor(TENANT, BRAND, LOCATION, theirs))
                .as("a cursor is resolved inside the caller's own scope or not at all")
                .isEmpty();
        assertThat(store.locationOrderCursor(TENANT, BRAND, OTHER_LOCATION, mine))
                .as("and the location is part of that scope, not only the tenant")
                .isEmpty();
    }

    // ------------------------------------------------------------- the cursor

    @Test
    @DisplayName("paging holds its place while newer orders arrive")
    void theCursorHoldsItsPlaceWhileNewOrdersArrive() {
        List<UUID> existing = List.of(
                insertOrder(order("N-1").createdAt(NOON)),
                insertOrder(order("N-2").createdAt(NOON.plusSeconds(60))),
                insertOrder(order("N-3").createdAt(NOON.plusSeconds(120))),
                insertOrder(order("N-4").createdAt(NOON.plusSeconds(180))),
                insertOrder(order("N-5").createdAt(NOON.plusSeconds(240))));

        List<OrderBoardRow> first = store.listForLocation(query().build(), null, null, 2);
        assertThat(first).hasSize(2);

        // The interleaving an offset cannot survive: two orders arrive at the top
        // of the list between the first page and the second.
        insertOrder(order("N-6").createdAt(NOON.plusSeconds(300)));
        insertOrder(order("N-7").createdAt(NOON.plusSeconds(360)));

        List<UUID> walked = new java.util.ArrayList<>(idsOf(first));
        UUID cursor = first.getLast().order().orderId();
        while (true) {
            Instant at =
                    store.locationOrderCursor(TENANT, BRAND, LOCATION, cursor).orElseThrow();
            List<OrderBoardRow> page = store.listForLocation(query().build(), at, cursor, 2);
            if (page.isEmpty()) {
                break;
            }
            walked.addAll(idsOf(page));
            cursor = page.getLast().order().orderId();
        }

        assertThat(walked)
                .as("every order that existed when paging began, exactly once, newest first")
                .containsExactlyElementsOf(existing.reversed())
                .doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("orders sharing one instant are all returned, exactly once")
    void aTiedInstantDoesNotSwallowARow() {
        // Six orders on the same microsecond: ordinary at a busy branch at the
        // top of the hour, and the case a sort key that is not unique gets wrong.
        // The sort must agree with the keyset predicate on every column it
        // compares, or a page boundary falling inside the tie loses whichever
        // rows the predicate excludes but the sort had not yet reached.
        List<UUID> tied = List.of(
                insertOrder(order("T-1").createdAt(NOON)),
                insertOrder(order("T-2").createdAt(NOON)),
                insertOrder(order("T-3").createdAt(NOON)),
                insertOrder(order("T-4").createdAt(NOON)),
                insertOrder(order("T-5").createdAt(NOON)),
                insertOrder(order("T-6").createdAt(NOON)));

        assertThat(walkEveryPage(2))
                .as("no row is skipped and none repeats, however the tie is physically ordered")
                .containsExactlyInAnyOrderElementsOf(tied)
                .doesNotHaveDuplicates();
    }

    /** Pages the whole board with the given page size and returns what it saw. */
    private List<UUID> walkEveryPage(int pageSize) {
        List<UUID> seen = new java.util.ArrayList<>();
        @Nullable Instant at = null;
        @Nullable UUID cursor = null;
        while (true) {
            List<OrderBoardRow> page = store.listForLocation(query().build(), at, cursor, pageSize);
            if (page.isEmpty()) {
                return seen;
            }
            seen.addAll(idsOf(page));
            cursor = page.getLast().order().orderId();
            at = store.locationOrderCursor(TENANT, BRAND, LOCATION, cursor).orElseThrow();
        }
    }

    @Test
    @DisplayName("a cursor minted for one filter set is refused for another")
    void aCursorIsPinnedToItsFilterSet() {
        OrderListQuery narrow = query().channelCode("TELEGRAM").build();
        OrderListQuery wide = query().build();

        assertThat(narrow.fingerprint()).isNotEqualTo(wide.fingerprint());

        String token = new Cursor("some-order-id", narrow.fingerprint()).encodeUnsigned();

        assertThat(Cursor.decodeUnsigned(token, narrow.fingerprint()))
                .as("the filter set it was minted for still accepts it")
                .isPresent();
        assertThat(Cursor.decodeUnsigned(token, wide.fingerprint()))
                .as("dropping a filter mid-iteration invalidates the cursor rather than paging incoherently")
                .isEmpty();
        assertThat(Cursor.decodeUnsigned("not-a-cursor-at-all", narrow.fingerprint()))
                .isEmpty();
    }

    @Test
    @DisplayName("the order of the status parameters does not change the cursor")
    void theFingerprintIsOrderIndependentInStatuses() {
        OrderListQuery one = query().statuses("READY", "RECEIVED").build();
        OrderListQuery other = query().statuses("RECEIVED", "READY").build();

        assertThat(one.fingerprint()).isEqualTo(other.fingerprint());
        assertThat(query().statuses("READY").build().fingerprint())
                .as("but dropping one of them is a different query")
                .isNotEqualTo(one.fingerprint());
    }

    @Test
    @DisplayName("two filter sets that would concatenate alike still fingerprint differently")
    void theFingerprintSeparatesItsFields() {
        OrderListQuery one = query().channelCode("AB").build();
        OrderListQuery other = query().channelCode("A").fulfillmentMode("B").build();

        assertThat(one.fingerprint()).isNotEqualTo(other.fingerprint());
    }

    // ------------------------------------------------------- the derived flag

    @Test
    @DisplayName("the process flag names the worse of the two failure states")
    void theProcessAttentionFlagPrefersTheWorseState() {
        UUID blocked = insertOrder(order("W-1"));
        UUID retrying = insertOrder(order("W-2"));
        UUID fine = insertOrder(order("W-3"));

        insertProcess(retrying, "ORDER_PAYMENT", "FAILED_RETRYABLE");
        insertProcess(blocked, "ORDER_PAYMENT", "FAILED_RETRYABLE");
        insertProcess(blocked, "ORDER_INVENTORY", "MANUAL_ACTION_REQUIRED");
        insertProcess(fine, "ORDER_PAYMENT", "COMPLETED");

        List<OrderBoardRow> rows = store.listForLocation(query().build(), null, null, 50);

        assertThat(attentionOf(rows, blocked))
                .as("an order that needs an operator is BLOCKED even while another process still retries")
                .isEqualTo("MANUAL_ACTION_REQUIRED");
        assertThat(attentionOf(rows, retrying)).isEqualTo("FAILED_RETRYABLE");
        assertThat(attentionOf(rows, fine))
                .as("a completed process is not an attention state")
                .isNull();
    }

    // ------------------------------------------------------------ the counts

    @Test
    @DisplayName("the badges count the period the list is filtered by")
    void theCountsRespectThePeriod() {
        insertOrder(order("B-1").createdAt(NOON).status("RECEIVED"));
        insertOrder(order("B-2").createdAt(NOON.plusSeconds(7200)).status("RECEIVED"));

        assertThat(store.counts(TENANT, BRAND, LOCATION).total()).isEqualTo(2);
        assertThat(store.counts(TENANT, BRAND, LOCATION, NOON, NOON.plusSeconds(3600))
                        .total())
                .as("a badge over a different population than its own tab is worse than no badge")
                .isEqualTo(1);
        assertThat(store.counts(TENANT, BRAND, LOCATION, NOON.plusSeconds(3600), null)
                        .newOrders())
                .isEqualTo(1);
    }

    // ------------------------------------------------------ the contract rules

    @Test
    @DisplayName("no field of the board row is personal data")
    void theResponseCarriesNoPersonalField() {
        List<String> forbidden =
                List.of("name", "phone", "email", "address", "note", "comment", "contact", "instruction");

        List<String> offending = java.util.Arrays.stream(
                        OperationsOrderController.OrderSummaryResponse.class.getRecordComponents())
                .map(RecordComponent::getName)
                .filter(component -> {
                    String lowered = component.toLowerCase(Locale.ROOT);
                    // publicOrderNumber is the branch's own counter, not a person.
                    return !lowered.equals("publicordernumber")
                            && forbidden.stream().anyMatch(lowered::contains);
                })
                .toList();

        assertThat(offending)
                .as("orders.md §1.5: the board renders on a screen standing open in a branch all day")
                .isEmpty();
    }

    @Test
    @DisplayName("the reference normaliser agrees with the one the global lookup uses")
    void theReferenceNormaliserAgreesWithTheGlobalLookup() {
        for (String raw : List.of("YE-2291-04", "ye 2291 04", "#WLT-88213", "  glovo 77 ", "uzum-1", "#", " - ")) {
            assertThat(JdbcOrderStore.normalisedExternalReference(raw))
                    .as("three copies of one rule, held together by this assertion: \"%s\"", raw)
                    .isEqualTo(JdbcGlobalLookup.partnerForm(raw));
        }
    }

    // ------------------------------------------------------------- the fixture

    private List<UUID> idsOf(List<OrderBoardRow> rows) {
        return rows.stream().map(row -> row.order().orderId()).toList();
    }

    private List<UUID> idsOf(QueryBuilder builder) {
        return idsOf(store.listForLocation(builder.build(), null, null, 50));
    }

    private static @Nullable String attentionOf(List<OrderBoardRow> rows, UUID orderId) {
        return rows.stream()
                .filter(row -> row.order().orderId().equals(orderId))
                .findFirst()
                .orElseThrow()
                .processAttention();
    }

    private QueryBuilder query() {
        return new QueryBuilder();
    }

    /** Keeps the twelve-argument query record out of every assertion above. */
    private static final class QueryBuilder {
        private List<String> statuses = List.of();
        private @Nullable Instant from;
        private @Nullable Instant to;
        private @Nullable String channelCode;
        private @Nullable String fulfillmentMode;
        private @Nullable UUID courierId;
        private @Nullable String paymentMethodCode;
        private @Nullable String createdByActorId;
        private @Nullable String reference;

        QueryBuilder statuses(String... values) {
            this.statuses = List.of(values);
            return this;
        }

        QueryBuilder from(Instant value) {
            this.from = value;
            return this;
        }

        QueryBuilder to(Instant value) {
            this.to = value;
            return this;
        }

        QueryBuilder channelCode(String value) {
            this.channelCode = value;
            return this;
        }

        QueryBuilder fulfillmentMode(String value) {
            this.fulfillmentMode = value;
            return this;
        }

        QueryBuilder courierId(UUID value) {
            this.courierId = value;
            return this;
        }

        QueryBuilder paymentMethodCode(String value) {
            this.paymentMethodCode = value;
            return this;
        }

        QueryBuilder createdByActorId(String value) {
            this.createdByActorId = value;
            return this;
        }

        QueryBuilder reference(String value) {
            this.reference = value;
            return this;
        }

        OrderListQuery build() {
            return new OrderListQuery(
                    TENANT,
                    BRAND,
                    LOCATION,
                    statuses,
                    from,
                    to,
                    channelCode,
                    fulfillmentMode,
                    courierId,
                    paymentMethodCode,
                    createdByActorId,
                    reference);
        }
    }

    private OrderSpec order(String seed) {
        return new OrderSpec(seed);
    }

    /** One fixture order, with the board-relevant attributes it may differ in. */
    private static final class OrderSpec {
        private final String seed;
        private UUID location = LOCATION;
        private Instant createdAt = NOON;
        private String status = "RECEIVED";
        private String mode = "DELIVERY";
        private String channelCode = "TELEGRAM";
        private @Nullable String createdByActorId;
        private boolean confirmed;

        private OrderSpec(String seed) {
            this.seed = seed;
        }

        OrderSpec location(UUID value) {
            this.location = value;
            return this;
        }

        OrderSpec createdAt(Instant value) {
            this.createdAt = value;
            return this;
        }

        OrderSpec status(String value) {
            this.status = value;
            return this;
        }

        OrderSpec mode(String value) {
            this.mode = value;
            return this;
        }

        OrderSpec channelCode(String value) {
            this.channelCode = value;
            return this;
        }

        OrderSpec createdByActorId(String value) {
            this.createdByActorId = value;
            return this;
        }

        OrderSpec confirmed() {
            this.confirmed = true;
            return this;
        }
    }

    private UUID insertOrder(OrderSpec spec) {
        return insertOrder(TENANT, BRAND, spec.location, spec);
    }

    private UUID insertOrderForOtherTenant(String seed) {
        return insertOrder(OTHER_TENANT, OTHER_BRAND, OTHER_TENANT_LOCATION, order(seed));
    }

    private UUID insertOrder(UUID tenantId, UUID brandId, UUID locationId, OrderSpec spec) {
        UUID orderId = derived("order:" + tenantId + spec.seed);
        UUID cartId = derived("cart:" + tenantId + spec.seed);
        UUID quoteId = derived("quote:" + tenantId + spec.seed);

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    customer_account_id, fulfillment_mode, currency, status, expires_at,
                    converted_order_id)
                VALUES (:id, :t, :b, :loc, :ch, :cust, :mode, 'UZS', 'CONVERTED', :expires, :orderId)
                """)
                .param("id", cartId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("ch", channelOf(tenantId))
                .param("cust", customerOf(tenantId))
                .param("mode", spec.mode)
                .param("expires", spec.createdAt.atOffset(ZoneOffset.UTC))
                .param("orderId", orderId)
                .update();

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id,
                    customer_account_id, currency, status, catalog_publication_id,
                    calculation_version, context_hash, subtotal_minor, tax_minor, fee_minor,
                    discount_minor, total_minor, expires_at, accepted_at)
                VALUES (:id, :t, :b, :loc, :cust, 'UZS', 'ACCEPTED', :pub, 1, :hash,
                    100000, 0, 2000, 1000, 101000, :expires, :accepted)
                """)
                .param("id", quoteId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("cust", customerOf(tenantId))
                .param("pub", publicationOf(tenantId))
                .param("hash", "hash-" + orderId)
                .param("expires", spec.createdAt.atOffset(ZoneOffset.UTC))
                .param("accepted", spec.createdAt.atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, customer_account_id,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot,
                    status, payment_status_projection, currency, subtotal_minor, tax_minor,
                    discount_minor, fee_minor, total_minor, pricing_quote_id,
                    pricing_context_hash, catalog_publication_id, cart_id, idempotency_key,
                    promised_at, promise_basis, promise_prep_minutes, version, created_at,
                    confirmed_at, created_by_actor_type, created_by_actor_id,
                    accepted_by_actor_type, accepted_by_actor_id, accepted_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, :channelCode, :cust,
                    :mode, 'AUTO_CONFIRM', 'NONE',
                    :status, 'NOT_REQUIRED', 'UZS', 100000, 0,
                    1000, 2000, 101000, :quote,
                    :hash, :pub, :cart, :key,
                    :promisedAt, 'PREPARATION_BAND', 35, 1, :createdAt,
                    :confirmedAt, 'USER', :createdBy,
                    'USER', 'manager-1', :createdAt)
                """)
                .param("id", orderId)
                .param("number", spec.seed)
                .param("t", tenantId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("ch", channelOf(tenantId))
                .param("channelCode", spec.channelCode)
                .param("cust", customerOf(tenantId))
                .param("mode", spec.mode)
                .param("status", spec.status)
                .param("quote", quoteId)
                .param("hash", "hash-" + orderId)
                .param("pub", publicationOf(tenantId))
                .param("cart", cartId)
                .param("key", "idem-" + orderId)
                .param("promisedAt", spec.createdAt.plusSeconds(2100).atOffset(ZoneOffset.UTC))
                .param("createdAt", spec.createdAt.atOffset(ZoneOffset.UTC))
                .param(
                        "confirmedAt",
                        spec.confirmed ? spec.createdAt.plusSeconds(60).atOffset(ZoneOffset.UTC) : null)
                .param("createdBy", spec.createdByActorId == null ? "operator-1" : spec.createdByActorId)
                .update();

        return orderId;
    }

    private void insertProcess(UUID orderId, String processName, String status) {
        jdbc.sql("""
                INSERT INTO ordering.order_process_states (order_id, process_name, tenant_id,
                    status, next_attempt_at)
                VALUES (:orderId, :name, :t, :status, :retryAt)
                """)
                .param("orderId", orderId)
                .param("name", processName)
                .param("t", TENANT)
                .param("status", status)
                // ck_order_process_retry: a retryable failure with no scheduled
                // retry is a process that has silently stopped.
                .param(
                        "retryAt",
                        "FAILED_RETRYABLE".equals(status) ? NOON.plusSeconds(30).atOffset(ZoneOffset.UTC) : null)
                .update();
    }

    private void insertReference(UUID tenantId, UUID orderId, String value) {
        jdbc.sql("""
                INSERT INTO ordering.order_external_references (id, tenant_id, order_id,
                    reference_type, reference_value, reference_value_normalised, issued_by)
                VALUES (:id, :t, :orderId, 'PARTNER_ORDER_ID', :value, :normalised, 'PARTNER')
                """)
                .param("id", derived("reference:" + orderId + value))
                .param("t", tenantId)
                .param("orderId", orderId)
                .param("value", value)
                .param("normalised", JdbcOrderStore.normalisedExternalReference(value))
                .update();
    }

    private void insertPaymentIntent(UUID orderId, String tender, String methodCode) {
        jdbc.sql("""
                INSERT INTO payments.payment_intents (id, tenant_id, order_id, brand_id,
                    location_id, tender, payment_method_code, provider_type,
                    requested_amount_minor, currency, status, capture_timing, idempotency_key)
                VALUES (:id, :t, :orderId, :b, :loc, :tender, :code, :provider,
                    101000, 'UZS', 'PENDING', 'ON_HANDOVER', :key)
                """)
                .param("id", derived("intent:" + orderId))
                .param("t", TENANT)
                .param("orderId", orderId)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("tender", tender)
                .param("code", methodCode)
                .param("provider", "PROVIDER".equals(tender) ? "CLICK" : null)
                .param("key", "intent-" + orderId)
                .update();
    }

    private void assignCourier(UUID orderId, UUID courierId) {
        UUID planId = derived("plan:" + orderId);
        jdbc.sql("""
                INSERT INTO fulfillment.delivery_plans (id, tenant_id, brand_id, location_id,
                    order_id, status, currency, confirmed_at, preparation_seconds,
                    estimated_ready_at, pickup_window_start, pickup_window_end, source_at,
                    latest_assignment_at, branch_zone)
                VALUES (:id, :t, :b, :loc, :orderId, 'ASSIGNED', 'UZS', :confirmedAt, 2100,
                    :readyAt, :windowStart, :windowEnd, :sourceAt, :latest, 'Asia/Tashkent')
                """)
                .param("id", planId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("orderId", orderId)
                .param("confirmedAt", NOON.atOffset(ZoneOffset.UTC))
                .param("readyAt", NOON.plusSeconds(2100).atOffset(ZoneOffset.UTC))
                .param("windowStart", NOON.plusSeconds(2100).atOffset(ZoneOffset.UTC))
                .param("windowEnd", NOON.plusSeconds(2400).atOffset(ZoneOffset.UTC))
                .param("sourceAt", NOON.atOffset(ZoneOffset.UTC))
                .param("latest", NOON.plusSeconds(2400).atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                INSERT INTO fulfillment.shipments (id, tenant_id, brand_id, location_id,
                    order_id, delivery_plan_id, status, source_type, courier_id, assigned_at)
                VALUES (:id, :t, :b, :loc, :orderId, :plan, 'ASSIGNED', 'INTERNAL',
                    :courier, :assignedAt)
                """)
                .param("id", derived("shipment:" + orderId))
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("orderId", orderId)
                .param("plan", planId)
                .param("courier", courierId)
                .param("assignedAt", NOON.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void seedTenancy() {
        seedTenant(TENANT, "board-tenant", BRAND, LOCATION);
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'YUN', 'yunusobod', 'Yunusobod', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", OTHER_LOCATION)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();

        seedTenant(OTHER_TENANT, "board-other-tenant", OTHER_BRAND, OTHER_TENANT_LOCATION);
        seedCouriers();
    }

    private void seedTenant(UUID tenantId, String slug, UUID brandId, UUID locationId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("t", tenantId)
                .param("slug", slug + "-brand")
                .update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CHI', :slug, 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("slug", slug + "-location")
                .update();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                    identity_policy_version, version)
                VALUES (:id, :t, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", customerOf(tenantId)).param("t", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :t, 'TELEGRAM', 'TELEGRAM', 'Telegram bot', 'ACTIVE', false)
                """).param("id", channelOf(tenantId)).param("t", tenantId).update();

        UUID catalogId = derived("catalog:" + tenantId);
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
                VALUES (:id, :t, :b, :cat, 'TELEGRAM', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationOf(tenantId))
                .param("t", tenantId)
                .param("b", brandId)
                .param("cat", catalogId)
                .update();
    }

    private void seedCouriers() {
        jdbc.sql("""
                INSERT INTO fulfillment.courier_types (id, tenant_id, code, display_name, vehicle_class)
                VALUES (:id, :t, 'SCOOTER', 'Scooter', 'SCOOTER')
                """).param("id", COURIER_TYPE).param("t", TENANT).update();
        for (var courier :
                List.of(java.util.Map.entry(COURIER, "K-014"), java.util.Map.entry(OTHER_COURIER, "K-015"))) {
            jdbc.sql("""
                    INSERT INTO fulfillment.couriers (id, tenant_id, courier_type_id,
                        principal_subject, display_reference, protected_full_name)
                    VALUES (:id, :t, :type, :subject, :reference, 'ciphertext')
                    """)
                    .param("id", courier.getKey())
                    .param("t", TENANT)
                    .param("type", COURIER_TYPE)
                    .param("subject", "subject-" + courier.getValue())
                    .param("reference", courier.getValue())
                    .update();
        }
    }

    private static UUID customerOf(UUID tenantId) {
        return TENANT.equals(tenantId) ? CUSTOMER : derived("customer:" + tenantId);
    }

    private static UUID channelOf(UUID tenantId) {
        return derived("channel:" + tenantId);
    }

    private static UUID publicationOf(UUID tenantId) {
        return derived("publication:" + tenantId);
    }

    private static UUID derived(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
