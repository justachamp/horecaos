package uz.qoida.platform.fulfillment.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.fulfillment.api.DeliveryOrderPort;
import uz.qoida.platform.fulfillment.api.DeliveryPlanner;
import uz.qoida.platform.fulfillment.api.DeliveryOrderPort.DeliveryOrder;
import uz.qoida.platform.fulfillment.domain.BranchOrigin;
import uz.qoida.platform.fulfillment.domain.Haversine;
import uz.qoida.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.qoida.platform.fulfillment.domain.sourcing.DeliverySourcingPolicy;
import uz.qoida.platform.fulfillment.domain.sourcing.PickupPlan;
import uz.qoida.platform.fulfillment.domain.sourcing.PlanStatus;
import uz.qoida.platform.fulfillment.domain.sourcing.SourcingMode;
import uz.qoida.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;
import uz.qoida.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore;
import uz.qoida.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore.DispatchBranch;
import uz.qoida.platform.fulfillment.infrastructure.persistence.JdbcSourcingJobStore;
import uz.qoida.platform.iam.api.ResourceScope;
import uz.qoida.platform.tenancy.api.GeoPoint;
import uz.qoida.platform.tenancy.api.PolicyResolver;
import uz.qoida.platform.tenancy.api.ResolvedPolicy;

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
    private final PolicyResolver policies;
    private final Clock clock;

    public DeliveryPlanningService(DeliveryOrderPort orders, JdbcDeliveryPlanStore plans,
            JdbcSourcingJobStore jobs, JdbcDispatchBranchStore branches,
            PolicyResolver policies, Clock clock) {
        this.orders = orders;
        this.plans = plans;
        this.jobs = jobs;
        this.branches = branches;
        this.policies = policies;
        this.clock = clock;
    }

    @Override
    public Optional<UUID> planFor(UUID tenantId, UUID brandId, UUID locationId, UUID orderId,
            Instant confirmedAt) {
        return open(tenantId, brandId, locationId, orderId, confirmedAt).map(DeliveryPlan::id);
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
    public Optional<DeliveryPlan> open(UUID tenantId, UUID brandId, UUID locationId,
            UUID orderId, Instant confirmedAt) {

        Optional<DeliveryOrder> order = orders.deliveryOrder(tenantId, orderId);
        if (order.isEmpty()) {
            log.debug("Order {} has nothing to deliver; no plan was created", orderId);
            return Optional.empty();
        }
        Optional<DispatchBranch> branch = branches.find(tenantId, brandId, locationId);
        if (branch.isEmpty()) {
            log.warn("Location {} is not this brand's, so order {} cannot be planned",
                    locationId, orderId);
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
            log.warn("Branch {} has no coordinate, so order {} has no delivery plan: {}",
                    locationId, orderId, unplaced.getMessage());
            return Optional.empty();
        }

        ResolvedPolicy<DeliverySourcingPolicy> policy = resolvePolicy(tenantId, brandId, locationId);
        PickupPlan pickup = PickupPlan.forOrder(confirmedAt, order.get().preparation(),
                branch.get().timezone(), policy.document());

        DeliveryOrder details = order.get();
        DeliveryPlan created = plans.create(new DeliveryPlan(
                UUID.randomUUID(), tenantId, brandId, locationId, orderId,
                PlanStatus.PLANNED, SourcingMode.FLEET_FIRST, DeliveryPlan.STANDARD,
                details.deliveryFeeMinor(), details.currency(), details.deliveryFeeResolutionId(),
                pickup, null, null,
                Haversine.metersBetween(origin.point(),
                        new GeoPoint(details.dropoff().latitude(), details.dropoff().longitude())),
                RADIUS, policy.policyId(), policy.policyVersion(), 1));

        if (jobs.enqueue(UUID.randomUUID(), tenantId, created.id(), created.pickup().sourceAt())) {
            log.info("Delivery plan {} for order {} will be sourced at {}",
                    created.id(), orderId, created.pickup().sourceAt());
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
        ResolvedPolicy<DeliverySourcingPolicy> policy =
                resolvePolicy(tenantId, plan.brandId(), plan.locationId());
        PickupPlan revisedPickup = plan.pickup().withPreparation(revised, policy.document());
        return jobs.moveDueTime(tenantId, planId, revisedPickup.sourceAt(), clock.instant());
    }

    private ResolvedPolicy<DeliverySourcingPolicy> resolvePolicy(UUID tenantId, UUID brandId,
            UUID locationId) {
        ResourceScope scope = ResourceScope.location(tenantId, brandId, locationId);
        return policies.resolve(DeliverySourcingPolicies.SOURCING, scope)
                .orElseGet(() -> new ResolvedPolicy<>(
                        DeliverySourcingPolicies.SOURCING.code(),
                        DeliverySourcingService.DEFAULTS_ID, 1, scope.type(), "defaults",
                        DeliverySourcingPolicy.DEFAULTS));
    }
}
