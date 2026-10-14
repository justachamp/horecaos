package uz.horecaos.platform.ordering;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.horecaos.platform.ordering.OrderBoardFixtures.NOON;
import static uz.horecaos.platform.ordering.OrderBoardFixtures.order;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.fiscal.application.OrderFiscalStatusAdapter;
import uz.horecaos.platform.fiscal.infrastructure.persistence.JdbcFiscalLifecycleStore;
import uz.horecaos.platform.fulfillment.api.CourierEtaPort;
import uz.horecaos.platform.fulfillment.application.CourierEtaPortAdapter;
import uz.horecaos.platform.fulfillment.application.DeliveryDistancePortAdapter;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;
import uz.horecaos.platform.ordering.api.PaymentIntentPort;
import uz.horecaos.platform.ordering.application.OrderBoardEnrichment;
import uz.horecaos.platform.ordering.application.OrderBoardEnrichment.Fact;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderBoardFacts;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderBoardRow;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.SalesChannel;
import uz.horecaos.platform.tenancy.api.SalesChannelLookup;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * What the order board draws beyond the order row (gap map row {@code 1.1}, ADR 0160 «Board columns»): the
 * payment method behind the payment icon, the item summary, and the five optional columns' facts -- the
 * courier ETA, the source channel's presentation, the fiscal status, the distance and the external number.
 *
 * <p>Every fact is read through the module that owns it, against the real schema, in <em>one</em> batch per
 * page; two of the tests count the calls to say so. Each case holds rows that differ in exactly the attribute
 * under test and names both the order that must carry the fact and the ones that must not, because a read
 * that fills every row with the same value passes any assertion that names only the row it expects.
 */
class OrderBoardFactsTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-7000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f6f4e-7000-7000-8000-0000000000a2");
    private static final UUID BRANCH = UUID.fromString("018f6f4e-7000-7000-8000-0000000000a3");

    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-7000-7000-8000-0000000000b1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f6f4e-7000-7000-8000-0000000000b2");
    private static final UUID OTHER_BRANCH = UUID.fromString("018f6f4e-7000-7000-8000-0000000000b3");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcOrderStore store;
    private OrderBoardFixtures fixtures;
    private RecordingEtaPort etaPort;
    private RecordingChannelLookup channels;

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
        jdbc = JdbcClient.create(db.dataSource());
        fixtures = new OrderBoardFixtures(jdbc);
        fixtures.clean();
        store = new JdbcOrderStore(jdbc);
        fixtures.tenant(TENANT, "facts", BRAND, BRANCH);
        fixtures.tenant(OTHER_TENANT, "facts-other", OTHER_BRAND, OTHER_BRANCH);
        etaPort = new RecordingEtaPort(new CourierEtaPortAdapter(new JdbcDeliveryPlanStore(jdbc)));
        channels = new RecordingChannelLookup(new JdbcSalesChannelStore(jdbc));
    }

    // ------------------------------------------------------------------ items

    @Test
    @DisplayName("the items column counts the live positions and names the first, not what the order held at checkout")
    void theItemSummaryIsTheCurrentLines() {
        UUID amended = insert("I-1");
        fixtures.insertLine(TENANT, amended, 1, "Lagman", null, null, 2); // removed by an amendment
        fixtures.insertLine(TENANT, amended, 2, "Plov", null, null, null);
        fixtures.insertLine(TENANT, amended, 3, "Samsa", null, null, null);
        fixtures.insertLine(TENANT, amended, 4, "Tea", null, null, null);

        OrderBoardFacts facts = factsOf(amended, Set.of());

        assertThat(facts.itemCount())
                .as("the removed line is not a position any more")
                .isEqualTo(3);
        assertThat(facts.firstItemName())
                .as("and the first position is the first one that is still there")
                .isEqualTo("Plov");
    }

    @Test
    @DisplayName("a combo is one position however many component lines it was written as, named by the combo")
    void aComboIsOnePosition() {
        UUID combo = insert("I-2");
        UUID selection = UUID.fromString("018f6f4e-7000-7000-8000-0000000000c1");
        fixtures.insertLine(TENANT, combo, 1, "Burger", selection, "Lunch set", null);
        fixtures.insertLine(TENANT, combo, 2, "Fries", selection, "Lunch set", null);
        fixtures.insertLine(TENANT, combo, 3, "Cola", selection, "Lunch set", null);
        fixtures.insertLine(TENANT, combo, 4, "Brownie", null, null, null);

        OrderBoardFacts facts = factsOf(combo, Set.of());

        assertThat(facts.itemCount())
                .as("three components and one dessert: two positions")
                .isEqualTo(2);
        assertThat(facts.firstItemName()).isEqualTo("Lunch set");
    }

    @Test
    @DisplayName("each order of a page gets its own summary, and an order with no lines gets none")
    void eachOrderGetsItsOwnItemSummary() {
        UUID two = insert("I-3");
        UUID one = insert("I-4");
        UUID none = insert("I-5");
        fixtures.insertLine(TENANT, two, 1, "Shashlik", null, null, null);
        fixtures.insertLine(TENANT, two, 2, "Non", null, null, null);
        fixtures.insertLine(TENANT, one, 1, "Manti", null, null, null);

        List<OrderBoardRow> rows = enrich(Set.of(), two, one, none);

        assertThat(rowOf(rows, two).facts().itemCount()).isEqualTo(2);
        assertThat(rowOf(rows, two).facts().firstItemName()).isEqualTo("Shashlik");
        assertThat(rowOf(rows, one).facts().itemCount()).isEqualTo(1);
        assertThat(rowOf(rows, one).facts().firstItemName()).isEqualTo("Manti");
        assertThat(rowOf(rows, none).facts().itemCount())
                .as("no lines is unknown, not zero positions")
                .isNull();
    }

    @Test
    @DisplayName("another tenant's lines never reach this tenant's board")
    void anotherTenantsLinesStayThere() {
        UUID mine = insert("I-6");
        UUID theirs = fixtures.insertOrder(order("I-6").at(OTHER_TENANT, OTHER_BRAND, OTHER_BRANCH));
        fixtures.insertCheckoutRevision(OTHER_TENANT, "I-6", theirs);
        fixtures.insertLine(OTHER_TENANT, theirs, 1, "Their secret dish", null, null, null);

        assertThat(factsOf(mine, Set.of()).firstItemName()).isNull();
        assertThat(store.itemSummariesByOrders(TENANT, List.of(theirs)))
                .as("asked by the wrong tenant, the other tenant's order answers as if it had no lines")
                .isEmpty();
    }

    // --------------------------------------------------------------- payments

    @Test
    @DisplayName(
            "the payment method is the live intent's, then the newest closed one's, and none for an order with no intent")
    void thePaymentMethodOfTheOrdersIntent() {
        UUID cash = insert("P-1");
        UUID replaced = insert("P-2");
        UUID noIntent = insert("P-3");
        fixtures.insertIntentOf(TENANT, BRAND, BRANCH, cash, "a", "PENDING", "CASH", NOON);
        // An expired CLICK attempt, then the PAYME intent that replaced it: the order is paid by PAYME now.
        fixtures.insertIntentOf(TENANT, BRAND, BRANCH, replaced, "a", "EXPIRED", "CLICK", NOON);
        fixtures.insertIntentOf(TENANT, BRAND, BRANCH, replaced, "b", "AUTHORIZING", "PAYME", NOON.minusSeconds(600));

        List<OrderBoardRow> rows = enrich(Set.of(), cash, replaced, noIntent);

        assertThat(rowOf(rows, cash).facts().paymentMethodCode()).isEqualTo("CASH");
        assertThat(rowOf(rows, replaced).facts().paymentMethodCode())
                .as("the live intent wins over a newer closed one")
                .isEqualTo("PAYME");
        assertThat(rowOf(rows, noIntent).facts().paymentMethodCode()).isNull();
    }

    @Test
    @DisplayName("among closed intents the newest names the method")
    void amongClosedIntentsTheNewestWins() {
        UUID retried = insert("P-4");
        fixtures.insertIntentOf(TENANT, BRAND, BRANCH, retried, "a", "FAILED", "CLICK", NOON.minusSeconds(900));
        fixtures.insertIntentOf(TENANT, BRAND, BRANCH, retried, "b", "CANCELLED", "PAYME", NOON.minusSeconds(60));

        assertThat(factsOf(retried, Set.of()).paymentMethodCode()).isEqualTo("PAYME");
    }

    // -------------------------------------------------------------- courier ETA

    @Test
    @DisplayName(
            "the courier ETA is one batched call per page, asked of the delivery orders only, and only when asked for")
    void theCourierEtaIsOneBatchedCallForTheDeliveryOrders() {
        Instant quoted = NOON.plus(40, ChronoUnit.MINUTES);
        UUID partner = insert("E-1", "DELIVERY");
        UUID house = insert("E-2", "DELIVERY");
        UUID pickup = insert("E-3", "PICKUP");
        fixtures.insertDeliveryPlan(TENANT, BRAND, BRANCH, partner, "ASSIGNED", quoted, null);
        fixtures.insertDeliveryPlan(TENANT, BRAND, BRANCH, house, "ASSIGNED", null, null);

        List<OrderBoardRow> rows = enrich(Set.of(Fact.COURIER_ETA), partner, house, pickup);

        assertThat(etaPort.calls)
                .as("one round trip for the whole page, not one per row")
                .hasSize(1);
        assertThat(etaPort.calls.getFirst())
                .as("a pickup order has no delivery plan to ask about")
                .containsExactlyInAnyOrder(partner, house);
        assertThat(rowOf(rows, partner).facts().courierEtaAt()).isEqualTo(quoted);
        assertThat(rowOf(rows, house).facts().courierEtaAt())
                .as("a house courier's plan carries no quoted ETA, and none is made up")
                .isNull();
        assertThat(rowOf(rows, pickup).facts().courierEtaAt()).isNull();

        etaPort.calls.clear();
        List<OrderBoardRow> unasked = enrich(Set.of(), partner, house, pickup);
        assertThat(etaPort.calls)
                .as("nobody turned the column on, so nobody pays for it")
                .isEmpty();
        assertThat(rowOf(unasked, partner).facts().courierEtaAt()).isNull();
    }

    @Test
    @DisplayName("a page with no delivery order makes no ETA call at all")
    void aPageWithoutDeliveriesAsksNothing() {
        UUID pickup = insert("E-4", "PICKUP");
        UUID dineIn = insert("E-5", "DINE_IN");

        enrich(Set.of(Fact.COURIER_ETA, Fact.DISTANCE), pickup, dineIn);

        assertThat(etaPort.calls).isEmpty();
    }

    @Test
    @DisplayName("a cancelled plan's ETA is not an ETA")
    void aCancelledPlanHasNoEta() {
        UUID cancelled = insert("E-6", "DELIVERY");
        fixtures.insertDeliveryPlan(TENANT, BRAND, BRANCH, cancelled, "CANCELLED", NOON.plusSeconds(1800), 4200);

        OrderBoardFacts facts = factsOf(cancelled, Set.of(Fact.COURIER_ETA, Fact.DISTANCE));

        assertThat(facts.courierEtaAt()).isNull();
        assertThat(facts.distanceMeters()).isNull();
    }

    // ------------------------------------------------------------------ source

    @Test
    @DisplayName("the source is resolved on the server from the page's distinct channel codes, in one lookup")
    void theSourceIsResolvedOnTheServerInOneLookup() {
        fixtures.insertChannel(TENANT, "YANDEX", "AGGREGATOR", "Yandex Eda", "yandex");
        // The other tenant's channel of the same code must never be the one drawn for this tenant's order.
        fixtures.insertChannel(OTHER_TENANT, "YANDEX", "WEB", "Their site", "their-icon");
        UUID telegram1 = insert("S-1");
        UUID telegram2 = insert("S-2");
        UUID yandex =
                fixtures.insertOrder(order("S-3").at(TENANT, BRAND, BRANCH).channelCode("YANDEX"));
        UUID gone = fixtures.insertOrder(order("S-4").at(TENANT, BRAND, BRANCH).channelCode("RETIRED"));

        List<OrderBoardRow> rows = enrich(Set.of(Fact.SOURCE), telegram1, telegram2, yandex, gone);

        assertThat(channels.byCodesCalls)
                .as("one lookup for the page, over its three distinct codes")
                .hasSize(1);
        assertThat(channels.byCodesCalls.getFirst()).containsExactlyInAnyOrder("TELEGRAM", "YANDEX", "RETIRED");
        assertThat(channels.byCodeCalls).as("never one lookup per row").isZero();

        OrderBoardFacts tg = rowOf(rows, telegram1).facts();
        assertThat(tg.channelSystemType()).isEqualTo("TELEGRAM");
        assertThat(tg.channelDisplayName()).isEqualTo("Telegram bot");
        assertThat(tg.channelIcon()).as("a channel with no icon of its own").isNull();
        OrderBoardFacts ya = rowOf(rows, yandex).facts();
        assertThat(ya.channelSystemType()).isEqualTo("AGGREGATOR");
        assertThat(ya.channelIcon()).isEqualTo("yandex");
        assertThat(ya.channelDisplayName()).isEqualTo("Yandex Eda");
        assertThat(rowOf(rows, gone).facts().channelSystemType())
                .as("a code this tenant has no channel for draws no source rather than a guess")
                .isNull();
        assertThat(rowOf(rows, telegram2).facts().channelSystemType()).isEqualTo("TELEGRAM");
    }

    @Test
    @DisplayName("without the source column on, the channel registry is not read")
    void theRegistryIsNotReadUnasked() {
        UUID order = insert("S-5");

        OrderBoardFacts facts = factsOf(order, Set.of());

        assertThat(channels.byCodesCalls).isEmpty();
        assertThat(facts.channelSystemType()).isNull();
    }

    // ------------------------------------------------------------------ fiscal

    @Test
    @DisplayName("the fiscal chip is the sale document's status, and an order with no document has none")
    void theFiscalStatusIsTheSaleDocuments() {
        UUID issued = insert("F-1");
        UUID failed = insert("F-2");
        UUID cash = insert("F-3");
        UUID none = insert("F-4");
        fixtures.insertFiscalDocument(TENANT, issued, "ISSUED");
        fixtures.insertFiscalDocument(TENANT, failed, "FAILED");
        fixtures.insertFiscalDocument(TENANT, cash, "NOT_APPLICABLE");

        List<OrderBoardRow> rows = enrich(Set.of(Fact.FISCAL), issued, failed, cash, none);

        assertThat(rowOf(rows, issued).facts().fiscalStatus()).isEqualTo("ISSUED");
        assertThat(rowOf(rows, failed).facts().fiscalStatus()).isEqualTo("FAILED");
        assertThat(rowOf(rows, cash).facts().fiscalStatus())
                .as("a recorded decision that no receipt is owed is not the same as no document")
                .isEqualTo("NOT_APPLICABLE");
        assertThat(rowOf(rows, none).facts().fiscalStatus()).isNull();
        assertThat(rowOf(enrich(Set.of(), issued), issued).facts().fiscalStatus())
                .as("unasked")
                .isNull();
    }

    @Test
    @DisplayName("a refund document does not replace the sale document's status")
    void aRefundDoesNotReplaceTheSale() {
        UUID refunded = insert("F-5");
        fixtures.insertFiscalDocument(TENANT, refunded, "ISSUED");
        jdbc.sql("""
                INSERT INTO fiscal.fiscal_documents (id, tenant_id, order_id, provider_type, document_type,
                    corrects_document_id, status, reason_code, reason_note, created_at)
                VALUES (:id, :t, :orderId, 'CLICK', 'REFUND', :sale, 'PENDING', 'PARTNER_FISCALIZED', 'board test',
                    now() + interval '1 hour')
                """)
                .param("id", OrderBoardFixtures.derived("refund:" + refunded))
                .param("sale", OrderBoardFixtures.derived("fiscal:" + refunded + "ISSUED"))
                .param("t", TENANT)
                .param("orderId", refunded)
                .update();

        assertThat(factsOf(refunded, Set.of(Fact.FISCAL)).fiscalStatus())
                .as("the chip answers «did the sale get its receipt», and the newer refund is PENDING")
                .isEqualTo("ISSUED");
    }

    // ---------------------------------------------------------------- distance

    @Test
    @DisplayName("the distance is the live plan's route length, absent when the tariff priced without measuring")
    void theDistanceOfTheLivePlan() {
        UUID measured = insert("D-1", "DELIVERY");
        UUID unmeasured = insert("D-2", "DELIVERY");
        fixtures.insertDeliveryPlan(TENANT, BRAND, BRANCH, measured, "ASSIGNED", null, 3800);
        fixtures.insertDeliveryPlan(TENANT, BRAND, BRANCH, unmeasured, "ASSIGNED", null, null);

        List<OrderBoardRow> rows = enrich(Set.of(Fact.DISTANCE), measured, unmeasured);

        assertThat(rowOf(rows, measured).facts().distanceMeters()).isEqualTo(3800);
        assertThat(rowOf(rows, unmeasured).facts().distanceMeters())
                .as("not zero: zero metres is a measurement")
                .isNull();
    }

    // ---------------------------------------------------------- external number

    @Test
    @DisplayName("the external number is the one a person would read aloud, and never a courier's delivery claim")
    void theExternalNumber() {
        UUID several = insert("X-1");
        UUID onlyClaim = insert("X-2");
        UUID none = insert("X-3");
        fixtures.insertExternalReference(
                TENANT, several, "PARTNER_ORDER_ID", "9f2c-77a1-long-partner-uuid", NOON.minusSeconds(300));
        fixtures.insertExternalReference(TENANT, several, "PARTNER_DISPLAY_CODE", "YE-2291", NOON);
        fixtures.insertExternalReference(TENANT, several, "DELIVERY_CLAIM_ID", "CLAIM-1", NOON.minusSeconds(900));
        fixtures.insertExternalReference(TENANT, onlyClaim, "DELIVERY_CLAIM_ID", "CLAIM-2", NOON);

        List<OrderBoardRow> rows = enrich(Set.of(Fact.EXTERNAL_NUMBER), several, onlyClaim, none);

        assertThat(rowOf(rows, several).facts().externalNumber())
                .as("the short display code beats the partner's own id even though it arrived later")
                .isEqualTo("YE-2291");
        assertThat(rowOf(rows, onlyClaim).facts().externalNumber())
                .as("a delivery claim identifies the courier's job, not the order")
                .isNull();
        assertThat(rowOf(rows, none).facts().externalNumber()).isNull();
    }

    @Test
    @DisplayName("an external reference of another tenant never labels this tenant's order")
    void anotherTenantsReferenceStaysThere() {
        UUID mine = insert("X-4");
        UUID theirs = fixtures.insertOrder(order("X-4").at(OTHER_TENANT, OTHER_BRAND, OTHER_BRANCH));
        fixtures.insertExternalReference(OTHER_TENANT, theirs, "PARTNER_DISPLAY_CODE", "THEIRS-1", NOON);

        assertThat(store.externalNumbersByOrders(TENANT, List.of(mine, theirs))).isEmpty();
    }

    // ------------------------------------------------------------------- shape

    @Test
    @DisplayName("an empty page reads nothing, and the facts of one row never leak onto its neighbour")
    void anEmptyPageAndTwoNeighbours() {
        assertThat(enrichment().enrich(TENANT, List.of(), Set.of(Fact.values())))
                .isEmpty();
        assertThat(etaPort.calls).isEmpty();
        assertThat(channels.byCodesCalls).isEmpty();

        UUID loaded = insert("N-1", "DELIVERY");
        UUID bare = insert("N-2", "DELIVERY");
        fixtures.insertLine(TENANT, loaded, 1, "Lagman", null, null, null);
        fixtures.insertIntentOf(TENANT, BRAND, BRANCH, loaded, "a", "PENDING", "CASH", NOON);
        fixtures.insertFiscalDocument(TENANT, loaded, "ISSUED");
        fixtures.insertDeliveryPlan(TENANT, BRAND, BRANCH, loaded, "ASSIGNED", NOON.plusSeconds(1800), 1200);
        fixtures.insertExternalReference(TENANT, loaded, "POS_ORDER_ID", "POS-9", NOON);

        List<OrderBoardRow> rows = enrich(Set.of(Fact.values()), loaded, bare);

        OrderBoardFacts none = rowOf(rows, bare).facts();
        assertThat(none.itemCount()).isNull();
        assertThat(none.paymentMethodCode()).isNull();
        assertThat(none.fiscalStatus()).isNull();
        assertThat(none.distanceMeters()).isNull();
        assertThat(none.courierEtaAt()).isNull();
        assertThat(none.externalNumber()).isNull();
        OrderBoardFacts all = rowOf(rows, loaded).facts();
        assertThat(all.itemCount()).isEqualTo(1);
        assertThat(all.paymentMethodCode()).isEqualTo("CASH");
        assertThat(all.fiscalStatus()).isEqualTo("ISSUED");
        assertThat(all.distanceMeters()).isEqualTo(1200);
        assertThat(all.courierEtaAt()).isEqualTo(NOON.plusSeconds(1800));
        assertThat(all.externalNumber()).isEqualTo("POS-9");
        assertThat(all.channelSystemType()).isEqualTo("TELEGRAM");
    }

    @Test
    @DisplayName("the board's origin rides on the row from the board statement itself")
    void theOriginComesWithTheRow() {
        UUID own = insert("O-1");
        UUID marketplaceBinding = UUID.fromString("018f6f4e-7000-7000-8000-0000000000d1");
        fixtures.marketplaceBinding(TENANT, marketplaceBinding, BRAND, BRANCH, "YANDEX_EDA", "Yandex Eda");
        UUID partner =
                fixtures.insertOrder(order("O-2").at(TENANT, BRAND, BRANCH).marketplace(marketplaceBinding));

        List<OrderBoardRow> rows = store.listForLocation(listQuery(), null, null, 50);

        assertThat(rowOf(rows, own).origin()).isEqualTo("HORECAOS");
        assertThat(rowOf(rows, partner).origin()).isEqualTo("MARKETPLACE");
    }

    // ----------------------------------------------------------------- helpers

    private UUID insert(String seed) {
        return insert(seed, "DELIVERY");
    }

    private UUID insert(String seed, String mode) {
        UUID orderId =
                fixtures.insertOrder(order(seed).at(TENANT, BRAND, BRANCH).mode(mode));
        fixtures.insertCheckoutRevision(TENANT, seed, orderId);
        return orderId;
    }

    private JdbcOrderStore.OrderListQuery listQuery() {
        return new JdbcOrderStore.OrderListQuery(
                TENANT, BRAND, BRANCH, List.of(), null, null, null, null, null, null, null, null, null, null);
    }

    /** The page the board would read, with the facts enriched, in the order the ids were given. */
    private List<OrderBoardRow> enrich(Set<Fact> include, UUID... orderIds) {
        List<OrderBoardRow> rows = store.listForLocation(listQuery(), null, null, 50).stream()
                .filter(row -> List.of(orderIds).contains(row.order().orderId()))
                .toList();
        assertThat(rows).as("every order asked about is on the board").hasSize(orderIds.length);
        return enrichment().enrich(TENANT, rows, include);
    }

    private OrderBoardFacts factsOf(UUID orderId, Set<Fact> include) {
        return rowOf(enrich(include, orderId), orderId).facts();
    }

    private static OrderBoardRow rowOf(List<OrderBoardRow> rows, UUID orderId) {
        return rows.stream()
                .filter(row -> row.order().orderId().equals(orderId))
                .findFirst()
                .orElseThrow();
    }

    private OrderBoardEnrichment enrichment() {
        JdbcPaymentIntentStore intents = new JdbcPaymentIntentStore(jdbc);
        PaymentIntentPort payments = new PaymentIntentPort() {
            @Override
            public boolean paymentRequiredBeforeConfirmation(UUID tenantId, UUID orderId, String paymentMethodCode) {
                return false;
            }

            @Override
            public @Nullable UUID createIntent(
                    UUID tenantId,
                    UUID orderId,
                    long amountMinor,
                    String currency,
                    String paymentMethodCode,
                    String idempotencyKey) {
                return null;
            }

            @Override
            public Map<UUID, String> paymentMethodsByOrders(UUID tenantId, Collection<UUID> orderIds) {
                return intents.paymentMethodsByOrders(tenantId, orderIds);
            }
        };
        return new OrderBoardEnrichment(
                store,
                payments,
                etaPort,
                channels,
                new OrderFiscalStatusAdapter(new JdbcFiscalLifecycleStore(jdbc)),
                new DeliveryDistancePortAdapter(new JdbcDeliveryPlanStore(jdbc)));
    }

    /** The real ETA read, remembering every call it was asked. */
    private static final class RecordingEtaPort implements CourierEtaPort {
        final List<Set<UUID>> calls = new ArrayList<>();
        private final CourierEtaPort delegate;

        RecordingEtaPort(CourierEtaPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public Map<UUID, Instant> etaByOrders(UUID tenantId, Collection<UUID> orderIds) {
            calls.add(Set.copyOf(orderIds));
            return delegate.etaByOrders(tenantId, orderIds);
        }
    }

    /** The real channel registry, counting how it is asked. */
    private static final class RecordingChannelLookup implements SalesChannelLookup {
        final List<Set<String>> byCodesCalls = new ArrayList<>();
        int byCodeCalls;
        private final SalesChannelLookup delegate;

        RecordingChannelLookup(SalesChannelLookup delegate) {
            this.delegate = delegate;
        }

        @Override
        public Optional<SalesChannel> byId(UUID tenantId, UUID channelId) {
            return delegate.byId(tenantId, channelId);
        }

        @Override
        public Optional<SalesChannel> byCode(UUID tenantId, String code) {
            byCodeCalls++;
            return delegate.byCode(tenantId, code);
        }

        @Override
        public Map<String, SalesChannel> byCodes(UUID tenantId, Collection<String> codes) {
            byCodesCalls.add(Set.copyOf(codes));
            return delegate.byCodes(tenantId, codes);
        }

        @Override
        public Set<String> enabledPaymentMethodCodes(UUID tenantId, UUID channelId) {
            return delegate.enabledPaymentMethodCodes(tenantId, channelId);
        }
    }
}
