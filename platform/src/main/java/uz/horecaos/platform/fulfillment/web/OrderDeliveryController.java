package uz.horecaos.platform.fulfillment.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
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
import uz.horecaos.platform.fulfillment.application.ManualExternalBookingService;
import uz.horecaos.platform.fulfillment.application.ManualExternalBookingService.BookOutcome;
import uz.horecaos.platform.fulfillment.application.ManualExternalBookingService.Decision;
import uz.horecaos.platform.fulfillment.application.ManualExternalBookingService.QuoteResult;
import uz.horecaos.platform.fulfillment.application.ServiceZoneService.DeliveryResourceNotFoundException;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryQuote;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourceType;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore.Shipment;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryCostSubsidyStore;
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
 * The order-to-fulfilment seam (ADR 0014, gap map rows 1.2e/1.2n): one
 * order-keyed read that maps an order id to its delivery plan, its current
 * shipment, and both delivery money figures.
 *
 * <p>Nothing mapped an order id to these facts before this wave. {@code
 * DispatchController}'s own queue is keyed by {@code planId}, deliberately
 * carries no customer-facing field, and excludes {@code COMPLETED}/{@code
 * CANCELLED} plans (its own class doc explains why); the order detail pane
 * holds only an order id and needs the plan whether the order finished or
 * not. This controller is the seam: the order detail's Money panel joins the
 * customer's snapshotted fee against what the winning provider actually
 * billed ({@code fulfillment.delivery_cost_subsidies}, written only when the
 * two disagree), and the assign/unassign control on the detail pane reuses
 * {@code DispatchController}'s own {@code /dispatch/plans/{planId}/assign}
 * and {@code /unassign} once it has the {@code planId} and versions this read
 * returns.
 *
 * <p>Deliberately no customer name or address here either — the same
 * {@code DispatchController} decision, for the same reason.
 *
 * <p>Gap map rows 1.2e/2.1c: {@link #externalCourier} is the same order-keyed
 * seam extended to the Millenium pattern's own quote/accept services
 * ({@link ManualExternalBookingService}, gap map row 1.2f) — one path the
 * order detail pane and the KDS pass both call, resolving the plan from
 * {@code orderId} here instead of each screen resolving {@code planId} its
 * own way (the order detail pane from this class's own {@link #delivery}
 * read; the KDS pass, before this, only through {@code DispatchController}'s
 * whole branch queue joined client-side by {@code orderId}).
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders/{orderId}")
@Tag(name = "Order delivery", description = "The order-keyed read of one order's delivery plan, shipment and money")
public class OrderDeliveryController {

    private final JdbcDeliveryPlanStore plans;
    private final JdbcAssignmentStore assignments;
    private final JdbcDeliveryCostSubsidyStore subsidies;
    private final JdbcDeliveryExceptionStore exceptions;
    private final ManualExternalBookingService externalBooking;
    private final CurrentActor currentActor;

    public OrderDeliveryController(
            JdbcDeliveryPlanStore plans,
            JdbcAssignmentStore assignments,
            JdbcDeliveryCostSubsidyStore subsidies,
            JdbcDeliveryExceptionStore exceptions,
            ManualExternalBookingService externalBooking,
            CurrentActor currentActor) {
        this.plans = plans;
        this.assignments = assignments;
        this.subsidies = subsidies;
        this.exceptions = exceptions;
        this.externalBooking = externalBooking;
        this.currentActor = currentActor;
    }

    @GetMapping("/delivery")
    @RequiresCapability(value = Capability.DELIVERY_PLAN_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "This order's delivery plan, current shipment and both money figures",
            description = "Not found for an order fulfilled some other way -- pickup, dine-in, or "
                    + "one whose delivery plan was cancelled outright. `providerCostMinor` is set "
                    + "only when fulfillment.delivery_cost_subsidies recognised a gap between the "
                    + "customer's fee and what the winning partner billed; a provider-fulfilled "
                    + "order that came in at or under the customer's fee has no such row, and this "
                    + "read says so honestly rather than fabricate a figure nothing recorded.")
    public ResponseEntity<OrderDeliveryResponse> delivery(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId) {

        DeliveryPlan plan = plans.findByOrder(tenantId, orderId)
                .filter(found -> found.locationId().equals(locationId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such delivery plan"));

        Optional<Shipment> shipment = assignments.findShipment(tenantId, plan.id());
        Long providerCostMinor = shipment.filter(carried -> carried.sourceType() == SourceType.PARTNER)
                .flatMap(carried -> subsidies.findByShipment(tenantId, carried.id()))
                .map(JdbcDeliveryCostSubsidyStore.Row::providerCostMinor)
                .orElse(null);
        Instant courierEtaAt = plans.courierEtaByOrder(tenantId, orderId).orElse(null);
        List<OpenException> openExceptions = exceptions.open(tenantId, plan.id());

        return ResponseEntity.ok(
                OrderDeliveryResponse.of(plan, shipment.orElse(null), providerCostMinor, courierEtaAt, openExceptions));
    }

    @PostMapping("/external-courier")
    @RequiresCapability(value = Capability.DELIVERY_MANUAL_ASSIGN, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Request an external courier for this order (gap map rows 1.2e/2.1c)",
            description = "One order-keyed action over the Millenium pattern's own quote/accept "
                    + "services (ManualExternalBookingService, gap map row 1.2f), so the order "
                    + "detail pane and the KDS pass share one path instead of each resolving "
                    + "planId a different way. Omit quoteId to price one partner (phase QUOTED); "
                    + "once QUOTED, call again with that quoteId and a decision to accept or "
                    + "abandon it (phase BOOKED). The price booked is always the one "
                    + "fulfillment.delivery_quotes recorded under quoteId, never a figure this "
                    + "request carries.")
    public ResponseEntity<ExternalCourierResponse> externalCourier(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            @Valid @RequestBody ExternalCourierRequest body) {
        try {
            DeliveryPlan plan = plans.findByOrder(tenantId, orderId)
                    .filter(found -> found.locationId().equals(locationId))
                    .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such delivery plan"));

            if (body.quoteId() == null) {
                QuoteResult result = externalBooking.quote(tenantId, brandId, locationId, plan.id(), body.bindingId());
                return ResponseEntity.ok(ExternalCourierResponse.quoted(result));
            }
            if (body.decision() == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "decision is required once quoteId is set");
            }
            if (body.reasonCode() == null || body.reasonCode().isBlank()) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "reasonCode is required once quoteId is set");
            }
            BookOutcome outcome = externalBooking.book(
                    tenantId,
                    brandId,
                    locationId,
                    plan.id(),
                    body.bindingId(),
                    body.quoteId(),
                    body.decision(),
                    body.reasonCode(),
                    actor());
            return ResponseEntity.ok(ExternalCourierResponse.booked(outcome));
        } catch (DeliveryResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    // --------------------------------------------------------------- payloads

    /**
     * {@code reasonCode} is required only once {@code quoteId} is set (the
     * BOOK phase) -- validated in the controller body, not by {@code
     * @NotBlank} here, because the QUOTE phase (no {@code quoteId}) is priced
     * before any reason for booking exists, and every real frontend caller
     * (the order detail pane and the KDS pass, via {@code
     * OrderDeliveryApi.requestExternalCourierQuote}) omits it on that call.
     * Mirrors {@code DispatchController}'s own split
     * {@code ExternalQuoteRequest}/{@code ExternalBookRequest}.
     */
    public record ExternalCourierRequest(
            @NotNull UUID bindingId,
            @Nullable UUID quoteId,
            @Nullable Decision decision,
            @Nullable @Size(max = 64) String reasonCode) {}

    /**
     * @param phase  {@code QUOTED} when this call priced a partner; {@code
     *               BOOKED} when it settled a decision on a quote already
     *               recorded. Exactly one of {@link #quoted}/{@link #booked}
     *               is set, matching {@code phase}.
     */
    public record ExternalCourierResponse(
            String phase,
            @Nullable QuotedState quoted,
            @Nullable BookedState booked) {

        static ExternalCourierResponse quoted(QuoteResult result) {
            DeliveryQuote quote = result.quote();
            Long priceMinor = quote == null ? null : quote.priceMinor();
            QuotedState state = new QuotedState(
                    result.priced(),
                    quote == null ? null : quote.id(),
                    result.providerType(),
                    priceMinor,
                    quote == null ? null : quote.currency(),
                    result.customerFeeMinor(),
                    priceMinor == null ? null : priceMinor - result.customerFeeMinor(),
                    result.failureCode());
            return new ExternalCourierResponse("QUOTED", state, null);
        }

        static ExternalCourierResponse booked(BookOutcome outcome) {
            BookedState state = new BookedState(
                    outcome.applied(),
                    outcome.abandoned(),
                    outcome.planVersion(),
                    outcome.shipmentId(),
                    outcome.reason());
            return new ExternalCourierResponse("BOOKED", null, state);
        }

        /** Mirrors {@code DispatchController.ExternalQuoteResponse} — see that record's own doc for each field. */
        public record QuotedState(
                boolean priced,
                @Nullable UUID quoteId,
                @Nullable String providerType,
                @Nullable Long priceMinor,
                @Nullable String currency,
                long customerDeliveryFeeMinor,
                @Nullable Long deltaMinor,
                @Nullable String failureCode) {}

        /** Mirrors {@code DispatchController.ExternalBookResponse} — see that record's own doc for each field. */
        public record BookedState(
                boolean applied,
                boolean abandoned,
                @Nullable Integer planVersion,
                @Nullable UUID shipmentId,
                @Nullable String reason) {}
    }

    public record OrderDeliveryResponse(
            UUID planId,
            int planVersion,
            String planStatus,
            Instant estimatedReadyAt,
            @Nullable Instant promisedDeliveryStart,
            @Nullable Instant promisedDeliveryEnd,
            long customerDeliveryFeeMinor,
            @Nullable Long providerCostMinor,
            String currency,
            @Nullable Instant courierEtaAt,
            @Nullable ShipmentResponse shipment,
            List<DeliveryExceptionResponse> exceptions) {

        static OrderDeliveryResponse of(
                DeliveryPlan plan,
                @Nullable Shipment shipment,
                @Nullable Long providerCostMinor,
                @Nullable Instant courierEtaAt,
                List<OpenException> exceptions) {
            return new OrderDeliveryResponse(
                    plan.id(),
                    plan.version(),
                    plan.status().name(),
                    plan.pickup().estimatedReadyAt(),
                    plan.promisedDeliveryStart(),
                    plan.promisedDeliveryEnd(),
                    plan.customerDeliveryFeeMinor(),
                    providerCostMinor,
                    plan.currency(),
                    courierEtaAt,
                    shipment == null ? null : ShipmentResponse.of(shipment),
                    exceptions.stream().map(DeliveryExceptionResponse::of).toList());
        }
    }

    /**
     * The delivery-exception band (gap map rows 1.2f/1.2g): every open ADR
     * 0014 sourcing or cancellation exception against this order's plan,
     * mirroring {@code DispatchController.ExceptionResponse} — a separate
     * record because {@code IdempotentResponseClassificationTests} requires
     * unique OpenAPI schema names across the two controllers' response trees.
     * Never a customer name, address or phone, for the same reason the class
     * doc above gives.
     */
    public record DeliveryExceptionResponse(
            UUID exceptionId,
            String reasonCode,
            String severity,
            String status,
            @Nullable String detail,
            Instant raisedAt) {

        static DeliveryExceptionResponse of(OpenException exception) {
            return new DeliveryExceptionResponse(
                    exception.id(),
                    exception.reasonCode(),
                    exception.severity(),
                    exception.status(),
                    exception.detail(),
                    exception.raisedAt());
        }
    }

    /** Mirrors {@code DispatchController.ShipmentView} plus the three V0054 timestamps that controller never serialised. */
    public record ShipmentResponse(
            UUID shipmentId,
            String status,
            String sourceType,
            @Nullable UUID courierId,
            @Nullable UUID providerBindingId,
            @Nullable Instant assignedAt,
            @Nullable Instant pickedUpAt,
            @Nullable Instant deliveredAt,
            int version) {

        static ShipmentResponse of(Shipment shipment) {
            return new ShipmentResponse(
                    shipment.id(),
                    shipment.status().name(),
                    shipment.sourceType().name(),
                    shipment.courierId(),
                    shipment.providerBindingId(),
                    shipment.assignedAt(),
                    shipment.pickedUpAt(),
                    shipment.deliveredAt(),
                    shipment.version());
        }
    }
}
