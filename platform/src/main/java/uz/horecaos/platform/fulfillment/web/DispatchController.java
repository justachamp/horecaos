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
import uz.horecaos.platform.fulfillment.application.ManualDispatchService;
import uz.horecaos.platform.fulfillment.application.ManualDispatchService.DispatchOutcome;
import uz.horecaos.platform.fulfillment.application.ServiceZoneService.DeliveryResourceNotFoundException;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore.Shipment;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;
import uz.horecaos.platform.iam.api.Capability;
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
    private final ManualDispatchService dispatch;

    public DispatchController(
            JdbcDeliveryPlanStore plans, JdbcAssignmentStore assignments, ManualDispatchService dispatch) {
        this.plans = plans;
        this.assignments = assignments;
        this.dispatch = dispatch;
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
            DispatchOutcome outcome =
                    dispatch.assign(tenantId, planId, body.courierId(), body.expectedVersion(), body.reasonCode());
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
                    dispatch.unassign(tenantId, planId, body.expectedShipmentVersion(), body.reasonCode());
            return ResponseEntity.ok(DispatchResponse.of(outcome));
        } catch (DeliveryResourceNotFoundException missing) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, missing.getMessage());
        }
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
     * lives here.
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
            @Nullable ShipmentView shipment) {

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
                    shipment == null ? null : ShipmentView.of(shipment));
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
}
