package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.OptionalDouble;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcCampaignPaceCursorStore;

/**
 * Paces one bot's campaign traffic well under the Bot API's shared ceiling
 * (ADR 0059 stage 4).
 *
 * <p>The Bot API allows roughly 30 messages per second per bot, shared with
 * every other message that bot sends — order alerts, flow replies, the
 * operator inbox. {@link #TELEGRAM_CAMPAIGN_RATE_PER_SECOND}'s conservative
 * default of 10/s is a static, deliberately generous headroom allocation
 * rather than a live, bot-wide coordinator: campaign traffic never claims more
 * than a third of the ceiling regardless of what else that bot is doing, so
 * transactional traffic — which does not go through this pacer at all — always
 * has room. ADR 0058's bot-per-brand topology is what makes "per bot" the same
 * key as "per brand", which is also {@code marketing.campaigns.brand_id}'s own
 * scope, so two campaigns racing on the same brand share one budget rather
 * than each getting their own.
 *
 * <p><strong>Pacing lives here, at scheduling time, not in the delivery
 * worker's claim loop.</strong> The alternative — claim a row, discover it is
 * too early, and requeue it — was rejected because {@code
 * NotificationWorker#claimDue} increments {@code attempt_count}
 * unconditionally on every claim, the same counter {@code
 * NotificationDispatchService#escalateOrRetry} uses to decide when a message
 * has failed enough times to need a person. A campaign message paced ten
 * seconds out would burn its entire eight-attempt budget on nothing but
 * waiting and land in {@code MANUAL_REVIEW} before it was ever really tried.
 * Computing the slot once, up front, and writing it as {@code scheduled_at}
 * means the worker's very first claim of a paced message happens at
 * (approximately) its actual turn — the same idiom ADR 0044's own quiet-hours
 * deferral already uses for "not yet, but not a failure either".
 */
@Component
public class CampaignPacer {

    /**
     * Conservative on purpose (ADR 0059 stage 4: "default conservative, e.g.
     * 10/s"). A third of the ~30/s Bot API ceiling, so transactional traffic
     * sharing the same bot always has at least twice the campaign's own rate
     * left over.
     */
    public static final double TELEGRAM_CAMPAIGN_RATE_PER_SECOND = 10.0;

    private final JdbcCampaignPaceCursorStore cursors;
    private final Clock clock;
    private final double telegramRatePerSecond;

    public CampaignPacer(
            JdbcCampaignPaceCursorStore cursors,
            Clock clock,
            @Value("${horecaos.notifications.telegram.campaign-rate-per-second:10}") double telegramRatePerSecond) {
        this.cursors = cursors;
        this.clock = clock;
        this.telegramRatePerSecond = telegramRatePerSecond;
    }

    /**
     * The configured ceiling for {@code channel}, or empty when this pacer
     * paces nothing on it. Read by {@code CampaignMessagePort#campaignRatePerSecond}
     * so the estimated delivery window an approver sees matches the rate a send
     * is actually paced at.
     */
    public OptionalDouble ratePerSecond(String channel) {
        return NotificationChannel.TELEGRAM.name().equals(channel)
                ? OptionalDouble.of(telegramRatePerSecond)
                : OptionalDouble.empty();
    }

    /**
     * Reserves the next available send slot for one more TELEGRAM campaign
     * message on this brand's bot.
     *
     * @param desired the earliest this message could otherwise go out — {@code
     *                now}, or a quiet-hours boundary the campaign already
     *                computed. The pacer only ever pushes this later, never
     *                earlier: composing with an upstream deferral is a
     *                {@code max}, not a replacement
     */
    public Instant reserveSlot(UUID tenantId, UUID brandId, Instant desired) {
        Instant now = clock.instant();
        Duration interval = Duration.ofNanos((long) (1_000_000_000.0 / telegramRatePerSecond));
        Instant floor = desired.isBefore(now) ? now : desired;
        return cursors.reserveSlot(tenantId, brandId, NotificationChannel.TELEGRAM.name(), floor, interval, now);
    }
}
