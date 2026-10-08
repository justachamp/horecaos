package uz.horecaos.platform.marketing.infrastructure.persistence;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** ADR 0044 "Attribution and referrals" — {@code marketing.attribution_links} (V0309). */
@Repository
public class JdbcAttributionLinkStore {

    private final JdbcClient jdbc;

    public JdbcAttributionLinkStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String label,
            String token,
            @Nullable String ownerNote,
            String channel,
            String destinationType,
            @Nullable UUID destinationId,
            Instant validFrom,
            @Nullable Instant validUntil,
            UUID createdBy,
            Instant now) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", id);
        params.put("tenantId", tenantId);
        params.put("brandId", brandId);
        params.put("label", label);
        params.put("token", token);
        params.put("ownerNote", ownerNote);
        params.put("channel", channel);
        params.put("destinationType", destinationType);
        params.put("destinationId", destinationId);
        params.put("validFrom", utc(validFrom));
        params.put("validUntil", utc(validUntil));
        params.put("createdBy", createdBy);
        params.put("now", utc(now));

        jdbc.sql("""
                INSERT INTO marketing.attribution_links (
                    id, tenant_id, brand_id, label, token, owner_note, channel,
                    destination_type, destination_id, status, valid_from, valid_until,
                    created_by, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :label, :token, :ownerNote, :channel,
                    :destinationType, :destinationId, 'ACTIVE', :validFrom, :validUntil,
                    :createdBy, :now, :now)
                """).params(params).update();
    }

    private static final String COLUMNS = """
            id, tenant_id, brand_id, label, token, owner_note, channel, destination_type,
            destination_id, status, valid_from, valid_until, click_count, created_by,
            created_at, updated_at, version
            """;

    public Optional<AttributionLinkRow> find(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM marketing.attribution_links WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .query(JdbcAttributionLinkStore::toRow)
                .optional();
    }

    public List<AttributionLinkRow> listByBrand(UUID tenantId, UUID brandId) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM marketing.attribution_links WHERE tenant_id = :tenantId AND brand_id = :brandId"
                        + " ORDER BY created_at DESC")
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(JdbcAttributionLinkStore::toRow)
                .list();
    }

    public boolean tokenTaken(UUID tenantId, String token) {
        Boolean exists = jdbc.sql(
                        "SELECT EXISTS (SELECT 1 FROM marketing.attribution_links WHERE tenant_id = :tenantId AND token = :token)")
                .param("tenantId", tenantId)
                .param("token", token)
                .query(Boolean.class)
                .single();
        return Boolean.TRUE.equals(exists);
    }

    public boolean archive(UUID tenantId, UUID id, Instant now) {
        return jdbc.sql("""
                UPDATE marketing.attribution_links
                   SET status = 'ARCHIVED', version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'ACTIVE'
                """)
                        .param("tenantId", tenantId)
                        .param("id", id)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * Counts one click on a link named by its public token (ADR 0167 Decision 4/5, tier 1).
     *
     * <p>One statement, so the running total and the day's series cannot disagree: the link is
     * updated only if it is {@code ACTIVE}, of the named brand and inside its validity window, and the
     * series row is upserted from what that update returned. An unknown, archived, out-of-window or
     * other-brand token updates nothing and inserts nothing -- the caller cannot tell which, and the
     * endpoint answers all of them alike. The "day" is the tenant's own calendar day (its default
     * timezone), the same day a manager reads the series on.
     *
     * <p>Records no address, no user agent and no cookie, because none reaches this method.
     *
     * @return whether a click was counted
     */
    public boolean recordPublicClick(UUID tenantId, UUID brandId, String token, Instant now) {
        return jdbc.sql("""
                WITH hit AS (
                    UPDATE marketing.attribution_links
                       SET click_count = click_count + 1
                     WHERE tenant_id = :tenantId AND brand_id = :brandId AND token = :token
                       AND status = 'ACTIVE'
                       AND valid_from <= :now AND (valid_until IS NULL OR valid_until > :now)
                    RETURNING id, tenant_id
                )
                INSERT INTO marketing.attribution_link_clicks_day (tenant_id, link_id, day, clicks)
                SELECT hit.tenant_id, hit.id,
                       (CAST(:now AS timestamptz) AT TIME ZONE
                           (SELECT t.default_timezone FROM tenant.tenants t WHERE t.id = hit.tenant_id))::date,
                       1
                  FROM hit
                ON CONFLICT (tenant_id, link_id, day)
                DO UPDATE SET clicks = marketing.attribution_link_clicks_day.clicks + 1
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("token", token)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** Clicks per link over {@code [from, to]}, for one brand or (null) every brand of the tenant. */
    public List<LinkClicksRow> clicksBetween(UUID tenantId, @Nullable UUID brandId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT l.id, l.brand_id, l.label, l.channel, l.status, sum(c.clicks) AS clicks
                  FROM marketing.attribution_link_clicks_day c
                  JOIN marketing.attribution_links l
                    ON l.id = c.link_id AND l.tenant_id = c.tenant_id
                 WHERE c.tenant_id = :tenantId
                   AND c.day >= :from AND c.day <= :to
                   AND (CAST(:brandId AS uuid) IS NULL OR l.brand_id = CAST(:brandId AS uuid))
                 GROUP BY l.id, l.brand_id, l.label, l.channel, l.status
                HAVING sum(c.clicks) > 0
                 ORDER BY sum(c.clicks) DESC, l.label, l.id
                """)
                .param("tenantId", tenantId)
                .param("from", from)
                .param("to", to)
                .param("brandId", brandId)
                .query((row, number) -> new LinkClicksRow(
                        row.getObject("id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getString("label"),
                        row.getString("channel"),
                        row.getString("status"),
                        row.getLong("clicks")))
                .list();
    }

    public long countClickSeriesOlderThan(LocalDate cutoff) {
        Long count = jdbc.sql("SELECT count(*) FROM marketing.attribution_link_clicks_day WHERE day < :cutoff")
                .param("cutoff", cutoff)
                .query(Long.class)
                .single();
        return count == null ? 0 : count;
    }

    public int deleteClickSeriesOlderThan(LocalDate cutoff) {
        return jdbc.sql("DELETE FROM marketing.attribution_link_clicks_day WHERE day < :cutoff")
                .param("cutoff", cutoff)
                .update();
    }

    private static AttributionLinkRow toRow(ResultSet row, int number) throws java.sql.SQLException {
        return new AttributionLinkRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getString("label"),
                row.getString("token"),
                row.getString("owner_note"),
                row.getString("channel"),
                row.getString("destination_type"),
                row.getObject("destination_id", UUID.class),
                row.getString("status"),
                row.getObject("valid_from", OffsetDateTime.class).toInstant(),
                instant(row.getObject("valid_until", OffsetDateTime.class)),
                row.getInt("click_count"),
                row.getObject("created_by", UUID.class),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getObject("updated_at", OffsetDateTime.class).toInstant(),
                row.getInt("version"));
    }

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime utc(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record LinkClicksRow(UUID linkId, UUID brandId, String label, String channel, String status, long clicks) {}

    public record AttributionLinkRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            String label,
            String token,
            @Nullable String ownerNote,
            String channel,
            String destinationType,
            @Nullable UUID destinationId,
            String status,
            Instant validFrom,
            @Nullable Instant validUntil,
            int clickCount,
            UUID createdBy,
            Instant createdAt,
            Instant updatedAt,
            int version) {}
}
