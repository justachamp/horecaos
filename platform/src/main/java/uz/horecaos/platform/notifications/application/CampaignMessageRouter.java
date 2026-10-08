package uz.horecaos.platform.notifications.application;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.marketing.api.CampaignMessagePort;
import uz.horecaos.platform.notifications.api.NotificationTransport;
import uz.horecaos.platform.notifications.api.NotificationTransport.Readiness;
import uz.horecaos.platform.notifications.domain.SuppressionReason;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcDeliveryReceiptStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcDeliveryReceiptStore.AttemptEvidence;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;

/**
 * {@link CampaignMessagePort} as one router over per-channel deliveries (ADR 0146
 * Decision 8).
 *
 * <p>Telegram and SMS today, each a {@link CampaignChannelDelivery}. The router owns
 * the three things that are not a channel's: which delivery a message belongs to,
 * the answer to "is this channel wired for this brand and this purpose", and the
 * campaign-wide reads that do not care about a channel (how many messages a pause
 * suppressed, and what a gateway has reported about the ones that went).
 *
 * <p><strong>Wired means "this brand has a working account", not "this build has an
 * adapter".</strong> Telegram keeps its old answer, true for every brand, because its
 * binding is the customer's own link to the bot and not the brand's account. Every
 * other channel asks the transport, which reads the brand's binding, the
 * installation's status and the account's configuration without calling out, and
 * answers with a stable reason when it cannot. A channel with no delivery here is not
 * wired, whatever an adapter elsewhere could do.
 */
@Component
@Primary
public class CampaignMessageRouter implements CampaignMessagePort {

    private static final String TELEGRAM_CAMPAIGN_CHANNEL = CampaignTelegramDeliveryService.MESSAGING_APP_CHANNEL;

    private final Map<String, CampaignChannelDelivery> deliveries = new LinkedHashMap<>();
    private final NotificationTransport transport;
    private final JdbcNotificationStore notifications;
    private final JdbcDeliveryReceiptStore evidence;

    public CampaignMessageRouter(
            List<CampaignChannelDelivery> deliveries,
            NotificationTransport transport,
            JdbcNotificationStore notifications,
            JdbcDeliveryReceiptStore evidence) {
        deliveries.forEach(delivery -> {
            if (this.deliveries.put(delivery.channel(), delivery) != null) {
                throw new IllegalStateException("Two campaign deliveries answer for the channel " + delivery.channel());
            }
        });
        this.transport = transport;
        this.notifications = notifications;
        this.evidence = evidence;
    }

    @Override
    public @Nullable UUID enqueue(MarketingMessage message) {
        CampaignChannelDelivery delivery = deliveries.get(message.channel());
        return delivery == null ? null : delivery.enqueue(message);
    }

    @Override
    public Map<String, String> templateBodies(UUID tenantId, UUID brandId, String templateKey, String channel) {
        CampaignChannelDelivery delivery = deliveries.get(channel);
        return delivery == null ? Map.of() : delivery.templateBodies(tenantId, brandId, templateKey);
    }

    @Override
    public Wiring wiring(UUID tenantId, UUID brandId, String channel, String purpose) {
        CampaignChannelDelivery delivery = deliveries.get(channel);
        if (delivery == null) {
            return Wiring.no("NO_DELIVERY_ADAPTER");
        }
        if (TELEGRAM_CAMPAIGN_CHANNEL.equals(channel)) {
            return Wiring.yes();
        }
        Readiness readiness = transport.readiness(tenantId, brandId, channel, purpose);
        return readiness.ready()
                ? Wiring.yes()
                : Wiring.no(readiness.reason() == null ? "NOT_READY" : readiness.reason());
    }

    @Override
    public OptionalDouble campaignRatePerSecond(String channel) {
        CampaignChannelDelivery delivery = deliveries.get(channel);
        return delivery == null ? OptionalDouble.empty() : delivery.ratePerSecond();
    }

    @Override
    public int countSuppressedForNotSending(UUID tenantId, UUID campaignId, Instant since) {
        return notifications.countSuppressed(
                tenantId,
                CampaignTelegramDeliveryService.CAMPAIGN_SUBJECT_TYPE,
                campaignId,
                SuppressionReason.CAMPAIGN_NOT_SENDING.name(),
                since);
    }

    @Override
    public Map<UUID, DeliveryEvidence> deliveryEvidence(UUID tenantId, Collection<UUID> notificationIds) {
        Map<UUID, DeliveryEvidence> byNotification = new LinkedHashMap<>();
        for (Map.Entry<UUID, AttemptEvidence> entry :
                evidence.latestAttempts(tenantId, notificationIds).entrySet()) {
            AttemptEvidence attempt = entry.getValue();
            byNotification.put(
                    entry.getKey(),
                    new DeliveryEvidence(stateOf(attempt), attempt.receiptState(), attempt.segmentsBilled()));
        }
        return byNotification;
    }

    /**
     * The word a recipient row shows (ADR 0146 Decision 5). "Handed to the
     * operator" is what a gateway that accepted a message and has reported nothing
     * on it deserves: not "delivered", which nobody said, and not a failure, which
     * nobody said either.
     */
    static String stateOf(AttemptEvidence attempt) {
        return switch (attempt.status()) {
            case "DELIVERED" -> "DELIVERED";
            case "FAILED" -> "FAILED";
            case "REJECTED" -> "REJECTED";
            case "ACCEPTED" -> "NO_RECEIPT".equals(attempt.receiptState()) ? "NO_RECEIPT" : "HANDED_TO_OPERATOR";
            default -> "PENDING";
        };
    }
}
