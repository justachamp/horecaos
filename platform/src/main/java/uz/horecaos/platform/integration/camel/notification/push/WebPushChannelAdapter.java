package uz.horecaos.platform.integration.camel.notification.push;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.interfaces.ECPrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.secrets.SecretReference;
import uz.horecaos.platform.iam.api.secrets.SecretResolver;
import uz.horecaos.platform.iam.api.secrets.SecretResolver.SecretNotFoundException;
import uz.horecaos.platform.integration.api.delivery.DeliveryPartner.ProviderCall;
import uz.horecaos.platform.integration.api.provider.ProviderOutcome;
import uz.horecaos.platform.integration.camel.notification.AccountContext;
import uz.horecaos.platform.integration.camel.notification.AccountReadiness;
import uz.horecaos.platform.integration.camel.notification.NotificationChannelAdapter;
import uz.horecaos.platform.notifications.api.NotificationDispatch;
import uz.horecaos.platform.notifications.api.NotificationDispatch.PushEnvelope;
import uz.horecaos.platform.notifications.api.PushEndpointPolicy;

/**
 * A tenant's campaign push, sent to the customer's browser through the browser vendor's own push
 * service (ADR 0182, D14; Web Push is RFC 8030, its message encryption RFC 8291, its sender identity
 * VAPID, RFC 8292).
 *
 * <p><strong>Platform-owned.</strong> As the mail relay is, the platform's VAPID identity is the
 * deployment's, one for every tenant, so this adapter has no ADR 0026 installation or binding. A
 * tenant's choice is whether a customer opted in, never which key signs the request. The private key is
 * an ADR 0028 reference resolved for the length of a send; the public key is not a secret and is the
 * one the storefront subscribes under.
 *
 * <p><strong>Marketing only</strong>, for the reason the email adapter is: the opt-in, the cap on
 * how often and the way out were decided for promotions, and an order status by push is a later
 * decision.
 *
 * <p><strong>Honest about what a push service says.</strong> {@code 201} is «the push service has
 * the message», which is {@code ACCEPTED} and no more: Web Push reports no delivery and no display.
 * {@code 404} and {@code 410} say the subscription is over (the customer cleared their browser data, or
 * the browser retired it), which is a fact about a device and not a withdrawal of consent, so it is a
 * rejection with {@link #SUBSCRIPTION_GONE} and never a hard bounce. A rate limit is retried when the
 * service says to. A request that went out and was not answered is uncertain and never repeated,
 * because a push has no idempotency key a service will report; a connection that never opened is not
 * uncertain, and is retried.
 *
 * <p>The push service URL, the keys, the title and the body never reach a log or an outcome's detail.
 */
