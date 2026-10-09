package uz.horecaos.platform.fulfillment.api;

import java.util.List;
import java.util.UUID;

/**
 * Whether a person may hand a courier an order (ADR 0172 Decision 5).
 *
 * <p>Fulfillment owns the port and the {@code courier} module implements it over
 * {@code CourierDispatchGate} and the courier type, the direction
 * {@link InternalFleetPort} already runs: fulfillment must never learn to read
 * shifts or engagements, because the moment it can "is this courier
 * dispatchable" acquires a second answer that drifts from the first.
 *
 * <p>The answer is the gate's own, with one difference that is the point of a
 * <em>person's</em> choice: {@code OUTSIDE_DISTANCE_BAND} is a ranking input for
 * the automated lane, and a dispatcher who sends a scooter across town has
 * decided to. It comes back as a note and never as a refusal.
 */
public interface CourierAssessmentPort {

    Assessment assess(UUID tenantId, UUID brandId, UUID locationId, UUID courierId, int distanceMeters);

    /**
     * @param eligible          whether the courier may be handed this order
     * @param refusals          the gate's named reasons when not eligible, empty otherwise
     * @param notes             non-fatal observations on an eligible courier
     *                          ({@code OUTSIDE_DISTANCE_BAND}, {@code NO_OPEN_SHIFT} where
     *                          the branch does not enforce shifts)
     * @param concurrencyCeiling the courier type's {@code max_concurrent_assignments};
     *                          0 when the courier is unknown
     */
    record Assessment(boolean eligible, List<String> refusals, List<String> notes, int concurrencyCeiling) {

        public Assessment {
            refusals = List.copyOf(refusals);
            notes = List.copyOf(notes);
        }
    }
}
