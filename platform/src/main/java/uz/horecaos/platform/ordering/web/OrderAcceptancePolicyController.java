package uz.horecaos.platform.ordering.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.ordering.application.OrderAcceptancePolicyService;
import uz.horecaos.platform.ordering.application.OrderAcceptancePolicyService.Effective;
import uz.horecaos.platform.ordering.domain.AcceptanceMode;
import uz.horecaos.platform.ordering.domain.ApprovalChannel;
import uz.horecaos.platform.ordering.domain.ApprovalTimeoutAction;
import uz.horecaos.platform.ordering.domain.OrderAcceptancePolicy;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Authoring the tenant's order-acceptance policy (ADR 0002, ADR 0030, Gap D
 * of the 2026-08-30 proving run).
 *
 * <p>Before this existed, {@code OrderAcceptancePolicyService} could resolve
 * an acceptance policy but nothing could publish one — {@code
 * JdbcPolicyResolver} had no writer anywhere, and every tenant not seeded by
 * hand fell through to {@link OrderAcceptancePolicy#platformDefault()}
 * ({@code AUTO_CONFIRM}), silently, whether or not that was the intended
 * behaviour. V0098's onboarding template now applies {@code
 * RESTAURANT_APPROVAL} on every fresh tenant's behalf; this is the surface an
 * owner uses afterward, to change it or to override it at a brand or
 * location the resolution model already lets a policy target — see {@link
 * OrderAcceptancePolicyService#ACCEPTANCE}'s {@code settableScopes}, which
 * this endpoint does not extend.
 *
 * <p>{@code TENANT} scope by default, with an optional {@code brandId}/{@code
 * locationId} override in the request body — the same shape {@code
 * GrantController} uses for a grant's scope, for the same reason: a tenant
 * grant already covers every brand and location beneath it, so an owner
 * authoring at {@code TENANT} scope needs no separate endpoint to reach a
 * narrower one.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/order-acceptance-policy")
@Tag(
        name = "Order acceptance policy",
        description = "How a paid order is accepted: automatically, or by staff (ADR 0002)")
public class OrderAcceptancePolicyController {

    private final OrderAcceptancePolicyService acceptancePolicies;
    private final CurrentActor currentActor;

    public OrderAcceptancePolicyController(OrderAcceptancePolicyService acceptancePolicies, CurrentActor currentActor) {
        this.acceptancePolicies = acceptancePolicies;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(Capability.ORDER_READ)
    @Operation(
            summary = "The policy currently in force",
            description = "Omit brandId/locationId for the tenant-wide resolution; supply either to see "
                    + "what a specific brand or location actually resolves, including any override.")
    AcceptancePolicyResponse effective(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) UUID brandId,
            @RequestParam(required = false) UUID locationId) {
        return AcceptancePolicyResponse.of(acceptancePolicies.resolveAt(scopeOf(tenantId, brandId, locationId)));
    }

    @PostMapping
    @RequiresCapability(value = Capability.ORDER_ACCEPTANCE_POLICY_MANAGE, mutating = true)
    @Operation(
            summary = "Publish the next version",
            description = "Never edits a version in force. Orders already accepted keep the version they "
                    + "resolved (ADR 0030); this only changes what a new order resolves from now on.")
    ResponseEntity<AcceptancePolicyResponse> author(
            @PathVariable UUID tenantId, @Valid @RequestBody AuthorRequest request) {
        Effective published = acceptancePolicies.author(
                scopeOf(tenantId, request.brandId(), request.locationId()),
                request.toDocument(),
                ActorRef.user(currentActor.get().subject(), null),
                request.reason());
        return ResponseEntity.ok(AcceptancePolicyResponse.of(published));
    }

    private static ResourceScope scopeOf(UUID tenantId, UUID brandId, UUID locationId) {
        if (locationId != null) {
            return ResourceScope.location(tenantId, brandId, locationId);
        }
        if (brandId != null) {
            return ResourceScope.brand(tenantId, brandId);
        }
        return ResourceScope.tenant(tenantId);
    }

    public record AuthorRequest(
            UUID brandId,
            UUID locationId,
            @NotNull AcceptanceMode mode,
            @NotNull ApprovalChannel approvalChannel,
            int approvalTimeoutSeconds,
            @NotNull ApprovalTimeoutAction timeoutAction,
            boolean rejectionReasonRequired,
            boolean notifyCustomerWhilePending,
            @NotBlank @Size(max = 1000) String reason) {

        OrderAcceptancePolicy toDocument() {
            return new OrderAcceptancePolicy(
                    mode,
                    approvalChannel,
                    approvalTimeoutSeconds,
                    timeoutAction,
                    rejectionReasonRequired,
                    notifyCustomerWhilePending);
        }
    }

    public record AcceptancePolicyResponse(
            String mode,
            String approvalChannel,
            int approvalTimeoutSeconds,
            String timeoutAction,
            boolean rejectionReasonRequired,
            boolean notifyCustomerWhilePending,
            boolean isPlatformDefault,
            UUID policyId,
            int policyVersion) {

        static AcceptancePolicyResponse of(Effective effective) {
            OrderAcceptancePolicy policy = effective.policy();
            return new AcceptancePolicyResponse(
                    policy.mode().name(),
                    policy.approvalChannel().name(),
                    policy.approvalTimeoutSeconds(),
                    policy.timeoutAction().name(),
                    policy.rejectionReasonRequired(),
                    policy.notifyCustomerWhilePending(),
                    effective.isPlatformDefault(),
                    effective.policyId(),
                    effective.policyVersion());
        }
    }
}
