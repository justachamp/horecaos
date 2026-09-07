package uz.horecaos.platform.payments.notifications;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.notifications.api.CustomerAlertPort;
import uz.horecaos.platform.payments.api.PaymentAttemptFailed;

/**
 * The customer-facing counterpart of {@link PaymentOperationsAlertTrigger}'s
 * {@code onAttemptFailed}: a payment attempt reaching {@code FAILED} tells
 * the customer whose order it was, not only the operations chat.
 *
 * <p>Built because {@code FAILED} is reached from a provider webhook
 * ({@code ClickCallbackProcessor}, {@code PaymeMerchantApi}) or from the
 * uncertainty resolver, never synchronously from the checkout call the
 * customer is watching — see {@code PaymentAttemptService.recordProviderEvent}'s
 * own call sites. A customer who was redirected to Click or Payme to finish
 * paying and then closed the tab has no other way to learn the charge did
 * not go through, and the order itself is not, on its own account, given up
 * on: {@code ordering.api.PaymentFailed}'s own Javadoc is explicit that "a
 * declined attempt is not, on its own, a reason to give up on the order",
 * so nothing else tells this customer their order is now waiting on a retry
 * that may never come.
 *
 * <p>{@code TRANSACTIONAL_REQUIRED}, the same class {@link
 * uz.horecaos.platform.notifications.application.OrderNotificationTrigger}
 * gives confirmation and rejection, and for the same reason {@code
 * NotificationClass}'s own Javadoc names this exact fact as an example of:
 * "a payment failure: the message is part of the transaction rather than an
 * extra the customer opted into." It is also exempt from ADR 0020 quiet
 * hours for the same reason — a customer whose order is stuck on a declined
 * payment is not helped by hearing about it after a multi-hour window
 * closes.
 *
 * <p>Lives in {@code payments}, beside {@link PaymentOperationsAlertTrigger}
 * and for the same reason that class's own Javadoc gives: {@code payments}
 * already depends on {@code integration}, and {@code integration} already
 * depends on {@code notifications}, so a listener inside {@code
 * notifications} importing {@link PaymentAttemptFailed} from {@code
 * payments.api} would close a cycle through {@code integration}. Calling
 * {@link CustomerAlertPort} from here — the customer-audience twin of {@link
 * uz.horecaos.platform.notifications.api.OperationsAlertPort}, which {@link
 * PaymentOperationsAlertTrigger} already calls from this same package — is
 * the one-way edge that already exists, used for a new reason.
 */
@Component
public class PaymentFailureCustomerTrigger {

    /** The semantic template key a tenant authors this message's wording against. */
    public static final String PAYMENT_FAILED = "PAYMENT_FAILED";

    /**
     * {@code "Order"}, not {@code "PaymentAttempt"} — matching {@link
     * PaymentFailureCustomerTrigger#PAYMENT_FAILED}'s recipient resolution.
     * {@code CustomerAlertPort#notifyCustomer}'s own contract requires the
     * order's own id as {@code subjectId}: eligibility resolves the recipient
     * and every rendered amount/currency variable from {@code
     * OrderDirectory.summary(tenantId, subjectId)} unconditionally, so
     * naming the attempt here would fail delivery with "names an order this
     * tenant does not own" the moment the worker picks the row up. The
     * attempt id lives in the idempotency key instead, the same split {@code
     * FiscalCustomerReceiptTrigger} uses for a document id.
     */
    static final String SUBJECT_TYPE = "Order";

    private final CustomerAlertPort customerAlerts;
    private final Duration expiry;

    public PaymentFailureCustomerTrigger(
            CustomerAlertPort customerAlerts,
            // Short, matching PaymentOperationsAlertTrigger's own reasoning: a
            // "your payment failed" message an hour after the order was
            // abandoned or retried successfully is noise, not help.
            @Value("${horecaos.notifications.payment-failure-customer-expiry:PT2H}") Duration expiry) {
        this.customerAlerts = customerAlerts;
        this.expiry = expiry;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onAttemptFailed(PaymentAttemptFailed event) {
        customerAlerts.notifyCustomer(
                event.tenantId(),
                event.orderId(),
                PAYMENT_FAILED,
                SUBJECT_TYPE,
                event.eventId(),
                // Keyed on the attempt, not the event id: a customer whose
                // first card is declined and who then tries a second one gets
                // one message per genuinely declined attempt, and a redelivery
                // of the same PaymentAttemptFailed (a fresh event id for the
                // same attempt) still collapses to that one message — the same
                // reasoning PaymentOperationsAlertTrigger#onAttemptFailed gives
                // its own idempotency key.
                "%s:%s:%s".formatted(PAYMENT_FAILED, SUBJECT_TYPE, event.attemptId()),
                reasonVariables(event.reasonCode()),
                event.occurredAt(),
                expiry);
    }

    /**
     * The entire variable set this message ever renders with — a reason
     * code, nothing about the card or the provider. Package-visible so a
     * classification test can assert directly that this is the whole set,
     * the same discipline {@code OrderNotificationTrigger#reasonVariables}
     * documents for its own.
     */
    static Map<String, String> reasonVariables(@Nullable String reasonCode) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("reasonCode", reasonCode == null ? "UNSPECIFIED" : reasonCode);
        return variables;
    }
}
