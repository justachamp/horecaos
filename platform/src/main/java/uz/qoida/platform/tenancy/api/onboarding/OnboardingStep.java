package uz.qoida.platform.tenancy.api.onboarding;

import java.util.Arrays;
import java.util.Optional;

/**
 * The onboarding step catalogue (ADR 0008).
 *
 * <p>Seven of these check capabilities that do not exist yet. They are declared
 * anyway and stay {@code BLOCKED} at runtime rather than being omitted, because
 * a template that silently skips a check is indistinguishable from one that
 * passed it. Support must be able to see that a tenant is live *despite* having
 * no catalogue check, not wonder whether one ran.
 */
public enum OnboardingStep {

    KEYCLOAK_ORGANIZATION_RECONCILE(1, Phase.PROVISIONING, true, null),
    TENANT_OWNER_LINK_OR_INVITE(2, Phase.PROVISIONING, true, null),
    DEFAULT_CONFIGURATION_APPLY(3, Phase.CONFIGURING, true, null),
    BRANDS_AND_LOCATIONS_VALIDATE(4, Phase.VALIDATING, true, null),

    PAYMENT_CONFIGURATION_VALIDATE(5, Phase.VALIDATING, false, "ADR 0013"),
    DELIVERY_CONFIGURATION_VALIDATE(6, Phase.VALIDATING, false, "ADR 0014"),
    POS_BINDINGS_VALIDATE(7, Phase.VALIDATING, false, "ADR 0011"),
    CATALOG_READINESS_VALIDATE(8, Phase.VALIDATING, false, "ADR 0016"),
    MEDIA_READINESS_VALIDATE(9, Phase.VALIDATING, false, "ADR 0010"),
    FRONTEND_DOMAIN_VALIDATE(10, Phase.VALIDATING, false, "ADR 0022"),
    ACTIVATION_SMOKE_TEST(11, Phase.VALIDATING, false, "ADR 0019"),

    TENANT_ACTIVATE(12, Phase.ACTIVATING, true, null);

    /** Phases a run moves through; a step belongs to exactly one. */
    public enum Phase {
        PROVISIONING,
        CONFIGURING,
        VALIDATING,
        ACTIVATING
    }

    private final int sequence;
    private final Phase phase;
    private final boolean requiredInV1;
    private final String blockedUntil;

    OnboardingStep(int sequence, Phase phase, boolean requiredInV1, String blockedUntil) {
        this.sequence = sequence;
        this.phase = phase;
        this.requiredInV1 = requiredInV1;
        this.blockedUntil = blockedUntil;
    }

    public int sequence() {
        return sequence;
    }

    public Phase phase() {
        return phase;
    }

    /**
     * Required for activation in template v1: identity and structure only.
     * Requiring a catalogue check before the catalogue module exists would mean
     * no tenant could ever activate.
     */
    public boolean requiredInV1() {
        return requiredInV1;
    }

    /** The ADR that must ship before this step can run, or empty when buildable. */
    public Optional<String> blockedUntil() {
        return Optional.ofNullable(blockedUntil);
    }

    public boolean isBlocked() {
        return blockedUntil != null;
    }

    public static Optional<OnboardingStep> find(String key) {
        return Arrays.stream(values()).filter(step -> step.name().equals(key)).findFirst();
    }
}
