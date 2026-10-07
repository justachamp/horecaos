package uz.horecaos.platform.reporting.application;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.pricing.api.PromotionRedemptionSource;
import uz.horecaos.platform.reporting.application.ReportingFacts.BranchDayAggregate;
import uz.horecaos.platform.reporting.application.ReportingFacts.BranchDayKey;
import uz.horecaos.platform.reporting.application.ReportingFacts.CallHourFact;
import uz.horecaos.platform.reporting.application.ReportingFacts.OrderFact;
import uz.horecaos.platform.reporting.application.ReportingFacts.OrderLineFact;
import uz.horecaos.platform.reporting.application.ReportingFacts.RefundFact;
import uz.horecaos.platform.reporting.application.ReportingFacts.TenderFact;
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;
import uz.horecaos.platform.reporting.domain.MetricRegistry;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore.RefundedOrder;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore.SourceLine;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore.SourceOrder;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore.SourceRefund;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore.SourceTender;
import uz.horecaos.platform.web.api.Quantities;

/**
 * Builds a business day's facts, and later checks that they were right
 * (ADR 0043).
 *
 * <p>Two operations, deliberately asymmetric.
 *
 * <p>{@link #close} derives the day from {@code ordering} and {@code payments}
 * and writes it. It is idempotent: running it twice over unchanged sources
 * produces byte-identical aggregates, which is the property the whole recut
 * depends on.
 *
 * <p>{@link #recut} re-derives the same day after the settle window and
 * <em>compares</em>. It writes divergence rows and touches neither the facts nor
 * the aggregates. That is the ADR's rule and it is worth being explicit about why
 * a self-correcting projection is the wrong design here: the correction hides the
 * bug that caused the drift, and — the operational half of the same argument —
 * somebody has already acted on the earlier figure. A manager who ordered stock
 * against Tuesday's revenue is never told the number they used has gone.
 */
@Service
public class DayCloseService {

    private static final Logger log = LoggerFactory.getLogger(DayCloseService.class);

    private final JdbcReportingStore store;
    private final BusinessDayService businessDays;
    private final SubjectPseudonym pseudonym;
    private final Clock clock;
    private final @Nullable PromotionRedemptionSource promotionRedemptions;

    public DayCloseService(
            JdbcReportingStore store, BusinessDayService businessDays, SubjectPseudonym pseudonym, Clock clock) {
        this(store, businessDays, pseudonym, clock, null);
    }

    /**
     * @param promotionRedemptions ADR 0140: where the redemption fact (7.9) comes from, read
     *        through the {@code pricing.api} port and never from pricing's tables (ADR 0023);
     *        null builds no promotion facts, which is what a close with no pricing wired does
     */
    @Autowired
    public DayCloseService(
            JdbcReportingStore store,
            BusinessDayService businessDays,
            SubjectPseudonym pseudonym,
            Clock clock,
            @Nullable PromotionRedemptionSource promotionRedemptions) {
        this.store = store;
        this.businessDays = businessDays;
        this.pseudonym = pseudonym;
        this.clock = clock;
        this.promotionRedemptions = promotionRedemptions;
    }

