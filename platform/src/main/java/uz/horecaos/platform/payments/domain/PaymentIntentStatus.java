package uz.horecaos.platform.payments.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * What the order's payment obligation has come to (ADR 0013).
 *
 * <p>Coarser than the attempt's state on purpose. An intent survives every
 * attempt made against it, so it says whether the order is paid and not which
 * provider vocabulary was involved in finding out: an order whose Click attempt
 * timed out and whose Payme attempt succeeded is {@link #PAID}, once, and the two
 * attempts remain individually readable underneath.
 */
public enum PaymentIntentStatus {

    /** Created and not yet presented to anything. A cash intent sits here until handover. */
    PENDING,

    /** At least one attempt is live at a provider. */
    AUTHORIZING,

    PAID,

    CANCELLED,

    EXPIRED,

    FAILED;

    private static final Set<PaymentIntentStatus> OPEN = EnumSet.of(PENDING, AUTHORIZING);

    public boolean open() {
        return OPEN.contains(this);
    }

    /**
     * Whether this intent still occupies the one-live-intent-per-order slot.
     *
     * <p>Mirrors {@code ux_payment_intent_live_per_order}, which includes
     * {@link #PAID}: a paid order must not acquire a second intent, or a second
     * charge becomes a matter of application discipline rather than a constraint.
     */
    public boolean holdsTheOrder() {
        return this != CANCELLED && this != EXPIRED && this != FAILED;
    }
}
