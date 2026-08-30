package uz.horecaos.platform.payments.infrastructure.click;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.payments.application.PaymentProviderPort;
import uz.horecaos.platform.payments.domain.PaymentAttempt;
import uz.horecaos.platform.payments.domain.PaymentAttemptStatus;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.PresentationFailure;
import uz.horecaos.platform.payments.domain.PresentationKind;
import uz.horecaos.platform.payments.domain.PresentationRequest;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.domain.ProviderEvidence;
import uz.horecaos.platform.payments.domain.ProviderInvoice;
import uz.horecaos.platform.payments.domain.ProviderOutcome;
import uz.horecaos.platform.payments.infrastructure.click.ClickMerchantApi.ClickResponse;

/**
 * Click as {@link PaymentProviderPort} (ADR 0013).
 *
 * <p>Translates between HorecaOS's states and Click's vocabulary, and nothing else:
 * the wire lives in {@link ClickMerchantApi}, the route policy in
 * {@code integration.camel.payment}, and the inbound half — the only half that
 * credits an order — in {@code payments.web.click}.
 *
 * <p>Three of this class's decisions are worth reading before changing it.
 *
 * <p><em>Presentation proves nothing.</em> {@link #createInvoice} produces either
 * an unsigned redirect, whose amount is attacker-controlled and whose
 * {@code return_url} is a browser event, or an invoice pushed to a phone that the
 * customer has still to accept. Neither is money; the SHOP API callback is.
 *
 * <p><em>A not-found is not a no.</em> {@link #queryOutcome} answers
 * {@link ProviderOutcome.Classification#UNCERTAIN} when {@code status_by_mti}
 * reports nothing, because the business date that query is keyed on is
 * undocumented. On this provider, absence of evidence is not evidence of absence,
 * and reporting "no payment" here would unblock the retry the whole mechanism
 * exists to prevent.
 *
 * <p><em>Reversal is the other half of the Complete rule.</em> After a successful
 * charge, Click's Complete may answer only {@code -4} or {@code -9}, so an order
 * that cannot be fulfilled is answered {@code error: 0} and given back through
 * here.
 */
@Component
public class ClickPaymentAdapter implements PaymentProviderPort {

    private static final Logger log = LoggerFactory.getLogger(ClickPaymentAdapter.class);

    private final ClickMerchantApi click;
    private final Clock clock;

    public ClickPaymentAdapter(ClickMerchantApi click, Clock clock) {
        this.click = click;
        this.clock = clock;
    }

    @Override
    public PaymentProviderType providerType() {
        return PaymentProviderType.CLICK;
    }

    /**
     * Produces one of Click's two checkout surfaces, and credits nothing either way.
     *
     * <p><strong>The link.</strong> A string built in this process from the
     * binding, the attempt's {@code merchant_trans_id} and the amount — no provider
     * call, nothing to lose the answer to, and nothing that can be uncertain. It is
     * unsigned, so its amount is attacker-controlled: the figure that is enforced
     * is the one Prepare checks against the attempt, and a customer returning to
     * {@code return_url} has told HorecaOS nothing at all.
     *
     * <p><strong>The push.</strong> {@code invoice/create} sends a payment request
     * to a phone, and it is the one presentation that is a mutating MERCHANT API
     * call. Click has no idempotency key anywhere, so a lost answer here is
     * {@link PresentationFailure.Uncertain} and never a second push — which is the
     * whole reason the attempt, its {@code merchant_trans_id} and its business date
     * are committed before this method is reached. A created invoice is still not a
     * payment: the customer has to accept it in the Click application, and the money
     * is learned about through the SHOP API callback.
     *
     * <p>No expiry is set on either. Click imposes none on a payment link or an
     * invoice — the twelve-hour clock is Payme's — so the reservation timeout that
     * eventually releases this order is HorecaOS's own, and Click is never told about
     * it.
     */
    @Override
    public ProviderInvoice createInvoice(PaymentAttempt attempt, ProviderBinding binding, PresentationRequest request) {

        if (request.preferredKind() == PresentationKind.INVOICE_PUSH) {
            return push(attempt, binding, request);
        }
        return link(attempt, binding, request);
    }

