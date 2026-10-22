package uz.horecaos.platform.notifications.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.notifications.api.DispatchOutcome;
import uz.horecaos.platform.notifications.api.NotificationDispatch.PushEnvelope;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NotificationRow;

/**
 * The envelope of a campaign push (ADR 0182, D14): the browser's keys, the link the service worker
 * reports a tap to, the cover, and where a tap goes.
 *
 * <p>The click link points at the platform's own public origin, not at a storefront: the service worker
 * posts to it from whichever storefront the customer subscribed on, with no session, and the origin is
 * one deployment setting where a storefront's hostname is one per tenant.
 */
@Component
public class CampaignPushDispatches implements PushDispatches {

    /** The most a push service may be asked to hold a message: 28 days, the longest the major ones keep. */
    static final long MAX_TTL_SECONDS = 28L * 24 * 3600;

    /** The least that is worth asking for: a message that lapses in seconds is a message nobody gets. */
    static final long MIN_TTL_SECONDS = 60;

    private static final long DEFAULT_TTL_SECONDS = 24L * 3600;

    private final PushSubscriptionService subscriptions;
    private final PushClickService clicks;
    private final JdbcNotificationStore notifications;
    private final Clock clock;
    private final String publicApiOrigin;

    public CampaignPushDispatches(
            PushSubscriptionService subscriptions,
            PushClickService clicks,
            JdbcNotificationStore notifications,
            Clock clock,
            @Value("${horecaos.public-api-origin:http://localhost:8080}") String publicApiOrigin) {
        this.subscriptions = subscriptions;
        this.clicks = clicks;
        this.notifications = notifications;
        this.clock = clock;
        this.publicApiOrigin = publicApiOrigin.endsWith("/")
                ? publicApiOrigin.substring(0, publicApiOrigin.length() - 1)
                : publicApiOrigin;
    }

    @Override
    public Optional<Resolved> resolve(NotificationRow row, @Nullable UUID coverAssetId, @Nullable String path) {
        if (!NotificationChannel.PUSH.name().equals(row.channel()) || row.recipientEndpointId() == null) {
            return Optional.empty();
        }
        Optional<UUID> subscription = subscriptionOf(row);
        if (subscription.isEmpty()) {
            return Optional.empty();
        }
        Optional<PushSubscriptionService.Revealed> revealed = subscriptions.reveal(row.tenantId(), subscription.get());
        if (revealed.isEmpty()) {
            return Optional.empty();
        }
        long ttl = ttlSeconds(row.expiresAt());
        String token = clicks.issue(row.tenantId(), row.id(), Duration.ofSeconds(ttl));
        PushSubscriptionService.Revealed keys = revealed.get();
        PushEnvelope envelope = new PushEnvelope(
                keys.p256dh(),
                keys.auth(),
                publicApiOrigin + "/providers/push/clicks/" + token,
                coverAssetId == null
                        ? null
                        : publicApiOrigin + "/api/v1/storefront/tenants/" + row.tenantId() + "/media/" + coverAssetId
                                + "?variant=w800",
                path == null || path.isBlank() ? "/" : path,
                topic(row.id()),
                ttl);
        return Optional.of(new Resolved(keys.endpoint(), envelope));
    }

    @Override
    public void settled(NotificationRow row, DispatchOutcome outcome) {
        if (!NotificationChannel.PUSH.name().equals(row.channel()) || row.recipientEndpointId() == null) {
            return;
        }
        Optional<UUID> subscription = subscriptionOf(row);
        if (subscription.isEmpty()) {
            return;
        }
        switch (outcome.status()) {
            case ACCEPTED -> subscriptions.markAccepted(row.tenantId(), subscription.get());
            case REJECTED -> {
                if ("PUSH_SUBSCRIPTION_GONE".equals(outcome.errorCode())) {
                    // The push service says the browser has dropped the subscription (404 or 410): ending
                    // it here is what makes the next campaign skip this customer with NO_RECIPIENT_ENDPOINT
                    // instead of trying a dead address once per campaign, forever.
                    subscriptions.markGone(row.tenantId(), subscription.get());
                }
            }
            case RETRYABLE, UNCERTAIN -> {
                // Nothing is known about the subscription yet.
            }
        }
    }

    private Optional<UUID> subscriptionOf(NotificationRow row) {
        UUID endpointId = row.recipientEndpointId();
        if (endpointId == null) {
            return Optional.empty();
        }
        return notifications
                .endpoint(row.tenantId(), endpointId)
                .map(JdbcNotificationStore.EndpointRow::pushSubscriptionId);
    }

    /** How long the push service may hold this message: until the platform would no longer send it. */
    private long ttlSeconds(@Nullable Instant expiresAt) {
        if (expiresAt == null) {
            return DEFAULT_TTL_SECONDS;
        }
        long seconds = Duration.between(clock.instant(), expiresAt).toSeconds();
        return Math.max(MIN_TTL_SECONDS, Math.min(MAX_TTL_SECONDS, seconds));
    }

    /**
     * The push service's replacement key (RFC 8030 section 5.4): 22 URL-safe characters derived from the
     * notification, so a retry of the same message replaces the copy still waiting instead of showing
     * twice, and the same value is the notification's {@code tag}, so a copy already on screen is
     * replaced as well.
     */
    static String topic(UUID notificationId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(notificationId.toString().getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(digest, 16));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is part of every JVM", impossible);
        }
    }
}
