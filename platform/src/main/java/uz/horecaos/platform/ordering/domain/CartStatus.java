package uz.horecaos.platform.ordering.domain;

/**
 * The cart lifecycle from ADR 0019.
 *
 * <pre>
 * ACTIVE -&gt; CHECKOUT_IN_PROGRESS -&gt; CONVERTED
 * ACTIVE -&gt; EXPIRED | ABANDONED
 * CHECKOUT_IN_PROGRESS -&gt; ACTIVE            when a recoverable validation fails
 * </pre>
 *
 * <p>The return edge matters: a checkout refused because a dish sold out must
 * leave the customer with their basket, not with a cart stuck in a state they
 * cannot edit.
 */
public enum CartStatus {
    ACTIVE,
    CHECKOUT_IN_PROGRESS,
    CONVERTED,
    EXPIRED,
    /** Abandoned by the customer, or superseded by a rebuild at another location. */
    ABANDONED;

    public boolean editable() {
        return this == ACTIVE;
    }
}
