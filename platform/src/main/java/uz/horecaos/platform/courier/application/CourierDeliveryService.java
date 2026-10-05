package uz.horecaos.platform.courier.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.courier.domain.CourierCompensationPolicy;
import uz.horecaos.platform.courier.domain.DeliveryGate;
import uz.horecaos.platform.courier.domain.DeliveryGate.Stage;
import uz.horecaos.platform.courier.domain.GateRefusal;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort.Job;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort.JobStatus;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort.Offer;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort.Result;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort.Step;
import uz.horecaos.platform.fulfillment.api.CourierJobsPort.Transition;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort.CustomerLocation;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.payments.api.CashDueLookupPort;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The courier app's offers and deliveries, held to the courier policy
 * (ADR 0014, ADR 0042, couriers.md §16, gap map row 3.9).
 *
 * <p>This is the enforcement point four of the courier-policy switches never had. Each is
 * a decision about what a courier may do <em>at the moment they do it</em>, so each can only
 * be enforced where a courier does something: accepting an offer, moving a delivery along,
 * opening the customer's door, finishing. {@code fulfillment} owns the offers, the shipments
 * and the customer's address and answers facts about them through {@link CourierJobsPort};
 * this class owns what those facts permit. It never reads another module's tables, and it
 * never sees a coordinate it did not receive from the courier's own handset.
 *
 * <ul>
 *   <li><b>GPS master toggle and the two radii.</b> With the toggle on, accepting an offer
 *       is measured against the branch within the accept radius, and arriving, collecting
 *       and handing over are measured within the status-change radius of the branch or the
 *       customer's door. Off, nothing is measured and the position is not even read.</li>
 *   <li><b>Kitchen-ready-only.</b> The order must be {@code READY} or later, both to be
 *       accepted and, in the courier's list, to be seen.</li>
 *   <li><b>Reveal timing.</b> A courier who holds only an offer opens the customer's door
 *       only when the tenant reveals it before acceptance; one who holds the delivery always
 *       may. Every reveal is an audit fact naming the courier, the order and the offer or
 *       shipment, and never the address.</li>
 *   <li><b>Post-delivery payment check.</b> With cash due and the check on, the delivery is
 *       not marked delivered until the courier has stated the cash they collected and it
 *       equals what the settlement says was due.</li>
 * </ul>
 *
 * <p><strong>The position is a measurement, not a record.</strong> It is turned into a
 * distance, compared with a radius and discarded: no coordinate of the courier is stored,
 * logged, audited or put in an error. ADR 0045's track is the one place a courier's movement
 * is kept, inside a duty session, and this path neither writes to it nor reads from it.
 *
 * <p><strong>A refusal here never tells a courier anything about anyone else.</strong> Every
 * lookup is by the caller's own courier id, so an offer or delivery that belongs to somebody
 * else is answered exactly as one that does not exist, and the gates below run only after
 * ownership is established.
 */
@Service
public class CourierDeliveryService {

    /** The ADR 0029 purpose a reveal states while the courier still only holds an offer. */
    static final String PURPOSE_OFFER = "COURIER_LOCATION_BEFORE_ACCEPT";

    /** The ADR 0029 purpose a reveal states when made by the courier carrying the delivery. */
    static final String PURPOSE_DELIVERY = "COURIER_LOCATION_ASSIGNED_DELIVERY";

    private final CourierJobsPort jobs;
    private final CourierPolicyResolver policies;
    private final JdbcCourierStore couriers;
    private final CashDueLookupPort cashDue;
    private final AuditRecorder audit;
    private final Clock clock;

    public CourierDeliveryService(
            CourierJobsPort jobs,
            CourierPolicyResolver policies,
            JdbcCourierStore couriers,
            CashDueLookupPort cashDue,
            AuditRecorder audit,
            Clock clock) {
        this.jobs = jobs;
        this.policies = policies;
        this.couriers = couriers;
        this.cashDue = cashDue;
        this.audit = audit;
        this.clock = clock;
    }

