package uz.horecaos.platform.fulfillment.domain.sourcing;

/**
 * Where a plan's <em>decision</em> has got to (ADR 0014 "Lifecycles").
 *
 * <p>Deliberately not the shipment's states. A plan is ASSIGNED when somebody has
 * agreed to carry the order; the bag has not moved and the shipment is still
 * PENDING or ASSIGNED on its own timeline. Collapsing the two is how "assigned"
 * comes to mean two different things on one screen.
 *
 * <p>The names are the strings in {@code ck_plan_status} and are written to the
 * column verbatim, so a value added here without the migration is refused by the
 * database rather than stored and later unreadable.
 */
public enum PlanStatus {
    PLANNED,
    WAITING_TO_SOURCE,
    SOURCING,
    BOOKING,
    RETRY_PENDING,
    SCHEDULED,
    ASSIGNED,
    IN_PROGRESS,
    COMPLETED,
    MANUAL_ACTION_REQUIRED,
    CANCELLED;

    /** Whether automated sourcing has finished with this plan, one way or another. */
    public boolean settled() {
        return this == ASSIGNED
                || this == IN_PROGRESS
                || this == COMPLETED
                || this == MANUAL_ACTION_REQUIRED
                || this == CANCELLED;
    }
}
