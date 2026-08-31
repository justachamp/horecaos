package uz.horecaos.platform.fulfillment.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort.DeliveryOrder;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.BookingStatus;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryExceptionReason;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.PlanStatus;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingDecision;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingProgress;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingRequest;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore.DispatchBranch;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJobStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJobStore.ClaimedJob;

/**
 * One claimed sourcing job, from the lease to the next due time (ADR 0014).
 *
 * <p>The scheduler decides <em>when</em> and the {@link DeliverySourcingService}
 * decides <em>what</em>; this decides what the job and the plan look like
 * afterwards. Keeping it apart from both is what lets the whole lifecycle —
 * offered, waiting, booked, retrying, escalated — be driven in a test by handing
 * this class a claim, with no scheduler thread and no clock that moves.
 *
 * <p><b>Every path out of here ends the lease.</b> A tick that returns without
 * completing, rescheduling or abandoning leaves a job that only becomes claimable
 * again when its lease expires — correct, but it turns a code path nobody thought
 * about into a delivery that is minutes late. So the mapping below is exhaustive
 * over the decision type, and the failure path releases the lease too.
 */
@Service
public class DeliverySourcingRunner {

    private static final Logger log = LoggerFactory.getLogger(DeliverySourcingRunner.class);

    /**
     * How long after a lapsed offer the plan is looked at again.
     *
     * <p>A second, not zero. The offer's expiry is the instant it stops being
     * live, and a job due at exactly that instant races the courier's last tap for
     * the same offer — the database settles that race correctly either way, but
     * the second is free and the courier's tap is the outcome everybody prefers.
     */
    private static final Duration AFTER_OFFER = Duration.ofSeconds(1);

    /** A partner refused. The next one is tried on the next tick, not in this one. */
    private static final Duration AFTER_REFUSAL = Duration.ofSeconds(5);

    private final DeliverySourcingService sourcing;
    private final SourcingJournal journal;
    private final DeliveryOrderPort orders;
    private final JdbcDeliveryPlanStore plans;
    private final JdbcSourcingJobStore jobs;
    private final JdbcDispatchBranchStore branches;
    private final Clock clock;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final int maxAttempts;

    public DeliverySourcingRunner(
            DeliverySourcingService sourcing,
            SourcingJournal journal,
            DeliveryOrderPort orders,
            JdbcDeliveryPlanStore plans,
            JdbcSourcingJobStore jobs,
            JdbcDispatchBranchStore branches,
            Clock clock,
            @Value("${horecaos.fulfillment.sourcing.initial-backoff:5s}") Duration initialBackoff,
            @Value("${horecaos.fulfillment.sourcing.max-backoff:2m}") Duration maxBackoff,
            @Value("${horecaos.fulfillment.sourcing.max-attempts:12}") int maxAttempts) {

        if (initialBackoff.isNegative()
                || initialBackoff.isZero()
                || maxBackoff.compareTo(initialBackoff) < 0
                || maxAttempts < 1) {
            throw new IllegalArgumentException("Sourcing retry settings must be positive and consistently ordered");
        }
        this.sourcing = sourcing;
        this.journal = journal;
        this.orders = orders;
        this.plans = plans;
        this.jobs = jobs;
        this.branches = branches;
        this.clock = clock;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.maxAttempts = maxAttempts;
    }

