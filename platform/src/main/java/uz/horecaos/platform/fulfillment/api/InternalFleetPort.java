package uz.horecaos.platform.fulfillment.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.audit.api.ActorRef;

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
     * The same candidates, each measured against the drop-off of the plan being sourced (ADR 0170 Decision 8).
     *
     * <p>Asked only when the resolved dispatch action has a {@code grouping}; otherwise nothing is read and
     * the overload above is the one called. The probe names the plan, the merge radius and the run's order
     * ceiling, and the answer is one number per candidate that qualifies, never an address or a coordinate
     * (ADR 0029). An implementation that cannot measure answers the plain candidates, which is what the
     * default does: grouping is a ranking bias and never a reason to ask nobody.
     */
    default List<FleetCandidate> candidates(
            UUID tenantId, UUID brandId, UUID locationId, int distanceMeters, @Nullable GroupingProbe probe) {
        return candidates(tenantId, brandId, locationId, distanceMeters);
    }

    /**
     * What a grouping rule needs measured (ADR 0170 Decision 8).
     *
     * @param planId            the plan being sourced; its drop-off is the one every carried drop-off is
     *                          measured against
     * @param mergeRadiusMeters the rule's radius: a carried drop-off further away than this does not count
     * @param maxOrdersPerRun   the rule's ceiling on orders carried at once: a courier at it is not groupable
     * @param requestedBy       the person on whose request the drop-offs are read (the dispatch-rule simulator),
     *                          so that the recorded reveal names them; null for sourcing, which reads them as a
     *                          system job
     */
    record GroupingProbe(
            UUID planId,
            int mergeRadiusMeters,
            int maxOrdersPerRun,
            @Nullable ActorRef requestedBy) {

        /** A probe sourcing makes on its own account. */
        public GroupingProbe(UUID planId, int mergeRadiusMeters, int maxOrdersPerRun) {
            this(planId, mergeRadiusMeters, maxOrdersPerRun, null);
        }
    }

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
     * @param groupLeadShipmentId the carried shipment {@code groupableWithMetres} was measured to (ADR 0170),
     *                          so acceptance joins that shipment's run without decrypting an address again.
     *                          An id and never a place; null exactly when {@code groupableWithMetres} is
     * @param runIncrementPay   whether this courier would be offered a grouped order on the
     *                          {@code FIRST_FULL_THEN_INCREMENT} basis: the resolved {@code courier.compensation}
     *                          says so and a rate card resolves for them (ADR 0170 Decision 5). False means
     *                          a grouped order is still ranked but is paid {@code DIRECT}
     * @param groupableWithMetres ADR 0142 grouping: the straight-line distance
     *                          between this plan's drop-off and the nearest drop-off of
     *                          an un-picked-up plan from the same branch that this
     *                          courier already carries, or null when there is none.
     *                          A distance only: the other order's coordinates are
     *                          never stored (ADR 0029). Read only when a rule enables
     *                          grouping
     */
    record FleetCandidate(
            UUID courierId,
            int offerTtlSeconds,
            int activeAssignments,
            int concurrencyCeiling,
            @Nullable Integer metresFromBranch,
            int deliveriesThisShift,
            @Nullable Integer groupableWithMetres,
            @Nullable UUID groupLeadShipmentId,
            boolean runIncrementPay) {

        /** A candidate with a measured drop-off and no pay treatment: the shape ADR 0142's ranking was tested with. */
        public FleetCandidate(
                UUID courierId,
                int offerTtlSeconds,
                int activeAssignments,
                int concurrencyCeiling,
                @Nullable Integer metresFromBranch,
                int deliveriesThisShift,
                @Nullable Integer groupableWithMetres) {
            this(
                    courierId,
                    offerTtlSeconds,
                    activeAssignments,
                    concurrencyCeiling,
                    metresFromBranch,
                    deliveriesThisShift,
                    groupableWithMetres,
                    null,
                    false);
        }

        /** A candidate nothing has measured against another drop-off: the shape every adapter answers today. */
        public FleetCandidate(
                UUID courierId,
                int offerTtlSeconds,
                int activeAssignments,
                int concurrencyCeiling,
                @Nullable Integer metresFromBranch,
                int deliveriesThisShift) {
            this(
                    courierId,
                    offerTtlSeconds,
                    activeAssignments,
                    concurrencyCeiling,
                    metresFromBranch,
                    deliveriesThisShift,
                    null);
        }

        public boolean hasCapacity() {
            return activeAssignments < concurrencyCeiling;
        }

        /** The same candidate, measured against the drop-off of a plan it is already carrying (ADR 0142 grouping). */
        public FleetCandidate withGroupableWithin(@Nullable Integer metres) {
            return new FleetCandidate(
                    courierId,
                    offerTtlSeconds,
                    activeAssignments,
                    concurrencyCeiling,
                    metresFromBranch,
                    deliveriesThisShift,
                    metres,
                    metres == null ? null : groupLeadShipmentId,
                    runIncrementPay);
        }

        /** The same candidate, measured against one named carried shipment, and paid on the run basis or not (ADR 0170). */
        public FleetCandidate withGroupableTo(
                @Nullable Integer metres, @Nullable UUID leadShipmentId, boolean runIncrementPay) {
            return new FleetCandidate(
                    courierId,
                    offerTtlSeconds,
                    activeAssignments,
                    concurrencyCeiling,
                    metresFromBranch,
                    deliveriesThisShift,
                    metres,
                    metres == null ? null : leadShipmentId,
                    metres != null && runIncrementPay);
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

        /**
         * For each courier that qualifies, the straight-line distance in metres from the drop-off of {@code
         * planId} to the nearest drop-off of a shipment they are carrying and have not yet picked up, from the
         * same branch (ADR 0170 Decision 8).
         *
         * <p>A courier qualifies only when they carry fewer than {@code maxOrdersPerRun} shipments and that
         * nearest drop-off is within {@code radiusMeters}. The answer is a distance and the id of the shipment
         * it was measured to; the other order's coordinates are revealed under the purpose {@code
         * DISPATCH_GROUPING}, used for the comparison and kept nowhere. A courier whose carried order has no
         * coordinate, or whose address cannot be read, simply does not qualify: never an error. The default
         * answers nobody, which is what an implementation that cannot measure should say.
         */
        default Map<UUID, Groupable> groupable(
                UUID tenantId, UUID planId, Collection<UUID> courierIds, int radiusMeters, int maxOrdersPerRun) {
            return Map.of();
        }

        /**
         * The same question asked on a person's behalf: the reveals it makes are recorded as theirs. An
         * implementation that records nothing about who asked answers as the overload above does.
         *
         * @param requestedBy who the reveals are recorded against; null records them as the sourcing job's
         */
        default Map<UUID, Groupable> groupable(
                UUID tenantId,
                UUID planId,
                Collection<UUID> courierIds,
                int radiusMeters,
                int maxOrdersPerRun,
                @Nullable ActorRef requestedBy) {
            return groupable(tenantId, planId, courierIds, radiusMeters, maxOrdersPerRun);
        }
    }

    /** A courier who can take the plan as part of a run: how far, and the shipment they would join. */
    record Groupable(int distanceMeters, UUID leadShipmentId) {}

    /** Whether a real implementation is present. */
    default boolean isWired() {
        return true;
    }

    /** The reason a sourcing decision carries when it fell back with no fleet behind it. */
    String NOT_WIRED_REASON = "INTERNAL_FLEET_NOT_WIRED";
}
