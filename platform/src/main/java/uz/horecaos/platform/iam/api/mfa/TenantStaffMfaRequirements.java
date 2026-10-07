package uz.horecaos.platform.iam.api.mfa;

import java.util.UUID;

/**
 * A tenant's second-factor setting, read through ADR 0030 (ADR 0148).
 *
 * <p>Implemented by {@code tenancy}, which owns configuration; {@code iam} asks, because the
 * other direction -- {@code iam} importing {@code tenancy.api} to resolve a key -- is the module
 * cycle {@code ModularArchitectureTests} exists to catch, exactly as it is for
 * {@code iam.api.grants.ScopedGrantDirectory}.
 */
public interface TenantStaffMfaRequirements {

    /** The mode in force for the tenant at tenant scope; {@link MfaRequirementMode#OFF} when none is set. */
    MfaRequirementMode modeFor(UUID tenantId);
}
