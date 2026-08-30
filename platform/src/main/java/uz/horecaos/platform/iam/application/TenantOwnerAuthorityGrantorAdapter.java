package uz.horecaos.platform.iam.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.grants.TenantOwnerAuthorityGrantor;

/**
 * Wires the ADR 0009 owner-link step to {@link GrantManagementService}
 * without exposing that service — or a general grant port — outside {@code
 * iam}.
 *
 * <p>{@code iam.application} carries no {@code @NamedInterface}, so nothing
 * outside this module may import {@link GrantManagementService} directly
 * (the same boundary rule ADR 0009 records for {@code OrganizationProvisioner}).
 * This adapter is the one thing {@code tenancy}'s onboarding workflow is
 * allowed to hold, and it can do exactly one thing: confer the fixed
 * {@code tenant-owner} role at tenant scope.
 */
@Component
public class TenantOwnerAuthorityGrantorAdapter implements TenantOwnerAuthorityGrantor {

    /**
     * Recorded as {@code granted_by} instead of a Keycloak subject, so the
     * audit trail can tell this apart from a person's own action. Not a
     * Keycloak subject and never resolvable as one.
     */
    static final String SYSTEM_ACTOR = "system:tenant-onboarding";

    private final GrantManagementService grants;

    public TenantOwnerAuthorityGrantorAdapter(GrantManagementService grants) {
        this.grants = grants;
    }

    @Override
    public void grantTenantOwner(UUID tenantId, String subjectId, String reason) {
        grants.grantSystemInitiated(
                new GrantManagementService.GrantCommand(
                        subjectId, PlatformRole.TENANT_OWNER.code(), ResourceScope.tenant(tenantId), reason, null),
                SYSTEM_ACTOR);
    }
}
