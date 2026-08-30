package uz.horecaos.platform.payments.infrastructure.payme;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import uz.horecaos.platform.integration.api.provider.ProviderInstallationLookup;
import uz.horecaos.platform.payments.application.PaymentProviderPort;
import uz.horecaos.platform.payments.domain.PaymentAttempt;
import uz.horecaos.platform.payments.domain.PaymentAttemptStatus;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.PresentationFailure;
import uz.horecaos.platform.payments.domain.PresentationKind;
import uz.horecaos.platform.payments.domain.PresentationRequest;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.domain.ProviderInvoice;
import uz.horecaos.platform.payments.domain.ProviderOutcome;
import uz.horecaos.platform.payments.domain.TiyinAmount;
import uz.horecaos.platform.payments.infrastructure.persistence.JdbcPaymentAttemptStore;

/**
 * The outbound half of Payme (ADR 0013).
 *
 * <p>Thin, and unavoidably so. Payme's Merchant API has no merchant-initiated
 * call at all: no create, no query, no refund. The only thing HorecaOS sends is a
 * checkout link, which is a string built in this process and handed to a browser.
 * That is why this class makes no HTTP request and needs no ADR 0007 route — there
 * is no transport to classify, no lost response to be uncertain about, and nothing
 * for a circuit breaker to open on. If the Subscribe API is ever adopted, its
 * {@code receipts.*} calls go through {@code MerchantApiTransport} like Click's.
 */
@Component
public class PaymeProviderAdapter implements PaymentProviderPort {

    private static final Logger log = LoggerFactory.getLogger(PaymeProviderAdapter.class);

    private final ProviderInstallationLookup installations;
    private final JdbcPaymentAttemptStore attempts;
    private final boolean percentEncodePathSeparator;

    /**
     * @param percentEncodePathSeparator whether a {@code /} in the base64 checkout
     *                                   payload is written {@code %2F}. Standard
     *                                   base64 emits one about three times in a
     *                                   hundred-character link and it is the only
     *                                   character that changes the URL's structure
     *                                   rather than its content, so the default is
     *                                   to encode it. The docs settle neither this
     *                                   nor the padding question — their one worked
     *                                   example is 48 bytes long and so exhibits
     *                                   neither — and a sandbox transaction is the
     *                                   only arbiter. It is a property so that the
     *                                   sandbox can settle it without a release
     */
    public PaymeProviderAdapter(ProviderInstallationLookup installations,
            JdbcPaymentAttemptStore attempts,
            @Value("${horecaos.payments.payme.checkout.percent-encode-path-separator:true}")
            boolean percentEncodePathSeparator) {
        this.installations = installations;
        this.attempts = attempts;
        this.percentEncodePathSeparator = percentEncodePathSeparator;
    }

    /**
     * Builds the checkout link, and credits nothing.
     *
     * <p>The link is unsigned: anyone can build one with any amount against any
     * order reference they can guess, and the browser's return to the
     * {@code callback} URL is a browser event whose {@code :transaction} placeholder
     * can be the literal string {@code "null"} on a perfectly good payment. So no
     * state moves here, and the authoritative signal is {@code PerformTransaction}
     * arriving inbound.
     *
     * <p>No expiry is returned, deliberately. Payme's twelve-hour window starts when
     * <em>Payme</em> creates the transaction, which has not happened yet and may
     * never happen; stamping a deadline here would be measuring it from HorecaOS's
     * clock, which is exactly the error Payme's own Java template makes.
     *
     * <p>Two things the caller may ask for are deliberately not honoured here.
     * There is no push: Payme has no merchant-initiated call of any kind, so an
     * {@code INVOICE_PUSH} request is refused rather than quietly downgraded, which
     * would leave a caller believing a phone rang. And the return URL is left off
     * the payload: {@code c=} would change the base64 length, and whether Payme
     * wants the resulting {@code =} padding kept, stripped or percent-encoded is
     * open question U19 with one worked example that happens to exhibit none of the
     * three. Payme's documented fallback covers it — an absent {@code callback} is
     * taken from the request's {@code Referer}, which for a customer arriving from
     * the storefront is the storefront — and the return is a browser event that
     * proves nothing either way.
     */
    @Override
    public ProviderInvoice createInvoice(PaymentAttempt attempt, ProviderBinding binding,
            PresentationRequest request) {

        if (request.preferredKind() == PresentationKind.INVOICE_PUSH) {
            throw new PresentationFailure.Refused("PAYME_PUSH_UNSUPPORTED",
                    "Payme has no merchant-initiated invoice call; the customer follows a link");
        }

        String checkoutHost = installations.installation(binding.tenantId(), binding.installationId())
                .map(ProviderInstallationLookup.InstallationSnapshot::baseUrl)
                .filter(host -> host != null && !host.isBlank())
                .orElseThrow(() -> new PresentationFailure.Refused("PAYME_CHECKOUT_HOST_MISSING",
                        // Never defaulted to the production host. An installation
                        // that does not say where it points is a configuration
                        // error, and guessing it is how a sandbox cashbox sends a
                        // customer to a live checkout page.
                        "Payme installation " + binding.installationId()
                                + " declares no checkout host"));

        String payload = PaymeCheckoutLink.payload(binding.merchantAccountReference(),
                attempt.merchantTransId(), TiyinAmount.of(attempt.amount()));
        String url = PaymeCheckoutLink.url(checkoutHost, payload, percentEncodePathSeparator);

        // The QR encodes the same URL. There is no documented payme:// deeplink
        // scheme and none is invented here: the docs' "mobile integration" section
        // is the Android card-tokenisation SDK, which is a different protocol. Both
        // are returned whichever was asked for, and the requested kind only decides
        // which one the caller is told it is looking at.
        PresentationKind kind = request.preferredKind() == PresentationKind.QR
                ? PresentationKind.QR : PresentationKind.PAYMENT_LINK;
        return new ProviderInvoice(kind, url, url, null, null, null, null);
    }

