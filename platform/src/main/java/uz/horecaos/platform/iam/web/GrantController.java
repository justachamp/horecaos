package uz.horecaos.platform.iam.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CapabilityView;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.TenantOrganizationDirectory;
import uz.horecaos.platform.iam.application.GrantManagementService;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/** Grant management and the session context the frontend shapes itself from (ADR 0025). */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "Authorization", description = "Capability grants and session context")
public class GrantController {

    private final GrantManagementService grants;
    private final AuthorizationService authorization;
    private final CurrentActor currentActor;
    private final TenantOrganizationDirectory tenantOrganizations;

    public GrantController(
            GrantManagementService grants,
            AuthorizationService authorization,
            CurrentActor currentActor,
            TenantOrganizationDirectory tenantOrganizations) {
        this.grants = grants;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.tenantOrganizations = tenantOrganizations;
    }

    /**
     * What the caller may do, so a frontend can hide controls it cannot use.
     *
     * <p>Deliberately not an authorization decision. Every mutation is
     * authorized again on the server, and a test asserts this view and server
     * enforcement agree.
     */
    @GetMapping("/session/context")
    @Operation(summary = "Capabilities and scopes for the current principal")
    CapabilityView sessionContext(
            @org.springframework.web.bind.annotation.RequestParam(required = false) UUID tenantId) {
        var actor = currentActor.get();
        // Without an explicit tenant, resolve one from the token's signed
        // organization claim (ADR 0003). A staff frontend has no other way to
        // learn its tenant: before this fallback, a tenant owner signing in
        // saw the platform-scope view — zero capabilities, an empty nav —
        // while holding a full grant. Exactly one resolvable organization
        // picks that tenant; none or several keeps the platform view, since
        // guessing among tenants is not this endpoint's call to make.
        if (tenantId == null) {
            var resolved = actor.organizationRoles().keySet().stream()
                    .map(tenantOrganizations::tenantIdForKeycloakOrganization)
                    .flatMap(java.util.Optional::stream)
                    .limit(2)
                    .toList();
            if (resolved.size() == 1) {
                tenantId = resolved.getFirst();
            }
        }
        return authorization.viewFor(actor.subject(), tenantId);
    }

    @GetMapping("/control-plane/tenants/{tenantId}/grants")
    @RequiresCapability(Capability.IAM_GRANT_MANAGE)
    @Operation(summary = "List active grants within a tenant")
    List<GrantManagementService.GrantView> list(@PathVariable UUID tenantId) {
        return grants.listForTenant(tenantId);
    }

    @PostMapping("/control-plane/tenants/{tenantId}/grants")
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, mutating = true)
    @Operation(
            summary = "Grant a role at a scope",
            description = "A granter may confer only capabilities it already holds, "
                    + "at a scope it already covers. platform-admin is never grantable here.")
    ResponseEntity<Map<String, Object>> grant(@PathVariable UUID tenantId, @Valid @RequestBody GrantRequest request) {

        UUID grantId = grants.grant(
                new GrantManagementService.GrantCommand(
                        request.principalSubject(),
                        request.roleCode(),
                        scopeOf(tenantId, request),
                        request.reason(),
                        request.validUntil()),
                currentActor.get().subject());

        return ResponseEntity.ok(Map.of("grantId", grantId));
    }

    @DeleteMapping("/control-plane/tenants/{tenantId}/grants/{grantId}")
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, mutating = true)
    @Operation(
            summary = "Revoke a grant",
            description = "Takes effect immediately; the cached grant is evicted rather than left to expire.")
    ResponseEntity<Map<String, Object>> revoke(
            @PathVariable UUID tenantId, @PathVariable UUID grantId, @Valid @RequestBody ReasonRequest request) {

        boolean revoked = grants.revoke(tenantId, grantId, currentActor.get().subject(), request.reason());
        return ResponseEntity.ok(Map.of("changed", revoked, "outcome", revoked ? "revoked" : "no_change"));
    }

    private static ResourceScope scopeOf(UUID tenantId, GrantRequest request) {
        if (request.locationId() != null) {
            return ResourceScope.location(tenantId, request.brandId(), request.locationId());
        }
        if (request.brandId() != null) {
            return ResourceScope.brand(tenantId, request.brandId());
        }
        return ResourceScope.tenant(tenantId);
    }

    /**
     * A request to grant one role to one principal, at the tenant, brand, or
     * location scope the path and body together name.
     *
     * @param validUntil set it for support access, which should lapse on its own
     *                   rather than waiting for someone to remember
     */
    public record GrantRequest(
            @NotBlank @Size(max = 255) String principalSubject,
            @NotBlank @Size(max = 64) String roleCode,
            UUID brandId,
            UUID locationId,
            @NotBlank @Size(max = 1000) String reason,
            Instant validUntil) {}

    public record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}
}
