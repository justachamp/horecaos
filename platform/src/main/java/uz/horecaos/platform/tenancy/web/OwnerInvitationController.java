package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.application.invitations.OwnerInvitationService;
import uz.horecaos.platform.tenancy.application.invitations.OwnerInvitationService.OwnerInvitationOverviewRow;
import uz.horecaos.platform.tenancy.application.invitations.OwnerInvitationService.OwnerInvitationView;
import uz.horecaos.platform.tenancy.application.invitations.OwnerInvitationService.OwnerStateView;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The owner's invitation as the control plane sees it (ADR 0097, ADR 0100):
 * where it stands, everything that has happened to it, every tenant's at once,
 * and a resend with a reason.
 *
 * <p>The recipient comes back in full only to a caller holding {@link
 * Capability#TENANT_ONBOARDING_MANAGE} -- the capability that typed the address
 * into onboarding in the first place -- and the reveal is audited. Everybody
 * else gets ADR 0097's mask. The link itself is never shown to anybody but the
 * owner, in their email.
 *
 * <p>A screen that wants to mark which tenants are still waiting, and renders
 * no address at all, asks {@code /owner-invitations/waiting} instead: it
 * resolves no recipient, so it reads no address rather than reading one and
 * dropping it, and it leaves no reveal fact behind to dilute the count of the
 * ones that matter.
 */
@RestController
@Tag(
        name = "Tenant onboarding",
        description = "Owner invitation state, history, overview and resend (ADR 0097, ADR 0100)")
public class OwnerInvitationController {

    private final OwnerInvitationService invitations;
    private final CurrentActor currentActor;

    public OwnerInvitationController(OwnerInvitationService invitations, CurrentActor currentActor) {
        this.invitations = invitations;
        this.currentActor = currentActor;
    }

    @GetMapping("/api/v1/control-plane/tenants/{tenantId}/owner-invitation")
    @RequiresCapability(Capability.TENANT_READ)
    @Operation(
            summary = "The tenant owner's invitation, and its history",
            description = "Queued, sent, opened, accepted, not needed, failed or expired, with when and why, "
                    + "and the timeline of every queue, send attempt, open, accept and resend. The recipient "
                    + "is shown in full to a caller holding tenant.onboarding.manage here, and masked to "
                    + "everyone else.")
    public OwnerInvitationView view(@PathVariable UUID tenantId) {
        return invitations
                .view(tenantId, actor(), correlationId())
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "This tenant has no owner invitation"));
    }

    @GetMapping("/api/v1/control-plane/owner-invitations")
    @RequiresCapability(value = Capability.TENANT_ONBOARDING_MANAGE, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Every tenant's owner invitation",
            description = "The cross-tenant view of onboarding's last mile: which owners have not set up an "
                    + "account, what was sent to them and what came of it. A tenant whose owner was linked "
                    + "but never invited is listed in state NONE -- that is the case a list built from the "
                    + "invitation table alone would miss. Archived tenants are left out. At most 200 rows.")
    public List<OwnerInvitationOverviewRow> overview(
            @RequestParam(required = false)
                    @Schema(
                            description = "One state, NONE, or OUTSTANDING for everything not yet accepted",
                            allowableValues = {
                                "QUEUED",
                                "SENT",
                                "ACCEPTED",
                                "NOT_NEEDED",
                                "FAILED",
                                "EXPIRED",
                                "NONE",
                                "OUTSTANDING"
                            })
                    String state) {
        return invitations.overview(state, actor(), correlationId());
    }

    @GetMapping("/api/v1/control-plane/owner-invitations/waiting")
    @RequiresCapability(value = Capability.TENANT_ONBOARDING_MANAGE, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Where every tenant's owner stands, without the addresses",
            description = "An identifier and a state per unarchived tenant, for a screen that renders a marker "
                    + "rather than a recipient. No address is read from the identity provider on this path, "
                    + "none is returned, and no reveal is recorded -- which is why it is a separate projection "
                    + "and not a flag on the overview. NO_OWNER means no owner has been linked or invited yet; "
                    + "a tenant this does not list is a tenant the caller knows nothing about.")
    public List<OwnerStateView> waiting() {
        return invitations.ownerStates();
    }

    @PostMapping("/api/v1/control-plane/tenants/{tenantId}/owner-invitation/resend")
    @RequiresCapability(value = Capability.TENANT_ONBOARDING_MANAGE, mutating = true)
    @Operation(
            summary = "Send the owner's invitation, or send it again",
            description = "With a new link: one already sent stops working at once. For a tenant onboarded "
                    + "before invitations existed, sends the first one to the owner its onboarding linked. "
                    + "Refused once the owner has set up their account.")
    public ResponseEntity<Void> resend(
            @PathVariable UUID tenantId, @Valid @RequestBody OwnerInvitationResendRequest body) {
        invitations.resend(tenantId, body.locale(), actor(), body.reason(), correlationId());
        return ResponseEntity.noContent().build();
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private static String correlationId() {
        String correlationId = org.slf4j.MDC.get("correlationId");
        return correlationId == null || correlationId.isBlank()
                ? UUID.randomUUID().toString()
                : correlationId;
    }

    /** Why it is resent, and optionally a different language for it. */
    public record OwnerInvitationResendRequest(
            @NotBlank @Size(max = 1000) String reason,
            @Size(max = 8) String locale) {}
}
