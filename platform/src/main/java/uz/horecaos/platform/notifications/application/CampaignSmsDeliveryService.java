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
 * The SMS channel of {@code CampaignMessagePort} (ADR 0146 Decision 8).
 *
 * <p>The same shape as the Telegram delivery and for the same reason: a campaign
 * message is an ordinary ADR 0020 {@code MARKETING} intent with
 * {@code recipient_account_id} set at creation, because a campaign has no order to
 * resolve its account from, and everything after that — consent at the message,
 * quiet hours, the template's approval state (ADR 0091), the endpoint, the attempt,
 * the gateway — is the machinery transactional SMS already rides. There is no second
 * delivery path and no second opinion about consent: marketing SMS to a number
 * requires, unchanged, consent and no suppression, and no gateway account relaxes
 * either.
 *
 * <p>Whether a brand may send one at all is not decided here. It is the router's
 * scoped {@code wiring} answer, which reads the brand's own binding and whether that
 * account has been cleared to carry marketing; this class is reached only when it
 * said yes. Defended anyway: the gateway refuses a purpose its account is not
 * cleared for at send time as well, so a message that slipped past is refused with
 * the same stable code rather than sent.
 *
 * <p>No pacing. {@code CampaignPacer} paces a bot's per-chat rate limit, a concern of
 * that provider, and an SMS gateway has its own limits (VAS: fifty an hour per number
 * per partner, which a frequency cap of three a week cannot approach).
 */
@Component
public class CampaignSmsDeliveryService implements CampaignChannelDelivery {

    static final String SMS_CHANNEL = "SMS";

    private final JdbcNotificationStore notifications;
    private final NotificationTemplateService templates;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration messageExpiry;

    public CampaignSmsDeliveryService(
            JdbcNotificationStore notifications,
            NotificationTemplateService templates,
            ObjectMapper objectMapper,
            Clock clock,
            // A promotion read a day late is noise, and the deferral an SMS campaign
            // may sit under (quiet hours) is at most a night, so a day from the
            // moment it becomes eligible is generous without holding a hopelessly
            // late send open.
            @Value("${horecaos.notifications.sms.campaign-message-expiry:P1D}") Duration messageExpiry) {
        this.notifications = notifications;
        this.templates = templates;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.messageExpiry = messageExpiry;
    }

    @Override
    public String channel() {
        return SMS_CHANNEL;
    }

    @Override
    @Transactional
    public @Nullable UUID enqueue(MarketingMessage message) {
        if (!SMS_CHANNEL.equals(message.channel())) {
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
                NotificationChannel.SMS.name(),
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
        // A replayed batch's key already has a row: the same customer, the same
        // campaign, the same message. Found rather than recreated.
        return notifications
                .findByIdempotencyKey(message.tenantId(), message.idempotencyKey())
                .map(NotificationRow::id)
                .orElse(null);
    }

    @Override
    public Map<String, String> templateBodies(UUID tenantId, UUID brandId, String templateKey) {
        Map<String, String> bodies = new LinkedHashMap<>();
        for (MessageLocale locale : MessageLocale.required()) {
            var resolution = templates.resolve(tenantId, brandId, templateKey, NotificationChannel.SMS, locale);
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
}
