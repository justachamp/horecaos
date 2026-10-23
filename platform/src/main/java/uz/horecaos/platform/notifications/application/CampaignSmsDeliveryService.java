package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.marketing.api.CampaignMessagePort.CourierMessage;
import uz.horecaos.platform.marketing.api.CampaignMessagePort.CourierTemplate;
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

    /**
     * What a courier broadcast's messages name as their subject (ADR 0182): the broadcast, as a campaign
     * message names its campaign.
     */
    static final String COURIER_SUBJECT_TYPE = "CourierBroadcast";

    private final JdbcNotificationStore notifications;
    private final NotificationTemplateService templates;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration messageExpiry;
    private final Duration courierMessageExpiry;

    /** An SMS delivery whose courier messages lapse after the default six hours. */
    public CampaignSmsDeliveryService(
            JdbcNotificationStore notifications,
            NotificationTemplateService templates,
            ObjectMapper objectMapper,
            Clock clock,
            Duration messageExpiry) {
        this(notifications, templates, objectMapper, clock, messageExpiry, Duration.ofHours(6));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public CampaignSmsDeliveryService(
            JdbcNotificationStore notifications,
            NotificationTemplateService templates,
            ObjectMapper objectMapper,
            Clock clock,
            // A promotion read a day late is noise, and the deferral an SMS campaign
            // may sit under (quiet hours) is at most a night, so a day from the
            // moment it becomes eligible is generous without holding a hopelessly
            // late send open.
            @Value("${horecaos.notifications.sms.campaign-message-expiry:P1D}") Duration messageExpiry,
            // A shift change read the next morning is worse than silence: a courier acting on it is
            // acting on yesterday. Six hours is generous for an instruction that is about today.
            @Value("${horecaos.notifications.sms.courier-message-expiry:PT6H}") Duration courierMessageExpiry) {
        this.notifications = notifications;
        this.templates = templates;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.messageExpiry = messageExpiry;
        this.courierMessageExpiry = courierMessageExpiry;
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

    /**
     * An operational SMS to one courier (ADR 0182, D15), as an ordinary ADR 0020 intent of the class
     * {@code COURIER_DISPATCH}.
     *
     * <p>The recipient is the courier's endpoint, which holds the courier's id and no number; the number
     * is read from the identity provider when the message is sent. Nothing about consent is checked
     * because a courier has no marketing consent and this is not marketing, and nothing about the
     * wording is relaxed: the gateway's approval (ADR 0091) and its purpose gate (ADR 0146) apply as to
     * any SMS.
     *
     * <p>A wording that is not a courier wording is refused here as well as at the broadcast: a
     * marketing template named by a broadcast would otherwise go to a courier with no consent behind it
     * and no class to say what it was.
     *
     * @return null when the template is not a courier wording in force for the brand
     */
    @Override
    @Transactional
    public @Nullable UUID enqueueCourierMessage(CourierMessage message) {
        boolean courierWording = templates.courierTemplates(message.tenantId(), message.brandId()).stream()
                .anyMatch(template -> template.templateKey().equals(message.templateKey()));
        if (!courierWording) {
            return null;
        }
        Instant now = clock.instant();
        UUID endpointId = notifications.ensureCourierEndpoint(message.tenantId(), message.courierId(), now);
        Instant expiresAt = message.expiresAt() != null
                ? message.expiresAt()
                : message.scheduledAt().plus(courierMessageExpiry);

        NewNotification intent = new NewNotification(
                UUID.randomUUID(),
                message.tenantId(),
                message.brandId(),
                null,
                NotificationClass.COURIER_DISPATCH.name(),
                NotificationChannel.SMS.name(),
                message.templateKey(),
                COURIER_SUBJECT_TYPE,
                message.broadcastId(),
                null,
                null,
                message.idempotencyKey(),
                objectMapper.writeValueAsString(message.variables()),
                message.scheduledAt(),
                expiresAt,
                now,
                endpointId);

        if (notifications.createIntent(intent)) {
            return intent.notificationId();
        }
        return notifications
                .findByIdempotencyKey(message.tenantId(), message.idempotencyKey())
                .map(NotificationRow::id)
                .orElse(null);
    }

    @Override
    public List<CourierTemplate> courierTemplates(UUID tenantId, UUID brandId) {
        return templates.courierTemplates(tenantId, brandId);
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

    /** SMS gateways send delivery receipts; whether this brand's gateway has an enabled source is the router's question. */
    @Override
    public boolean providersReportDelivery() {
        return true;
    }
}
