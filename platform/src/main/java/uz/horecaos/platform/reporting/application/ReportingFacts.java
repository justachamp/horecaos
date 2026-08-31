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
     * @param grossRevenueSom     order value before discount, including fee and
     *                            tax. Whole som
     * @param secondsLate         signed seconds past the promise; null when there
     *                            was no promise or the order never closed, which
     *                            is a third state and not a zero
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

    /** One order line, for the product cuts. */
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
            long netSom) {}

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
     * @param avgSecondsTotal null when no order closed on this day. A zero would
     *                        read as an instant order, which is the more damaging
     *                        wrong answer
     * @param refundedSom     refunds attributed to <em>this</em> date, from orders
     *                        of any date
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
            int newCustomers) {}

    /** One bucket of the fixed SLA distribution, for one location on one day. */
    public record SlaBucketAggregate(
            UUID tenantId,
            LocalDate businessDate,
            String scopeKind,
            UUID scopeId,
            int bucketSetVersion,
            String bucketCode,
            int orderCount,
            int shareBasisPoints) {}
}
