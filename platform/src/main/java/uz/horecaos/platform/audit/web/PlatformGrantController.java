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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.application.PlatformGrantService;
import uz.horecaos.platform.audit.application.PlatformGrantService.Outcome;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.grants.PlatformGrantAuthority;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * {@code PLATFORM}-scope grants (ADR 0025, ADR 0027, Gap A of the 2026-08-30
 * proving run).
 *
 * <p>Before this existed, no HTTP path anywhere in this codebase could create
 * a {@code PLATFORM}-scope grant — {@code GrantController}'s own grant
 * endpoint is nested under {@code /control-plane/tenants/{tenantId}/grants}
 * and its {@code scopeOf()} can only ever construct a {@code TENANT}, {@code
 * BRAND}, or {@code LOCATION} scope from that path. Every test fixture and
 * every proving run needing a platform administrator wrote the row directly
 * into {@code iam.grants} instead, citing exactly that absence. This closes
 * it — for every grant after the very first one, which {@code
 * PlatformAdminBootstrapReconciler} closes from configuration, because the
 * very first platform-admin cannot call an endpoint that itself requires
 * holding {@code iam.grant.manage} at {@code PLATFORM} scope.
 *
 * <p>Lives in {@code audit.web} rather than {@code iam.web}: it has to
 * orchestrate both the grant write ({@link PlatformGrantAuthority}, an {@code
 * iam.api} port) and ADR 0027's maker-checker ({@link PlatformGrantService},
 * which depends on {@code audit.api.ApprovalService}) — see {@link
 * PlatformGrantAuthority}'s own javadoc for why that combination cannot live
 * in {@code iam} without closing a module cycle.
 *
 * <p>Callable only by a principal already holding {@code iam.grant.manage} at
 * {@code PLATFORM} scope — the {@code JdbcAuthorizationService} bootstrap
 * bypass for a Keycloak {@code platform-admin} realm-role holder, or an
 * actual {@code PLATFORM}-scope grant conferring it (exactly what the
 * bootstrap reconciler now creates). {@code GrantManagementService} enforces
 * the identical rule again on the write path: a granter may confer only what
 * it already holds, and {@code platform.admin} itself is never grantable
 * through any HTTP surface, this one included.
 */
@RestController
@RequestMapping("/api/v1/control-plane/grants")
@Tag(name = "Platform authorization", description = "PLATFORM-scope capability grants (ADR 0025, ADR 0027)")
public class PlatformGrantController {

    private final PlatformGrantService grants;
    private final CurrentActor currentActor;

    public PlatformGrantController(PlatformGrantService grants, CurrentActor currentActor) {
        this.grants = grants;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, scope = ScopeType.PLATFORM)
    @Operation(summary = "List active PLATFORM-scope grants")
    List<PlatformGrantAuthority.PlatformGrantView> list() {
        return grants.list();
    }

    @PostMapping
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Grant a PLATFORM-scope role",
            description = "A granter may confer only capabilities it already holds. platform-admin is never "
                    + "grantable here. Subject to ADR 0027 approval when a tenant has configured a policy for "
                    + "iam.platform-grant.manage; absent one, a single signature suffices (ADR 0050).")
    ResponseEntity<PlatformGrantResponse> grant(@Valid @RequestBody PlatformGrantRequest request) {
        Outcome outcome = grants.grant(
                request.principalSubject(),
                request.roleCode(),
                request.reason(),
                request.validUntil(),
                currentActor.get().subject());
        return ResponseEntity.ok(PlatformGrantResponse.of(outcome));
    }

    @DeleteMapping("/{grantId}")
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Revoke a PLATFORM-scope grant",
            description = "Subject to the same ADR 0027 approval gate as granting one.")
    ResponseEntity<PlatformGrantResponse> revoke(
            @PathVariable UUID grantId, @Valid @RequestBody ReasonRequest request) {
        Outcome outcome = grants.revoke(grantId, currentActor.get().subject(), request.reason());
        return ResponseEntity.ok(PlatformGrantResponse.of(outcome));
    }

    /**
     * Request to grant a {@code PLATFORM}-scope role to a principal.
     *
     * @param validUntil set it for support access, which should lapse on its own
     *                   rather than waiting for someone to remember
     */
    public record PlatformGrantRequest(
            @NotBlank @Size(max = 255) String principalSubject,
            @NotBlank @Size(max = 64) String roleCode,
            @NotBlank @Size(max = 1000) String reason,
            Instant validUntil) {}

    public record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}

    public record PlatformGrantResponse(
            String outcome,
            @Nullable UUID grantId,
            @Nullable UUID approvalRequestId) {

        static PlatformGrantResponse of(Outcome outcome) {
            return new PlatformGrantResponse(outcome.status().name(), outcome.grantId(), outcome.approvalRequestId());
        }
    }
}
