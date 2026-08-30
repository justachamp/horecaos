package uz.horecaos.platform.inventory.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.inventory.api.TrackingMode;

/** Inventory persistence (ADR 0017). */
@Repository
public class JdbcInventoryStore {

    private final JdbcClient jdbc;

    public JdbcInventoryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public UUID createStockItem(
            UUID tenantId, UUID brandId, UUID locationId, UUID variantId, TrackingMode mode, Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO inventory.stock_items (
                    id, tenant_id, brand_id, location_id, variant_id, tracking_mode,
                    created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :variantId, :mode, :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("variantId", variantId)
                .param("mode", mode.name())
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();

        // A binary item starts available. Starting unavailable would silently
        // hide every newly listed dish until someone noticed.
        jdbc.sql("""
                INSERT INTO inventory.positions (
                    stock_item_id, tenant_id, brand_id, location_id, binary_available, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :available, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("available", mode == TrackingMode.BINARY ? Boolean.TRUE : null)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
        return id;
    }

    public Optional<StockItemRow> findStockItem(UUID tenantId, UUID locationId, UUID variantId) {
        return jdbc.sql("""
                SELECT s.id, s.tracking_mode, p.binary_available, p.position_sequence
                FROM inventory.stock_items s
                JOIN inventory.positions p ON p.stock_item_id = s.id
                WHERE s.tenant_id = :tenantId AND s.location_id = :locationId
                  AND s.variant_id = :variantId AND s.status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("variantId", variantId)
                .query(JdbcInventoryStore::mapStockItem)
                .optional();
    }

    /** Stock items for a set of variants at one location, in one round trip. */
    public Map<UUID, StockItemRow> findStockItems(UUID tenantId, UUID locationId, Set<UUID> variantIds) {
        if (variantIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, StockItemRow> byVariant = new HashMap<>();
        jdbc.sql("""
                SELECT s.variant_id, s.id, s.tracking_mode, p.binary_available, p.position_sequence
                FROM inventory.stock_items s
                JOIN inventory.positions p ON p.stock_item_id = s.id
                WHERE s.tenant_id = :tenantId AND s.location_id = :locationId
                  AND s.variant_id = ANY(:ids) AND s.status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("ids", variantIds.toArray(UUID[]::new))
                .query((row, number) -> Map.entry(row.getObject("variant_id", UUID.class), mapStockItem(row, number)))
                .list()
                .forEach(entry -> byVariant.put(entry.getKey(), entry.getValue()));
        return byVariant;
    }

    /**
     * Sets a binary item's availability and records why.
     *
     * <p>Position and movement are written together. The movement is the record
     * of what happened; the position is derived state that could be rebuilt from
     * it. Writing only the position would leave "why is this sold out" with no
     * answer at all.
     */
    public void setBinaryAvailability(
            UUID tenantId,
            UUID stockItemId,
            boolean available,
            String idempotencyKey,
            String reasonCode,
            String actorType,
            UUID actorId,
            Instant now) {

        jdbc.sql("""
                INSERT INTO inventory.movements (
                    id, tenant_id, brand_id, location_id, stock_item_id, sequence_number,
                    movement_type, binary_state, source_type, idempotency_key, reason_code,
                    actor_type, actor_id, occurred_at)
                SELECT :movementId, s.tenant_id, s.brand_id, s.location_id, s.id,
                       COALESCE((SELECT max(m.sequence_number) FROM inventory.movements m
                                 WHERE m.stock_item_id = s.id), 0) + 1,
                       'AVAILABILITY_CHANGE', :available, 'OPERATOR', :idempotencyKey, :reason,
                       :actorType, :actorId, :now
                FROM inventory.stock_items s
                WHERE s.id = :stockItemId AND s.tenant_id = :tenantId
                ON CONFLICT (tenant_id, stock_item_id, idempotency_key) DO NOTHING
                """)
                .param("movementId", UUID.randomUUID())
                .param("stockItemId", stockItemId)
                .param("tenantId", tenantId)
                .param("available", available)
                .param("idempotencyKey", idempotencyKey)
                .param("reason", reasonCode)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                UPDATE inventory.positions
                SET binary_available = :available,
                    position_sequence = position_sequence + 1,
                    version = version + 1,
                    updated_at = :now
                WHERE stock_item_id = :stockItemId AND tenant_id = :tenantId
                """)
                .param("stockItemId", stockItemId)
                .param("tenantId", tenantId)
                .param("available", available)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    /**
     * Takes a hold, refusing a second live one for the same owner.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than catching a violation, because
     * a constraint violation aborts the surrounding PostgreSQL transaction and
     * everything after it fails for the wrong reason.
     *
     * @return true if this call created the hold
     */
    public boolean insertReservation(
            UUID reservationId,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String ownerType,
            UUID ownerId,
            Instant expiresAt,
            Instant now) {
        return jdbc.sql("""
                INSERT INTO inventory.reservations (
                    id, tenant_id, brand_id, location_id, owner_type, owner_id,
                    status, expires_at, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :ownerType, :ownerId,
                    'HELD', :expiresAt, :now, :now)
                ON CONFLICT (tenant_id, owner_type, owner_id) DO NOTHING
                """)
                        .param("id", reservationId)
                        .param("tenantId", tenantId)
                        .param("brandId", brandId)
                        .param("locationId", locationId)
                        .param("ownerType", ownerType)
                        .param("ownerId", ownerId)
                        .param("expiresAt", OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC))
                        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        .update()
                == 1;
    }

    public void insertReservationLine(
            UUID reservationId, UUID tenantId, UUID stockItemId, java.math.BigDecimal quantity) {
        jdbc.sql("""
                INSERT INTO inventory.reservation_lines (
                    reservation_id, stock_item_id, tenant_id, quantity)
                VALUES (:reservationId, :stockItemId, :tenantId, :quantity)
                ON CONFLICT (reservation_id, stock_item_id) DO NOTHING
                """)
                .param("reservationId", reservationId)
                .param("stockItemId", stockItemId)
                .param("tenantId", tenantId)
                .param("quantity", quantity)
                .update();
    }

    public Optional<ReservationRow> findReservation(UUID tenantId, String ownerType, UUID ownerId) {
        return jdbc.sql("""
                SELECT id, status, expires_at FROM inventory.reservations
                WHERE tenant_id = :tenantId AND owner_type = :ownerType AND owner_id = :ownerId
                """)
                .param("tenantId", tenantId)
                .param("ownerType", ownerType)
                .param("ownerId", ownerId)
                .query((row, number) -> new ReservationRow(
                        row.getObject("id", UUID.class),
                        row.getString("status"),
                        row.getObject("expires_at", OffsetDateTime.class).toInstant()))
                .optional();
    }

    /**
     * Moves a held reservation to a terminal state.
     *
     * <p>The {@code status = 'HELD'} predicate is in the statement so a release
     * arriving after a commit cannot undo it, and two concurrent releases cannot
     * both believe they freed the stock.
     */
    public boolean transitionReservation(UUID tenantId, UUID reservationId, String toStatus, Instant now) {
        return jdbc.sql("""
                UPDATE inventory.reservations
                SET status = :toStatus, version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND status = 'HELD'
                """)
                        .param("tenantId", tenantId)
                        .param("id", reservationId)
                        .param("toStatus", toStatus)
                        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        .update()
                == 1;
    }

    /** Expires holds past their TTL, so abandoned carts stop holding stock. */
    public List<UUID> expireReservations(Instant now) {
        return jdbc.sql("""
                UPDATE inventory.reservations
                SET status = 'EXPIRED', updated_at = :now
                WHERE status = 'HELD' AND expires_at <= :now
                RETURNING id
                """)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .query(UUID.class)
                .list();
    }

    public long movementCount(UUID tenantId, UUID stockItemId) {
        return jdbc.sql("""
                SELECT count(*) FROM inventory.movements
                WHERE tenant_id = :tenantId AND stock_item_id = :stockItemId
                """)
                .param("tenantId", tenantId)
                .param("stockItemId", stockItemId)
                .query(Long.class)
                .single();
    }

    private static StockItemRow mapStockItem(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new StockItemRow(
                row.getObject("id", UUID.class),
                TrackingMode.valueOf(row.getString("tracking_mode")),
                (Boolean) row.getObject("binary_available"),
                row.getLong("position_sequence"));
    }

    public record StockItemRow(
            UUID stockItemId, TrackingMode trackingMode, Boolean binaryAvailable, long positionSequence) {}

    public record ReservationRow(UUID id, String status, Instant expiresAt) {}
}
