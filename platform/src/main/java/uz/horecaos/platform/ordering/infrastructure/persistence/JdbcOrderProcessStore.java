package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Durable process-manager state, one row per order per concern (ADR 0019).
 *
 * <p>The work item and the business change are written in the same transaction,
 * so a process manager can never be asked to act on an order state that was
 * rolled back, and an order can never reach a state whose consequence nobody was
 * asked to carry out.
 */
@Repository
public class JdbcOrderProcessStore {

    private final JdbcClient jdbc;

    public JdbcOrderProcessStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records what a process must do next.
     *
     * <p>An upsert: an order that is confirmed and then cancelled hands the same
     * inventory process a second instruction, and the row carries the latest one
     * rather than accumulating a queue whose order nobody controls.
     */
    public void enqueue(UUID orderId, UUID tenantId, String processName, String checkpointJson,
            Instant now) {
        jdbc.sql("""
                INSERT INTO ordering.order_process_states (
                    order_id, process_name, tenant_id, status, checkpoint,
                    attempt_count, next_attempt_at, created_at, updated_at)
                VALUES (:orderId, :process, :tenantId, 'WAITING', CAST(:checkpoint AS jsonb),
                    0, :now, :now, :now)
                ON CONFLICT (order_id, process_name) DO UPDATE
                SET status = 'WAITING',
                    checkpoint = EXCLUDED.checkpoint,
                    attempt_count = 0,
                    next_attempt_at = EXCLUDED.next_attempt_at,
                    last_error = NULL,
                    version = ordering.order_process_states.version + 1,
                    updated_at = EXCLUDED.updated_at
                """)
                .param("orderId", orderId).param("process", processName)
                .param("tenantId", tenantId).param("checkpoint", checkpointJson)
                .param("now", utc(now))
                .update();
    }

    /**
     * Claims runnable work for one process.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} so two workers never run one order's
     * process twice concurrently, and one slow order does not stall every other.
     */
    public List<ProcessRow> claim(String processName, Instant now, int batchSize) {
        return jdbc.sql("""
                SELECT order_id, process_name, tenant_id, status, checkpoint::text AS checkpoint,
                       attempt_count, version
                FROM ordering.order_process_states
                WHERE process_name = :process
                  AND status IN ('WAITING', 'FAILED_RETRYABLE')
                  AND (next_attempt_at IS NULL OR next_attempt_at <= :now)
                ORDER BY next_attempt_at NULLS FIRST
                FOR UPDATE SKIP LOCKED
                LIMIT :batchSize
                """)
                .param("process", processName).param("now", utc(now))
                .param("batchSize", batchSize)
                .query((row, number) -> new ProcessRow(
                        row.getObject("order_id", UUID.class),
                        row.getString("process_name"),
                        row.getObject("tenant_id", UUID.class),
                        row.getString("status"),
                        row.getString("checkpoint"),
                        row.getInt("attempt_count"),
                        row.getInt("version")))
                .list();
    }

    public Optional<ProcessRow> find(UUID orderId, String processName) {
        return jdbc.sql("""
                SELECT order_id, process_name, tenant_id, status, checkpoint::text AS checkpoint,
                       attempt_count, version
                FROM ordering.order_process_states
                WHERE order_id = :orderId AND process_name = :process
                """)
                .param("orderId", orderId).param("process", processName)
                .query((row, number) -> new ProcessRow(
                        row.getObject("order_id", UUID.class),
                        row.getString("process_name"),
                        row.getObject("tenant_id", UUID.class),
                        row.getString("status"),
                        row.getString("checkpoint"),
                        row.getInt("attempt_count"),
                        row.getInt("version")))
                .optional();
    }

    /**
     * Settles a process attempt.
     *
     * <p>The expected version is in the statement. A worker whose row was
     * re-enqueued while it was working loses here rather than overwriting the new
     * instruction with the outcome of the old one — which is how a process
     * manager reports "committed" for an order that has since been cancelled.
     */
    public boolean settle(UUID orderId, String processName, int expectedVersion, String status,
            String checkpointJson, Instant nextAttemptAt, String lastError, Instant now) {
        return jdbc.sql("""
                UPDATE ordering.order_process_states
                SET status = :status,
                    checkpoint = CAST(:checkpoint AS jsonb),
                    attempt_count = attempt_count + 1,
                    next_attempt_at = :nextAttemptAt,
                    last_error = :lastError,
                    version = version + 1,
                    updated_at = :now
                WHERE order_id = :orderId AND process_name = :process AND version = :expectedVersion
                """)
                .param("orderId", orderId).param("process", processName)
                .param("expectedVersion", expectedVersion).param("status", status)
                .param("checkpoint", checkpointJson)
                .param("nextAttemptAt", nextAttemptAt == null ? null : utc(nextAttemptAt))
                .param("lastError", lastError).param("now", utc(now))
                .update() == 1;
    }

    /** Everything an operator needs to answer "which processes are stuck". */
    public List<ProcessRow> stuck(UUID tenantId, int limit) {
        return jdbc.sql("""
                SELECT order_id, process_name, tenant_id, status, checkpoint::text AS checkpoint,
                       attempt_count, version
                FROM ordering.order_process_states
                WHERE tenant_id = :tenantId
                  AND status IN ('FAILED_RETRYABLE', 'MANUAL_ACTION_REQUIRED')
                ORDER BY updated_at
                LIMIT :limit
                """)
                .param("tenantId", tenantId).param("limit", limit)
                .query((row, number) -> new ProcessRow(
                        row.getObject("order_id", UUID.class),
                        row.getString("process_name"),
                        row.getObject("tenant_id", UUID.class),
                        row.getString("status"),
                        row.getString("checkpoint"),
                        row.getInt("attempt_count"),
                        row.getInt("version")))
                .list();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record ProcessRow(UUID orderId, String processName, UUID tenantId, String status,
            String checkpointJson, int attemptCount, int version) { }
}
