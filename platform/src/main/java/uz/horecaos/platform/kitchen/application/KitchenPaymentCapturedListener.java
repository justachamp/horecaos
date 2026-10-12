package uz.horecaos.platform.kitchen.application;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.ordering.api.PaymentCaptured;

/**
 * Releases a payment-held ticket when the money arrives (ADR 0168 Decision 4a).
 *
 * <p>The event is the proof: no read of the payment projection is involved, so it cannot race the
 * projection listener. The hold is cleared in the capture's own transaction and the ticket returns to its own
 * schedule; a ticket not held, or not in the buffer, is untouched.
 */
@Component
public class KitchenPaymentCapturedListener {

    private final KitchenPaymentHoldService holds;

    public KitchenPaymentCapturedListener(KitchenPaymentHoldService holds) {
        this.holds = holds;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPaymentCaptured(PaymentCaptured event) {
        holds.onPaymentCaptured(event.tenantId().value(), event.orderId());
    }
}
