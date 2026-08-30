package uz.horecaos.platform.payments.infrastructure.click;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.payment.MerchantApiCall;
import uz.horecaos.platform.integration.api.payment.MerchantApiTransport;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.payments.domain.PaymentProviderType;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.domain.SomAmount;
import uz.horecaos.platform.payments.domain.TiyinAmount;

/**
 * Click's MERCHANT API, endpoint by endpoint (ADR 0013, ADR 0007).
 *
 * <p>Everything goes out through {@link MerchantApiTransport}, which is the ADR
 * 0007 route seen from this side: the route owns the timeout, the circuit, the
 * classification and the rule that a lost mutating response is uncertain, and this
 * class owns the paths, the bodies, the {@code Auth} header and the units.
 *
 * <p><strong>The units are the reason each method's signature reads the way it
 * does.</strong> Everything on this class that Click calls {@code amount} takes a
 * {@link SomAmount}; everything Click calls {@code Price}, {@code VAT} or
 * {@code received_*} takes a {@link TiyinAmount}. The same payment is som here and
 * tiyin in {@link #submitItems}, and no method takes a bare {@code long} for
 * money, so the two cannot be swapped by a caller that misremembers which is which.
 *
 * <p>Base URL comes from the ADR 0026 installation and must be the {@code /v2/merchant}
 * root. Version two, not one: Click's documentation and its newer PHP reference
 * both use v2, and the Django reference's hardcoded v1 is stale along with most of
 * the rest of that sample.
 */
@Component
public class ClickMerchantApi {

    private static final DateTimeFormatter BUSINESS_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * Longer than the delivery route's twenty seconds, and deliberately.
     *
     * <p>A read timeout on a Click payment call is not a retry, it is a manual
     * reconciliation and a blocked intent, so waiting a little longer for an
     * answer that would settle the question is cheaper than giving up on one.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final MerchantApiTransport transport;
    private final Clock clock;

    public ClickMerchantApi(MerchantApiTransport transport, Clock clock) {
        this.transport = transport;
        this.clock = clock;
    }

