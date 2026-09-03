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
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CapabilityView;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.TenantOrganizationDirectory;
import uz.horecaos.platform.iam.api.TenantRoleCatalog;
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

    /**
     * "Can this principal do this, on this resource, and why" (control-plane
     * IA 7.3), for a subject the caller names rather than themselves.
     *
     * <p>Reuses exactly what {@link #sessionContext} already reuses for the
     * signed-in principal: {@link AuthorizationService#viewFor}, which is the
     * grants cache's own read (ADR 0003). The difference is authorization
     * over who may ask the question. {@code /session/context} needs none,
     * because a principal reading its own capabilities cannot see anything a
     * 403 would not already have told it; reading a colleague's capability
     * set is a different act and requires platform-admin.
     *
     * <p>{@code capability} is optional. Naming one adds the direct yes/no
     * answer ({@code granted}) on top of the full view, computed the same
     * way the server itself decides every mutation ({@link
     * AuthorizationService#has}) rather than by re-deriving it from the
     * capability set client-side.
     */
    @GetMapping("/control-plane/access-debugger")
    @RequiresCapability(value = Capability.PLATFORM_ADMIN, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "Explain a named principal's effective access",
            description = "platform-admin only: this reveals another principal's grants, which is a "
                    + "different act from a principal reading its own session context.")
    AccessDebugResponse debugAccess(
            @RequestParam String subject,
            @RequestParam(required = false) @Nullable UUID tenantId,
            @RequestParam(required = false) @Nullable UUID brandId,
            @RequestParam(required = false) @Nullable UUID locationId,
            @RequestParam(required = false) @Nullable Capability capability) {

        CapabilityView view = viewForNullableTenant(subject, tenantId);
        Boolean granted = capability == null
                ? null
                : authorization.has(subject, capability, scopeOf(tenantId, brandId, locationId));
        return new AccessDebugResponse(view, capability, granted);
    }

    /**
     * {@link AuthorizationService#viewFor} is declared to take a non-null
     * tenant id, but its one production implementation already branches on a
     * null one — {@link #sessionContext} passes a possibly-absent {@code
     * tenantId} through to it today for exactly the platform-scope case this
     * debugger also needs. Isolated here rather than widening the shared
     * interface, which several unrelated test doubles across other modules
     * implement with a non-null parameter and would stop compiling.
     */
    @SuppressWarnings("NullAway")
    private CapabilityView viewForNullableTenant(String subject, @Nullable UUID tenantId) {
        return authorization.viewFor(subject, tenantId);
    }

    /** Neither PLATFORM, TENANT, BRAND, nor LOCATION is assumed; the caller's own identifiers decide it. */
    private static ResourceScope scopeOf(@Nullable UUID tenantId, @Nullable UUID brandId, @Nullable UUID locationId) {
        if (tenantId == null) {
            return ResourceScope.platform();
        }
        if (brandId == null) {
            return ResourceScope.tenant(tenantId);
        }
        if (locationId == null) {
            return ResourceScope.brand(tenantId, brandId);
        }
        return ResourceScope.location(tenantId, brandId, locationId);
    }

    /**
     * @param requestedCapability echoes what the caller asked about, absent when
     *                            they only wanted the effective view
     * @param granted             null when requestedCapability is null; otherwise the
     *                            server's own {@link AuthorizationService#has} answer
     */
    public record AccessDebugResponse(
            CapabilityView view,
            @Nullable Capability requestedCapability,
            @Nullable Boolean granted) {}

    @GetMapping("/control-plane/tenants/{tenantId}/grants")
    @RequiresCapability(Capability.IAM_GRANT_MANAGE)
    @Operation(
            summary = "List grants within a tenant",
            description = "Active only by default. Pass includeInactive=true for staff-and-access.md "
                    + "§2's suspended-row state and §11.2's restore action, which both need to see a "
                    + "revoked grant, not only what remains active.")
    List<GrantManagementService.GrantView> list(
            @PathVariable UUID tenantId,
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
        return grants.listForTenant(tenantId, includeInactive);
    }

    @GetMapping("/control-plane/tenants/{tenantId}/roles")
    @RequiresCapability(Capability.IAM_GRANT_MANAGE)
    @Operation(
            summary = "List the platform-defined jobs a tenant may see",
            description = "The eight tenant-visible PlatformRole bundles (staff-and-access.md §5 "
                    + "\"Должности\"), each with its capability codes. platform-admin and "
                    + "platform-support are never included. Tenant-defined roles are not yet "
                    + "supported (ADR 0025's deferred item) and so are not yet in this list.")
    List<TenantRoleCatalog.RoleDescriptor> roles(@PathVariable UUID tenantId) {
        return TenantRoleCatalog.tenantVisible();
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
