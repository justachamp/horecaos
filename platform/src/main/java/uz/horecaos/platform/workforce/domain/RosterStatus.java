package uz.horecaos.platform.workforce.domain;

import java.util.Set;

/**
 * A roster entry's lifecycle (ADR 0156 Decision 3): a rota, not an offer. {@code DRAFT -> PUBLISHED
 * -> CONSUMED | MISSED | CANCELLED}, and {@code DRAFT -> CANCELLED}. There is no accepted or
 * declined: an employee is scheduled, and a person who cannot work tells the manager, who edits the
 * entry. {@code CONSUMED} is set when a shift opens against the entry; {@code MISSED} is set by a
 * sweeper after the planned end with no shift, and the console words it as a coverage fact and not a
 * disciplinary one.
 *
 * <p>{@code MISSED} is not the end of the line: hours a manager records after the sweeper ran are the
 * fact of that plan, so the shift opens against the entry and {@code MISSED -> CONSUMED} (a coverage
 * miss that stood beside the shift covering it would say the person both worked and did not).
 */
public enum RosterStatus {
    DRAFT,
    PUBLISHED,
    CONSUMED,
    MISSED,
    CANCELLED;

    /** The statuses the overlap exclusion constraint treats as live. */
    public static final Set<RosterStatus> LIVE = Set.of(DRAFT, PUBLISHED, CONSUMED, MISSED);

    public boolean canMoveTo(RosterStatus next) {
        return switch (this) {
            case DRAFT -> next == PUBLISHED || next == CANCELLED;
            case PUBLISHED -> next == CONSUMED || next == MISSED || next == CANCELLED;
            case MISSED -> next == CONSUMED;
            case CONSUMED, CANCELLED -> false;
        };
    }
}
