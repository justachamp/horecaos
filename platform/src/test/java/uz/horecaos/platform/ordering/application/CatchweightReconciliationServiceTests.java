package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.ordering.api.OrderSettlementPort;
import uz.horecaos.platform.ordering.domain.OrderPromise;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.domain.PromiseBasis;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderLineRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderModifierRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.RevisionRow;
import uz.horecaos.platform.pricing.api.CartPricingPort;
import uz.horecaos.platform.pricing.api.CartPricingPort.PricingCommand;
import uz.horecaos.platform.pricing.api.PromoCodeRedemptionPort;
import uz.horecaos.platform.pricing.api.PromotionRedemptionPort;
import uz.horecaos.platform.pricing.api.QuoteAcceptance;
import uz.horecaos.platform.pricing.api.QuoteAcceptancePort;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * What a weight capture asks pricing (ADR 0137), on literals.
 *
 * <p>The capture re-prices the whole live basket, so the question this class answers is the
 * one an amendment's tests answer for an amendment: is the order priced again as the thing
 * it was bought as, under the context it was bought in? Each test would pass if the
 * re-price were a free-standing pickup quote of the lines as plain rows, and none of them
 * does: they read the command pricing is handed, and the figures written back for a combo.
 */
class CatchweightReconciliationServiceTests {

    private static final UUID TENANT = UUID.fromString("018fc200-0000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fc200-0000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018fc200-0000-7000-8000-0000000000c1");
    private static final UUID CUSTOMER = UUID.fromString("018fc200-0000-7000-8000-0000000000d1");
    private static final UUID ORDER = UUID.fromString("018fc200-0000-7000-8000-0000000000e1");
    private static final UUID CHECKOUT_QUOTE = UUID.fromString("018fc200-0000-7000-8000-0000000000f1");
    private static final UUID REPRICE_QUOTE = UUID.fromString("018fc200-0000-7000-8000-0000000000f2");

    private static final UUID CAKE_VARIANT = UUID.randomUUID();
    private static final UUID SODA_VARIANT = UUID.randomUUID();
    private static final UUID LUNCH = UUID.randomUUID();
    private static final UUID BURGER = UUID.randomUUID();
    private static final UUID COLA = UUID.randomUUID();
    private static final UUID BURGER_IN_LUNCH = UUID.randomUUID();
    private static final UUID COLA_IN_LUNCH = UUID.randomUUID();
    private static final UUID SELECTION = UUID.randomUUID();

    private static final UUID TOPPING_OPTION = UUID.randomUUID();
    private static final UUID BOX_OPTION = UUID.randomUUID();
    private static final UUID NESTED_OPTION = UUID.randomUUID();
    private static final UUID TOPPING_MODIFIER = UUID.randomUUID();

    private static final Instant PLACED_AT = Instant.parse("2026-10-01T07:30:00Z");
    private static final Instant WEIGHED_AT = Instant.parse("2026-10-01T10:20:00Z");

    private final JdbcOrderStore orders = mock(JdbcOrderStore.class);
    private final CartPricingPort pricing = mock(CartPricingPort.class);
    private final QuoteAcceptancePort quoteAcceptance = mock(QuoteAcceptancePort.class);
    private final PromoCodeRedemptionPort promoCodes = mock(PromoCodeRedemptionPort.class);
    private final PromotionRedemptionPort promotions = mock(PromotionRedemptionPort.class);
    private final OrderSettlementPort settlement = mock(OrderSettlementPort.class);
    private final OrderDeliveryPoint deliveryPoint = mock(OrderDeliveryPoint.class);
    private final AuditRecorder audit = mock(AuditRecorder.class);
    private final Clock clock = Clock.fixed(WEIGHED_AT, ZoneOffset.UTC);

    private final AtomicReference<@Nullable PricingCommand> priced = new AtomicReference<>();

    private OrderLineRow cake;
    private OrderLineRow soda;
    private OrderLineRow burger;
    private OrderLineRow cola;

