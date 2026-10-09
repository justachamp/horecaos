package uz.horecaos.platform.courier.infrastructure.dispatch;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.courier.application.CourierDispatchGate;
import uz.horecaos.platform.courier.application.CourierPolicyResolver;
import uz.horecaos.platform.courier.domain.RunPayMode;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierRateCardStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierShiftStore.FleetRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.fulfillment.api.InternalFleetPort;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.telemetry.api.CourierProximityPort;

/**
 * ADR 0042's answer to ADR 0014's first question: who may be offered this order
 * (ADR 0014, ADR 0042).
 *
 * <p>Until this class existed, {@code InternalFleetConfiguration}'s stand-in
 * answered no candidates, every delivery on the platform recorded
 * {@code NO_INTERNAL_CANDIDATE}, and the in-house lane — written, tested and
 * paid for — was never taken. {@code CourierDispatchGate} had one caller and it
 * was a test.
 *
 * <h2>Which direction the dependency points, and why it has to</h2>
 *
 * <p>Fulfillment declares the port; this module implements it. That is the only
 * arrangement in which sourcing stays free of a workforce model: fulfillment
 * must never learn to read {@code courier_shifts} or
 * {@code courier_engagements}, because the moment it can, "is this courier
 * dispatchable" acquires a second answer that drifts from the first.
 *
 * <p>The same rule cuts the other way for the two facts this module does not
 * own, and neither is fetched by reaching into somebody else's tables.
 *
 * <ul>
 *   <li><b>How loaded a courier is</b> comes from
 *       {@link InternalFleetPort.ActiveAssignments}, implemented in
 *       fulfillment. V0040 states the boundary in as many words — ADR 0014 owns
 *       the attempt "including the conditional-update ceiling on concurrent
 *       assignments" — and counting shipments here would be the second place
 *       that ceiling lives.</li>
 *   <li><b>How far away he is</b> comes from
 *       {@link CourierProximityPort}, implemented in telemetry, and it answers a
 *       metre count rather than a position. ADR 0045 keeps a courier's
 *       coordinates behind a location-scoped capability; a distance from a
 *       branch the caller already named is a circle, not a pin.</li>
 * </ul>
 *
 * <h2>What this class decides, which is nothing</h2>
 *
 * <p>Eligibility is {@link CourierDispatchGate}'s, unchanged and unduplicated,
 * including its ADVISORY and OFF behaviour and its named refusal reasons. This
 * class enumerates and assembles. The one judgement it makes is that a courier
 * is associated with a branch by having opened a shift there — see
 * {@code JdbcCourierShiftStore.fleetOnShiftAt} — which is a real limitation
 * under {@code courier.shift.enforcement = OFF}, where the gate would forgive a
 * missing shift but nothing on this platform can say which branch a shift-less
 * courier belongs to. There is no roster and no availability table yet; ADR
 * 0042 lists both as not built. A fleet that opens no shifts is therefore an
 * empty fleet under every enforcement mode, and the honest place to fix that is
 * the roster, not a guess here.
 *
 * <p>{@link #isWired()} is left at its default {@code true}. That is the whole
 * point of the method: the stand-in says false to mean "the empty answer below
 * is a hole, not a fleet", and a real implementation saying true means the
 * emptiness is a fact about tonight's rota.
 */
@Component
public class InternalFleetAdapter implements InternalFleetPort {

    private final JdbcCourierShiftStore shifts;
    private final CourierDispatchGate gate;
    private final ActiveAssignments assignments;
    private final CourierProximityPort proximity;
    private final @Nullable CourierPolicyResolver policies;
    private final @Nullable JdbcCourierRateCardStore rateCards;
    private final @Nullable JdbcCourierStore couriers;
    private final @Nullable MeterRegistry meters;
    private final Clock clock;

    /** A fleet that can rank but not measure a run: no pay treatment, no card check. Every test that predates ADR 0170. */
    public InternalFleetAdapter(
            JdbcCourierShiftStore shifts,
            CourierDispatchGate gate,
            ActiveAssignments assignments,
            CourierProximityPort proximity) {
        this(shifts, gate, assignments, proximity, null, null, null, null, Clock.systemUTC());
    }

    @Autowired
    public InternalFleetAdapter(
            JdbcCourierShiftStore shifts,
            CourierDispatchGate gate,
            ActiveAssignments assignments,
            CourierProximityPort proximity,
            @Nullable CourierPolicyResolver policies,
            @Nullable JdbcCourierRateCardStore rateCards,
            @Nullable JdbcCourierStore couriers,
            @Nullable MeterRegistry meters,
            Clock clock) {
        this.shifts = shifts;
        this.gate = gate;
        this.assignments = assignments;
        this.proximity = proximity;
        this.policies = policies;
        this.rateCards = rateCards;
        this.couriers = couriers;
        this.meters = meters;
        this.clock = clock;
    }

