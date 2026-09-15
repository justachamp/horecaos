package uz.horecaos.platform.fulfillment.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourceType;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore.Shipment;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryCostSubsidyStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryExceptionStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryExceptionStore.OpenException;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;
import uz.horecaos.platform.iam.api.Capability;
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
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders/{orderId}")
@Tag(name = "Order delivery", description = "The order-keyed read of one order's delivery plan, shipment and money")
public class OrderDeliveryController {

    private final JdbcDeliveryPlanStore plans;
    private final JdbcAssignmentStore assignments;
    private final JdbcDeliveryCostSubsidyStore subsidies;
    private final JdbcDeliveryExceptionStore exceptions;

    public OrderDeliveryController(
            JdbcDeliveryPlanStore plans,
            JdbcAssignmentStore assignments,
            JdbcDeliveryCostSubsidyStore subsidies,
            JdbcDeliveryExceptionStore exceptions) {
        this.plans = plans;
        this.assignments = assignments;
        this.subsidies = subsidies;
        this.exceptions = exceptions;
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

    // --------------------------------------------------------------- payloads

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