    /** Which branch's policy a call is held to. A courier works one branch at a time. */
    public record Where(UUID tenantId, UUID brandId, UUID locationId) {

        ResourceScope scope() {
            return ResourceScope.location(tenantId, brandId, locationId);
        }
    }

    /**
     * Where the courier's handset says it is, as measured at one moment.
     *
     * @param accuracyMeters the handset's own error circle for the fix
     */
    public record Position(GeoPoint point, double accuracyMeters) {

        /** Never printed: a courier's location is personal data. */
        @Override
        public String toString() {
            return "Position[REDACTED]";
        }
    }

    /** An offer as the courier sees it, with the policy facts the app needs to draw it. */
    public record OfferView(Offer offer, boolean customerLocationRevealable) {}

    /** A delivery as the courier sees it. */
    public record DeliveryView(Job job, long cashDueMinor, boolean paymentConfirmationRequired) {}

    /**
     * What taking an offer came to.
     *
     * @param delivery the delivery the courier now carries, or null when {@code accepted} is false
     */
    public record AcceptOutcome(boolean accepted, @Nullable DeliveryView delivery) {

        static AcceptOutcome gone() {
            return new AcceptOutcome(false, null);
        }
    }

    // ------------------------------------------------------------------ offers

    /**
     * The offers this courier could take at this branch, soonest to lapse first.
     *
     * <p>With kitchen-ready-only on, an order the kitchen has not finished is not in the list at
     * all: the setting's own sentence is that the courier "sees and can take only" ready orders,
     * and a list that showed them would invite a tap the policy then has to refuse.
     */
    @Transactional(readOnly = true)
    public List<OfferView> offers(Where where, UUID courierId) {
        CourierCompensationPolicy policy = policies.resolve(where.scope());
        boolean revealable = DeliveryGate.revealBeforeAccept(policy).isEmpty();
        return jobs.openOffers(where.tenantId(), courierId, clock.instant()).stream()
                .filter(offer -> sameBranch(where, offer.brandId(), offer.locationId()))
                .filter(offer ->
                        DeliveryGate.kitchen(policy, offer.kitchenReady()).isEmpty())
                .map(offer -> new OfferView(offer, revealable))
                .toList();
    }

    /**
     * Takes an offer.
     *
     * <p>A courier who is too slow, or who was never offered this, gets {@link AcceptOutcome#gone()}
     * and not an error: "somebody else took it" is the ordinary result of the second of two taps
     * (ADR 0014), and answering a stranger's offer id differently from a lapsed one would tell
     * them which offers exist. The gates run only for an offer the courier really holds.
     */
    @Transactional
    public AcceptOutcome accept(
            Where where,
            UUID courierId,
            UUID offerId,
            long expectedVersion,
            @Nullable Position position,
            ActorRef actor) {

        Instant now = clock.instant();
        Optional<Offer> held = jobs.offer(where.tenantId(), courierId, offerId, now)
                .filter(offer -> sameBranch(where, offer.brandId(), offer.locationId()));
        if (held.isEmpty()) {
            return AcceptOutcome.gone();
        }
        Offer offer = held.get();
        requireVersion(expectedVersion, offer.version());
        CourierCompensationPolicy policy = policies.resolve(where.scope());

        requireEligibleForNewWork(where.tenantId(), courierId);
        refuse(DeliveryGate.kitchen(policy, offer.kitchenReady()));
        refuse(DeliveryGate.gps(
                policy,
                Stage.ACCEPT_OFFER,
                position != null,
                position == null ? null : position.accuracyMeters(),
                measure(
                        policy,
                        position,
                        point -> jobs.metresFromBranch(where.tenantId(), offer.brandId(), offer.locationId(), point))));

        Optional<UUID> shipment = jobs.accept(where.tenantId(), courierId, offerId, now);
        if (shipment.isEmpty()) {
            return AcceptOutcome.gone();
        }

        audit.record(AuditFact.of("courier.offer.accepted", AuditClass.BUSINESS)
                .by(actor)
                .at(where.scope())
                .target("fulfillment.shipment", shipment.get())
                .because("Courier accepted the offer")
                .usingCapability(Capability.COURIER_OFFER_ACCEPT.code())
                .changed(ChangeDocuments.diff(
                        Map.of("offerStatus", "OFFERED"),
                        Map.of(
                                "offerStatus", "ACCEPTED",
                                "courierId", courierId.toString(),
                                "orderId", offer.orderId().toString(),
                                "offerId", offerId.toString())))
                .correlatedBy(offer.planId().toString())
                .occurredAt(now)
                .build());

        DeliveryView delivery = jobs.job(where.tenantId(), courierId, shipment.get())
                .map(job -> view(where, policy, job))
                .orElseThrow(() -> new IllegalStateException("The shipment just won has vanished"));
        return new AcceptOutcome(true, delivery);
    }

