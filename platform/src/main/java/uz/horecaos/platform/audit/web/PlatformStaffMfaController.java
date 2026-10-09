package uz.horecaos.platform.audit.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.application.StaffMfaResetService;
import uz.horecaos.platform.audit.application.StaffMfaResetService.Outcome;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.mfa.StaffMfaAdministration;
import uz.horecaos.platform.iam.api.mfa.StaffMfaAdministration.Authenticator;
import uz.horecaos.platform.iam.api.mfa.StaffMfaAdministration.Requirement;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A platform account's second factor: where it stands, and its reset behind a second signature
 * (ADR 0148, Decision 5 and 6).
 *
 * <p>Lives in {@code audit.web} beside {@link PlatformGrantController}, for the same reason: the
 * reset has to orchestrate the {@code iam} act and ADR 0027's maker-checker, and an approval gate
 * in {@code iam} would close a module cycle. The route is keyed by the Keycloak subject, because
 * a platform administrator has no tenant and so no staff member record to name.
 *
 * <p>Both calls are narrow to platform accounts: a subject that holds no platform-scope grant
 * answers not-found, so asking here discloses nothing about who exists.
 */
@RestController
@RequestMapping("/api/v1/control-plane/staff/{subjectId}/mfa")
@Tag(name = "Platform staff second factor", description = "A platform account's second factor (ADR 0148)")
public class PlatformStaffMfaController {

    private final StaffMfaAdministration mfa;
    private final StaffMfaResetService resets;
    private final CurrentActor currentActor;

    public PlatformStaffMfaController(
            StaffMfaAdministration mfa, StaffMfaResetService resets, CurrentActor currentActor) {
        this.mfa = mfa;
        this.resets = resets;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.IAM_STAFF_MFA_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Whether a platform account holds a second factor",
            description = "The identity provider's own credential list, cached for sixty seconds. A subject "
                    + "that holds no platform-scope grant answers not-found.")
    public PlatformMfaResponse read(@PathVariable String subjectId) {
        if (!mfa.isPlatformAccount(subjectId)) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such platform account.");
        }
        StaffMfaAdministration.MfaStatus status = mfa.status(subjectId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such platform account."));
        return new PlatformMfaResponse(
                status.enrolled(),
                status.authenticators().stream().map(PlatformAuthenticator::of).toList(),
                status.requirement());
    }

    @PostMapping("/resets")
    @RequiresCapability(value = Capability.IAM_STAFF_MFA_RESET, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Reset a platform account's second factor",
            description = "Needs a second signature (ADR 0027, ADR 0050, fail-closed): the first call raises "
                    + "the approval request and answers AWAITING_APPROVAL with its id; once a second "
                    + "administrator approves it, repeating the call removes every authenticator, ends "
                    + "every session, audits the reset with the reason and emails the person. Nobody "
                    + "resets their own.")
    public PlatformResetResponse reset(@PathVariable String subjectId, @Valid @RequestBody PlatformResetRequest body) {
        Outcome outcome = resets.reset(
                subjectId, currentActor.get().subject(), body.reason().strip());
        return PlatformResetResponse.of(outcome);
    }

    public record PlatformResetRequest(
            @NotBlank @Size(max = 1000) String reason) {}

    public record PlatformAuthenticator(
            String id, @Nullable String label, @Nullable Instant createdAt) {

        static PlatformAuthenticator of(Authenticator authenticator) {
            return new PlatformAuthenticator(authenticator.id(), authenticator.label(), authenticator.createdAt());
        }
    }

    public record PlatformMfaResponse(
            boolean enrolled, List<PlatformAuthenticator> authenticators, Requirement requirement) {}

    public record PlatformResetResponse(
            String outcome,
            @Nullable Integer authenticatorsRemoved,
            @Nullable Boolean sessionsEnded,
            @Nullable Boolean personNotified,
            @Nullable UUID approvalRequestId) {

        static PlatformResetResponse of(Outcome outcome) {
            var result = outcome.result();
            return new PlatformResetResponse(
                    outcome.status().name(),
                    result == null ? null : result.authenticatorsRemoved(),
                    result == null ? null : result.sessionsEnded(),
                    result == null ? null : result.personNotified(),
                    outcome.approvalRequestId());
        }
    }
}
