package uz.qoida.platform.payments.infrastructure.payme;

import java.util.Optional;

import uz.qoida.platform.payments.domain.PaymentAttemptStatus;

/**
 * Payme's transaction states, and the projection of Qoida's onto them (ADR 0013).
 *
 * <p><strong>The sign carries the meaning.</strong> A negative state is a
 * cancellation and the magnitude says how far the transaction had got when it was
 * cancelled: {@code -1} before money moved, {@code -2} after. Any code that tests
 * {@code state == -1} for "is it cancelled" is wrong; the test is {@code state < 0},
 * which is what {@link #cancelled()} does.
 *
 * <p>Only four transitions exist — {@code (none) → 1}, {@code 1 → 2},
 * {@code 1 → -1}, {@code 2 → -2} — and {@code 2} is settled but not terminal,
 * because a refund from the merchant cabinet still takes it to {@code -2}.
 *
 * <p>This is a projection and never a source of truth. Qoida's state is
 * {@link PaymentAttemptStatus}; this is the word Payme uses for it, and the
 * mapping is deliberately one-directional. Payme has no vocabulary for an attempt
 * that has been presented but not created, and Qoida has no separate state for
 * "cancelled by timeout" versus "cancelled by request" — the reason code carries
 * that, which is why {@link PaymentAttemptStatus#EXPIRED} and
 * {@link PaymentAttemptStatus#CANCELLED} both project onto {@code -1}.
 */
public enum PaymeState {

    /** Created, awaiting confirmation. */
    CREATED(1),

    /** Performed: the money is the merchant's. Reversible, so not terminal. */
    PERFORMED(2),

    /** Cancelled before the money moved. */
    CANCELLED(-1),

    /** Cancelled after the money moved: it went back to the payer's card. */
    CANCELLED_AFTER_PERFORM(-2);

    private final int code;

    PaymeState(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public boolean cancelled() {
        return code < 0;
    }

    /**
     * What Payme would call this attempt, if it would call it anything.
     *
     * <p>Empty is a real answer and the caller must handle it rather than default.
     * An attempt carrying a Payme transaction id in {@code INITIATED},
     * {@code PRESENTED}, {@code FAILED} or {@code UNCERTAIN} is an internal
     * inconsistency — those states are unreachable once a Payme transaction exists
     * — and the honest response to Payme is {@code -32400}, not a state invented to
     * fill the gap.
     */
    public static Optional<PaymeState> of(PaymentAttemptStatus status) {
        return switch (status) {
            case RESERVED -> Optional.of(CREATED);
            case CAPTURED -> Optional.of(PERFORMED);
            // Both project onto -1: Payme distinguishes a timeout cancellation from
            // any other by the reason code, not by the state.
            case CANCELLED, EXPIRED -> Optional.of(CANCELLED);
            case REVERSED -> Optional.of(CANCELLED_AFTER_PERFORM);
            case INITIATED, PRESENTED, FAILED, UNCERTAIN -> Optional.empty();
        };
    }
}