    /**
     * Answers from HorecaOS's own record, because Payme offers nothing else.
     *
     * <p>{@code CheckTransaction} is a method Payme calls on HorecaOS, not one HorecaOS
     * can call on Payme, so there is no provider to ask. That is less alarming than
     * it sounds: on this provider the roles are reversed, Payme repeats its own
     * mutating calls until they are answered, and everything that decides an
     * attempt's fate is written in the same database transaction as the order
     * change it implies. What remains genuinely unknown is nothing, and this
     * reports the persisted position.
     *
     * <p>A reservation still in state {@code 1} answers {@code UNCERTAIN} again,
     * meaning "still in flight" — which is a legitimate result and not a failure.
     * Reporting it as failed would let a second attempt start against an order Payme
     * is about to perform.
     */
    @Override
    public ProviderOutcome queryOutcome(PaymentAttempt attempt, ProviderBinding binding) {
        PaymentAttempt current = attempts.find(attempt.tenantId(), attempt.id()).orElse(attempt);
        PaymentAttemptStatus status = current.status();

        return switch (status) {
            case CAPTURED -> ProviderOutcome.success(PaymentAttemptStatus.CAPTURED,
                    current.evidence(), current.externalPaymentId(), current.amount());
            case CANCELLED, EXPIRED, REVERSED, FAILED -> new ProviderOutcome(
                    ProviderOutcome.Classification.SUCCESS, status, current.evidence(),
                    current.externalPaymentId(), null, null, null,
                    "Payme reported this transaction as " + status, null);
            case RESERVED -> ProviderOutcome.uncertain("PAYME_TRANSACTION_IN_FLIGHT",
                    "Payme created the transaction and has not performed or cancelled it yet");
            case INITIATED, PRESENTED -> ProviderOutcome.uncertain("PAYME_NO_TRANSACTION",
                    "Payme has not created a transaction against this attempt");
            case UNCERTAIN -> ProviderOutcome.uncertain("PAYME_STATE_UNRESOLVED",
                    "Nothing in HorecaOS's record settles this attempt");
        };
    }

    /**
     * Refused, and declared rather than attempted.
     *
     * <p>Payme has no outbound refund call in either direction of the Merchant API.
     * A refund is pressed in the Payme merchant cabinet and arrives here as
     * {@code CancelTransaction}, where HorecaOS's only lever is to veto it with
     * {@code -31007}. {@code ProviderBinding.supportsReversal()} carries the same
     * fact so the operations console can render it before an operator commits to
     * rejecting a paid order.
     */
    @Override
    public ProviderOutcome reverse(PaymentAttempt attempt, ProviderBinding binding, String reason) {
        log.warn("A reversal was requested for Payme attempt {}; Payme has no outbound refund and "
                + "the refund must be issued from the Payme merchant cabinet.", attempt.id());
        return ProviderOutcome.rejected("PAYME_REVERSAL_IS_INBOUND",
                "Payme refunds are initiated in the Payme merchant cabinet and arrive as "
                        + "CancelTransaction; HorecaOS can only accept or veto them",
                Optional.ofNullable(attempt.evidence()).orElse(null));
    }

    @Override
    public PaymentProviderType providerType() {
        return PaymentProviderType.PAYME;
    }
}