    @BeforeEach
    void basket() {
        cake = catchweightLine(1, CAKE_VARIANT, 180_000L);
        soda = plainLine(2, SODA_VARIANT, 7_000L);
        burger = componentLine(3, BURGER, BURGER_IN_LUNCH, 25_000L);
        cola = componentLine(4, COLA, COLA_IN_LUNCH, 3_000L);
        when(quoteAcceptance.acceptQuote(any(), any(), any()))
                .thenReturn(new QuoteAcceptance(QuoteAcceptance.Outcome.ACCEPTED, 0L, "UZS"));
        when(settlement.restateTotal(any(), any(), anyLong(), any())).thenReturn(true);
        when(orders.applyRevision(any(), any(), anyInt(), anyInt(), any(), any(), any()))
                .thenReturn(Optional.of(3));
        when(orders.applyReconciledAmounts(any(), any(), any(), any(), anyLong(), anyLong(), anyLong()))
                .thenReturn(true);
        when(orders.revisions(TENANT, ORDER)).thenReturn(List.of(revision(1, CHECKOUT_QUOTE)));
        when(pricing.priceCart(any())).thenAnswer(invocation -> {
            priced.set(invocation.getArgument(0));
            return repriced();
        });
    }

    @Test
    @DisplayName("the re-price runs under the order's own mode and promotion frame, not as a pickup quote of now")
    void theRepriceCarriesTheOrdersModeAndFrame() {
        orderIs(FulfillmentMode.DINE_IN, cake, soda);

        weigh(1_340);

        PricingCommand command = commandSeenByPricing();
        assertThat(command.fulfillmentMode())
                .as("which hidden groups apply, and which FULFILLMENT_MODE promotions, is the order's mode")
                .isEqualTo(FulfillmentMode.DINE_IN);
        assertThat(command.frame())
                .as("the promotion frame an amendment would build")
                .isNotNull();
        PricingCommand.PromotionFrame frame = Objects.requireNonNull(command.frame());
        assertThat(frame.fulfillmentMode()).isEqualTo("DINE_IN");
        assertThat(frame.inheritFromQuoteId())
                .as("the promotion inputs recorded on the quote behind the current revision")
                .isEqualTo(CHECKOUT_QUOTE);
        assertThat(frame.placedAt())
                .as("a lunch promotion that priced the order at 12:30 still holds when it is weighed at 15:20")
                .isEqualTo(PLACED_AT);
        assertThat(frame.serviceInstant()).as("the clock is never an override").isNull();
        assertThat(frame.paymentMethodCode())
                .as("the method is the one recorded on the order's quote; a weight does not change it")
                .isNull();
        assertThat(command.carriedRedemptionOrderId()).isEqualTo(ORDER);
    }

    @Test
    @DisplayName("what the server applied and the nested choices go back as what they are, never as customer choices")
    void hiddenAndNestedSelectionsAreNotFedBackAsChoices() {
        orderIs(FulfillmentMode.PICKUP, cake, soda);
        when(orders.lineModifiers(TENANT, ORDER))
                .thenReturn(List.of(
                        modifier(soda, TOPPING_MODIFIER, TOPPING_OPTION, null, false),
                        modifier(soda, UUID.randomUUID(), BOX_OPTION, null, true),
                        modifier(soda, UUID.randomUUID(), NESTED_OPTION, TOPPING_MODIFIER, false)));

        weigh(1_340);

        PricingCommand.Item sodaItem = itemFor(soda.lineId().toString());
        assertThat(sodaItem.modifierOptionIds())
                .as("the hidden box is applied again by pricing; passing it back would charge it twice")
                .containsExactly(TOPPING_OPTION);
        assertThat(sodaItem.nestedModifiers())
                .as("a second-level choice names the first-level option that offered it")
                .containsExactly(new PricingCommand.NestedModifier(TOPPING_OPTION, NESTED_OPTION));
    }

    @Test
    @DisplayName("a combo in the order is priced as the combo and its picks, and its components get their figures back")
    void aComboIsPricedAsAComboAndItsComponentsAreRestated() {
        orderIs(FulfillmentMode.DELIVERY, cake, burger, cola);

        weigh(1_340);

        PricingCommand command = commandSeenByPricing();
        assertThat(command.items()).hasSize(2);
        PricingCommand.Item comboItem = itemFor(SELECTION.toString());
        assertThat(comboItem.variantId())
                .as("the container is what pricing knows; the components are its picks")
                .isEqualTo(LUNCH);
        assertThat(comboItem.comboPicks())
                .extracting(PricingCommand.ComboPick::componentId)
                .containsExactly(BURGER_IN_LUNCH, COLA_IN_LUNCH);
        PricingCommand.Item cakeItem = itemFor(cake.lineId().toString());
        assertThat(cakeItem.actualWeightGrams()).isEqualTo(1_340);

        verify(orders)
                .applyReconciledAmounts(
                        TENANT, ORDER, burger.lineId(), null, 25_000L, 25_000L, burger.taxAmountMinor());
        verify(orders)
                .applyReconciledAmounts(TENANT, ORDER, cola.lineId(), null, 3_000L, 3_000L, cola.taxAmountMinor());
        verify(orders)
                .applyReconciledAmounts(TENANT, ORDER, cake.lineId(), 1_340, 201_000L, 201_000L, 201_000L * 12 / 112);
    }