    private ProviderInvoice link(PaymentAttempt attempt, ProviderBinding binding, PresentationRequest request) {

        String merchantId = binding.merchantId()
                .orElseThrow(() -> new PresentationFailure.Refused(
                        "CLICK_MERCHANT_ID_MISSING",
                        // Refused rather than built without it. Click documents merchant_id
                        // as mandatory on this link, and a link that omits it is either
                        // rejected at my.click.uz or resolved against whichever merchant
                        // Click infers — which would be another restaurant's account.
                        "This Click binding carries no merchant_id, so no payment link can be built"));

        String url = ClickCheckoutLink.build(
                merchantId,
                binding.merchantAccountReference(),
                binding.merchantUser().orElse(null),
                attempt.merchantTransId(),
                attempt.amount(),
                request.returnTo().orElse(null),
                null);

        return new ProviderInvoice(PresentationKind.PAYMENT_LINK, url, null, null, null, null, null);
    }

    private ProviderInvoice push(PaymentAttempt attempt, ProviderBinding binding, PresentationRequest request) {

        String recipient = request.recipient()
                .orElseThrow(() -> new PresentationFailure.Refused(
                        "CLICK_PUSH_RECIPIENT_MISSING", "An invoice push needs the number to push it to"));

        ClickMerchantApi.ClickResponse response =
                click.createInvoice(binding, attempt.merchantTransId(), attempt.amount(), recipient);

        if (response.uncertain()) {
            // Nobody can say whether an invoice was created and a phone rang. There
            // is no idempotency key on this call and no way to ask Click about an
            // invoice it may not have made, so the attempt carries the question and
            // the payment resolver — status_by_mti against the id committed before
            // this call — is what settles it.
            throw new PresentationFailure.Uncertain(
                    "CLICK_INVOICE_UNCERTAIN", "invoice/create did not answer: " + response.describe());
        }
        if (!response.successful()) {
            throw new PresentationFailure.Refused("CLICK_INVOICE_REFUSED", response.describe());
        }

        String invoiceId = response.field("invoice_id");
        log.info("Click accepted an invoice for attempt {}", attempt.id());
        return new ProviderInvoice(PresentationKind.INVOICE_PUSH, null, null, invoiceId, null, null, null);
    }

    /**
     * The uncertainty resolver: {@code status_by_mti}, then {@code payment/status}.
     *
     * <p>Two calls, because the first resolves HorecaOS's id to Click's and carries no
     * state of its own — Click's own example response for it has no
     * {@code payment_status} field at all.
     *
     * <p>Never mutates, and reads are the one thing on this provider that is safe
     * to repeat.
     */
    @Override
    public ProviderOutcome queryOutcome(PaymentAttempt attempt, ProviderBinding binding) {
        Instant now = clock.instant();

        String paymentId = attempt.externalPayment().orElse(null);
        if (paymentId == null) {
            ClickResponse resolved =
                    click.statusByMerchantTransId(binding, attempt.merchantTransId(), attempt.businessDate());

            if (resolved.uncertain()
                    || resolved.status()
                            != uz.horecaos.platform.integration.api.provider.ProviderOutcome.Status.SUCCESS) {
                return ProviderOutcome.uncertain(
                        "CLICK_STATUS_LOOKUP_FAILED", "status_by_mti did not answer: " + resolved.describe());
            }
            paymentId = resolved.field("payment_id");

            if (!resolved.successful() || paymentId == null || paymentId.isBlank()) {
                // Click has no payment under this merchant transaction id *for the
                // business date asked about*. The date's meaning and timezone are
                // undocumented, so this is "we did not find it", not "it did not
                // happen" — and answering the second would let a caller charge the
                // card again.
                log.warn(
                        "Click reports no payment for attempt {} on {}; staying uncertain",
                        attempt.id(),
                        attempt.businessDate());
                return ProviderOutcome.uncertain(
                        "CLICK_PAYMENT_NOT_FOUND", "status_by_mti found no payment for the recorded business date");
            }
        }

        ClickResponse state = click.paymentStatus(binding, paymentId);
        if (!state.successful()) {
            return ProviderOutcome.uncertain(
                    "CLICK_STATUS_UNREADABLE", "payment/status did not answer: " + state.describe());
        }

        ClickPaymentStatus status = state.paymentStatus();
        ProviderEvidence evidence =
                ProviderEvidence.of(String.valueOf(state.body().get("payment_status")), now);

        return switch (status) {
            case PAID -> ProviderOutcome.success(PaymentAttemptStatus.CAPTURED, evidence, paymentId, attempt.amount());
            case FAILED ->
                ProviderOutcome.rejected(
                        "CLICK_PAYMENT_FAILED",
                        "Click reports a negative payment_status; the enumeration is unpublished",
                        evidence);
            // Created and in processing are not money. Answering uncertain again is
            // a legitimate result and means "still in flight" — several of Click's
            // own examples pair payment_status 1 with error_note "Success".
            case CREATED, IN_PROCESSING, UNKNOWN ->
                ProviderOutcome.uncertain(
                        "CLICK_IN_FLIGHT", "Click reports payment_status " + status + ", which is not yet money");
        };
    }