    /**
     * Pushes a payment request to a phone. {@code amount} is in <strong>som</strong>.
     *
     * <p>A created invoice is not a payment: the customer still has to accept it in
     * the Click application, and HorecaOS learns about the money through the SHOP API
     * callback and never from here. {@code merchant_trans_id} is the join key that
     * comes back on Prepare and Complete, so it must already be committed before
     * this call is made.
     *
     * <p>{@code phoneNumber} is personal data under ADR 0029. It is passed straight
     * into the request body and is never logged, never held on a field, and never
     * put on the {@link MerchantApiCall}'s {@code toString}.
     */
    public ClickResponse createInvoice(
            ProviderBinding binding, String merchantTransId, SomAmount amount, String phoneNumber) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service_id", binding.merchantAccountReference());
        body.put("amount", som(amount));
        body.put("phone_number", phoneNumber);
        body.put("merchant_trans_id", merchantTransId);
        return call(binding, "invoice.create", "POST", "/invoice/create", body, true);
    }

    /**
     * Resolves HorecaOS's own id to Click's {@code payment_id}.
     *
     * <p>The first half of the uncertainty resolver, and the reason
     * {@code merchantTransId} and {@code businessDate} are written and committed
     * before any mutating call. The trailing {@code YYYY-MM-DD} is documented as
     * part of the path with no explanation of which date it is or in what timezone,
     * so a wrong one reads as "no payment found" — which is exactly the answer that
     * would make a retry look safe. That is why a not-found from here never
     * unblocks a second charge.
     *
     * <p>The PHP reference issues this as a {@code DELETE} and omits the date. That
     * is a bug in the sample, not an alternative API: both the Russian and English
     * documentation pages say {@code GET} with the date.
     */
    public ClickResponse statusByMerchantTransId(
            ProviderBinding binding, String merchantTransId, LocalDate businessDate) {
        String path = "/payment/status_by_mti/" + segment(binding.merchantAccountReference()) + "/"
                + segment(merchantTransId) + "/" + BUSINESS_DATE.format(businessDate);
        return call(binding, "payment.status_by_mti", "GET", path, null, false);
    }

    /**
     * The state of one Click payment.
     *
     * <p>The second half of the resolver. Read {@code payment_status} and not
     * {@code error_code}: several of Click's own examples pair
     * {@code payment_status: 1} with {@code error_note: "Success"}, and only
     * {@code payment_status: 2} is money.
     */
    public ClickResponse paymentStatus(ProviderBinding binding, String paymentId) {
        String path = "/payment/status/" + segment(binding.merchantAccountReference()) + "/" + segment(paymentId);
        return call(binding, "payment.status", "GET", path, null, false);
    }

    /**
     * Gives a captured payment back. Takes no amount, because Click has none.
     *
     * <p>There is no partial reversal in the documented API. Click additionally
     * requires the payment to have completed, to have been made with an online
     * card, and to fall inside the current reporting month — a previous month's
     * payment can be reversed only on the first day of the current one — and
     * UZCARD may still refuse. All of that is Click's, none of it is checkable
     * from here, and the answer arrives as a failure after the fact.
     */
    public ClickResponse reversal(ProviderBinding binding, String paymentId) {
        String path = "/payment/reversal/" + segment(binding.merchantAccountReference()) + "/" + segment(paymentId);
        return call(binding, "payment.reversal", "DELETE", path, null, true);
    }

    /**
     * Fiscalizes a payment. Every amount here is <strong>tiyin</strong>.
     *
     * <p>The same payment whose {@code amount} went out in som on
     * {@link #createInvoice}. Click documents «тийин» on {@code Price}, {@code VAT},
     * {@code received_ecash}, {@code received_cash} and {@code received_card}, and
     * on nothing else in the whole API.
     *
     * <p>Strictly after capture, because {@code payment_id} is Click's and does not
     * exist before the payment does. Whether a second submission for one
     * {@code payment_id} rejects, replaces or duplicates the receipt is
     * <strong>not documented and is an open question with CLICK</strong>, so a lost
     * response here is resolved by {@link #ofdData} and never by sending the items
     * again.
     */
    public ClickResponse submitItems(
            ProviderBinding binding,
            String paymentId,
            List<Map<String, Object>> items,
            TiyinAmount receivedCard,
            TiyinAmount receivedCash,
            TiyinAmount receivedEcash) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service_id", binding.merchantAccountReference());
        body.put("payment_id", paymentId);
        body.put("items", items);
        body.put("received_ecash", receivedEcash.value());
        body.put("received_cash", receivedCash.value());
        body.put("received_card", receivedCard.value());
        return call(binding, "ofd.submit_items", "POST", "/payment/ofd_data/submit_items", body, true);
    }

    /**
     * Reads back the fiscal evidence: {@code paymentId} and {@code qrCodeURL}.
     *
     * <p>Note the camelCase, which nothing else in this API uses. This is the call
     * that settles an uncertain {@code submit_items}: a populated {@code qrCodeURL}
     * means the submission already worked, whatever the lost response would have
     * said. It is also eventually consistent — Click does not say how long the OFD
     * round trip takes — so an empty answer means "not yet", not "never".
     */
    public ClickResponse ofdData(ProviderBinding binding, String paymentId) {
        String path = "/payment/ofd_data/" + segment(binding.merchantAccountReference()) + "/" + segment(paymentId);
        return call(binding, "ofd.read", "GET", path, null, false);
    }

    /**
     * Attaches a receipt HorecaOS fiscalized elsewhere.
     *
     * <p>Unused at cutover — ADR 0038's terminal path is not built — and present
     * because it is the call a location with its own KKM will need, and because
     * having it here stops someone reaching for {@link #submitItems} to do a job it
     * does not do.
     */
    public ClickResponse submitQrCode(ProviderBinding binding, String paymentId, String qrCodeUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("service_id", binding.merchantAccountReference());
        body.put("payment_id", paymentId);
        body.put("qrcode", qrCodeUrl);
        return call(binding, "ofd.submit_qrcode", "POST", "/payment/ofd_data/submit_qrcode", body, true);
    }

    private ClickResponse call(
            ProviderBinding binding,
            String operation,
            String method,
            String path,
            Map<String, Object> body,
            boolean mutating) {

        String merchantUser = binding.merchantUser().orElse(null);
        if (merchantUser == null || merchantUser.isBlank()) {
            // The Auth header cannot be formed without it, so nothing is sent and
            // nothing can have happened. A rejection rather than an uncertainty:
            // this is configuration, and a human fixes it rather than a resolver.
            return ClickResponse.configurationFailure(
                    "The Click binding carries no merchant_user_id, so no Auth header can be built");
        }

        MerchantApiCall call = new MerchantApiCall(
                binding.tenantId(),
                binding.installationId(),
                PaymentProviderType.CLICK.name(),
                operation,
                method,
                path,
                body,
                mutating,
                authorization(merchantUser),
                null,
                TIMEOUT);

        return ClickResponse.of(transport.exchange(call), mutating);
    }

    /**
     * Builds {@code Auth: merchant_user_id:sha1(timestamp + secret_key):timestamp}.
     *
     * <p>A function of the credential rather than the credential itself: the
     * gateway resolves the secret, hands it to this lambda for the length of one
     * call, and the value never lands on a field, a record, or a log line. The
     * timestamp is taken per call because Click documents no validity window and no
     * accepted clock skew, so a cached header is a header that may already be stale.
     */
    private Function<String, Map<String, String>> authorization(String merchantUserId) {
        return secret -> Map.of(
                "Auth",
                ClickSignature.authHeader(
                        merchantUserId, secret, clock.instant().getEpochSecond()));
    }

    /**
     * Click's {@code amount}, in som.
     *
     * <p>Sent as a whole number because UZS is transacted in whole som and ADR 0018
     * stores whole som. The payment <em>link</em> is the one surface Click formats
     * differently, {@code N.NN}, and that formatting lives with the link.
     */
    private static long som(SomAmount amount) {
        return amount.value();
    }

    private static String segment(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    /**
     * One Click response, transport classification and body together.
     *
     * @param body the parsed JSON, never logged as a whole
     */
    public record ClickResponse(ProviderOutcome.Status status, Map<String, Object> body, boolean mutating) {

        static ClickResponse of(ProviderOutcome outcome, boolean mutating) {
            return new ClickResponse(
                    outcome.status(), outcome.normalized() == null ? Map.of() : outcome.normalized(), mutating);
        }

        static ClickResponse configurationFailure(String detail) {
            return new ClickResponse(ProviderOutcome.Status.REJECTED, Map.of("error_note", detail), false);
        }

        /**
         * Reached Click, Click answered, and Click said the call succeeded.
         *
         * <p>A read and a mutation ask different questions of the same field. Click
         * omits {@code error_code} on a successful fiscal read-back, so absence has
         * to mean success there. Applying that to a mutation meant a 2xx with an
         * empty or unparsed body -- an ordinary answer from a proxy in front of a
         * DELETE -- was read as a completed reversal, and the ledger then recorded
         * money returned to a cardholder that Click may never have moved.
         */
        public boolean successful() {
            return status == ProviderOutcome.Status.SUCCESS
                    && (mutating
                            ? ClickErrorCodes.successfulMutation(body.get("error_code"))
                            : ClickErrorCodes.successfulRead(body.get("error_code")));
        }

        /**
         * Nobody can say whether Click acted. Resolve by query; never send it again.
         *
         * <p>A mutating 2xx that does not state an error code lands here rather
         * than in a bare rejection, because "Click did not tell us" is exactly the
         * question the adapters resolve with {@code status_by_mti}. Rejecting it
         * would invite a resend, which on a capture is a second charge.
         */
        public boolean uncertain() {
            return status == ProviderOutcome.Status.UNCERTAIN
                    || (mutating
                            && status == ProviderOutcome.Status.SUCCESS
                            && ClickErrorCodes.uncertainMutation(body.get("error_code")));
        }

        public String field(String name) {
            Object value = body.get(name);
            return value == null ? null : String.valueOf(value);
        }

        public ClickPaymentStatus paymentStatus() {
            return ClickPaymentStatus.of(body.get("payment_status"));
        }

        /** Safe to log: the numeric code and Click's note, truncated. */
        public String describe() {
            return status + " " + ClickErrorCodes.describe(body.get("error_code"), body.get("error_note"));
        }

        @Override
        public String toString() {
            return "ClickResponse[" + describe() + "]";
        }
    }
}