    /**
     * Derives and stores one tenant's business day.
     *
     * <p>One transaction. A partially written day is worse than an unwritten one:
     * a report over half a Tuesday looks like a quiet Tuesday, and nothing on the
     * screen says otherwise.
     */
    @Transactional
    public CloseResult close(UUID tenantId, LocalDate businessDate) {
        BusinessDayBoundary boundary = businessDays.boundaryFor(tenantId);
        UUID runId = UUID.randomUUID();
        Instant startedAt = clock.instant();
        store.insertRun(
                runId,
                tenantId,
                businessDate,
                "CLOSE",
                boundary.version(),
                MetricRegistry.CALCULATION_VERSION,
                startedAt);

        DerivedDay derived = derive(tenantId, businessDate, boundary);

        store.clearDay(tenantId, businessDate);
        store.clearMisfiledOrders(
                tenantId, derived.orders().stream().map(OrderFact::orderId).toList(), businessDate);
        // The promotion fact is keyed by redemption alone, so a redemption a boundary change moved
        // onto this day must leave the day it was filed under first.
        store.clearMisfiledPromotionRedemptions(
                tenantId,
                derived.promotionRedemptions().stream()
                        .map(ReportingFacts.PromotionRedemptionFact::redemptionId)
                        .toList(),
                businessDate);

        derived.orders().forEach(store::insertOrderFact);
        // P39: the tender producer, right beside the order-fact write and inside
        // the same transaction as everything else in this method. A tender fact
        // that failed to write would otherwise leave fact_order_tender half
        // populated for a day whose fact_order rows all committed — exactly the
        // "partially written day is worse than an unwritten one" the class doc
        // above already argues for orders, and there is nothing tender-specific
        // about that argument.
        derived.tenders().forEach(store::insertTenderFact);
        derived.lines().forEach(store::insertLineFact);
        derived.refunds().forEach(store::insertRefundFact);
        derived.aggregates().forEach(store::insertAggregate);
        DayAggregator.slaBuckets(tenantId, businessDate, derived.orders()).forEach(store::insertSlaBucket);
        derived.callHours().forEach(store::insertCallHourFact);
        // T11 / ADR 0125: fact_delivery and the COURIER scope of
        // agg_sla_bucket_day, right beside the order-side producers and inside
        // the same transaction, for the reason the P39 comment above already
        // gives for tenders — a fact half-written for a day whose other facts
        // all committed is worse than one never written.
        derived.deliveries().forEach(store::insertDeliveryFact);
        DayAggregator.courierSlaBuckets(tenantId, businessDate, derived.deliveries())
                .forEach(store::insertSlaBucket);
        // w6-reporting-facts, batch 11 (7.4b/7.4c, ADR 0023/0125): right
        // beside the delivery producer above, inside the same transaction,
        // for the same "a day is written whole or not at all" reason.
        derived.feeResolutions().forEach(store::insertTariffFeeResolutionFact);
        derived.externalDeliveryCosts().forEach(store::insertExternalDeliveryCostFact);
        // ADR 0140 (7.9): the promotion redemption fact, in the same transaction for the
        // same "a day is written whole or not at all" reason.
        derived.promotionRedemptions().forEach(store::insertPromotionRedemptionFact);

        store.completeRun(runId, derived.orders().size(), derived.lines().size(), 0, clock.instant());

        log.info(
                "Closed business day {} for tenant {}: {} orders, {} lines, {} tenders, {} refunds, "
                        + "{} call-hours, {} deliveries, {} fee resolutions, {} external-delivery costs, "
                        + "{} promotion redemptions",
                businessDate,
                tenantId,
                derived.orders().size(),
                derived.lines().size(),
                derived.tenders().size(),
                derived.refunds().size(),
                derived.callHours().size(),
                derived.deliveries().size(),
                derived.feeResolutions().size(),
                derived.externalDeliveryCosts().size(),
                derived.promotionRedemptions().size());

        return new CloseResult(
                runId,
                derived.orders().size(),
                derived.lines().size(),
                derived.refunds().size(),
                derived.callHours().size(),
                List.of());
    }

