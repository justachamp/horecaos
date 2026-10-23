package uz.horecaos.platform.notifications.application;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.marketing.api.CampaignMessagePort;
import uz.horecaos.platform.notifications.api.NotificationTransport;
import uz.horecaos.platform.notifications.api.NotificationTransport.Readiness;
import uz.horecaos.platform.notifications.api.ReceiptEnablement;
import uz.horecaos.platform.notifications.domain.SuppressionReason;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcDeliveryReceiptStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcDeliveryReceiptStore.EvidenceGroup;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcDeliveryReceiptStore.MessageEvidence;
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
    private final ReceiptEnablement receiptSources;

    /** For a caller with no delivery receipt source enabled for any gateway. */
    public CampaignMessageRouter(
            List<CampaignChannelDelivery> deliveries,
            NotificationTransport transport,
            JdbcNotificationStore notifications,
            JdbcDeliveryReceiptStore evidence) {
        this(deliveries, transport, notifications, evidence, new ConfiguredReceiptEnablement(""));
    }

    @Autowired
    public CampaignMessageRouter(
            List<CampaignChannelDelivery> deliveries,
            NotificationTransport transport,
            JdbcNotificationStore notifications,
            JdbcDeliveryReceiptStore evidence,
            ReceiptEnablement receiptSources) {
        deliveries.forEach(delivery -> {
            if (this.deliveries.put(delivery.channel(), delivery) != null) {
                throw new IllegalStateException("Two campaign deliveries answer for the channel " + delivery.channel());
            }
        });
        this.transport = transport;
        this.notifications = notifications;
        this.evidence = evidence;
        this.receiptSources = receiptSources;
    }

    @Override
    public @Nullable UUID enqueue(MarketingMessage message) {
        CampaignChannelDelivery delivery = deliveries.get(message.channel());
        return delivery == null ? null : delivery.enqueue(message);
    }

    @Override
    public @Nullable UUID enqueueCourierMessage(CourierMessage message) {
        CampaignChannelDelivery delivery = deliveries.get(CampaignSmsDeliveryService.SMS_CHANNEL);
        return delivery == null ? null : delivery.enqueueCourierMessage(message);
    }

    @Override
    public List<CourierTemplate> courierTemplates(UUID tenantId, UUID brandId) {
        CampaignChannelDelivery delivery = deliveries.get(CampaignSmsDeliveryService.SMS_CHANNEL);
        return delivery == null ? List.of() : delivery.courierTemplates(tenantId, brandId);
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
        for (Map.Entry<UUID, MessageEvidence> entry :
                evidence.evidenceFor(tenantId, notificationIds).entrySet()) {
            DeliveryStates.Classified classified = DeliveryStates.classify(entry.getValue());
            var attempt = entry.getValue().attempt();
            byNotification.put(
                    entry.getKey(),
                    new DeliveryEvidence(
                            classified.state(),
                            attempt == null ? null : attempt.receiptState(),
                            attempt == null ? null : attempt.segmentsBilled(),
                            classified.reason()));
        }
        return byNotification;
    }

    /**
     * The campaign's messages counted by the word each one's evidence earns (ADR 0182).
     *
     * <p>The same mapping a recipient row uses, over a grouped read, so a count and the rows it
     * summarises cannot disagree. A read is a delivered message that was also read, so it is
     * counted in both.
     */
    @Override
    public DeliveryCounts deliveryCounts(UUID tenantId, UUID campaignId) {
        int total = 0;
        int delivered = 0;
        int read = 0;
        int failed = 0;
        int rejected = 0;
        int handed = 0;
        int noReceipt = 0;
        int pending = 0;
        int withheld = 0;
        long segments = 0;
        Map<String, Integer> withheldByReason = new LinkedHashMap<>();
        for (EvidenceGroup group :
                evidence.evidenceGroups(tenantId, CampaignTelegramDeliveryService.CAMPAIGN_SUBJECT_TYPE, campaignId)) {
            DeliveryStates.Classified classified = DeliveryStates.classify(group.evidence());
            int count = group.count();
            total += count;
            segments += group.segmentsBilled();
            switch (classified.state()) {
                case STATE_DELIVERED -> delivered += count;
                case STATE_READ -> {
                    delivered += count;
                    read += count;
                }
                case STATE_FAILED -> failed += count;
                case STATE_REJECTED -> rejected += count;
                case STATE_HANDED_TO_OPERATOR -> handed += count;
                case STATE_NO_RECEIPT -> noReceipt += count;
                case STATE_WITHHELD -> {
                    withheld += count;
                    withheldByReason.merge(
                            classified.reason() == null ? "UNKNOWN" : classified.reason(), count, Integer::sum);
                }
                default -> pending += count;
            }
        }
        return new DeliveryCounts(
                total,
                delivered,
                read,
                failed,
                rejected,
                handed,
                noReceipt,
                pending,
                withheld,
                withheldByReason,
                segments);
    }

    /**
     * What this channel can report for this brand (ADR 0182): a delivery receipt only where the
     * channel's gateways send them <em>and</em> the brand's bound gateway has an enabled receipt
     * source (ADR 0146 Decision 4), because a VAS-bound brand stops at «handed to the operator»
     * until a real callback has been captured. A read is the channel's own declaration.
     */
    @Override
    public ChannelReporting reporting(UUID tenantId, UUID brandId, String channel) {
        CampaignChannelDelivery delivery = deliveries.get(channel);
        if (delivery == null) {
            return ChannelReporting.NONE;
        }
        boolean reportsDelivery = delivery.providersReportDelivery()
                && transport
                        .boundProviderType(tenantId, brandId, channel)
                        .map(receiptSources::isEnabled)
                        .orElse(false);
        return new ChannelReporting(reportsDelivery, delivery.reportsRead());
    }
}
