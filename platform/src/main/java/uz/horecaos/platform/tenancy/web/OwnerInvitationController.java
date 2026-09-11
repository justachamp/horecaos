package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.tenancy.application.invitations.OwnerInvitationService;
import uz.horecaos.platform.tenancy.application.invitations.OwnerInvitationService.OwnerInvitationView;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The owner's invitation as the control plane sees it (ADR 0097): where it
 * stands, and a resend with a reason. The address is shown masked; the link
 * is never shown to anybody but the owner, in their email.
 */
@RestController
@Tag(name = "Tenant onboarding", description = "Owner invitation state and resend (ADR 0097)")
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
            summary = "The tenant owner's invitation",
            description = "Queued, sent, opened, accepted, not needed, failed or expired, with when and why. "
                    + "The address is masked.")
    public OwnerInvitationView view(@PathVariable UUID tenantId) {
        return invitations
                .view(tenantId)
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "This tenant has no owner invitation"));
    }

    @PostMapping("/api/v1/control-plane/tenants/{tenantId}/owner-invitation/resend")
    @RequiresCapability(value = Capability.TENANT_ONBOARDING_MANAGE, mutating = true)
    @Operation(
            summary = "Send the owner's invitation again",
            description = "With a new link: the one already sent stops working at once. Refused once the "
                    + "owner has set up their account.")
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
