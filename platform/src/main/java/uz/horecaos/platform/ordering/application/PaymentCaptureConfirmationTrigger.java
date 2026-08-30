package uz.horecaos.platform.ordering.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.ordering.api.PaymentCaptured;

/**
 * Confirms — or sends to restaurant approval — an order whose money has landed
 * while it waited (ADR 0013, ADR 0019).
 *
 * <p>The reverse of {@code OrderConfirmedSettlementTrigger}: that class is a
 * listener in {@code payments} for an ordering fact, {@code OrderConfirmed};
 * this is a listener in {@code ordering} for a payments fact,
 * {@link PaymentCaptured}. The direction each dependency runs is what keeps the
 * two modules from being mutually dependent — see {@link PaymentCaptured}'s own
 * Javadoc for why the event type lives here rather than in payments.
 *
 * <p>{@link TransactionPhase#BEFORE_COMMIT}, the same phase
 * {@code OrderConfirmedSettlementTrigger} uses and for the same reason: the work
 * {@link OrderStateService#paymentCaptured} does is local rows only — a status
 * change, an approval timer, an ADR 0027 audit fact — so there is no reason to
 * let it commit separately from the capture that caused it. Committing
 * separately is exactly the gap this class closes: before it, nothing moved a
 * {@code BEFORE_CONFIRMATION} order out of {@code PAYMENT_AUTHORIZING} once it
 * was paid.
 *
 * <p>All the actual decision-making — which acceptance policy applies, whether
 * this is a replay, what an order that already left {@code PAYMENT_AUTHORIZING}
 * some other way should do — lives in {@link OrderStateService#paymentCaptured}.
 * This class only delivers the fact to it.
 */
@Component
public class PaymentCaptureConfirmationTrigger {

    private final OrderStateService orders;

    public PaymentCaptureConfirmationTrigger(OrderStateService orders) {
        this.orders = orders;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPaymentCaptured(PaymentCaptured event) {
        orders.paymentCaptured(event.tenantId().value(), event.orderId(), event.occurredAt());
    }
}
