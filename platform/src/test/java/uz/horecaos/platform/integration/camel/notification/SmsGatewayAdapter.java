package uz.horecaos.platform.integration.camel.notification;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;
import uz.horecaos.platform.notifications.api.NotificationDispatch;

/**
 * The controlled fake SMS gateway ADR 0007 asks for, and the only adapter that
 * speaks {@code GENERIC_SMS} (ADR 0146 Decision 3).
 *
 * <p>This used to be the one wired SMS channel in production, deliberately generic
 * because no SMS contract existed. ADR 0146 made it test scope: a production bean
 * with a request shape nobody agreed to, and an {@code Idempotency-Key} header no
 * real gateway documents, is a placeholder that a tenant could bind and lose its
 * order confirmations to. What it still proves is the path — intent, eligibility,
 * template, render, attempt, route, provider, outcome — and, because it honours an
 * idempotency key, the ADR 0007 behaviours (a repeated key produces one effect, a
 * lost reply is resolved rather than resent) that a gateway with no key cannot
 * show. It stays a {@code @Component}, so any Spring test context carries it and
 * a second SMS adapter beside {@code VasSmsGatewayAdapter} is exercised by every
 * context test that starts the application.
 *
 * <p>Its provider type is accepted for a test installation (an approved
 * environment row is inserted by the test), never for a production one: no
 * migration approves {@code GENERIC_SMS}.
 *
 * <p>The request carries the idempotency key as a header, so the ADR 0007 contract
 * suite's "a repeated key produces one side effect" test exercises the same path a
 * real gateway would.
 *
 * <p>The recipient and the body are on the request and nowhere else. They are never
 * logged here, and the shared client logs the classifier's message rather than the
 * provider's body for the same reason: provider errors are known to echo request
 * content back.
 */
@Component
public class SmsGatewayAdapter implements NotificationChannelAdapter {

    static final String SEND_PATH = "/provider/commands";
    static final String STATUS_PATH = "/provider/commands/";

    /**
     * The gateway answered that it has never seen this key.
     *
     * <p>The one answer that makes a second send safe, and the reason a status
     * query exists at all. Every other answer leaves the message possibly sent.
     */
    static final String NO_RECORD = NotificationChannelAdapter.NO_RECORD;

    private final ProviderHttpClient http;

    public SmsGatewayAdapter(ProviderHttpClient http) {
        this.http = http;
    }

    @Override
    public String providerType() {
        return "GENERIC_SMS";
    }

    @Override
    public String channel() {
        return "SMS";
    }

    @Override
    public ProviderOutcome send(NotificationDispatch dispatch, ProviderCall call) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("to", dispatch.recipientValue());
        body.put("text", dispatch.body());

        return http.post(
                call,
                SEND_PATH,
                Map.of("Idempotency-Key", dispatch.providerIdempotencyKey()),
                body,
                response -> ProviderOutcome.success(
                        Map.of("providerStatus", string(response, "status", "ACCEPTED")),
                        string(response, "externalReference", null)));
    }

    @Override
    public ProviderOutcome queryStatus(String providerIdempotencyKey, ProviderCall call) {
        return http.get(call, STATUS_PATH + providerIdempotencyKey, Map.of(), response -> {
            String status = string(response, "status", "UNKNOWN");
            if ("NOT_FOUND".equalsIgnoreCase(status)) {
                // Read from the body rather than from a 404, deliberately. A 404
                // and a 400 look identical once the shared client has classified
                // them, and "the gateway rejected my query" must never be mistaken
                // for "the gateway never had this message" — the second licenses a
                // resend and the first does not.
                return ProviderOutcome.rejected(NO_RECORD, "The gateway has no record of this request");
            }
            return ProviderOutcome.success(
                    Map.of("providerStatus", status), string(response, "externalReference", null));
        });
    }

    /**
     * The fake's own receipt body, {@code {"messageId": …, "status": …}}, which is
     * the only receipt shape that exists for a gateway that can carry a secret
     * header ({@code ReceiptAuthentication.SECRET_HEADER}, the default). It exists
     * so the receipt endpoint's rules are proven without a real provider whose
     * callback has not been captured.
     */
    @Override
    public Optional<ReceiptEvent> normalise(Map<String, Object> rawReceipt) {
        String id = string(rawReceipt, "messageId", null);
        String status = string(rawReceipt, "status", null);
        if (id == null || id.isBlank() || status == null) {
            return Optional.empty();
        }
        String word = status.toUpperCase(java.util.Locale.ROOT);
        return switch (word) {
            case "ACCEPTED" -> Optional.of(new ReceiptEvent(id, "ACCEPTED", word, null, false));
            case "SENT" -> Optional.of(new ReceiptEvent(id, "DISPATCHED", word, null, false));
            case "DELIVERED" -> Optional.of(new ReceiptEvent(id, "DELIVERED", word, null, false));
            case "FAILED" -> Optional.of(new ReceiptEvent(id, "FAILED", word, null, false));
            case "BLACKLISTED" -> Optional.of(new ReceiptEvent(id, "FAILED", word, null, true));
            case "UNKNOWN" -> Optional.of(new ReceiptEvent(id, "UNKNOWN", word, null, false));
            default -> Optional.empty();
        };
    }

    /**
     * Reads one string from a provider response.
     *
     * <p>A missing field falls back rather than throwing. A gateway that answers
     * 200 with a shape we did not expect has still accepted the message, and
     * turning that into an exception would classify a successful send as a
     * transport failure and send it round again.
     */
    private static @Nullable String string(Map<String, Object> response, String key, @Nullable String fallback) {
        Object value = response.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