    /**
     * @return the decision this tick reached, or empty when there was nothing to
     *         decide — a plan already settled, an order that stopped being
     *         deliverable, a branch that lost its pin
     */
    public Optional<SourcingDecision> run(ClaimedJob job) {
        Instant now = clock.instant();
        Optional<DeliveryPlan> found = plans.find(job.tenantId(), job.planId());
        if (found.isEmpty() || found.get().status().settled()) {
            // The plan was cancelled, or somebody assigned it by hand while this
            // job waited. Completing rather than abandoning: nothing went wrong.
            jobs.complete(job.jobId(), job.leaseToken(), now);
            return Optional.empty();
        }
        DeliveryPlan plan = found.get();

        Optional<UUID> shipment = journal.assignedShipment(plan.tenantId(), plan.id());
        if (shipment.isPresent()) {
            // Somebody is already carrying this order, and the plan does not say so
            // because the worker that won died between committing the shipment and
            // recording it. Deciding again from here is how a real second courier
            // gets booked: the second shipment loses to the unique index, but the
            // partner has already dispatched somebody and will bill for it.
            log.warn("Plan {} already has shipment {}; the tick that won it did not finish", plan.id(), shipment.get());
            plans.settle(plan.tenantId(), plan.id(), PlanStatus.ASSIGNED, now);
            jobs.complete(job.jobId(), job.leaseToken(), now);
            return Optional.empty();
        }

        Optional<DeliveryOrder> order = orders.deliveryOrder(job.tenantId(), plan.orderId());
        Optional<DispatchBranch> branch = branches.find(plan.tenantId(), plan.brandId(), plan.locationId());
        if (order.isEmpty() || branch.isEmpty()) {
            return Optional.of(handUp(
                    job,
                    plan,
                    DeliveryExceptionReason.ADDRESS_ISSUE,
                    "the order or its branch can no longer be read for dispatch",
                    now));
        }

        // Before anything is decided, because an offer left OFFERED past its
        // expiry still holds ux_attempt_one_offered and the next courier cannot be
        // asked. The planner would see a live offer that is not one.
        journal.expireLapsedOffers(plan.tenantId(), plan.id(), now);

        SourcingProgress progress = journal.progress(plan.tenantId(), plan.id(), job.createdAt());
        plans.transition(plan.tenantId(), plan.id(), plan.status(), PlanStatus.SOURCING, now);

        DeliverySourcingService.Outcome outcome = sourcing.source(request(plan, order.get(), branch.get()), progress);

        return Optional.of(settle(job, plan, outcome, now));
    }

    // ------------------------------------------------------- what happens next

    private SourcingDecision settle(
            ClaimedJob job, DeliveryPlan plan, DeliverySourcingService.Outcome outcome, Instant now) {

        SourcingDecision decision = outcome.decision();
        switch (decision) {
            case SourcingDecision.OfferInternal offer ->
                wake(job, plan, offer.expiresAt().plus(AFTER_OFFER), outcome, null, now);
            case SourcingDecision.WaitForInternal wait ->
                wake(job, plan, wait.retryAt().plus(AFTER_OFFER), outcome, null, now);
            case SourcingDecision.EscalateToOperations escalate -> {
                // The exception row is already written by the service. Nothing
                // automated remains, so the job is finished rather than retried:
                // a plan a human owns must not keep waking a worker that will
                // reach the same conclusion every five seconds.
                plans.settle(plan.tenantId(), plan.id(), PlanStatus.MANUAL_ACTION_REQUIRED, now);
                jobs.complete(job.jobId(), job.leaseToken(), now);
                log.warn("Plan {} left to operations: {}", plan.id(), escalate.reason());
            }
            case SourcingDecision.BookPartner book -> settleBooking(job, plan, outcome, book, now);
        }
        return decision;
    }

    private void settleBooking(
            ClaimedJob job,
            DeliveryPlan plan,
            DeliverySourcingService.Outcome outcome,
            SourcingDecision.BookPartner book,
            Instant now) {

        if (outcome.assigned()) {
            plans.settle(plan.tenantId(), plan.id(), PlanStatus.ASSIGNED, now);
            jobs.complete(job.jobId(), job.leaseToken(), now);
            log.info("Plan {} assigned to {} ({})", plan.id(), book.partner().providerType(), book.reason());
            return;
        }

        BookingStatus status =
                outcome.receipt() == null ? null : outcome.receipt().status();
        if (status == BookingStatus.UNCERTAIN || status == BookingStatus.HELD) {
            // ADR 0014: do not book a fallback while the first provider may have
            // accepted. The route already tried to reconcile by query, so this is
            // one the query could not settle either, and a human owns it.
            plans.settle(plan.tenantId(), plan.id(), PlanStatus.MANUAL_ACTION_REQUIRED, now);
            jobs.abandon(job.jobId(), job.leaseToken(), DeliveryExceptionReason.AWAITING_RECONCILIATION, now);
            log.error("Plan {} has an unresolved partner attempt and will not be sourced further", plan.id());
            return;
        }

        if (job.claimedAttempt() >= maxAttempts) {
            handUp(
                    job,
                    plan,
                    DeliveryExceptionReason.LATE_ASSIGNMENT,
                    "sourcing spent its retry budget after " + job.claimedAttempt() + " attempts",
                    now);
            return;
        }

        Duration wait = status == BookingStatus.RETRYABLE ? backoff(job.claimedAttempt()) : AFTER_REFUSAL;
        plans.transition(plan.tenantId(), plan.id(), PlanStatus.SOURCING, PlanStatus.RETRY_PENDING, now);
        wake(
                job,
                plan,
                now.plus(wait),
                outcome,
                outcome.receipt() == null ? null : outcome.receipt().errorCode(),
                now);
    }