@Component
public class WebPushChannelAdapter implements NotificationChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(WebPushChannelAdapter.class);

    /** The provider type recorded on the attempt, though no installation exists. */
    public static final String PROVIDER_TYPE = "WEB_PUSH";

    /** The channel this adapter sends on. */
    public static final String CHANNEL = "PUSH";

    /** The rejection code that says the subscription is over: the dispatch retires it. */
    public static final String SUBSCRIPTION_GONE = "PUSH_SUBSCRIPTION_GONE";

    /** What the attempt's provider status says: a push service took it. Not a delivery, not a display. */
    static final String PROVIDER_STATUS_ACCEPTED = "PUSH_ACCEPTED";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration MAX_RETRY_AFTER = Duration.ofHours(1);

    /** A title and a body are what a notification shows; beyond this a browser truncates them anyway. */
    static final int MAX_TITLE = 100;

    static final int MAX_BODY = 400;

    private static final Base64.Decoder URL = Base64.getUrlDecoder();

    private final SecretResolver secrets;
    private final PushEndpointPolicy endpoints;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final HttpClient client;
    private final String publicKey;
    private final String privateKeyReference;
    private final String subject;

    public WebPushChannelAdapter(
            SecretResolver secrets,
            PushEndpointPolicy endpoints,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${horecaos.push.vapid.public-key:}") String publicKey,
            @Value("${horecaos.push.vapid.private-key-reference:}") String privateKeyReference,
            @Value("${horecaos.push.vapid.subject:}") String subject) {
        this.secrets = secrets;
        this.endpoints = endpoints;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.publicKey = publicKey.strip();
        this.privateKeyReference = privateKeyReference.strip();
        this.subject = subject.strip();
        if (!this.privateKeyReference.isEmpty()) {
            // Parsed now so a malformed reference stops startup, not the first campaign.
            SecretReference.parse(this.privateKeyReference);
        }
        this.client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                // A push service redirecting a POST is misconfigured or hostile, and a redirect is the
                // classic way past an allowlist of hosts.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public String channel() {
        return CHANNEL;
    }

    @Override
    public boolean platformOwned() {
        return true;
    }

    @Override
    public AccountReadiness describeAccount(AccountContext account) {
        List<String> missing = new ArrayList<>();
        if (!validPublicKey()) {
            missing.add("horecaos.push.vapid.public-key");
        }
        if (privateKeyReference.isEmpty()) {
            missing.add("horecaos.push.vapid.private-key-reference");
        }
        if (!(subject.startsWith("mailto:") || subject.startsWith("https://"))) {
            missing.add("horecaos.push.vapid.subject");
        }
        return AccountReadiness.missing(missing);
    }

    @Override
    public boolean permits(String purpose, AccountContext account) {
        return NotificationDispatch.PURPOSE_MARKETING.equals(purpose);
    }

    @Override
    public ProviderOutcome send(NotificationDispatch dispatch, ProviderCall call) {
        if (!NotificationDispatch.PURPOSE_MARKETING.equals(dispatch.purpose())) {
            return ProviderOutcome.rejected(
                    "PUSH_PURPOSE_NOT_PERMITTED", "The platform's web push carries marketing messages only");
        }
        PushEnvelope envelope = dispatch.push();
        if (envelope == null) {
            // A campaign push without its click link would be a message that can never be counted read;
            // it is also the sign of a dispatch built by something that is not the pipeline.
            return ProviderOutcome.rejected("PUSH_ENVELOPE_MISSING", "A campaign push carries its click link");
        }
        String title = dispatch.subject();
        if (title == null || title.isBlank()) {
            return ProviderOutcome.rejected("PUSH_TITLE_MISSING", "A push notification needs a title");
        }
        Optional<String> refused = endpoints.refusal(dispatch.recipientValue());
        if (refused.isPresent()) {
            // Checked again here, not only when the subscription arrived: a stored URL must not outlive
            // a narrowing of the list. A subscription to such an address is over.
            return ProviderOutcome.rejected(
                    SUBSCRIPTION_GONE, "The push service address is not one this platform sends to");
        }

        ECPrivateKey key;
        try {
            key = privateKey();
        } catch (SecretNotFoundException missing) {
            log.warn("The VAPID private key reference resolves to nothing in the secrets manager");
            return ProviderOutcome.retryable("PUSH_SECRET_MISSING", "The VAPID key is not available", null);
        } catch (IllegalArgumentException | GeneralSecurityException malformed) {
            log.warn("The VAPID private key in the secrets manager is not a P-256 key");
            return ProviderOutcome.retryable("PUSH_SECRET_INVALID", "The VAPID key is not usable", null);
        }

        byte[] body;
        try {
            byte[] payload = payload(dispatch, title, envelope);
            body = WebPushEncryption.encrypt(payload, URL.decode(envelope.p256dh()), URL.decode(envelope.auth()));
        } catch (IllegalArgumentException unusable) {
            // A subscription whose keys are not keys, or a payload that cannot fit, will never succeed.
            return ProviderOutcome.rejected(
                    "PUSH_PAYLOAD_UNUSABLE", "The push could not be encrypted to this subscription");
        }

        URI uri = URI.create(dispatch.recipientValue());
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", VapidAuthorization.header(uri, subject, publicKey, key, clock.instant()))
                .header("Content-Encoding", "aes128gcm")
                .header("Content-Type", "application/octet-stream")
                .header("TTL", Long.toString(envelope.ttlSeconds()))
                // Promotions are low urgency: a device on battery saver is not woken for one.
                .header("Urgency", "low")
                // A retry of this message replaces the one still waiting at the push service.
                .header("Topic", envelope.topic())
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try {
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return classify(response.statusCode(), response.headers().firstValue("Retry-After"), envelope.topic());
        } catch (HttpConnectTimeoutException | ConnectException | UnresolvedAddressException notReached) {
            // Nothing was sent: safe to try again.
            return ProviderOutcome.retryable("PUSH_SERVICE_UNREACHABLE", "The push service could not be reached", null);
        } catch (HttpTimeoutException | java.net.SocketException noAnswer) {
            // The message was on its way and nothing came back.
            return ProviderOutcome.uncertain(
                    "PUSH_NO_ANSWER", "The push service did not answer after the message was sent");
        } catch (IOException failure) {
            return ProviderOutcome.uncertain(
                    "PUSH_NO_ANSWER", "The push service connection failed after the message was sent");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return ProviderOutcome.uncertain("PUSH_INTERRUPTED", "The send was interrupted");
        }
    }

    /**
     * What a push service's status means (RFC 8030 section 5, and the browsers' own documentation).
     * Only the status is read: the body of an error is the service's, and may repeat the request.
     */
    static ProviderOutcome classify(int status, Optional<String> retryAfter, String topic) {
        if (status == 201 || status == 200 || status == 202) {
            return ProviderOutcome.success(
                    Map.of(PROVIDER_STATUS_KEY, PROVIDER_STATUS_ACCEPTED, NORMALIZED_STATUS_KEY, "ACCEPTED"), topic);
        }
        if (status == 404 || status == 410) {
            return ProviderOutcome.rejected(SUBSCRIPTION_GONE, "The push service says the subscription is over");
        }
        if (status == 429) {
            return ProviderOutcome.retryable(
                    "PUSH_RATE_LIMITED", "The push service asked to slow down", retryDelay(retryAfter));
        }
        if (status >= 500) {
            return ProviderOutcome.retryable("PUSH_SERVICE_ERROR", "The push service failed", retryDelay(retryAfter));
        }
        if (status == 413) {
            return ProviderOutcome.rejected(
                    "PUSH_PAYLOAD_TOO_LARGE", "The push service refused the size of the message");
        }
        if (status == 401 || status == 403) {
            // The push service did not accept the platform's VAPID identity, or the subscription was made
            // under another key. After a key rotation every earlier subscription answers this until its
            // customer subscribes again; ADR 0182 records that, and the runbook says so.
            return ProviderOutcome.rejected(
                    "PUSH_VAPID_REJECTED", "The push service refused the platform's VAPID identity");
        }
        if (status >= 300 && status < 400) {
            return ProviderOutcome.rejected(
                    "PUSH_REDIRECT_REFUSED", "The push service redirected, which is not followed");
        }
        return ProviderOutcome.rejected("PUSH_REQUEST_REFUSED", "The push service refused the request");
    }

    private static @Nullable Duration retryDelay(Optional<String> retryAfter) {
        if (retryAfter.isEmpty()) {
            return null;
        }
        try {
            Duration asked = Duration.ofSeconds(Long.parseLong(retryAfter.get().strip()));
            return asked.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : asked;
        } catch (NumberFormatException httpDate) {
            // An HTTP date is legal and rare; the pipeline's own backoff is a fine answer to it.
            return null;
        }
    }

    /**
     * A push has no query for what a service holds, so a message whose fate is unknown stays unknown.
     * Answering «no record» would license a second send, which is the duplicate this adapter exists
     * not to produce.
     */
    @Override
    public ProviderOutcome queryStatus(String providerIdempotencyKey, ProviderCall call) {
        return ProviderOutcome.uncertain("PUSH_NO_STATUS_QUERY", "Web Push offers no way to ask what a service holds");
    }

    // ----------------------------------------------------------------------- the payload

    /**
     * What the service worker receives: the words, the cover, where a tap goes and where a tap is
     * reported. JSON, encrypted before it leaves; the service worker reads exactly these keys.
     */
    private byte[] payload(NotificationDispatch dispatch, String title, PushEnvelope envelope) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("title", cut(title, MAX_TITLE));
        data.put("body", cut(dispatch.body(), MAX_BODY));
        if (envelope.imageUrl() != null) {
            data.put("image", envelope.imageUrl());
        }
        data.put("path", envelope.path());
        data.put("click", envelope.clickUrl());
        data.put("tag", envelope.topic());
        return objectMapper.writeValueAsString(data).getBytes(StandardCharsets.UTF_8);
    }

    /** Cut on a code point, never inside a surrogate pair, and marked. */
    static String cut(String text, int limit) {
        String stripped = text.strip();
        if (stripped.codePointCount(0, stripped.length()) <= limit) {
            return stripped;
        }
        int end = stripped.offsetByCodePoints(0, limit - 1);
        return stripped.substring(0, end) + "…";
    }

    // --------------------------------------------------------------------------- the key

    private boolean validPublicKey() {
        if (publicKey.isEmpty()) {
            return false;
        }
        try {
            return WebPushEncryption.isValidPublicKey(URL.decode(publicKey));
        } catch (IllegalArgumentException notBase64) {
            return false;
        }
    }

    private ECPrivateKey privateKey() throws GeneralSecurityException {
        String value =
                secrets.resolve(SecretReference.parse(privateKeyReference)).reveal();
        return WebPushEncryption.decodePrivateKey(URL.decode(value.strip()));
    }
}
