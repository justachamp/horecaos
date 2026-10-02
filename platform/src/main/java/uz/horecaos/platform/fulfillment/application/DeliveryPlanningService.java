package uz.horecaos.platform.fulfillment.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort.DeliveryOrder;
import uz.horecaos.platform.fulfillment.api.DeliveryPlanner;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.PartnerOption;
import uz.horecaos.platform.fulfillment.domain.BranchOrigin;
import uz.horecaos.platform.fulfillment.domain.Haversine;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliverySourcingPolicy;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchDecision;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchFacts;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRuleEvaluator;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument;
import uz.horecaos.platform.fulfillment.domain.sourcing.PickupPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.PlanStatus;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore.DispatchBranch;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJobStore;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;

/**
 * Turns a confirmed delivery order into a plan and a job (ADR 0014).
 *
 * <p>This is where the two-hour preparation order stops being a decision nobody
 * made. The plan holds the whole time model computed once — from the confirmation
 * instant and the kitchen's estimate, under a named calculation version — and the
 * job is the durable alarm clock that wakes sourcing at {@code source_at}. ADR
 * 0014 rejects a Kafka delayed message for that alarm by name: the delay is
 * approximate, invisible to an operator, and cannot be cancelled or rescheduled
 * when the kitchen changes its mind.
 *
 * <p><b>Both writes are idempotent against a unique index rather than a read.</b>
 * A confirmation arriving twice — a replayed event, a retried command, two
 * threads — must produce one plan and one job, because two jobs for one plan is
 * two workers sourcing the same order, which is how two couriers arrive. Reading
 * first and inserting if absent loses that race by construction, so neither
 * write does.
 */
@Service
public class DeliveryPlanningService implements DeliveryPlanner {

    private static final Logger log = LoggerFactory.getLogger(DeliveryPlanningService.class);

    /** V0023's vocabulary for a straight-line measurement, as ADR 0037 records it. */
    private static final String RADIUS = "RADIUS";

    private final DeliveryOrderPort orders;
    private final JdbcDeliveryPlanStore plans;
    private final JdbcSourcingJobStore jobs;
    private final JdbcDispatchBranchStore branches;
    private final @Nullable ShipmentBookingPort bookings;
    private final PolicyResolver policies;
    private final DispatchMetrics metrics;
    private final Clock clock;

    @Autowired
    public DeliveryPlanningService(
            DeliveryOrderPort orders,
            JdbcDeliveryPlanStore plans,
            JdbcSourcingJobStore jobs,
            JdbcDispatchBranchStore branches,
            ShipmentBookingPort bookings,
            PolicyResolver policies,
            DispatchMetrics metrics,
            Clock clock) {
        this.orders = orders;
        this.plans = plans;
        this.jobs = jobs;
        this.branches = branches;
        this.bookings = bookings;
        this.policies = policies;
        this.metrics = metrics;
        this.clock = clock;
    }

    /**
     * Planning that does not know which partners the branch has bound: the dispatch rules are still
     * evaluated and stored, but a rule's named installations that have no binding cannot be recorded
     * as skipped. For a caller that never reaches a partner lane.
     */
    public DeliveryPlanningService(
            DeliveryOrderPort orders,
            JdbcDeliveryPlanStore plans,
            JdbcSourcingJobStore jobs,
            JdbcDispatchBranchStore branches,
            PolicyResolver policies,
            Clock clock) {
        this.orders = orders;
        this.plans = plans;
        this.jobs = jobs;
        this.branches = branches;
        this.bookings = null;
        this.policies = policies;
        this.metrics = DispatchMetrics.none();
        this.clock = clock;
    }

    @Override
    public Optional<UUID> planFor(UUID tenantId, UUID brandId, UUID locationId, UUID orderId, Instant confirmedAt) {
        return open(tenantId, brandId, locationId, orderId, confirmedAt).map(DeliveryPlan::id);
    }

