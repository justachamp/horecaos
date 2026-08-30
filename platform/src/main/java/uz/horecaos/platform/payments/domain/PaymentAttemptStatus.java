package uz.horecaos.platform.payments.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * HorecaOS's payment states (ADR 0013). They are HorecaOS's, and neither provider's.
 *
 * <pre>
 * INITIATED -> PRESENTED -> RESERVED -> CAPTURED -> REVERSED
 *                  |            |
 *                  |            +-> CANCELLED  (released, no money moved)
 *                  |            +-> EXPIRED    (reservation aged out)
 *                  +-> FAILED   (declined before any reservation)
 *
 * any state -> UNCERTAIN -> exactly one of the above
 * </pre>
 *
 * <p>Two of these deserve their reason written down.
 *
 * <p>{@link #CAPTURED} is settled but <strong>not terminal</strong>. Payme's
 * state {@code 2} is explicitly reversible — a cabinet refund takes it to
 * {@code -2} — and Click's reversal reaches a completed payment for the rest of
 * the reporting month. Treating capture as the end of the story is how a refund
 * arrives at a state machine with nowhere to put it.
 *
 * <p>{@link #EXPIRED} exists even though Click has no such concept. Payme imposes
 * a hard twelve-hour expiry with its own cancellation reason; Click imposes none
 * at all, so on Click this state is produced by HorecaOS's own reservation timeout
 * and the provider is never told. A state that only one provider can express is
 * still HorecaOS's state.
 */
public enum PaymentAttemptStatus {
    INITIATED,

    PRESENTED,

    RESERVED,

    CAPTURED,

    CANCELLED,

    EXPIRED,

    REVERSED,

    FAILED,

    /**
     * The provider may or may not have acted, and nothing may be attempted until
     * that is discovered.
     *
     * <p>Not a failure and not terminal. It carries an obligation — a named
     * resolver, a first-observed time, and a deadline after which it becomes an
     * operations exception — and it blocks any further charge against the same
     * intent, enforced by a partial unique index rather than by convention.
     *
     * <p>The alternative, collapsing it into "retryable", is the single most
     * expensive mistake available on this path: Click's MERCHANT API carries no
     * idempotency key on any call, so a retried {@code card_token/payment} after a
     * lost response is a second charge on a customer's card.
     */
    UNCERTAIN;

    private static final Set<PaymentAttemptStatus> TERMINAL = EnumSet.of(CANCELLED, EXPIRED, REVERSED, FAILED);

    private static final Set<PaymentAttemptStatus> LIVE = EnumSet.of(RESERVED, CAPTURED, UNCERTAIN);

    private static final Set<PaymentAttemptStatus> RE_PRESENTABLE = EnumSet.of(INITIATED, PRESENTED, RESERVED);

    /** Whether nothing further can happen to an attempt in this state. */
    public boolean terminal() {
        return TERMINAL.contains(this);
    }

    /**
     * Whether an attempt in this state holds money, or holds a question about
     * money.
     *
     * <p>Not the same question as whether a second attempt may be opened, which
     * {@code ux_payment_attempt_open_per_intent} answers for every non-terminal
     * state. This is the narrower fact the inbound handlers and the operations
     * console read: {@code RESERVED} is a reservation the provider is holding,
     * {@code CAPTURED} is money, and {@code UNCERTAIN} is a charge that may or may
     * not have happened — and none of the three may be walked away from.
     */
    public boolean blocksFurtherAttempts() {
        return LIVE.contains(this);
    }

    /**
     * Whether a customer who abandoned this checkout and came back may be handed
     * the same surface again.
     *
     * <p>Three states qualify and each for its own reason. {@code INITIATED} is an
     * attempt whose presentation never completed — a crash between the commit and
     * the provider call leaves exactly this, and re-presenting it is the recovery.
     * {@code PRESENTED} is the ordinary abandoned tab. {@code RESERVED} is a
     * customer already on the provider's page: sending them back to the same link
     * is safe on both providers, because Click's Prepare is keyed on the same
     * deterministic prepare id and Payme refuses a second {@code CreateTransaction}
     * for an order that already has an active one.
     *
     * <p>{@code CAPTURED} and {@code UNCERTAIN} are deliberately excluded. Showing
     * a payment surface for an order already paid invites a second charge, and
     * showing one while an outcome is unknown is the retry this whole module exists
     * to prevent.
     */
    public boolean rePresentable() {
        return RE_PRESENTABLE.contains(this);
    }

    /** Whether the money has moved and not been given back. */
    public boolean settledInFavourOfTheMerchant() {
        return this == CAPTURED;
    }
}
