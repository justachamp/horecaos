package uz.horecaos.platform.reviews.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * A case's two clocks and where they stand (ADR 0159, Decision 10).
 *
 * <p>Both clocks are wall-clock and start when the case opens: staff are not given the benefit of
 * business hours, which is stricter than they can meet and accepted over building a calendar into a
 * clock. A case is <em>at risk</em> in the last 20% of the window of a clock that is still running and
 * <em>breached</em> past it.
 *
 * <p>Everything here is a function of the case row and an instant -- nothing is stored, so the board
 * cannot disagree with the clock. A closed case is judged against when it was answered or closed, not
 * against now: a case resolved on time stays {@link SlaState#MET} however long ago that was.
 *
 * @param firstResponseAt when an owner first started work, or null while nobody has
 * @param closedAt        when the case reached a terminal state, or null while it is open
 */
public record CaseClocks(
        Instant openedAt,
        Instant firstResponseDueAt,
        Instant resolutionDueAt,
        @Nullable Instant firstResponseAt,
        @Nullable Instant closedAt) {

    /** The share of a window, at its end, in which an open case is "at risk". */
    private static final double AT_RISK_SHARE = 0.20;

    public CaseClocks {
        Objects.requireNonNull(openedAt, "The opening time is required");
        Objects.requireNonNull(firstResponseDueAt, "The first-response due time is required");
        Objects.requireNonNull(resolutionDueAt, "The resolution due time is required");
    }

    /** The first-response clock: stopped by the first start of work, or by the case closing unanswered. */
    public SlaState firstResponse(Instant now) {
        if (firstResponseAt != null) {
            return judged(firstResponseAt, firstResponseDueAt);
        }
        if (closedAt != null) {
            return judged(closedAt, firstResponseDueAt);
        }
        return running(now, firstResponseDueAt);
    }

    /** The resolution clock: stopped only by the case closing. */
    public SlaState resolution(Instant now) {
        if (closedAt != null) {
            return judged(closedAt, resolutionDueAt);
        }
        return running(now, resolutionDueAt);
    }

    /**
     * The worst of the two clocks, which is the state a board column shows: a case that has been
     * answered in time is judged by its resolution clock alone, one that has not is judged by the
     * worse of the two.
     */
    public SlaState overall(Instant now) {
        return worse(firstResponse(now), resolution(now));
    }

    /** Whether an open case's first response is due and unanswered, for the sweeper's own predicate. */
    public boolean firstResponseOverdue(Instant now) {
        return closedAt == null && firstResponseAt == null && now.isAfter(firstResponseDueAt);
    }

    /** Whether an open case's resolution is overdue, for the sweeper's own predicate. */
    public boolean resolutionOverdue(Instant now) {
        return closedAt == null && now.isAfter(resolutionDueAt);
    }

    private static SlaState judged(Instant stoppedAt, Instant dueAt) {
        return stoppedAt.isAfter(dueAt) ? SlaState.BREACHED : SlaState.MET;
    }

    private SlaState running(Instant now, Instant dueAt) {
        if (now.isAfter(dueAt)) {
            return SlaState.BREACHED;
        }
        long windowMillis = Duration.between(openedAt, dueAt).toMillis();
        Instant atRiskFrom = dueAt.minusMillis(Math.round(windowMillis * AT_RISK_SHARE));
        return now.isBefore(atRiskFrom) ? SlaState.ON_TRACK : SlaState.AT_RISK;
    }

    private static SlaState worse(SlaState left, SlaState right) {
        return severity(left) >= severity(right) ? left : right;
    }

    private static int severity(SlaState state) {
        return switch (state) {
            case BREACHED -> 3;
            case AT_RISK -> 2;
            case ON_TRACK -> 1;
            case MET -> 0;
        };
    }
}
