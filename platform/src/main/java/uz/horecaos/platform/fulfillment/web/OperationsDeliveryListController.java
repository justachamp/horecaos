package uz.horecaos.platform.fulfillment.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.fulfillment.api.DeliveryMoneyPort.Cost;
import uz.horecaos.platform.fulfillment.api.DeliveryMoneyPort.DeliveryMoney;
import uz.horecaos.platform.fulfillment.application.DeliveryListService;
import uz.horecaos.platform.fulfillment.application.DeliveryListService.DeliveryRow;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryListStore.Row;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;

/**
 * The deliveries list (ADR 0164 slice two, gap map row 3.1b): every delivery plan a tenant has, with history.
 *
 * <p>The dispatch board's queue is a branch's open plans. This is the record behind it, for a courier-service
 * tenant whose work is the deliveries themselves: one row per plan, newest first, filterable by branch, courier,
 * state and date, keyset-paged.
 *
 * <p><strong>Its scope follows its filters</strong> (ADR 0144) and so is authorised in the method rather than
 * declared once: a {@code locationId} (with its {@code brandId}) needs {@code delivery.plan.read} at that branch,
 * a {@code brandId} alone at the brand, neither at the tenant. A dispatcher who holds the capability at one
 * branch reads that branch's deliveries and is refused the tenant's, which is the console's cue to filter.
 *
 * <p>A row carries no recipient: not a name, not a telephone number, not an address. The order detail pane's own
 * authorised reveal is the only way to those (ADR 0029). It is a read, so it takes no {@code Idempotency-Key}.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}")
@Tag(name = "Deliveries", description = "Every delivery a tenant has had, for a courier-service tenant (ADR 0164)")
public class OperationsDeliveryListController {

    private final DeliveryListService deliveries;
    private final AuthorizationService authorization;
    private final CurrentActor currentActor;

    public OperationsDeliveryListController(
            DeliveryListService deliveries, AuthorizationService authorization, CurrentActor currentActor) {
        this.deliveries = deliveries;
        this.authorization = authorization;
        this.currentActor = currentActor;
    }

    @GetMapping("/deliveries")
    @Operation(
            operationId = "listDeliveries",
            summary = "Every delivery, with history: one row per plan",
            description = "Newest first. Filters: brandId and locationId (repeat locationId for several "
                    + "branches; it needs the brandId it belongs to), status (plan states: PLANNED, "
                    + "WAITING_TO_SOURCE, SOURCING, BOOKING, RETRY_PENDING, SCHEDULED, ASSIGNED, IN_PROGRESS, "
                    + "COMPLETED, MANUAL_ACTION_REQUIRED, CANCELLED; repeat for several), courierId (the "
                    + "in-house courier carrying it), from and to (when the order was confirmed; to is "
                    + "exclusive). Requires delivery.plan.read at the scope the filters name: a locationId "
                    + "at that branch, a brandId at that brand, neither at the tenant. Keyset-paginated "
                    + "(ADR 0031); changing a filter invalidates the cursor. A row has the order reference, "
                    + "the branch, the plan and shipment states, who carries it (an in-house courier by id, "
                    + "or the partner), the non-PII destination label, the promise, when it was picked up "
                    + "and delivered, the order total, the cash to collect and the newest cost line with "
                    + "its basis (ACCRUED, INVOICED or SETTLED; never summed across bases). It never "
                    + "carries a recipient's name, telephone number or address.")
    @SuppressWarnings("checkstyle:ParameterNumber")
    public Page<DeliveryResponse> list(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @Nullable UUID brandId,
            @RequestParam(required = false) @Nullable List<UUID> locationId,
            @RequestParam(required = false) @Nullable List<String> status,
            @RequestParam(required = false) @Nullable UUID courierId,
            @RequestParam(required = false) @Nullable Instant from,
            @RequestParam(required = false) @Nullable Instant to,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {

        List<UUID> branches = locationId == null ? List.of() : locationId;
        authorization.require(
                currentActor.get().subject(), Capability.DELIVERY_PLAN_READ, scopeOf(tenantId, brandId, branches));

        Page<DeliveryRow> page = deliveries.list(
                tenantId, brandId, branches, status == null ? List.of() : status, courierId, from, to, cursor, limit);
        return new Page<>(page.items().stream().map(DeliveryResponse::of).toList(), page.nextCursor());
    }

    private static ResourceScope scopeOf(UUID tenantId, @Nullable UUID brandId, List<UUID> locationIds) {
        if (locationIds.isEmpty()) {
            return brandId != null ? ResourceScope.brand(tenantId, brandId) : ResourceScope.tenant(tenantId);
        }
        if (brandId == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "locationId needs the brandId it belongs to");
        }
        // One branch is authorised at that branch; several are a brand-wide question.
        return locationIds.size() == 1
                ? ResourceScope.location(tenantId, brandId, locationIds.getFirst())
                : ResourceScope.brand(tenantId, brandId);
    }

    /**
     * @param courierId       the in-house courier carrying it; null for a partner or an unassigned plan
     * @param providerType    the partner carrying it; null for an in-house courier or an unassigned plan
     * @param cashToCollectMinor what the courier is to collect at the door; null when no shipment exists yet
     * @param cost            the newest live cost line with its basis, or null before any is recognised
     */
    record DeliveryResponse(
            UUID planId,
            UUID orderId,
            String orderReference,
            UUID brandId,
            UUID locationId,
            String branchName,
            String planStatus,
            @Nullable UUID shipmentId,
            @Nullable String shipmentStatus,
            @Nullable String sourceType,
            @Nullable UUID courierId,
            @Nullable String providerType,
            @Nullable String destinationLabel,
            @Nullable Integer distanceMeters,
            Instant confirmedAt,
            @Nullable Instant promisedDeliveryStart,
            @Nullable Instant promisedDeliveryEnd,
            @Nullable Instant pickedUpAt,
            @Nullable Instant deliveredAt,
            long orderTotalMinor,
            String currency,
            boolean prepaid,
            @Nullable Long cashToCollectMinor,
            @Nullable CostResponse cost) {

        static DeliveryResponse of(DeliveryRow item) {
            Row row = item.row();
            DeliveryMoney money = item.money();
            return new DeliveryResponse(
                    row.planId(),
                    row.orderId(),
                    row.orderReference(),
                    row.brandId(),
                    row.locationId(),
                    row.branchName(),
                    row.planStatus(),
                    row.shipmentId(),
                    row.shipmentStatus(),
                    row.sourceType(),
                    row.courierId(),
                    row.providerType(),
                    row.destinationLabel(),
                    row.distanceMeters(),
                    row.confirmedAt(),
                    row.promisedDeliveryStart(),
                    row.promisedDeliveryEnd(),
                    row.pickedUpAt(),
                    row.deliveredAt(),
                    row.orderTotalMinor(),
                    row.currency(),
                    row.prepaid(),
                    money == null ? null : money.cashToCollectMinor(),
                    money == null || money.cost() == null ? null : CostResponse.of(money.cost()));
        }
    }

    /** One cost line at its basis; the amount is in minor units of its own currency. */
    record CostResponse(String basis, String path, long amountMinor, String currency) {

        static CostResponse of(Cost cost) {
            return new CostResponse(cost.basis(), cost.path(), cost.amountMinor(), cost.currency());
        }
    }
}
