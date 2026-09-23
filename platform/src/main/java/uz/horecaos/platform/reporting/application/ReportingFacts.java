package uz.horecaos.platform.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The rows the close job writes (ADR 0043).
 *
 * <p>Records rather than a mutable builder, because a fact is settled the moment
 * it is computed: everything a report needs is decided here, once, and stored.
 * Nothing downstream recomputes a figure, which is how two surfaces come to
 * disagree.
 */
public final class ReportingFacts {

    private ReportingFacts() {}

    /**
     * One order, on its business date.
     *
     * @param legalEntityId       ADR 0038, snapshotted from the payment intent
     *                            that priced the order. Null means no fiscal
     *                            identity was recorded, which is its own group
     * @param customerSubjectHash ADR 0029 keyed hash, never an account id
     * @param operatorPrincipalId T12 (ADR 0043): a staff subject when a
     *                            {@code USER} created or accepted the order, or
     *                            {@code "channel:<code>"} as a pseudo-operator
     *                            otherwise — see {@link OperatorAttribution}.
     *                            Never null on a fact this build writes; null
     *                            only on a row closed before T12 and never recut
     * @param grossRevenueSom     order value before discount, including fee and
     *                            tax. Whole som
     * @param secondsLate         signed seconds past the promise; null when there
     *                            was no promise or the order never closed, which
     *                            is a third state and not a zero
     * @param secondsToAccept     wave P27 (7.2): CONFIRMED -> PREPARING, the
     *                            "branch acceptance" wait — null until the order
     *                            reaches PREPARING
     * @param secondsPreparing    wave P27 (7.2): PREPARING -> READY, actual
     *                            cooking time, narrower than {@code
     *                            secondsToReady} — null until the order reaches
     *                            READY from PREPARING
     * @param publicOrderNumber   wave P27 (7.2a): snapshotted from {@code
     *                            ordering.orders.public_order_number} — the short
     *                            number a receipt and a kitchen ticket both print
     * @param stockDisposition    ADR 0039's {@code ordering.order_outcomes},
     *                            copied when a terminal outcome was recorded for
     *                            this order. Null on an order with no outcome row
     *                            yet (still open) or closed before ADR 0039
     * @param liabilityParty      see {@code stockDisposition} — the two travel
     *                            together from the same outcome row
     */
    public record OrderFact(
            UUID tenantId,
            UUID orderId,
            LocalDate businessDate,
            int boundaryVersion,
            Instant occurredAt,
            @Nullable Instant closedAt,
            UUID brandId,
            UUID locationId,
            @Nullable UUID legalEntityId,
            String channelCode,
            String fulfilmentType,
            String terminalStatus,
            @Nullable String cancellationReasonCode,
            @Nullable String operatorPrincipalId,
            @Nullable String customerSubjectHash,
            @Nullable Boolean isFirstOrder,
            long grossRevenueSom,
            long discountSom,
            long deliveryFeeSom,
            long taxSom,
            long netRevenueSom,
            int lineCount,
            int itemCount,
            @Nullable Integer secondsToConfirm,
            @Nullable Integer secondsToReady,
            @Nullable Integer secondsTotal,
            @Nullable Instant promisedAt,
            @Nullable Integer promiseTravelMinutes,
            @Nullable Integer secondsLate,
            @Nullable Integer secondsToAccept,
            @Nullable Integer secondsPreparing,
            @Nullable String publicOrderNumber,
            @Nullable String stockDisposition,
            @Nullable String liabilityParty,
            /** Wave 9 w4-reports-distance-crm (7.1): the delivery leg's resolved distance (ADR 0037), null for a non-delivery order. */
            @Nullable Integer deliveryDistanceMeters,
            int metricCalculationVersion,
            int sourceOrderVersion) {

        public OrderFact {
            Objects.requireNonNull(tenantId, "A fact is tenant-owned");
            Objects.requireNonNull(orderId, "A fact names its order");
            Objects.requireNonNull(businessDate, "A fact is dated");
            // Mirrors ck_fact_order_net. Asserted here as well as in the schema
            // because a constraint violation surfacing at the end of a batch is a
            // far worse diagnostic than a failure at the row that caused it.
            if (netRevenueSom != grossRevenueSom - discountSom) {
                throw new IllegalArgumentException("Net revenue is gross less discount, not " + netRevenueSom);
            }
            // Mirrors ck_fact_order_lateness_pairing.
            if ((promisedAt != null && closedAt != null) != (secondsLate != null)) {
                throw new IllegalArgumentException(
                        "Lateness is known exactly when a promise was made and the order closed");
            }
        }

        public boolean completed() {
            return "COMPLETED".equals(terminalStatus);
        }

        /** The four non-completing terminal statuses, which are never a subtraction. */
        public boolean cancelled() {
            return switch (terminalStatus) {
                case "CANCELLED", "REJECTED", "EXPIRED", "PAYMENT_FAILED" -> true;
                default -> false;
            };
        }

        public boolean late() {
            return secondsLate != null && secondsLate > 0;
        }
    }