    /**
     * {@code DELETE payment/reversal/{service_id}/{payment_id}}.
     *
     * <p>Takes no amount, because Click has no partial reversal. Click also
     * requires an online-card payment inside the current reporting month — a
     * previous month's only on the first day of the current one — and UZCARD may
     * still refuse; none of that is checkable from here, so the answer arrives as a
     * failure after the fact.
     *
     * <p>Needs Click's {@code payment_id}, which a SHOP API callback never carries:
     * Complete brings {@code click_trans_id} and {@code click_paydoc_id}, and
     * <strong>nothing documents that {@code click_paydoc_id} is the
     * {@code payment_id} the reversal path wants</strong>. So the id is resolved
     * through {@code status_by_mti} rather than assumed, and a resolution that
     * fails leaves the reversal uncertain rather than sending a DELETE at a guessed
     * identifier.
     */
    @Override
    public ProviderOutcome reverse(PaymentAttempt attempt, ProviderBinding binding, String reason) {
        if (!binding.supportsReversal()) {
            return ProviderOutcome.rejected(
                    "REVERSAL_UNSUPPORTED", "This Click binding is not configured for reversals", null);
        }

        Optional<String> paymentId = resolvePaymentId(attempt, binding);
        if (paymentId.isEmpty()) {
            return ProviderOutcome.uncertain(
                    "CLICK_PAYMENT_ID_UNRESOLVED", "The payment to reverse could not be identified from status_by_mti");
        }

        ClickResponse reversal = click.reversal(binding, paymentId.get());
        Instant now = clock.instant();

        if (reversal.successful()) {
            log.info("Click reversed payment for attempt {} ({})", attempt.id(), reason);
            return ProviderOutcome.success(
                    PaymentAttemptStatus.REVERSED,
                    ProviderEvidence.of("reversed", now),
                    paymentId.get(),
                    attempt.amount());
        }
        if (reversal.uncertain()) {
            // The money may or may not have gone back. Resolved by reading the
            // payment's status, never by issuing the DELETE again.
            return ProviderOutcome.uncertain("CLICK_REVERSAL_UNCERTAIN", reversal.describe());
        }
        // A refusal Click stated. Which refusal it was cannot be said: the
        // error_code enumeration is unpublished and is an open question with CLICK,
        // so the code travels in the detail for a human rather than into a mapping
        // table this adapter invented.
        return ProviderOutcome.rejected(
                "CLICK_REVERSAL_REFUSED", reversal.describe(), ProviderEvidence.of("reversal-refused", now));
    }

    private Optional<String> resolvePaymentId(PaymentAttempt attempt, ProviderBinding binding) {
        if (attempt.externalPayment().filter(id -> !id.isBlank()).isPresent()) {
            return attempt.externalPayment();
        }
        ClickResponse resolved =
                click.statusByMerchantTransId(binding, attempt.merchantTransId(), attempt.businessDate());
        if (!resolved.successful()) {
            return Optional.empty();
        }
        return Optional.ofNullable(resolved.field("payment_id")).filter(id -> !id.isBlank());
    }
}