    /**
     * Re-derives a closed day and reports what disagrees.
     *
     * <p>Nothing stored is changed. The comparison runs at the branch-day grain
     * on the three figures a person acts on — gross revenue, net revenue, and the
     * completed order count — because a divergence report that lists every column
     * of every slice is one nobody reads. The promotion redemption fact (ADR 0140,
     * report 7.9) is compared beside them, per brand, on the redemption count and
     * the discount and markup given: the ledger moves after a day closes when an
     * amendment restates a row, and this is where that is reported.
     *
     * <p>The tender fact (ADR 0115, {@code payment_mix.amount.v1}) is compared the same
     * way, per (branch, legal entity, payment method): a tender fact is a net-in-place
     * snapshot taken at close, so a refund that lands the next morning, or a capture the
     * acquirer confirms after the day closed, leaves the stored figure behind the payment
     * ledger. The recut is the one place that says so; nothing rewrites the stored row.
     */
    @Transactional
    public CloseResult recut(UUID tenantId, LocalDate businessDate) {
        BusinessDayBoundary boundary = businessDays.boundaryFor(tenantId);
        UUID runId = UUID.randomUUID();
        store.insertRun(
                runId,
                tenantId,
                businessDate,
                "RECUT",
                boundary.version(),
                MetricRegistry.CALCULATION_VERSION,
                clock.instant());

        DerivedDay derived = derive(tenantId, businessDate, boundary);

        Map<BranchDayKey, BranchDayAggregate> stored = new LinkedHashMap<>();
        store.readAggregates(tenantId, businessDate, businessDate).forEach(row -> stored.put(row.key(), row));

        Map<BranchDayKey, BranchDayAggregate> fresh = new LinkedHashMap<>();
        derived.aggregates().forEach(row -> fresh.put(row.key(), row));

        List<Divergence> divergences = new ArrayList<>();
        for (BranchDayKey key : union(stored.keySet(), fresh.keySet())) {
            BranchDayAggregate before = stored.get(key);
            BranchDayAggregate after = fresh.get(key);

            compare(
                    divergences,
                    key,
                    "revenue.gross",
                    1,
                    before == null ? 0 : before.grossSom(),
                    after == null ? 0 : after.grossSom());
            compare(
                    divergences,
                    key,
                    "revenue.net",
                    1,
                    before == null ? 0 : before.netSom() - before.refundedSom(),
                    after == null ? 0 : after.netSom() - after.refundedSom());
            compare(
                    divergences,
                    key,
                    "orders.count",
                    1,
                    before == null ? 0 : before.orderCount(),
                    after == null ? 0 : after.orderCount());
        }

        comparePromotionFacts(divergences, tenantId, businessDate, derived.promotionRedemptions());
        comparePaymentMix(divergences, tenantId, businessDate, derived.tenders());

        for (Divergence divergence : divergences) {
            store.insertDivergence(
                    UUID.randomUUID(),
                    tenantId,
                    runId,
                    businessDate,
                    divergence.metricName(),
                    divergence.metricVersion(),
                    divergence.dimension(),
                    divergence.storedValue(),
                    divergence.recutValue());
        }
        store.completeRun(runId, derived.orders().size(), derived.lines().size(), divergences.size(), clock.instant());

        if (!divergences.isEmpty()) {
            // Logged at warn and not error: the stored figure is still what every
            // surface shows, deliberately, so this is something a person has to
            // decide about rather than an outage.
            log.warn(
                    "Recut of {} for tenant {} disagrees with the stored day in {} places; "
                            + "the stored figures were left alone (ADR 0043)",
                    businessDate,
                    tenantId,
                    divergences.size());
        }
        return new CloseResult(
                runId,
                derived.orders().size(),
                derived.lines().size(),
                derived.refunds().size(),
                derived.callHours().size(),
                divergences);
    }

    // ------------------------------------------------------------ derivation

