package uz.horecaos.platform.tenancy.application;

import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.mfa.MfaRequirementMode;
import uz.horecaos.platform.iam.api.mfa.TenantStaffMfaRequirements;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * A tenant's second-factor setting, resolved through ADR 0030 for {@code iam} (ADR 0148).
 *
 * <p>Read at tenant scope: the key is settable there and at platform scope, and the platform
 * value is the default every tenant inherits. A stored value that no longer parses -- a mode
 * removed in a later release -- reads as the strictest one rather than the loosest, so that
 * drift can only ever ask for more, never quietly stop asking.
 */
@Component
class StaffMfaRequirementLookup implements TenantStaffMfaRequirements {

    private final ConfigurationResolver resolver;

    StaffMfaRequirementLookup(ConfigurationResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public MfaRequirementMode modeFor(UUID tenantId) {
        String stored = resolver.value(ConfigurationKeys.IAM_STAFF_MFA_REQUIREMENT, ResourceScope.tenant(tenantId));
        if (stored == null) {
            return MfaRequirementMode.OFF;
        }
        return MfaRequirementMode.parse(stored).orElse(MfaRequirementMode.ALL_STAFF);
    }
}
