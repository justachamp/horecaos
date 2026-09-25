package uz.horecaos.platform.fulfillment.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.PartnerOption;
import uz.horecaos.platform.fulfillment.application.ManualDispatchService;
import uz.horecaos.platform.fulfillment.application.ManualDispatchService.DispatchOutcome;
import uz.horecaos.platform.fulfillment.application.ManualExternalBookingService;
import uz.horecaos.platform.fulfillment.application.ManualExternalBookingService.BookOutcome;
import uz.horecaos.platform.fulfillment.application.ManualExternalBookingService.Decision;
import uz.horecaos.platform.fulfillment.application.ManualExternalBookingService.QuoteResult;
import uz.horecaos.platform.fulfillment.application.ServiceZoneService.DeliveryResourceNotFoundException;
import uz.horecaos.platform.fulfillment.application.ShipmentCancellationService;
import uz.horecaos.platform.fulfillment.application.ShipmentCancellationService.ShipmentCancelOutcome;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryQuote;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore.Shipment;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryExceptionStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryExceptionStore.OpenException;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The dispatch board (ADR 0014, operations §3.1): the queue, and manual
 * assign/unassign.
 *
 * <p>On the ADR 0031 {@code /api/v1/operations/**} prefix — new code, unlike
 * the kitchen board's own controllers which predate that convention (see
 * {@code KitchenBoardController}'s own doc on why it still sits on the legacy
 * shape). Both prefixes land in the same {@code operations} OpenAPI surface
 * group regardless (ADR 0057).
 *
 * <p>The queue is deliberately thin: plan-level facts only — distance, fee, the
 * timing model, who (if anyone) is carrying it. It carries no customer name,
 * no address, and no order total, for the same reason {@code
 * KitchenBoardController.ItemView} carries no dish name: those live on the
 * ADR 0019 order snapshot, which has one authority, and this board's caller
 * already holds the order board's own read (§1.1) to join against by {@code
 * orderId} — exactly the seam the kitchen board already established.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/dispatch")
@Tag(name = "Dispatch board", description = "The delivery queue, and manual assign/unassign")
public class DispatchController {

    private static final int QUEUE_LIMIT = 200;

    private final JdbcDeliveryPlanStore plans;
    private final JdbcAssignmentStore assignments;
    private final JdbcDeliveryExceptionStore exceptions;
    private final ManualDispatchService dispatch;
    private final ManualExternalBookingService externalBooking;
    private final ShipmentCancellationService cancellation;
    private final CurrentActor currentActor;

    public DispatchController(
            JdbcDeliveryPlanStore plans,
            JdbcAssignmentStore assignments,
            JdbcDeliveryExceptionStore exceptions,
            ManualDispatchService dispatch,
            ManualExternalBookingService externalBooking,
            ShipmentCancellationService cancellation,
            CurrentActor currentActor) {
        this.plans = plans;
        this.assignments = assignments;
        this.exceptions = exceptions;
        this.dispatch = dispatch;
        this.externalBooking = externalBooking;
        this.cancellation = cancellation;
        this.currentActor = currentActor;
    }

    @GetMapping("/queue")
    @RequiresCapability(value = Capability.DELIVERY_PLAN_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Every plan this branch has open",
            description = "Unassigned and assigned alike, soonest source-at first. COMPLETED and "
                    + "CANCELLED plans are the only ones excluded.")
    public ResponseEntity<List<PlanQueueResponse>> queue(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {

        List<DeliveryPlan> open = plans.listActiveByLocation(tenantId, locationId, QUEUE_LIMIT);
        Map<UUID, Shipment> shipments = assignments.shipmentsByPlans(
                tenantId, open.stream().map(DeliveryPlan::id).toList());

        return ResponseEntity.ok(open.stream()
                .map(plan -> PlanQueueResponse.of(plan, shipments.get(plan.id())))
                .toList());
    }

    @PostMapping("/plans/{planId}/assign")
    @RequiresCapability(value = Capability.DELIVERY_MANUAL_ASSIGN, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Assign one courier to one plan",
            description = "Idempotent and audited (§3.1): a second identical click settles once. "
                    + "Refused as a conflict, never an error, when the plan is already carried.")
    public ResponseEntity<DispatchResponse> assign(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID planId,
            @Valid @RequestBody AssignRequest body) {
        try {
            DispatchOutcome outcome = dispatch.assign(
                    tenantId, planId, body.courierId(), body.expectedVersion(), body.reasonCode(), actor());
            return ResponseEntity.ok(DispatchResponse.of(outcome));
        } catch (DeliveryResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    @PostMapping("/plans/{planId}/unassign")
    @RequiresCapability(value = Capability.DELIVERY_MANUAL_ASSIGN, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Unassign the courier currently carrying this plan",
            description = "Returns the plan to the sourcing pool. Refused once the shipment has "
                    + "moved past PICKUP_PENDING — a courier already holding the food is not "
                    + "unassigned out from under them.")
    public ResponseEntity<DispatchResponse> unassign(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID planId,
            @Valid @RequestBody UnassignRequest body) {
        try {
            DispatchOutcome outcome =
                    dispatch.unassign(tenantId, planId, body.expectedShipmentVersion(), body.reasonCode(), actor());
            return ResponseEntity.ok(DispatchResponse.of(outcome));
        } catch (DeliveryResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    @GetMapping("/plans/{planId}/exceptions")
    @RequiresCapability(value = Capability.DELIVERY_PLAN_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Why this plan needs a human",
            description = "Every open ADR 0014 sourcing exception against this plan -- no provider, "
                    + "a booking whose outcome is still uncertain, a promise sourcing could not "
                    + "meet. Empty for a plan sourcing has not flagged, which is the ordinary case.")
    public ResponseEntity<List<ExceptionResponse>> exceptions(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID planId) {

        return ResponseEntity.ok(exceptions.open(tenantId, planId).stream()
                .map(ExceptionResponse::of)
                .toList());
    }

    @GetMapping("/plans/{planId}/external-partners")
    @RequiresCapability(value = Capability.DELIVERY_PLAN_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Every external courier partner configured for this branch",
            description = "The picker behind «call an external courier» (gap map row 1.2f). Empty is "
                    + "an ordinary answer for a tenant running an in-house fleet only.")
    public ResponseEntity<List<ExternalPartnerResponse>> externalPartners(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return ResponseEntity.ok(externalBooking.partners(tenantId, brandId, locationId).stream()
                .map(ExternalPartnerResponse::of)
                .toList());
    }

    @PostMapping("/plans/{planId}/external-quote")
    @RequiresCapability(value = Capability.DELIVERY_MANUAL_ASSIGN, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "A non-binding price from one external courier partner, against the customer's fee",
            description = "The Millenium pattern (gap map row 1.2f): asks one partner what this "
                    + "journey would cost right now and records the answer as evidence the same way "
                    + "automated sourcing does. Never books anything -- the partner is only asked "
                    + "to create a live delivery once the operator calls external-book with ACCEPT.")
    public ResponseEntity<ExternalQuoteResponse> externalQuote(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID planId,
            @Valid @RequestBody ExternalQuoteRequest body) {
        try {
            QuoteResult result = externalBooking.quote(tenantId, brandId, locationId, planId, body.bindingId());
            return ResponseEntity.ok(ExternalQuoteResponse.of(result));
        } catch (DeliveryResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    @PostMapping("/plans/{planId}/external-book")
    @RequiresCapability(value = Capability.DELIVERY_MANUAL_ASSIGN, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Accept or abandon a quoted external booking",
            description = "The confirmation seam ADR 0014's alternatives table calls out by name: a "
                    + "re-quote above the customer's own delivery fee is never booked without this "
                    + "call, and the price booked is always the one fulfillment.delivery_quotes "
                    + "already recorded under quoteId -- never a figure the request body carries.")
    public ResponseEntity<ExternalBookResponse> externalBook(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID planId,
            @Valid @RequestBody ExternalBookRequest body) {
        try {
            BookOutcome outcome = externalBooking.book(
                    tenantId,
                    brandId,
                    locationId,
                    planId,
                    body.bindingId(),
                    body.quoteId(),
                    body.decision(),
                    body.reasonCode(),
                    actor());
            return ResponseEntity.ok(ExternalBookResponse.of(outcome));
        } catch (DeliveryResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    @PostMapping("/shipments/{shipmentId}/cancel")
    @RequiresCapability(value = Capability.SHIPMENT_CANCEL, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Cancel one shipment, telling the provider (ADR 0014, gap map row 1.2g)",
            description = "Distinct from unassign: a PARTNER shipment's provider is called and its "
                    + "answer classified into a free cancel, a chargeable one, or UNCERTAIN, which "
                    + "opens fulfillment.delivery_exceptions and leaves the plan MANUAL_ACTION_REQUIRED "
                    + "rather than guess. An INTERNAL shipment is cancelled locally only -- notifying "
                    + "the courier is ADR 0042's own concern.")
    public ResponseEntity<ShipmentCancelResponse> cancelShipment(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID shipmentId,
            @Valid @RequestBody ShipmentCancelRequest body) {
        try {
            ShipmentCancelOutcome outcome = cancellation.cancelShipment(
                    tenantId, brandId, locationId, shipmentId, body.expectedVersion(), body.reasonCode(), actor());
            return ResponseEntity.ok(ShipmentCancelResponse.of(outcome));
        } catch (DeliveryResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    // --------------------------------------------------------------- payloads

    public record AssignRequest(
            @NotNull UUID courierId,
            @NotNull Integer expectedVersion,
            @NotBlank @Size(max = 64) String reasonCode) {}

    public record UnassignRequest(
            @NotNull Integer expectedShipmentVersion,
            @NotBlank @Size(max = 64) String reasonCode) {}

    /**
     * One queue row. {@code shipment} is null for a plan nobody has taken yet;
     * see the class doc for why no order-facing field (name, address, total)
     * lives here — {@code destinationLabel} is the one deliberate exception
     * (row 3.1): a non-PII projection, never the address itself, safe for the
     * same 10-second poll this whole response answers.
     */
    public record PlanQueueResponse(
            UUID planId,
            UUID orderId,
            String status,
            @Nullable Integer distanceMeters,
            long customerDeliveryFeeMinor,
            String currency,
            Instant sourceAt,
            Instant estimatedReadyAt,
            @Nullable Instant promisedDeliveryStart,
            @Nullable Instant promisedDeliveryEnd,
            int version,
            @Nullable ShipmentView shipment,
            @Nullable String destinationLabel) {

        static PlanQueueResponse of(DeliveryPlan plan, @Nullable Shipment shipment) {
            return new PlanQueueResponse(
                    plan.id(),
                    plan.orderId(),
                    plan.status().name(),
                    plan.distanceMeters(),
                    plan.customerDeliveryFeeMinor(),
                    plan.currency(),
                    plan.pickup().sourceAt(),
                    plan.pickup().estimatedReadyAt(),
                    plan.promisedDeliveryStart(),
                    plan.promisedDeliveryEnd(),
                    plan.version(),
                    shipment == null ? null : ShipmentView.of(shipment),
                    plan.destinationLabel());
        }
    }

    public record ShipmentView(
            UUID shipmentId,
            String status,
            String sourceType,
            @Nullable UUID courierId,
            @Nullable UUID providerBindingId,
            int version) {

        static ShipmentView of(Shipment shipment) {
            return new ShipmentView(
                    shipment.id(),
                    shipment.status().name(),
                    shipment.sourceType().name(),
                    shipment.courierId(),
                    shipment.providerBindingId(),
                    shipment.version());
        }
    }

    /** One open ADR 0014 sourcing exception. Never a customer name, address or phone (§ class doc). */
    public record ExceptionResponse(
            UUID exceptionId,
            String reasonCode,
            String severity,
            String status,
            @Nullable String detail,
            Instant raisedAt) {

        static ExceptionResponse of(OpenException exception) {
            return new ExceptionResponse(
                    exception.id(),
                    exception.reasonCode(),
                    exception.severity(),
                    exception.status(),
                    exception.detail(),
                    exception.raisedAt());
        }
    }

    /** Mirrors the order board's own {@code DecisionResponse} shape: applied, or the settled state and why not. */
    public record DispatchResponse(
            boolean applied,
            String planStatus,
            int planVersion,
            @Nullable UUID shipmentId,
            @Nullable String reason) {

        static DispatchResponse of(DispatchOutcome outcome) {
            return new DispatchResponse(
                    outcome.applied(),
                    outcome.planStatus().name(),
                    outcome.planVersion(),
                    outcome.shipmentId(),
                    outcome.reason());
        }
    }

    /** One external courier partner this branch could call, from ADR 0026's own binding resolution. */
    public record ExternalPartnerResponse(UUID bindingId, String providerType, boolean supportsHold) {

        static ExternalPartnerResponse of(PartnerOption option) {
            return new ExternalPartnerResponse(option.bindingId(), option.providerType(), option.supportsHold());
        }
    }

    public record ExternalQuoteRequest(@NotNull UUID bindingId) {}

    /**
     * @param priced             false when the partner had nothing to say (out
     *                           of zone, an outage); every field below it is
     *                           then meaningless and {@code failureCode} says why
     * @param deltaMinor         {@code priceMinor - customerDeliveryFeeMinor}.
     *                           Positive is the price increase the dialog exists
     *                           to show; zero or negative needs no confirmation
     */
    public record ExternalQuoteResponse(
            boolean priced,
            @Nullable UUID quoteId,
            @Nullable UUID bindingId,
            @Nullable String providerType,
            @Nullable Long priceMinor,
            @Nullable String currency,
            long customerDeliveryFeeMinor,
            @Nullable Long deltaMinor,
            @Nullable String failureCode) {

        static ExternalQuoteResponse of(QuoteResult result) {
            DeliveryQuote quote = result.quote();
            if (!result.priced() || quote == null) {
                return new ExternalQuoteResponse(
                        false, null, null, result.providerType(), null, null, 0, null, result.failureCode());
            }
            Long priceMinor = quote.priceMinor();
            return new ExternalQuoteResponse(
                    true,
                    quote.id(),
                    quote.bindingId(),
                    quote.providerType(),
                    priceMinor,
                    quote.currency(),
                    result.customerFeeMinor(),
                    priceMinor == null ? null : priceMinor - result.customerFeeMinor(),
                    null);
        }
    }

    public record ExternalBookRequest(
            @NotNull UUID bindingId,
            @NotNull UUID quoteId,
            @NotNull Decision decision,
            @NotBlank @Size(max = 64) String reasonCode) {}

    /**
     * @param applied   true for a booking that won the plan's shipment, and for
     *                  every {@code ABANDON} — abandoning always "applies", it
     *                  simply books nothing
     * @param abandoned true when the operator refused this quote rather than
     *                  accepted it
     * @param reason    present only on a refused {@code ACCEPT}: {@code
     *                  QUOTE_EXPIRED}, {@code ALREADY_ASSIGNED}, {@code
     *                  ALREADY_BEING_SOURCED}, or a booking status name
     */
    public record ExternalBookResponse(
            boolean applied,
            boolean abandoned,
            @Nullable Integer planVersion,
            @Nullable UUID shipmentId,
            @Nullable String reason) {

        static ExternalBookResponse of(BookOutcome outcome) {
            return new ExternalBookResponse(
                    outcome.applied(),
                    outcome.abandoned(),
                    outcome.planVersion(),
                    outcome.shipmentId(),
                    outcome.reason());
        }
    }

    public record ShipmentCancelRequest(
            @NotNull Integer expectedVersion,
            @NotBlank @Size(max = 64) String reasonCode) {}

    /**
     * @param outcome one of {@code ShipmentCancellationPort.Result}'s names
     *                when {@code applied}: {@code INTERNAL_CANCELLED}, {@code
     *                PROVIDER_CANCELLED}, {@code PROVIDER_CANCELLED_CHARGEABLE},
     *                {@code PROVIDER_UNCERTAIN} or {@code PROVIDER_FAILED}
     * @param conflictReason present only when {@code !applied}: {@code
     *                STALE_VERSION}, {@code ALREADY_CANCELLED} or {@code
     *                ALREADY_DELIVERED}
     */
    public record ShipmentCancelResponse(
            boolean applied,
            @Nullable String outcome,
            @Nullable String providerType,
            @Nullable String conflictReason) {

        static ShipmentCancelResponse of(ShipmentCancelOutcome shipmentOutcome) {
            var outcome = shipmentOutcome.outcome();
            if (!shipmentOutcome.applied() || outcome == null) {
                return new ShipmentCancelResponse(false, null, null, shipmentOutcome.conflictReason());
            }
            return new ShipmentCancelResponse(true, outcome.result().name(), outcome.providerType(), null);
        }
    }
}
