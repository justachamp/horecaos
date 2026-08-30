package uz.qoida.platform.courier.domain;

import java.time.Duration;
import java.time.Instant;

/**
 * The on-time outcome, computed once at delivery from values snapshotted at
 * acceptance (ADR 0042).
 *
 * <p>No report recomputes this. A report that recomputes an outcome is a report
 * that can disagree with the statement a courier was paid against, and the
 * disagreement always surfaces as an argument nobody can settle.
 */
public final class OnTimeEvaluator {

    private OnTimeEvaluator() {
    }

    /**
     * @param promisedDeliveryEnd the ADR 0014 plan's promise as it stood when the
     *                            courier accepted; null when the plan recorded none
     * @param kitchenHandoverAt   when the branch actually handed the bag over
     * @param pickupWindowEnd     when the plan said it would be ready by
     */
    public static OnTimeOutcome evaluate(
            Instant deliveredAt,
            Instant promisedDeliveryEnd,
            int graceSeconds,
            Instant kitchenHandoverAt,
            Instant pickupWindowEnd) {

        if (promisedDeliveryEnd == null) {
            return OnTimeOutcome.UNKNOWN;
        }
        Instant deadline = promisedDeliveryEnd.plus(Duration.ofSeconds(graceSeconds));
        if (!deliveredAt.isAfter(deadline)) {
            return OnTimeOutcome.ON_TIME;
        }
        if (kitchenHandoverAt != null && pickupWindowEnd != null
                && kitchenHandoverAt.isAfter(pickupWindowEnd)) {
            return OnTimeOutcome.LATE_EXCUSED;
        }
        return OnTimeOutcome.LATE;
    }
}
