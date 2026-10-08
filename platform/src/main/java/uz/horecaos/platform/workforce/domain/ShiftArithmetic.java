package uz.horecaos.platform.workforce.domain;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The three-line arithmetic of a staff shift (ADR 0156, Lifecycle and arithmetic), stated here and
 * pinned by {@code shift-arithmetic-vectors.csv}, which the courier-shift tests read too. No
 * shared-kernel class is introduced: ADR 0042's courier shifts and these are two records that
 * happen to agree on three lines.
 *
 * <pre>
 * worked_seconds   = ended_at - started_at
 * paid_seconds     = worked_seconds - break_seconds
 * planned_seconds  = (planned_end - planned_start) - planned_break      (null if no entry)
 * variance_seconds = paid_seconds - planned_seconds                     (null if no plan)
 * </pre>
 *
 * <p>Null is not zero: a shift with no roster entry has no plan and no variance, and reads
 * «без плана», not «0 минут отклонения». Pure and clock-free, so the same inputs always give the
 * same hours; a shift's time comes from a request or the server's clock before it reaches here.
 */
public final class ShiftArithmetic {

    /**
     * A shift or a roster entry longer than this is a mistyped date, not a working day. Not a legal
     * limit: the platform enforces no working-time rule (ADR 0156, first open input), and the same
     * bound is the schema's data-sanity check.
     */
    public static final Duration MAXIMUM_SPAN = Duration.ofHours(16);

    private ShiftArithmetic() {}

    public static long workedSeconds(Instant startedAt, Instant endedAt) {
        return Duration.between(startedAt, endedAt).toSeconds();
    }

    /**
     * @throws IllegalArgumentException when the break is longer than the shift, which the schema
     *     also refuses: unlike a courier's clamp, an employee's break cannot exceed their shift
     */
    public static long paidSeconds(long workedSeconds, long breakSeconds) {
        if (breakSeconds < 0 || breakSeconds > workedSeconds) {
            throw new IllegalArgumentException("A break is between zero and the shift's own length");
        }
        return workedSeconds - breakSeconds;
    }

    public static long plannedSeconds(Instant plannedStart, Instant plannedEnd, int plannedBreakMinutes) {
        return Duration.between(plannedStart, plannedEnd).toSeconds() - plannedBreakMinutes * 60L;
    }

    public static @Nullable Long varianceSeconds(long paidSeconds, @Nullable Long plannedSeconds) {
        return plannedSeconds == null ? null : paidSeconds - plannedSeconds;
    }

    /** Whether a shift's absolute variance is over the threshold and so waits for a person to look. */
    public static boolean needsApproval(@Nullable Long varianceSeconds, int thresholdMinutes) {
        return varianceSeconds != null && Math.abs(varianceSeconds) > thresholdMinutes * 60L;
    }

    /** Whether {@code started..ended} is a span the schema accepts. */
    public static boolean isSaneSpan(Instant start, Instant end) {
        Duration span = Duration.between(start, end);
        return span.isPositive() && span.compareTo(MAXIMUM_SPAN) <= 0;
    }
}
