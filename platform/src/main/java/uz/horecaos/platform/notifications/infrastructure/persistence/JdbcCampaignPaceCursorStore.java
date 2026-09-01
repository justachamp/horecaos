package uz.horecaos.platform.notifications.infrastructure.persistence;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The shared "next available slot" cursor a campaign's Telegram sends are
 * paced against (ADR 0059 stage 4, {@code V0113}).
 *
 * <p>{@link #reserveSlot} is the whole file. It is a leaky-bucket scheduler
 * expressed as one upsert: the row it touches stores the instant one pacing
 * interval past the last slot handed out, so the next caller's {@code
 * GREATEST(cursor, desired)} both respects "no earlier than the caller asked
 * for" and "no earlier than one interval after whoever went last" in the same
 * comparison. The row lock the upsert takes is the serialisation point — the
 * same shape {@code marketing.campaigns.claimBatch}'s reservation UPDATE
 * already uses — so two application nodes reserving a slot for the same
 * (tenant, brand, channel) at once still hand out two correctly-spaced slots
 * rather than the same one twice.
 */
@Repository
public class JdbcCampaignPaceCursorStore {

    private final JdbcClient jdbc;

    public JdbcCampaignPaceCursorStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Reserves the next slot at least {@code interval} apart from every other
     * slot already reserved for this bot, and no earlier than {@code desired}.
     *
     * @param desired the earliest this message could go out for reasons that
     *                have nothing to do with pacing — {@code now}, or a
     *                quiet-hours deferral already computed upstream
     * @param interval one divided by the configured messages-per-second rate
     * @return the assigned slot, always {@code >= desired}
     */
    public Instant reserveSlot(
            UUID tenantId, UUID brandId, String channel, Instant desired, Duration interval, Instant now) {
        Map<String, Object> parameters = Map.of(
                "tenantId",
                tenantId,
                "brandId",
                brandId,
                "channel",
                channel,
                "desired",
                utc(desired),
                "nextAfterDesired",
                utc(desired.plus(interval)),
                "intervalSeconds",
                interval.toNanos() / 1_000_000_000.0,
                "now",
                utc(now));

        Instant nextSlotAt = jdbc.sql("""
                WITH upsert AS (
                    INSERT INTO notifications.campaign_pace_cursors
                        (tenant_id, brand_id, channel, next_slot_at, updated_at)
                    VALUES (:tenantId, :brandId, :channel, :nextAfterDesired, :now)
                    ON CONFLICT (tenant_id, brand_id, channel) DO UPDATE
                       SET next_slot_at = GREATEST(campaign_pace_cursors.next_slot_at, :desired)
                                          + make_interval(secs => :intervalSeconds),
                           updated_at = :now
                    RETURNING next_slot_at
                )
                SELECT next_slot_at FROM upsert
                """)
                .params(parameters)
                .query(OffsetDateTime.class)
                .single()
                .toInstant();

        return nextSlotAt.minus(interval);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
