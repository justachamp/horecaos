package uz.horecaos.platform.inventory.infrastructure.persistence;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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

    /** Every column {@link #mapStockItem} reads, shared so the two queries below cannot drift apart. */
    private static final String STOCK_ITEM_COLUMNS = "s.id, s.brand_id, s.tracking_mode, s.default_quantity, "
            + "p.binary_available, p.on_hand_quantity, p.reserved_quantity, "
            + "p.last_reset_business_date, p.position_sequence";

    public Optional<StockItemRow> findStockItem(UUID tenantId, UUID locationId, UUID variantId) {
        return jdbc.sql("""
                SELECT %s
                FROM inventory.stock_items s
                JOIN inventory.positions p ON p.stock_item_id = s.id
                WHERE s.tenant_id = :tenantId AND s.location_id = :locationId
                  AND s.variant_id = :variantId AND s.status = 'ACTIVE'
                """.formatted(STOCK_ITEM_COLUMNS))
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
                SELECT s.variant_id, %s
                FROM inventory.stock_items s
                JOIN inventory.positions p ON p.stock_item_id = s.id
                WHERE s.tenant_id = :tenantId AND s.location_id = :locationId
                  AND s.variant_id = ANY(:ids) AND s.status = 'ACTIVE'
                """.formatted(STOCK_ITEM_COLUMNS))
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .param("ids", variantIds.toArray(UUID[]::new))
                .query((row, number) -> Map.entry(row.getObject("variant_id", UUID.class), mapStockItem(row, number)))
                .list()
                .forEach(entry -> byVariant.put(entry.getKey(), entry.getValue()));
        return byVariant;
    }

    /** Every stock item listed at a location, for the console's per-location stock page. */
    public List<StockItemListingRow> listStockItems(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT s.variant_id, %s
                FROM inventory.stock_items s
                JOIN inventory.positions p ON p.stock_item_id = s.id
                WHERE s.tenant_id = :tenantId AND s.location_id = :locationId AND s.status = 'ACTIVE'
                ORDER BY s.created_at
                """.formatted(STOCK_ITEM_COLUMNS))
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query((row, number) ->
                        new StockItemListingRow(row.getObject("variant_id", UUID.class), mapStockItem(row, number)))
                .list();
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
            @Nullable UUID actorId,
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
                row.getObject("brand_id", UUID.class),
                TrackingMode.valueOf(row.getString("tracking_mode")),
                (Boolean) row.getObject("binary_available"),
                row.getBigDecimal("on_hand_quantity"),
                row.getBigDecimal("reserved_quantity"),
                row.getBigDecimal("default_quantity"),
                row.getObject("last_reset_business_date", LocalDate.class),
                row.getLong("position_sequence"));
    }

    /**
     * @param onHandQuantity  {@code inventory.positions.on_hand_quantity} — 0 rather than
     *                        meaningful for a BINARY/UNTRACKED item, the same way {@code
     *                        binaryAvailable} is null rather than meaningful for a QUANTITY one
     * @param reservedQuantity {@code inventory.positions.reserved_quantity}, same caveat
     * @param defaultQuantity ADR 0017 QUANTITY branch (V0405): the daily reset target, or null
     *                        if this item has none configured
     * @param lastResetBusinessDate ADR 0017 QUANTITY branch (V0406): the tenant business date this
     *                              item was last reset on, or null if the scheduler has never reset it
     */
    public record StockItemRow(
            UUID stockItemId,
            UUID brandId,
            TrackingMode trackingMode,
            @Nullable Boolean binaryAvailable,
            BigDecimal onHandQuantity,
            BigDecimal reservedQuantity,
            @Nullable BigDecimal defaultQuantity,
            @Nullable LocalDate lastResetBusinessDate,
            long positionSequence) {

        /** {@code on_hand_quantity - reserved_quantity}: what a QUANTITY item can still sell. */
        public BigDecimal remainingQuantity() {
            return onHandQuantity.subtract(reservedQuantity);
        }
    }

    public record StockItemListingRow(UUID variantId, StockItemRow stockItem) {}

    public record ReservationRow(UUID id, String status, Instant expiresAt) {}

    // ----------------------------------------------------------- QUANTITY: reservation lifecycle

    /**
     * Atomically takes a quantity hold, refusing it where {@code on_hand -
     * reserved < quantity} (ADR 0017's atomic reservation algorithm, step 3).
     *
     * <p>The {@code WHERE} predicate is evaluated and the row locked in the same
     * statement, so two concurrent callers targeting the same stock item never
     * both read "enough is free" before either writes — the second one's
     * predicate is re-evaluated against the first's already-committed-or-not
     * row once Postgres's row lock releases, which is exactly the guarantee a
     * separate {@code SELECT} then {@code UPDATE} from Java could not make.
     *
     * @return true if this call reserved the quantity; false if it did not (and
     *         reserved nothing — a partial hold is not possible from one call)
     */
    public boolean tryReserveQuantity(UUID tenantId, UUID stockItemId, BigDecimal quantity, Instant now) {
        return jdbc.sql("""
                UPDATE inventory.positions
                SET reserved_quantity = reserved_quantity + :quantity,
                    position_sequence = position_sequence + 1,
                    version = version + 1,
                    updated_at = :now
                WHERE stock_item_id = :stockItemId AND tenant_id = :tenantId
                  AND on_hand_quantity - reserved_quantity >= :quantity
                """)
                        .param("stockItemId", stockItemId)
                        .param("tenantId", tenantId)
                        .param("quantity", quantity)
                        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        .update()
                == 1;
    }

    /**
     * Gives a held quantity back on release or expiry (ADR 0017: "releasing/
     * expiring reduces reserved only"). Floored at zero defensively — it should
     * never go negative given callers only ever release what {@link
     * #tryReserveQuantity} actually reserved, but a floor costs nothing and a
     * negative {@code reserved_quantity} would violate {@code
     * ck_position_quantities} outright rather than merely surprise a reader.
     */
    public void releaseReservedQuantity(UUID tenantId, UUID stockItemId, BigDecimal quantity, Instant now) {
        jdbc.sql("""
                UPDATE inventory.positions
                SET reserved_quantity = GREATEST(reserved_quantity - :quantity, 0),
                    position_sequence = position_sequence + 1,
                    version = version + 1,
                    updated_at = :now
                WHERE stock_item_id = :stockItemId AND tenant_id = :tenantId
                """)
                .param("stockItemId", stockItemId)
                .param("tenantId", tenantId)
                .param("quantity", quantity)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    /**
     * Turns a held quantity into a sale (ADR 0017: "committing... reduces both
     * on-hand and reserved in one transaction"), with a {@code SALE_COMMITMENT}
     * movement recording it — the ledger fact a bare position update alone
     * would leave unexplained.
     */
    public void commitQuantitySale(
            UUID tenantId,
            UUID stockItemId,
            BigDecimal quantity,
            String idempotencyKey,
            UUID sourceId,
            String actorType,
            @Nullable UUID actorId,
            Instant now) {
        jdbc.sql("""
                INSERT INTO inventory.movements (
                    id, tenant_id, brand_id, location_id, stock_item_id, sequence_number,
                    movement_type, quantity_delta, source_type, source_id, idempotency_key,
                    reason_code, actor_type, actor_id, occurred_at)
                SELECT :movementId, s.tenant_id, s.brand_id, s.location_id, s.id,
                       COALESCE((SELECT max(m.sequence_number) FROM inventory.movements m
                                 WHERE m.stock_item_id = s.id), 0) + 1,
                       'SALE_COMMITMENT', :delta, 'ORDER', :sourceId, :idempotencyKey,
                       'RESERVATION_COMMITTED', :actorType, :actorId, :now
                FROM inventory.stock_items s
                WHERE s.id = :stockItemId AND s.tenant_id = :tenantId
                ON CONFLICT (tenant_id, stock_item_id, idempotency_key) DO NOTHING
                """)
                .param("movementId", UUID.randomUUID())
                .param("stockItemId", stockItemId)
                .param("tenantId", tenantId)
                .param("delta", quantity.negate())
                .param("sourceId", sourceId)
                .param("idempotencyKey", idempotencyKey)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                UPDATE inventory.positions
                SET on_hand_quantity = GREATEST(on_hand_quantity - :quantity, 0),
                    reserved_quantity = GREATEST(reserved_quantity - :quantity, 0),
                    position_sequence = position_sequence + 1,
                    version = version + 1,
                    updated_at = :now
                WHERE stock_item_id = :stockItemId AND tenant_id = :tenantId
                """)
                .param("stockItemId", stockItemId)
                .param("tenantId", tenantId)
                .param("quantity", quantity)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    /** One reservation's own QUANTITY-tracked lines, for an ordinary {@code commit}/{@code release}. */
    public List<QuantityReservationLineRow> findQuantityReservationLines(UUID tenantId, UUID reservationId) {
        return jdbc.sql("""
                SELECT rl.tenant_id, rl.stock_item_id, rl.quantity
                FROM inventory.reservation_lines rl
                JOIN inventory.stock_items si ON si.id = rl.stock_item_id
                WHERE rl.tenant_id = :tenantId AND rl.reservation_id = :reservationId
                  AND si.tracking_mode = 'QUANTITY'
                """)
                .param("tenantId", tenantId)
                .param("reservationId", reservationId)
                .query((row, number) -> new QuantityReservationLineRow(
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("stock_item_id", UUID.class),
                        row.getBigDecimal("quantity")))
                .list();
    }

    /**
     * The QUANTITY-tracked lines of every reservation a sweep tick just
     * expired, cross-tenant like {@link #expireReservations} itself — see
     * {@code InventoryService#expireStaleReservations}'s own doc for why that
     * is a deliberate, platform-bypass-bound choice here rather than an
     * omitted predicate.
     */
    public List<QuantityReservationLineRow> findQuantityReservationLinesForReservations(List<UUID> reservationIds) {
        if (reservationIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT rl.tenant_id, rl.stock_item_id, rl.quantity
                FROM inventory.reservation_lines rl
                JOIN inventory.stock_items si ON si.id = rl.stock_item_id
                WHERE rl.reservation_id = ANY(:ids) AND si.tracking_mode = 'QUANTITY'
                """)
                .param("ids", reservationIds.toArray(UUID[]::new))
                .query((row, number) -> new QuantityReservationLineRow(
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("stock_item_id", UUID.class),
                        row.getBigDecimal("quantity")))
                .list();
    }

    public record QuantityReservationLineRow(UUID tenantId, UUID stockItemId, BigDecimal quantity) {}

    // --------------------------------------------------------- QUANTITY: operator on-hand write

    /**
     * An operator's manual on-hand correction, with a {@code CORRECTION}
     * movement recording the delta and its reason — {@link
     * uz.horecaos.platform.inventory.application.InventoryService#setOnHandQuantity}'s
     * own ADR 0027 audit fact is the "who" half; this is the "what happened to
     * stock" half neither a position update alone nor the audit trail alone
     * would carry.
     */
    public void recordOnHandCorrection(
            UUID tenantId,
            UUID stockItemId,
            BigDecimal newOnHand,
            BigDecimal delta,
            String idempotencyKey,
            String reasonCode,
            String actorType,
            @Nullable UUID actorId,
            Instant now) {
        jdbc.sql("""
                INSERT INTO inventory.movements (
                    id, tenant_id, brand_id, location_id, stock_item_id, sequence_number,
                    movement_type, quantity_delta, source_type, idempotency_key, reason_code,
                    actor_type, actor_id, occurred_at)
                SELECT :movementId, s.tenant_id, s.brand_id, s.location_id, s.id,
                       COALESCE((SELECT max(m.sequence_number) FROM inventory.movements m
                                 WHERE m.stock_item_id = s.id), 0) + 1,
                       'CORRECTION', :delta, 'OPERATOR', :idempotencyKey, :reason,
                       :actorType, :actorId, :now
                FROM inventory.stock_items s
                WHERE s.id = :stockItemId AND s.tenant_id = :tenantId
                ON CONFLICT (tenant_id, stock_item_id, idempotency_key) DO NOTHING
                """)
                .param("movementId", UUID.randomUUID())
                .param("stockItemId", stockItemId)
                .param("tenantId", tenantId)
                .param("delta", delta)
                .param("idempotencyKey", idempotencyKey)
                .param("reason", reasonCode)
                .param("actorType", actorType)
                .param("actorId", actorId)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                UPDATE inventory.positions
                SET on_hand_quantity = :newOnHand,
                    position_sequence = position_sequence + 1,
                    version = version + 1,
                    updated_at = :now
                WHERE stock_item_id = :stockItemId AND tenant_id = :tenantId
                """)
                .param("stockItemId", stockItemId)
                .param("tenantId", tenantId)
                .param("newOnHand", newOnHand)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    /** ADR 0017 QUANTITY branch (V0405): the per-item daily reset target, or null to disable it. */
    public void setDefaultQuantity(UUID tenantId, UUID stockItemId, @Nullable BigDecimal defaultQuantity, Instant now) {
        jdbc.sql("""
                UPDATE inventory.stock_items
                SET default_quantity = :defaultQuantity, version = version + 1, updated_at = :now
                WHERE id = :stockItemId AND tenant_id = :tenantId
                """)
                .param("stockItemId", stockItemId)
                .param("tenantId", tenantId)
                .param("defaultQuantity", defaultQuantity)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    // ---------------------------------------------------- QUANTITY: the daily default/reset job

    /** Every tenant with at least one active QUANTITY item that has a daily default configured. */
    public List<UUID> tenantIdsWithQuantityDefaults() {
        return jdbc.sql("""
                SELECT DISTINCT s.tenant_id
                FROM inventory.stock_items s
                WHERE s.tracking_mode = 'QUANTITY' AND s.status = 'ACTIVE' AND s.default_quantity IS NOT NULL
                """).query(UUID.class).list();
    }

    /** One tenant's stock items due for their daily reset on {@code businessDate}. */
    public List<UUID> dueQuantityResetStockItems(UUID tenantId, LocalDate businessDate) {
        return jdbc.sql("""
                SELECT s.id
                FROM inventory.stock_items s
                JOIN inventory.positions p ON p.stock_item_id = s.id
                WHERE s.tenant_id = :tenantId AND s.tracking_mode = 'QUANTITY' AND s.status = 'ACTIVE'
                  AND s.default_quantity IS NOT NULL
                  AND (p.last_reset_business_date IS NULL OR p.last_reset_business_date < :businessDate)
                """)
                .param("tenantId", tenantId)
                .param("businessDate", businessDate)
                .query(UUID.class)
                .list();
    }

    /**
     * Resets one stock item to its configured default, if it is still due —
     * the same "still due" predicate {@link #dueQuantityResetStockItems} used
     * to find it, re-checked and locked in the same statement so two scheduler
     * ticks (this platform's own, or a second replica's) racing the identical
     * item cannot both reset it for the same business date.
     *
     * @return true if this call reset the item; false if it was no longer due
     *         (already reset by a concurrent caller, or its default was
     *         cleared between the worklist read and this call)
     */
    public boolean resetIfDue(UUID tenantId, UUID stockItemId, LocalDate businessDate, Instant now) {
        Optional<BigDecimal[]> due = jdbc.sql("""
                SELECT p.on_hand_quantity, s.default_quantity
                FROM inventory.positions p
                JOIN inventory.stock_items s ON s.id = p.stock_item_id
                WHERE p.stock_item_id = :stockItemId AND p.tenant_id = :tenantId
                  AND s.default_quantity IS NOT NULL
                  AND (p.last_reset_business_date IS NULL OR p.last_reset_business_date < :businessDate)
                FOR UPDATE OF p
                """)
                .param("stockItemId", stockItemId)
                .param("tenantId", tenantId)
                .param("businessDate", businessDate)
                .query((row, number) ->
                        new BigDecimal[] {row.getBigDecimal("on_hand_quantity"), row.getBigDecimal("default_quantity")})
                .optional();
        if (due.isEmpty()) {
            return false;
        }
        BigDecimal onHand = due.get()[0];
        BigDecimal target = due.get()[1];
        BigDecimal delta = target.subtract(onHand);

        jdbc.sql("""
                INSERT INTO inventory.movements (
                    id, tenant_id, brand_id, location_id, stock_item_id, sequence_number,
                    movement_type, quantity_delta, source_type, idempotency_key, reason_code,
                    actor_type, occurred_at)
                SELECT :movementId, s.tenant_id, s.brand_id, s.location_id, s.id,
                       COALESCE((SELECT max(m.sequence_number) FROM inventory.movements m
                                 WHERE m.stock_item_id = s.id), 0) + 1,
                       'CORRECTION', :delta, 'SYSTEM_JOB', :idempotencyKey, 'DAILY_RESET',
                       'SYSTEM_JOB', :now
                FROM inventory.stock_items s
                WHERE s.id = :stockItemId AND s.tenant_id = :tenantId
                ON CONFLICT (tenant_id, stock_item_id, idempotency_key) DO NOTHING
                """)
                .param("movementId", UUID.randomUUID())
                .param("stockItemId", stockItemId)
                .param("tenantId", tenantId)
                .param("delta", delta)
                .param("idempotencyKey", "reset:" + businessDate)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                UPDATE inventory.positions
                SET on_hand_quantity = :target,
                    last_reset_business_date = :businessDate,
                    position_sequence = position_sequence + 1,
                    version = version + 1,
                    updated_at = :now
                WHERE stock_item_id = :stockItemId AND tenant_id = :tenantId
                """)
                .param("stockItemId", stockItemId)
                .param("tenantId", tenantId)
                .param("target", target)
                .param("businessDate", businessDate)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
        return true;
    }

    // ------------------------------------------------ QUANTITY: per-channel-type stop thresholds

    public Optional<BigDecimal> findChannelStopThreshold(UUID tenantId, UUID stockItemId, String channelSystemType) {
        return jdbc.sql("""
                SELECT stop_at_or_below FROM inventory.channel_stop_thresholds
                WHERE tenant_id = :tenantId AND stock_item_id = :stockItemId AND channel_system_type = :channelType
                """)
                .param("tenantId", tenantId)
                .param("stockItemId", stockItemId)
                .param("channelType", channelSystemType)
                .query(BigDecimal.class)
                .optional();
    }

    public List<ChannelStopThresholdRow> listChannelStopThresholds(UUID tenantId, UUID locationId) {
        return jdbc.sql("""
                SELECT stock_item_id, channel_system_type, stop_at_or_below
                FROM inventory.channel_stop_thresholds
                WHERE tenant_id = :tenantId AND location_id = :locationId
                """)
                .param("tenantId", tenantId)
                .param("locationId", locationId)
                .query((row, number) -> new ChannelStopThresholdRow(
                        row.getObject("stock_item_id", UUID.class),
                        row.getString("channel_system_type"),
                        row.getBigDecimal("stop_at_or_below")))
                .list();
    }

    public void upsertChannelStopThreshold(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            UUID stockItemId,
            String channelSystemType,
            BigDecimal stopAtOrBelow,
            Instant now) {
        jdbc.sql("""
                INSERT INTO inventory.channel_stop_thresholds (
                    id, tenant_id, brand_id, location_id, stock_item_id, channel_system_type,
                    stop_at_or_below, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :stockItemId, :channelType,
                    :threshold, :now, :now)
                ON CONFLICT (tenant_id, stock_item_id, channel_system_type)
                DO UPDATE SET stop_at_or_below = EXCLUDED.stop_at_or_below,
                              version = inventory.channel_stop_thresholds.version + 1,
                              updated_at = EXCLUDED.updated_at
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", locationId)
                .param("stockItemId", stockItemId)
                .param("channelType", channelSystemType)
                .param("threshold", stopAtOrBelow)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }

    /** @return true if a threshold existed and was removed */
    public boolean deleteChannelStopThreshold(UUID tenantId, UUID stockItemId, String channelSystemType) {
        return jdbc.sql("""
                DELETE FROM inventory.channel_stop_thresholds
                WHERE tenant_id = :tenantId AND stock_item_id = :stockItemId AND channel_system_type = :channelType
                """)
                        .param("tenantId", tenantId)
                        .param("stockItemId", stockItemId)
                        .param("channelType", channelSystemType)
                        .update()
                == 1;
    }

    public record ChannelStopThresholdRow(UUID stockItemId, String channelSystemType, BigDecimal stopAtOrBelow) {}
}
