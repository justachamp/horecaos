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
 * to receive whichever ships first. {@link #COMPLETE} is now the exception:
 *
 * <ul>
 *   <li>{@link #COMPLETE} is wired (wave P09, gap map {@code 1.2j}) —
 *       {@link OrderActionsPolicy#availableFor} emits it wherever it emits
 *       {@code ADVANCE} with a {@code COMPLETED} target, and {@code POST
 *       .../completion} lets the order detail pane name the fulfilment-mode-
 *       appropriate completion reason from the tenant registry instead of
 *       always recording «Доставлен» via the generic advance. Both action
 *       codes are offered together deliberately — see {@code
 *       OrderActionsPolicy}'s own doc on the pair — so a client built before
 *       this wave keeps working against the {@code ADVANCE} entry.
 *   <li>{@link #AMEND} has a built, tested gate ({@code ORDER_AMEND} plus
 *       {@code OrderActionsPolicy.canAmend}) — wave P05 built it, then held it
 *       back behind {@code OrderActionsPolicy.AMEND_EMISSION_ENABLED} after an
 *       adversarial review found {@code order-actions.ts} had no translated
 *       label or click handler for it and {@code ORDER_AMEND} already reaches
 *       five real {@code PlatformRole}s. Wave {@code P10} shipped the
 *       amendment client and flipped the constant to {@code true}; see ADR
 *       0105 and ADR 0113.
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
     * (orders.md §4.6). Wired — see the class doc.
     */
    COMPLETE,

    /**
     * {@code POST .../amendments}, opening the amendment submenu (orders.md
     * §4.4). The gate is built (whenever the order has not ended and the
     * principal holds {@code ORDER_AMEND}) and, as of wave P10, emitted — see
     * the class doc and {@code OrderActionsPolicy.AMEND_EMISSION_ENABLED}.
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
    ISSUE_INVOICE,

    /**
     * {@code POST .../state-overrides}, a compensating transition that restores
     * an earlier status under {@code Capability.ORDER_STATE_OVERRIDE} (orders.md
     * §0.2, §11.3; ADR 0019 amendment, ADR 0110; wave {@code P41}).
     *
     * <p>Deliberately its own code and its own endpoint rather than a target of
     * {@link #ADVANCE}, for the reason {@link #CANCEL} is already kept separate
     * from {@link #ADVANCE} even though both are edges the same {@link
     * uz.horecaos.platform.ordering.domain.OrderStateMachine} models: a
     * different capability, a mandatory registry reason, and a console dialog
     * that must not be reachable from the ordinary advance button. Emitted
     * (unlike five of this enum's other declared-but-inert values) — {@code
     * OrderActionsPolicy.availableFor} gates it on {@code
     * ORDER_STATE_OVERRIDE} and offers it exactly where {@code
     * OrderStateMachine.compensatingTransitionsFrom} names an edge.
     */
    OVERRIDE
}
