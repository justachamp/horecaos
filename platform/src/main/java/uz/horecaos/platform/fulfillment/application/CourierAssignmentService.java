package uz.horecaos.platform.fulfillment.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.fulfillment.api.CourierAssessmentPort;
import uz.horecaos.platform.fulfillment.api.CourierAssignmentPort;
import uz.horecaos.platform.fulfillment.domain.sourcing.AttemptStatus;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.PlanAssignability;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingMode;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;

/**
 * A person's bulk assignment of one courier to one order (ADR 0172).
 *
 * <p>Decides the item in the order the record states and the first refusal is
 * its answer, then delegates the assignment itself to {@link ManualDispatchService#assign}
 * so the single path and the bulk path share {@code JdbcAssignmentStore.open}
 * and {@code win} and the one single-winner guarantee (ADR 0014). It adds three
 * things the single assignment does not do and a hundred-order version cannot do
 * without:
 *
 * <ul>
 *   <li>a derived attempt key, so a retried item reaches the attempt it opened;
 *   <li>the courier's ceiling, under a row lock, in submission order;
 *   <li>the dispatch gate's full answer, through {@link CourierAssessmentPort}.
 * </ul>
 *
 * <p>It never evaluates, edits or re-runs a dispatch rule: a person's assignment
 * overrides one, visibly, and the note says so. It never creates a run and reads no
 * other order's drop-off (ADR 0170): the attempt it opens is paid {@code DIRECT}.
 *
 * <p>One transaction per call. The caller ({@code ordering}'s bulk service) opens none
 * of its own, so the courier's row lock is held for the length of one item.
 */
@Service
public class CourierAssignmentService implements CourierAssignmentPort {

    /** The reason a plan's stored decision is overridden by a person's choice (ADR 0172 Decision 6). */
    static final String OVERRODE_DISPATCH_RULE = "OVERRODE_DISPATCH_RULE";

    private final JdbcDeliveryPlanStore plans;
    private final JdbcAssignmentStore assignments;
    private final ManualDispatchService manual;
    private final CourierAssessmentPort assessment;

    public CourierAssignmentService(
            JdbcDeliveryPlanStore plans,
            JdbcAssignmentStore assignments,
            ManualDispatchService manual,
            CourierAssessmentPort assessment) {
        this.plans = plans;
        this.assignments = assignments;
        this.manual = manual;
        this.assessment = assessment;
    }

    @Override
    public boolean courierExists(UUID tenantId, UUID courierId) {
        return assignments.courierExists(tenantId, courierId);
    }

    @Override
    @Transactional
    public Outcome assignForOrder(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID orderId,
            UUID courierId,
            String reasonCode,
            String attemptKey,
            UUID bulkOperationId,
            ActorRef actor) {

        Optional<DeliveryPlan> found = plans.findLatestByOrder(tenantId, orderId);
        if (found.isEmpty() || !found.get().locationId().equals(locationId)) {
            return Outcome.refused(null, "NO_DELIVERY_PLAN", List.of());
        }
        DeliveryPlan plan = found.get();
        // ADR 0172 Decision 6: the rule the plan matched is recorded on the item whatever the item's fate.
        // Read from the stored decision, never re-evaluated.
        String ruleId = plan.dispatch().ruleId();

        // A retried item whose first run already won: the attempt under this key is
        // ACCEPTED and its shipment is the courier's. Report the same assignment, not
        // "already assigned" -- the retry asked for exactly this.
        Optional<JdbcAssignmentStore.AttemptRef> own = assignments.findAttemptByKey(tenantId, attemptKey);
        if (own.isPresent()
                && own.get().status() == AttemptStatus.ACCEPTED
                && courierId.equals(own.get().courierId())
                && own.get().shipmentId() != null) {
            return Outcome.applied(plan.id(), own.get().shipmentId(), List.of(), ruleId);
        }

        PlanAssignability assignability = PlanAssignability.of(plan.status());
        if (assignability == PlanAssignability.NOT_OPEN) {
            return Outcome.refused(plan.id(), "PLAN_NOT_OPEN", List.of(), ruleId);
        }
        var carried = assignments.findShipment(tenantId, plan.id());
        if (assignability == PlanAssignability.CARRIED || carried.isPresent()) {
            boolean thisCourier =
                    carried.isPresent() && courierId.equals(carried.get().courierId());
            return Outcome.refused(
                    plan.id(),
                    thisCourier ? "ALREADY_ASSIGNED_TO_THIS_COURIER" : "ALREADY_ASSIGNED",
                    List.of(),
                    ruleId);
        }
        if (assignability == PlanAssignability.BEING_SOURCED
                || assignments.hasLiveAttemptOtherThan(tenantId, plan.id(), attemptKey)) {
            return Outcome.refused(plan.id(), "ALREADY_BEING_SOURCED", List.of(), ruleId);
        }
        if (assignments.hasUncertainAttempt(tenantId, plan.id())) {
            return Outcome.refused(plan.id(), "PARTNER_ATTEMPT_UNRECONCILED", List.of(), ruleId);
        }

        CourierAssessmentPort.Assessment assessed = assessment.assess(
                tenantId, brandId, locationId, courierId, plan.distanceMeters() == null ? 0 : plan.distanceMeters());
        if (!assessed.eligible()) {
            return Outcome.refused(plan.id(), "COURIER_NOT_ELIGIBLE", assessed.refusals(), ruleId);
        }

        // The ceiling. Locked and counted inside this item's transaction: two bulks
        // aimed at one courier serialise here, and a replay answers identically because
        // the count is of committed state and the items run in the order sent.
        int carriedByCourier = assignments.lockCourierAndCountCarried(tenantId, courierId);
        if (carriedByCourier >= assessed.concurrencyCeiling()) {
            return Outcome.refused(plan.id(), "COURIER_AT_CAPACITY", List.of(), ruleId);
        }

        List<String> notes = new ArrayList<>(assessed.notes());
        SourcingMode storedMode = plan.dispatch().mode();
        if (storedMode == SourcingMode.PARTNER_ONLY || storedMode == SourcingMode.PARTNER_FIRST) {
            // The stored decision never asks the fleet, or asks a partner first. A person
            // putting a courier on it has overridden that, and the record says so.
            notes.add(OVERRODE_DISPATCH_RULE);
        }

        ManualDispatchService.DispatchOutcome result = manual.assign(
                tenantId,
                plan.id(),
                courierId,
                plan.version(),
                reasonCode,
                actor,
                attemptKey,
                new ManualDispatchService.AssignmentContext(plan.id().toString(), bulkOperationId, notes));

        if (result.applied()) {
            return Outcome.applied(plan.id(), Objects.requireNonNull(result.shipmentId()), notes, ruleId);
        }
        String reason = result.reason() == null ? "UNEXPECTED_FAILURE" : result.reason();
        if ("STALE_VERSION".equals(reason)) {
            // The plan's version moved between this item reading it and acting on it. When what moved it was
            // somebody else's assignment of this very order -- the second of two bulks sent together over one
            // order -- the honest answer is the conflict that happened, not a version the caller never held.
            var carriedNow = assignments.findShipment(tenantId, plan.id());
            if (carriedNow.isPresent()) {
                boolean thisCourier = courierId.equals(carriedNow.get().courierId());
                return Outcome.refused(
                        plan.id(),
                        thisCourier ? "ALREADY_ASSIGNED_TO_THIS_COURIER" : "ALREADY_ASSIGNED",
                        List.of(),
                        ruleId);
            }
        }
        return Outcome.refused(plan.id(), reason, List.of(), ruleId);
    }
}
