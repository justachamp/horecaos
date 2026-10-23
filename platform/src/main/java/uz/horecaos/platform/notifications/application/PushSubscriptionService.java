package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.customers.api.ConsentDirectory;
import uz.horecaos.platform.customers.api.ConsentRecorder;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.notifications.api.PushEndpointPolicy;
import uz.horecaos.platform.notifications.api.WebPushKeys;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcPushSubscriptionStore;

/**
 * A customer's Web Push subscriptions: opt in, opt out, and what a send needs (ADR 0182, D14).
 *
 * <p><strong>The opt-in is the consent.</strong> A subscription exists only because the customer tapped
 * an explicit prompt in their own storefront and then accepted the browser's own permission dialog, so
 * the grant of {@code MARKETING_PROMOTIONS} on the {@code PUSH} channel is recorded in the same
 * transaction as the subscription (ADR 0015's append-only store). Turning push off withdraws it, and a
 * customer who withdrew another way (the consent screen) has an ended subscription the next time a send
 * looks. There is no state in which the platform holds a subscription the customer did not ask for.
 *
 * <p><strong>One subscription per customer per brand.</strong> A new one replaces the old one, and a
 * browser that moves to another customer (a shared handset, a sign-out and a sign-in) leaves the first
 * customer at once, so a promotion for one person is never shown on another person's screen. ADR 0182
 * records the single subscription as an accepted trade-off.
 *
 * <p><strong>The URL is a capability that names a device.</strong> It, the browser's public key and its
 * auth secret are one ADR 0029 value, bound to the row; they are read for the length of one send and are
 * in no log, event, URL or error. The service never returns them to the storefront, which already has
 * them.
 */
@Service
public class PushSubscriptionService {

    /** The consent purpose a push campaign is sent under (the shipped {@code MARKETING_PROMOTIONS}). */
    public static final String CONSENT_PURPOSE = "MARKETING_PROMOTIONS";

    /** The consent channel: the notification channel's own spelling, as the eligibility gate reads it. */
    public static final String CONSENT_CHANNEL = "PUSH";

    static final String POLICY_VERSION = "PUSH_OPT_IN_V1";
    static final String EVIDENCE_REFERENCE = "WEB_PUSH_PROMPT";
    private static final String SOURCE = "STOREFRONT";

    /** Recorded on every reveal, so a delivery is distinguishable from an export. */
    static final String REVEAL_PURPOSE = "NOTIFICATION_DELIVERY";

    private static final String SUBSCRIPTION_TABLE = "notifications.push_subscriptions";
    private static final String SECRET_COLUMN = "secret_ciphertext";
    private static final String ENDPOINT_DOMAIN = "notifications.push.endpoint";

    private static final Base64.Decoder URL = Base64.getUrlDecoder();

