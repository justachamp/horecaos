package uz.qoida.platform.pos.api;

/**
 * How well a provider does something (ADR 0011).
 *
 * <p>Three values rather than a boolean, and the middle one is the reason. A
 * capability that works in one half of its range and silently does nothing in
 * the other is the failure mode that reaches a customer: an order cancellation
 * that works before the till accepted the order and sets a decorative label
 * afterwards is not "supported", and it is not "unsupported" either. Calling it
 * either would put a wrong statement in front of whoever configures the branch.
 */
public enum CapabilitySupport {

    /** The provider does this, and the adapter implements it. */
    SUPPORTED,

    /**
     * The provider does part of this. The stored rationale must say which part,
     * because a caller that reads PARTIAL as SUPPORTED discovers the missing half
     * during service.
     */
    PARTIAL,

    /**
     * The provider does not do this. It cannot be enabled on a binding; the
     * database refuses it, so the refusal does not depend on a control-plane
     * screen remembering to check.
     */
    UNSUPPORTED;

    /** Whether a binding may enable this at all. */
    public boolean configurable() {
        return this != UNSUPPORTED;
    }
}
