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
import uz.horecaos.platform.marketing.api.CampaignMessagePort;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NewNotification;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NotificationRow;

/**
 * {@link CampaignMessagePort}'s implementation, for the TELEGRAM channel only
 * (ADR 0044, ADR 0059 stage 4).
 *
 * <p>Riding the machinery waves 6 to 8 built rather than building a second
 * delivery path: a campaign message becomes an ordinary ADR 0020 {@code
 * MARKETING} intent, with {@code recipient_account_id} set at creation — the
 * one thing every other caller of {@link JdbcNotificationStore#createIntent}
 * leaves null, because every other caller resolves its account from an order
 * ({@code NotificationEligibilityService} does that resolution at eligibility).
 * A campaign has no order; it already knows exactly which account it means,
 * from the audience snapshot, so it says so up front. That one difference is
 * also what lets {@code NotificationEligibilityService} skip the order lookup
 * for this class of message — see that class's own doc comment.
 *
 * <p>{@link #enqueue}'s {@code scheduledAt} is not the caller's. ADR 0044
 * already deferred it once, to the next quiet-hours boundary when the batch
 * would otherwise land inside the closed window; this method defers it again,
 * later still if the bot's pacing budget is not yet free, via {@link
 * CampaignPacer}. The two compose as a {@code max}, never a replacement: quiet
 * hours can push a message past where pacing alone would have put it, and a
 * busy bot can push it past where quiet hours alone would have.
 */
@Component
public class CampaignTelegramDeliveryService implements CampaignMessagePort {

    /**
     * The one marketing-side channel name this adapter answers for. A literal
     * rather than an import of {@code marketing.domain.MarketingChannel}:
     * that enum is internal to the marketing module, and {@link
     * CampaignMessagePort} deliberately carries the channel as a string for
     * exactly this reason — see that interface's own doc comment on why
     * {@code notifications.domain.NotificationChannel} is not shared either.
     */
    static final String MESSAGING_APP_CHANNEL = "MESSAGING_APP";

    /**
     * What a campaign message's {@code subject_type} names on the ADR 0020
     * row. Package-visible so {@link NotificationEligibilityService} and
     * {@link CampaignBlockRateMonitor} recognise the same rows this class
     * creates without a third copy of the literal.
     */
    static final String CAMPAIGN_SUBJECT_TYPE = "MarketingCampaign";

    private final JdbcNotificationStore notifications;
    private final NotificationTemplateService templates;
    private final CampaignPacer pacer;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration messageExpiry;

    public CampaignTelegramDeliveryService(
            JdbcNotificationStore notifications,
            NotificationTemplateService templates,
            CampaignPacer pacer,
            ObjectMapper objectMapper,
            Clock clock,
            // Relative to each message's own paced scheduledAt, not to when the
            // batch was expanded: a message paced hours out because the audience
            // is large is not stale the moment it is created. A day is generous
            // enough for a large, slowly-paced audience without holding a
            // hopelessly late send open indefinitely.
            @Value("${horecaos.notifications.telegram.campaign-message-expiry:P1D}") Duration messageExpiry) {
        this.notifications = notifications;
        this.templates = templates;
        this.pacer = pacer;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.messageExpiry = messageExpiry;
    }

    @Override
    @Transactional
    public @Nullable UUID enqueue(MarketingMessage message) {
        if (!MESSAGING_APP_CHANNEL.equals(message.channel())) {
            // Unreachable in practice: CampaignSendService checks isWired(channel)
            // before it ever calls this, and only MESSAGING_APP answers true.
            // Defended anyway rather than assumed, the same posture
            // resolveRecipientValue's ck_endpoint_destination comment takes.
            return null;
        }

        Instant now = clock.instant();
        Instant pacedAt = pacer.reserveSlot(message.tenantId(), message.brandId(), message.scheduledAt());
        Instant expiresAt = message.expiresAt() != null ? message.expiresAt() : pacedAt.plus(messageExpiry);

        NewNotification intent = new NewNotification(
                UUID.randomUUID(),
                message.tenantId(),
                message.brandId(),
                null,
                NotificationClass.MARKETING.name(),
                NotificationChannel.TELEGRAM.name(),
                message.templateKey(),
                CAMPAIGN_SUBJECT_TYPE,
                message.campaignId(),
                message.customerAccountId(),
                null,
                message.idempotencyKey(),
                objectMapper.writeValueAsString(message.variables()),
                pacedAt,
                expiresAt,
                now,
                null);

        boolean created = notifications.createIntent(intent);
        if (created) {
            return intent.notificationId();
        }
        // A replayed batch's idempotency key already has a row: the same
        // customer, the same campaign, the same message. Found rather than
        // recreated, per this port's own contract — the caller does not need to
        // know whether this call or an earlier one made it exist.
        return notifications
                .findByIdempotencyKey(message.tenantId(), message.idempotencyKey())
                .map(NotificationRow::id)
                .orElse(null);
    }

    @Override
    public Map<String, String> templateBodies(UUID tenantId, UUID brandId, String templateKey, String channel) {
        if (!MESSAGING_APP_CHANNEL.equals(channel)) {
            return Map.of();
        }
        Map<String, String> bodies = new LinkedHashMap<>();
        for (MessageLocale locale : MessageLocale.required()) {
            var resolution = templates.resolve(tenantId, brandId, templateKey, NotificationChannel.TELEGRAM, locale);
            if (resolution.isFound()) {
                bodies.put(
                        locale.tag(),
                        Optional.ofNullable(resolution.version()).orElseThrow().bodyTemplate());
            }
        }
        return Map.copyOf(bodies);
    }

    @Override
    public boolean isWired(String channel) {
        return MESSAGING_APP_CHANNEL.equals(channel);
    }

    @Override
    public OptionalDouble campaignRatePerSecond(String channel) {
        return MESSAGING_APP_CHANNEL.equals(channel)
                ? pacer.ratePerSecond(NotificationChannel.TELEGRAM.name())
                : OptionalDouble.empty();
    }
}
