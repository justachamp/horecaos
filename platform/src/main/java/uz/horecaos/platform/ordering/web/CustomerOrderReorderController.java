package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.application.ReorderPlanService;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The New Order screen's own «Повторить» (gap map rows 1.3f/1.3a): the same
 * question {@link CustomerOrderHistoryController#reorderPlan} answers, at a
 * scope {@code LOCATION_STAFF} — this screen's primary persona — actually
 * holds.
 *
 * <p>{@link CustomerOrderHistoryController#reorderPlan} declares {@code
 * ORDER_READ} at {@code BRAND} scope, because it is the Customers section's
 * own history tab reading an order that could be at any of the brand's
 * branches. The New Order screen is a different caller entirely: an operator
 * working one branch, whose {@code ORDER_READ} grant — per {@code
 * PlatformRole}, {@code LOCATION_STAFF} holds it only at {@code LOCATION}
 * scope — reaches nothing past it. Widening that role's grant is a decision
 * this wave does not make; adding a second, narrower route to the same
 * read is not.
 *
 * <p><strong>The honest part.</strong> This is not the brand-scoped read
 * with a looser capability bolted on: {@link ReorderPlanService#planForAtLocation}
 * resolves every line against <em>this</em> {@code locationId}'s own menu,
 * offering rows and stock — the branch the operator is actually standing
 * in — never the customer's original order's own location, which this
 * operator may hold no read grant on at all and whose menu is not what the
 * New Order screen is about to build a cart against. A dish available at
 * the order's original branch and withdrawn here answers {@code WITHDRAWN}
 * here, correctly, rather than a plan the operator cannot actually place.
 *
 * <p><strong>Blocker fix.</strong> {@code brandId} is checked against the
 * order's own snapshot brand: a {@code LOCATION}-scoped {@code ORDER_READ}
 * grant never widens past its own brand (ADR 0025), and skipping this check
 * would let this route return another brand's order in full — line items,
 * quantities and what the customer paid — to an operator who holds no grant
 * on that brand at all. See {@link ReorderPlanService#planForAtLocation}'s
 * own doc for the full argument.
 */
@RestController
@RequestMapping(
        "/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/customers/{accountId}/orders/{orderId}/reorder")
@Tag(name = "Customer order history", description = "One customer's own orders, as staff may read them")
public class CustomerOrderReorderController {

    private final ReorderPlanService reorderPlans;

    public CustomerOrderReorderController(ReorderPlanService reorderPlans) {
        this.reorderPlans = reorderPlans;
    }

    @GetMapping
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