    /**
     * The candidates, measured against the plan's drop-off when a rule groups (ADR 0170 Decisions 5, 6 and 8).
     *
     * <p>The measurement is {@code fulfillment}'s ({@link ActiveAssignments#groupable}): a distance and the
     * shipment it was measured to, never a coordinate. What this module adds is the pay treatment, because pay
     * is its own: a measured courier is paid on the run basis only when the resolved {@code courier.compensation}
     * says first-full-then-increment <em>and</em> a rate card resolves for them, since a quote the courier is
     * shown before accepting needs a card to compute it. A courier with no card who is groupable is still ranked
     * first (grouping saves the road) but is never offered a run price: {@code runIncrementPay} stays false and
     * the order is paid {@code DIRECT}; the miss is counted as {@code no_card}.
     */
    @Override
    public List<FleetCandidate> candidates(
            UUID tenantId, UUID brandId, UUID locationId, int distanceMeters, @Nullable GroupingProbe probe) {

        List<FleetCandidate> plain = candidates(tenantId, brandId, locationId, distanceMeters);
        if (probe == null || plain.isEmpty()) {
            return plain;
        }
        Map<UUID, Groupable> groupable = assignments.groupable(
                tenantId,
                probe.planId(),
                plain.stream().map(FleetCandidate::courierId).toList(),
                probe.mergeRadiusMeters(),
                probe.maxOrdersPerRun());
        if (groupable.isEmpty()) {
            return plain;
        }
        boolean incrementPolicy = policies != null
                && policies.resolve(ResourceScope.location(tenantId, brandId, locationId))
                                .runPayMode()
                        == RunPayMode.FIRST_FULL_THEN_INCREMENT;

        List<FleetCandidate> measured = new ArrayList<>(plain.size());
        for (FleetCandidate candidate : plain) {
            Groupable near = groupable.get(candidate.courierId());
            if (near == null) {
                measured.add(candidate);
                continue;
            }
            boolean runPay = incrementPolicy && hasCard(tenantId, brandId, locationId, candidate.courierId());
            if (incrementPolicy && !runPay) {
                count("no_card");
            }
            measured.add(candidate.withGroupableTo(near.distanceMeters(), near.leadShipmentId(), runPay));
        }
        return List.copyOf(measured);
    }

    /** Whether a rate card resolves for this courier's type at the branch: the precondition of any quote. */
    private boolean hasCard(UUID tenantId, UUID brandId, UUID locationId, UUID courierId) {
        if (rateCards == null || couriers == null) {
            return false;
        }
        return couriers.findCourier(tenantId, courierId)
                .flatMap(courier ->
                        rateCards.resolve(tenantId, brandId, locationId, courier.courierTypeId(), clock.instant()))
                .isPresent();
    }

    private void count(String outcome) {
        if (meters != null) {
            meters.counter("fulfillment.grouping.offers", "outcome", outcome).increment();
        }
    }

    @Override
    public List<FleetCandidate> candidates(UUID tenantId, UUID brandId, UUID locationId, int distanceMeters) {

        List<FleetRow> onShift = shifts.fleetOnShiftAt(tenantId, brandId, locationId);
        if (onShift.isEmpty()) {
            return List.of();
        }

        // The gate is asked per courier and its answer is taken whole. It resolves
        // the enforcement policy each time, which is one policy read per courier
        // on shift at one branch -- a handful -- and the alternative is this class
        // holding a copy of the enforcement rules, which is the thing that goes
        // stale.
        List<FleetRow> eligible = new ArrayList<>();
        for (FleetRow row : onShift) {
            if (gate.evaluate(tenantId, brandId, locationId, row.courierId(), distanceMeters)
                    .eligible()) {
                eligible.add(row);
            }
        }
        if (eligible.isEmpty()) {
            return List.of();
        }

        List<UUID> courierIds = eligible.stream().map(FleetRow::courierId).toList();
        Map<UUID, Integer> carrying = assignments.byCourier(tenantId, courierIds);
        Map<UUID, Integer> metres = proximity.metresFromBranch(tenantId, locationId, courierIds);

        List<FleetCandidate> candidates = new ArrayList<>(eligible.size());
        for (FleetRow row : eligible) {
            candidates.add(new FleetCandidate(
                    row.courierId(),
                    row.offerTtlSeconds(),
                    // Absent means carrying nothing. Absent from the proximity
                    // answer means something different -- we do not know where he
                    // is -- and stays null, which the planner ranks last rather
                    // than nearest.
                    carrying.getOrDefault(row.courierId(), 0),
                    row.concurrencyCeiling(),
                    metres.get(row.courierId()),
                    row.deliveriesThisShift()));
        }
        return List.copyOf(candidates);
    }
}
