package uz.horecaos.platform.tenancy.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.domain.Tenant;

@Component
public class TenantAccessPolicy {

    static final String PLATFORM_ADMIN = "platform-admin";

    private final CurrentActor currentActor;
    private final AuthorizationService authorization;
    private final boolean enforceCapabilities;

    public TenantAccessPolicy(
            CurrentActor currentActor,
            AuthorizationService authorization,
            @Value("${horecaos.authorization.enforce:true}") boolean enforceCapabilities) {
        this.currentActor = currentActor;
        this.authorization = authorization;
        this.enforceCapabilities = enforceCapabilities;
    }

    void requirePlatformAdministrator() {
        if (!currentActor.get().hasGlobalRole(PLATFORM_ADMIN)) {
            throw denied();
        }
    }

    /**
     * ADR 0025 narrows ADR 0003 here.
     *
     * <p>Organization membership alone used to authorise reading every location
     * in a tenant, which meant a single-location employee could read the whole
     * business. Under enforcement, membership establishes tenant context and a
     * capability grant establishes what may be read within it.
     *
     * <p>The narrowing is in force. It sits behind the same
     * {@code horecaos.authorization.enforce} flag as the rest of ADR 0025, which is
     * now an opt-out: turning it off restores ADR 0003's rule that membership
     * alone authorises every read in the tenant.
     */
    void requireTenantRead(Tenant tenant) {
        AuthenticatedActor actor = currentActor.get();
        if (actor.hasGlobalRole(PLATFORM_ADMIN)) {
            return;
        }
        if (!belongsToTenant(actor, tenant)) {
            throw denied();
        }
        if (enforceCapabilities) {
            authorization.require(
                    actor.subject(),
                    Capability.TENANT_READ,
                    ResourceScope.tenant(tenant.id().value()));
        }
    }

    /**
     * ADR 0025 replaces the org-role check here too (Gap C of the 2026-08-30
     * proving run).
     *
     * <p>This used to accept either the global {@code platform-admin} realm
     * role or an org-nested Keycloak client role ({@code tenant-owner}/{@code
     * tenant-admin}) read from the token's own {@code
     * organization.<org>.resource_access} claim. Nothing in this codebase
     * ever assigned that nested role — {@code
     * KeycloakOrganizationProvisioner}'s own class javadoc explains why
     * {@code ensureOrganizationRoles} was deliberately left unimplemented:
     * Keycloak 26.7's Organizations Admin REST API has no organization-scoped
     * role sub-resource at all. Confirmed live: a linked tenant owner —
     * holding a real {@code tenant-owner} grant since ADR 0009's own missing
     * half shipped — got a plain {@code 403} creating their tenant's second
     * brand, from this check alone; every {@code @RequiresCapability}-gated
     * endpoint the same owner reaches (legal entity, catalog, payments,
     * channels) already resolves through {@code iam.grants} correctly.
     *
     * <p>Callers now name the exact capability and scope the controller's own
     * {@code @RequiresCapability} already declared and the interceptor already
     * enforced before this method runs — see {@code
     * TenantControlPlaneController}. Resolving through the same {@link
     * AuthorizationService} every other endpoint uses means a caller who
     * passed the interceptor's check trivially passes this one too, which is
     * what makes the fix behaviour-preserving for every path that already
     * worked: platform-admin, and any grant-holding owner or admin.
     *
     * <p>Organization membership stays the coarse gate ADR 0003 assigns it —
     * a stranger to the organization is refused before the capability is even
     * asked about, exactly as {@link #requireTenantRead} already does.
     */
    void requireTenantManagement(Tenant tenant, Capability capability, ResourceScope scope) {
        AuthenticatedActor actor = currentActor.get();
        if (actor.hasGlobalRole(PLATFORM_ADMIN)) {
            return;
        }
        if (!belongsToTenant(actor, tenant)) {
            throw denied();
        }
        if (enforceCapabilities) {
            authorization.require(actor.subject(), capability, scope);
        }
    }

    private static boolean belongsToTenant(AuthenticatedActor actor, Tenant tenant) {
        return tenant.keycloakOrganizationId().map(actor::belongsToOrganization).orElse(false);
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("The authenticated principal cannot access this tenant resource");
    }
}
