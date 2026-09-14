package uz.horecaos.platform.notifications.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The windowed stop-list digest queue (gap map row 2.5c, wave P16, V0298).
 *
 * <p>{@link #enqueue} is called from {@code InventoryOperationsAlertTrigger}'s
 * existing {@code BEFORE_COMMIT} listener, so an entry lands in the same
 * transaction as the availability toggle it describes. {@link
 * #pendingLocations} and {@link #pendingFor} are the sweeper's own reads;
 * {@link #markConsumed} closes a batch out once its digest alert has been
 * raised, in the same transaction as that alert.
 */
@Repository
public class JdbcInventoryStopDigestStore {

    private final JdbcClient jdbc;

    public JdbcInventoryStopDigestStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void enqueue(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID variantId,
            boolean available,
            @Nullable String reasonCode,
            @Nullable UUID triggerEventId,
            Instant occurredAt) {
        jdbc.sql("""
                INSERT INTO notifications.inventory_stop_digest_entries (
                    id, tenant_id, brand_id, location_id, variant_id, available,
                    reason_code, trigger_event_id, occurred_at, created_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :variantId, :available,
                    :reasonCode, :triggerEventId, :occurredAt, :occurredAt)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("variantId", variantId)
                .param("available", available)
                .param("reasonCode", reasonCode)
                .param("triggerEventId", triggerEventId)
                .param("occurredAt", utc(occurredAt))
                .update();
    }

    /**
     * Every (tenant, brand, location) with at least one pending entry —
     * cross-tenant by design, like every other platform-wide sweep
     * ({@code InventoryReservationSweeper}, {@code PosSyncScheduler}), since a
     * digest tick has no tenant of its own to be scoped to.
     */
    public List<PendingLocation> pendingLocations() {
        return jdbc.sql("""
                SELECT DISTINCT tenant_id, brand_id, location_id
                FROM notifications.inventory_stop_digest_entries
                WHERE consumed_at IS NULL
                """)
                .query(JdbcInventoryStopDigestStore::mapPendingLocation)
                .list();
    }

    /** Every unconsumed entry for one location, oldest first. */
    public List<PendingEntry> pendingFor(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT id, variant_id, available, reason_code, occurred_at
                FROM notifications.inventory_stop_digest_entries
                WHERE tenant_id = :tenantId AND location_id = :locationId AND consumed_at IS NULL
                ORDER BY occurred_at
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query(JdbcInventoryStopDigestStore::mapPendingEntry)
                .list();
    }

    /** Closes a claimed batch out. Called once per digest alert actually raised. */
    public void markConsumed(UUID tenantId, List<UUID> entryIds, Instant now) {
        if (entryIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                UPDATE notifications.inventory_stop_digest_entries
                SET consumed_at = :now
                WHERE tenant_id = :tenantId AND id IN (:ids) AND consumed_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("ids", entryIds)
                .param("now", utc(now))
                .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static PendingLocation mapPendingLocation(ResultSet row, int rowNumber) throws SQLException {
        return new PendingLocation(
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class));
    }

    private static PendingEntry mapPendingEntry(ResultSet row, int rowNumber) throws SQLException {
        return new PendingEntry(
                row.getObject("id", UUID.class),
                row.getObject("variant_id", UUID.class),
                row.getBoolean("available"),
                row.getString("reason_code"),
                row.getObject("occurred_at", OffsetDateTime.class).toInstant());
    }

    public record PendingLocation(UUID tenantId, UUID brandId, UUID locationId) {}

    public record PendingEntry(
            UUID id,
            UUID variantId,
            boolean available,
            @Nullable String reasonCode,
            Instant occurredAt) {}
}
