package uz.horecaos.platform.reporting.application;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.reporting.application.ReportingFacts.BranchDayAggregate;
import uz.horecaos.platform.reporting.application.ReportingFacts.BranchDayKey;
import uz.horecaos.platform.reporting.application.ReportingFacts.OrderFact;
import uz.horecaos.platform.reporting.application.ReportingFacts.OrderLineFact;
import uz.horecaos.platform.reporting.application.ReportingFacts.RefundFact;
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;
import uz.horecaos.platform.reporting.domain.MetricRegistry;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore.RefundedOrder;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore.SourceLine;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore.SourceOrder;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore.SourceRefund;

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

    public DayCloseService(
            JdbcReportingStore store, BusinessDayService businessDays, SubjectPseudonym pseudonym, Clock clock) {
        this.store = store;
        this.businessDays = businessDays;
        this.pseudonym = pseudonym;
        this.clock = clock;
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

        derived.orders().forEach(store::insertOrderFact);
        derived.lines().forEach(store::insertLineFact);
        derived.refunds().forEach(store::insertRefundFact);
        derived.aggregates().forEach(store::insertAggregate);
        DayAggregator.slaBuckets(tenantId, businessDate, derived.orders()).forEach(store::insertSlaBucket);

        store.completeRun(runId, derived.orders().size(), derived.lines().size(), 0, clock.instant());

        log.info(
                "Closed business day {} for tenant {}: {} orders, {} lines, {} refunds",
                businessDate,
                tenantId,
                derived.orders().size(),
                derived.lines().size(),
                derived.refunds().size());

        return new CloseResult(
                runId,
                derived.orders().size(),
                derived.lines().size(),
                derived.refunds().size(),
                List.of());
    }

    /**
     * Re-derives a closed day and reports what disagrees.
     *
     * <p>Nothing stored is changed. The comparison runs at the branch-day grain
     * on the three figures a person acts on — gross revenue, net revenue, and the
     * completed order count — because a divergence report that lists every column
     * of every slice is one nobody reads.
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

        for (Divergence divergence : divergences) {
            store.insertDivergence(
                    UUID.randomUUID(),
                    tenantId,
                    runId,
                    businessDate,
                    divergence.metricName(),
                    divergence.metricVersion(),
                    describe(divergence.key()),
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
                divergences);
    }

    // ------------------------------------------------------------ derivation

    private DerivedDay derive(UUID tenantId, LocalDate businessDate, BusinessDayBoundary boundary) {

        Instant from = boundary.startOf(businessDate);
        Instant to = boundary.endOf(businessDate);

        List<SourceOrder> sourceOrders = store.readSourceOrders(tenantId, from, to);
        List<SourceLine> sourceLines = store.readSourceLines(tenantId, from, to);
        List<SourceRefund> sourceRefunds = store.readSourceRefunds(tenantId, from, to);

        Map<UUID, List<SourceLine>> linesByOrder = new HashMap<>();
        sourceLines.forEach(line -> linesByOrder
                .computeIfAbsent(line.orderId(), ignored -> new ArrayList<>())
                .add(line));

        List<OrderFact> orders = new ArrayList<>(sourceOrders.size());
        List<OrderLineFact> lines = new ArrayList<>(sourceLines.size());

        for (SourceOrder source : sourceOrders) {
            List<SourceLine> orderLines = linesByOrder.getOrDefault(source.orderId(), List.of());
            orders.add(toFact(tenantId, businessDate, boundary, source, orderLines));
            for (SourceLine line : orderLines) {
                lines.add(toFact(tenantId, businessDate, source, line));
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

        return new DerivedDay(
                orders,
                lines,
                refunds,
                DayAggregator.branchDay(
                        businessDate, orders, refunds, boundary.version(), MetricRegistry.CALCULATION_VERSION));
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

        // Lateness is a closed order's settled fact and is known only when a
        // promise was made. Null is the third state — no promise, or still open —
        // and is not an on-time order.
        Integer secondsLate = source.promisedAt() == null || source.closedAt() == null
                ? null
                : (int) Duration.between(source.promisedAt(), source.closedAt()).toSeconds();

        int itemCount = lines.stream().mapToInt(SourceLine::quantity).sum();

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
                // The category needs a catalogue join that reaches into a module
                // schema for a dimension this build does not cut by. Left null
                // rather than half-resolved.
                null,
                line.productName(),
                line.quantity(),
                gross,
                gross - line.finalAmountMinor(),
                line.finalAmountMinor());
    }

    private static Integer elapsed(Instant from, Instant to) {
        return from == null || to == null
                ? null
                : (int) Duration.between(from, to).toSeconds();
    }

    private static void compare(
            List<Divergence> into, BranchDayKey key, String metricName, int metricVersion, long stored, long recut) {
        if (stored != recut) {
            into.add(new Divergence(key, metricName, metricVersion, stored, recut));
        }
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
            List<RefundFact> refunds,
            List<BranchDayAggregate> aggregates) {}

    /**
     * One slice whose re-derived figure disagrees with the stored one.
     *
     * <p>Never applied. It is evidence that something is wrong, handed to a person
     * along with the figure that is still on the screen.
     */
    public record Divergence(
            BranchDayKey key, String metricName, int metricVersion, long storedValue, long recutValue) {

        public long difference() {
            return recutValue - storedValue;
        }
    }

    /** What a close or a recut did. */
    public record CloseResult(
            UUID runId, int ordersWritten, int linesWritten, int refundsWritten, List<Divergence> divergences) {}
}
