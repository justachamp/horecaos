package uz.horecaos.platform.payments.application;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.payments.domain.PaymentAttempt;
import uz.horecaos.platform.payments.domain.PaymentIntent;
import uz.horecaos.platform.payments.domain.PaymentIntentStatus;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.PaymentTender;
import uz.horecaos.platform.payments.domain.PresentationKind;
import uz.horecaos.platform.payments.domain.PresentationRequest;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.domain.ProviderInvoice;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentIntentStore;

/**
 * The half of ADR 0013 the storefront calls: open an attempt, hand back a
 * checkout surface.
 *
 * <p>Checkout creates the provider-neutral intent and refuses to confirm an order
 * without one, and both inbound endpoints are built — but until this existed
 * nothing opened an <em>attempt</em>, so a payment-first order waited in
 * {@code PAYMENT_AUTHORIZING} for a callback that could not arrive. Click had no
 * {@code merchant_trans_id} to name and Payme no transaction to find, and both
 * surfaces could only answer "unknown order". This is the endpoint's service, and
 * the seam is {@code CheckoutService.awaitPayment}, which puts the order into that
 * state and leaves the customer to be sent here.
 *
 * <p><strong>Deliberately not transactional, and that is the whole design.</strong>
 * {@link PaymentAttemptService#open} commits the attempt, with its
 * {@code merchant_trans_id} and its snapshotted business date, in a transaction
 * that is closed before {@link PaymentAttemptService#present} opens another one
 * and reaches a provider. Wrapping both in one transaction would put an outbound
 * call inside a transaction that can be rolled back, and Click's
 * {@code invoice/create} pushed inside a rolled-back transaction is a payment
 * request on somebody's phone that no row remembers — with no idempotency key
 * anywhere in Click's MERCHANT API to recover from it. The cost of the split is a
 * crash window that leaves an attempt in {@code INITIATED} with no surface, and
 * that costs nothing: the next call re-presents it.
 *
 * <p><strong>Re-presentation.</strong> A customer who abandons a checkout and
 * comes back is handed the same attempt, never a second one. This is enforced by
 * {@code ux_payment_attempt_open_per_intent} and only read here — two payable
 * links against one intent is the outbound shape of a double charge, and a
 * read-then-write in this class would be exactly the race the index exists for.
 */
@Service
public class PaymentCheckoutService {

    private static final Logger log = LoggerFactory.getLogger(PaymentCheckoutService.class);

    private final JdbcPaymentIntentStore intents;
    private final JdbcPaymentAttemptStore attempts;
    private final PaymentAttemptService attemptService;
    private final PaymentBindingResolver bindings;
    private final PaymentBusinessCalendar calendar;
    private final OrderDirectory orders;
    private final Clock clock;

    public PaymentCheckoutService(JdbcPaymentIntentStore intents, JdbcPaymentAttemptStore attempts,
            PaymentAttemptService attemptService, PaymentBindingResolver bindings,
            PaymentBusinessCalendar calendar, OrderDirectory orders, Clock clock) {
        this.intents = intents;
        this.attempts = attempts;
        this.attemptService = attemptService;
        this.bindings = bindings;
        this.calendar = calendar;
        this.orders = orders;
        this.clock = clock;
    }

    /**
     * Opens the attempt if there is none, and presents the checkout surface.
     *
     * @param customerAccountId the caller's own account, or null for a call that
     *                          is not on a customer's behalf. When both this and
     *                          the order's account are present they must match, and
     *                          a mismatch is reported as "no such order" rather
     *                          than as a refusal — an endpoint that distinguishes
     *                          the two lets anyone probe which order ids exist
     */
    public PaymentSession openOrRePresent(UUID tenantId, UUID orderId, UUID customerAccountId,
            PresentationRequest request) {

        OrderDirectory.OrderSummary order = orders.summary(tenantId, orderId)
                .orElseThrow(() -> new CheckoutRefusedException("ORDER_NOT_FOUND",
                        "No such order"));

        if (customerAccountId != null && order.hasAccount()
                && !customerAccountId.equals(order.customerAccountId())) {
            throw new CheckoutRefusedException("ORDER_NOT_FOUND", "No such order");
        }

        PaymentIntent intent = intents.findLiveForOrder(tenantId, orderId)
                .orElseThrow(() -> new CheckoutRefusedException("NO_PAYMENT_INTENT",
                        "This order has no payment to present"));

        if (intent.tender() == PaymentTender.CASH || intent.providerType() == null) {
            // Cash is collected at handover and has no surface. Refused rather than
            // answered with an empty session, because a storefront that got an empty
            // answer would have to guess whether the order is unpayable or the
            // platform is misconfigured.
            throw new CheckoutRefusedException("NOT_PAYABLE_ONLINE",
                    "This order is not paid through a provider");
        }
        if (intent.status() == PaymentIntentStatus.PAID) {
            throw new CheckoutRefusedException("ALREADY_PAID",
                    "This order is already paid");
        }
        if (!intent.status().open()) {
            throw new CheckoutRefusedException("PAYMENT_CLOSED",
                    "This order's payment is " + intent.status());
        }

        UUID seller = intent.legalEntity().orElseThrow(() -> new CheckoutRefusedException(
                // ADR 0038 resolves the seller from the location's fiscal
                // assignment on the order's business date. Without one there is no
                // merchant account to charge through and no name to put on a
                // receipt, and inventing either is a tax error rather than a bug.
                "SELLER_UNRESOLVED",
                "No legal entity is assigned to this order's location"));

        Instant now = clock.instant();
        LocalDate businessDate = calendar.businessDateFor(tenantId, intent.locationId(), now);

        ProviderBinding binding = bindings
                .resolve(tenantId, seller, intent.providerType(), businessDate)
                .orElseThrow(() -> new CheckoutRefusedException("BINDING_UNAVAILABLE",
                        // A serviceability precondition that has changed since the
                        // order was placed: the method is not offered on a channel
                        // whose legal entity has no binding, so reaching here means
                        // the binding was retired or its window closed underneath a
                        // live order.
                        "No merchant account is configured for this order's seller"));

        Optional<PaymentAttempt> existing = attempts.findOpenForIntent(tenantId, intent.id());
        PaymentAttempt attempt = existing
                .map(open -> reuse(open, binding, request))
                .orElseGet(() -> attemptService.open(intent, binding, businessDate));

        boolean rePresented = existing.isPresent();

        ProviderInvoice invoice = attemptService.present(attempt, binding, request)
                .orElseThrow(() -> new CheckoutRefusedException("PRESENTATION_UNAVAILABLE",
                        "No adapter is wired for " + binding.providerType()));

        log.info("Presented a {} checkout for order {} on {} ({})",
                invoice.presentationKind(), orderId, binding.providerType(),
                rePresented ? "re-presented" : "first presentation");

        return new PaymentSession(
                attempt.id(),
                attempt.merchantTransId(),
                binding.providerType(),
                invoice.presentationKind(),
                invoice.checkoutUrl(),
                invoice.qrPayload(),
                invoice.expiresAt(),
                attempt.amount().value(),
                attempt.amount().currency(),
                rePresented,
                attempts.presentationCount(tenantId, attempt.id()));
    }

