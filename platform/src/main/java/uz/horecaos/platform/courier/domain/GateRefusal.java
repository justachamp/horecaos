package uz.horecaos.platform.courier.domain;

/**
 * Why the courier app's delivery endpoints refused a courier (ADR 0042, couriers.md §16).
 *
 * <p>Each name is the {@code reason} extension of a {@code 422 UNPROCESSABLE_STATE}
 * problem. A client branches on it and never on the sentence: the handset shows the
 * courier a message in their own language chosen by this code, and a dispatcher
 * answering "why can't Alisher take this order" reads the same code in the access log
 * instead of a translation. Renaming one is a breaking change, so the set is closed and
 * every member is pinned by a test that sends the request that produces it.
 *
 * <p>Every one of these refuses a request that is well formed and names a real thing the
 * courier holds. None of them is an authorization failure -- the courier is who they say
 * they are and the offer or delivery is theirs -- which is why they are not
 * {@code 403}: what is wrong is the courier's circumstances (where they stand, what the
 * kitchen has done, whether the cash was counted), and the remedy is to change those.
 */
public enum GateRefusal {

    /** The tenant requires the courier's position and the request carried none. */
    GPS_POSITION_REQUIRED,

    /**
     * The fix is too coarse to prove anything. A reading with a 900 m error circle cannot show a
     * courier is within 150 m of a door, and accepting it would turn the radius into a number
     * that only ever measured how wide the circle was.
     */
    GPS_ACCURACY_INSUFFICIENT,

    /**
     * The place the courier is to be measured against has no coordinate (an unplaced branch).
     * Refused rather than waved through: a gate that opens whenever it cannot measure is a gate
     * that a missing address switches off.
     */
    GPS_REFERENCE_UNAVAILABLE,

    /** The courier is further from the branch than the radius for this step allows. */
    TOO_FAR_FROM_PICKUP,

    /** The courier is further from the customer's door than the radius allows. */
    TOO_FAR_FROM_DROPOFF,

    /** The tenant lets couriers take only orders the kitchen has finished, and this one is not. */
    KITCHEN_NOT_READY,

    /** The tenant requires the courier to confirm the cash first, and they have not. */
    PAYMENT_CONFIRMATION_REQUIRED,

    /** The figure the courier stated is not the figure the settlement says is due. */
    PAYMENT_AMOUNT_MISMATCH,

    /** The tenant reveals the customer's door only after the courier has accepted. */
    LOCATION_NOT_YET_REVEALED,

    /** The courier's engagement does not allow new work (lapsed registration, suspension). */
    COURIER_NOT_ELIGIBLE,

    /** The delivery cannot take the requested step from where it stands. */
    STEP_NOT_ALLOWED,

    /** The delivery is not in a state a payment can still be confirmed on. */
    PAYMENT_NOT_CONFIRMABLE
}
