package uz.horecaos.platform.tenancy.application;

import java.util.Set;
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
    static final String TENANT_OWNER = "tenant-owner";
    static final String TENANT_ADMIN = "tenant-admin";

    private static final Set<String> TENANT_MANAGEMENT_ROLES = Set.of(TENANT_OWNER, TENANT_ADMIN);

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

    void requireTenantManagement(Tenant tenant) {
        AuthenticatedActor actor = currentActor.get();
        if (actor.hasGlobalRole(PLATFORM_ADMIN)) {
            return;
        }
        String organizationId = tenant.keycloakOrganizationId().orElseThrow(TenantAccessPolicy::denied);
        boolean permitted =
                TENANT_MANAGEMENT_ROLES.stream().anyMatch(role -> actor.hasOrganizationRole(organizationId, role));
        if (!permitted) {
            throw denied();
        }
    }

    private static boolean belongsToTenant(AuthenticatedActor actor, Tenant tenant) {
        return tenant.keycloakOrganizationId().map(actor::belongsToOrganization).orElse(false);
    }

    private static AccessDeniedException denied() {
        return new AccessDeniedException("The authenticated principal cannot access this tenant resource");
    }
}
