package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
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
import uz.horecaos.platform.ordering.domain.BulkActionType;
import uz.horecaos.platform.ordering.domain.BulkItemStatus;

/**
 * Persistence for ADR 0039 bulk actions.
 *
 * <p>Two tables, mirroring {@link JdbcOrderAmendmentStore}'s own split between
 * the request and its commands: {@code bulk_operations} names one operator
 * request under one {@code Idempotency-Key}, and {@code bulk_operation_items}
 * is the per-order ledger — never collapsed into one fact, so a bulk of two
 * hundred with three failures still names which three.
 */
@Repository
public class JdbcBulkOperationStore {

    private final JdbcClient jdbc;

    public JdbcBulkOperationStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(NewBulkOperation operation) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", operation.id());
        params.put("tenantId", operation.tenantId());
        params.put("brandId", operation.brandId());
        params.put("locationId", operation.locationId());
        params.put("actionType", operation.actionType().name());
        params.put("requestedCount", operation.requestedCount());
        params.put("idempotencyKey", operation.idempotencyKey());
        params.put("actorType", operation.createdByActorType());
        params.put("actorId", operation.createdByActorId());
        params.put("now", utc(operation.createdAt()));

        jdbc.sql("""
                INSERT INTO ordering.bulk_operations (
                    id, tenant_id, brand_id, location_id, action_type, requested_count,
                    idempotency_key, created_by_actor_type, created_by_actor_id, created_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :actionType, :requestedCount,
                    :idempotencyKey, :actorType, :actorId, :now)
                """).params(params).update();
    }

    public void markCompleted(UUID tenantId, UUID bulkOperationId, Instant now) {
        jdbc.sql("""
                UPDATE ordering.bulk_operations SET completed_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND completed_at IS NULL
                """)
                .param("tenantId", tenantId)
                .param("id", bulkOperationId)
                .param("now", utc(now))
                .update();
    }

    public Optional<BulkOperationRow> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        return jdbc.sql(SELECT_OPERATION + " WHERE tenant_id = :tenantId AND idempotency_key = :key")
                .param("tenantId", tenantId)
                .param("key", idempotencyKey)
                .query(JdbcBulkOperationStore::mapOperation)
                .optional();
    }

    /**
     * Stakes a claim on one order for this bulk operation, if nothing already
     * has. A second insert for the same pair — a concurrent worker, or a
     * caller who resubmitted the identical bulk key — changes nothing rather
     * than failing: the {@code (bulk_operation_id, order_id)} primary key is
     * exactly ADR 0039's {@code {bulkKey}:{orderId}} derivation, so the row
     * already there is the record that matters.
     */
    public void insertPendingItem(UUID tenantId, UUID bulkOperationId, UUID orderId) {
        jdbc.sql("""
                INSERT INTO ordering.bulk_operation_items (bulk_operation_id, order_id, tenant_id, item_status)
                VALUES (:bulkOperationId, :orderId, :tenantId, 'PENDING')
                ON CONFLICT (bulk_operation_id, order_id) DO NOTHING
                """)
                .param("bulkOperationId", bulkOperationId)
                .param("orderId", orderId)
                .param("tenantId", tenantId)
                .update();
    }

    public void markItemApplied(
            UUID tenantId, UUID bulkOperationId, UUID orderId, int resultingOrderVersion, Instant now) {
        jdbc.sql("""
                UPDATE ordering.bulk_operation_items
                SET item_status = 'APPLIED', resulting_order_version = :version, decided_at = :now
                WHERE tenant_id = :tenantId AND bulk_operation_id = :bulkOperationId AND order_id = :orderId
                  AND item_status = 'PENDING'
                """)
                .param("tenantId", tenantId)
                .param("bulkOperationId", bulkOperationId)
                .param("orderId", orderId)
                .param("version", resultingOrderVersion)
                .param("now", utc(now))
                .update();
    }

    public void markItemFailed(UUID tenantId, UUID bulkOperationId, UUID orderId, String problemCode, Instant now) {
        jdbc.sql("""
                UPDATE ordering.bulk_operation_items
                SET item_status = 'FAILED', item_problem_code = :problemCode, decided_at = :now
                WHERE tenant_id = :tenantId AND bulk_operation_id = :bulkOperationId AND order_id = :orderId
                  AND item_status = 'PENDING'
                """)
                .param("tenantId", tenantId)
                .param("bulkOperationId", bulkOperationId)
                .param("orderId", orderId)
                .param("problemCode", problemCode)
                .param("now", utc(now))
                .update();
    }

    public Optional<BulkItemRow> findItem(UUID tenantId, UUID bulkOperationId, UUID orderId) {
        return jdbc.sql(SELECT_ITEM + """
                 WHERE tenant_id = :tenantId AND bulk_operation_id = :bulkOperationId AND order_id = :orderId
                """)
                .param("tenantId", tenantId)
                .param("bulkOperationId", bulkOperationId)
                .param("orderId", orderId)
                .query(JdbcBulkOperationStore::mapItem)
                .optional();
    }

    public List<BulkItemRow> items(UUID tenantId, UUID bulkOperationId) {
        return jdbc.sql(SELECT_ITEM + """
                 WHERE tenant_id = :tenantId AND bulk_operation_id = :bulkOperationId
                 ORDER BY order_id
                """)
                .param("tenantId", tenantId)
                .param("bulkOperationId", bulkOperationId)
                .query(JdbcBulkOperationStore::mapItem)
                .list();
    }

    private static final String SELECT_OPERATION = """
            SELECT id, tenant_id, brand_id, location_id, action_type, requested_count,
                   idempotency_key, created_by_actor_type, created_by_actor_id, created_at, completed_at
            FROM ordering.bulk_operations""";

    private static final String SELECT_ITEM = """
            SELECT bulk_operation_id, order_id, item_status, item_problem_code,
                   resulting_order_version, decided_at
            FROM ordering.bulk_operation_items""";

    private static BulkOperationRow mapOperation(ResultSet row, int number) throws SQLException {
        return new BulkOperationRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("location_id", UUID.class),
                BulkActionType.valueOf(row.getString("action_type")),
                row.getInt("requested_count"),
                row.getString("idempotency_key"),
                row.getString("created_by_actor_type"),
                row.getString("created_by_actor_id"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                instantOrNull(row, "completed_at"));
    }

    private static BulkItemRow mapItem(ResultSet row, int number) throws SQLException {
        return new BulkItemRow(
                row.getObject("bulk_operation_id", UUID.class),
                row.getObject("order_id", UUID.class),
                BulkItemStatus.valueOf(row.getString("item_status")),
                row.getString("item_problem_code"),
                row.getObject("resulting_order_version", Integer.class),
                instantOrNull(row, "decided_at"));
    }

    private static @Nullable Instant instantOrNull(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record NewBulkOperation(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            BulkActionType actionType,
            int requestedCount,
            String idempotencyKey,
            String createdByActorType,
            String createdByActorId,
            Instant createdAt) {}

    public record BulkOperationRow(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            BulkActionType actionType,
            int requestedCount,
            String idempotencyKey,
            String createdByActorType,
            String createdByActorId,
            Instant createdAt,
            @Nullable Instant completedAt) {}

    public record BulkItemRow(
            UUID bulkOperationId,
            UUID orderId,
            BulkItemStatus itemStatus,
            @Nullable String itemProblemCode,
            @Nullable Integer resultingOrderVersion,
            @Nullable Instant decidedAt) {}
}
