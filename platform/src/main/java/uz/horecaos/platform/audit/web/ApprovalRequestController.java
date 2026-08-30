package uz.horecaos.platform.audit.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.application.ApprovalDecisionService;
import uz.horecaos.platform.audit.application.ApprovalDecisionService.PendingApproval;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The approvals console: what is waiting, and how a second person signs it
 * (ADR 0027).
 *
 * <p>This is the surface {@code OperationsRemedyController} documents the absence
 * of. Its class comment is the specification for this one: a payments-local
 * approve endpoint behind {@code refund.approve} would let a refund approver
 * decide a loyalty adjustment or an onboarding step by pasting a different
 * identifier, because {@code ApprovalService.decide} takes a request identifier
 * and nothing else. So the console belongs to the audit module, which is the only
 * place that can scope a decision to the action code it was raised under — and
 * scope it it does, against the policy's own
 * {@code required_approver_capability}.
 *
 * <p>{@code approval.decide} on the annotation is admission and nothing more. The
 * decision itself is authorised three further times inside the service: the
 * request must belong to this tenant, the caller must hold the capability the
 * governing policy version named, and the caller must not be the person who
 * raised it. Every refusal is recorded.
 *
 * <p>A request exists only where a policy governed the action. ADR 0050 makes
 * an absent policy per-action: permissive actions answer {@code NotRequired},
 * while fail-closed actions return {@code APPROVAL_POLICY_REQUIRED} until an
 * operator authors a policy. The policy console exposes those modes and
 * configured scopes separately.
 *
 * <p><strong>Known limitation.</strong> The console is tenant-scoped, so a
 * principal whose grant is brand- or location-scoped cannot reach it even when
 * the policy names a capability they hold at that level. Brand-scoped requests
 * are decidable here by tenant-scoped staff, whose grant covers the brand; a
 * brand-scoped approvals path is a separate surface.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/approval-requests")
@Tag(name = "Approval requests", description = "The maker-checker queue: actions waiting for a second signature")
public class ApprovalRequestController {

    private final ApprovalDecisionService decisions;
    private final CurrentActor currentActor;

    public ApprovalRequestController(ApprovalDecisionService decisions, CurrentActor currentActor) {
        this.decisions = decisions;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.APPROVAL_DECIDE, scope = ScopeType.TENANT)
    @Operation(
            summary = "List the requests waiting for a second signature",
            description = "Oldest first, so the request closest to lapsing is the one on top. "
                    + "Lapsed requests are excluded whether or not the expiry sweep has reached "
                    + "them yet. The maker's free-text reason is deliberately not returned: it is "
                    + "unclassified prose about a named customer (ADR 0029), and the action's own "
                    + "console already holds the detail behind its own capability.")
    Page<PendingApprovalResponse> pending(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String actionCode,
            @RequestParam(required = false) Integer limit) {

        List<PendingApprovalResponse> waiting =
                decisions.pending(tenantId, actionCode, Page.limitOrDefault(limit), subject()).stream()
                        .map(PendingApprovalResponse::of)
                        .toList();
        return Page.last(waiting);
    }

    @PostMapping("/{requestId}/decision")
    @RequiresCapability(value = Capability.APPROVAL_DECIDE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Approve or decline a pending request",
            description = "Refused unless the caller holds the capability the governing policy "
                    + "version named as the second signature, and refused outright if the caller "
                    + "is the person who raised the request. An approval lets the maker's "
                    + "identical resubmission proceed exactly once — executing under it moves the "
                    + "request to CONSUMED, so the next identical submission raises a new request "
                    + "needing a new signature. A decline blocks it with the reason given here. "
                    + "Both refusals and decisions are audited.")
    DecisionResponse decide(
            @PathVariable UUID tenantId, @PathVariable UUID requestId, @Valid @RequestBody DecisionRequest body) {

        var decided = decisions.decide(tenantId, requestId, decisionOf(body.decision()), actor(), body.reason());

        return new DecisionResponse(
                decided.id(), decided.actionCode(), decided.status(), decided.decidedBy(), decided.decidedAt());
    }

    private ActorRef actor() {
        return ActorRef.user(subject(), null);
    }

    private String subject() {
        return currentActor.get().subject();
    }

    private static ApprovalService.Decision decisionOf(String value) {
        try {
            return ApprovalService.Decision.valueOf(value);
        } catch (IllegalArgumentException notADecision) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "decision is one of APPROVE or DECLINE");
        }
    }

    /**
     * @param decision APPROVE or DECLINE
     * @param reason   why. Recorded on the request and in the audit trail, and
     *                 returned to the maker when the decision is a decline, so it
     *                 is the sentence that has to explain the refusal to the
     *                 person who asked
     */
    public record DecisionRequest(
            @NotBlank String decision,

            @NotBlank @Size(max = ApprovalDecisionService.MAXIMUM_REASON_LENGTH)
            String reason) {}

    /**
     * @param mayDecide whether the caller could decide this row. False for the
     *                  caller's own requests however senior they are, so a console
     *                  can grey the button rather than offer one that answers 403
     */
    public record PendingApprovalResponse(
            UUID id,
            String actionCode,
            String parametersHash,
            String scopeType,
            UUID scopeId,
            String thresholdDescription,
            int policyVersion,
            String requiredApproverCapability,
            String requestedBy,
            java.time.Instant requestedAt,
            java.time.Instant expiresAt,
            boolean mayDecide) {

        static PendingApprovalResponse of(PendingApproval view) {
            return new PendingApprovalResponse(
                    view.id(),
                    view.actionCode(),
                    view.parametersHash(),
                    view.scopeType(),
                    view.scopeId(),
                    view.thresholdDescription(),
                    view.policyVersion(),
                    view.requiredApproverCapability(),
                    view.requestedBy(),
                    view.requestedAt(),
                    view.expiresAt(),
                    view.mayDecide());
        }
    }

    /** @param status APPROVED or DECLINED */
    public record DecisionResponse(
            UUID id, String actionCode, String status, String decidedBy, java.time.Instant decidedAt) {}
}