    /**
     * One refund, on the refund's own business date.
     *
     * <p>A grain and not a column: a pair of columns on the order fact cannot
     * express an order refunded partially on Tuesday and again on Friday without
     * attributing one of them to the wrong day.
     */
    public record RefundFact(
            UUID tenantId,
            LocalDate businessDate,
            UUID refundId,
            UUID orderId,
            LocalDate orderBusinessDate,
            UUID locationId,
            UUID legalEntityId,
            String channelCode,
            String fulfilmentType,
            long refundedSom,
            Instant occurredAt,
            int boundaryVersion,
            int metricCalculationVersion) {

        public RefundFact {
            if (refundedSom <= 0) {
                throw new IllegalArgumentException("A refund of nothing is not a refund");
            }
        }
    }

    /**
     * One tender, on the ORDER's business date (ADR 0043/0115) — never a
     * separate date the way {@link RefundFact} uses its own, because "what is
     * currently in the till for this order, by method" is the figure a cash
     * reconciliation needs, not a ledger of every movement against it.
     *
     * @param amountSom net of any refund already recorded against the tender
     *                  (V0048's {@code payments.tenders.refunded_minor}). A
     *                  fully refunded tender is zero, never a negative row
     * @param tenderStatus every status {@code payments.tenders} can hold, not
     *                     only {@code SETTLED}/{@code REVERSED} — a
     *                     {@code PLANNED} or {@code FAILED} tender never
     *                     collected money and the metric layer's inclusion
     *                     rule, not this fact, is what excludes it
     */
    public record TenderFact(
            UUID tenantId,
            LocalDate businessDate,
            UUID orderId,
            int tenderSequence,
            int boundaryVersion,
            UUID locationId,
            @Nullable UUID legalEntityId,
            String paymentMethodCode,
            boolean settlesFromBalance,
            String tenderStatus,
            long amountSom,
            int metricCalculationVersion) {

        public TenderFact {
            Objects.requireNonNull(tenantId, "A fact is tenant-owned");
            Objects.requireNonNull(orderId, "A fact names its order");
            Objects.requireNonNull(businessDate, "A fact is dated");
            if (amountSom < 0) {
                throw new IllegalArgumentException("A tender's net amount cannot be negative");
            }
            if (tenderSequence < 1) {
                throw new IllegalArgumentException("Tender sequence is 1-based, matching payments.tenders.sequence");
            }
        }
    }

    /**
     * One order line, for the product cuts.
     *
     * @param occurredAt     wave W02 (7.8a): the line's own order's {@code
     *                       occurred_at}, copied rather than joined so a line
     *                       can be bucketed by operating-day hour without a
     *                       read back to {@code fact_order}
     * @param categoryId     wave W02: resolved from {@code catalog.variants}
     *                       via {@code catalog.category_products} at close
     *                       time — see {@code JdbcReportingStore#readSourceLines}'s
     *                       own doc for the tie-break when a product carries
     *                       more than one category. Null when the variant has
     *                       none
     * @param legalEntityId  ADR 0038 (V0370, batch 6 review): the line's own
     *                       order's {@code legal_entity_id}, copied the same
     *                       way {@code occurredAt} is so {@code
     *                       ProductClassificationService} can filter or refuse
     *                       revenue.gross.v1 by legal entity without a read
     *                       back to {@code fact_order}. Null means no fiscal
     *                       identity was recorded on the order, its own group,
     *                       never folded into a real entity
     */
    public record OrderLineFact(
            UUID tenantId,
            LocalDate businessDate,
            UUID orderId,
            UUID lineId,
            UUID locationId,
            UUID variantId,
            @Nullable UUID categoryId,
            String productNameSnapshot,
            int quantity,
            long grossSom,
            long discountSom,
            long netSom,
            Instant occurredAt,
            @Nullable UUID legalEntityId) {}

    /**
     * The slice a branch-day aggregate is keyed by.
     *
     * <p>{@code legalEntityId} is nullable and that null is load bearing: it is
     * the group of orders with no recorded fiscal identity, which stays its own
     * bucket rather than being folded into an entity that did not sell them.
     */
    public record BranchDayKey(
            UUID tenantId,
            LocalDate businessDate,
            UUID locationId,
            @Nullable UUID legalEntityId,
            String channelCode,
            String fulfilmentType) {}