    private DerivedDay derive(UUID tenantId, LocalDate businessDate, BusinessDayBoundary boundary) {

        Instant from = boundary.startOf(businessDate);
        Instant to = boundary.endOf(businessDate);

        List<SourceOrder> sourceOrders = store.readSourceOrders(tenantId, from, to);
        List<SourceLine> sourceLines = store.readSourceLines(tenantId, from, to);
        List<SourceTender> sourceTenders = store.readSourceTenders(tenantId, from, to);
        List<SourceRefund> sourceRefunds = store.readSourceRefunds(tenantId, from, to);
        var sourceCallEvents = store.readSourceCallEvents(tenantId, from, to);

        Map<UUID, List<SourceLine>> linesByOrder = new HashMap<>();
        sourceLines.forEach(line -> linesByOrder
                .computeIfAbsent(line.orderId(), ignored -> new ArrayList<>())
                .add(line));
        Map<UUID, List<SourceTender>> tendersByOrder = new HashMap<>();
        sourceTenders.forEach(tender -> tendersByOrder
                .computeIfAbsent(tender.orderId(), ignored -> new ArrayList<>())
                .add(tender));

        List<OrderFact> orders = new ArrayList<>(sourceOrders.size());
        List<OrderLineFact> lines = new ArrayList<>(sourceLines.size());
        List<TenderFact> tenders = new ArrayList<>(sourceTenders.size());

        for (SourceOrder source : sourceOrders) {
            List<SourceLine> orderLines = linesByOrder.getOrDefault(source.orderId(), List.of());
            orders.add(toFact(tenantId, businessDate, boundary, source, orderLines));
            for (SourceLine line : orderLines) {
                lines.add(toFact(tenantId, businessDate, source, line));
            }
            for (SourceTender tender : tendersByOrder.getOrDefault(source.orderId(), List.of())) {
                tenders.add(toFact(tenantId, businessDate, boundary, source, tender));
            }
        }

        Map<UUID, RefundedOrder> refundedOrders = store.findRefundedOrders(
                tenantId, sourceRefunds.stream().map(SourceRefund::orderId).toList());

        List<RefundFact> refunds = new ArrayList<>();
        for (SourceRefund refund : sourceRefunds) {
            RefundedOrder order = refundedOrders.get(refund.orderId());
            if (order == null) {
                continue;
            }
            refunds.add(new RefundFact(
                    tenantId,
                    businessDate,
                    refund.refundId(),
                    refund.orderId(),
                    boundary.dateOf(order.createdAt()),
                    order.locationId(),
                    order.legalEntityId(),
                    order.channelCode(),
                    order.fulfilmentMode(),
                    refund.amountMinor(),
                    refund.occurredAt(),
                    boundary.version(),
                    MetricRegistry.CALCULATION_VERSION));
        }

        List<CallHourFact> callHours = DayAggregator.callHourFacts(
                tenantId,
                businessDate,
                boundary.zone(),
                sourceCallEvents,
                boundary.version(),
                MetricRegistry.CALCULATION_VERSION);

        // T11 / ADR 0125. Read by the same [from, to) instant range every
        // order-side source above uses, against the earning's own
        // delivered_at — not by trusting the earning's stored business_date,
        // which an adversarial review (2026-09-14) found could disagree with
        // this boundary for a delivery in the tenant's early-morning window.
        List<uz.horecaos.platform.reporting.application.ReportingFacts.DeliveryFact> deliveries =
                store.readSourceDeliveries(tenantId, from, to).stream()
                        .map(source -> new uz.horecaos.platform.reporting.application.ReportingFacts.DeliveryFact(
                                tenantId,
                                source.earningId(),
                                businessDate,
                                boundary.version(),
                                MetricRegistry.CALCULATION_VERSION,
                                source.courierId(),
                                source.locationId(),
                                source.brandId(),
                                source.shipmentId(),
                                source.assignmentAttemptId(),
                                source.distanceMeters(),
                                source.distanceSource(),
                                source.onTimeOutcome(),
                                source.acceptedAt(),
                                source.deliveredAt(),
                                Math.toIntExact(Duration.between(source.acceptedAt(), source.deliveredAt())
                                        .getSeconds())))
                        .toList();

        // w6-reporting-facts, batch 11 (7.4b, ADR 0023/0125): same instant
        // range as every source read above, against the resolution's own
        // created_at -- delivery_fee_resolutions has no business_date of its
        // own to trust (V0338's own comment: "resolved once, at checkout,
        // and never revisited").
        List<uz.horecaos.platform.reporting.application.ReportingFacts.TariffFeeResolutionFact> feeResolutions =
                store.readSourceTariffResolutions(tenantId, from, to).stream()
                        .map(source ->
                                new uz.horecaos.platform.reporting.application.ReportingFacts.TariffFeeResolutionFact(
                                        tenantId,
                                        source.resolutionId(),
                                        businessDate,
                                        boundary.version(),
                                        MetricRegistry.CALCULATION_VERSION,
                                        source.locationId(),
                                        source.tariffId(),
                                        source.tariffVersion(),
                                        source.zoneId(),
                                        source.bandSequence(),
                                        source.courierId(),
                                        source.orderId(),
                                        source.shipmentId(),
                                        source.finalFeeMinor(),
                                        source.currency(),
                                        source.resolvedAt()))
                        .toList();

        // w6-reporting-facts, batch 11 (7.4c, ADR 0023/0125): same instant
        // range as fact_delivery's own, against the shipment's own
        // delivered_at -- see ReportingFacts.ExternalDeliveryCostFact's own
        // doc for why matchStatus/providerBilledMinor/varianceMinor are a
        // close-time snapshot rather than a live reconciliation state.
        List<uz.horecaos.platform.reporting.application.ReportingFacts.ExternalDeliveryCostFact> externalDeliveryCosts =
                store.readSourceExternalDeliveryCosts(tenantId, from, to).stream()
                        .map(source ->
                                new uz.horecaos.platform.reporting.application.ReportingFacts.ExternalDeliveryCostFact(
                                        tenantId,
                                        source.shipmentId(),
                                        businessDate,
                                        boundary.version(),
                                        MetricRegistry.CALCULATION_VERSION,
                                        source.locationId(),
                                        source.orderId(),
                                        source.publicOrderNumber(),
                                        source.orderTotalMinor(),
                                        source.currency(),
                                        source.chargedDeliveryMinor(),
                                        source.providerType(),
                                        source.providerEstimatedMinor(),
                                        source.invoiceLineId(),
                                        source.providerBilledMinor(),
                                        source.matchStatus(),
                                        source.varianceMinor(),
                                        source.deliveredAt()))
                        .toList();

        // ADR 0140 (7.9): same instant range as every source read above, against the
        // ledger's own redeemed_at. The customer becomes the ADR 0029 keyed pseudonym here
        // and never travels as an account id; a coupon redemption's word never crosses the
        // port at all.
        List<uz.horecaos.platform.reporting.application.ReportingFacts.PromotionRedemptionFact> promotionFacts =
                promotionRedemptions == null
                        ? List.of()
                        : promotionRedemptions.redeemedBetween(tenantId, from, to).stream()
                                .map(source ->
                                        new uz.horecaos.platform.reporting.application.ReportingFacts
                                                .PromotionRedemptionFact(
                                                tenantId,
                                                source.redemptionId(),
                                                businessDate,
                                                boundary.version(),
                                                MetricRegistry.CALCULATION_VERSION,
                                                source.brandId(),
                                                source.promotionId(),
                                                source.promotionCode(),
                                                source.definitionVersion(),
                                                source.kind().name(),
                                                source.couponId(),
                                                source.orderId(),
                                                source.customerAccountId() == null
                                                        ? null
                                                        : pseudonym.of(tenantId, source.customerAccountId()),
                                                source.discountMinor(),
                                                source.markupMinor(),
                                                source.currency(),
                                                source.redeemedAt()))
                                .toList();

        return new DerivedDay(
                orders,
                lines,
                tenders,
                refunds,
                DayAggregator.branchDay(
                        businessDate, orders, refunds, boundary.version(), MetricRegistry.CALCULATION_VERSION),
                callHours,
                deliveries,
                feeResolutions,
                externalDeliveryCosts,
                promotionFacts);
    }

