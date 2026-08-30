package uz.horecaos.platform.integration.camel.sms;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.integration.provider.SmsAccountLookup.SmsAccount;

/**
 * smsgw.vas.uz, behind ADR 0007's rules, from
 * {@code docs/providers/sms-gateway-vas.md}.
 *
 * <p>Two of the provider's four operations are implemented, and the two that are
 * not are as much of the design as the two that are.
 *
 * <p><strong>{@code /send_msgs} is not used.</strong> Its envelope reports
 * {@code status.code: 0 — success} while individual entries carry their own
 * failure codes and {@code id: 0}, so a caller reading only the envelope
 * concludes everything sent. There is exactly one message in a verification code,
 * so the bulk endpoint buys nothing and costs a per-entry parser that nobody
 * would have a reason to keep honest. If a caller ever needs it, every entry has
 * to be inspected on its own and {@code id: 0} means nothing was accepted.
 *
 * <p><strong>The distribution API is not used either</strong>, which is why codes
 * 22–26 exist in {@link SmsGateCode} only so that receiving one is a named
 * refusal rather than an unreadable answer.
 *
 * <p><strong>There is no idempotency key on {@code /send}.</strong> That single
 * fact shapes everything here. A lost or unreadable answer is
 * {@link ProviderOutcome.Status#UNCERTAIN} and is resolved by
 * {@link #resolve} — a {@code /search} against the destination for the day — in
 * the same way the Click adapter resolves an uncertain payment with
 * {@code status_by_mti} rather than by charging the card again. Nothing in this
 * class re-sends, and no header pretends the provider deduplicates: an
 * {@code Idempotency-Key} it ignores would be a claim the document does not
 * support.
 *
 * <p>The request body is never logged, at any level, on any path. See
 * {@link SmsGateBody}.
 */
@Component
public class VasSmsGatewayAdapter {

    /** The ADR 0026 {@code provider_type} an installation must declare to be called here. */
    public static final String PROVIDER_TYPE = "SMSGW_VAS";

    static final String SEND_PATH = "/send";
    static final String SEARCH_PATH = "/search";

    /** Keys the route and the transport read off a normalised outcome. Bounded and safe. */
    static final String MESSAGE_ID_KEY = "providerMessageId";
    static final String SEGMENTS_KEY = "providerSegments";
    static final String DELIVERY_STATE_KEY = "providerDeliveryState";

    /**
     * No headers, and that is a statement rather than an omission.
     *
     * <p>The other adapters on this platform send an {@code Idempotency-Key}. This
     * provider documents none anywhere, so sending one would be a claim the
     * document does not support — and a header the provider ignores is exactly the
     * kind of thing a later reader takes for a guarantee.
     */
    private static final Map<String, String> NO_HEADERS = Map.of();

    private final ProviderHttpClient http;

    public VasSmsGatewayAdapter(ProviderHttpClient http) {
        this.http = http;
    }

    /**
     * {@code POST /send}. One message, one destination, no key to repeat it under.
     *
     * <p>A 2xx here is not success on its own: this provider reports business
     * failures inside a 200 body, so the envelope's {@code status.code} decides.
     * A {@code 0} that arrives without a message id contradicts itself and is
     * treated as uncertain rather than believed — {@code id} is the only evidence
     * the gateway actually took the message.
     */
    public ProviderOutcome send(SmsVerificationOperation operation, SmsAccount account,
            ProviderCall call) {

        SmsGateBody.Send body = new SmsGateBody.Send(account.login(), call.credential(),
                account.sender(), operation.destination(), operation.text());

        return http.post(call, SEND_PATH, NO_HEADERS, body, response -> {
            SmsGateCode code = SmsGateCode.of(integer(response.get("status") instanceof Map<?, ?> status
                    ? status.get("code")
                    : null));

            if (code.effect() != SmsGateCode.Effect.ACCEPTED) {
                return classify(code);
            }

            String messageId = text(response.get("id"));
            if (messageId == null || messageId.isBlank() || "0".equals(messageId)) {
                // Success with no identifier. The provider's own bulk response
                // uses id 0 to mean "nothing was accepted", so a 0 here is at best
                // ambiguous, and there is nothing to carry into a callback or a
                // support conversation. Resolved by asking, never by resending.
                return ProviderOutcome.uncertain("SMS_ACCEPTED_WITHOUT_ID",
                        "The gateway reported success without a message id");
            }

            return ProviderOutcome.success(
                    Map.of(MESSAGE_ID_KEY, messageId,
                            SEGMENTS_KEY, String.valueOf(integerOr(response.get("parts"), 1))),
                    messageId);
        });
    }

