package uz.horecaos.platform.courier.domain;

/**
 * The lifecycle of one {@code fulfillment.courier_roster_entries} row (ADR
 * 0042): {@code DRAFT -> PUBLISHED -> (ACCEPTED | DECLINED) -> (CONSUMED |
 * MISSED | CANCELLED)}.
 *
 * <p>{@link #ACCEPTED}, {@link #DECLINED}, {@link #CONSUMED} and {@link
 * #MISSED} are reachable states in the schema that nothing in this wave's
 * code writes — they are the courier's own answer and the eventual outcome,
 * which belong to the courier-facing surface ADR 0042 describes and this
 * wave does not build. A row that never leaves {@link #PUBLISHED} is not a
 * bug; it is a roster nobody has answered yet.
 */
public enum PlannedShiftStatus {
    DRAFT,
    PUBLISHED,
    ACCEPTED,
    DECLINED,
    CONSUMED,
    MISSED,
    CANCELLED;

    /** Whether a manager may still cancel the entry outright. */
    public boolean cancellable() {
        return this == DRAFT || this == PUBLISHED;
    }
}
