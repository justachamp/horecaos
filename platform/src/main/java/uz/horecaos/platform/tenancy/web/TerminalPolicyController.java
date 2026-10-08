package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.terminals.TerminalPolicy.AttributionMode;
import uz.horecaos.platform.iam.api.terminals.TerminalPolicy.RosterDisplay;
import uz.horecaos.platform.tenancy.application.TerminalPolicyService;
import uz.horecaos.platform.tenancy.application.TerminalPolicyService.PolicyChange;
import uz.horecaos.platform.tenancy.application.TerminalPolicyService.PolicyView;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Staff → Терминалы → Policy (ADR 0155, Decision 7): the five tenant values, each with where it comes from.
 *
 * <p>Both endpoints are held at {@code TENANT} scope -- a location manager's grant does not reach them -- and the five keys are
 * not tenant-visible in the generic configuration editor, so this card is their only write path and the capability is the
 * control.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/terminal-policy")
@Tag(
        name = "Terminal policy",
        description = "ADR 0155: the tenant's PIN length, auto-lock, session length, roster and default mode")
public class TerminalPolicyController {

    private final TerminalPolicyService policy;
    private final CurrentActor currentActor;

    public TerminalPolicyController(TerminalPolicyService policy, CurrentActor currentActor) {
        this.policy = policy;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.TERMINAL_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The tenant's terminal policy, resolved",
            description =
                    "The five values, each with its source (DEFAULT, PLATFORM or TENANT), the guess odds of each PIN "
                            + "length stated against the lockout, and the lockout itself -- code constants, not settings.")
    public ResponseEntity<PolicyView> read(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(policy.view(tenantId));
    }

    @PutMapping
    @RequiresCapability(value = Capability.TERMINAL_MANAGE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Save the tenant's terminal policy",
            description =
                    "Writes all five values in one transaction; every field is required. pinLength is 4 or 6 and applies "
                            + "to the next PIN set (an existing PIN keeps its length), autoLockSeconds 30 to 3,600, sessionMaxHours 1 "
                            + "to 16. attributionDefault is the mode a newly approved device starts in; existing devices keep theirs.")
    public ResponseEntity<PolicyView> save(@PathVariable UUID tenantId, @Valid @RequestBody SavePolicyRequest body) {
        PolicyChange change = new PolicyChange(
                body.pinLength(),
                body.autoLockSeconds(),
                body.sessionMaxHours(),
                body.rosterDisplay(),
                body.attributionDefault());
        String reason = body.reason() == null || body.reason().isBlank()
                ? "Terminal policy saved from Staff → Терминалы"
                : body.reason().strip();
        return ResponseEntity.ok(
                policy.save(tenantId, change, currentActor.get().subject(), reason));
    }

    record SavePolicyRequest(
            @NotNull Integer pinLength,
            @NotNull Integer autoLockSeconds,
            @NotNull Integer sessionMaxHours,
            @NotNull RosterDisplay rosterDisplay,
            @NotNull AttributionMode attributionDefault,
            @Nullable @Size(max = 500) String reason) {}
}