    /**
     * {@code POST /search}: the uncertainty resolver. Sends nothing.
     *
     * <p>The provider answers with every message it holds for that destination on
     * that day, <em>including the text</em>. Ours is the entry whose text carries
     * the code we were trying to send, which is the only correlator this API
     * offers — there is no key to ask by. The comparison happens here, in memory,
     * and neither the code nor the text it came back in is logged, counted, or
     * put on the outcome.
     *
     * <p><strong>Not finding it is not proof it was never sent.</strong> The
     * {@code date} parameter names a day whose timezone the document does not
     * state, so a message sent either side of a boundary can be absent from a
     * search that is working perfectly. The answer is therefore "unconfirmed" and
     * never "not sent" — the second would license the resend this whole path
     * exists to prevent.
     */
    public ProviderOutcome resolve(SmsVerificationOperation operation, SmsAccount account,
            ProviderCall call) {

        SmsGateBody.Search body = new SmsGateBody.Search(account.login(), call.credential(),
                operation.destination(), operation.issuedAt().getEpochSecond());

        return http.post(call, SEARCH_PATH, NO_HEADERS, body, response -> {
            SmsGateCode code = SmsGateCode.of(integer(response.get("status") instanceof Map<?, ?> status
                    ? status.get("code")
                    : null));
            if (code.effect() != SmsGateCode.Effect.ACCEPTED) {
                // The search itself was refused. That says nothing about the send,
                // so it stays uncertain rather than inheriting the search's own
                // refusal — a wrong key on the query is not a blacklisted
                // recipient on the message.
                return ProviderOutcome.uncertain("SMS_SEARCH_REFUSED", code.reasonCode());
            }

            for (Map<String, Object> entry : entries(response)) {
                String sentText = text(entry.get("msg"));
                if (sentText == null || !sentText.contains(operation.code())) {
                    continue;
                }
                return found(entry);
            }

            return ProviderOutcome.uncertain("SMS_SEND_UNCONFIRMED",
                    "The gateway holds no message carrying this challenge for that destination");
        });
    }

    /**
     * The message was found. What state it is in decides the answer.
     *
     * <p>Only the three states the provider states as failures are failures.
     * {@code Sent} means handed to the operator and never confirmed, and
     * {@code Unknown} is terminal-and-unresolved; both are ordinary for a
     * subscriber whose operator sends no receipt, and CDMA subscribers send none
     * at all. Reading either as "not delivered" would tear down a challenge whose
     * code is on a customer's phone.
     */
    private static ProviderOutcome found(Map<String, Object> entry) {
        SmsGateDeliveryState state = SmsGateDeliveryState.of(integer(entry.get("status")));
        String messageId = text(entry.get("id"));

        if (state.isBlacklisted()) {
            return ProviderOutcome.rejected(SmsGateCode.RECEIVER_IN_BLACKLIST.reasonCode(),
                    "The operator's blacklist holds this destination");
        }
        if (state.isFailure()) {
            return ProviderOutcome.retryable("SMS_DELIVERY_FAILED",
                    "The gateway reports " + state.name() + " for this message", null);
        }
        return ProviderOutcome.success(
                Map.of(MESSAGE_ID_KEY, messageId == null ? "" : messageId,
                        DELIVERY_STATE_KEY, state.name()),
                messageId);
    }

    private static ProviderOutcome classify(SmsGateCode code) {
        return switch (code.effect()) {
            case REFUSED -> ProviderOutcome.rejected(code.reasonCode(), describe(code));
            // No retryAfter, deliberately, and it matters most for SPAM: a delay
            // would turn the one signal that our own limiter is broken into
            // patient background traffic. See SmsGateCode.
            case RETRYABLE -> ProviderOutcome.retryable(code.reasonCode(), describe(code), null);
            case UNCERTAIN -> ProviderOutcome.uncertain(code.reasonCode(), describe(code));
            case ACCEPTED -> throw new IllegalStateException("An accepted code is not a failure");
        };
    }

    /**
     * The provider's numeric code and our name for it, and nothing else.
     *
     * <p>Never the provider's {@code description}: this provider echoes what it
     * was sent inside an error, and what it was sent is a phone number and a live
     * one-time code.
     */
    private static String describe(SmsGateCode code) {
        return code == SmsGateCode.UNDOCUMENTED
                ? "The gateway answered with no code this adapter recognises"
                : "gateway code %d (%s)".formatted(code.wireValue(), code.name());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> response) {
        Object data = response.get("data");
        if (!(data instanceof List<?> items)) {
            return List.of();
        }
        return items.stream()
                .filter(Map.class::isInstance)
                .map(item -> (Map<String, Object>) item)
                .toList();
    }

    /**
     * A wire integer, whatever JSON shape it arrived in.
     *
     * <p>This provider is inconsistent about it — {@code /send} quotes its id as a
     * string while {@code /send_msgs} returns it as a number — so a cast would
     * work in testing and throw in production.
     */
    private static Integer integer(Object value) {
        return switch (value) {
            case Number number -> number.intValue();
            case String string -> parse(string);
            case null, default -> null;
        };
    }

    private static int integerOr(Object value, int fallback) {
        Integer parsed = integer(value);
        return parsed == null ? fallback : parsed;
    }

    private static Integer parse(String value) {
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
