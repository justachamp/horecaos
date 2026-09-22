package uz.horecaos.platform.payments.notifications;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.notifications.api.CustomerAlertPort;
import uz.horecaos.platform.notifications.api.NotificationConfigurationKeys;
import uz.horecaos.platform.payments.api.PaymentIntentCreated;
import uz.horecaos.platform.payments.application.PaymentCheckoutService;
import uz.horecaos.platform.payments.application.PaymentCheckoutService.CheckoutRefusedException;
import uz.horecaos.platform.payments.application.PaymentCheckoutService.PaymentSession;
import uz.horecaos.platform.payments.domain.PresentationRequest;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;

/**
 * Gap map row {@code 10.9d}, first half: {@code
 * notifications.payment_link_auto_send} finally gets a reader.
 *
 * <p>Before this class, the automation-card switch existed —
 * {@code NotificationConfigurationKeys.PAYMENT_LINK_AUTO_SEND}'s own Javadoc
 * said so — and toggling it changed nothing in production, because no
 * trigger anywhere read it. This is that trigger: the point an order is
 * known to need an online payment is {@link PaymentIntentCreated}, published
 * by {@code PaymentIntentService.createIntent} the moment the intent row
 * commits.
 *
 * <p>{@link TransactionPhase#AFTER_COMMIT}, not {@code BEFORE_COMMIT} the
 * way {@code OrderNotificationTrigger} runs. {@link PaymentCheckoutService#openOrRePresent}
 * calls {@code PaymentAttemptService.present}, and that method's own Javadoc
 * is explicit that it is deliberately not transactional because it may call
 * a provider over HTTP — exactly the outbound call inside a business
 * transaction {@code PaymentIntentService}'s own class doc forbids. Running
 * after commit is also why a refusal here (the seller unresolved, the
 * binding retired, a race with the customer's own checkout) can only ever
 * leave the order exactly as payable as it already was: the intent this
 * event describes is already durable regardless of what this listener does
 * with it.
 *
 * <p>Resolved at {@link uz.horecaos.platform.iam.api.ResourceScope.ScopeType#BRAND}:
 * the key's own {@code settableAt(PLATFORM, TENANT, BRAND)} names the finest
 * scope a tenant may author it at, and a brand is what {@link
 * PaymentIntentCreated} carries — there is no location on the event to
 * resolve any finer.
 *
 * <p>{@link PresentationRequest#link()} only. An auto-send is a courtesy the
 * customer did not ask for; pushing an invoice to a phone number
 * ({@code INVOICE_PUSH}) is a mutating provider call this class has no
 * customer-supplied number to attempt, and {@code
 * PresentationKind#mutatesTheProvider}'s own reasoning is why a link is the
 * only presentation safe to create unprompted.
 *
 * <p>Lives in {@code payments}, beside {@code PaymentOperationsAlertTrigger},
 * for the identical module-boundary reason that class's own Javadoc gives:
 * {@code payments} already depends on {@code integration}, and {@code
 * integration} already depends on {@code notifications}, so a listener
 * inside {@code notifications} reading a {@code payments.api} event would
 * close a cycle through {@code integration}.
 */
@Component
public class PaymentLinkAutoSendTrigger {

    private static final Logger log = LoggerFactory.getLogger(PaymentLinkAutoSendTrigger.class);

    /** The semantic template key a tenant authors this message's wording against. */
    public static final String PAYMENT_LINK_AUTO_SEND = "PAYMENT_LINK_AUTO_SEND";

    static final String SUBJECT_TYPE = "Order";

    private final ConfigurationResolver configuration;
    private final PaymentCheckoutService checkout;
    private final CustomerAlertPort customerAlerts;
    private final Duration expiry;

    public PaymentLinkAutoSendTrigger(
            ConfigurationResolver configuration,
            PaymentCheckoutService checkout,
            CustomerAlertPort customerAlerts,
            // An unpaid link is only worth sending for as long as the order
            // itself waits on payment before confirmation; matching
            // PaymentFailureCustomerTrigger's own reasoning for a short,
            // separately configurable default rather than ORDER_CONFIRMED's.
            @Value("${horecaos.notifications.payment-link-auto-send-expiry:PT2H}") Duration expiry) {
        this.configuration = configuration;
        this.checkout = checkout;
        this.customerAlerts = customerAlerts;
        this.expiry = expiry;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onIntentCreated(PaymentIntentCreated event) {
        Boolean autoSend = configuration.value(
                NotificationConfigurationKeys.PAYMENT_LINK_AUTO_SEND,
                ResourceScope.brand(event.tenantId(), event.brandId()));
        if (autoSend == null || !autoSend) {
            return;
        }

        PaymentSession session;
        try {
            session = checkout.openOrRePresent(
                    event.tenantId(), event.orderId(), event.customerAccountId(), PresentationRequest.link());
        } catch (CheckoutRefusedException refused) {
            // Expected in the ordinary course of things: a seller not yet
            // assigned, a binding retired underneath the order, an attempt
            // already captured or uncertain by the time this listener ran.
            // None of these is a defect in this trigger — the customer's own
            // checkout call is still the authoritative path and will report
            // whichever of these is still true when they try it themselves.
            log.info(
                    "Payment-link auto-send skipped for order {}: {} ({})",
                    event.orderId(),
                    refused.code(),
                    refused.getMessage());
            return;
        } catch (RuntimeException failure) {
            // A provider or presentation failure (PresentationFailure.Refused/
            // Uncertain) is the customer's own checkout path's problem to
            // surface, not this best-effort courtesy's to propagate — an
            // exception here must never look like the order itself failed.
            log.warn("Payment-link auto-send failed for order {}", event.orderId(), failure);
            return;
        }

        String checkoutUrl = session.checkoutUrl();
        if (checkoutUrl == null) {
            // Defensive: PresentationRequest.link() always asks for
            // PAYMENT_LINK, but a re-presented attempt already carrying a
            // push presentation answers with whatever kind it actually holds
            // (see PaymentCheckoutService.reuse). Nothing to send in that case.
            return;
        }

        customerAlerts.notifyCustomer(
                event.tenantId(),
                event.orderId(),
                PAYMENT_LINK_AUTO_SEND,
                SUBJECT_TYPE,
                event.eventId(),
                // One auto-sent link per order: a re-presentation of the same
                // attempt (the customer opening the storefront checkout after
                // this already ran) must not send a second message.
                "%s:%s:%s".formatted(PAYMENT_LINK_AUTO_SEND, SUBJECT_TYPE, event.orderId()),
                linkVariables(checkoutUrl),
                event.occurredAt(),
                expiry);
    }

    /**
     * The entire variable set this message ever renders with — the checkout
     * link, and nothing about the provider or the merchant account behind
     * it. Package-visible so a classification test can assert directly that
     * this is the whole set, the same discipline {@code
     * OrderNotificationTrigger#reasonVariables} documents for its own.
     */
    static Map<String, String> linkVariables(String checkoutUrl) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("checkoutUrl", checkoutUrl);
        return variables;
    }
}