    @Override
    public Optional<SourcingOutcome> sourcingOutcome(UUID tenantId, UUID orderId) {
        return plans.findByOrder(tenantId, orderId).map(plan -> switch (plan.status()) {
            case ASSIGNED, IN_PROGRESS, COMPLETED -> SourcingOutcome.SOURCED;
            case CANCELLED -> SourcingOutcome.CANCELLED;
            case MANUAL_ACTION_REQUIRED -> SourcingOutcome.MANUAL_ACTION_REQUIRED;
            // PLANNED, WAITING_TO_SOURCE, SOURCING, BOOKING, RETRY_PENDING,
            // SCHEDULED: sourcing's own ladder is still working the job, on its
            // own considered backoff. ORDER_FULFILLMENT never invents a
            // competing timeout for that here — it defers entirely to
            // PlanStatus#MANUAL_ACTION_REQUIRED as the one signal that means
            // sourcing itself has given up.
            default -> SourcingOutcome.IN_PROGRESS;
        });
    }

    /**
     * The plan for a confirmed delivery order, created once.
     *
     * <p>The whole plan rather than {@link DeliveryPlanner}'s id, for the callers
     * inside this module that go on to read the window they just computed.
     *
     * @param confirmedAt the order's own confirmation instant, not now. Every
     *                    instant in the time model derives from it, so a plan
     *                    created by a replay an hour later still describes the
     *                    same promise
     * @return empty when there is nothing to plan — a pickup order, an order this
     *         tenant does not own, or a branch nobody has placed on a map. None of
     *         those is an error a confirmation should be failed for
     */
    @Transactional
    public Optional<DeliveryPlan> open(
            UUID tenantId, UUID brandId, UUID locationId, UUID orderId, Instant confirmedAt) {

        Optional<DeliveryOrder> order = orders.deliveryOrder(tenantId, orderId);
        if (order.isEmpty()) {
            log.debug("Order {} has nothing to deliver; no plan was created", orderId);
            return Optional.empty();
        }
        Optional<DispatchBranch> branch = branches.find(tenantId, brandId, locationId);
        if (branch.isEmpty()) {
            log.warn("Location {} is not this brand's, so order {} cannot be planned", locationId, orderId);
            return Optional.empty();
        }

        BranchOrigin origin;
        try {
            origin = branch.get().origin();
        } catch (BranchOrigin.UnlocatedBranchException unplaced) {
            // A branch with no pin is a configuration fault, not a customer-visible
            // outcome, and failing the confirmation for it would take the
            // restaurant's revenue for a problem the operator can fix in a minute.
            // The order stands and nobody is dispatched.
            log.warn(
                    "Branch {} has no coordinate, so order {} has no delivery plan: {}",
                    locationId,
                    orderId,
                    unplaced.getMessage());
            return Optional.empty();
        }

        ResolvedPolicy<DeliverySourcingPolicy> policy = resolvePolicy(tenantId, brandId, locationId);
        DeliveryOrder details = order.get();
        int distanceMeters = Haversine.metersBetween(
                origin.point(),
                new GeoPoint(details.dropoff().latitude(), details.dropoff().longitude()));

        // ADR 0142 Decision 3: the dispatch rules are evaluated ONCE, here, and the result is stored
        // on the plan beside the document version it ran under. Every later tick applies that stored
        // decision, so editing a rule never reroutes an order already in flight.
        ResolvedPolicy<DispatchRulesDocument> dispatchDocument = resolveDispatchRules(tenantId, brandId, locationId);
        DispatchRulesDocument document =
                dispatchDocument == null ? DispatchRulesDocument.builtIn() : dispatchDocument.document();
        DispatchFacts facts = dispatchFacts(
                details,
                brandId,
                locationId,
                distanceMeters,
                confirmedAt,
                branch.get().timezone());
        List<PartnerOption> bound = bookings == null ? null : bookings.partners(tenantId, brandId, locationId);
        DispatchDecision decision =
                DispatchRuleEvaluator.evaluate(document, facts, bound).decision();
        metrics.recorded(decision);

        PickupPlan pickup = PickupPlan.forOrder(
                confirmedAt, details.preparation(), branch.get().timezone(), policy.document(), decision.dispatchAt());

        DeliveryPlan created = plans.create(new DeliveryPlan(
                UUID.randomUUID(),
                tenantId,
                brandId,
                locationId,
                orderId,
                PlanStatus.PLANNED,
                decision.mode(),
                DeliveryPlan.STANDARD,
                details.deliveryFeeMinor(),
                details.currency(),
                details.deliveryFeeResolutionId(),
                pickup,
                null,
                null,
                distanceMeters,
                RADIUS,
                policy.policyId(),
                policy.policyVersion(),
                1,
                details.destinationLabel(),
                dispatchDocument == null ? null : dispatchDocument.policyId(),
                dispatchDocument == null ? null : dispatchDocument.policyVersion(),
                decision));

        if (jobs.enqueue(
                UUID.randomUUID(), tenantId, created.id(), created.pickup().sourceAt())) {
            log.info(
                    "Delivery plan {} for order {} will be sourced at {}",
                    created.id(),
                    orderId,
                    created.pickup().sourceAt());
        }
        return Optional.of(created);
    }

