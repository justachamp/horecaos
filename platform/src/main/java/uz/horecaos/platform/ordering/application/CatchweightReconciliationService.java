package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.fulfillment.api.PricingAuthority;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.api.OrderSettlementPort;
import uz.horecaos.platform.ordering.domain.OrderStatus;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderFieldPatch;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderLineRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderModifierRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.RevisionRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.RevisionTotals;
import uz.horecaos.platform.pricing.api.CartPricingPort;
import uz.horecaos.platform.pricing.api.PromoCodeRedemptionPort;
import uz.horecaos.platform.pricing.api.PromotionRedemptionPort;
import uz.horecaos.platform.pricing.api.QuoteAcceptance;
import uz.horecaos.platform.pricing.api.QuoteAcceptancePort;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * The weight captured at handover, and the charge it corrects (ADR 0137).
 *
 * <p>A catchweight line is checked out at a provisional amount: its price row is "per
 * quantum of weight", and the quote could only compute it against the menu's nominal
 * weight. The real weight is known to the person at the scale. Capturing it here writes
 * {@code order_lines.actual_weight_grams} and corrects the line's amounts, the order's
 * totals and, where the order is still to be paid at the door, the amount it will collect
 * -- the same "captured late, reconciled before the receipt is final" shape ADR 0038
 * accepted for marking codes.
 *
 * <h2>Pricing stays in pricing</h2>
 *
 * <p>This class does no arithmetic on money. It re-prices the order's live basket through
 * the same {@link CartPricingPort} a cart and an amendment use, handing each weighed line's
 * captured weight in, and copies what comes back. A re-implementation here of "quantum
 * rounding, then tax, then the order-level discount" would be a second answer to what an
 * order costs, and two answers to that is the defect ADR 0018 exists to prevent.
 *
 * <p>The basket goes back in the way an amendment sends it ({@link AmendmentBasket}) and under
 * the context the order was bought in: a combo as the combo and its picks and not as its
 * components, the customer's own choices and not the options the server applied (pricing
 * applies those again for the order's mode, and handing them back would charge them twice),
 * the order's fulfilment mode, its delivery point, and the promotion inputs recorded on the
 * quote behind its current revision (ADR 0140). A weight captured at 15:20 on a delivery order
 * placed at lunch time is priced as that order, not as a pickup quote of 15:20: the "delivery
 * orders" promotion, the lunch window and the first-order discount all still hold.
 *
 * <p>Two things are checked on the way back so that a re-price cannot do more than the
 * weight asked of it. A weighed line must still be priced at the per-quantum price the
 * customer agreed to (snapshotted on the line), and every other line must still have the
 * unit and base amount it was checked out at. A menu repriced between checkout and the
 * scale would otherwise ride in on a weight capture and change what the customer owes for
 * a dish nobody weighed; here it refuses instead, and nothing is written.
 *
 * <h2>What is, and is not, an amendment</h2>
 *
 * <p>The correction appends an order revision (source {@code CATCHWEIGHT}) carrying the
 * complete new total and the signed delta, so the order's money history still answers "what
 * was it worth before it was weighed". It is not an ADR 0039 amendment: nobody proposed it,
 * the customer is not asked to confirm it (a catchweight total is provisional by its own
 * definition, and ADR 0137 accepts that "the total they see at checkout is not necessarily
 * the total they pay"), and it needs no second signature -- an operator reading a scale is
 * not choosing a discount.
 *
 * <h2>What a revision carries with it</h2>
 *
 * <p>The re-priced quote becomes the order's current quote, so everything that is recorded
 * against "the quote behind the current revision" is restated with it, as an amendment restates
 * it: the promo code's redemption, the automatic promotions' ledger rows (ADR 0140, report 7.9)
 * and the loyalty flags those promotions set. A discount that grows with the weight would
 * otherwise be recorded at its nominal-weight amount for ever, and a promotion that stopped (or
 * began) applying at the scale would keep (or lack) the ledger row and the points rule the
 * order's totals no longer agree with.
 *
 * <h2>Money that has already moved</h2>
 *
 * <p>The correction is made only where the order's money is not yet taken. A cash order's
 * settlement is restated through {@link OrderSettlementPort#restateTotal}, so the amount a
 * courier is told to collect is the weighed one. An order whose payment already runs through
 * a provider is refused when the weight moves its total at all: an increase needs an
 * incremental charge and a decrease a partial refund, neither of which this build performs
 * (the same refusal ADR 0039's amendments give), and a total that disagrees with what the
 * provider holds is worse than a refusal an operator can see.
 */
@Service
public class CatchweightReconciliationService {

    /**
     * Where a weight can still be captured: after the restaurant accepted the order and
     * before it left the pass. FULFILLING and later are refused -- the handover blocker
     * ({@code CATCHWEIGHT_NOT_RECONCILED}) is what keeps an unweighed order from getting
     * there, and a total corrected after the food is out the door is a dispute, not a
     * reconciliation.
     */
    private static final Set<OrderStatus> WEIGHABLE =
            EnumSet.of(OrderStatus.CONFIRMED, OrderStatus.PREPARING, OrderStatus.READY);

    /** The one payment projection that means no money is held against the order yet. */
    private static final String NO_PAYMENT_TAKEN = "NOT_REQUIRED";

    private final JdbcOrderStore orders;
    private final CartPricingPort pricing;
    private final QuoteAcceptancePort quoteAcceptance;
    private final PromoCodeRedemptionPort promoCodes;
    private final PromotionRedemptionPort promotions;
    private final OrderSettlementPort settlement;
    private final OrderDeliveryPoint deliveryPoint;
    private final AuditRecorder audit;
    private final Clock clock;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public CatchweightReconciliationService(
            JdbcOrderStore orders,
            CartPricingPort pricing,
            QuoteAcceptancePort quoteAcceptance,
            PromoCodeRedemptionPort promoCodes,
            PromotionRedemptionPort promotions,
            OrderSettlementPort settlement,
            OrderDeliveryPoint deliveryPoint,
            AuditRecorder audit,
            Clock clock) {
        this.orders = orders;
        this.pricing = pricing;
        this.quoteAcceptance = quoteAcceptance;
        this.promoCodes = promoCodes;
        this.promotions = promotions;
        this.settlement = settlement;
        this.deliveryPoint = deliveryPoint;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * What a reconciliation did.
     *
     * @param changed false when the captured weight was the one already on the line, in which
     *     case nothing was written and no revision was appended
     * @param deltaTotalMinor the order total after minus before; zero when the weight
     *     happened to match what the nominal weight priced
     */
    public record Result(
            boolean changed,
            int orderVersion,
            int revision,
            long totalMinor,
            long deltaTotalMinor,
            long lineFinalAmountMinor,
            int actualWeightGrams) {}

    /**
     * Captures the weighed total of one line and corrects the order against it.
     *
     * @param actualWeightGrams the weight of the whole line -- all its units together --
     *     weighed once
     * @param expectedVersion the order version the caller read (ADR 0031)
     * @throws RefusedException the weight cannot be applied, with a stable code
     * @throws OrderStateService.StaleOrderException the order moved since the caller read it
     */
    @Transactional
    public Result reconcile(
            UUID tenantId,
            UUID orderId,
            UUID lineId,
            int actualWeightGrams,
            int expectedVersion,
            String actorType,
            String actorId,
            @Nullable String correlationId) {

        if (actualWeightGrams <= 0) {
            throw new RefusedException("WEIGHT_NOT_POSITIVE", "A weighed amount must be more than zero grams");
        }
        Instant now = clock.instant();
        OrderRow order =
                orders.find(tenantId, orderId).orElseThrow(() -> new OrderStateService.OrderNotFoundException(orderId));
        if (order.version() != expectedVersion) {
            throw new OrderStateService.StaleOrderException(expectedVersion, order.version());
        }
        if (!WEIGHABLE.contains(order.status())) {
            throw new RefusedException(
                    "ORDER_NOT_WEIGHABLE",
                    "An order that is %s can no longer have a weight captured against it".formatted(order.status()));
        }

        List<OrderLineRow> live = orders.lines(tenantId, orderId);
        OrderLineRow target = live.stream()
                .filter(line -> line.lineId().equals(lineId))
                .findFirst()
                .orElseThrow(() -> new LineNotFoundException(lineId));
        if (!target.catchweight()) {
            throw new RefusedException(
                    "LINE_NOT_CATCHWEIGHT", "This line is not sold by weight, so there is no weight to capture");
        }
        if (Integer.valueOf(actualWeightGrams).equals(target.actualWeightGrams())) {
            // The scale read the same number twice, or a retry arrived after the first
            // succeeded. Correct, and not worth a revision that says nothing changed.
            return new Result(
                    false,
                    order.version(),
                    order.currentRevision(),
                    order.totalMinor(),
                    0L,
                    target.finalAmountMinor(),
                    actualWeightGrams);
        }

        QuoteSnapshot quote = repriceWithWeights(order, live, lineId, actualWeightGrams, expectedVersion);
        Map<UUID, QuoteSnapshot.Line> quotedByLine = quotedByOrderLine(live, quote);
        requireNothingElseMoved(live, quotedByLine);

        // The delivery charge was agreed at checkout and is not a function of the weight: the
        // quote's own fee is taken back out of its total and the order's is put in its place. The
        // destination is passed to pricing only so the delivery-side promotions and zone-bound
        // conditions are evaluated as they were at checkout, not to re-quote the fee.
        // total = subtotal + tax + fee - discount holds on both sides.
        RevisionTotals totals = new RevisionTotals(
                quote.subtotalMinor(),
                quote.taxMinor(),
                quote.discountMinor(),
                order.feeMinor(),
                Math.addExact(Math.subtractExact(quote.totalMinor(), quote.feeMinor()), order.feeMinor()));
        long delta = totals.totalMinor() - order.totalMinor();

        if (delta != 0) {
            if (!NO_PAYMENT_TAKEN.equals(order.paymentStatusProjection())) {
                throw new RefusedException(
                        "PAYMENT_ALREADY_TAKEN",
                        ("This order's payment already runs through a provider, and the weighed amount moves "
                                        + "its total by %d %s. Collecting more or giving back some needs an "
                                        + "incremental charge or a partial refund this build does not perform; "
                                        + "the order is left as it is.")
                                .formatted(delta, order.currency()));
            }
            if (!settlement.restateTotal(tenantId, orderId, totals.totalMinor(), actorId)) {
                throw new RefusedException(
                        "SETTLEMENT_NOT_RESTATABLE",
                        "The amount this order is to collect cannot be restated: money against it is "
                                + "already in motion");
            }
        }

        QuoteAcceptance acceptance = quoteAcceptance.acceptQuote(tenantId, quote.quoteId(), quote.contextHash());
        if (!acceptance.isAccepted()) {
            throw new RefusedException(
                    "REPRICE_QUOTE_LAPSED",
                    "The re-price behind this weight lapsed before it could be applied; capture it again");
        }

        int newRevision = order.currentRevision() + 1;
        orders.insertRevision(new JdbcOrderStore.NewRevision(
                orderId,
                newRevision,
                tenantId,
                REVISION_SOURCE,
                null,
                quote.quoteId(),
                quote.contextHash(),
                order.currency(),
                totals.subtotalMinor(),
                totals.taxMinor(),
                totals.discountMinor(),
                totals.feeMinor(),
                totals.totalMinor(),
                delta,
                // The receipt is not final yet -- that is the whole premise of capturing the
                // weight before handover -- so there is no issued document to correct.
                false,
                actorType,
                actorId,
                now));
        int orderVersion = orders.applyRevision(
                        tenantId,
                        orderId,
                        expectedVersion,
                        newRevision,
                        new OrderFieldPatch(null, null, null, null, null, null, totals),
                        actorId,
                        now)
                .orElseThrow(() -> new OrderStateService.StaleOrderException(
                        expectedVersion,
                        orders.find(tenantId, orderId).map(OrderRow::version).orElse(0)));

        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        before.put("totalMinor", order.totalMinor());
        after.put("totalMinor", totals.totalMinor());
        before.put("revision", order.currentRevision());
        after.put("revision", newRevision);
        for (OrderLineRow line : live) {
            QuoteSnapshot.Line quoted = Objects.requireNonNull(quotedByLine.get(line.lineId()));
            boolean isTarget = line.lineId().equals(lineId);
            if (!orders.applyReconciledAmounts(
                    tenantId,
                    orderId,
                    line.lineId(),
                    isTarget ? actualWeightGrams : null,
                    quoted.baseAmountMinor(),
                    quoted.finalAmountMinor(),
                    quoted.taxAmountMinor())) {
                throw new RefusedException(
                        "ORDER_LINE_NOT_LIVE", "Line " + line.lineId() + " is no longer live on this order");
            }
            String key = "line" + line.lineNumber();
            before.put(key + "FinalAmountMinor", line.finalAmountMinor());
            after.put(key + "FinalAmountMinor", quoted.finalAmountMinor());
            if (isTarget) {
                before.put(key + "ActualWeightGrams", line.actualWeightGrams());
                after.put(key + "ActualWeightGrams", actualWeightGrams);
            }
        }

        // The coupon this order holds keeps its slot; only the amount it stands for moves.
        promoCodes.restateForOrder(tenantId, orderId, quote.quoteId());
        // ADR 0140: the same for the automatic promotions, and for what they decide about points.
        // The order keeps one ledger row per promotion and it moves in place to the quote behind
        // this revision, so a discount that grows with the weight is recorded at the amount given;
        // a promotion that stopped applying is released (its counter stays consumed) and one that
        // newly applies gets a row. A weight never claims a slot, as an amendment never does.
        promotions.restateForOrder(
                tenantId, order.brandId(), orderId, quote.quoteId(), newRevision, order.customerAccountId(), now);
        orders.setLoyaltyFlags(tenantId, orderId, quote.loyaltyAccrualAllowed(), quote.loyaltyRedemptionAllowed());

        audit.record(AuditFact.of("ordering.order.catchweight-reconciled", AuditClass.BUSINESS)
                .by(actorOf(actorType, actorId))
                .at(ResourceScope.location(order.tenantId(), order.brandId(), order.locationId()))
                .target("ordering.order", orderId)
                .targetVersion((long) orderVersion)
                .outcome(AuditFact.Outcome.SUCCEEDED)
                .because("Weight captured at handover")
                .changed(ChangeDocuments.diff(before, after))
                .correlatedBy(correlationId == null ? orderId.toString() : correlationId)
                .occurredAt(now)
                .build());

        QuoteSnapshot.Line weighed = Objects.requireNonNull(quotedByLine.get(lineId));
        return new Result(
                true,
                orderVersion,
                newRevision,
                totals.totalMinor(),
                delta,
                weighed.finalAmountMinor(),
                actualWeightGrams);
    }

    /** {@code ordering.order_revisions.source} for a reconciliation (V0450). */
    public static final String REVISION_SOURCE = "CATCHWEIGHT";

    /**
     * The whole live basket, priced again through the cart's own entry point, with the
     * weights already captured plus the one being captured now. Lines nobody has weighed yet
     * go back in provisional, so reconciling one line of three never touches the other two.
     *
     * <p>Built the way {@link OrderAmendmentService} builds the same request for an amendment, so
     * the two ways an order is re-priced cannot disagree about what it was bought as: see the
     * class documentation, and {@link AmendmentBasket#pricingItems} for the basket itself.
     */
    private QuoteSnapshot repriceWithWeights(
            OrderRow order, List<OrderLineRow> live, UUID weighedLineId, int weighedGrams, int expectedVersion) {

        Map<UUID, List<OrderModifierRow>> modifiersByLine =
                orders.lineModifiers(order.tenantId(), order.orderId()).stream()
                        .collect(Collectors.groupingBy(OrderModifierRow::orderLineId));
        List<CartPricingPort.PricingCommand.Item> items = AmendmentBasket.pricingItems(
                AmendmentBasket.units(live), Map.of(), modifiersByLine, Map.of(weighedLineId, weighedGrams));

        // ADR 0140. The order was bought once, at one instant, by one method, and that is what
        // it is re-priced under: pricing starts from the promotion inputs recorded on the quote
        // behind the order's current revision, and the frame overrides nothing a weight changes.
        // The clock is never an override. An order priced before calculation version 3 recorded
        // none, and falls back to its own creation time and fulfilment mode.
        UUID currentQuoteId = orders.revisions(order.tenantId(), order.orderId()).stream()
                .filter(revision -> revision.revision() == order.currentRevision())
                .map(RevisionRow::pricingQuoteId)
                .findFirst()
                .orElse(order.pricingQuoteId());
        var frame = new CartPricingPort.PricingCommand.PromotionFrame(
                null, null, order.fulfillmentMode().name(), currentQuoteId, order.createdAt());

        GeoPoint point = deliveryPoint.of(order, "CATCHWEIGHT_REPRICE");
        CartPricingPort.PricingCommand.Delivery delivery =
                point == null ? null : new CartPricingPort.PricingCommand.Delivery(point, PricingAuthority.HORECAOS);
        try {
            return pricing.priceCart(new CartPricingPort.PricingCommand(
                    order.tenantId(),
                    order.brandId(),
                    order.locationId(),
                    order.customerAccountId(),
                    order.channelCode(),
                    items,
                    // Keyed on the order, the line, the weight and the version it was read at, so a
                    // retried request prices once and a changed order prices afresh.
                    "catchweight:%s:%s:%d:v%d".formatted(order.orderId(), weighedLineId, weighedGrams, expectedVersion),
                    null,
                    delivery,
                    // The redemption this order's checkout recorded rides along, exactly as it does
                    // for an amendment, so a promo code earned at checkout is not re-priced away.
                    order.orderId(),
                    // Which hidden auto-selected groups apply is decided by the order's own mode, and
                    // a dine-in order is told apart from a pickup one by nothing else.
                    order.fulfillmentMode(),
                    frame));
        } catch (CartPricingPort.PricingRefusedException refused) {
            throw new RefusedException(
                    refused.code(), Objects.requireNonNullElse(refused.getMessage(), refused.code()));
        }
    }

    /**
     * The quote's line for each live order line.
     *
     * <p>An ordinary line is priced under its own id. A combo is priced as one cart line and
     * comes back as one line per component, so each stored component line is matched to its
     * priced component by the pairing it was bought from, exactly as an amendment matches them.
     */
    private static Map<UUID, QuoteSnapshot.Line> quotedByOrderLine(List<OrderLineRow> live, QuoteSnapshot quote) {
        Map<String, List<QuoteSnapshot.Line>> byCartLine = quote.lines().stream()
                .collect(Collectors.groupingBy(
                        QuoteSnapshot.Line::cartLineKey, LinkedHashMap::new, Collectors.toList()));
        Map<UUID, QuoteSnapshot.Line> byOrderLine = new HashMap<>();
        for (AmendmentBasket.Unit unit : AmendmentBasket.units(live)) {
            for (QuoteSnapshot.Line quoted : byCartLine.getOrDefault(unit.key(), List.of())) {
                try {
                    byOrderLine.put(unit.replacedBy(quoted).lineId(), quoted);
                } catch (IllegalStateException unknownComponent) {
                    throw new RefusedException(
                            "REPRICE_COMPONENT_MISMATCH",
                            "The re-price priced a combo component this order never had; nothing is written");
                }
            }
        }
        return byOrderLine;
    }

    /**
     * Refuses a re-price that moved anything but the weight (see the class documentation).
     */
    private static void requireNothingElseMoved(List<OrderLineRow> live, Map<UUID, QuoteSnapshot.Line> quotedByLine) {
        for (OrderLineRow line : live) {
            QuoteSnapshot.Line quoted = quotedByLine.get(line.lineId());
            if (quoted == null) {
                throw new RefusedException("REPRICE_DROPPED_LINE", "The re-price did not return line " + line.lineId());
            }
            if (line.catchweight()) {
                QuoteSnapshot.Catchweight now = quoted.catchweight();
                int agreedQuantum = Objects.requireNonNull(line.catchweightQuantumGrams());
                long agreedPrice = Objects.requireNonNull(line.catchweightPricePerQuantumMinor());
                if (now == null || now.quantumGrams() != agreedQuantum || now.pricePerQuantumMinor() != agreedPrice) {
                    throw new RefusedException(
                            "CATCHWEIGHT_PRICE_CHANGED",
                            "Line %s is no longer priced at what the customer agreed to per quantum; "
                                            .formatted(line.lineId())
                                    + "the weight is not applied");
                }
            } else if (quoted.unitAmountMinor() != line.unitAmountMinor()
                    || quoted.baseAmountMinor() != line.baseAmountMinor()) {
                throw new RefusedException(
                        "ORDER_REPRICE_DRIFT",
                        "Line %s would now be priced differently from what it was checked out at; "
                                        .formatted(line.lineId())
                                + "capturing the weight would change more than the weighed line");
            }
        }
    }

    private static ActorRef actorOf(String actorType, String actorId) {
        return switch (actorType == null ? "SERVICE" : actorType) {
            case "USER" -> ActorRef.user(actorId == null ? "unknown-user" : actorId, null);
            case "SYSTEM_JOB" -> ActorRef.systemJob(actorId == null ? "ordering" : actorId);
            default -> ActorRef.service(actorId == null ? "ordering" : actorId);
        };
    }

    /** The weight cannot be applied, with a code a client can branch on. */
    public static class RefusedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String code;

        public RefusedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /** The line is not a live line of this order. */
    public static class LineNotFoundException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        public LineNotFoundException(UUID lineId) {
            super("Line " + lineId + " is not a live line of this order");
        }
    }
}
