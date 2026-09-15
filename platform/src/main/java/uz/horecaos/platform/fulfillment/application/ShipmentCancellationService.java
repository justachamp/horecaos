package uz.horecaos.platform.fulfillment.application;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.CancelCommand;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.CancellationReceipt;
import uz.horecaos.platform.fulfillment.api.ShipmentCancellationPort;
import uz.horecaos.platform.fulfillment.api.ShipmentCancellationPort.Outcome;
import uz.horecaos.platform.fulfillment.api.ShipmentCancellationPort.Result;
import uz.horecaos.platform.fulfillment.application.ServiceZoneService.DeliveryResourceNotFoundException;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryExceptionReason;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.PlanStatus;
import uz.horecaos.platform.fulfillment.domain.sourcing.ShipmentStatus;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourceType;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore.Shipment;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.telemetry.api.RealtimeSignal;
import uz.horecaos.platform.telemetry.api.RealtimeSignalPublisher;
import uz.horecaos.platform.telemetry.api.ScopeKey;
import uz.horecaos.platform.telemetry.api.StreamChannel;

/**
 * Cascading cancel at the provider (ADR 0014 line 547; gap map row 1.2g).
 *
 * <p>Before this class, an operator cancelling a dispatched order could not
 * see whether the courier provider was ever told — the {@code Shipment} row
 * simply sat there, and a Noor or Yandex courier already en route stayed en
 * route. Two callers reach it: {@link #cancelForOrder} (the {@link
 * ShipmentCancellationPort} ordering calls after its own cancellation
 * commits) and {@link #cancelShipment}, the dedicated {@code
 * Capability.SHIPMENT_CANCEL} operator action ADR 0014 names at line 547 and
 * still lists missing at line 639 — distinct from {@link
 * ManualDispatchService#unassign}, which only ever moves local rows and never
 * tells a {@code PARTNER} shipment's provider anything.
 *
 * <p><b>Never wrapped in {@code @Transactional}.</b> A {@code PARTNER}
 * shipment's cancel is a network call to a courier partner, and holding a
 * pooled database connection across a call this class does not control is
 * exactly what {@code ExternalCallTransactionBoundaryTests} exists to catch
 * elsewhere in the codebase — the same reason {@link DeliverySourcingService
 * #execute} is not transactional either. Every write below is a single
 * autocommitted statement, individually safe to lose to a crash between two of
 * them: a shipment that stays {@code ASSIGNED} after a plan already moved to
 * {@code MANUAL_ACTION_REQUIRED} is a state an operator can read and finish by
 * hand, which is the same best-effort contract {@link
 * DeliverySourcingService#recordSubsidyIfAny} already keeps.
 *
 * <p>An {@code INTERNAL} shipment is cancelled locally only — telling the
 * courier is ADR 0042's own concern, not this cascade's; a {@code PARTNER}
 * shipment is told before anything local moves, because a shipment marked
 * cancelled here and never told to the courier is exactly the gap this class
 * exists to close. A partner answer this class cannot confirm as cancelled
 * (UNCERTAIN, REJECTED, RETRYABLE) never marks the shipment cancelled locally
 * either — it raises a {@code fulfillment.delivery_exceptions} row and leaves
 * the plan {@code MANUAL_ACTION_REQUIRED} instead, the same honest "a human
 * owns this now" ADR 0014 already uses for a booking sourcing could not settle.
 */
@Service
public class ShipmentCancellationService implements ShipmentCancellationPort {

    private static final Logger log = LoggerFactory.getLogger(ShipmentCancellationService.class);

    private final JdbcDeliveryPlanStore plans;
    private final JdbcAssignmentStore assignments;
    private final ShipmentBookingPort bookings;
    private final SourcingJournal journal;
    private final AuditRecorder audit;
    private final RealtimeSignalPublisher realtime;
    private final Clock clock;

    public ShipmentCancellationService(
            JdbcDeliveryPlanStore plans,
            JdbcAssignmentStore assignments,
            ShipmentBookingPort bookings,
            SourcingJournal journal,
            AuditRecorder audit,
            RealtimeSignalPublisher realtime,
            Clock clock) {
        this.plans = plans;
        this.assignments = assignments;
        this.bookings = bookings;
        this.journal = journal;
        this.audit = audit;
        this.realtime = realtime;
        this.clock = clock;
    }

