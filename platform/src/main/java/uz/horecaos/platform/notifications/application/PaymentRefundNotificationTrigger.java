package uz.horecaos.platform.notifications.application;

import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.notifications.api.CustomerAlertPort;
import uz.horecaos.platform.ordering.api.PaymentRefunded;

/**
 * Telling the customer money has come back on their order (ADR 0020, ADR
 * 0013, ADR 0048).
 *
 * <p>{@link PaymentRefunded} fires for two different facts, both genuinely
 * "a refund was issued" from a customer's point of view: a provider
 * reversing a captured attempt on its own account ({@code
 * PaymentAttemptService.applyToIntent}'s {@code REVERSED} case — a Payme
 * cabinet refund or a Click reversal), and an operator recording a refund or
 * a delivery-fee reimbursement through {@code OrderRemedyService} — "the
 * platform's only sanctioned way to initiate a refund at all", per {@link
 * PaymentRefunded}'s own Javadoc. Both leave a customer wondering whether
 * their money is actually coming back, which today nothing tells them.
 *
 * <p><strong>One message per order, not one per remedy.</strong> {@link
 * PaymentRefunded} carries {@code orderId} and nothing that names which
 * remedy or which reversal caused it — no remedy id, no amount — so there is
 * no distinguishing id to key a second message on if an order is refunded
 * more than once (a delivery-fee reimbursement today, a full order refund
 * next week both publish the identical shape of fact). Keying on the order
 * — the same "one message per subject" discipline {@code
 * OrderNotificationTrigger} applies to confirmation and rejection — means a
 * second, later refund on the same order is not narrated by this trigger.
 * Support already sees every {@code order_remedies} row; this message's job
 * is the first reassurance, not a running ledger. Widening {@link
 * PaymentRefunded} to carry a remedy id so each one gets its own message is
 * a real option, and a payments-side change this build does not make.
 *
 * <p>{@code TRANSACTIONAL_REQUIRED} (via {@link CustomerAlertPort}, which
 * creates every intent at that class) and exempt from ADR 0020 quiet hours
 * for the same reason a payment failure is: a customer waiting to see their
 * money back is not helped by a multi-hour hold.
 *
 * <p>Lives beside {@link OrderNotificationTrigger} rather than in {@code
 * payments}, unlike {@link
 * uz.horecaos.platform.payments.notifications.PaymentFailureCustomerTrigger}.
 * {@link PaymentRefunded} is declared in {@code ordering.api} — see that
 * type's own Javadoc for why — and {@code notifications} already depends on
 * {@code ordering.api} for {@code OrderConfirmed}/{@code OrderRejected}, so a
 * listener here importing it is the same clean one-way edge {@link
 * FiscalOperationsAlertTrigger} and {@link InventoryOperationsAlertTrigger}
 * already rely on; no cycle through {@code integration} is in the way the
 * way it is for a type declared in {@code payments.api}.
 */
@Component
public class PaymentRefundNotificationTrigger {

    /** The semantic template key a tenant authors this message's wording against. */
    public static final String PAYMENT_REFUNDED = "PAYMENT_REFUNDED";

    static final String SUBJECT_TYPE = "Order";

    private final CustomerAlertPort customerAlerts;
    private final Duration expiry;

    public PaymentRefundNotificationTrigger(
            CustomerAlertPort customerAlerts,
            // Generous rather than short: unlike a payment failure, there is
            // nothing time-sensitive for the customer to act on, so this
            // matches FiscalCustomerReceiptTrigger's own receipt-length
            // default rather than PaymentOperationsAlertTrigger's worklist one.
            @Value("${horecaos.notifications.payment-refund-expiry:P3D}") Duration expiry) {
        this.customerAlerts = customerAlerts;
        this.expiry = expiry;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onPaymentRefunded(PaymentRefunded event) {
        customerAlerts.notifyCustomer(
                event.tenantId().value(),
                event.orderId(),
                PAYMENT_REFUNDED,
                SUBJECT_TYPE,
                event.eventId(),
                // Keyed on the order alone — see this class's own Javadoc for
                // why no finer-grained id is available on the event, and why
                // that makes "once per order" the honest answer rather than a
                // shortcut.
                "%s:%s:%s".formatted(PAYMENT_REFUNDED, SUBJECT_TYPE, event.orderId()),
                Map.of(),
                event.occurredAt(),
                expiry);
    }
}
