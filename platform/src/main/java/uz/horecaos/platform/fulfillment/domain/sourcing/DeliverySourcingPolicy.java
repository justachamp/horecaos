package uz.horecaos.platform.fulfillment.domain.sourcing;

/**
 * The ADR 0030 policy document behind ADR 0014's sourcing timings.
 *
 * <p>One document rather than seven keys because every number here is decided by
 * the same conversation between operations and the branch: how long the kitchen
 * takes, how long a courier takes to reach it, and how long a fleet is given
 * before the platform pays a partner instead. A lead time changed without the
 * buffer that goes with it is a mistake made by editing one of a pair.
 *
 * <p>All durations are seconds rather than {@code Duration}, because this
 * document is stored as JSON in the ADR 0030 policy table and read back by
 * operators who need to see a number they can reason about.
 *
 * @param preparationLeadSeconds  how long before the food is ready an in-house
 *                                courier is expected to need to reach the
 *                                branch. Sourcing starts this far ahead of the
 *                                pickup window plus the safety buffer
 * @param partnerLeadSeconds      the same figure for an external partner, and
 *                                deliberately larger: a partner has to find and
 *                                dispatch a courier who is not already ours
 * @param safetyBufferSeconds     added to whichever lead applies, so that
 *                                sourcing that starts a minute late still lands
 *                                inside the window
 * @param pickupToleranceSeconds  how long after the food is ready a courier may
 *                                still arrive without the order suffering. This
 *                                is the width of the pickup window, and it is
 *                                the whole slack the fallback decision spends
 * @param offerRounds             how many consecutive in-house offers are made
 *                                before the fleet lane is conceded. One is a
 *                                single courier's chance to miss a notification;
 *                                two gives the order to somebody else before it
 *                                goes outside
 * @param maxOfferSeconds         a ceiling on the courier type's own
 *                                {@code offer_ttl_seconds}, so a type configured
 *                                with fifteen minutes cannot hold an order
 *                                hostage during a dinner rush
 * @param latestAssignmentSlackSeconds how far past the pickup window an
 *                                assignment may still be attempted before the
 *                                order becomes an operations exception rather
 *                                than a sourcing problem
 */
public record DeliverySourcingPolicy(
        int preparationLeadSeconds,
        int partnerLeadSeconds,
        int safetyBufferSeconds,
        int pickupToleranceSeconds,
        int offerRounds,
        int maxOfferSeconds,
        int latestAssignmentSlackSeconds) {

    /**
     * Provisional values, in force until operations answers ADR 0014's
     * "approve preparation estimate, pickup window, promise, scoring and subsidy
     * policies" checklist item.
     *
     * <p>They are chosen as a set, and the arithmetic between them is the point.
     * Sourcing starts fifteen minutes before the food is ready (a ten-minute
     * in-house lead plus a five-minute buffer). A partner needs fifteen minutes,
     * so with a fifteen-minute pickup window the last safe handover is at the
     * ready time itself — leaving the fleet the fifteen minutes between. Two
     * offer rounds of at most ninety seconds spend three of those and leave
     * twelve as margin, which is why the rounds and not the clock normally end
     * the in-house lane. Change one of these numbers without the others and that
     * margin is the thing that disappears.
     */
    public static final DeliverySourcingPolicy DEFAULTS =
            new DeliverySourcingPolicy(600, 900, 300, 900, 2, 90, 900);

    public DeliverySourcingPolicy {
        if (preparationLeadSeconds < 0 || partnerLeadSeconds < 0 || safetyBufferSeconds < 0
                || latestAssignmentSlackSeconds < 0) {
            throw new IllegalArgumentException("Sourcing lead times cannot be negative");
        }
        if (pickupToleranceSeconds < 1) {
            throw new IllegalArgumentException(
                    "A pickup window of zero width is a single instant no courier can hit");
        }
        if (offerRounds < 1) {
            // Zero rounds is not "prefer partners", it is "never use the fleet",
            // and a tenant meaning that turns the fleet off rather than setting a
            // timing to a value that quietly disables a whole sourcing lane.
            throw new IllegalArgumentException(
                    "At least one in-house offer round is required; disable the fleet instead");
        }
        if (maxOfferSeconds < 15) {
            throw new IllegalArgumentException(
                    "An offer shorter than fifteen seconds expires before a phone finishes ringing");
        }
    }
}
