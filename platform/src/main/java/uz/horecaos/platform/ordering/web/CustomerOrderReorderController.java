package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
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
 * The New Order screen's own order-history read and «Повторить» (gap map rows
 * 1.3f/1.3a): the same two questions {@link CustomerOrderHistoryController}
 * answers, at a scope {@code LOCATION_STAFF} — this screen's primary persona
 * — actually holds.
 *
 * <p>{@link CustomerOrderHistoryController} declares {@code ORDER_READ} at
 * {@code BRAND} scope for both {@link CustomerOrderHistoryController#listOrders}
 * and {@link CustomerOrderHistoryController#reorderPlan}, because it is the
 * Customers section's own history tab reading a customer who could have
 * ordered at any of the brand's branches. The New Order screen is a different
 * caller entirely: an operator working one branch, whose {@code ORDER_READ}
 * grant — per {@code PlatformRole}, {@code LOCATION_STAFF} holds it only at
 * {@code LOCATION} scope — reaches nothing past it. Widening that role's
 * grant is a decision this wave does not make; adding a second, narrower
 * route to the same two reads is not.
 *
 * <p>Both reads stay brand-scoped in what they return — {@link
 * OrderQueryService#forCustomer} already filters by {@code brandId}, and
 * {@link ReorderPlanService#planForAtLocation} checks the order's own {@code
 * brandId} against the path's before resolving anything — because a
 * {@code LOCATION}-scoped grant never widens past its own brand (ADR 0025): it
 * narrows {@link CustomerOrderHistoryController}'s capability requirement,
 * never the tenant boundary of what a caller may read past it.
 *
 * <p><strong>The honest part about {@code reorder}.</strong> This is not the
 * brand-scoped read with a looser capability bolted on: {@link
 * ReorderPlanService#planForAtLocation} resolves every line against
 * <em>this</em> {@code locationId}'s own menu, offering rows and stock — the
 * branch the operator is actually standing in — never the customer's original
 * order's own location, which this operator may hold no read grant on at all
 * and whose menu is not what the New Order screen is about to build a cart
 * against. A dish available at the order's original branch and withdrawn
 * here answers {@code WITHDRAWN} here, correctly, rather than a plan the
 * operator cannot actually place. The location narrows only the resolved
 * menu, never which brand's orders are visible at all.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/customers/{accountId}/orders")
@Tag(name = "Customer order history", description = "One customer's own orders, as staff may read them")
public class CustomerOrderReorderController {

    private final OrderQueryService orderQuery;
    private final ReorderPlanService reorderPlans;

    public CustomerOrderReorderController(OrderQueryService orderQuery, ReorderPlanService reorderPlans) {
        this.orderQuery = orderQuery;
        this.reorderPlans = reorderPlans;
    }

    @GetMapping
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "One customer's orders at this brand, newest first, reached with a LOCATION grant",
            description = "The New Order screen's own history peek (gap map rows 1.3f/1.3a): the "
                    + "same read as GET .../brands/{brandId}/customers/{accountId}/orders, reached "
                    + "with ORDER_READ at this LOCATION rather than the whole BRAND. Still every "
                    + "order this customer placed anywhere in the brand, cursor-paginated "
                    + "identically — the location narrows only which grant satisfies the capability "
                    + "check, not which of the customer's own orders are listed.")
    public Page<CustomerOrderHistoryController.OrderSummaryResponse> listOrders(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
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

        String nextCursor = rows.size() < pageSize
                ? null
                : rows.get(rows.size() - 1).orderId().toString();

        return new Page<>(
                rows.stream()
                        .map(CustomerOrderHistoryController.OrderSummaryResponse::of)
                        .toList(),
                nextCursor);
    }

    @GetMapping("/{orderId}/reorder")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Whether one of this customer's own orders can be ordered again at this branch",
            description = "The New Order screen's own «Повторить» (gap map rows 1.3f/1.3a): the "
                    + "same read as GET .../brands/{brandId}/customers/{accountId}/orders/{orderId}"
                    + "/reorder, reached with ORDER_READ at this LOCATION rather than the whole "
                    + "BRAND, and resolved against this location's own menu, offering rows and "
                    + "stock rather than the order's original branch — an operator standing at "
                    + "this counter is about to build the new cart here, not there. `accountId` "
                    + "scopes the read exactly as the brand-scoped twin does: an orderId that is "
                    + "not this account's own, or not this path's own brand's, answers 404, "
                    + "identically to an order that does not exist at all.")
    public StorefrontOrderingController.ReorderPlanResponse reorderPlan(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID accountId,
            @PathVariable UUID orderId) {
        return StorefrontOrderingController.ReorderPlanResponse.of(reorderPlans
                .planForAtLocation(tenantId, brandId, orderId, accountId, locationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order")));
    }
}