    /**
     * Come back to this plan later, but never later than the promise allows.
     *
     * <p>Clamped to {@code latest_assignment_at} so that a backoff cannot carry a
     * job past the instant the planner would have escalated it. Without the clamp
     * an order whose partner keeps timing out sleeps through its own deadline and
     * an operator hears about it from the customer.
     */
    private void wake(
            ClaimedJob job,
            DeliveryPlan plan,
            Instant dueAt,
            DeliverySourcingService.Outcome outcome,
            @Nullable String errorCode,
            Instant now) {

        Instant latest = plan.pickup().latestAssignmentAt();
        Instant clamped = dueAt.isAfter(latest) ? latest : dueAt;
        if (!jobs.reschedule(job.jobId(), job.leaseToken(), clamped, checkpoint(outcome), errorCode, now)) {
            log.warn("Sourcing lease for plan {} was lost before it could be rescheduled", plan.id());
        }
    }

    private SourcingDecision handUp(ClaimedJob job, DeliveryPlan plan, String reason, String detail, Instant now) {

        journal.raiseException(plan.tenantId(), plan.brandId(), plan.locationId(), plan.id(), reason, detail, now);
        plans.settle(plan.tenantId(), plan.id(), PlanStatus.MANUAL_ACTION_REQUIRED, now);
        jobs.abandon(job.jobId(), job.leaseToken(), reason, now);
        log.error("Plan {} cannot be sourced automatically: {}", plan.id(), reason);
        return new SourcingDecision.EscalateToOperations(reason);
    }

    /**
     * What the job row shows an operator, and nothing a decision is read back from.
     *
     * <p>Deliberately not the authoritative progress. That is rebuilt from the
     * attempt rows on every tick, because two places holding one truth is how a
     * restart re-offers an order to a courier who already declined it. This is a
     * convenience for whoever opens the job row at three in the morning.
     */
    private static String checkpoint(DeliverySourcingService.Outcome outcome) {
        return """
                {"lastDecision":"%s","lastReason":"%s","attempt":"%s"}""".formatted(
                        outcome.decision().getClass().getSimpleName(),
                        outcome.decision().reason(),
                        outcome.attemptId() == null ? "" : outcome.attemptId());
    }

    /** Exponential, capped, and measured from the claim count the job itself holds. */
    private Duration backoff(int attempt) {
        int exponent = Math.min(Math.max(attempt - 1, 0), 30);
        long candidate;
        try {
            candidate = Math.multiplyExact(initialBackoff.toMillis(), 1L << exponent);
        } catch (ArithmeticException overflow) {
            candidate = Long.MAX_VALUE;
        }
        return Duration.ofMillis(Math.min(candidate, maxBackoff.toMillis()));
    }

    private static SourcingRequest request(DeliveryPlan plan, DeliveryOrder order, DispatchBranch branch) {

        return new SourcingRequest(
                plan.tenantId(),
                plan.brandId(),
                plan.locationId(),
                plan.orderId(),
                plan.id(),
                order.orderReference(),
                plan.pickup(),
                plan.mode(),
                plan.distanceMeters() == null ? 0 : plan.distanceMeters(),
                branch.asWaypoint(),
                order.dropoff(),
                order.prepaid(),
                order.itemValueMinor(),
                order.currency(),
                // The plan id, not a request id: every log line and every provider
                // call for this order correlates on the one thing that identifies
                // the sourcing effort and names nobody.
                plan.id().toString());
    }
}
