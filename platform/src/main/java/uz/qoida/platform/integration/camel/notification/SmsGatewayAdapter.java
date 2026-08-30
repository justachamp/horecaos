package uz.qoida.platform.integration.camel.notification;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import uz.qoida.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.qoida.platform.integration.api.provider.ProviderOutcome;
import uz.qoida.platform.integration.camel.common.ProviderHttpClient;
import uz.qoida.platform.notifications.api.NotificationDispatch;

/**
 * A generic JSON-over-HTTP SMS gateway (ADR 0020, ADR 0007).
 *
 * <p>This is the one wired channel. It is deliberately generic rather than named
 * after a vendor: no SMS contract exists yet, and inventing a provider-specific
 * adapter would encode a request shape nobody has agreed to. What it does prove is
 * the path — intent, eligibility, template, render, attempt, route, provider,
 * outcome — end to end, which is what ADR 0020's rollout asks for before a real
 * gateway is connected.
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
    static final String NO_RECORD = "PROVIDER_HAS_NO_RECORD";

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

        return http.post(call, SEND_PATH,
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
                return ProviderOutcome.rejected(NO_RECORD,
                        "The gateway has no record of this request");
            }
            return ProviderOutcome.success(Map.of("providerStatus", status),
                    string(response, "externalReference", null));
        });
    }

    /**
     * Reads one string from a provider response.
     *
     * <p>A missing field falls back rather than throwing. A gateway that answers
     * 200 with a shape we did not expect has still accepted the message, and
     * turning that into an exception would classify a successful send as a
     * transport failure and send it round again.
     */
    private static String string(Map<String, Object> response, String key, String fallback) {
        Object value = response.get(key);
        return value == null ? fallback : String.valueOf(value);
    }
}
