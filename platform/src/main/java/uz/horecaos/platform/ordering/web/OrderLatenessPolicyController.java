package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.ordering.application.OrderLatenessPolicyService;
import uz.horecaos.platform.ordering.application.OrderLatenessPolicyService.Effective;
import uz.horecaos.platform.ordering.application.OrderQueryService;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy;
import uz.horecaos.platform.ordering.domain.OrderLatenessPolicy.LatenessThresholds;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Serves the ADR 0030 {@code ordering.lateness} policy to the console (gap map
 * rows {@code 1.1g}/{@code X.39}, orders.md §2.7).
 *
 * <p>{@code GET .../lateness-policy} is what {@code order-severity.ts} and
 * {@code kitchen-ticket.ts} both call instead of each hard-coding its own
 * thresholds. Reads declare {@link Capability#ORDER_READ} at {@code LOCATION}
 * scope, matching every other read on the branch's own order surface —
 * authoring is a separate, narrower capability that ships with wave P31.
 *
 * <p>{@code GET .../{orderId}/lateness} gives {@link OrderLatenessPolicy#evaluate}
 * — and through it {@link uz.horecaos.platform.ordering.domain.OrderPromise#lateAt}
 * — the production caller the gap map named missing: a real order's promise,
 * status, and creation time, evaluated against the resolved policy. The
 * console itself keeps deriving severity client-side, per render, exactly as
 * orders.md §2.7 requires ("a stored flag is wrong five seconds after it is
 * written"); this exists for any caller that is not a per-render UI and would
 * otherwise have to reimplement the ramp to answer one question about one
 * order.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/orders")
@Tag(name = "Order lateness", description = "The ordering.lateness policy (ADR 0030) and one order's resolved severity")
public class OrderLatenessPolicyController {

    private final OrderLatenessPolicyService latenessPolicies;
    private final OrderQueryService orders;
    private final Clock clock;

    public OrderLatenessPolicyController(
            OrderLatenessPolicyService latenessPolicies, OrderQueryService orders, Clock clock) {
        this.latenessPolicies = latenessPolicies;
        this.orders = orders;
        this.clock = clock;
    }

    @GetMapping("/lateness-policy")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The lateness policy in force for this location",
            description = "at_risk_before/late_after/no_promise_fallback per fulfilment mode "
                    + "(orders.md §2.7), resolved through the ADR 0030 chain: LOCATION, then "
                    + "BRAND, then TENANT, then the platform default.")
    LatenessPolicyResponse policy(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return LatenessPolicyResponse.of(
                latenessPolicies.resolveAt(ResourceScope.location(tenantId, brandId, locationId)));
    }

    @GetMapping("/{orderId}/lateness")
    @RequiresCapability(value = Capability.ORDER_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "This order's resolved severity, right now",
            description = "LATE, AT_RISK, or NORMAL — orders.md §2.7's Levels table, minus "
                    + "BLOCKED, which is a process-state fact this endpoint does not carry. "
                    + "Computed fresh on every call from the order's own promise and the "
                    + "resolved policy; nothing here is stored.")
    OrderLatenessResponse severity(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId) {

        OrderRow order = orders.detail(tenantId, orderId)
                .map(OrderQueryService.OrderDetail::order)
                .filter(found -> found.locationId().equals(locationId))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such order"));

        OrderLatenessPolicy policy = latenessPolicies
                .resolveAt(ResourceScope.location(tenantId, brandId, locationId))
                .policy();
        OrderLatenessPolicy.LatenessLevel level = policy.evaluate(
                order.fulfillmentMode(), order.promise(), order.status(), order.createdAt(), clock.instant());
        return new OrderLatenessResponse(level.name());
    }

    /** One fulfilment mode's own numbers, as the policy document declares them. */
    public record LatenessThresholdsResponse(
            int atRiskBeforeSeconds, int lateAfterSeconds, int noPromiseFallbackSeconds) {

        static LatenessThresholdsResponse of(LatenessThresholds thresholds) {
            return new LatenessThresholdsResponse(
                    thresholds.atRiskBeforeSeconds(),
                    thresholds.lateAfterSeconds(),
                    thresholds.noPromiseFallbackSeconds());
        }
    }

    /** The resolved {@code ordering.lateness} document, one threshold set per fulfilment mode. */
    public record LatenessPolicyResponse(
            LatenessThresholdsResponse delivery,
            LatenessThresholdsResponse pickup,
            LatenessThresholdsResponse dineIn,
            boolean isPlatformDefault,
            @Nullable UUID policyId,
            int policyVersion) {

        static LatenessPolicyResponse of(Effective effective) {
            OrderLatenessPolicy policy = effective.policy();
            return new LatenessPolicyResponse(
                    LatenessThresholdsResponse.of(policy.delivery()),
                    LatenessThresholdsResponse.of(policy.pickup()),
                    LatenessThresholdsResponse.of(policy.dineIn()),
                    effective.isPlatformDefault(),
                    effective.policyId(),
                    effective.policyVersion());
        }
    }

    /** {@code level} is one of {@code LatenessLevel}'s three names — a state, never a fact about a person. */
    public record OrderLatenessResponse(String level) {}
}
