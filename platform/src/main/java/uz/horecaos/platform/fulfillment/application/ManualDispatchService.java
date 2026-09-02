package uz.horecaos.platform.fulfillment.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.fulfillment.application.ServiceZoneService.DeliveryResourceNotFoundException;
import uz.horecaos.platform.fulfillment.domain.sourcing.AttemptStatus;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.PlanStatus;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourceType;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore.NewAttempt;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore.Shipment;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore.WinningAttempt;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;

/**
 * The dispatch board's own assign/unassign (operations §3.1), on top of ADR
 * 0014's automated sourcing machinery rather than beside it.
 *
 * <p>This is deliberately not a second state machine. An operator's "Assign to
 * courier" opens exactly the {@code assignment_attempts} row automated sourcing
 * would have opened for an offer — {@code source_type = INTERNAL}, one courier
 * named — except {@link AttemptStatus#REQUESTED} rather than {@code OFFERED}:
 * a manual assignment is authoritative the instant an operator clicks it,
 * because the courier mobile app's accept/decline flow is a different build
 * this wave does not touch (an operator here is standing in for that
 * acceptance, not routing around it). {@link JdbcAssignmentStore#win} is then
 * the exact primitive automated sourcing itself uses to promote an attempt to
 * a shipment — the same compare-and-set, the same single-winner guarantee, so
 * a dispatcher clicking "assign" one second after the scheduler ticks loses
 * the race the same honest way a second dispatcher would.
 *
 * <p>Unassigning is the inverse of assigning, not a cancellation. The winning
 * attempt is left exactly as it was — it is a true historical fact that this
 * courier once accepted this order — and only the shipment and the plan move.
 * {@link JdbcAssignmentStore#cancelShipment} is a new, narrow write for exactly
 * this: nothing existing already un-does a {@code win()}.
 */
@Service
public class ManualDispatchService {

    private final JdbcDeliveryPlanStore plans;
    private final JdbcAssignmentStore assignments;
    private final Clock clock;

    public ManualDispatchService(JdbcDeliveryPlanStore plans, JdbcAssignmentStore assignments, Clock clock) {
        this.plans = plans;
        this.assignments = assignments;
        this.clock = clock;
    }

    /**
     * Assigns one courier to one plan.
     *
     * <p>Refused as a conflict, never as an error, when somebody else already
     * holds the plan — a live attempt from a scheduler tick, or another
     * dispatcher's own click a moment earlier. The caller re-reads and shows
     * who actually has it, the same "the response reports the outcome that
     * actually settled it" contract the order board's own decisions keep.
     */
    @Transactional
    public DispatchOutcome assign(
            UUID tenantId, UUID planId, UUID courierId, int expectedPlanVersion, String reasonCode) {
        DeliveryPlan plan = requirePlan(tenantId, planId);
        if (plan.version() != expectedPlanVersion) {
            return DispatchOutcome.conflict(plan.status(), plan.version(), "STALE_VERSION");
        }

        Instant now = clock.instant();
        var opened = assignments.open(new NewAttempt(
                tenantId,
                planId,
                SourceType.INTERNAL,
                AttemptStatus.REQUESTED,
                courierId,
                null,
                null,
                UUID.randomUUID().toString(),
                reasonCode,
                null,
                null,
                null,
                now));

        if (opened.status() != AttemptStatus.REQUESTED) {
            // A live attempt already exists for this plan — the scheduler's, or
            // another operator's. Neither is this call's to override.
            return DispatchOutcome.conflict(plan.status(), plan.version(), "ALREADY_BEING_SOURCED");
        }

        Optional<UUID> shipmentId = assignments.win(new WinningAttempt(
                tenantId, opened.attemptId(), SourceType.INTERNAL, AttemptStatus.REQUESTED, null, null, now));
        if (shipmentId.isEmpty()) {
            // Somebody else's shipment already exists for this plan. Not an error:
            // the order is carried, just not by the courier this call named.
            return DispatchOutcome.conflict(plan.status(), plan.version(), "ALREADY_ASSIGNED");
        }

        int newPlanVersion = plan.version() + 1;
        if (!plans.transition(tenantId, planId, plan.status(), PlanStatus.ASSIGNED, now)) {
            // The shipment above is the fact that matters and is already
            // committed; the plan's own status column may simply be one step
            // behind a concurrent write. One retry against the freshest read is
            // enough to close that window without risking a lost update.
            DeliveryPlan latest = requirePlan(tenantId, planId);
            plans.transition(tenantId, planId, latest.status(), PlanStatus.ASSIGNED, now);
            newPlanVersion = latest.version() + 1;
        }

        return DispatchOutcome.applied(PlanStatus.ASSIGNED, newPlanVersion, shipmentId.get());
    }

    /**
     * Returns a plan to the sourcing pool without touching the attempt that
     * won it.
     *
     * <p>{@code expectedShipmentVersion} is the version the caller last read the
     * <em>shipment</em> at — not the plan — because it is the shipment row this
     * call conditions its write on.
     */
    @Transactional
    public DispatchOutcome unassign(UUID tenantId, UUID planId, int expectedShipmentVersion, String reasonCode) {
        DeliveryPlan plan = requirePlan(tenantId, planId);
        Shipment shipment = assignments
                .findShipment(tenantId, planId)
                .orElseThrow(() -> new DeliveryResourceNotFoundException(
                        "Plan " + planId + " has no active shipment to unassign"));

        Instant now = clock.instant();
        boolean cancelled =
                assignments.cancelShipment(tenantId, shipment.id(), expectedShipmentVersion, reasonCode, now);
        if (!cancelled) {
            // Either the version is stale, or the shipment has already moved past
            // ASSIGNED/PICKUP_PENDING — couriers.md's "blocked once PICKED_UP".
            return DispatchOutcome.conflict(plan.status(), plan.version(), "CANNOT_UNASSIGN");
        }

        // Best-effort: a plan not currently ASSIGNED (already MANUAL_ACTION_REQUIRED,
        // say) is left as it is rather than forced backwards. The shipment
        // cancellation just committed is the fact that matters either way.
        boolean movedBack = plans.transition(tenantId, planId, PlanStatus.ASSIGNED, PlanStatus.WAITING_TO_SOURCE, now);
        return movedBack
                ? DispatchOutcome.applied(PlanStatus.WAITING_TO_SOURCE, plan.version() + 1, null)
                : DispatchOutcome.applied(plan.status(), plan.version(), null);
    }

    private DeliveryPlan requirePlan(UUID tenantId, UUID planId) {
        return plans.find(tenantId, planId)
                .orElseThrow(() -> new DeliveryResourceNotFoundException("No delivery plan " + planId));
    }

    /**
     * @param planStatus the plan's status as this call leaves it (or found it, on
     *                   a conflict) — never stale by more than the one retry
     *                   {@link #assign} already takes
     * @param reason     null when {@code applied}; one of {@code STALE_VERSION},
     *                   {@code ALREADY_BEING_SOURCED}, {@code ALREADY_ASSIGNED},
     *                   {@code CANNOT_UNASSIGN} otherwise
     */
    public record DispatchOutcome(
            boolean applied,
            PlanStatus planStatus,
            int planVersion,
            @Nullable UUID shipmentId,
            @Nullable String reason) {

        static DispatchOutcome applied(PlanStatus status, int version, @Nullable UUID shipmentId) {
            return new DispatchOutcome(true, status, version, shipmentId, null);
        }

        static DispatchOutcome conflict(PlanStatus status, int version, String reason) {
            return new DispatchOutcome(false, status, version, null, reason);
        }
    }
}
