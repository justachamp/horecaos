package uz.horecaos.platform.iam.api.grants;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * Grant and revoke a role at any scope, for a caller outside {@code iam}
 * that has already decided who is asking.
 *
 * <p>Unlike {@link PlatformGrantAuthority} ({@code PLATFORM} scope only) and
 * {@link TenantOwnerAuthorityGrantor} (one fixed role, one fixed scope),
 * this is the general shape a staff-management flow needs: an operator
 * choosing an arbitrary role at an arbitrary {@code BRAND}/{@code
 * LOCATION}/{@code TENANT} scope — the same operation the People screen's
 * own "Add job" already performs from inside {@code
 * iam.web.GrantController}. This interface crosses no enforcement out of
 * {@code iam}: {@code GrantManagementService} still confers only what the
 * granter already holds, at a scope it already covers, before either method
 * writes a row — the same rule {@link PlatformGrantAuthority}'s own doc
 * names. It exists only so that a module such as {@code tenancy}, granting a
 * new staff member's first job as part of inviting them, need not depend on
 * {@code iam.application.GrantManagementService} directly, which {@code
 * ModularArchitectureTests} refuses as a dependency on a non-exposed type.
 */
public interface GrantAuthority {

    /**
     * Grants one role to one principal at one scope.
     *
     * @param validUntil null for an open-ended grant
     * @return the new grant's id
     */
    UUID grant(
            String principalSubject,
            String roleCode,
            ResourceScope scope,
            String reason,
            @Nullable Instant validUntil,
            String granterSubject);

    /**
     * Revokes an active grant.
     *
     * @param tenantId null only for a {@code PLATFORM}-scope grant
     * @return whether an active grant was found and revoked
     */
    boolean revoke(@Nullable UUID tenantId, UUID grantId, String revokerSubject, String reason);
}
