package uz.horecaos.platform.iam.application.mfa;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.api.mfa.MfaRequirementMode;
import uz.horecaos.platform.iam.api.mfa.StaffMfaAdministration.Requirement;
import uz.horecaos.platform.iam.api.mfa.TenantStaffMfaRequirements;

/**
 * Who must hold a second factor (ADR 0148, Decision 4).
 *
 * <p>Two rules, and they meet by taking the stricter. An account holding a platform-scope grant
 * needs one, always, on a deploy setting that ships in three phases -- {@code OFF}, {@code
 * PROMPT} (offer, do not require) and {@code REQUIRED}. A tenant account needs one when its
 * tenant's setting says so: {@code SENSITIVE_ROLES} for the owner, the administrator, finance and
 * the brand manager; {@code ALL_STAFF} for everybody; {@code OFF} (the default) for nobody.
 * Device principals and customers are outside the record.
 *
 * <p>Read fresh on every sign-in. A requirement decided from a cached grant is a factor skipped
 * for as long as the cache lives, and a revoked role must stop being asked for at once.
 */
@Component
public class MfaPolicy {

    /** The platform rule's phases, on {@code horecaos.iam.mfa.enforcement}. */
    public enum PlatformEnforcement {
        OFF,
        PROMPT,
        REQUIRED;

        static PlatformEnforcement parse(String text) {
            try {
                return valueOf(text.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                // A mistyped setting must not quietly switch the control off.
                throw new IllegalStateException(
                        "horecaos.iam.mfa.enforcement must be OFF, PROMPT or REQUIRED, not \"" + text + "\"");
            }
        }

        Requirement requirement() {
            return switch (this) {
                case OFF -> Requirement.NOT_REQUIRED;
                case PROMPT -> Requirement.OFFERED;
                case REQUIRED -> Requirement.REQUIRED;
            };
        }
    }

    private final StaffAccountFacts grants;
    private final TenantStaffMfaRequirements tenants;
    private final PlatformEnforcement platform;

    public MfaPolicy(
            StaffAccountFacts grants,
            TenantStaffMfaRequirements tenants,
            @Value("${horecaos.iam.mfa.enforcement:OFF}") String enforcement) {
        this.grants = grants;
        this.tenants = tenants;
        this.platform = PlatformEnforcement.parse(enforcement);
    }

    /**
     * @param realmPlatformAdmin whether the sign-in's own token carries the {@code platform-admin}
     *     realm role: the bootstrap administrator may hold no grant row, and is the account the
     *     rule exists for
     */
    public Requirement requirementFor(String subjectId, boolean realmPlatformAdmin) {
        Requirement result = Requirement.NOT_REQUIRED;
        if (realmPlatformAdmin || grants.holdsPlatformGrant(subjectId)) {
            result = stricter(result, platform.requirement());
        }
        for (Map.Entry<UUID, Set<String>> held : grants.rolesByTenant(subjectId).entrySet()) {
            if (result == Requirement.REQUIRED) {
                break;
            }
            result = stricter(result, tenantRequirement(held.getKey(), held.getValue()));
        }
        return result;
    }

    private Requirement tenantRequirement(UUID tenantId, Set<String> heldRoleCodes) {
        // A kitchen device and a support session are grants without a colleague behind them
        // (StaffMembers.MACHINE_ROLE_CODES): neither is a person the tenant asked to carry a phone.
        Set<String> roleCodes = heldRoleCodes.stream()
                .filter(code ->
                        PlatformRole.find(code).map(MfaPolicy::isStaffRole).orElse(true))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (roleCodes.isEmpty()) {
            return Requirement.NOT_REQUIRED;
        }
        MfaRequirementMode mode = tenants.modeFor(tenantId);
        return switch (mode) {
            case OFF -> Requirement.NOT_REQUIRED;
            case ALL_STAFF -> Requirement.REQUIRED;
            case SENSITIVE_ROLES ->
                roleCodes.stream().anyMatch(PlatformRole::isMfaSensitive)
                        ? Requirement.REQUIRED
                        : Requirement.NOT_REQUIRED;
        };
    }

    private static boolean isStaffRole(PlatformRole role) {
        return role != PlatformRole.KITCHEN_DEVICE && !role.supportSessionOnly();
    }

    private static Requirement stricter(Requirement left, Requirement right) {
        return left.compareTo(right) >= 0 ? left : right;
    }
}