    /**
     * P39: one tender, on its order's business date — see {@link TenderFact}'s
     * own doc for why this is not the refund's grain. {@code source} is the
     * same {@link SourceOrder} {@link #toFact(UUID, LocalDate,
     * BusinessDayBoundary, SourceOrder, List)} above already turned into this
     * order's {@link OrderFact}, so the location and legal entity are read
     * from it rather than re-resolved: two facts for one order must never
     * disagree about which branch or which taxpayer it belongs to.
     */
    private static TenderFact toFact(
            UUID tenantId,
            LocalDate businessDate,
            BusinessDayBoundary boundary,
            SourceOrder source,
            SourceTender tender) {
        return new TenderFact(
                tenantId,
                businessDate,
                source.orderId(),
                tender.sequence(),
                boundary.version(),
                source.locationId(),
                source.legalEntityId(),
                tender.paymentMethodCode(),
                tender.settlesFromBalance(),
                tender.status(),
                tender.amountMinor(),
                MetricRegistry.CALCULATION_VERSION);
    }

    private OrderFact toFact(
            UUID tenantId,
            LocalDate businessDate,
            BusinessDayBoundary boundary,
            SourceOrder source,
            List<SourceLine> lines) {

        // Gross is the order value before discount. The order row stores the total
        // net of discount (ADR 0019: total = subtotal + tax + fee - discount), and
        // ADR 0043 defines net revenue as gross minus discount, so reading the
        // total as gross would subtract the discount twice.
        long gross = source.totalMinor() + source.discountMinor();

        Integer secondsToConfirm = elapsed(source.createdAt(), source.confirmedAt());
        Integer secondsToReady = elapsed(source.confirmedAt(), source.readyAt());
        Integer secondsTotal = elapsed(source.createdAt(), source.closedAt());
        // Wave P27 (7.2): splits secondsToReady into the wait for the branch to
        // actually start the order and the cooking that follows it.
        Integer secondsToAccept = elapsed(source.confirmedAt(), source.preparingAt());
        Integer secondsPreparing = elapsed(source.preparingAt(), source.readyAt());

        // Lateness is a closed order's settled fact and is known only when a
        // promise was made. Null is the third state — no promise, or still open —
        // and is not an on-time order.
        Integer secondsLate = source.promisedAt() == null || source.closedAt() == null
                ? null
                : (int) Duration.between(source.promisedAt(), source.closedAt()).toSeconds();

        // The units the order asked for, whole: item_count is an integer column (V0031)
        // and a decimal portion (ADR 0137) is counted as the plate it occupies, so the
        // total is the exact sum rounded up once, not each half portion rounded alone.
        int itemCount = Quantities.wholeUnitsCeiling(
                lines.stream().map(SourceLine::quantity).reduce(BigDecimal.ZERO, BigDecimal::add));

        // T12: whoever approved the order, else whoever created it, else the
        // channel itself as a pseudo-operator — see OperatorAttribution's own
        // doc for why accepted_by outranks created_by and why "no human actor"
        // is never a third null state here.
        String operatorPrincipalId = OperatorAttribution.resolve(
                source.createdByActorType(),
                source.createdByActorId(),
                source.acceptedByActorType(),
                source.acceptedByActorId(),
                source.channelCode());

        return new OrderFact(
                tenantId,
                source.orderId(),
                businessDate,
                boundary.version(),
                source.createdAt(),
                source.closedAt(),
                source.brandId(),
                source.locationId(),
                source.legalEntityId(),
                source.channelCode(),
                source.fulfilmentMode(),
                source.status(),
                source.cancellationReasonCode(),
                operatorPrincipalId,
                pseudonym.of(tenantId, source.customerAccountId()),
                source.customerAccountId() == null ? null : source.firstOrder(),
                gross,
                source.discountMinor(),
                source.feeMinor(),
                source.taxMinor(),
                gross - source.discountMinor(),
                lines.size(),
                itemCount,
                secondsToConfirm,
                secondsToReady,
                secondsTotal,
                source.promisedAt(),
                source.promiseTravelMinutes(),
                secondsLate,
                secondsToAccept,
                secondsPreparing,
                source.publicOrderNumber(),
                source.stockDisposition(),
                source.liabilityParty(),
                source.deliveryDistanceMeters(),
                MetricRegistry.CALCULATION_VERSION,
                source.version());
    }