    @Test
    @DisplayName("a delivery order is priced with its destination, and keeps the delivery charge it was agreed at")
    void aDeliveryOrderKeepsItsAgreedFeeAndIsPricedWithItsDestination() {
        orderIs(FulfillmentMode.DELIVERY, cake);
        when(orders.find(TENANT, ORDER)).thenReturn(Optional.of(order(FulfillmentMode.DELIVERY, 12_000L)));
        when(deliveryPoint.of(any(), any())).thenReturn(new GeoPoint(41.311081, 69.240562));
        when(pricing.priceCart(any())).thenAnswer(invocation -> {
            priced.set(invocation.getArgument(0));
            // Today's tariff would charge 15,000, and the goods come to 201,000 once weighed.
            return quoteWithFee(15_000L, quotedCake(cake, 201_000L, 1_340));
        });

        weigh(1_340);

        PricingCommand.Delivery delivery =
                Objects.requireNonNull(commandSeenByPricing().delivery(), "a delivery order is priced with its point");
        assertThat(delivery.destination()).isEqualTo(new GeoPoint(41.311081, 69.240562));
        ArgumentCaptor<JdbcOrderStore.NewRevision> revision = ArgumentCaptor.forClass(JdbcOrderStore.NewRevision.class);
        verify(orders).insertRevision(revision.capture());
        assertThat(revision.getValue().feeMinor())
                .as("the fee the customer agreed to, not the one the tariff would charge now")
                .isEqualTo(12_000L);
        assertThat(revision.getValue().totalMinor())
                .as("the weighed goods and the agreed fee")
                .isEqualTo(213_000L);
    }

    @Test
    @DisplayName("the promotion ledger and the loyalty flags are restated against the quote the revision carries")
    void theLedgersAreRestatedAgainstTheNewQuote() {
        orderIs(FulfillmentMode.PICKUP, cake, soda);
        when(pricing.priceCart(any())).thenAnswer(invocation -> {
            priced.set(invocation.getArgument(0));
            return withLoyalty(repriced(), false, true);
        });

        weigh(1_340);

        verify(promotions).restateForOrder(TENANT, BRAND, ORDER, REPRICE_QUOTE, 2, CUSTOMER, WEIGHED_AT);
        verify(orders).setLoyaltyFlags(TENANT, ORDER, false, true);
        verify(promoCodes).restateForOrder(TENANT, ORDER, REPRICE_QUOTE);
    }

    @Test
    @DisplayName("a weight that is not the target line's is carried unchanged for the lines already weighed")
    void aLineAlreadyWeighedKeepsItsWeight() {
        OrderLineRow weighedCake = catchweightLine(1, CAKE_VARIANT, 150_000L, 1_000);
        OrderLineRow secondCake = catchweightLine(2, CAKE_VARIANT, 180_000L);
        orderIs(FulfillmentMode.PICKUP, weighedCake, secondCake);
        when(pricing.priceCart(any())).thenAnswer(invocation -> {
            priced.set(invocation.getArgument(0));
            return new QuoteSnapshot(
                    REPRICE_QUOTE,
                    TENANT,
                    BRAND,
                    LOCATION,
                    CUSTOMER,
                    "UZS",
                    QuoteSnapshot.Status.ACTIVE,
                    UUID.randomUUID(),
                    "hash",
                    360_000L,
                    38_572L,
                    0L,
                    0L,
                    375_000L,
                    WEIGHED_AT.plusSeconds(600),
                    List.of(quotedCake(weighedCake, 150_000L, 1_000), quotedCake(secondCake, 225_000L, 1_500)),
                    List.of(),
                    null,
                    null,
                    null,
                    null);
        });

        weighLine(secondCake, 1_500);

        assertThat(itemFor(weighedCake.lineId().toString()).actualWeightGrams()).isEqualTo(1_000);
        assertThat(itemFor(secondCake.lineId().toString()).actualWeightGrams()).isEqualTo(1_500);
        verify(orders)
                .applyReconciledAmounts(
                        TENANT, ORDER, secondCake.lineId(), 1_500, 225_000L, 225_000L, 225_000L * 12 / 112);
    }