    /**
     * Turns an offer down.
     *
     * @return false when the offer is no longer there to decline, which a client renders as it
     *         renders a lapsed one
     */
    @Transactional
    public boolean decline(Where where, UUID courierId, UUID offerId, long expectedVersion, ActorRef actor) {
        Instant now = clock.instant();
        Optional<Offer> held = jobs.offer(where.tenantId(), courierId, offerId, now)
                .filter(offer -> sameBranch(where, offer.brandId(), offer.locationId()));
        if (held.isEmpty()) {
            return false;
        }
        Offer offer = held.get();
        requireVersion(expectedVersion, offer.version());
        if (!jobs.decline(where.tenantId(), courierId, offerId, now)) {
            return false;
        }
        audit.record(AuditFact.of("courier.offer.declined", AuditClass.BUSINESS)
                .by(actor)
                .at(where.scope())
                .target("fulfillment.delivery_plan", offer.planId())
                .because("Courier declined the offer")
                .usingCapability(Capability.COURIER_OFFER_DECLINE.code())
                .changed(ChangeDocuments.diff(
                        Map.of("offerStatus", "OFFERED"),
                        Map.of(
                                "offerStatus", "DECLINED",
                                "courierId", courierId.toString(),
                                "orderId", offer.orderId().toString(),
                                "offerId", offerId.toString())))
                .correlatedBy(offer.planId().toString())
                .occurredAt(now)
                .build());
        return true;
    }

    // -------------------------------------------------------------- deliveries

    /** What this courier is carrying at this branch: assigned, waiting at the pickup, or on the road. */
    @Transactional(readOnly = true)
    public List<DeliveryView> deliveries(Where where, UUID courierId) {
        CourierCompensationPolicy policy = policies.resolve(where.scope());
        return jobs.jobs(where.tenantId(), courierId).stream()
                .filter(job -> sameBranch(where, job.brandId(), job.locationId()))
                .map(job -> view(where, policy, job))
                .toList();
    }

    /** One of this courier's deliveries in any status, or a 404 for one that is not theirs. */
    @Transactional(readOnly = true)
    public DeliveryView delivery(Where where, UUID courierId, UUID shipmentId) {
        Job job = requireJob(where, courierId, shipmentId);
        return view(where, policies.resolve(where.scope()), job);
    }

