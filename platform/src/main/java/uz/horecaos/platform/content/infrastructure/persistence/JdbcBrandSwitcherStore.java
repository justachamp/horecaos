package uz.horecaos.platform.content.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code content.brand_switcher_entries} (V0676, ADR 0157 Release 4, ADR 0183 D17).
 *
 * <p>Every statement carries the tenant and the brand: an entry id is a UUID the caller may have been handed anywhere, and an
 * update that matched on the id alone could edit a sibling brand's switcher.
 */
@Repository
public class JdbcBrandSwitcherStore {

    private static final String COLUMNS = """
            id, tenant_id, brand_id, target_brand_id, channel_id, sort_key, state, created_by, created_at, updated_at, version
            """;

    private final JdbcClient jdbc;

    public JdbcBrandSwitcherStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public record EntryRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID targetBrandId,
            UUID channelId,
            int sortKey,
            String state,
            String createdBy,
            Instant createdAt,
            Instant updatedAt,
            int version) {}

    public List<EntryRow> list(UUID tenantId, UUID brandId) {
        return jdbc.sql("SELECT " + COLUMNS + """
                FROM content.brand_switcher_entries
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                ORDER BY sort_key, created_at, id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(JdbcBrandSwitcherStore::row)
                .list();
    }

    public Optional<EntryRow> find(UUID tenantId, UUID brandId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + """
                FROM content.brand_switcher_entries
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", id)
                .query(JdbcBrandSwitcherStore::row)
                .optional();
    }

    /** The sort key a new entry takes: ten past the last, so a later reorder has room between. */
    public int nextSortKey(UUID tenantId, UUID brandId) {
        Integer max = jdbc.sql("""
                SELECT max(sort_key) FROM content.brand_switcher_entries
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        return max == null ? 10 : Math.min(max + 10, 100_000);
    }

    /** @return false when the same target and channel is already an entry of this brand */
    public boolean insert(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID targetBrandId,
            UUID channelId,
            int sortKey,
            String createdBy,
            Instant now) {
        return jdbc.sql("""
                INSERT INTO content.brand_switcher_entries
                    (id, tenant_id, brand_id, target_brand_id, channel_id, sort_key, state, created_by, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :targetBrandId, :channelId, :sortKey, 'ACTIVE', :createdBy, :now, :now)
                ON CONFLICT (tenant_id, brand_id, target_brand_id, channel_id) DO NOTHING
                """)
                        .param("id", id)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("targetBrandId", targetBrandId)
                        .param("channelId", channelId)
                        .param("sortKey", sortKey)
                        .param("createdBy", createdBy)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** @return false when the entry's version has moved since it was read, or it is not this brand's */
    public boolean setState(UUID tenantId, UUID brandId, UUID id, String state, int expectedVersion, Instant now) {
        return jdbc.sql("""
                UPDATE content.brand_switcher_entries
                   SET state = :state, version = version + 1, updated_at = :now
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id AND version = :expectedVersion
                """)
                        .param("state", state)
                        .param("now", utc(now))
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("id", id)
                        .param("expectedVersion", expectedVersion)
                        .update()
                == 1;
    }

    public boolean delete(UUID tenantId, UUID brandId, UUID id) {
        return jdbc.sql("""
                DELETE FROM content.brand_switcher_entries
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                """)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("id", id)
                        .update()
                == 1;
    }

    /** Gives the named entries sort keys 10, 20, 30... in the order given. */
    public void reorder(UUID tenantId, UUID brandId, List<UUID> orderedIds, Instant now) {
        int key = 10;
        for (UUID id : orderedIds) {
            jdbc.sql("""
                    UPDATE content.brand_switcher_entries
                       SET sort_key = :key, version = version + 1, updated_at = :now
                     WHERE tenant_id = :tenantId AND brand_id = :brandId AND id = :id
                    """)
                    .param("key", key)
                    .param("now", utc(now))
                    .param("tenantId", tenantId)
                    .param("brandId", brandId)
                    .param("id", id)
                    .update();
            key += 10;
        }
    }

    private static EntryRow row(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new EntryRow(
                rs.getObject("id", UUID.class),
                rs.getObject("tenant_id", UUID.class),
                rs.getObject("brand_id", UUID.class),
                rs.getObject("target_brand_id", UUID.class),
                rs.getObject("channel_id", UUID.class),
                rs.getInt("sort_key"),
                rs.getString("state"),
                rs.getString("created_by"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                rs.getInt("version"));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
