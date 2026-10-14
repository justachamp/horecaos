package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Explicit SQL for {@code tenant.kiosk_idle_media} and its list (ADR 0187, V0695).
 *
 * <p>A channel's list is a {@code tenant.kiosk_idle_media_sets} row (the version the list's {@code If-Match} is
 * checked against) and up to eight item rows in position order. The positions are {@code 0..7}, unique per
 * channel, and the unique key is deferred so a reorder is one transaction. Every statement names the tenant.
 */
@Repository
public class JdbcKioskIdleMediaStore {

    private final JdbcClient jdbc;

    public JdbcKioskIdleMediaStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** One picture on a channel's idle loop. */
    public record ItemRow(UUID id, UUID assetId, int position, String createdBy, Instant createdAt) {}

    /** The list's version, or 0 when the channel has never had a picture. */
    public int version(UUID tenantId, UUID channelId) {
        return jdbc.sql("""
                        SELECT version FROM tenant.kiosk_idle_media_sets
                         WHERE tenant_id = :tenantId AND channel_id = :channelId
                        """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .query(Integer.class)
                .optional()
                .orElse(0);
    }

    public List<ItemRow> items(UUID tenantId, UUID channelId) {
        return jdbc.sql("""
                        SELECT id, asset_id, position, created_by, created_at
                          FROM tenant.kiosk_idle_media
                         WHERE tenant_id = :tenantId AND channel_id = :channelId
                         ORDER BY position
                        """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .query((rs, n) -> new ItemRow(
                        rs.getObject("id", UUID.class),
                        rs.getObject("asset_id", UUID.class),
                        rs.getInt("position"),
                        rs.getString("created_by"),
                        rs.getObject("created_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /**
     * Takes the list's next version if (and only if) it is still {@code expected}; locks it for the rest of the
     * transaction. A channel with no list yet is version 0 and gets its first row here.
     *
     * @return the new version, or empty when someone else changed the list since the manager read it
     */
    public Optional<Integer> bump(UUID tenantId, UUID channelId, int expected, Instant now) {
        if (expected == 0) {
            int created = jdbc.sql("""
                            INSERT INTO tenant.kiosk_idle_media_sets
                                (tenant_id, channel_id, channel_type, version, updated_at)
                            VALUES (:tenantId, :channelId, 'KIOSK', 1, :now)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("tenantId", tenantId)
                    .param("channelId", channelId)
                    .param("now", at(now))
                    .update();
            return created == 1 ? Optional.of(1) : Optional.empty();
        }
        return jdbc.sql("""
                        UPDATE tenant.kiosk_idle_media_sets
                           SET version = version + 1, updated_at = :now
                         WHERE tenant_id = :tenantId AND channel_id = :channelId AND version = :expected
                        RETURNING version
                        """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("expected", expected)
                .param("now", at(now))
                .query(Integer.class)
                .optional();
    }

    public void insert(
            UUID tenantId, UUID id, UUID channelId, UUID assetId, int position, String createdBy, Instant now) {
        jdbc.sql("""
                        INSERT INTO tenant.kiosk_idle_media
                            (tenant_id, id, channel_id, asset_id, position, created_by, created_at)
                        VALUES (:tenantId, :id, :channelId, :assetId, :position, :createdBy, :now)
                        """)
                .param("tenantId", tenantId)
                .param("id", id)
                .param("channelId", channelId)
                .param("assetId", assetId)
                .param("position", position)
                .param("createdBy", createdBy)
                .param("now", at(now))
                .update();
    }

    /** Removes one item and closes the gap, in one transaction (the position key is deferred). */
    public boolean remove(UUID tenantId, UUID channelId, UUID itemId) {
        Integer removed = jdbc.sql("""
                        DELETE FROM tenant.kiosk_idle_media
                         WHERE tenant_id = :tenantId AND channel_id = :channelId AND id = :itemId
                        RETURNING position
                        """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("itemId", itemId)
                .query(Integer.class)
                .optional()
                .orElse(null);
        if (removed == null) {
            return false;
        }
        jdbc.sql("""
                        UPDATE tenant.kiosk_idle_media SET position = position - 1
                         WHERE tenant_id = :tenantId AND channel_id = :channelId AND position > :removed
                        """)
                .param("tenantId", tenantId)
                .param("channelId", channelId)
                .param("removed", removed)
                .update();
        return true;
    }

    /** Sets each listed item's position to its index. The caller has checked the list is a permutation. */
    public void place(UUID tenantId, UUID channelId, List<UUID> orderedItemIds) {
        for (int position = 0; position < orderedItemIds.size(); position++) {
            jdbc.sql("""
                            UPDATE tenant.kiosk_idle_media SET position = :position
                             WHERE tenant_id = :tenantId AND channel_id = :channelId AND id = :itemId
                            """)
                    .param("position", position)
                    .param("tenantId", tenantId)
                    .param("channelId", channelId)
                    .param("itemId", orderedItemIds.get(position))
                    .update();
        }
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
