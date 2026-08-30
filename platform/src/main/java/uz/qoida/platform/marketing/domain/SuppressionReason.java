package uz.qoida.platform.marketing.domain;

import java.time.Duration;
import java.util.Optional;

/**
 * Why a customer is suppressed, and for how long (ADR 0044).
 *
 * <p>Suppression is absent from the competitor entirely, and it is a different
 * fact from consent: consent is legal permission, suppression is a deliverability
 * or abuse fact, and one customer can carry both.
 *
 * <p>The expiry differences are the whole reason this is an enum with behaviour.
 * {@link #UNSUBSCRIBE} never expires on its own, because it records a person's
 * refusal and letting it lapse re-enables what they refused. {@link #HARD_BOUNCE}
 * and {@link #INVALID_NUMBER} expire after twelve months, because numbers are
 * reassigned in this market and a permanent block on a recycled number silences a
 * different, willing person. The accepted cost of that is stated in the ADR: a
 * number that is still bad will occasionally be messaged again.
 */
public enum SuppressionReason {

    UNSUBSCRIBE(null),
    HARD_BOUNCE(Duration.ofDays(365)),
    INVALID_NUMBER(Duration.ofDays(365)),
    COMPLAINT(null),
    OPERATOR_BLOCK(null),

    /**
     * Settable only by the control plane, and how Qoida stops a tenant messaging
     * someone who complained to a regulator. A tenant operator who could set it
     * could also lift it, which would make it worth nothing.
     */
    PLATFORM_BLOCK(null);

    private final Duration lifetime;

    SuppressionReason(Duration lifetime) {
        this.lifetime = lifetime;
    }

    /** How long this suppression lasts, or empty when it lasts until lifted. */
    public Optional<Duration> lifetime() {
        return Optional.ofNullable(lifetime);
    }

    /** Whether only the control plane may record this reason. */
    public boolean isControlPlaneOnly() {
        return this == PLATFORM_BLOCK;
    }
}
