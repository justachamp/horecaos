package uz.horecaos.platform.iam.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.grants.PlatformGrantAuthority;
import uz.horecaos.platform.iam.application.GrantManagementService.GrantCommand;

/** Wraps {@link GrantManagementService} behind the narrow {@link PlatformGrantAuthority} port (Gap A). */
@Component
public class PlatformGrantAuthorityAdapter implements PlatformGrantAuthority {

    private final GrantManagementService grants;

    public PlatformGrantAuthorityAdapter(GrantManagementService grants) {
        this.grants = grants;
    }

    @Override
    public UUID grant(
            String principalSubject,
            String roleCode,
            String reason,
            @Nullable Instant validUntil,
            String granterSubject) {
        return grants.grant(
                new GrantCommand(principalSubject, roleCode, ResourceScope.platform(), reason, validUntil),
                granterSubject);
    }

    @Override
    public boolean revoke(UUID grantId, String revokerSubject, String reason) {
        return grants.revoke(null, grantId, revokerSubject, reason);
    }

    @Override
    public List<PlatformGrantView> list() {
        return grants.listPlatformGrants().stream()
                .map(view -> new PlatformGrantView(
                        view.id(), view.principalSubject(), view.roleCode(), view.status(), view.grantedBy()))
                .toList();
    }
}