    /**
     * Moves a delivery one step, carrying the courier's own position.
     *
     * <p>Order of checks: ownership (a 404), the version the caller read (a 409), whether the
     * shipment can take the step at all (so a courier told "not from here" is not first told
     * to move closer), then the policy gates, and only then the compare-and-set. The position
     * is measured only if the GPS toggle is on; the customer's door is decrypted only for the
     * handover step and only then.
     */
    @Transactional
    public DeliveryView advance(
            Where where,
            UUID courierId,
            UUID shipmentId,
            long expectedVersion,
            Step step,
            @Nullable Position position,
            ActorRef actor) {

        Job job = requireJob(where, courierId, shipmentId);
        requireVersion(expectedVersion, job.version());
        CourierCompensationPolicy policy = policies.resolve(where.scope());

        if (job.status().name().equals(step.name())) {
            return view(where, policy, job);
        }
        if (!step.canFollow(job.status())) {
            throw refusal(GateRefusal.STEP_NOT_ALLOWED);
        }

        Stage stage = step == Step.DELIVERED ? Stage.AT_DROPOFF : Stage.AT_PICKUP;
        refuse(DeliveryGate.gps(
                policy,
                stage,
                position != null,
                position == null ? null : position.accuracyMeters(),
                measure(
                        policy,
                        position,
                        point -> stage == Stage.AT_DROPOFF
                                ? jobs.metresFromDoor(where.tenantId(), courierId, shipmentId, point)
                                : jobs.metresFromBranch(where.tenantId(), job.brandId(), job.locationId(), point))));

        if (step == Step.DELIVERED) {
            refuse(DeliveryGate.paymentCheck(policy, cashDueMinor(where, job), job.paymentConfirmedAt() != null));
        }

        Instant now = clock.instant();
        Transition transition = jobs.advance(where.tenantId(), courierId, shipmentId, step, now);
        Job after = transition.job();
        if (transition.result() == Result.NOT_FOUND || after == null) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such delivery of yours");
        }
        if (transition.result() == Result.NOT_ALLOWED) {
            throw refusal(GateRefusal.STEP_NOT_ALLOWED);
        }
        if (transition.result() == Result.APPLIED) {
            audit.record(AuditFact.of("courier.delivery.advanced", AuditClass.BUSINESS)
                    .by(actor)
                    .at(where.scope())
                    .target("fulfillment.shipment", shipmentId)
                    .because("Courier moved the delivery to " + step.name())
                    .usingCapability(Capability.COURIER_DELIVERY_ADVANCE.code())
                    .changed(ChangeDocuments.diff(
                            Map.of("status", job.status().name()),
                            Map.of(
                                    "status", step.name(),
                                    "courierId", courierId.toString(),
                                    "orderId", job.orderId().toString(),
                                    // Whether the gate measured the courier, never where they were.
                                    "positionChecked", DeliveryGate.needsPosition(policy))))
                    .correlatedBy(job.planId().toString())
                    .occurredAt(now)
                    .build());
        }
        return view(where, policy, after);
    }

    /**
     * Records the cash the courier collected at the door, once.
     *
     * <p>Held to the settlement's own figure: a courier cannot confirm a number the settlement
     * does not say is due, because the check exists to make them count and say so, and a check
     * that accepts any number only records that someone tapped.
     */
    @Transactional
    public DeliveryView confirmPayment(
            Where where, UUID courierId, UUID shipmentId, long expectedVersion, long collectedMinor, ActorRef actor) {

        Job job = requireJob(where, courierId, shipmentId);
        requireVersion(expectedVersion, job.version());
        CourierCompensationPolicy policy = policies.resolve(where.scope());

        if (job.paymentConfirmedAt() != null) {
            return view(where, policy, job);
        }
        if (job.status() != JobStatus.PICKED_UP) {
            throw refusal(GateRefusal.PAYMENT_NOT_CONFIRMABLE);
        }
        if (collectedMinor != cashDueMinor(where, job)) {
            throw refusal(GateRefusal.PAYMENT_AMOUNT_MISMATCH);
        }

        Instant now = clock.instant();
        Job after = jobs.confirmPayment(where.tenantId(), courierId, shipmentId, collectedMinor, job.currency(), now)
                .orElseThrow(() -> refusal(GateRefusal.PAYMENT_NOT_CONFIRMABLE));

        audit.record(AuditFact.of("courier.delivery.payment_confirmed", AuditClass.BUSINESS)
                .by(actor)
                .at(where.scope())
                .target("fulfillment.shipment", shipmentId)
                .because("Courier confirmed the cash collected at the door")
                .usingCapability(Capability.COURIER_DELIVERY_PAYMENT_CONFIRM.code())
                .changed(ChangeDocuments.diff(
                        Map.of("paymentConfirmed", false),
                        Map.of(
                                "paymentConfirmed", true,
                                "courierId", courierId.toString(),
                                "orderId", job.orderId().toString(),
                                "collectedMinor", collectedMinor,
                                "currency", job.currency())))
                .correlatedBy(job.planId().toString())
                .occurredAt(now)
                .build());
        return view(where, policy, after);
    }

    // ------------------------------------------------------------------ reveal

    /**
     * Opens the customer's door for a courier who holds only an offer, if the tenant's policy
     * reveals it that early.
     */
    @Transactional
    public CustomerLocation revealForOffer(Where where, UUID courierId, UUID offerId, ActorRef actor) {
        Instant now = clock.instant();
        Offer offer = jobs.offer(where.tenantId(), courierId, offerId, now)
                .filter(held -> sameBranch(where, held.brandId(), held.locationId()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such offer of yours"));
        CourierCompensationPolicy policy = policies.resolve(where.scope());
        refuse(DeliveryGate.revealBeforeAccept(policy));

        auditReveal(
                where,
                courierId,
                actor,
                "fulfillment.assignment_attempt",
                offerId,
                offer.orderId(),
                "OFFER",
                policy,
                now);
        return jobs.customerLocationOfOffer(where.tenantId(), courierId, offerId, now, PURPOSE_OFFER)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "This order has no address to show"));
    }

    /** Opens the customer's door for the courier carrying the delivery. */
    @Transactional
    public CustomerLocation revealForDelivery(Where where, UUID courierId, UUID shipmentId, ActorRef actor) {
        Job job = requireJob(where, courierId, shipmentId);
        CourierCompensationPolicy policy = policies.resolve(where.scope());
        Instant now = clock.instant();

        auditReveal(
                where, courierId, actor, "fulfillment.shipment", shipmentId, job.orderId(), "DELIVERY", policy, now);
        // A delivered or cancelled shipment is refused by the port: a courier who has finished with
        // an order has no further business knowing where its customer lives.
        return jobs.customerLocationOfJob(where.tenantId(), courierId, shipmentId, PURPOSE_DELIVERY)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "This delivery has no address to show"));
    }

    /**
     * The audit fact is written before the address is read, in the same transaction, so a reveal
     * that happened cannot be missing its record. It names who, which order and which offer or
     * shipment; it carries no part of the address.
     */
    private void auditReveal(
            Where where,
            UUID courierId,
            ActorRef actor,
            String targetType,
            UUID targetId,
            UUID orderId,
            String stage,
            CourierCompensationPolicy policy,
            Instant now) {
        audit.record(AuditFact.of("courier.customer_location.revealed", AuditClass.SECURITY)
                .by(actor)
                .at(where.scope())
                .target(targetType, targetId)
                .because("Courier opened the customer's location (" + stage + ")")
                .usingCapability(Capability.COURIER_DELIVERY_LOCATION_REVEAL.code())
                .changed(ChangeDocuments.created(Map.of(
                        "courierId", courierId.toString(),
                        "orderId", orderId.toString(),
                        "stage", stage,
                        "revealTimingPolicy",
                                policy.revealCustomerLocationTiming().name())))
                .correlatedBy(orderId.toString())
                .occurredAt(now)
                .build());
    }

    // ----------------------------------------------------------------- helpers

    private DeliveryView view(Where where, CourierCompensationPolicy policy, Job job) {
        long due = cashDueMinor(where, job);
        return new DeliveryView(job, due, DeliveryGate.paymentConfirmationRequired(policy, due));
    }

    /**
     * What the courier is to collect at the door. Nothing for an order the platform already holds
     * the money for. When no settlement exists the order total stands in, the same fallback
     * {@code DeliveryAccrualOrderCompletionTrigger} makes: a courier is never told "nothing is due"
     * because a lookup found nothing.
     *
     * <p>The fallback is read from an answer, not caught from a throw. The lookup is a transactional
     * bean method, and one that throws into this request's transaction marks it rollback-only
     * before a {@code catch} here could run: the accept, the advance and the delivery list would
     * each have rolled back their own work and answered 500. Any failure the lookup cannot answer
     * (the database itself) is a real failure and propagates.
     */
    private long cashDueMinor(Where where, Job job) {
        if (job.prepaid()) {
            return 0L;
        }
        return Math.max(
                0L,
                cashDue.cashDueMinorIfSettled(where.tenantId(), job.orderId()).orElse(job.orderTotalMinor()));
    }

    /**
     * Measures only if the policy needs a measurement and the courier supplied a position. Both
     * conditions matter: with the toggle off nothing is read (least of all a decrypted door), and
     * with no position there is nothing to measure.
     */
    private static OptionalInt measure(
            CourierCompensationPolicy policy,
            @Nullable Position position,
            Function<GeoPoint, OptionalInt> measurement) {
        if (!DeliveryGate.needsPosition(policy) || position == null) {
            return OptionalInt.empty();
        }
        return measurement.apply(position.point());
    }

    private Job requireJob(Where where, UUID courierId, UUID shipmentId) {
        return jobs.job(where.tenantId(), courierId, shipmentId)
                .filter(job -> sameBranch(where, job.brandId(), job.locationId()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such delivery of yours"));
    }

    private void requireEligibleForNewWork(UUID tenantId, UUID courierId) {
        boolean active = couriers.findCourier(tenantId, courierId)
                .map(courier -> "ACTIVE".equals(courier.status()))
                .orElse(false);
        boolean engaged = couriers.findLiveEngagement(tenantId, courierId)
                .map(engagement -> engagement.status().dispatchable())
                .orElse(false);
        if (!active || !engaged) {
            // The compliance lever: new work stops, work already accepted still finishes.
            throw refusal(GateRefusal.COURIER_NOT_ELIGIBLE);
        }
    }

    private static boolean sameBranch(Where where, UUID brandId, UUID locationId) {
        return where.brandId().equals(brandId) && where.locationId().equals(locationId);
    }

    private static void requireVersion(long expected, long actual) {
        if (expected != actual) {
            throw ApiException.staleVersion(expected, actual);
        }
    }

    private static void refuse(Optional<GateRefusal> refusal) {
        refusal.ifPresent(reason -> {
            throw refusal(reason);
        });
    }

    /** The stable problem a gate answers with: {@code 422 UNPROCESSABLE_STATE} carrying {@code reason}. */
    static ApiException refusal(GateRefusal reason) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("reason", reason.name());
        return new ApiException(ErrorCode.UNPROCESSABLE_STATE, describe(reason), properties);
    }

    private static String describe(GateRefusal reason) {
        return switch (reason) {
            case GPS_POSITION_REQUIRED ->
                "This branch checks where couriers are, and no position came with the request";
            case GPS_ACCURACY_INSUFFICIENT -> "The position is too imprecise to show you are close enough";
            case GPS_REFERENCE_UNAVAILABLE -> "There is no place on record to measure your position against";
            case TOO_FAR_FROM_PICKUP -> "You are too far from the branch for this step";
            case TOO_FAR_FROM_DROPOFF -> "You are too far from the delivery address for this step";
            case KITCHEN_NOT_READY -> "The kitchen has not finished this order yet";
            case PAYMENT_CONFIRMATION_REQUIRED -> "Confirm the cash you collected before finishing this delivery";
            case PAYMENT_AMOUNT_MISMATCH -> "The amount you confirmed is not the amount due";
            case LOCATION_NOT_YET_REVEALED -> "The customer's address is shown only after you accept";
            case COURIER_NOT_ELIGIBLE -> "Your engagement does not allow taking new work right now";
            case STEP_NOT_ALLOWED -> "This delivery cannot take that step from where it is";
            case PAYMENT_NOT_CONFIRMABLE -> "The cash for this delivery cannot be confirmed right now";
        };
    }
}
