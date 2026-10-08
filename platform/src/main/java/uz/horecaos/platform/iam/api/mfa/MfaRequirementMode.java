package uz.horecaos.platform.iam.api.mfa;

import java.util.Locale;
import java.util.Optional;

/**
 * What a tenant asks of its staff (ADR 0148, ADR 0030 key {@code iam.staff_mfa_requirement}).
 *
 * <p>{@link #OFF} is the default and a tenant that never opens the setting keeps it. Platform
 * accounts are outside this switch: every account holding a platform-scope grant needs a second
 * factor, on a deploy setting of its own ({@code horecaos.iam.mfa.enforcement}).
 */
public enum MfaRequirementMode {
    /** Nobody is asked; an enrolled account is still asked for its code, because Keycloak enforces it. */
    OFF,

    /** The owner, the administrator, finance and the brand manager ({@code PlatformRole#mfaSensitive}). */
    SENSITIVE_ROLES,

    /** Every account holding a job in the tenant. */
    ALL_STAFF;

    public static Optional<MfaRequirementMode> parse(String text) {
        try {
            return Optional.of(valueOf(text.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException unknown) {
            return Optional.empty();
        }
    }

    /** The stored form of the setting: the constant's name. */
    public String code() {
        return name();
    }
}
