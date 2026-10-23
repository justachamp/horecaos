package uz.horecaos.platform.audit.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.web.ApprovalPolicyController.AuthorPolicyRequest;
import uz.horecaos.platform.audit.web.ApprovalPolicyController.EndPolicyRequest;
import uz.horecaos.platform.audit.web.ApprovalPolicyController.PolicyCoverageResponse;
import uz.horecaos.platform.audit.web.ApprovalPolicyController.PolicyResponse;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The operations console's twin of {@link ApprovalPolicyController} (ADR 0050, ADR 0027, ADR 0057, ADR 0181).
 *
 * <p>The three policy routes were published only in the control-plane group, which the operations console does
 * not call, so a tenant could be told that an action "needs a published approval policy" and had nowhere in the
 * console to publish one: {@code COURIER_MANUAL_PENALTY} is fail-closed and no migration seeds a floor for it, and
 * a tenant that wants a second signature on a refund or an export had no screen either. This is the same
 * three operations, with the same capability ({@code approval.policy.manage} at the tenant, the owner alone) and
 * the same service behind them, exposed where the console lives -- the precedent {@link ApprovalRequestController}
 * set for the worklist and the decision, which it also serves twice.
 *
 * <p>Delegates to {@link ApprovalPolicyController} rather than copying it, so the two surfaces cannot disagree
 * about what a version is, how its scope is read, or what an end date means. Each method re-declares its own
 * capability: the interceptor reads the handler it dispatched to, not the one it delegates to.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/approval-policies")
@Tag(
        name = "Approval policies (operations)",
        description = "The maker-checker thresholds that decide when an action needs a second signature, "
                + "for the operations console")
public class OperationsApprovalPolicyController {

    private final ApprovalPolicyController delegate;

    public OperationsApprovalPolicyController(ApprovalPolicyController delegate) {
        this.delegate = delegate;
    }

    @GetMapping
    @RequiresCapability(value = Capability.APPROVAL_POLICY_MANAGE, scope = ScopeType.TENANT)
    @Operation(
            summary = "List the approval policies governing this tenant",
            description = "Every version of each policy, newest first; the control-plane route's twin. An action "
                    + "code with no row here follows its registered missing-policy mode.")
    public Page<PolicyResponse> list(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String actionCode,
            @RequestParam(required = false, defaultValue = "false") boolean includeEnded,
            @RequestParam(required = false) Integer limit) {
        return delegate.list(tenantId, actionCode, includeEnded, limit);
    }

    @GetMapping("/coverage")
    @RequiresCapability(value = Capability.APPROVAL_POLICY_MANAGE, scope = ScopeType.TENANT)
    @Operation(
            summary = "Show registered approval actions and their configured scopes",
            description = "An empty configuredScopes list is an unresolved action; its missingPolicyMode says "
                    + "whether it proceeds on one signature (ALLOW_WITHOUT_APPROVAL) or is refused until a "
                    + "policy is authored (REQUIRE_CONFIGURED_POLICY). The control-plane route's twin.")
    public List<PolicyCoverageResponse> coverage(@PathVariable UUID tenantId) {
        return delegate.coverage(tenantId);
    }

    @PostMapping
    @RequiresCapability(value = Capability.APPROVAL_POLICY_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Publish the next version of an approval policy",
            description = "Never an edit: the previous open version is closed at the instant this one starts. "
                    + "The control-plane route's twin.")
    public ResponseEntity<PolicyResponse> author(
            @PathVariable UUID tenantId, @Valid @RequestBody AuthorPolicyRequest body) {
        return delegate.author(tenantId, body);
    }

    @PostMapping("/{policyId}/expiry")
    @RequiresCapability(value = Capability.APPROVAL_POLICY_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "End-date a policy version",
            description = "Closes the version's window, the only way to retire a threshold. Retroactive ends and "
                    + "versions scheduled for later are refused. The control-plane route's twin.")
    public PolicyResponse endDate(
            @PathVariable UUID tenantId, @PathVariable UUID policyId, @Valid @RequestBody EndPolicyRequest body) {
        return delegate.endDate(tenantId, policyId, body);
    }
}
