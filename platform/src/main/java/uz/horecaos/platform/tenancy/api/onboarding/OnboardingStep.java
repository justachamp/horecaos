package uz.horecaos.platform.tenancy.api.onboarding;

import java.util.Arrays;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The onboarding step catalogue (ADR 0008).
 *
 * <p>All twelve now have handlers except {@code TENANT_ACTIVATE}, which is
 * never blocked in the first place — it waits on a platform administrator, not
 * a missing capability. The seven that used to stay materialised {@code
 * BLOCKED} — because a template that silently skips a check is
 * indistinguishable from one that passed it — are unblocked as of 2026-08-30,
 * each backed by a real handler in {@code OnboardingStepHandlers} (or, for
 * {@code ACTIVATION_SMOKE_TEST}, in {@code ordering} — see that handler's own
 * javadoc for why it could not live alongside the rest without closing a
 * module cycle).
 *
 * <p>Each also moves from optional to {@link #requiredInV1()}. The reasoning
 * that justified {@code false} was never "this check does not matter" — it was
 * "requiring a check before its capability exists would mean no tenant could
 * ever activate", which stopped applying the moment each capability shipped.
 * Left optional, a failing payment or delivery configuration would not block
 * {@code READY}, and the validation phase would be validating nothing a
 * platform administrator could rely on before approving activation.
 */
public enum OnboardingStep {
    KEYCLOAK_ORGANIZATION_RECONCILE(1, Phase.PROVISIONING, true, null),
    TENANT_OWNER_LINK_OR_INVITE(2, Phase.PROVISIONING, true, null),
    DEFAULT_CONFIGURATION_APPLY(3, Phase.CONFIGURING, true, null),
    BRANDS_AND_LOCATIONS_VALIDATE(4, Phase.VALIDATING, true, null),

    PAYMENT_CONFIGURATION_VALIDATE(5, Phase.VALIDATING, true, null),
    DELIVERY_CONFIGURATION_VALIDATE(6, Phase.VALIDATING, true, null),
    POS_BINDINGS_VALIDATE(7, Phase.VALIDATING, true, null),
    CATALOG_READINESS_VALIDATE(8, Phase.VALIDATING, true, null),
    MEDIA_READINESS_VALIDATE(9, Phase.VALIDATING, true, null),
    FRONTEND_DOMAIN_VALIDATE(10, Phase.VALIDATING, true, null),
    ACTIVATION_SMOKE_TEST(11, Phase.VALIDATING, true, null),

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
    private final @Nullable String blockedUntil;

    OnboardingStep(int sequence, Phase phase, boolean requiredInV1, @Nullable String blockedUntil) {
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
     * Required for activation in template v1. Every step but {@code
     * TENANT_ACTIVATE} itself (which is a decision, not a check) is required
     * as of 2026-08-30: identity, structure, and every readiness validation
     * that now has a real handler behind it.
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