    private static OrderLineFact toFact(UUID tenantId, LocalDate businessDate, SourceOrder order, SourceLine line) {
        // A line's final amount can exceed its base once paid modifiers are added,
        // so the discount is the drop from the higher of the two rather than a
        // subtraction that would go negative and fail ck_fact_order_line_amounts.
        long gross = Math.max(line.baseAmountMinor(), line.finalAmountMinor());
        return new OrderLineFact(
                tenantId,
                businessDate,
                order.orderId(),
                line.lineId(),
                order.locationId(),
                line.variantId(),
                // Wave W02: JdbcReportingStore#readSourceLines now resolves this
                // with a catalogue join at read time, so the source line already
                // carries the answer rather than this method guessing at one.
                line.categoryId(),
                line.productName(),
                line.quantity(),
                gross,
                gross - line.finalAmountMinor(),
                line.finalAmountMinor(),
                // Wave W02 (7.8a): the line's own order occurred_at, so a line
                // can be bucketed by operating-day hour without a join back to
                // fact_order.
                order.createdAt(),
                // ADR 0038 (V0370, batch 6 review): the line's own order
                // legal_entity_id, so ProductClassificationService can filter
                // or refuse revenue.gross.v1 by legal entity without a join
                // back to fact_order.
                order.legalEntityId(),
                // ADR 0136 (V0478): which combo the line was bought as part of, copied so
                // "sales by combo" reads the fact alone.
                line.comboSelectionId(),
                line.comboContainerVariantId(),
                line.comboQuantity(),
                line.comboName());
    }

