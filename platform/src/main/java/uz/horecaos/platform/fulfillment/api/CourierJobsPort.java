package uz.horecaos.platform.fulfillment.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort.CustomerLocation;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * What an in-house courier is offered, and what they are carrying (ADR 0014, ADR 0042).
 *
 * <p>The courier-app half of ADR 0014's attempt and shipment. Sourcing creates an
 * {@code OFFERED} attempt for a courier the fleet gate allowed; until this port nothing
 * could answer it, so the offer lapsed unanswered and a shipment moved only when an operator
 * assigned it by hand or completed the order. {@code courier} is the caller: it owns the
 * policy those answers are held to (the GPS toggle and its two radii, kitchen-ready-only, when
 * the customer's door is revealed, the post-delivery payment check), and fulfilment owns the
 * rows. The two must never read each other's tables, so the question each asks of the other
 * is this interface — declared here for the reason {@link DeliveryCompletionPort} gives:
 * {@code courier} already depends on {@code fulfillment.api}, and the reverse edge would close
 * a module cycle.
 *
 * <p><strong>Every method names the courier and filters on them in the statement.</strong>
 * A shipment or an offer id is a UUID the caller supplies and proves nothing about ownership;
 * the courier a request is about is always the caller's own, resolved from their token by the
 * controller, and an id belonging to anybody else answers exactly as an id that does not
 * exist. That is also what keeps the surface from being an oracle for which offers other
 * couriers hold.
 *
 * <p><strong>No coordinate crosses this interface towards the caller.</strong> A courier's
 * position goes in as a {@link GeoPoint} and comes back as a number of metres from a fixed
 * point, the same reduction ADR 0045's {@code CourierProximityPort} makes. The customer's door
 * leaves only through {@link #customerLocationOfOffer} and {@link #customerLocationOfJob},
 * each of which checks that the caller holds that offer or that shipment before it decrypts
 * anything.
 *
 * <p>Nothing here decides a policy. Whether a courier is far enough from the branch to be
 * refused is {@code courier}'s question; this answers how far they are.
 */
public interface CourierJobsPort {

    /**
     * The offers this courier holds that they could still accept: {@code OFFERED}, not lapsed,
     * for an order that is still being cooked or carried and a plan sourcing has not settled.
     * Soonest to lapse first.
     */
    List<Offer> openOffers(UUID tenantId, UUID courierId, Instant now);

    /** One such offer, or empty when it is not this courier's, has lapsed, or has been closed. */
    Optional<Offer> offer(UUID tenantId, UUID courierId, UUID offerId, Instant now);

    /**
     * Takes the offer. The whole decision is one compare-and-set in {@code ux_attempt_one_accepted}'s
     * shadow: a false answer is the ordinary outcome of the second of two taps and of an offer that
     * lapsed a second earlier.
     *
     * @return the shipment this courier now carries, or empty when the offer is not theirs to take
     *         any more
     */
    Optional<UUID> accept(UUID tenantId, UUID courierId, UUID offerId, Instant now);

    /**
     * Turns the offer down, and wakes sourcing for the plan so the next courier is asked now
     * rather than when this offer would have lapsed.
     *
     * @return false when the offer is not this courier's or is no longer open
     */
    boolean decline(UUID tenantId, UUID courierId, UUID offerId, Instant now);

    /** Everything this courier is carrying: assigned, waiting at the pickup, or on the road. */
    List<Job> jobs(UUID tenantId, UUID courierId);

    /** One shipment of this courier's, in any status, or empty when it is not theirs. */
    Optional<Job> job(UUID tenantId, UUID courierId, UUID shipmentId);

    /**
     * Moves the shipment one step along {@code ASSIGNED -> PICKUP_PENDING -> PICKED_UP ->
     * DELIVERED}, conditioned on where it is now.
     *
     * <p>A step the shipment already stands on is {@link Result#ALREADY_THERE}, not an error:
     * a handset replaying a tap it was never told succeeded must get the same answer twice.
     * A step the shipment cannot take from where it is is {@link Result#NOT_ALLOWED} and
     * changes nothing.
     */
    Transition advance(UUID tenantId, UUID courierId, UUID shipmentId, Step to, Instant now);

    /**
     * Records the courier's payment confirmation on a shipment they are carrying. Idempotent in
     * the amount: confirming the same figure twice leaves the first moment standing.
     *
     * @return the shipment as it now stands, or empty when it is not this courier's or is not
     *         one a payment can still be confirmed on (anything other than {@code PICKED_UP})
     */
    Optional<Job> confirmPayment(
            UUID tenantId, UUID courierId, UUID shipmentId, long collectedMinor, String currency, Instant now);

    /**
     * How far {@code position} is from the branch the plan collects from, in metres. A branch is
     * advertised by the merchant, so the number is a fact about a published place and not about a
     * person.
     *
     * @return empty when the branch has no coordinate -- an unplaced branch is not at the origin
     */
    OptionalInt metresFromBranch(UUID tenantId, UUID brandId, UUID locationId, GeoPoint position);

    /**
     * How far {@code position} is from the door of the order this shipment carries.
     *
     * <p>Decrypts the destination for the length of the comparison and returns only the distance;
     * the courier is not told where the door is by being told how far from it they stand.
     *
     * @return empty when the shipment is not this courier's, or the order holds no address
     */
    OptionalInt metresFromDoor(UUID tenantId, UUID courierId, UUID shipmentId, GeoPoint position);

    /**
     * The customer's door for an offer the courier holds and has not yet accepted. Whether they
     * may see it before accepting is {@code courier}'s policy; this only refuses an offer that is
     * not theirs or is no longer open.
     */
    Optional<CustomerLocation> customerLocationOfOffer(
            UUID tenantId, UUID courierId, UUID offerId, Instant now, String purpose);

    /**
     * The customer's door for a shipment the courier carries. Refused once the shipment has been
     * delivered or cancelled: a courier who has finished with an order has no further business
     * knowing where its customer lives.
     */
    Optional<CustomerLocation> customerLocationOfJob(
            UUID tenantId, UUID courierId, UUID shipmentId, String purpose);

    /** The steps a courier takes a shipment through. */
    enum Step {
        PICKUP_PENDING,
        PICKED_UP,
        DELIVERED
    }

    /** Where a shipment is, in the courier's vocabulary. */
    enum JobStatus {
        ASSIGNED,
        PICKUP_PENDING,
        PICKED_UP,
        DELIVERED,
        CANCELLED
    }

    /** What {@link #advance} did. */
    enum Result {
        APPLIED,
        ALREADY_THERE,
        NOT_ALLOWED,
        NOT_FOUND
    }

    /**
     * @param job the shipment as it stands after the call, or as it was found; null only for
     *            {@link Result#NOT_FOUND}
     */
    record Transition(Result result, @Nullable Job job) {}

    /** The branch end of a delivery. Published by the merchant, so in clear. */
    record Pickup(
            String name,
            String addressLine,
            @Nullable String landmark,
            @Nullable Double latitude,
            @Nullable Double longitude) {}

    /**
     * One offer a courier could take.
     *
     * <p>Carries no name, no telephone and no address of the customer: the destination is the
     * non-PII label ordering projected for the dispatch board, and the door itself is a separate,
     * policy-gated, audited read.
     *
     * @param offerId       the {@code assignment_attempts} row
     * @param kitchenReady  the order is {@code READY} or later. The courier policy's
     *                      kitchen-ready-only switch reads this
     * @param prepaid       HorecaOS already holds the money, so nothing is collected at the door
     */
    record Offer(
            UUID offerId,
            int version,
            UUID planId,
            UUID orderId,
            UUID brandId,
            UUID locationId,
            String orderReference,
            Instant offeredAt,
            Instant expiresAt,
            String currency,
            long orderTotalMinor,
            boolean prepaid,
            boolean kitchenReady,
            @Nullable String destinationLabel,
            @Nullable Integer distanceMeters,
            Instant pickupWindowStart,
            Instant pickupWindowEnd,
            @Nullable Instant promisedDeliveryEnd,
            Pickup pickup) {}

    /**
     * One shipment a courier carries (or carried).
     *
     * @param paymentConfirmedMinor the cash the courier stated they collected, or null before they
     *                              confirmed the payment step
     */
    record Job(
            UUID shipmentId,
            int version,
            JobStatus status,
            UUID planId,
            UUID orderId,
            UUID brandId,
            UUID locationId,
            String orderReference,
            Instant assignedAt,
            @Nullable Instant pickedUpAt,
            @Nullable Instant deliveredAt,
            @Nullable Instant paymentConfirmedAt,
            @Nullable Long paymentConfirmedMinor,
            String currency,
            long orderTotalMinor,
            boolean prepaid,
            boolean kitchenReady,
            @Nullable String destinationLabel,
            @Nullable Integer distanceMeters,
            Instant pickupWindowStart,
            Instant pickupWindowEnd,
            @Nullable Instant promisedDeliveryEnd,
            Pickup pickup) {}

    /** Whether a real implementation is present. */
    default boolean isWired() {
        return true;
    }
}
