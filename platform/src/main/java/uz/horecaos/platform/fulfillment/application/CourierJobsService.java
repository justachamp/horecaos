package uz.horecaos.platform.fulfillment.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort.CustomerLocation;
import uz.horecaos.platform.fulfillment.domain.Haversine;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.PlanStatus;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcCourierJobStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore.DispatchBranch;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcSourcingJobStore;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.RealtimeSignalPublisher;
import uz.horecaos.platform.telemetry.api.ScopeKey;
import uz.horecaos.platform.telemetry.api.StreamChannel;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * {@link CourierJobsPort}: the courier-app side of ADR 0014's attempt and shipment.
 *
 * <p>State and facts only. Which of these answers a courier may be given is a courier policy,
 * held by {@code courier}; this class is the compare-and-sets, the ownership predicates and the
 * reductions (a position becomes a distance, a door is decrypted for one purpose) that policy
 * is enforced with.
 *
 * <h2>What a courier's answer does to the rest of the pipeline</h2>
 *
 * <ul>
 *   <li><b>Accept</b> is {@link JdbcAssignmentStore#acceptOffer}, the exact primitive automated
 *       sourcing and the dispatch board's manual assign already use, so a courier tapping accept
 *       one second after an operator clicks assign loses the race the same honest way a second
 *       dispatcher would. The plan then moves to {@code ASSIGNED} the way
 *       {@link ManualDispatchService#assign} moves it; the sourcing job that was waiting for the
 *       offer to lapse finds a settled plan when it wakes and completes without asking anyone.</li>
 *   <li><b>Decline</b> closes the offer and pulls the plan's waiting job forward to now. Without
 *       that the next courier is asked when the declined offer would have lapsed, which is the
 *       difference between a decline being useful and a decline being a formality. Sourcing never
 *       re-offers an order to a courier it already asked, so the declined courier is not asked
 *       twice.</li>
 *   <li><b>Every change publishes an ADR 0045 {@code DISPATCH_BOARD} signal</b> after commit, so a
 *       dispatcher sees the order change hands without waiting for the board's poll. Fire and
 *       forget, like every signal: it never fails the courier's tap.</li>
 * </ul>
 *
 * <p><b>Not here: the order.</b> A shipment reaching {@code PICKED_UP} or {@code DELIVERED} does not
 * move the order to {@code FULFILLING} or {@code COMPLETED}. ADR 0125 leaves that to the operator
 * (or an automated rule) and records the accrual trigger as what <em>revisits</em> it; whether a
 * courier's tap should close the order is a decision about who owns the order's lifecycle, not an
 * enforcement point for a courier policy switch, and it is not made by building it here.
 */
@Service
public class CourierJobsService implements CourierJobsPort {

    private static final Logger log = LoggerFactory.getLogger(CourierJobsService.class);

    /** The ADR 0029 purpose stated by the decrypt that measures how far a courier is from a door. */
    static final String PROXIMITY_PURPOSE = "COURIER_PROXIMITY_CHECK";

    private final JdbcCourierJobStore store;
    private final JdbcAssignmentStore assignments;
    private final JdbcDeliveryPlanStore plans;
    private final JdbcSourcingJobStore sourcingJobs;
    private final JdbcDispatchBranchStore branches;
    private final DeliveryOrderPort orders;
    private final RealtimeSignalPublisher realtime;
    private final Clock clock;

    public CourierJobsService(
            JdbcCourierJobStore store,
            JdbcAssignmentStore assignments,
            JdbcDeliveryPlanStore plans,
            JdbcSourcingJobStore sourcingJobs,
            JdbcDispatchBranchStore branches,
            DeliveryOrderPort orders,
            RealtimeSignalPublisher realtime,
            Clock clock) {
        this.store = store;
        this.assignments = assignments;
        this.plans = plans;
        this.sourcingJobs = sourcingJobs;
        this.branches = branches;
        this.orders = orders;
        this.realtime = realtime;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ offers

    @Override
    @Transactional(readOnly = true)
    public List<Offer> openOffers(UUID tenantId, UUID courierId, Instant now) {
        return store.openOffers(tenantId, courierId, now);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Offer> offer(UUID tenantId, UUID courierId, UUID offerId, Instant now) {
        return store.offer(tenantId, courierId, offerId, now);
    }

    @Override
    @Transactional
    public Optional<UUID> accept(UUID tenantId, UUID courierId, UUID offerId, Instant now) {
        Optional<Offer> held = store.offer(tenantId, courierId, offerId, now);
        if (held.isEmpty()) {
            return Optional.empty();
        }
        Offer offer = held.get();

        Optional<UUID> shipment = assignments.acceptOffer(tenantId, offerId, courierId, now);
        if (shipment.isEmpty()) {
            return Optional.empty();
        }

        // The same move ManualDispatchService#assign makes, with the same tolerance: the shipment is
        // the fact that matters and is already written, so a plan whose status column is one step
        // behind a concurrent write is left for the sourcing tick that wakes to settle.
        plans.settle(tenantId, offer.planId(), PlanStatus.ASSIGNED, now);
        signalDispatchBoardChanged(tenantId, offer.locationId(), offer.planId());
        return shipment;
    }

    @Override
    @Transactional
    public boolean decline(UUID tenantId, UUID courierId, UUID offerId, Instant now) {
        Optional<Offer> held = store.offer(tenantId, courierId, offerId, now);
        if (held.isEmpty() || !store.decline(tenantId, courierId, offerId, now)) {
            return false;
        }
        Offer offer = held.get();
        // Only a job nobody is holding is moved (JdbcSourcingJobStore#moveDueTime). One a worker
        // has leased is mid-tick and will see the declined attempt when it reads its progress.
        sourcingJobs.moveDueTime(tenantId, offer.planId(), now, now);
        signalDispatchBoardChanged(tenantId, offer.locationId(), offer.planId());
        return true;
    }

    // -------------------------------------------------------------------- jobs

    @Override
    @Transactional(readOnly = true)
    public List<Job> jobs(UUID tenantId, UUID courierId) {
        return store.activeJobs(tenantId, courierId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Job> job(UUID tenantId, UUID courierId, UUID shipmentId) {
        return store.job(tenantId, courierId, shipmentId);
    }

    @Override
    @Transactional
    public Transition advance(UUID tenantId, UUID courierId, UUID shipmentId, Step to, Instant now) {
        Optional<Job> found = store.job(tenantId, courierId, shipmentId);
        if (found.isEmpty()) {
            return new Transition(Result.NOT_FOUND, null);
        }
        Job before = found.get();
        if (before.status().name().equals(to.name())) {
            return new Transition(Result.ALREADY_THERE, before);
        }
        if (!store.advance(tenantId, courierId, shipmentId, to, now)) {
            // Lost a race with another writer of the same shipment, or asked for a step the shipment
            // cannot take from where it is. Re-read so the caller is told where it actually stands.
            Job current = store.job(tenantId, courierId, shipmentId).orElse(before);
            return new Transition(
                    current.status().name().equals(to.name()) ? Result.ALREADY_THERE : Result.NOT_ALLOWED, current);
        }
        Job after = store.job(tenantId, courierId, shipmentId).orElse(before);
        signalDispatchBoardChanged(tenantId, after.locationId(), after.planId());
        return new Transition(Result.APPLIED, after);
    }

    @Override
    @Transactional
    public Optional<Job> confirmPayment(
            UUID tenantId, UUID courierId, UUID shipmentId, long collectedMinor, String currency, Instant now) {
        Optional<Job> found = store.job(tenantId, courierId, shipmentId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        Job before = found.get();
        if (before.paymentConfirmedAt() != null) {
            return found;
        }
        if (!store.confirmPayment(tenantId, courierId, shipmentId, collectedMinor, currency, now)) {
            return Optional.empty();
        }
        Optional<Job> after = store.job(tenantId, courierId, shipmentId);
        after.ifPresent(job -> signalDispatchBoardChanged(tenantId, job.locationId(), job.planId()));
        return after;
    }

    // ---------------------------------------------------------------- distance

    @Override
    @Transactional(readOnly = true)
    public OptionalInt metresFromBranch(UUID tenantId, UUID brandId, UUID locationId, GeoPoint position) {
        Optional<DispatchBranch> branch = branches.find(tenantId, brandId, locationId);
        if (branch.isEmpty() || branch.get().latitude() == null || branch.get().longitude() == null) {
            return OptionalInt.empty();
        }
        GeoPoint origin = new GeoPoint(branch.get().latitude(), branch.get().longitude());
        return OptionalInt.of(Haversine.metersBetween(origin, position));
    }

    @Override
    @Transactional(readOnly = true)
    public OptionalInt metresFromDoor(UUID tenantId, UUID courierId, UUID shipmentId, GeoPoint position) {
        Optional<Job> found = store.job(tenantId, courierId, shipmentId);
        if (found.isEmpty()) {
            return OptionalInt.empty();
        }
        return orders.customerLocation(tenantId, found.get().orderId(), PROXIMITY_PURPOSE)
                .map(door -> OptionalInt.of(
                        Haversine.metersBetween(new GeoPoint(door.latitude(), door.longitude()), position)))
                .orElse(OptionalInt.empty());
    }

    // ------------------------------------------------------------------- doors

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerLocation> customerLocationOfOffer(
            UUID tenantId, UUID courierId, UUID offerId, Instant now, String purpose) {
        return store.offer(tenantId, courierId, offerId, now)
                .flatMap(offer -> orders.customerLocation(tenantId, offer.orderId(), purpose));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CustomerLocation> customerLocationOfJob(
            UUID tenantId, UUID courierId, UUID shipmentId, String purpose) {
        return store.job(tenantId, courierId, shipmentId)
                .filter(job -> job.status() == JobStatus.ASSIGNED
                        || job.status() == JobStatus.PICKUP_PENDING
                        || job.status() == JobStatus.PICKED_UP)
                .flatMap(job -> orders.customerLocation(tenantId, job.orderId(), purpose));
    }

    // ----------------------------------------------------------------- signals

    /**
     * Publishes the ADR 0045 {@code DISPATCH_BOARD} signal for one plan, deferred past this
     * transaction's commit when one is open, so a board that re-reads on the strength of the signal
     * never sees the row before the write that produced it.
     *
     * <p>Outside a real transaction (a unit test constructing this class with {@code new}) it
     * publishes immediately rather than losing the signal to a callback nothing invokes.
     */
    private void signalDispatchBoardChanged(UUID tenantId, UUID locationId, UUID planId) {
        Instant now = clock.instant();
        Runnable publish = () -> {
            try {
                long version =
                        plans.find(tenantId, planId).map(DeliveryPlan::version).orElse(0);
                realtime.publish(RealtimeSignal.of(
                        tenantId,
                        StreamChannel.DISPATCH_BOARD,
                        ScopeKey.location(locationId),
                        "DeliveryPlan",
                        planId,
                        version,
                        now));
            } catch (RuntimeException failure) {
                log.warn("The dispatch board signal for plan {} could not be published", planId, failure);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    publish.run();
                }
            });
        } else {
            publish.run();
        }
    }
}