    /**
     * One aggregate row.
     *
     * @param avgSecondsTotal  null when no order closed on this day. A zero would
     *                         read as an instant order, which is the more damaging
     *                         wrong answer
     * @param refundedSom      refunds attributed to <em>this</em> date, from orders
     *                         of any date
     * @param deliveryFeeSom   wave 8 w7-reports (7.2c, V0383): sum of the slice's
     *                         COMPLETED orders' {@code delivery_fee_som}. Already
     *                         part of {@code grossSom} (ADR 0019's total includes
     *                         the fee) — this exists so a report can show the
     *                         fee-exclusive figure too, by subtraction, without a
     *                         second read. Zero, not a real total, on any row
     *                         closed before the column existed — see
     *                         {@code delivery_fee.v1}'s own {@code openQuestion}
     */
    public record BranchDayAggregate(
            BranchDayKey key,
            int boundaryVersion,
            int metricCalculationVersion,
            int orderCount,
            int cancelledCount,
            long grossSom,
            long discountSom,
            long netSom,
            long refundedSom,
            @Nullable Integer avgSecondsTotal,
            int promisedCount,
            int lateCount,
            int distinctCustomers,
            int newCustomers,
            long deliveryFeeSom) {}

    /** One bucket of the fixed SLA distribution, for one location or one courier on one day. */
    public record SlaBucketAggregate(
            UUID tenantId,
            LocalDate businessDate,
            String scopeKind,
            UUID scopeId,
            int bucketSetVersion,
            String bucketCode,
            int orderCount,
            int shareBasisPoints) {}

    /**
     * T11 / ADR 0125: one internal delivery, straight off {@code
     * fulfillment.courier_assignment_earnings} (joined at close time to
     * {@code assignment_attempts} for {@code acceptedAt}, a column the
     * earning row itself does not carry). Never joined against a courier's
     * protected name — {@code 7.4}/{@code 7.4a} resolve display through
     * P19's reveal, keyed on {@code courierId}, never through this fact.
     *
     * @param courierAssignmentEarningId the natural key: one delivery
     *                                   accrues exactly once
     *                                   ({@code uq_earning_attempt}, V0040),
     *                                   so one earning is exactly one
     *                                   delivery fact
     * @param transitSeconds            {@code deliveredAt - acceptedAt}, the
     *                                  figure {@code 7.4}'s "transit hours"
     *                                  and {@code 7.4a}'s SLA buckets are
     *                                  both cut from
     */
    public record DeliveryFact(
            UUID tenantId,
            UUID courierAssignmentEarningId,
            LocalDate businessDate,
            int boundaryVersion,
            int metricCalculationVersion,
            UUID courierId,
            UUID locationId,
            UUID shipmentId,
            UUID assignmentAttemptId,
            int distanceMeters,
            String distanceSource,
            String onTimeOutcome,
            Instant acceptedAt,
            Instant deliveredAt,
            int transitSeconds) {

        public DeliveryFact {
            Objects.requireNonNull(tenantId, "A fact is tenant-owned");
            Objects.requireNonNull(courierAssignmentEarningId, "A delivery fact names its earning");
            if (transitSeconds < 0) {
                throw new IllegalArgumentException("A delivery cannot be delivered before it was accepted");
            }
        }
    }

    /**
     * ADR 0064: one hour's call activity for one operator at one location, on
     * one business date. The one new grain ADR 0043's day-only physical model
     * did not have — see {@code fact_call_hour}'s own migration comment for
     * why calls get an hour bucket instead of widening every existing fact.
     *
     * @param operatorPrincipalId {@code "(unassigned)"} rather than null: a
     *                            call this build could not attribute to a
     *                            specific operator (most missed calls, and
     *                            any answered call neither the provider nor
     *                            our own screen-pop acknowledgment could
     *                            name) is still a real call for the location,
     *                            and a primary key cannot hold a null column
     */
    public record CallHourFact(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            LocalDate businessDate,
            int hourOfDay,
            String operatorPrincipalId,
            int boundaryVersion,
            int metricCalculationVersion,
            int offeredCount,
            int answeredCount,
            int missedCount,
            int transferredCount,
            long talkDurationSeconds) {

        public CallHourFact {
            Objects.requireNonNull(tenantId, "A fact is tenant-owned");
            Objects.requireNonNull(businessDate, "A fact is dated");
            if (hourOfDay < 0 || hourOfDay > 23) {
                throw new IllegalArgumentException("hourOfDay must be 0-23, not " + hourOfDay);
            }
            // No answered-vs-offered check here: those two counts share a
            // location-hour, not a row — see fact_call_hour's own migration
            // comment for why that invariant is a rollup, not a per-row one.
        }
    }
}
