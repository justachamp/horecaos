package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
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
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.application.OrderQueryService;
import uz.horecaos.platform.ordering.application.ReorderPlanService;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A staff-facing read of one customer's order history (frontend information
 * architecture §5.2: "order history + reorder"), for the Customers section's
 * detail pane.
 *
 * <p>Deliberately its own controller rather than a new method on {@code
 * CustomerController}: the read is entirely {@link OrderQueryService#forCustomer},
 * which already exists for {@code StorefrontOrderingController}'s "my orders"
 * screen, and this class exists only to expose the identical query to a staff
 * capability instead of account ownership — the same split {@code
 * OperationsOrderController} and {@code StorefrontOrderingController} already
 * draw over the same order rows. Nothing here decrypts anything: this is a list,
 * and the customer grid's "never a list-wide decrypt" rule holds for an order
 * list about one customer just as much as it does for the customer grid itself.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/customers/{accountId}/orders")
@Tag(name = "Customer order history", description = "One customer's own orders, as staff may read them")
public class CustomerOrderHistoryController {

    private final OrderQueryService orderQuery;
    private final ReorderPlanService reorderPlans;

    public CustomerOrderHistoryController(OrderQueryService orderQuery, ReorderPlanService reorderPlans) {
        this.orderQuery = orderQuery;
        this.reorderPlans = reorderPlans;
    }

    @GetMapping
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "One customer's orders at this brand, newest first",
            description = "Cursor-paginated per ADR 0031, identical in shape to the storefront's "
                    + "own \"my orders\" read: pass the previous page's nextCursor, and a null "
                    + "nextCursor is the end. Carries what a list shows and nothing beneath it — "
                    + "open the order itself (GET .../locations/{locationId}/orders/{orderId}) "
                    + "for lines, modifiers, or the customer panel's reveal calls.")
    public Page<OrderSummaryResponse> listOrders(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID accountId,
            @RequestParam(required = false) @Schema(description = "The nextCursor of the previous page") UUID cursor,
            @RequestParam(required = false) Integer limit) {

        int pageSize = Page.limitOrDefault(limit);

        List<JdbcOrderStore.CustomerOrderRow> rows;
        try {
            rows = orderQuery.forCustomer(tenantId, brandId, accountId, cursor, pageSize);
        } catch (OrderQueryService.UnknownCursorException unusable) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, unusable.getMessage());
        }

        // A short page is the end of the collection — see StorefrontOrderingController#listOrders,
        // whose identical comment this mirrors alongside the identical query.
        String nextCursor = rows.size() < pageSize
                ? null
                : rows.get(rows.size() - 1).orderId().toString();

        return new Page<>(rows.stream().map(OrderSummaryResponse::of).toList(), nextCursor);
    }

    @GetMapping("/{orderId}/reorder")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Whether one of this customer's own orders can be ordered again, and with what",
            description = "The staff-capability twin of the storefront's own "
                    + "GET .../orders/{orderId}/reorder (ADR 0074) — identical read, identical "
                    + "answer shape, reached by a staff token instead of @CustomerOwned. Built for "
                    + "orders.md §5.3's «Повторить» on the New order screen's phone-lookup "
                    + "history peek (wave P14) and for this same section's own order-history tab "
                    + "(§5.2d) to point at once it renders one. `accountId` scopes the read: an "
                    + "orderId that is not this account's own answers 404, identically to an order "
                    + "that does not exist at all, the same way `ReorderPlanService#planFor` "
                    + "already answers a customer asking about somebody else's order.")
    public StorefrontOrderingController.ReorderPlanResponse reorderPlan(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID accountId,
            @PathVariable UUID orderId) {
        return StorefrontOrderingController.ReorderPlanResponse.of(reorderPlans
                .planFor(tenantId, orderId, accountId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order")));
    }

    /** One order as this list renders it — the same shape {@code StorefrontOrderingController.OrderSummaryResponse} uses. */
    public record OrderSummaryResponse(
            UUID orderId,
            String publicOrderNumber,
            UUID locationId,
            String fulfillmentMode,
            String status,
            String paymentStatus,
            String fulfillmentStatus,
            String currency,
            long totalMinor,
            @Nullable Instant promisedAt,
            int version,
            Instant placedAt) {

        static OrderSummaryResponse of(JdbcOrderStore.CustomerOrderRow row) {
            return new OrderSummaryResponse(
                    row.orderId(),
                    row.publicOrderNumber(),
                    row.locationId(),
                    row.fulfillmentMode().name(),
                    row.status().name(),
                    row.paymentStatusProjection(),
                    row.fulfillmentStatusProjection(),
                    row.currency(),
                    row.totalMinor(),
                    row.promisedAt(),
                    row.version(),
                    row.createdAt());
        }
    }
}
