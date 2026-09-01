package uz.horecaos.platform.integration.provider.telegramgateway;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretValue;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.common.ProviderHttpClient;

/**
 * Telegram Gateway (ADR 0063) — {@code https://gateway.telegram.org}, not the Bot
 * API. A separate, separately-billed Telegram product: its own base URL, its own
 * bearer token, and no relationship to a brand's own bot beyond both being
 * Telegram products. Modelled on {@link uz.horecaos.platform.integration.provider.telegram.TelegramBotApiClient}'s
 * shape — one class owning one provider's whole HTTP surface — but as its own
 * class with its own configuration, because unlike the Bot API this product is
 * platform-wide rather than per-tenant: there is one token, not one ADR 0026
 * installation per brand, so {@link #isConfigured()} is the whole of "is this
 * usable" and nothing here ever resolves a tenant's own binding.
 *
 * <p>Unlike {@code TelegramBotApiClient}, this class runs its calls through
 * {@link ProviderHttpClient}: Gateway's HTTP contract is ordinary bearer-header
 * REST/JSON with no bot-token-in-the-URL-path oddity and no per-chat 403
 * semantics to hand-classify, so the shared ADR 0007 classifier is the right
 * fit rather than a reason to avoid it.
 *
 * <p><strong>The real token is an open input</strong> (ADR 0063's own Open
 * Inputs list): {@link #isConfigured()} is false until the owner obtains a
 * Gateway account and its secret reference is set, and the whole delivery-policy
 * seam in {@code CamelVerificationCodeTransport} is written to fall back to SMS
 * whenever it is. Nothing here fails a deployment that never configures it.
 */
@Component
public class TelegramGatewayClient {

    static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    static final String SEND_PATH = "/sendVerificationMessage";

    /** Keys the delivery-policy seam reads off a successful outcome's normalized map. */
    public static final String REQUEST_ID_KEY = "telegramGatewayRequestId";

    public static final String COST_MINOR_KEY = "telegramGatewayCostMinor";
    public static final String COST_CURRENCY_KEY = "telegramGatewayCostCurrency";

    /** Gateway prices in fractional USD; every cost this client reports is converted here. */
    private static final String COST_CURRENCY = "USD";

    private static final Logger log = LoggerFactory.getLogger(TelegramGatewayClient.class);

    private final ProviderHttpClient http;
    private final SecretResolver secrets;
    private final String baseUrl;
    private final @Nullable SecretReference tokenReference;

    public TelegramGatewayClient(
            ProviderHttpClient http,
            SecretResolver secrets,
            @Value("${horecaos.telegram.gateway.base-url:https://gateway.telegram.org}") String baseUrl,
            @Value("${horecaos.telegram.gateway.secret-reference:}") String secretReferenceRaw) {
        this.http = http;
        this.secrets = secrets;
        this.baseUrl = baseUrl;
        this.tokenReference = secretReferenceRaw == null || secretReferenceRaw.isBlank()
                ? null
                : SecretReference.parse(secretReferenceRaw);
    }

    /**
     * Whether a Gateway token is configured at all.
     *
     * <p>The delivery-policy seam asks this before ever building a request, so an
     * unconfigured deployment never spends a network round trip — or a log line —
     * discovering what this method already knows for free.
     */
    public boolean isConfigured() {
        return tokenReference != null;
    }

    /**
     * Sends one verification code through the Gateway.
     *
     * <p>Never throws for a provider failure, the same contract every ADR 0007
     * adapter keeps: the caller — {@code CamelVerificationCodeTransport} — decides
     * from the outcome whether to fall back to SMS.
     *
     * @throws IllegalStateException if called while {@link #isConfigured()} is
     *                                false; the caller must check first, the same
     *                                discipline {@code SmsGateway} keeps around its
     *                                own binding lookup
     */
    public ProviderOutcome sendVerificationMessage(TelegramGatewayVerificationOperation operation) {
        SecretReference reference = requireConfigured();
        SecretValue credential = secrets.resolve(reference);
        ProviderOutcome outcome = dispatch(operation, credential.reveal());

        if (isWrongKey(outcome)) {
            // One read past the ADR 0028 cache, exactly as SmsGateway does it:
            // either the cached copy aged out, or the token was rotated in
            // Telegram's own console and never written to the secret manager, and
            // only a fresh read tells the two apart. Once, never in a loop.
            log.warn("Telegram Gateway rejected the cached credential; refreshing once");
            outcome = dispatch(operation, secrets.resolveFresh(reference).reveal());
        }
        return outcome;
    }