    /**
     * Cascades an already-cancelled order onto its open delivery plan, if it
     * has one.
     *
     * <p>Called after the order's own cancellation has committed — never from
     * inside that transaction; see the class doc. A best-effort side effect of
     * a decision already made: whatever this returns, the order stays
     * cancelled, and a provider outcome this call could not confirm is a
     * fulfilment-side exception for an operator, never a reason to undo the
     * cancellation.
     */
    @Override
    public Outcome cancelForOrder(
            UUID tenantId, UUID brandId, UUID locationId, UUID orderId, String reasonCode, ActorRef actor) {

        Instant now = clock.instant();
        Optional<DeliveryPlan> found = plans.findByOrder(tenantId, orderId);
        if (found.isEmpty()) {
            // Pickup, dine-in, or a plan already cancelled some other way. Not
            // an anomaly -- most cancelled orders never had a courier at all.
            return new Outcome(Result.NOTHING_TO_CANCEL, null);
        }
        DeliveryPlan plan = found.get();
        if (plan.status() == PlanStatus.COMPLETED) {
            // The delivery already finished; cancelling the order now is a
            // write-off/refund decision the outcome reason already recorded,
            // not a fulfilment one -- nothing here to tell a provider.
            return new Outcome(Result.NOTHING_TO_CANCEL, null);
        }

        Optional<Shipment> shipment = assignments.findShipment(tenantId, plan.id());
        if (shipment.isEmpty()) {
            boolean moved = plans.transition(tenantId, plan.id(), plan.status(), PlanStatus.CANCELLED, now);
            if (moved) {
                recordAudit(
                        tenantId, brandId, locationId, plan.id(), actor, reasonCode, Result.PLAN_CANCELLED, null, now);
                signal(tenantId, locationId, plan.id(), plan.version() + 1, now);
            }
            return new Outcome(Result.PLAN_CANCELLED, null);
        }

        return applyCascade(tenantId, brandId, locationId, plan, shipment.get(), reasonCode, actor, now);
    }

    /**
     * The dedicated, provider-notifying shipment cancel — {@code
     * Capability.SHIPMENT_CANCEL}, operator-triggered from the dispatch board
     * or the order detail pane, independent of the order's own status.
     */
    public ShipmentCancelOutcome cancelShipment(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID shipmentId,
            int expectedShipmentVersion,
            String reasonCode,
            ActorRef actor) {

        Instant now = clock.instant();
        Shipment shipment = assignments
                .findShipmentById(tenantId, shipmentId)
                .orElseThrow(() -> new DeliveryResourceNotFoundException("No shipment " + shipmentId));

        if (shipment.status() == ShipmentStatus.CANCELLED || shipment.status() == ShipmentStatus.DELIVERED) {
            return ShipmentCancelOutcome.conflict("ALREADY_" + shipment.status().name());
        }
        if (shipment.version() != expectedShipmentVersion) {
            return ShipmentCancelOutcome.conflict("STALE_VERSION");
        }

        DeliveryPlan plan = plans.findByOrder(tenantId, shipment.orderId())
                .orElseThrow(
                        () -> new DeliveryResourceNotFoundException("No delivery plan for shipment " + shipmentId));
        if (!plan.locationId().equals(locationId)) {
            throw new DeliveryResourceNotFoundException("No shipment " + shipmentId + " at this location");
        }

        Outcome outcome = applyCascade(tenantId, brandId, locationId, plan, shipment, reasonCode, actor, now);
        return ShipmentCancelOutcome.applied(outcome);
    }

    // ------------------------------------------------------------- the effect