    /**
     * The kitchen revised its estimate.
     *
     * <p>Recalculated from the original confirmation instant rather than from now,
     * so a revision arriving late does not push the promise out by the time it took
     * to arrive. Only the job's due time moves here: neither verified partner
     * supports reschedule, so a plan already booked is a cancel-and-re-source
     * decision under the cancellation cost policy and not something this method may
     * make silently.
     */
    @Transactional
    public boolean repriceSchedule(UUID tenantId, UUID planId, java.time.Duration revised) {
        Optional<DeliveryPlan> existing = plans.find(tenantId, planId);
        if (existing.isEmpty() || existing.get().status().settled()) {
            return false;
        }
        DeliveryPlan plan = existing.get();
        ResolvedPolicy<DeliverySourcingPolicy> policy = resolvePolicy(tenantId, plan.brandId(), plan.locationId());
        // Under the dispatch start the plan was created with: a revised estimate moves the clock the
        // start is measured from, never the rule that chose the start (ADR 0142 Decision 3).
        PickupPlan revisedPickup = plan.pickup()
                .withPreparation(revised, policy.document(), plan.dispatch().dispatchAt());
        return jobs.moveDueTime(tenantId, planId, revisedPickup.sourceAt(), clock.instant());
    }

    /**
     * The dispatch rules in force for a branch, or null when none was ever published -- in which
     * case the built-in default applies and the plan pins no dispatch document.
     */
    private @Nullable ResolvedPolicy<DispatchRulesDocument> resolveDispatchRules(
            UUID tenantId, UUID brandId, UUID locationId) {
        return policies.resolve(
                        DeliverySourcingPolicies.DISPATCH_RULES, ResourceScope.location(tenantId, brandId, locationId))
                .orElse(null);
    }

    /**
     * The facts a rule can ask about this order. Nothing personal: a channel, a zone, a number of
     * minutes and metres, an instant, whether it is prepaid.
     */
    static DispatchFacts dispatchFacts(
            DeliveryOrder order,
            UUID brandId,
            UUID locationId,
            int distanceMeters,
            Instant confirmedAt,
            java.time.ZoneId branchZone) {
        DeliveryOrderPort.DispatchOrderFacts channel = order.dispatchFacts();
        return new DispatchFacts(
                channel == null ? null : channel.channelSystemType(),
                channel == null ? null : channel.channelId(),
                channel == null ? null : channel.zoneId(),
                brandId,
                locationId,
                order.preparation(),
                distanceMeters,
                confirmedAt,
                branchZone,
                order.prepaid());
    }

    private ResolvedPolicy<DeliverySourcingPolicy> resolvePolicy(UUID tenantId, UUID brandId, UUID locationId) {
        ResourceScope scope = ResourceScope.location(tenantId, brandId, locationId);
        return policies.resolve(DeliverySourcingPolicies.SOURCING, scope)
                .orElseGet(() -> new ResolvedPolicy<>(
                        DeliverySourcingPolicies.SOURCING.code(),
                        DeliverySourcingService.DEFAULTS_ID,
                        1,
                        scope.type(),
                        "defaults",
                        DeliverySourcingPolicy.DEFAULTS));
    }
}
