package uz.horecaos.platform.notifications.application;

import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.marketing.api.CampaignMessagePort.CourierMessage;
import uz.horecaos.platform.marketing.api.CampaignMessagePort.CourierTemplate;
import uz.horecaos.platform.marketing.api.CampaignMessagePort.MarketingMessage;

/**
 * One channel's way of turning a campaign message into an ADR 0020 intent (ADR
 * 0146 Decision 8).
 *
 * <p>{@code CampaignMessagePort} used to be implemented by one class that answered
 * for Telegram alone. It is now one router over these, so a second channel is a
 * second implementation rather than a branch inside the first, and "is this channel
 * wired" is answered per channel and per brand where the channel's own account
 * lives.
 */
public interface CampaignChannelDelivery {

    /** The marketing-side channel name this delivery answers for ({@code MarketingChannel}'s). */
    String channel();

    /** Creates, or finds, the durable intent for one recipient. Idempotent on the message's key. */
    @Nullable
    UUID enqueue(MarketingMessage message);

    /** The raw body template for each locale this channel would send in, or empty. */
    Map<String, String> templateBodies(UUID tenantId, UUID brandId, String templateKey);

    /** The per-provider throughput ceiling campaign sends are paced to, or empty for none. */
    OptionalDouble ratePerSecond();

    /**
     * Whether this channel's gateways can send delivery receipts at all (ADR 0182). Whether a
     * brand's bound gateway has an enabled receipt source is the router's question; this is the
     * channel's: SMS gateways do, Telegram's Bot API and an SMTP relay do not.
     */
    default boolean providersReportDelivery() {
        return false;
    }

    /** Whether this channel reports that a message was opened or clicked (ADR 0182). */
    default boolean reportsRead() {
        return false;
    }

    /**
     * An operational message to a courier on this channel (ADR 0182, D15), or null where the channel
     * carries none. Only SMS does.
     */
    default @Nullable UUID enqueueCourierMessage(CourierMessage message) {
        return null;
    }

    /** The wordings a dispatcher may broadcast to couriers on this channel (ADR 0182). */
    default List<CourierTemplate> courierTemplates(UUID tenantId, UUID brandId) {
        return List.of();
    }
}
