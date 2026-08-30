package uz.qoida.platform.kitchen.domain;

/**
 * When a ticket leaves the buffer and reaches a station (ADR 0041).
 *
 * <p>Kitchen release is a first-class instant precisely because it is neither the
 * confirmation nor the promise. Without it there is no buffer at all: a preorder
 * placed at 11:00 for 20:00 prints on the line at 11:00 and the food is thrown
 * away.
 */
public enum ReleaseMode {

    /** Fire as soon as the order is CONFIRMED. The ordinary case. */
    AUTO_ON_CONFIRM,

    /**
     * Fire at the ticket's {@code release_at}, computed backwards from the
     * promise. The scheduler owns it; Kafka is not the timer.
     */
    SCHEDULED,

    /** Fire only on an explicit release command from someone at the branch. */
    MANUAL_HOLD;

    /** Whether this mode requires an instant on the ticket. */
    public boolean requiresInstant() {
        return this == SCHEDULED;
    }
}
