package uz.horecaos.platform.fulfillment.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The one thing sourcing asks of ADR 0042 before it offers anybody an order
 * (ADR 0014).
 *
 * <p>ADR 0014's alternatives table refuses to treat internal couriers as another
 * provider adapter, and that refusal is what this interface implements: the
 * in-house lane is a workforce with shifts, an engagement and a registration
 * that expires, all of which ADR 0042 owns. Sourcing knows only who may be
 * offered this order and how loaded they already are.
 *
 * <p>Deliberately answers candidates and not an assignment. The single-winner
 * rule ADR 0014 states — one plan, one active winner, enforced by a
 * compare-and-set rather than by convention — needs the
 * {@code fulfillment.assignment_attempts} row that does not exist in any
 * migration. An offer method here would have to invent a second place to hold
 * that invariant, and two places holding one invariant is how a plan ends up
 * with two couriers.
 *
 * <p>{@code courier.infrastructure.dispatch.InternalFleetAdapter} implements it,
 * because ADR 0042 owns the shift, the engagement and the courier type this
 * answer is made of. {@code UnwiredInternalFleetPort} still stands behind
 * {@code @ConditionalOnMissingBean} for a deployment that runs no in-house
 * fleet, and it answers no candidates: an empty fleet costs a partner
 * commission, whereas a stand-in that invented a courier would offer paid work
 * to somebody whose self-employment registration nobody checked.
 */
public interface InternalFleetPort {

    /**
     * The couriers ADR 0042's dispatch gate would allow this order to be offered
     * to, in no particular order — sourcing does the ranking, because which
     * courier is <em>best</em> is ADR 0014's versioned decision and not ADR
     * 0042's eligibility one.
     *
     * @param distanceMeters branch to destination, so a courier whose vehicle
     *                       class puts this order outside their distance band is
     *                       filtered out here rather than offered and declined
     */
    List<FleetCandidate> candidates(UUID tenantId, UUID brandId, UUID locationId, int distanceMeters);

    /**
     * One courier who could take this order now.
     *
     * <p>Carries no name. ADR 0029 keeps a courier's name inside envelope
     * encryption and {@code display_reference} is the handle a dispatch board,
     * an event and a log line may carry; sourcing needs neither, so it gets
     * neither.
     *
     * @param offerTtlSeconds  the courier type's {@code offer_ttl_seconds}. Held
     *                         per candidate rather than per policy because it
     *                         belongs to the vehicle class: a courier on foot
     *                         reading a phone in his pocket needs longer to
     *                         answer than one on a scooter at the counter
     * @param activeAssignments how many orders this courier is already carrying.
     *                         Ranking prefers the emptiest hands, which is both
     *                         the fastest pickup and the fairest spread
     * @param concurrencyCeiling the type's {@code max_concurrent_assignments}
     * @param metresFromBranch  straight-line distance from the courier's last
     *                          known position to the branch, or null when ADR
     *                          0045 has no fresh position. Null ranks last
     *                          rather than nearest: an unknown position is not
     *                          evidence of being close
     */
    record FleetCandidate(
            UUID courierId,
            int offerTtlSeconds,
            int activeAssignments,
            int concurrencyCeiling,
            @Nullable Integer metresFromBranch,
            int deliveriesThisShift) {

        public boolean hasCapacity() {
            return activeAssignments < concurrencyCeiling;
        }
    }

    /**
     * How loaded each of these couriers already is, keyed by courier id and
     * absent for a courier carrying nothing.
     *
     * <p>Declared here, beside the port that needs it, and implemented by
     * fulfillment rather than by whoever implements {@link InternalFleetPort}.
     * V0040 says why in as many words: ADR 0014 "owns the attempt, including the
     * conditional-update ceiling on concurrent assignments", and a courier module
     * counting {@code fulfillment.shipments} for itself would be a second place
     * that invariant lives. Two places holding one invariant is how a courier
     * ends up carrying one more order than his vehicle class allows.
     *
     * <p>An order is being carried while its shipment exists and is neither
     * {@code DELIVERED} nor {@code CANCELLED} — which is exactly the predicate
     * of V0054's {@code ix_shipment_courier_open}, an index created for this
     * question and until now asked by nobody.
     */
    interface ActiveAssignments {

        Map<UUID, Integer> byCourier(UUID tenantId, Collection<UUID> courierIds);
    }

    /** Whether a real implementation is present. */
    default boolean isWired() {
        return true;
    }

    /** The reason a sourcing decision carries when it fell back with no fleet behind it. */
    String NOT_WIRED_REASON = "INTERNAL_FLEET_NOT_WIRED";
}