    /**
     * Decides whether an attempt that already exists may be shown again.
     *
     * <p>Three states may. {@code INITIATED} is the crash window between the commit
     * and the provider call. {@code PRESENTED} is the ordinary abandoned tab.
     * {@code RESERVED} is a customer already on the provider's page, and sending
     * them back to the same link is safe on both providers — Click's Prepare is
     * keyed on a prepare id that is a deterministic function of the order, and Payme
     * refuses a second {@code CreateTransaction} for an order that already has an
     * active one.
     *
     * <p>Two may not, and both refusals are the point of the whole module.
     * {@code CAPTURED} would be a second charge invited by the platform, and
     * {@code UNCERTAIN} is a charge that may already have happened — showing a
     * surface for it is the retry that has no idempotency key to survive.
     *
     * <p>A push is never repeated. It is a mutating call with no idempotency key,
     * so a customer pressing "pay" again would push a second invoice; wanting
     * another one is an operator's decision made under the runbook, not a
     * storefront button.
     *
     * <p>And the merchant account must still be the one the attempt was opened
     * against. If a binding has been retired or superseded underneath a live
     * attempt, the surface built now would name a different Click service or Payme
     * cashbox from the row the callback will be matched against — so the callback
     * would arrive on the new binding's endpoint carrying an id that belongs to the
     * old one, and be answered "unknown order" after the customer had paid.
     */
    private PaymentAttempt reuse(PaymentAttempt attempt, ProviderBinding binding,
            PresentationRequest request) {
        if (!attempt.merchantBindingId().equals(binding.bindingId())) {
            throw new CheckoutRefusedException("BINDING_CHANGED",
                    "This order's payment attempt was opened against a merchant account that no "
                            + "longer resolves; it has to be settled before another surface is "
                            + "shown");
        }
        if (!attempt.status().rePresentable()) {
            throw new CheckoutRefusedException(
                    attempt.status() == uz.horecaos.platform.payments.domain.PaymentAttemptStatus.CAPTURED
                            ? "ALREADY_PAID" : "PAYMENT_IN_DOUBT",
                    "This order's payment attempt is " + attempt.status()
                            + " and cannot be presented again");
        }
        if (request.preferredKind().mutatesTheProvider()) {
            throw new CheckoutRefusedException("PRESENTATION_NOT_REPEATABLE",
                    "An invoice was already opened for this order; a second push is an "
                            + "operator action");
        }
        if (attempt.presentationKind() == PresentationKind.INVOICE_PUSH) {
            // The customer was pushed an invoice and is now asking for a link. Both
            // name the same merchant_trans_id and Click will settle whichever the
            // customer acts on, so this is a legitimate second surface for one
            // attempt rather than a second attempt.
            log.info("Attempt {} was pushed as an invoice and is now being linked", attempt.id());
        }
        return attempt;
    }

    /**
     * What the caller gets: a surface, and the identity the callback will carry.
     *
     * <p>{@code merchantTransId} is returned because it is the only identifier that
     * joins this to anything else — Click's callback names it and Payme carries it
     * as {@code account.order_id} — so a support conversation about a payment that
     * did not arrive starts here. It is opaque and non-sequential by construction
     * and reveals nothing about the order.
     *
     * @param rePresented        whether this was an abandoned checkout handed back
     *                           rather than a new attempt. The storefront does not
     *                           need it; a support view does
     * @param presentationCount  how many times a surface has now been handed over
     *                           for this attempt
     */
    public record PaymentSession(
            UUID attemptId,
            String merchantTransId,
            PaymentProviderType providerType,
            PresentationKind presentationKind,
            String checkoutUrl,
            String qrPayload,
            Instant expiresAt,
            long amountMinor,
            String currency,
            boolean rePresented,
            int presentationCount) {
    }

    /**
     * A settled answer that this order cannot be presented for payment.
     *
     * <p>Carries a stable code rather than only a sentence, because the storefront
     * branches on it: "already paid" ends the checkout, "no merchant account"
     * needs a different tender offered, and "payment in doubt" is a wait rather
     * than a retry.
     */
    public static class CheckoutRefusedException extends RuntimeException {

        private final String code;

        public CheckoutRefusedException(String code, String message) {
            super(message);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
