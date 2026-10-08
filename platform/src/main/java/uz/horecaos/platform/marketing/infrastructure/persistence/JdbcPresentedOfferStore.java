package uz.horecaos.platform.marketing.infrastructure.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code marketing.presented_offers}: what an in-app surface is to show a guest
 * (ADR 0112).
 *
 * <p>Holds an account id and an offer id and nothing that identifies the person. Its own
 * per-day show count is the cap a banner needs, since a banner has no delivery attempt to
 * count against the messaging frequency cap; the day rolls over by comparing
 * {@code shown_day} rather than by a job that resets a counter.
 */
@Repository
public class JdbcPresentedOfferStore {

    private final JdbcClient jdbc;

    public JdbcPresentedOfferStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Offers this guest one banner, once per offer, surface and campaign.
     *
     * @return true when this call created the presentation
     */
    public boolean present(
            UUID id,
            UUID tenantId,
            UUID brandId,
            @Nullable UUID campaignId,
            UUID offerId,
            UUID accountId,
            String surface,
            Instant now) {
        return jdbc.sql("""
                INSERT INTO marketing.presented_offers (
                    id, tenant_id, brand_id, campaign_id, offer_id, customer_account_id, surface, created_at)
                VALUES (:id, :tenantId, :brandId, :campaignId, :offerId, :accountId, :surface, :now)
                ON CONFLICT DO NOTHING
                """)
                        .param("id", id)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("campaignId", campaignId)
                        .param("offerId", offerId)
                        .param("accountId", accountId)
                        .param("surface", surface)
                        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        .update()
                == 1;
    }

    /**
     * What could be shown to this guest on this surface: not dismissed, and the offer
     * still in force at {@code now}. The cap is applied by {@link #markShown}, not here, so
     * a read that shows nothing is not mistaken for one that was capped.
     */
    public List<PresentedRow> candidates(UUID tenantId, UUID brandId, UUID accountId, String surface, Instant now) {
        return jdbc.sql("""
                SELECT p.id, p.offer_id, p.campaign_id, o.display_name, o.banner_image_reference, p.created_at
                  FROM marketing.presented_offers p
                  JOIN marketing.offers o ON o.id = p.offer_id AND o.tenant_id = p.tenant_id
                 WHERE p.tenant_id = :tenantId AND p.brand_id = :brandId AND p.customer_account_id = :accountId
                   AND p.surface = :surface AND p.dismissed_at IS NULL
                   AND (o.status = 'PUBLISHED' OR (o.status = 'SUPERSEDED' AND p.campaign_id IS NOT NULL))
                   AND o.valid_from <= :now
                   AND (o.valid_until IS NULL OR o.valid_until > :now)
                 ORDER BY p.created_at, p.id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("accountId", accountId)
                .param("surface", surface)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .query((row, number) -> new PresentedRow(
                        row.getObject("id", UUID.class),
                        row.getObject("offer_id", UUID.class),
                        row.getObject("campaign_id", UUID.class),
                        row.getString("display_name"),
                        row.getString("banner_image_reference"),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /**
     * Counts one showing against today's cap, or refuses because the cap is reached.
     *
     * <p>One conditional UPDATE, so two polls racing for the last slot of the day produce
     * one winner: the check and the increment are the same statement.
     *
     * @return true when this showing was within the cap and has been counted
     */
    public boolean markShown(
            UUID tenantId, UUID accountId, UUID presentedId, LocalDate day, int capPerDay, Instant now) {
        return jdbc.sql("""
                UPDATE marketing.presented_offers
                   SET shown_count = shown_count + 1,
                       last_shown_at = :now,
                       shown_today = CASE WHEN shown_day = :day THEN shown_today + 1 ELSE 1 END,
                       shown_day = :day
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId AND id = :id
                   AND dismissed_at IS NULL
                   AND (shown_day IS DISTINCT FROM :day OR shown_today < :cap)
                """)
                        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        .param("day", day)
                        .param("tenantId", tenantId)
                        .param("accountId", accountId)
                        .param("id", presentedId)
                        .param("cap", capPerDay)
                        .update()
                == 1;
    }

    public boolean dismiss(UUID tenantId, UUID accountId, UUID presentedId, Instant now) {
        return jdbc.sql("""
                UPDATE marketing.presented_offers
                   SET dismissed_at = :now
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId AND id = :id
                   AND dismissed_at IS NULL
                """)
                        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        .param("tenantId", tenantId)
                        .param("accountId", accountId)
                        .param("id", presentedId)
                        .update()
                == 1;
    }

    /** A guest's presentations across every offer, newest first: what the customer card reads (ADR 0111). */
    public List<PresentationHistoryRow> historyForGuest(UUID tenantId, UUID accountId, int limit) {
        return jdbc.sql("""
                SELECT id, brand_id, campaign_id, offer_id, surface, shown_count, last_shown_at, dismissed_at, created_at
                  FROM marketing.presented_offers
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                 ORDER BY created_at DESC, id DESC
                 LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("accountId", accountId)
                .param("limit", limit)
                .query((row, number) -> new PresentationHistoryRow(
                        row.getObject("id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("campaign_id", UUID.class),
                        row.getObject("offer_id", UUID.class),
                        row.getString("surface"),
                        row.getInt("shown_count"),
                        instant(row.getObject("last_shown_at", OffsetDateTime.class)),
                        instant(row.getObject("dismissed_at", OffsetDateTime.class)),
                        row.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    public record PresentedRow(
            UUID id,
            UUID offerId,
            @Nullable UUID campaignId,
            String displayName,
            @Nullable String bannerImageReference,
            Instant createdAt) {}

    public record PresentationHistoryRow(
            UUID id,
            UUID brandId,
            @Nullable UUID campaignId,
            UUID offerId,
            String surface,
            int shownCount,
            @Nullable Instant lastShownAt,
            @Nullable Instant dismissedAt,
            Instant createdAt) {}
}
