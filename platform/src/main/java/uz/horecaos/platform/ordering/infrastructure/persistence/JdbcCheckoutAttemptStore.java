package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The transactional checkout idempotency record (ADR 0019, step 1).
 *
 * <p>Step 1 of the checkout transaction is "lock the idempotency record and
 * return the prior result if completed". That is what these two statements do,
 * and the ordering matters: the insert is attempted first, so two concurrent
 * checkouts with one key contend on the unique index. The loser blocks until the
 * winner commits and then reads its settled result, rather than proceeding in
 * parallel and discovering the collision after it has already consumed a
 * reservation and a capacity slot.
 */
@Repository
public class JdbcCheckoutAttemptStore {

    private final JdbcClient jdbc;

    public JdbcCheckoutAttemptStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Claims the key for this attempt.
     *
     * @return true when this caller claimed it; false when a record already
     *         exists, in which case {@link #findForUpdate} returns the outcome
     */
    public boolean claim(
            UUID attemptId,
            UUID tenantId,
            String idempotencyKey,
            UUID cartId,
            UUID quoteId,
            String requestFingerprint,
            Instant now) {
        return jdbc.sql("""
                INSERT INTO ordering.checkout_attempts (
                    id, tenant_id, idempotency_key, cart_id, quote_id,
                    request_fingerprint, status, created_at)
                VALUES (:id, :tenantId, :key, :cartId, :quoteId, :fingerprint,
                    'IN_PROGRESS', :now)
                ON CONFLICT (tenant_id, idempotency_key) DO NOTHING
                """)
                        .param("id", attemptId)
                        .param("tenantId", tenantId)
                        .param("key", idempotencyKey)
                        .param("cartId", cartId)
                        .param("quoteId", quoteId)
                        .param("fingerprint", requestFingerprint)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * Reads the existing attempt, taking the row lock.
     *
     * <p>{@code FOR UPDATE} because the caller is about to branch on the status:
     * without the lock it could read {@code IN_PROGRESS} from an attempt that
     * commits a microsecond later and report "still running" for an order that
     * exists.
     */
    public Optional<AttemptRow> findForUpdate(UUID tenantId, String idempotencyKey) {
        return jdbc.sql("""
                SELECT id, cart_id, quote_id, request_fingerprint, status, order_id,
                       outcome_code, outcome_detail
                FROM ordering.checkout_attempts
                WHERE tenant_id = :tenantId AND idempotency_key = :key
                FOR UPDATE
                """)
                .param("tenantId", tenantId)
                .param("key", idempotencyKey)
                .query((row, number) -> new AttemptRow(
                        row.getObject("id", UUID.class),
                        row.getObject("cart_id", UUID.class),
                        row.getObject("quote_id", UUID.class),
                        row.getString("request_fingerprint"),
                        row.getString("status"),
                        row.getObject("order_id", UUID.class),
                        row.getString("outcome_code"),
                        row.getString("outcome_detail")))
                .optional();
    }

    /**
     * Settles the attempt, whichever way it went.
     *
     * <p>A business rejection is settled too. ADR 0019 is explicit: retrying must
     * return the same rejection rather than running again against a cart that has
     * since changed underneath it. Only an unexpected failure leaves the record
     * unsettled, and that happens by the transaction rolling back rather than by
     * anything written here.
     */
    public void complete(
            UUID attemptId,
            @Nullable UUID orderId,
            String outcomeCode,
            @Nullable String outcomeDetail,
            Instant now) {
        jdbc.sql("""
                UPDATE ordering.checkout_attempts
                SET status = 'COMPLETED', order_id = :orderId, outcome_code = :code,
                    outcome_detail = :detail, completed_at = :now
                WHERE id = :id
                """)
                .param("id", attemptId)
                .param("orderId", orderId)
                .param("code", outcomeCode)
                .param("detail", outcomeDetail)
                .param("now", utc(now))
                .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record AttemptRow(
            UUID attemptId,
            UUID cartId,
            UUID quoteId,
            String requestFingerprint,
            String status,
            UUID orderId,
            String outcomeCode,
            String outcomeDetail) {}
}
