package uz.horecaos.platform.courier.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.courier.domain.AdjustmentOrigin;
import uz.horecaos.platform.courier.domain.LedgerEntryType;
import uz.horecaos.platform.courier.domain.OnTimeOutcome;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.EarningRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.PeriodRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierLedgerStore.ShiftDeliveryMetrics;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.ShiftRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.AdjustmentReasonRow;

/**
 * The rule half of ADR 0042's "bonuses and penalties are one mechanism with
 * two origins" (ADR 0108). A manual adjustment names an actor and a reason a
 * person picked at the moment of the penalty; a rule-derived one evaluates a
 * typed condition — one of {@code courier_adjustment_reasons}' own {@code
 * outcome_basis} values against its own {@code rule_threshold} — over a
 * window, at a trigger, and posts the same registry's {@code
 * rule_amount_minor} when it holds. Neither this class nor anything it calls
 * ever reads a value the request supplied for the origin it stamps: {@link
 * AdjustmentOrigin#RULE} comes only from here, which is the fix for the gap
 * the gap map named — a client that could set {@code origin=RULE} on {@code
 * POST .../adjustments} bypassed the four-eyes branch that a {@code MANUAL}
 * penalty above threshold requires. {@code OperationsCourierController} no
 * longer accepts an origin at all; every HTTP-originated adjustment is
 * {@code MANUAL}, unconditionally, and {@code RULE} exists only as a value
 * this class's own Java call constructs.
 *
 * <p><strong>What this evaluates, and what it does not.</strong> Five of
 * {@code courier_adjustment_reasons.outcome_basis}' seven values are
 * computable from data this module already owns and are evaluated here:
 * {@code DELIVERED_VOLUME}, {@code LATE_DELIVERY}, {@code ON_TIME_RATE},
 * {@code GEO_UNVERIFIED_RATE}, and (period window only) {@code
 * CASH_VARIANCE}. {@code ORDER_UNDELIVERED} and {@code ORDER_DAMAGED} name
 * facts this module has no reader for — they live in {@code
 * fulfillment.delivery_exceptions}, which nothing here queries — so a reason
 * carrying either basis may only be authored manual-only (its {@code
 * rule_amount_minor} left null); {@link
 * uz.horecaos.platform.courier.application.CourierAdjustmentService.AdjustmentCommand}
 * is entirely unaffected, since a manual entry never reaches this class.
 *
 * <p><strong>Wired, and not wired.</strong> {@link #evaluateShiftClose} runs
 * from {@link CourierShiftService#close} (the {@code CLOSED} path, mirroring
 * where {@code creditShiftEarning} already runs) and from {@link
 * CourierShiftService#approveHours}, so a {@code SHIFT}-window rule fires the
 * same moment paid seconds become final. {@link #evaluatePeriodClose} is
 * built and tested the same way but is <em>not</em> called from {@link
 * CourierSettlementService#close} this wave — that close method reads {@code
 * entriesOf}/{@code earningsOf}/{@code computeTotals} and then hashes the
 * result, and a rule posted after that read would be silently excluded from
 * the very statement it was supposed to affect. Wiring it correctly means
 * moving the evaluation before that read, which touches the statement-hash
 * sequencing this wave chose not to disturb; ADR 0108 records the exact call
 * site as an open input rather than leaving it unmentioned.
 *
 * <p>Every posted entry is idempotent on {@code "rule:" + reasonCode + ":" +
 * windowId} (the shift id, or the period id), so a shift closed twice by a
 * retried request evaluates the same rule twice and posts once — {@link
 * CourierLedgerService#append}'s own idempotency guarantee, not a new one.
 */
@Service
public class AdjustmentRuleEvaluator {

    private static final String WINDOW_SHIFT = "SHIFT";
    private static final String WINDOW_PERIOD = "SETTLEMENT_PERIOD";
    private static final String TRIGGER_SHIFT_CLOSE = "SHIFT_CLOSE";
    private static final String TRIGGER_PERIOD_CLOSE = "SETTLEMENT_PERIOD_CLOSE";
    private static final long RATE_BASIS_POINTS = 10_000L;

    private final JdbcCourierStore couriers;
    private final JdbcCourierLedgerStore ledgerStore;
    private final CourierAdjustmentService adjustments;

    public AdjustmentRuleEvaluator(
            JdbcCourierStore couriers, JdbcCourierLedgerStore ledgerStore, CourierAdjustmentService adjustments) {
        this.couriers = couriers;
        this.ledgerStore = ledgerStore;
        this.adjustments = adjustments;
    }

    /** Every {@code SHIFT}-window, {@code SHIFT_CLOSE}-trigger rule this shift's outcome may satisfy. */
    @Transactional
    public List<CourierAdjustmentService.Outcome> evaluateShiftClose(UUID tenantId, ShiftRow shift) {
        List<AdjustmentReasonRow> rules = couriers.ruleReasonsAt(tenantId, WINDOW_SHIFT, TRIGGER_SHIFT_CLOSE);
        if (rules.isEmpty()) {
            return List.of();
        }
        ShiftDeliveryMetrics metrics = ledgerStore.shiftDeliveryMetrics(tenantId, shift.id());

        List<CourierAdjustmentService.Outcome> posted = new ArrayList<>();
        for (AdjustmentReasonRow rule : rules) {
            metricFor(rule.outcomeBasis(), metrics)
                    .filter(value -> holds(rule, value))
                    .ifPresent(value ->
                            posted.add(post(tenantId, shift.courierId(), shift.locationId(), rule, shift.id())));
        }
        return posted;
    }