    private static @Nullable Integer elapsed(@Nullable Instant from, @Nullable Instant to) {
        return from == null || to == null
                ? null
                : (int) Duration.between(from, to).toSeconds();
    }

    private static void compare(
            List<Divergence> into, BranchDayKey key, String metricName, int metricVersion, long stored, long recut) {
        compare(into, describe(key), metricName, metricVersion, stored, recut);
    }

    private static void compare(
            List<Divergence> into, String dimension, String metricName, int metricVersion, long stored, long recut) {
        if (stored != recut) {
            into.add(new Divergence(dimension, metricName, metricVersion, stored, recut));
        }
    }

    /**
     * ADR 0140 (7.9): the redemption count and the discount and markup given, per brand, against
     * the rows the close stored.
     *
     * <p>The ledger is final when an order completes, but an amendment applied or a redemption
     * recorded late changes a row after the day closed, and the record says such a change is "a
     * divergence for recut to report, not a silent rewrite". Nothing else would: a promotion's
     * discount is inside the day's gross and net revenue only as a difference between two figures
     * that both moved.
     */
    private void comparePromotionFacts(
            List<Divergence> into,
            UUID tenantId,
            LocalDate businessDate,
            List<uz.horecaos.platform.reporting.application.ReportingFacts.PromotionRedemptionFact> derived) {
        Map<UUID, long[]> stored = new LinkedHashMap<>();
        store.readPromotionDayTotals(tenantId, businessDate)
                .forEach(total -> stored.put(
                        total.brandId(), new long[] {total.redemptions(), total.discountMinor(), total.markupMinor()}));
        Map<UUID, long[]> fresh = new LinkedHashMap<>();
        for (var fact : derived) {
            long[] totals = fresh.computeIfAbsent(fact.brandId(), brand -> new long[3]);
            totals[0]++;
            totals[1] += fact.discountMinor();
            totals[2] += fact.markupMinor();
        }
        java.util.Set<UUID> brands = new java.util.LinkedHashSet<>(stored.keySet());
        brands.addAll(fresh.keySet());
        long[] none = new long[3];
        for (UUID brand : brands) {
            long[] before = stored.getOrDefault(brand, none);
            long[] after = fresh.getOrDefault(brand, none);
            String dimension = "brand=%s".formatted(brand);
            compare(into, dimension, "promotion.redemptions", 1, before[0], after[0]);
            compare(into, dimension, "promotion.discount", 1, before[1], after[1]);
            compare(into, dimension, "promotion.markup", 1, before[2], after[2]);
        }
    }

