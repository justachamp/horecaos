package uz.qoida.platform.tenancy.api;

/**
 * Why a location cannot be ordered from right now (ADR 0036).
 *
 * <p>Stable codes, in the order the resolver evaluates them, so the reason
 * returned is the most fundamental one rather than whichever check happened to
 * run last. "This channel does not sell here" and "we are shut" are different
 * answers and lead to different fixes; returning the second when the first is
 * true sends an operator to the opening hours screen for an hour.
 *
 * <p>The storefront maps these to customer wording and never renders the code.
 */
public enum ServiceabilityReason {

    /** Rule 1 and 2: the channel is not active, or is not enabled at this location. */
    CHANNEL_NOT_ENABLED,

    /** Rule 3: the channel does not carry this fulfilment mode. */
    FULFILMENT_MODE_UNAVAILABLE,

    /** Rule 4: a manager closed the branch and it has not reopened. */
    MANUALLY_CLOSED,

    /** Rule 5: a dated exception closes the branch today. */
    CLOSED_BY_EXCEPTION,

    /** Rule 6: outside every weekly window bound to this fulfilment mode. */
    OUTSIDE_SERVICE_HOURS,

    /** Rule 7: the brand has no live publication on this channel. */
    NO_LIVE_MENU,

    /** Rule 8: the kitchen is at its concurrent-order ceiling. */
    AT_CAPACITY
}
