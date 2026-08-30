package uz.horecaos.platform.ordering.domain;

/**
 * What caused a transition (ADR 0019, recorded on every history row).
 *
 * <p>Separate from the actor: "a person clicked reject" and "the approval
 * deadline lapsed" can both produce the same actor type on a background thread,
 * and telling them apart afterwards is the difference between a restaurant that
 * declined an order and one that never looked at it.
 */
public enum TransitionTrigger {
    CHECKOUT,
    APPROVAL_DECISION,
    APPROVAL_TIMEOUT,
    PAYMENT_RESULT,
    OPERATIONS_ACTION,

    /**
     * A kitchen ticket's roll-up proposed it (ADR 0041).
     *
     * <p>Distinct from {@link #OPERATIONS_ACTION} even though a person pressed
     * both buttons, because ADR 0041 names the operations order list as the
     * <em>fallback</em> control for exactly these two transitions. Recording them
     * the same way would leave "which orders did the kitchen screen actually
     * drive" unanswerable from the history — the question a rollback of the pilot
     * has to answer before it can decide whether the screen was working.
     */
    KITCHEN_PROGRESS,

    CUSTOMER_ACTION,
    SYSTEM
}