    /**
     * ADR 0115 ({@code payment_mix.amount.v1}): the net tendered amount per (branch, legal
     * entity, payment method), over the tenders the metric's inclusion rule counts, against the
     * rows the close stored.
     *
     * <p>Net-in-place is what makes this comparison necessary rather than decorative: the
     * record chooses a snapshot of "what is in the till for this order, by method" over a
     * movement ledger, and the price of that choice is that a refund recorded after the close
     * is invisible to the stored fact. The comparison reads the same SQL the report reads
     * ({@link JdbcReportingStore#readPaymentMix}), so what it calls "stored" is exactly what a
     * manager saw on the screen.
     */
    private void comparePaymentMix(
            List<Divergence> into, UUID tenantId, LocalDate businessDate, List<TenderFact> derived) {
        Map<String, Long> stored = new LinkedHashMap<>();
        store.readPaymentMix(tenantId, businessDate, businessDate, List.of(), List.of())
                .forEach(row -> stored.merge(
                        paymentMixDimension(row.locationId(), row.legalEntityId(), row.paymentMethodCode()),
                        row.amountSom(),
                        Long::sum));
        Map<String, Long> fresh = new LinkedHashMap<>();
        for (var fact : derived) {
            if (!countsInPaymentMix(fact.tenderStatus())) {
                continue;
            }
            fresh.merge(
                    paymentMixDimension(fact.locationId(), fact.legalEntityId(), fact.paymentMethodCode()),
                    fact.amountSom(),
                    Long::sum);
        }
        java.util.Set<String> slices = new java.util.LinkedHashSet<>(stored.keySet());
        slices.addAll(fresh.keySet());
        for (String slice : slices) {
            compare(
                    into,
                    slice,
                    "payment_mix.amount",
                    1,
                    stored.getOrDefault(slice, 0L),
                    fresh.getOrDefault(slice, 0L));
        }
    }

    /** {@code payment_mix.amount.v1}'s inclusion rule, {@code SETTLED_OR_REVERSED_TENDERS}. */
    private static boolean countsInPaymentMix(String tenderStatus) {
        return "SETTLED".equals(tenderStatus) || "REVERSED".equals(tenderStatus);
    }

    private static String paymentMixDimension(UUID locationId, @Nullable UUID legalEntityId, String paymentMethodCode) {
        return "location=%s;entity=%s;method=%s".formatted(locationId, legalEntityId, paymentMethodCode);
    }

    private static List<BranchDayKey> union(java.util.Set<BranchDayKey> left, java.util.Set<BranchDayKey> right) {
        List<BranchDayKey> all = new ArrayList<>(left);
        right.stream().filter(key -> !left.contains(key)).forEach(all::add);
        return all;
    }

    private static String describe(BranchDayKey key) {
        return "location=%s;entity=%s;channel=%s;fulfilment=%s"
                .formatted(key.locationId(), key.legalEntityId(), key.channelCode(), key.fulfilmentType());
    }

    private record DerivedDay(
            List<OrderFact> orders,
            List<OrderLineFact> lines,
            List<TenderFact> tenders,
            List<RefundFact> refunds,
            List<BranchDayAggregate> aggregates,
            List<CallHourFact> callHours,
            List<uz.horecaos.platform.reporting.application.ReportingFacts.DeliveryFact> deliveries,
            List<uz.horecaos.platform.reporting.application.ReportingFacts.TariffFeeResolutionFact> feeResolutions,
            List<uz.horecaos.platform.reporting.application.ReportingFacts.ExternalDeliveryCostFact>
                    externalDeliveryCosts,
            List<uz.horecaos.platform.reporting.application.ReportingFacts.PromotionRedemptionFact>
                    promotionRedemptions) {}

    /**
     * One slice whose re-derived figure disagrees with the stored one.
     *
     * <p>Never applied. It is evidence that something is wrong, handed to a person
     * along with the figure that is still on the screen.
     */
    public record Divergence(
            String dimension, String metricName, int metricVersion, long storedValue, long recutValue) {

        public long difference() {
            return recutValue - storedValue;
        }
    }

    /**
     * What a close or a recut did.
     *
     * @param callsWritten the ADR 0064 call-hour facts a close derived; always
     *                     the count re-derived on a recut too, even though
     *                     recut does not yet compare it against what is
     *                     stored (see {@link #recut}'s own limits)
     */
    public record CloseResult(
            UUID runId,
            int ordersWritten,
            int linesWritten,
            int refundsWritten,
            int callsWritten,
            List<Divergence> divergences) {}
}
