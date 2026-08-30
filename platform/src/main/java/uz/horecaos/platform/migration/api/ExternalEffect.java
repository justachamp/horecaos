package uz.horecaos.platform.migration.api;

/**
 * The external effects a historical import must not produce (ADR 0024).
 *
 * <p>ADR 0024 lists them in prose: "Historical import never replays customer
 * messages, captures payments, books couriers, exports POS orders, consumes
 * benefits, or changes inventory." This enum is that sentence made countable, so
 * the wiring can be checked rather than believed. {@code
 * MigrationImportSuppressionTests} asserts that every constant here is consulted
 * from the adapter that produces it — the test exists because the flag it guards
 * spent a release with exactly one occurrence in the main sources, its own
 * declaration, while a Javadoc promised a suppression that did not happen.
 *
 * <p>Each constant also fixes <em>how</em> the effect is suppressed, and there
 * are only two answers.
 *
 * <p>{@link Suppression#SKIPPED} means the effect is simply not produced and the
 * import carries on. It is correct where not producing the effect leaves the
 * caller with a truthful answer: no outbox row means no publication, no
 * notification intent means no message, no usage movement means nothing was
 * counted against a plan. The caller of a skipped effect either has no return
 * value to be misled by, or its return value already has a shape that says
 * "nothing happened".
 *
 * <p>{@link Suppression#REFUSED} means the call throws. It is correct where
 * skipping would have to fabricate a result — a reservation id for stock that
 * was never held, a provider reference for a courier nobody booked — because a
 * fabricated result is worse than a failed import: the run finishes green and
 * the lie is only found later, in a target the reconciliation suite has already
 * approved. A refusal is also the honest signal that an import port took the
 * live request path when ADR 0024 requires historical facts to be imported as
 * immutable snapshots.
 */
public enum ExternalEffect {

    /**
     * ADR 0004 outbox append, and therefore every downstream consumer of the
     * topic. Skipped: an order fact from 2021 has no audience.
     */
    OUTBOX_PUBLICATION(Suppression.SKIPPED),

    /**
     * ADR 0020 notification intent. Skipped at creation rather than at delivery,
     * for the reason given on {@link ImportContext}: the delivery worker runs on
     * a scheduler thread where the binding does not exist, so an intent written
     * during an import would be indistinguishable from a real one by the time
     * anything looked at it.
     */
    CUSTOMER_NOTIFICATION(Suppression.SKIPPED),

    /**
     * The outbound half of {@link #CUSTOMER_NOTIFICATION}, and the same split as
     * {@link #POS_ORDER_EXPORT} against {@link #POS_PROVIDER_CALL}: not writing
     * an intent is a coherent state, while putting a message on the wire is not
     * withdrawable once it has left. An SMS to a customer about an order they
     * placed in 2021 cannot be recalled, so the gateway fails the run rather
     * than skipping quietly.
     *
     * <p>Separate from {@code CUSTOMER_NOTIFICATION} because one constant cannot
     * be both skipped and refused. Passing the skipped one to {@code refuse}
     * throws unconditionally — on every send, import or not — which is exactly
     * what happened when these two layers shared a constant, and the validation
     * on {@link ImportSuppression} is what surfaced it.
     */
    NOTIFICATION_PROVIDER_CALL(Suppression.REFUSED),

    /**
     * ADR 0013 payment collection — opening an intent or presenting an attempt to
     * a provider. Refused: a historical payment is imported as the settled fact
     * it already is, and anything that reaches a provider for it is a live
     * collection against a customer who paid years ago.
     */
    PAYMENT_COLLECTION(Suppression.REFUSED),

    /** ADR 0014 courier dispatch. Refused: there is no way to fake a booking. */
    COURIER_BOOKING(Suppression.REFUSED),

    /**
     * ADR 0011 POS order export. The export record is skipped, because an
     * unexported historical order is a coherent state; putting one on the wire is
     * refused, because it prints a kitchen ticket.
     */
    POS_ORDER_EXPORT(Suppression.SKIPPED),

    /** The outbound half of {@link #POS_ORDER_EXPORT}. */
    POS_PROVIDER_CALL(Suppression.REFUSED),

    /**
     * ADR 0021 benefit consumption. Skipped: metering five years of orders
     * against this month's plan would exhaust a tenant's entitlement on history.
     */
    BENEFIT_CONSUMPTION(Suppression.SKIPPED),

    /**
     * ADR 0017 order-driven stock movement — a quote reservation and its commit
     * or release. Refused rather than skipped, and the distinction matters: an
     * import that reaches this has run the live checkout path instead of
     * importing a snapshot, and handing it a reservation id for stock nobody held
     * would let it finish and commit that fiction.
     *
     * <p>Deliberately <strong>not</strong> every inventory write. ADR 0024 makes
     * the legacy stock baseline an explicit opening movement, so setting
     * availability during an import is the import doing its job; it is the
     * order-driven movements that must not fire.
     */
    INVENTORY_MOVEMENT(Suppression.REFUSED);

    private final Suppression suppression;

    ExternalEffect(Suppression suppression) {
        this.suppression = suppression;
    }

    public Suppression suppression() {
        return suppression;
    }

    /** How an adapter suppresses its effect while an import is running. */
    public enum Suppression {

        /** Not produced; the caller continues. */
        SKIPPED,

        /** Refused, because skipping would mean inventing the result. */
        REFUSED
    }
}