    /**
     * Every {@code SETTLEMENT_PERIOD}-window, {@code SETTLEMENT_PERIOD_CLOSE}-trigger
     * rule this period's outcome may satisfy.
     *
     * <p>Built and tested, and deliberately not called from {@code
     * CourierSettlementService.close} this wave — see this class's own doc.
     */
    @Transactional
    public List<CourierAdjustmentService.Outcome> evaluatePeriodClose(
            UUID tenantId, PeriodRow period, UUID locationId) {
        List<AdjustmentReasonRow> rules = couriers.ruleReasonsAt(tenantId, WINDOW_PERIOD, TRIGGER_PERIOD_CLOSE);
        if (rules.isEmpty()) {
            return List.of();
        }
        List<EarningRow> earnings = ledgerStore.earningsOf(tenantId, period.id());
        long delivered = earnings.size();
        long onTime = earnings.stream()
                .filter(e -> e.onTimeOutcome() == OnTimeOutcome.ON_TIME)
                .count();
        long late = earnings.stream()
                .filter(e -> e.onTimeOutcome() == OnTimeOutcome.LATE)
                .count();
        long geoUnverified = earnings.stream().filter(EarningRow::geoUnverified).count();
        long cashVariance = ledgerStore.entriesOf(tenantId, period.id()).stream()
                .filter(e -> e.entryType() == LedgerEntryType.CASH_VARIANCE)
                .count();

        List<CourierAdjustmentService.Outcome> posted = new ArrayList<>();
        for (AdjustmentReasonRow rule : rules) {
            Long value =
                    switch (rule.outcomeBasis()) {
                        case "DELIVERED_VOLUME" -> delivered;
                        case "LATE_DELIVERY" -> late;
                        case "ON_TIME_RATE" -> delivered == 0 ? null : onTime * RATE_BASIS_POINTS / delivered;
                        case "GEO_UNVERIFIED_RATE" ->
                            delivered == 0 ? null : geoUnverified * RATE_BASIS_POINTS / delivered;
                        case "CASH_VARIANCE" -> cashVariance;
                        default -> null; // ORDER_UNDELIVERED, ORDER_DAMAGED: no reader here — manual only.
                    };
            if (value != null && holds(rule, value)) {
                posted.add(post(tenantId, period.courierId(), locationId, rule, period.id()));
            }
        }
        return posted;
    }

    /** @return empty when the basis has no shift-level reading (a rate over zero deliveries, or an unsupported basis) */
    private static Optional<Long> metricFor(String outcomeBasis, ShiftDeliveryMetrics metrics) {
        return switch (outcomeBasis) {
            case "DELIVERED_VOLUME" -> Optional.of((long) metrics.deliveredCount());
            case "LATE_DELIVERY" -> Optional.of((long) metrics.lateCount());
            case "ON_TIME_RATE" ->
                metrics.deliveredCount() == 0
                        ? Optional.empty()
                        : Optional.of(metrics.onTimeCount() * RATE_BASIS_POINTS / metrics.deliveredCount());
            case "GEO_UNVERIFIED_RATE" ->
                metrics.deliveredCount() == 0
                        ? Optional.empty()
                        : Optional.of(metrics.geoUnverifiedCount() * RATE_BASIS_POINTS / metrics.deliveredCount());
            // CASH_VARIANCE is period-window only (a shift's own handover is one row,
            // and "one variance in one shift" is a weaker signal than the period
            // total); ORDER_UNDELIVERED/ORDER_DAMAGED have no reader here.
            default -> Optional.empty();
        };
    }

    private static boolean holds(AdjustmentReasonRow rule, long value) {
        Long threshold = rule.ruleThreshold();
        if (threshold == null) {
            return false;
        }
        return "LTE".equals(rule.ruleComparator()) ? value <= threshold : value >= threshold;
    }

    private CourierAdjustmentService.Outcome post(
            UUID tenantId, UUID courierId, UUID locationId, AdjustmentReasonRow rule, UUID windowId) {
        // ruleReasonsAt only returns rows with rule_amount_minor set, and
        // ck_adjustment_reason_rule_pair ties currency, comparator, threshold,
        // window and trigger to it — so every field this method reads off
        // `rule` below is non-null in practice; requireNonNull says so to
        // NullAway rather than leaving it to infer from a query it cannot see.
        long amount = Objects.requireNonNull(rule.ruleAmountMinor(), "a rule reason carries a rule amount");
        String currency = Objects.requireNonNull(rule.ruleCurrency(), "a rule reason carries a rule currency");
        return adjustments.request(new CourierAdjustmentService.AdjustmentCommand(
                tenantId,
                courierId,
                locationId,
                amount,
                currency,
                rule.code(),
                AdjustmentOrigin.RULE,
                "rule:" + rule.code() + ":" + windowId,
                ActorRef.systemJob("courier-adjustment-rule-evaluator"),
                "Rule '%s' (%s %s %d) satisfied"
                        .formatted(rule.code(), rule.outcomeBasis(), rule.ruleComparator(), rule.ruleThreshold()),
                "courier-adjustment-rule-evaluator"));
    }
}
