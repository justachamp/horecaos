package uz.horecaos.platform.audit.web;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.application.ApprovalPolicyService;
import uz.horecaos.platform.audit.application.ApprovalPolicyService.PolicyCoverage;
import uz.horecaos.platform.audit.application.ApprovalPolicyService.PolicyView;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Where a tenant's maker-checker thresholds are set (ADR 0027).
 *
 * <p>This is the surface that was missing. ADR 0027's four-eyes rule has been
 * called from six services since it shipped, and every one of them resolved no
 * policy and proceeded, because {@code audit.approval_policies} had no writer:
 * no endpoint, no service, no seed, and a database role holding {@code SELECT}
 * alone. A control nobody can configure is not a control that is off, it is a
 * control that answers "not required" to everything and records that answer as a
 * decision.
 *
 * <p>Behind {@code approval.policy.manage}, which is its own capability. The
 * person who sets the bar for a second signature must not be the person who
 * signs, so it is not a use of {@code refund.approve} or of {@code audit.read},
 * and among the tenant bundles only {@code tenant-owner} holds it — not finance,
 * which executes what the thresholds gate.
 *
 * <p>ADR 0050 gives every registered action an explicit absent-policy mode. The
 * coverage endpoint exposes that mode alongside the scopes a tenant has
 * configured, so a policy author can distinguish deliberate one-signature
 * operation from an action that is refused until its policy exists.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/approval-policies")
@Tag(name = "Approval policies",
        description = "The maker-checker thresholds that decide when an action needs a second signature")
public class ApprovalPolicyController {

    private final ApprovalPolicyService policies;
    private final CurrentActor currentActor;
    private final Clock clock;

    public ApprovalPolicyController(
            ApprovalPolicyService policies, CurrentActor currentActor, Clock clock) {
        this.policies = policies;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @GetMapping
    @RequiresCapability(value = Capability.APPROVAL_POLICY_MANAGE, scope = ScopeType.TENANT)
    @Operation(summary = "List the approval policies governing this tenant",
            description = "Every version of each policy, newest first. An action code with no row "
                    + "here needs no second signature, which is the state every action is in "
                    + "until somebody publishes one.")
    Page<PolicyResponse> list(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) String actionCode,
            @RequestParam(required = false, defaultValue = "false") boolean includeEnded,
            @RequestParam(required = false) Integer limit) {

        Instant now = clock.instant();
        List<PolicyResponse> found = policies
                .list(tenantId, actionCode, includeEnded, Page.limitOrDefault(limit))
                .stream()
                .map(view -> PolicyResponse.of(view, now))
                .toList();
        return Page.last(found);
    }

    @GetMapping("/coverage")
    @RequiresCapability(value = Capability.APPROVAL_POLICY_MANAGE, scope = ScopeType.TENANT)
    @Operation(summary = "Show registered approval actions and their configured scopes",
            description = "An empty configuredScopes list is an unresolved action. Its missingPolicyMode "
                    + "states whether it currently proceeds on one signature or is refused until a "
                    + "policy is authored. A scoped row is not presented as tenant-wide coverage.")
    List<PolicyCoverageResponse> coverage(@PathVariable UUID tenantId) {
        Instant now = clock.instant();
        return policies.coverage(tenantId).stream()
                .map(coverage -> PolicyCoverageResponse.of(coverage, now))
                .toList();
    }

