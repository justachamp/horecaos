package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.marketing.api.CampaignMessagePort.MarketingMessage;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NewNotification;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NotificationRow;

/**
 * The PUSH channel of {@code CampaignMessagePort} (ADR 0182, D14): the fourth {@link CampaignChannelDelivery},
 * after Telegram's, SMS's and email's, and the same shape for the same reason.
 *
 * <p>A campaign message becomes an ordinary ADR 0020 {@code MARKETING} intent with
 * {@code recipient_account_id} set at creation. Everything after that is machinery the other channels
 * already ride: consent at the message (read under the channel {@code PUSH}, so a customer who agreed to
 * SMS has not agreed to this), quiet hours, the customer's own browser subscription resolved as the
 * recipient endpoint, the attempt, and the platform's Web Push at the end. There is no second delivery
 * path and no second opinion about consent.
 *
 * <p>Whether a brand may send one at all is not decided here. It is the router's scoped {@code wiring}
 * answer, which for push is «the platform's VAPID identity is configured» (the key pair and a contact);
 * this class is reached only when it said yes, and the gateway refuses again at send time if it is not.
 *
 * <p>No pacing: the browser vendor's push service is the queue. A push that sits for a device that is
 * off lasts as long as the message does and then lapses, so the expiry is short: a promotion that
 * arrives the next afternoon is noise.
 *
 * <p>The channel reports a read and not a delivery: a customer's tap on the notification is the only
 * signal Web Push offers, and the push service reports nothing further.
 */
@Component
public class CampaignPushDeliveryService implements CampaignChannelDelivery {

    static final String PUSH_CHANNEL = "PUSH";

    private final JdbcNotificationStore notifications;
    private final NotificationTemplateService templates;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration messageExpiry;

    public CampaignPushDeliveryService(
            JdbcNotificationStore notifications,
            NotificationTemplateService templates,
            ObjectMapper objectMapper,
            Clock clock,
            // A promotion shown half a day late is noise, and the deferral a campaign may sit under
            // (quiet hours) is at most a night, so twelve hours from the moment it becomes eligible is
            // generous without holding a hopelessly late send open at the browser's push service.
            @Value("${horecaos.notifications.push.campaign-message-expiry:PT12H}") Duration messageExpiry) {
        this.notifications = notifications;
        this.templates = templates;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.messageExpiry = messageExpiry;
    }

    @Override
    public String channel() {
        return PUSH_CHANNEL;
    }

    @Override
    @Transactional
    public @Nullable UUID enqueue(MarketingMessage message) {
        if (!PUSH_CHANNEL.equals(message.channel())) {
            return null;
        }

        Instant now = clock.instant();
        Instant expiresAt = message.expiresAt() != null
                ? message.expiresAt()
                : message.scheduledAt().plus(messageExpiry);

        NewNotification intent = new NewNotification(
                UUID.randomUUID(),
                message.tenantId(),
                message.brandId(),
                null,
                NotificationClass.MARKETING.name(),
                NotificationChannel.PUSH.name(),
                message.templateKey(),
                CampaignTelegramDeliveryService.CAMPAIGN_SUBJECT_TYPE,
                message.campaignId(),
                message.customerAccountId(),
                null,
                message.idempotencyKey(),
                objectMapper.writeValueAsString(message.variables()),
                message.scheduledAt(),
                expiresAt,
                now,
                null);

        if (notifications.createIntent(intent)) {
            return intent.notificationId();
        }
        // A replayed batch's key already has a row: the same customer, the same campaign, the same
        // message. Found rather than recreated.
        return notifications
                .findByIdempotencyKey(message.tenantId(), message.idempotencyKey())
                .map(NotificationRow::id)
                .orElse(null);
    }

    @Override
    public Map<String, String> templateBodies(UUID tenantId, UUID brandId, String templateKey) {
        Map<String, String> bodies = new LinkedHashMap<>();
        // The languages this brand serves that the platform can send in (ADR 0149), exactly as
        // Telegram reads them: one the brand does not serve has no body to show, and one it does but
        // this template lacks is left out rather than filled with another language's text.
        for (MessageLocale locale : templates.requiredLocales(tenantId, brandId)) {
            var resolution = templates.resolveExact(
                    tenantId, brandId, templateKey, NotificationChannel.PUSH, locale, null, null);
            if (resolution.isFound()) {
                bodies.put(
                        locale.tag(),
                        Optional.ofNullable(resolution.version()).orElseThrow().bodyTemplate());
            }
        }
        return Map.copyOf(bodies);
    }

    @Override
    public OptionalDouble ratePerSecond() {
        return OptionalDouble.empty();
    }

    /** The push service takes the message and says no more: delivery is never reported. */
    @Override
    public boolean providersReportDelivery() {
        return false;
    }

    /** A tap on the notification is reported by the storefront's service worker (ADR 0182, D14). */
    @Override
    public boolean reportsRead() {
        return true;
    }
}