    // ---------------------------------------------------------------- driving

    private void weigh(int grams) {
        weighLine(cake, grams);
    }

    private void weighLine(OrderLineRow line, int grams) {
        service().reconcile(TENANT, ORDER, line.lineId(), grams, 7, "USER", "scale", null);
    }

    private CatchweightReconciliationService service() {
        return new CatchweightReconciliationService(
                orders, pricing, quoteAcceptance, promoCodes, promotions, settlement, deliveryPoint, audit, clock);
    }

    private void orderIs(FulfillmentMode mode, OrderLineRow... lines) {
        when(orders.find(TENANT, ORDER)).thenReturn(Optional.of(order(mode)));
        when(orders.lines(TENANT, ORDER)).thenReturn(List.of(lines));
        when(orders.lineModifiers(TENANT, ORDER)).thenReturn(List.of());
    }

    private PricingCommand commandSeenByPricing() {
        return Objects.requireNonNull(priced.get(), "pricing was asked");
    }

    private PricingCommand.Item itemFor(String lineKey) {
        return commandSeenByPricing().items().stream()
                .filter(item -> item.lineKey().equals(lineKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no item priced under " + lineKey));
    }

    /** What pricing answers: the cake at its weighed amount, everything else exactly as it was bought. */
    private QuoteSnapshot repriced() {
        List<QuoteSnapshot.Line> lines = new java.util.ArrayList<>();
        for (OrderLineRow line : currentLines()) {
            if (line.catchweight()) {
                lines.add(quotedCake(line, 201_000L, 1_340));
            } else if (line.isComboComponent()) {
                lines.add(quotedComponent(line));
            } else {
                lines.add(new QuoteSnapshot.Line(
                        line.lineId().toString(),
                        line.sourceVariantId(),
                        line.quantity(),
                        "name",
                        line.unitAmountMinor(),
                        line.baseAmountMinor(),
                        line.finalAmountMinor(),
                        line.taxAmountMinor()));
            }
        }
        long total =
                lines.stream().mapToLong(QuoteSnapshot.Line::finalAmountMinor).sum();
        return new QuoteSnapshot(
                REPRICE_QUOTE,
                TENANT,
                BRAND,
                LOCATION,
                CUSTOMER,
                "UZS",
                QuoteSnapshot.Status.ACTIVE,
                UUID.randomUUID(),
                "hash",
                total,
                0L,
                0L,
                0L,
                total,
                WEIGHED_AT.plusSeconds(600),
                lines,
                List.of(),
                null,
                null,
                null,
                null);
    }

    private static QuoteSnapshot quoteWithFee(long feeMinor, QuoteSnapshot.Line... lines) {
        long goods = java.util.Arrays.stream(lines)
                .mapToLong(QuoteSnapshot.Line::finalAmountMinor)
                .sum();
        return new QuoteSnapshot(
                REPRICE_QUOTE,
                TENANT,
                BRAND,
                LOCATION,
                CUSTOMER,
                "UZS",
                QuoteSnapshot.Status.ACTIVE,
                UUID.randomUUID(),
                "hash",
                goods,
                0L,
                feeMinor,
                0L,
                goods + feeMinor,
                WEIGHED_AT.plusSeconds(600),
                List.of(lines),
                List.of(),
                null,
                null,
                null,
                null);
    }

    private static QuoteSnapshot withLoyalty(QuoteSnapshot quote, boolean accrual, boolean redemption) {
        return new QuoteSnapshot(
                quote.quoteId(),
                quote.tenantId(),
                quote.brandId(),
                quote.locationId(),
                quote.customerAccountId(),
                quote.currency(),
                quote.status(),
                quote.catalogPublicationId(),
                quote.contextHash(),
                quote.subtotalMinor(),
                quote.taxMinor(),
                quote.feeMinor(),
                quote.discountMinor(),
                quote.totalMinor(),
                quote.expiresAt(),
                quote.lines(),
                quote.adjustments(),
                quote.deliveryOutcome(),
                quote.deliveryShortfallMinor(),
                quote.deliveryMinBasketMinor(),
                quote.deliveryFreeFromMinor(),
                accrual,
                redemption);
    }

    private List<OrderLineRow> currentLines() {
        return orders.lines(TENANT, ORDER);
    }

    // --------------------------------------------------------------- fixtures

    private static OrderRow order(FulfillmentMode mode) {
        return order(mode, 0L);
    }

    private static OrderRow order(FulfillmentMode mode, long feeMinor) {
        return new OrderRow(
                ORDER,
                "0001",
                TENANT,
                BRAND,
                LOCATION,
                UUID.randomUUID(),
                "STOREFRONT",
                CUSTOMER,
                "guest-hash",
                mode,
                "AUTO_CONFIRM",
                null,
                0,
                "NONE",
                null,
                null,
                OrderStatus.READY,
                "NOT_REQUIRED",
                "NONE",
                "UZS",
                0L,
                0L,
                0L,
                feeMinor,
                215_000L + feeMinor,
                CHECKOUT_QUOTE,
                "hash",
                UUID.randomUUID(),
                UUID.randomUUID(),
                "idem-key",
                new OrderPromise(PLACED_AT.plusSeconds(1_800), PromiseBasis.PREPARATION_BAND, 25, null),
                7,
                PLACED_AT,
                PLACED_AT,
                null,
                1,
                "CUSTOMER",
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                0L,
                "");
    }

    private static RevisionRow revision(int number, UUID quoteId) {
        return new RevisionRow(
                ORDER,
                number,
                "CHECKOUT",
                null,
                quoteId,
                "hash",
                "UZS",
                0L,
                0L,
                0L,
                0L,
                215_000L,
                0L,
                false,
                "CUSTOMER",
                null,
                PLACED_AT);
    }

    private static OrderLineRow catchweightLine(int number, UUID variant, long amount) {
        return catchweightLine(number, variant, amount, null);
    }

    private static OrderLineRow catchweightLine(int number, UUID variant, long amount, @Nullable Integer weighed) {
        return new OrderLineRow(
                UUID.randomUUID(),
                number,
                UUID.randomUUID(),
                variant,
                "Tort",
                "",
                "SKU-CAKE",
                BigDecimal.ONE,
                180_000L,
                amount,
                amount,
                amount * 12 / 112,
                "",
                null,
                null,
                null,
                null,
                null,
                null,
                100,
                1_200,
                15_000L,
                weighed);
    }

    private static OrderLineRow plainLine(int number, UUID variant, long amount) {
        return new OrderLineRow(
                UUID.randomUUID(),
                number,
                UUID.randomUUID(),
                variant,
                "Cola",
                "",
                "SKU",
                BigDecimal.ONE,
                amount,
                amount,
                amount,
                amount * 12 / 112,
                "",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static OrderLineRow componentLine(int number, UUID variant, UUID component, long amount) {
        return new OrderLineRow(
                UUID.randomUUID(),
                number,
                UUID.randomUUID(),
                variant,
                "name",
                "",
                "SKU",
                BigDecimal.ONE,
                amount,
                amount,
                amount,
                amount * 12 / 112,
                "",
                SELECTION,
                LUNCH,
                "Lunch box",
                component,
                1,
                1,
                null,
                null,
                null,
                null);
    }

    private static OrderModifierRow modifier(
            OrderLineRow line, UUID modifierId, UUID optionId, @Nullable UUID parent, boolean auto) {
        return new OrderModifierRow(
                line.lineId(), UUID.randomUUID(), optionId, "group", "option", 1, 0L, 0L, modifierId, parent, auto);
    }

    private static QuoteSnapshot.Line quotedCake(OrderLineRow line, long amount, int grams) {
        return new QuoteSnapshot.Line(
                line.lineId().toString(),
                line.sourceVariantId(),
                BigDecimal.ONE,
                "Tort",
                180_000L,
                amount,
                amount,
                amount * 12 / 112,
                null,
                null,
                null,
                null,
                null,
                new QuoteSnapshot.Catchweight(100, 1_200, 15_000L, grams));
    }

    private static QuoteSnapshot.Line quotedComponent(OrderLineRow line) {
        int position = BURGER_IN_LUNCH.equals(line.comboComponentId()) ? 1 : 2;
        return new QuoteSnapshot.Line(
                SELECTION + "~" + position,
                line.sourceVariantId(),
                line.quantity(),
                "name",
                line.unitAmountMinor(),
                line.baseAmountMinor(),
                line.finalAmountMinor(),
                line.taxAmountMinor(),
                SELECTION,
                LUNCH,
                line.comboComponentId(),
                1,
                1,
                null);
    }
}
