package uz.horecaos.platform.courier.domain;

import java.util.Optional;
import java.util.OptionalInt;
import org.jspecify.annotations.Nullable;

/**
 * The four courier-policy switches that need an enforcement point, as pure functions
 * (ADR 0042, couriers.md §16, gap map row 3.9).
 *
 * <p>No I/O and no clock: the caller measures -- how far the courier stands from a place,
 * whether the kitchen has finished, what the settlement says is due -- and this decides
 * whether the policy lets the courier proceed. Kept apart from the service that does the
 * measuring so a rule can be read, and tested exhaustively, without a database, and so the
 * service cannot quietly decide a switch means something other than its document says.
 *
 * <p><strong>The GPS toggle is the master switch.</strong> {@link CourierCompensationPolicy#gpsVerificationEnabled()}
 * false means nothing is measured at all -- not even decrypting the customer's door to
 * compute a distance nobody will use -- which is why {@link #gps} is consulted before the
 * caller asks for a distance, and why the caller must pass an empty distance rather than a
 * stale one when the toggle is off.
 */
public final class DeliveryGate {

    private DeliveryGate() {}

    /** Where in a delivery's life the courier is being asked to prove they are in the right place. */
    public enum Stage {

        /** Taking an offer: measured against the branch, within the accept radius. */
        ACCEPT_OFFER,

        /** Arriving at, or collecting from, the branch: within the status-change radius of it. */
        AT_PICKUP,

        /** Handing over at the customer's door: within the status-change radius of it. */
        AT_DROPOFF
    }

    /** Whether the stage needs the courier measured against the branch rather than the door. */
    public static boolean measuresAgainstBranch(Stage stage) {
        return stage != Stage.AT_DROPOFF;
    }

    /**
     * Whether the policy needs a position at all. Callers use this to avoid reading anything
     * (a distance, a decrypted door) when the answer cannot matter.
     */
    public static boolean needsPosition(CourierCompensationPolicy policy) {
        return policy.gpsVerificationEnabled();
    }

    /** The radius, in metres, this stage is held to. */
    public static int radiusMeters(CourierCompensationPolicy policy, Stage stage) {
        return stage == Stage.ACCEPT_OFFER ? policy.gpsAcceptRadiusMeters() : policy.gpsStatusChangeRadiusMeters();
    }

    /**
     * @param policy         the policy in force at the delivery's own location
     * @param stage          what the courier is trying to do
     * @param positionGiven  whether the request carried a position
     * @param accuracyMeters the reported error circle of that position; ignored when no position
     * @param distance       metres from the place this stage measures against, or empty when it
     *                       could not be measured (no position, or the place has no coordinate)
     * @return the refusal, or empty when the courier may proceed
     */
    public static Optional<GateRefusal> gps(
            CourierCompensationPolicy policy,
            Stage stage,
            boolean positionGiven,
            @Nullable Double accuracyMeters,
            OptionalInt distance) {

        if (!policy.gpsVerificationEnabled()) {
            return Optional.empty();
        }
        if (!positionGiven) {
            return Optional.of(GateRefusal.GPS_POSITION_REQUIRED);
        }
        int radius = radiusMeters(policy, stage);
        if (accuracyMeters == null || !Double.isFinite(accuracyMeters) || accuracyMeters > radius) {
            return Optional.of(GateRefusal.GPS_ACCURACY_INSUFFICIENT);
        }
        if (distance.isEmpty()) {
            return Optional.of(GateRefusal.GPS_REFERENCE_UNAVAILABLE);
        }
        if (distance.getAsInt() > radius) {
            return Optional.of(
                    measuresAgainstBranch(stage) ? GateRefusal.TOO_FAR_FROM_PICKUP : GateRefusal.TOO_FAR_FROM_DROPOFF);
        }
        return Optional.empty();
    }

    /** Whether a courier may take (and, in a list, even see) an order given where the kitchen is with it. */
    public static Optional<GateRefusal> kitchen(CourierCompensationPolicy policy, boolean kitchenReady) {
        return policy.kitchenReadyOnly() && !kitchenReady
                ? Optional.of(GateRefusal.KITCHEN_NOT_READY)
                : Optional.empty();
    }

    /** Whether the customer's door may be shown to a courier who holds only an offer. */
    public static Optional<GateRefusal> revealBeforeAccept(CourierCompensationPolicy policy) {
        return policy.revealCustomerLocationTiming() == RevealTiming.BEFORE_ACCEPT
                ? Optional.empty()
                : Optional.of(GateRefusal.LOCATION_NOT_YET_REVEALED);
    }

    /**
     * Whether the delivery may be marked delivered given the cash step.
     *
     * <p>A step only exists when cash is due: an order the platform already holds the money
     * for has nothing for a courier to count, and demanding a "confirmation of zero" would be
     * a tap that protects nothing.
     *
     * @param cashDueMinor what the settlement says is still to be collected at the door
     * @param confirmed    the courier has recorded their payment confirmation
     */
    public static Optional<GateRefusal> paymentCheck(
            CourierCompensationPolicy policy, long cashDueMinor, boolean confirmed) {
        return policy.postDeliveryPaymentCheckRequired() && cashDueMinor > 0 && !confirmed
                ? Optional.of(GateRefusal.PAYMENT_CONFIRMATION_REQUIRED)
                : Optional.empty();
    }

    /** Whether the cash step is demanded of the courier on this delivery under this policy. */
    public static boolean paymentConfirmationRequired(CourierCompensationPolicy policy, long cashDueMinor) {
        return policy.postDeliveryPaymentCheckRequired() && cashDueMinor > 0;
    }
}