    private final JdbcPushSubscriptionStore subscriptions;
    private final JdbcNotificationStore notifications;
    private final FieldProtection protection;
    private final PushEndpointPolicy endpoints;
    private final ConsentDirectory consentReader;
    private final ConsentRecorder consentRecorder;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PushSubscriptionService(
            JdbcPushSubscriptionStore subscriptions,
            JdbcNotificationStore notifications,
            FieldProtection protection,
            PushEndpointPolicy endpoints,
            ConsentDirectory consentReader,
            ConsentRecorder consentRecorder,
            ObjectMapper objectMapper,
            Clock clock) {
        this.subscriptions = subscriptions;
        this.notifications = notifications;
        this.protection = protection;
        this.endpoints = endpoints;
        this.consentReader = consentReader;
        this.consentRecorder = consentRecorder;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** What a browser hands over when it subscribes. Printing it prints nothing. */
    public record Subscribe(
            String endpoint,
            String p256dh,
            String auth,
            @Nullable Instant expirationTime) {

        @Override
        public String toString() {
            return "Subscribe[<redacted>]";
        }
    }

    /** What a send needs, for the length of one send. */
    public record Revealed(String endpoint, String p256dh, String auth) {

        @Override
        public String toString() {
            return "Revealed[<redacted>]";
        }
    }

    /** Why a subscription was refused, as a bounded code a storefront can show a sentence for. */
    public static final class Refused extends RuntimeException {

        private final String code;

        Refused(String code) {
            super(code);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    /**
     * Records the subscription and the consent that goes with it, replacing what the customer and the
     * browser had before.
     *
     * @throws Refused when the browser's URL or keys are not ones the platform will send to
     */
    @Transactional
    public UUID subscribe(UUID tenantId, UUID brandId, UUID customerAccountId, Subscribe request) {
        Optional<String> refusal = endpoints.refusal(request.endpoint());
        if (refusal.isPresent()) {
            throw new Refused(refusal.get());
        }
        byte[] p256dh = decode(request.p256dh());
        byte[] auth = decode(request.auth());
        if (p256dh == null || auth == null || auth.length != 16 || !WebPushKeys.isValidPublicKey(p256dh)) {
            throw new Refused("PUSH_KEYS_INVALID");
        }
        Instant now = clock.instant();
        if (request.expirationTime() != null && !request.expirationTime().isAfter(now)) {
            throw new Refused("PUSH_SUBSCRIPTION_EXPIRED");
        }

        UUID id = Ids.newId();
        String secret = protection
                .protect(
                        tenantId,
                        DataClass.PERSONAL,
                        new RecordRef(SUBSCRIPTION_TABLE, SECRET_COLUMN, id),
                        json(request))
                .serialize();
        String hash = protection.lookupHash(tenantId, ENDPOINT_DOMAIN, request.endpoint());

        // The old rows end first, in this transaction, so the unique indexes see one live row at the end.
        List<UUID> ended = subscriptions.endLiveFor(
                tenantId, brandId, customerAccountId, hash, JdbcPushSubscriptionStore.ENDED_REPLACED, now);
        ended.forEach(old -> notifications.retirePushEndpoint(tenantId, old, now));

        subscriptions.insert(id, tenantId, brandId, customerAccountId, secret, hash, request.expirationTime(), now);
        notifications.ensurePushEndpoint(tenantId, customerAccountId, id, now);

        if (!consentGranted(tenantId, brandId, customerAccountId)) {
            consentRecorder.recordGrant(
                    tenantId,
                    customerAccountId,
                    brandId,
                    CONSENT_PURPOSE,
                    CONSENT_CHANNEL,
                    POLICY_VERSION,
                    SOURCE,
                    EVIDENCE_REFERENCE);
        }
        return id;
    }

    /**
     * Turns push off for the customer at this brand: the subscription ends, and so does the consent.
     * Idempotent, because turning off what is off is the state that was asked for.
     */
    @Transactional
    public void unsubscribe(UUID tenantId, UUID brandId, UUID customerAccountId) {
        Instant now = clock.instant();
        subscriptions
                .endLiveOf(tenantId, brandId, customerAccountId, JdbcPushSubscriptionStore.ENDED_BY_CUSTOMER, now)
                .ifPresent(ended -> notifications.retirePushEndpoint(tenantId, ended, now));
        if (consentGranted(tenantId, brandId, customerAccountId)) {
            consentRecorder.recordWithdrawal(
                    tenantId,
                    customerAccountId,
                    brandId,
                    CONSENT_PURPOSE,
                    CONSENT_CHANNEL,
                    POLICY_VERSION,
                    SOURCE,
                    null);
        }
    }

    /** Whether the customer has a live subscription at this brand right now. */
    @Transactional(readOnly = true)
    public boolean subscribed(UUID tenantId, UUID brandId, UUID customerAccountId) {
        return subscriptions
                .liveOf(tenantId, brandId, customerAccountId, clock.instant())
                .isPresent();
    }

    /**
     * The URL and keys of a live subscription, revealed for one send. Empty once it has ended, which a
     * dispatch treats as a recipient that cannot be resolved (the customer turned push off between the
     * eligibility check and the send).
     */
    @Transactional(readOnly = true)
    public Optional<Revealed> reveal(UUID tenantId, UUID subscriptionId) {
        return subscriptions.secretOf(tenantId, subscriptionId).map(stored -> {
            String plaintext = protection.reveal(
                    tenantId,
                    ProtectedValue.deserialize(stored),
                    new RecordRef(SUBSCRIPTION_TABLE, SECRET_COLUMN, subscriptionId),
                    REVEAL_PURPOSE);
            JsonNode node = objectMapper.readTree(plaintext);
            return new Revealed(
                    node.path("endpoint").asString(),
                    node.path("p256dh").asString(),
                    node.path("auth").asString());
        });
    }

    /** The push service said this subscription is over. Ends it and retires its endpoint; idempotent. */
    @Transactional
    public void markGone(UUID tenantId, UUID subscriptionId) {
        Instant now = clock.instant();
        subscriptions.end(tenantId, subscriptionId, "EXPIRED", JdbcPushSubscriptionStore.ENDED_GONE, now);
        notifications.retirePushEndpoint(tenantId, subscriptionId, now);
    }

    /** A push service took a message for this subscription. */
    @Transactional
    public void markAccepted(UUID tenantId, UUID subscriptionId) {
        subscriptions.touchAccepted(tenantId, subscriptionId, clock.instant());
    }

    private boolean consentGranted(UUID tenantId, UUID brandId, UUID customerAccountId) {
        return consentReader
                .consentFor(tenantId, customerAccountId, brandId, CONSENT_PURPOSE, CONSENT_CHANNEL)
                .map(ConsentDirectory.ConsentState::granted)
                .orElse(false);
    }

    private String json(Subscribe request) {
        return objectMapper.writeValueAsString(
                java.util.Map.of("endpoint", request.endpoint(), "p256dh", request.p256dh(), "auth", request.auth()));
    }

    private static byte @Nullable [] decode(String base64Url) {
        try {
            return URL.decode(base64Url);
        } catch (IllegalArgumentException notBase64) {
            return null;
        }
    }
}
