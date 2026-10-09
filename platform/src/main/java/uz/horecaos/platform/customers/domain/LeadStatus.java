package uz.horecaos.platform.customers.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a lead stands (ADR 0111 §4).
 *
 * <p>The bank reference this record adapts has six states and a lead→application→contract chain;
 * a restaurant has neither an application nor a contract, so the vocabulary is the six a call
 * centre actually uses:
 *
 * <pre>
 * NEW -> CONTACTED -> CALLBACK_SCHEDULED -> CONVERTED
 * NEW | CONTACTED | CALLBACK_SCHEDULED -> DECLINED | LOST
 * </pre>
 *
 * <p>The record's arrows are the edges that matter; three shortcuts an operator needs ride on them
 * and add no state. A guest who orders during the first call is {@code CONVERTED} without anyone
 * having to schedule a callback they will not make, so conversion is allowed from any open state:
 * the status records a fact that happened (an order or a reservation exists), and making the
 * operator log steps that did not happen first would only teach her to log false ones. A callback
 * can be scheduled from {@code NEW} for a guest who asked to be rung at six, and rescheduled while
 * it is still pending.
 *
 * <p>{@code CONVERTED}, {@code DECLINED} and {@code LOST} are terminal. A declined lead is never
 * handed to a different branch: the lead moves to {@code DECLINED} with a reason and the next
 * contact with that guest is a new lead.
 */
public enum LeadStatus {
    NEW,
    CONTACTED,
    CALLBACK_SCHEDULED,
    CONVERTED,
    DECLINED,
    LOST;

    private static final Set<LeadStatus> OPEN = EnumSet.of(NEW, CONTACTED, CALLBACK_SCHEDULED);

    /** Open means somebody still owes this guest something. */
    public boolean isOpen() {
        return OPEN.contains(this);
    }

    public boolean isTerminal() {
        return !isOpen();
    }

    /** Whether the machine has an edge from this state to {@code target}. */
    public boolean canMoveTo(LeadStatus target) {
        if (!isOpen()) {
            return false;
        }
        return switch (target) {
            case NEW -> false;
            // Back to "contacted" from a pending callback: the callback was made and the guest
            // still has not decided. Not from NEW to CONTACTED twice -- CONTACTED is not a loop.
            case CONTACTED -> this == NEW || this == CALLBACK_SCHEDULED;
            // From any open state, and from CALLBACK_SCHEDULED to itself: a reschedule.
            case CALLBACK_SCHEDULED -> true;
            case CONVERTED, DECLINED, LOST -> true;
        };
    }
}