    @PostMapping
    @RequiresCapability(value = Capability.APPROVAL_POLICY_MANAGE, scope = ScopeType.TENANT,
            mutating = true)
    @Operation(summary = "Publish the next version of an approval policy",
            description = "Never an edit. The previous open version is closed at the instant this "
                    + "one starts, so what an earlier approver was shown stays readable and the "
                    + "version snapshotted onto their request still means what it said.")
    ResponseEntity<PolicyResponse> author(
            @PathVariable UUID tenantId, @Valid @RequestBody AuthorPolicyRequest body) {

        PolicyView published = policies.author(new ApprovalPolicyService.NewPolicyVersion(
                scope(tenantId, body),
                body.actionCode(),
                body.thresholdDescription(),
                body.requiredApproverCapability(),
                body.validFrom(),
                actor(),
                body.reason()));

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PolicyResponse.of(published, clock.instant()));
    }

    @PostMapping("/{policyId}/expiry")
    @RequiresCapability(value = Capability.APPROVAL_POLICY_MANAGE, scope = ScopeType.TENANT,
            mutating = true)
    @Operation(summary = "End-date a policy version",
            description = "Closes the version's window, which is the only way to retire a threshold: "
                    + "resolution takes the highest version whose window is open, so a superseding "
                    + "row cannot switch an earlier one off. This is how an operator says stop "
                    + "requiring approval, so it only applies to the version in force. Retroactive "
                    + "ends are refused, a closed version is never reopened — that is a new "
                    + "version — and a version scheduled to take effect later is refused too: "
                    + "cancelling it would leave the version it superseded closed with nothing to "
                    + "follow it, which turns the control off from the scheduled date onwards. To "
                    + "call off a scheduled change, publish the threshold you want as a new "
                    + "version.")
    PolicyResponse endDate(
            @PathVariable UUID tenantId,
            @PathVariable UUID policyId,
            @Valid @RequestBody EndPolicyRequest body) {

        return PolicyResponse.of(
                policies.endDate(tenantId, policyId, body.effectiveAt(), actor(), body.reason()),
                clock.instant());
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private static ScopeType scopeType(String value) {
        try {
            return ScopeType.valueOf(value);
        } catch (IllegalArgumentException notAScope) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "scopeType is one of TENANT, BRAND, or LOCATION");
        }
    }

    private static uz.horecaos.platform.iam.api.ResourceScope scope(
            UUID tenantId, AuthorPolicyRequest body) {
        return switch (scopeType(body.scopeType())) {
            case TENANT -> {
                if (body.brandId() != null || body.locationId() != null) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED,
                            "A TENANT policy names no brand or location");
                }
                yield uz.horecaos.platform.iam.api.ResourceScope.tenant(tenantId);
            }
            case BRAND -> {
                if (body.brandId() == null || body.locationId() != null) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED,
                            "A BRAND policy requires brandId and names no location");
                }
                yield uz.horecaos.platform.iam.api.ResourceScope.brand(tenantId, body.brandId());
            }
            case LOCATION -> {
                if (body.brandId() == null || body.locationId() == null) {
                    throw new ApiException(ErrorCode.VALIDATION_FAILED,
                            "A LOCATION policy requires both brandId and locationId");
                }
                yield uz.horecaos.platform.iam.api.ResourceScope.location(
                        tenantId, body.brandId(), body.locationId());
            }
            case PLATFORM -> throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "A tenant authors TENANT, BRAND, or LOCATION policies");
        };
    }

    /**
     * @param scopeType                  the level the policy governs
     * @param brandId                    required for BRAND and LOCATION policies
     * @param locationId                 required for LOCATION policies
     * @param thresholdDescription       the sentence the approver reads on the
     *                                   request, copied onto it and frozen there
     * @param requiredApproverCapability which ADR 0025 capability the second pair
     *                                   of eyes has to hold; refused if the
     *                                   platform declares no such capability
     * @param validFrom                  when it takes effect, or absent for now.
     *                                   Never in the past
     * @param reason                     why the threshold is changing. ADR 0027
     *                                   refuses an operator action without one,
     *                                   and this is the operator action that
     *                                   decides when other operators are checked
     */
    public record AuthorPolicyRequest(
            @NotBlank @Size(max = 128) String actionCode,
            @NotBlank String scopeType,
            UUID brandId,
            UUID locationId,
            @NotBlank @Size(max = ApprovalPolicyService.MAXIMUM_THRESHOLD_LENGTH)
            String thresholdDescription,
            @NotBlank @Size(max = 128) String requiredApproverCapability,
            Instant validFrom,
            @NotBlank @Size(max = 1000) String reason) {
    }

    /**
     * @param effectiveAt when the threshold stops applying, or absent for now
     */
    public record EndPolicyRequest(
            Instant effectiveAt,
            @NotBlank @Size(max = 1000) String reason) {
    }

    /**
     * @param governsNow whether this version is the one an action resolving right
     *                   now would find, so an operator reading the list does not
     *                   have to compare two timestamps to answer the only question
     *                   they came with
     */
    public record PolicyResponse(
            UUID id,
            String actionCode,
            String scopeType,
            UUID brandId,
            UUID locationId,
            boolean legacyScopeWide,
            String thresholdDescription,
            String requiredApproverCapability,
            Instant validFrom,
            Instant validUntil,
            int version,
            String authoredBy,
            Instant createdAt,
            boolean governsNow) {

        static PolicyResponse of(PolicyView view, Instant now) {
            return new PolicyResponse(
                    view.id(), view.actionCode(), view.scopeType(), view.brandId(), view.locationId(),
                    view.legacyScopeWide(), view.thresholdDescription(),
                    view.requiredApproverCapability(), view.validFrom(), view.validUntil(),
                    view.version(), view.authoredBy(), view.createdAt(),
                    view.isOpenAt(now));
        }
    }

    public record PolicyCoverageResponse(
            String actionCode,
            String missingPolicyMode,
            boolean configuredAnywhere,
            List<PolicyResponse> configuredScopes) {

        static PolicyCoverageResponse of(PolicyCoverage coverage, Instant now) {
            return new PolicyCoverageResponse(
                    coverage.actionCode(), coverage.missingPolicyMode().name(),
                    coverage.configuredAnywhere(),
                    coverage.configuredScopes().stream().map(view -> PolicyResponse.of(view, now)).toList());
        }
    }
}
