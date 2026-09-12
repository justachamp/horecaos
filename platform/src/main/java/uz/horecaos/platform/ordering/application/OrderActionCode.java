package uz.horecaos.platform.ordering.application;

/**
 * The lifecycle actions the operations console can ever offer on an order
 * (orders.md §4.2, §4.3, §11).
 *
 * <p>A closed set rather than a free-form string, for the reason {@link
 * uz.horecaos.platform.ordering.domain.OrderStateMachine} is closed: a client that
 * matched on an unlisted code would either crash or silently ignore an action
 * nobody meant to hide.
 *
 * <p><b>Wave P05.</b> Widened past the original four ({@link #APPROVE}, {@link
 * #REJECT}, {@link #ADVANCE}, {@link #CANCEL}) to name every mutation {@code
 * OperationsOrderController} already serves without a code that reaches it.
 * {@link #COMPLETE}, {@link #AMEND}, {@link #RESOLVE}, {@link #ASSIGN_COURIER}
 * and {@link #ISSUE_INVOICE} are declared here — so the wire contract names
 * them, and the frontend's forward-compatible rendering (an unrecognised code
 * renders its raw name rather than nothing, {@code order-actions.ts}) is ready
 * to receive whichever ships first — but none of the five is emitted by
 * {@link OrderActionsPolicy#availableFor} yet:
 *
 * <ul>
 *   <li>{@link #COMPLETE} needs the order's fulfilment-mode-appropriate
 *       completion reason from the tenant registry to pick correctly between
 *       «Доставлен» and «Доставлен сторонней службой» (orders.md §4.6); wired
 *       by the wave that owns the completion dialog (gap map {@code P09}).
 *   <li>{@link #AMEND} is the one exception with a built, tested gate ({@code
 *       ORDER_AMEND} plus {@code OrderActionsPolicy.canAmend}) — this wave
 *       built it, then held it back behind {@code
 *       OrderActionsPolicy.AMEND_EMISSION_ENABLED} after an adversarial
 *       review found {@code order-actions.ts} has no translated label or
 *       click handler for it and {@code ORDER_AMEND} already reaches five
 *       real {@code PlatformRole}s. Wave {@code P10} ships the amendment
 *       client and flips the constant; see ADR 0105.
 *   <li>{@link #RESOLVE} targets {@code POST .../amendments/{id}/confirmation}
 *       and only makes sense while a specific amendment is {@code
 *       AWAITING_CUSTOMER_CONFIRMATION} — a fact {@link OrderActionsPolicy}
 *       cannot see without the amendment read that wave owns.
 *   <li>{@link #ASSIGN_COURIER} targets the existing {@code DispatchController}
 *       manual-assignment endpoint (orders.md §4.7), not yet reachable from an
 *       order at all (gap map {@code P11}).
 *   <li>{@link #ISSUE_INVOICE} targets «Выставить счёт», a re-issued payment
 *       invoice (orders.md §4.9), whose endpoint does not exist yet (gap map
 *       {@code P12}).
 * </ul>
 */
public enum OrderActionCode {

    /** {@code POST .../approval-decisions} {@code action:APPROVE}. */
    APPROVE,

    /** {@code POST .../approval-decisions} {@code action:REJECT}. */
    REJECT,

    /** {@code POST .../state-actions}, carrying the target status. */
    ADVANCE,

    /** {@code POST .../cancellations}. */
    CANCEL,

    /**
     * {@code POST .../completion}, naming how the order was completed
     * (orders.md §4.6). Declared, not yet emitted — see the class doc.
     */
    COMPLETE,

    /**
     * {@code POST .../amendments}, opening the amendment submenu (orders.md
     * §4.4). The gate is built (whenever the order has not ended and the
     * principal holds {@code ORDER_AMEND}) but not yet emitted — see the
     * class doc and {@code OrderActionsPolicy.AMEND_EMISSION_ENABLED}.
     */
    AMEND,

    /**
     * {@code POST .../amendments/{amendmentId}/confirmation}, attesting the
     * customer agreed to a pending amendment's change in total. Declared, not
     * yet emitted — see the class doc.
     */
    RESOLVE,

    /**
     * Assign or reassign the order's courier (orders.md §4.7). Declared, not
     * yet emitted — see the class doc.
     */
    ASSIGN_COURIER,

    /**
     * Re-issue a payment invoice (orders.md §4.9, «Выставить счёт»). Declared,
     * not yet emitted — see the class doc.
     */
    ISSUE_INVOICE
}
