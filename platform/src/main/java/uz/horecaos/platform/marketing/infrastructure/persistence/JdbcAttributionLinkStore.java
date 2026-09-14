package uz.horecaos.platform.marketing.infrastructure.persistence;

import java.sql.ResultSet;
import java.time.Instant;
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

    /** Incremented by whichever surface actually serves the link — see the table's own comment. */
    public boolean recordClick(UUID tenantId, UUID id, Instant now) {
        return jdbc.sql("""
                UPDATE marketing.attribution_links
                   SET click_count = click_count + 1, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                        .param("tenantId", tenantId)
                        .param("id", id)
                        .param("now", utc(now))
                        .update()
                == 1;
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
