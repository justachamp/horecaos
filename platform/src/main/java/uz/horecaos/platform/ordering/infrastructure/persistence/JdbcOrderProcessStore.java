package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
    public void enqueue(UUID orderId, UUID tenantId, String processName, String checkpointJson, Instant now) {
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
                .param("orderId", orderId)
                .param("process", processName)
                .param("tenantId", tenantId)
                .param("checkpoint", checkpointJson)
                .param("now", utc(now))
                .update();
    }

    /**
     * Records the ledger movement a disposal wrote for an order's outcome (ADR 0169).
     *
     * <p>{@code ordering.order_outcomes} is append-only for the application role, with the one exception V0542
     * grants: this column, which is null when the outcome is written and is filled once, by the process that
     * carries the stock consequence out. It names the first movement of the disposal; the rest are found by the
     * order and the disposal's key. Never overwritten: a repeat finds the column set and writes nothing.
     */
    public void recordOutcomeMovement(UUID tenantId, UUID orderId, UUID movementId) {
        jdbc.sql("""
                UPDATE ordering.order_outcomes
                   SET inventory_movement_id = :movementId
                 WHERE tenant_id = :tenantId AND order_id = :orderId AND inventory_movement_id IS NULL
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .param("movementId", movementId)
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
                .param("process", processName)
                .param("now", utc(now))
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

    /**
     * Takes, without waiting, the row locks that ending an unpaid order needs and that a payment capture for
     * the same order also takes (ADR 0170 Decisions 13 to 17).
     *
     * <p><strong>Lock order.</strong> A capture takes the payment attempt row first, then the settlement,
     * then moves the order, then settles this order's process row. The sweep holds the process row from the
     * moment it claims it, and ending the order then asks for the order and the settlement, which is the
     * opposite way round: left to wait, the two meet in a cycle and the database ends one of them, and the
     * one it ends is as likely to be the capture, whose money has already moved at the provider. So the
     * sweep never waits. It takes the attempt rows the capture takes first and the order row next, both with
     * {@code SKIP LOCKED}, and when either is held by someone else it does not end the order on this tick:
     * a capture in flight is about to resolve the order itself, and whatever else holds it can be waited
     * for by the next tick, which is not holding a lock on anything.
     *
     * <p>Holding the attempt rows is also what keeps a capture that begins <em>after</em> this point from
     * getting ahead of the sweep: it waits at the first row it needs, with nothing of the sweep's held, and
     * finds the order ended when it resumes (the late-capture path, ADR 0170 Decision 17).
     *
     * @return true when every payment attempt of the order, and the order itself, are now held by this
     *         transaction until it ends; false when another transaction holds any of them
     */
    public boolean tryLockForLapse(UUID tenantId, UUID orderId) {
        long attemptsHeldByOthers = jdbc.sql("""
                        WITH locked AS MATERIALIZED (
                            SELECT a.id
                            FROM payments.payment_attempts a
                            JOIN payments.payment_intents i ON i.id = a.intent_id AND i.tenant_id = a.tenant_id
                            WHERE i.tenant_id = :tenantId AND i.order_id = :orderId
                            FOR UPDATE OF a SKIP LOCKED
                        )
                        SELECT count(*)
                        FROM payments.payment_attempts a
                        JOIN payments.payment_intents i ON i.id = a.intent_id AND i.tenant_id = a.tenant_id
                        WHERE i.tenant_id = :tenantId AND i.order_id = :orderId
                          AND a.id NOT IN (SELECT id FROM locked)
                        """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(Long.class)
                .single();
        if (attemptsHeldByOthers > 0) {
            return false;
        }
        return jdbc.sql("""
                        SELECT id FROM ordering.orders
                        WHERE tenant_id = :tenantId AND id = :orderId
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(UUID.class)
                .optional()
                .isPresent();
    }

    /**
     * Whether any payment attempt on the order is {@code UNCERTAIN} (ADR 0170 Decision 15, ADR 0007).
     *
     * <p>A question about money is owned by its resolver, and an unpaid order is never ended over an
     * unanswered one: the attempt may have been captured at the provider with the answer lost. Reads
     * {@code payments.*} directly, the established crossing {@code JdbcOrderStore}'s own order search already
     * makes for the same two tables, and only a status: no amount, no provider reference.
     */
    public boolean hasUncertainPaymentAttempt(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                        SELECT count(*)
                        FROM payments.payment_attempts a
                        JOIN payments.payment_intents i ON i.id = a.intent_id AND i.tenant_id = a.tenant_id
                        WHERE i.tenant_id = :tenantId AND i.order_id = :orderId AND a.status = 'UNCERTAIN'
                        """)
                        .param("tenantId", tenantId)
                        .param("orderId", orderId)
                        .query(Long.class)
                        .single()
                > 0;
    }

    public Optional<ProcessRow> find(UUID orderId, String processName) {
        return jdbc.sql("""
                SELECT order_id, process_name, tenant_id, status, checkpoint::text AS checkpoint,
                       attempt_count, version
                FROM ordering.order_process_states
                WHERE order_id = :orderId AND process_name = :process
                """)
                .param("orderId", orderId)
                .param("process", processName)
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
    public boolean settle(
            UUID orderId,
            String processName,
            int expectedVersion,
            String status,
            String checkpointJson,
            @Nullable Instant nextAttemptAt,
            @Nullable String lastError,
            Instant now) {
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
                        .param("orderId", orderId)
                        .param("process", processName)
                        .param("expectedVersion", expectedVersion)
                        .param("status", status)
                        .param("checkpoint", checkpointJson)
                        .param("nextAttemptAt", nextAttemptAt == null ? null : utc(nextAttemptAt))
                        .param("lastError", lastError)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * Marks an active process {@code COMPLETED} without a prior claim.
     *
     * <p>{@link #settle} names the version a claim already read; this is for the
     * caller that has none, because its own state change is what resolved the
     * process rather than a scheduled worker's batch. {@code ORDER_PAYMENT} is
     * the case: the order's own transition out of {@code PAYMENT_AUTHORIZING}
     * settles the row in the same transaction, with no {@link #claim} step
     * before it to hand back a version.
     *
     * <p>Guarded by status instead: a row already {@code COMPLETED} or {@code
     * COMPENSATED} is left untouched, so a duplicate delivery of the fact that
     * resolved it — a replayed payment capture, a cancellation racing a capture —
     * settles nothing a second time.
     */
    public boolean settleCompletedIfActive(UUID orderId, String processName, String checkpointJson, Instant now) {
        return jdbc.sql("""
                UPDATE ordering.order_process_states
                SET status = 'COMPLETED',
                    checkpoint = CAST(:checkpoint AS jsonb),
                    attempt_count = attempt_count + 1,
                    next_attempt_at = NULL,
                    last_error = NULL,
                    version = version + 1,
                    updated_at = :now
                WHERE order_id = :orderId AND process_name = :process
                  AND status NOT IN ('COMPLETED', 'COMPENSATED')
                """)
                        .param("orderId", orderId)
                        .param("process", processName)
                        .param("checkpoint", checkpointJson)
                        .param("now", utc(now))
                        .update()
                == 1;
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
                .param("tenantId", tenantId)
                .param("limit", limit)
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

    public record ProcessRow(
            UUID orderId,
            String processName,
            UUID tenantId,
            String status,
            String checkpointJson,
            int attemptCount,
            int version) {}
}
