package uz.horecaos.platform.reviews.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * The four states of a service-recovery case: the IA's New, In progress, Resolved and Closed without a
 * remedy (ADR 0159, Decision 8).
 *
 * <p>The machine is code-owned for the reason the order state machine is: tenant-defined columns make
 * reporting and SLAs ungovernable. A terminal state is final -- a complaint that returns is a new
 * review or a new order, not a reopened case, because reopening needs a second SLA, a second owner
 * history and a rule for the compensation already given.
 */
public enum CaseState {
    NEW,
    IN_PROGRESS,
    RESOLVED,
    CLOSED_UNRESOLVED;

    public boolean terminal() {
        return this == RESOLVED || this == CLOSED_UNRESOLVED;
    }

    /**
     * The states a case may move to from this one.
     *
     * <p>{@code NEW -> IN_PROGRESS} is "start work" and the first response; {@code IN_PROGRESS -> NEW}
     * is release; {@code NEW -> CLOSED_UNRESOLVED} exists for {@link CaseOutcome#NOT_ACTIONABLE} only,
     * which dismisses a case without answering it.
     */
    public Set<CaseState> targets() {
        return switch (this) {
            case NEW -> EnumSet.of(IN_PROGRESS, CLOSED_UNRESOLVED);
            case IN_PROGRESS -> EnumSet.of(NEW, RESOLVED, CLOSED_UNRESOLVED);
            case RESOLVED, CLOSED_UNRESOLVED -> EnumSet.noneOf(CaseState.class);
        };
    }

    /** Whether {@code outcome} is a legal way to end a case in {@code to} coming from this state. */
    public boolean mayCloseWith(CaseState to, CaseOutcome outcome) {
        if (!targets().contains(to) || !to.terminal() || outcome.endsIn() != to) {
            return false;
        }
        // Dismissing from NEW is the one closure that skips In progress, and it carries one outcome.
        return this != NEW || outcome == CaseOutcome.NOT_ACTIONABLE;
    }
}
