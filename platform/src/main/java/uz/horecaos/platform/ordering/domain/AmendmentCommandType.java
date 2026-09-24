package uz.horecaos.platform.ordering.domain;

/**
 * The closed set of amendment commands (ADR 0039).
 *
 * <p>Intent is declared, never inferred. ADR 0039 rejects a generic field-level
 * diff by name: a diff cannot separate "added a dessert" from "corrected the
 * entrance number", yet the first reprices, re-reserves and re-prints while the
 * second does none of those. The consequence has to be decidable from the command
 * at exactly the moment it must be certain, which is why every entry here has a
 * declared consequence in the quote, the inventory hold, the payment, the fiscal
 * receipt and the POS export.
 *
 * <p>The set being closed is the price of that. A request fitting none of the
 * twelve needs an ADR entry rather than a configuration change — which is
 * exactly how {@link #SET_COURIER_NOTE} and {@link #SET_INTERNAL_NOTE} got
 * here: ADR 0039 declared ten, and ADR 0113 (wave P10) amended it to twelve
 * for the two note channels the legacy dashboard had and this platform did
 * not.
 *
 * <p>Wave 10 (gap map rows {@code 1.2c}/{@code 2.1d}) built six of the seven
 * financial commands: {@link #ADD_LINES} and {@link #CHANGE_LINE_QUANTITY} for
 * an increase only, {@link #CHANGE_PAYMENT_METHOD} for {@code CASH} at either
 * end or opening a fresh online intent, {@link #CHANGE_DELIVERY_ADDRESS},
 * {@link #CHANGE_FULFILLMENT_TIME} and {@link #CHANGE_CONTACT}. Eleven of the
 * twelve are built in total; only {@link #REMOVE_LINES} is still declared and
 * refused — the set is code-owned like {@link OrderStatus}, and a set that is
 * open at the edges cannot express "closed" — because releasing or writing off
 * already-committed stock needs a fourth ADR 0017 primitive (return-to-stock
 * or write-off) beyond the port's deliberate three (hold, commit, release),
 * and inventing one is that module's decision, not this one's. {@code
 * built()} is false for it, so the application refuses it by name rather than
 * accepting a command it would carry out in the quote and forget in the
 * inventory ledger.
 */
public enum AmendmentCommandType {
    /** Wave 10 (gap map rows {@code 1.2c}/{@code 2.1d}): increases only, no modifiers. */
    ADD_LINES(true, true),

    /** Wave 10: increases only — a decrease has no ADR 0017 return/waste primitive yet. */
    CHANGE_LINE_QUANTITY(true, true),

    /** Still refused: removing a line needs the same missing ADR 0017 primitive as a decrease. */
    REMOVE_LINES(true, false),

    /** Wave 10: {@code CASH} at either end direct; anything else needs a void/refund this build cannot do. */
    CHANGE_PAYMENT_METHOD(true, true),

    /** Wave 10: reprices through the same path {@code CartService#price} uses; refused out of zone. */
    CHANGE_DELIVERY_ADDRESS(true, true),

    /** Wave 10: a field update. Never reprices — no price plane in this build varies by time. */
    CHANGE_FULFILLMENT_TIME(true, true),

    /**
     * Financial in ADR 0039's matrix only in the sense that it touches the
     * customer snapshot, which is ADR 0029 protected data with its own row
     * binding — never a money consequence. Built (wave 10).
     */
    CHANGE_CONTACT(true, true),

    SET_KITCHEN_NOTE(false, true),

    /**
     * Sets the callback flag, and clears it.
     *
     * <p>Clearing is what ADR 0039 calls resolution — it records
     * {@code callback_resolved_at} and {@code callback_resolved_by} — and it is
     * the same command with {@code requested = false} rather than a second one,
     * because a separate clear command would be an eleventh entry in a set the
     * ADR closed at ten.
     */
    SET_CALLBACK_REQUESTED(false, true),

    SET_CASH_TENDERED(false, true),

    /**
     * Operator to courier: never rendered to the customer.
     *
     * <p>Added by ADR 0113 (wave P10), amending ADR 0039's closed set from ten
     * commands to twelve — see that ADR's own dated status addition rather
     * than a rewritten Decision/Alternatives/Consequences. Unlike {@link
     * #SET_KITCHEN_NOTE} this wave carries no migration number, so the note
     * has no column on {@code ordering.orders} to land in; {@code
     * OrderAmendmentService#patchOf} folds it into no order field, and it is
     * read back only through the amendment history, {@code GET .../amendments}
     * (orders.md §3.6).
     */
    SET_COURIER_NOTE(false, true),

    /**
     * Operator to operator: the same shape as {@link #SET_COURIER_NOTE}, for
     * the one channel with no customer, kitchen or courier recipient at all —
     * the legacy dashboard's {@code internal_note}.
     */
    SET_INTERNAL_NOTE(false, true);

    private final boolean financial;
    private final boolean built;

    AmendmentCommandType(boolean financial, boolean built) {
        this.financial = financial;
        this.built = built;
    }

    /**
     * Whether the command has a consequence in money, stock, fiscal or POS.
     *
     * <p>A financial command stops at the ADR 0039 cut point, default
     * {@code READY}. Past it the answer to "add a dessert" is a second order,
     * honestly presented as one, because the fiscal and POS consequences stop
     * being reliably reversible.
     */
    public boolean financial() {
        return financial;
    }

    /** Whether the application can actually carry this command out today. */
    public boolean built() {
        return built;
    }
}