    private Outcome applyCascade(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            DeliveryPlan plan,
            Shipment shipment,
            String reasonCode,
            ActorRef actor,
            Instant now) {

        if (shipment.sourceType() == SourceType.INTERNAL) {
            assignments.cancelActiveShipment(tenantId, shipment.id(), reasonCode, now);
            boolean moved = plans.transition(tenantId, plan.id(), plan.status(), PlanStatus.CANCELLED, now);
            recordAudit(
                    tenantId, brandId, locationId, plan.id(), actor, reasonCode, Result.INTERNAL_CANCELLED, null, now);
            signal(tenantId, locationId, plan.id(), moved ? plan.version() + 1 : plan.version(), now);
            return new Outcome(Result.INTERNAL_CANCELLED, null);
        }

        String externalReference = shipment.externalShipmentId();
        UUID bindingId = shipment.providerBindingId();
        if (externalReference == null || externalReference.isBlank() || bindingId == null) {
            // A PARTNER shipment with no reference to cancel by should not exist
            // -- win() always records one -- but an honest UNCERTAIN and a human
            // is the only safe answer to a row that contradicts its own invariant.
            return escalate(
                    tenantId,
                    brandId,
                    locationId,
                    plan,
                    actor,
                    reasonCode,
                    shipment.providerType(),
                    now,
                    DeliveryExceptionReason.PROVIDER_CANCEL_UNCERTAIN,
                    Result.PROVIDER_UNCERTAIN,
                    "shipment " + shipment.id() + " has no provider reference to cancel by");
        }

        CancellationReceipt receipt = bookings.cancel(new CancelCommand(
                Ids.newId(),
                tenantId,
                brandId,
                locationId,
                bindingId,
                externalReference,
                reasonCode,
                plan.id().toString()));

        return switch (receipt.status()) {
            case CANCELLED, CANCELLED_WITH_COST -> {
                assignments.cancelActiveShipment(tenantId, shipment.id(), reasonCode, now);
                boolean moved = plans.transition(tenantId, plan.id(), plan.status(), PlanStatus.CANCELLED, now);
                Result result = receipt.status() == ShipmentBookingPort.CancellationStatus.CANCELLED
                        ? Result.PROVIDER_CANCELLED
                        : Result.PROVIDER_CANCELLED_CHARGEABLE;
                recordAudit(
                        tenantId,
                        brandId,
                        locationId,
                        plan.id(),
                        actor,
                        reasonCode,
                        result,
                        receipt.providerType(),
                        now);
                signal(tenantId, locationId, plan.id(), moved ? plan.version() + 1 : plan.version(), now);
                yield new Outcome(result, receipt.providerType());
            }
            case UNCERTAIN ->
                escalate(
                        tenantId,
                        brandId,
                        locationId,
                        plan,
                        actor,
                        reasonCode,
                        receipt.providerType(),
                        now,
                        DeliveryExceptionReason.PROVIDER_CANCEL_UNCERTAIN,
                        Result.PROVIDER_UNCERTAIN,
                        "the cancel for shipment " + shipment.id() + " could not be confirmed");
            case REJECTED, RETRYABLE ->
                escalate(
                        tenantId,
                        brandId,
                        locationId,
                        plan,
                        actor,
                        reasonCode,
                        receipt.providerType(),
                        now,
                        DeliveryExceptionReason.PROVIDER_CANCEL_FAILED,
                        Result.PROVIDER_FAILED,
                        "the provider refused or could not be reached to cancel shipment " + shipment.id());
        };
    }

    private Outcome escalate(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            DeliveryPlan plan,
            ActorRef actor,
            String reasonCode,
            @Nullable String providerType,
            Instant now,
            String exceptionReason,
            Result result,
            String detail) {

        journal.raiseException(tenantId, brandId, locationId, plan.id(), exceptionReason, detail, now);
        boolean moved = plans.transition(tenantId, plan.id(), plan.status(), PlanStatus.MANUAL_ACTION_REQUIRED, now);
        recordAudit(tenantId, brandId, locationId, plan.id(), actor, reasonCode, result, providerType, now);
        signal(tenantId, locationId, plan.id(), moved ? plan.version() + 1 : plan.version(), now);
        log.warn("Plan {} needs manual action after a cancel that could not be confirmed: {}", plan.id(), detail);
        return new Outcome(result, providerType);
    }

    private void recordAudit(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID planId,
            ActorRef actor,
            String reasonCode,
            Result result,
            @Nullable String providerType,
            Instant now) {

        Map<String, Object> changed = new LinkedHashMap<>();
        changed.put("outcome", result.name());
        if (providerType != null) {
            changed.put("providerType", providerType);
        }
        audit.record(AuditFact.of("fulfillment.shipment.cascade-cancel", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.location(tenantId, brandId, locationId))
                .target("fulfillment.delivery_plan", planId)
                .because(reasonCode)
                .changed(changed)
                .correlatedBy(planId.toString())
                .occurredAt(now)
                .build());
    }

    /** Mirrors {@code ManualDispatchService}'s own ADR 0045 board push, and the same immediate-outside-a-transaction fallback. */
    private void signal(UUID tenantId, UUID locationId, UUID planId, int version, Instant now) {
        realtime.publish(RealtimeSignal.of(
                tenantId,
                StreamChannel.DISPATCH_BOARD,
                ScopeKey.location(locationId),
                "DeliveryPlan",
                planId,
                (long) version,
                now));
    }

    // --------------------------------------------------------------- results

    /**
     * @param applied        false on a conflict this call refused to act on —
     *                       a stale shipment version or one already settled
     * @param outcome        present only when {@code applied}
     * @param conflictReason present only when {@code !applied}: {@code
     *                       STALE_VERSION}, {@code ALREADY_CANCELLED} or {@code
     *                       ALREADY_DELIVERED}
     */
    public record ShipmentCancelOutcome(
            boolean applied,
            @Nullable Outcome outcome,
            @Nullable String conflictReason) {

        static ShipmentCancelOutcome applied(Outcome outcome) {
            return new ShipmentCancelOutcome(true, outcome, null);
        }

        static ShipmentCancelOutcome conflict(String reason) {
            return new ShipmentCancelOutcome(false, null, reason);
        }
    }
}
