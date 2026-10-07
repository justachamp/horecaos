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
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.ResourceScopeVerifier;
import uz.horecaos.platform.iam.application.GrantManagementService;
import uz.horecaos.platform.iam.application.GrantManagementService.GrantView;
import uz.horecaos.platform.iam.application.GrantManagementService.GrantableRole;
import uz.horecaos.platform.web.authorization.RequiresCapability;
import uz.horecaos.platform.web.authorization.ScopeNotFoundException;

/**
 * A branch's own team, for the manager of that branch (ADR 0103, gap map row 9.1).
 *
 * <p>{@link GrantController}'s grants routes sit under {@code
 * /control-plane/tenants/{tenantId}} and are declared at {@code TENANT} scope,
 * because the person they were written for is the owner and the administrator.
 * A grant covers only the routes whose path names its own level (ADR 0025), so a
 * location manager holding {@code iam.grant.manage} at her branch could never
 * reach them. These are the routes her grant does cover: the same operations,
 * with the place in the path, authorised against it, and answering about it and
 * nothing else — the same pairing {@code StaffMemberController} makes for the
 * people themselves and {@code OperationsCustomerController} makes for opening a
 * customer.
 *
 * <p>Three rules keep this from being a wider door than the tenant routes:
 *
 * <ul>
 *   <li>What she sees is the grants lying at or beneath the place in the path —
 *       never a company-level one, never a sibling's ({@link
 *       GrantManagementService#listWithin}).
 *   <li>What she gives is bounded by {@link GrantManagementService#grant}'s subset
 *       rule and, for a granter whose reach stops short of the company, by the
 *       job's own level ({@code requireWithinABranchGranter}): a company-level job
 *       cannot be conferred "at one branch".
 *   <li>What she takes away is a grant lying inside the place and a job she could
 *       herself have given there ({@link GrantManagementService#revokeWithin}).
 * </ul>
 *
 * <p>A brand route also takes an optional {@code locationId} on a grant, because
 * a brand manager's team is spread across her branches. The pair is verified to
 * be real before it reaches the capability check — {@code covers} would otherwise
 * let a brand manager name a location of another brand and be authorised for it.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}")
@Tag(name = "Branch team access", description = "A branch or brand manager's own grants (ADR 0103)")
public class ScopedGrantController {

    private final GrantManagementService grants;
    private final CurrentActor currentActor;
    private final ResourceScopeVerifier scopes;

    public ScopedGrantController(
            GrantManagementService grants, CurrentActor currentActor, ResourceScopeVerifier scopes) {
        this.grants = grants;
        this.currentActor = currentActor;
        this.scopes = scopes;
    }

    // ================================================================== a branch

    @GetMapping("/brands/{brandId}/locations/{locationId}/grants")
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The jobs people hold at this branch",
            description = "ADR 0103: «the Chilonzor manager sees Chilonzor's team». Grants whose own "
                    + "scope is this location, active only unless includeInactive=true. Never a "
                    + "company-level or brand-level grant and never another branch's.")
    public List<GrantView> listForLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
        return grants.listWithin(ResourceScope.location(tenantId, brandId, locationId), includeInactive);
    }

    @GetMapping("/brands/{brandId}/locations/{locationId}/grant-roles")
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The jobs, and which of them the caller may give at this branch",
            description = "The tenant-visible jobs with their capability codes, each marked "
                    + "grantable=true when the caller could confer it at this branch right now — the "
                    + "answer is the grant route's own refusal read back, so the picker and the "
                    + "button cannot disagree.")
    public List<GrantableRole> rolesForLocation(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return grants.grantableRoles(ResourceScope.location(tenantId, brandId, locationId), subject());
    }

    @GetMapping("/brands/{brandId}/locations/{locationId}/grant-places")
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The names of the places this branch's team is grouped under",
            description = "This branch and its brand, with display names — the manager's own scope "
                    + "directory, since the brand and location lists need a wider grant than hers.")
    public GrantManagementService.PlaceDirectory placesForLocation(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return grants.placesWithin(ResourceScope.location(tenantId, brandId, locationId));
    }

    @PostMapping("/brands/{brandId}/locations/{locationId}/grants")
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Give someone a job at this branch",
            description = "The same audited path as the company-wide grant: a granter may confer only "
                    + "capabilities she already holds at this branch, and, because her grant stops at "
                    + "the branch, only a job no broader than a branch.")
    public ResponseEntity<Map<String, Object>> grantAtLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody ScopedGrantRequest request) {
        UUID grantId = grants.grant(
                new GrantManagementService.GrantCommand(
                        request.principalSubject(),
                        request.roleCode(),
                        ResourceScope.location(tenantId, brandId, locationId),
                        request.reason(),
                        request.validUntil()),
                subject());
        return ResponseEntity.ok(Map.of("grantId", grantId));
    }

    @DeleteMapping("/brands/{brandId}/locations/{locationId}/grants/{grantId}")
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Take a job away at this branch",
            description = "Only a grant lying at this branch, and only a job the caller could herself "
                    + "have given here. Anything else answers changed=false, the same as a grant that "
                    + "does not exist, so a grant id seen elsewhere is not an oracle.")
    public ResponseEntity<Map<String, Object>> revokeAtLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID grantId,
            @Valid @RequestBody ReasonRequest request) {
        boolean revoked = grants.revokeWithin(
                ResourceScope.location(tenantId, brandId, locationId), grantId, subject(), request.reason());
        return ResponseEntity.ok(Map.of("changed", revoked, "outcome", revoked ? "revoked" : "no_change"));
    }

    // =================================================================== a brand

    @GetMapping("/brands/{brandId}/grants")
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, scope = ScopeType.BRAND)
    @Operation(
            summary = "The jobs people hold in this brand",
            description = "Grants whose own scope is this brand or any of its locations, active only "
                    + "unless includeInactive=true. Never a company-level grant and never another "
                    + "brand's.")
    public List<GrantView> listForBrand(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive) {
        return grants.listWithin(ResourceScope.brand(tenantId, brandId), includeInactive);
    }

    @GetMapping("/brands/{brandId}/grant-roles")
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, scope = ScopeType.BRAND)
    @Operation(
            summary = "The jobs, and which of them the caller may give in this brand",
            description = "As the branch route, at brand level; give a job at one branch by naming the "
                    + "branch on the grant.")
    public List<GrantableRole> rolesForBrand(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return grants.grantableRoles(ResourceScope.brand(tenantId, brandId), subject());
    }

    @GetMapping("/brands/{brandId}/grant-places")
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, scope = ScopeType.BRAND)
    @Operation(
            summary = "The names of the places this brand's team is grouped under",
            description = "This brand and every one of its locations, with display names.")
    public GrantManagementService.PlaceDirectory placesForBrand(
            @PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return grants.placesWithin(ResourceScope.brand(tenantId, brandId));
    }

    @PostMapping("/brands/{brandId}/grants")
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Give someone a job in this brand",
            description = "At the brand, or at one of its branches when locationId is given. The "
                    + "branch must belong to this brand: a branch of another brand answers not-found.")
    public ResponseEntity<Map<String, Object>> grantInBrand(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody BrandGrantRequest request) {
        ResourceScope target = brandOrLocation(tenantId, brandId, request.locationId());
        UUID grantId = grants.grant(
                new GrantManagementService.GrantCommand(
                        request.principalSubject(), request.roleCode(), target, request.reason(), request.validUntil()),
                subject());
        return ResponseEntity.ok(Map.of("grantId", grantId));
    }

    @DeleteMapping("/brands/{brandId}/grants/{grantId}")
    @RequiresCapability(value = Capability.IAM_GRANT_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Take a job away in this brand",
            description = "Only a grant lying at this brand or one of its branches, and only a job the "
                    + "caller could herself have given there. Anything else answers changed=false.")
    public ResponseEntity<Map<String, Object>> revokeInBrand(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID grantId,
            @Valid @RequestBody ReasonRequest request) {
        boolean revoked =
                grants.revokeWithin(ResourceScope.brand(tenantId, brandId), grantId, subject(), request.reason());
        return ResponseEntity.ok(Map.of("changed", revoked, "outcome", revoked ? "revoked" : "no_change"));
    }

    /** The brand itself, or one of its own branches — verified to be real, never taken on the body's word. */
    ResourceScope brandOrLocation(UUID tenantId, UUID brandId, @Nullable UUID locationId) {
        ResourceScope target = locationId == null
                ? ResourceScope.brand(tenantId, brandId)
                : ResourceScope.location(tenantId, brandId, locationId);
        if (!scopes.exists(target)) {
            throw new ScopeNotFoundException(target);
        }
        return target;
    }

    private String subject() {
        return currentActor.get().subject();
    }

    /** A job at the branch the path names. */
    public record ScopedGrantRequest(
            @NotBlank @Size(max = 255) String principalSubject,
            @NotBlank @Size(max = 64) String roleCode,
            @NotBlank @Size(max = 1000) String reason,
            @Nullable Instant validUntil) {}

    /** A job at the brand the path names, or at one of its branches. */
    public record BrandGrantRequest(
            @NotBlank @Size(max = 255) String principalSubject,
            @NotBlank @Size(max = 64) String roleCode,
            @Nullable UUID locationId,
            @NotBlank @Size(max = 1000) String reason,
            @Nullable Instant validUntil) {}

    public record ReasonRequest(@NotBlank @Size(max = 1000) String reason) {}
}