    private ProviderOutcome dispatch(TelegramGatewayVerificationOperation operation, String token) {
        ProviderCall call =
                new ProviderCall(baseUrl, token, operation.challengeId().toString(), DEFAULT_TIMEOUT);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("phone_number", operation.destination());
        body.put("code", operation.code());
        body.put("code_length", operation.code().length());

        return http.post(call, SEND_PATH, Map.of("Authorization", "Bearer " + token), body, this::classifySuccess);
    }

    /**
     * The 2xx branch. Every non-2xx status is already classified generically by
     * {@code ProviderExceptionClassifier} before this is ever called — 400 as
     * {@code PROVIDER_REJECTED} (an invalid number, or one Gateway cannot reach —
     * exactly ADR 0063's "Gateway refusal"), 401/403 as {@code PROVIDER_AUTHENTICATION},
     * 429 as retryable — so there is no provider-specific error vocabulary to
     * hand-classify here the way {@code SmsGateCode} does for the SMS side.
     */
    @SuppressWarnings("unchecked")
    private ProviderOutcome classifySuccess(Map<String, Object> parsed) {
        if (!Boolean.TRUE.equals(parsed.get("ok"))) {
            // Documented as always a non-2xx failure, but read defensively: a 200
            // with ok:false would otherwise be believed as a successful delivery.
            Object error = parsed.get("error");
            return ProviderOutcome.rejected(
                    "TELEGRAM_GATEWAY_REJECTED", error == null ? "no error given" : String.valueOf(error));
        }

        Object resultObject = parsed.get("result");
        if (!(resultObject instanceof Map<?, ?> resultMap)) {
            return ProviderOutcome.uncertain(
                    "TELEGRAM_GATEWAY_NO_RESULT", "The Gateway reported success without a result");
        }
        Map<String, Object> result = (Map<String, Object>) resultMap;

        String requestId = text(result.get("request_id"));
        if (requestId == null || requestId.isBlank()) {
            // Success with nothing to correlate a later status check against —
            // the same "believed rather than confirmed" refusal
            // VasSmsGatewayAdapter gives a send that answers 0 for an id.
            return ProviderOutcome.uncertain(
                    "TELEGRAM_GATEWAY_NO_REQUEST_ID", "The Gateway reported success without a request id");
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put(REQUEST_ID_KEY, requestId);
        // Named for what it is before conversion -- the provider's own
        // fractional-USD wire value -- and never held as a "cost"-shaped
        // double anywhere a reader (or repo_hygiene's own money-field check)
        // could mistake it for money at rest. Money is integer minor units
        // from the very next line on.
        Double gatewayReportedUsd = doubleOrNull(result.get("request_cost"));
        if (gatewayReportedUsd != null) {
            normalized.put(COST_MINOR_KEY, String.valueOf(Math.round(gatewayReportedUsd * 100)));
            normalized.put(COST_CURRENCY_KEY, COST_CURRENCY);
        }
        return ProviderOutcome.success(normalized, requestId);
    }

    private SecretReference requireConfigured() {
        if (tokenReference == null) {
            throw new IllegalStateException(
                    "TelegramGatewayClient was called without a configured token; callers must check isConfigured() first");
        }
        return tokenReference;
    }

    /** {@code SmsGateway#isWrongKey}'s own test, restated: the provider says the credential is wrong. */
    private static boolean isWrongKey(ProviderOutcome outcome) {
        return outcome.status() == ProviderOutcome.Status.REJECTED
                && "PROVIDER_AUTHENTICATION".equals(outcome.errorCode());
    }

    private static @Nullable String text(@Nullable Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static @Nullable Double doubleOrNull(@Nullable Object value) {
        return switch (value) {
            case Number number -> number.doubleValue();
            case null, default -> null;
        };
    }
}
