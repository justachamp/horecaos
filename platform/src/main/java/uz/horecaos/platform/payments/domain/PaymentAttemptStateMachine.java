package uz.horecaos.platform.payments.domain;

import static uz.horecaos.platform.payments.domain.PaymentAttemptStatus.CANCELLED;
import static uz.horecaos.platform.payments.domain.PaymentAttemptStatus.CAPTURED;
import static uz.horecaos.platform.payments.domain.PaymentAttemptStatus.EXPIRED;
import static uz.horecaos.platform.payments.domain.PaymentAttemptStatus.FAILED;
import static uz.horecaos.platform.payments.domain.PaymentAttemptStatus.INITIATED;
import static uz.horecaos.platform.payments.domain.PaymentAttemptStatus.PRESENTED;
import static uz.horecaos.platform.payments.domain.PaymentAttemptStatus.RESERVED;
import static uz.horecaos.platform.payments.domain.PaymentAttemptStatus.REVERSED;
import static uz.horecaos.platform.payments.domain.PaymentAttemptStatus.UNCERTAIN;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The transitions an attempt may take (ADR 0013).
 *
 * <p>Held in code beside the CHECK constraint that lists the states, in the same
 * arrangement ordering uses: the database owns which values exist, this owns
 * which moves between them are legal. Neither provider's transition rules are
 * encoded here — a Payme {@code PerformTransaction} and a Click Complete both
 * arrive as "capture", and the adapter is what knows that.
 *
 * <p>Every state may move to {@link PaymentAttemptStatus#UNCERTAIN}, because a
 * response can be lost at any moment; and {@code UNCERTAIN} may move to anything
 * a resolver discovers, including back to the state it left. What it may never do
 * is be retried out of.
 */
public final class PaymentAttemptStateMachine {

    private static final Map<PaymentAttemptStatus, Set<PaymentAttemptStatus>> ALLOWED =
            new EnumMap<>(PaymentAttemptStatus.class);

    static {
        ALLOWED.put(INITIATED, EnumSet.of(PRESENTED, RESERVED, FAILED, CANCELLED, UNCERTAIN));
        ALLOWED.put(PRESENTED, EnumSet.of(RESERVED, FAILED, CANCELLED, EXPIRED, UNCERTAIN));
        // A reservation may be captured, released, or aged out. It may not jump
        // straight to REVERSED: there is nothing to reverse until money has moved,
        // and a state machine that allowed it would let a cancellation be recorded
        // as a refund in the settlement reconciliation.
        ALLOWED.put(RESERVED, EnumSet.of(CAPTURED, CANCELLED, EXPIRED, FAILED, UNCERTAIN));
        ALLOWED.put(CAPTURED, EnumSet.of(REVERSED, UNCERTAIN));
        ALLOWED.put(CANCELLED, EnumSet.noneOf(PaymentAttemptStatus.class));
        ALLOWED.put(EXPIRED, EnumSet.noneOf(PaymentAttemptStatus.class));
        ALLOWED.put(REVERSED, EnumSet.noneOf(PaymentAttemptStatus.class));
        ALLOWED.put(FAILED, EnumSet.noneOf(PaymentAttemptStatus.class));
        ALLOWED.put(UNCERTAIN, EnumSet.of(PRESENTED, RESERVED, CAPTURED, CANCELLED, EXPIRED, REVERSED, FAILED));
    }

    private PaymentAttemptStateMachine() {}

    public static boolean permits(PaymentAttemptStatus from, PaymentAttemptStatus to) {
        return ALLOWED.getOrDefault(from, EnumSet.noneOf(PaymentAttemptStatus.class))
                .contains(to);
    }

    public static Set<PaymentAttemptStatus> from(PaymentAttemptStatus status) {
        return EnumSet.copyOf(ALLOWED.getOrDefault(status, EnumSet.noneOf(PaymentAttemptStatus.class)));
    }

    /**
     * @throws IllegalStateException naming both states, because the message is
     *                               read by whoever is holding a customer on the
     *                               phone about a payment
     */
    public static void require(PaymentAttemptStatus from, PaymentAttemptStatus to) {
        if (!permits(from, to)) {
            throw new IllegalStateException("A payment attempt cannot move from " + from + " to " + to);
        }
    }
}
