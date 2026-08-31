package uz.horecaos.platform.integration.provider.telegram;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The durable, multi-replica per-chat send lease ADR 0058 names as "the
 * OutboxRelay lease pattern", scoped to one chat instead of one outbox row.
 *
 * <p>{@code ADR 0033}'s in-process rate limiter cannot coordinate two JVMs, so
 * mutual exclusion on "is a Bot API call to this chat in flight right now" has to
 * be a database row every replica contends for. Combined with
 * {@code NotificationWorker}'s own claim ordering (oldest-due first, one row at a
 * time per worker thread) and the ordering precondition in
 * {@code NotificationDispatchService} (an older message to the same chat must
 * settle first), this lease is what stops two replicas from both being mid-send
 * to the same chat at once — the case that could otherwise interleave two
 * messages regardless of which was older.
 */
@Repository
public class TelegramChatLockService {

    private final JdbcClient jdbc;
    private final Clock clock;

    public TelegramChatLockService(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Attempts to claim the chat. An upsert rather than read-then-write: two
     * replicas racing the same never-before-locked chat must not both succeed by
     * each inserting a fresh row, and {@code ON CONFLICT} is what makes the second
     * one lose instead of duplicating the row.
     */
    public boolean tryAcquire(UUID tenantId, long chatId, UUID leaseOwner, Duration leaseDuration) {
        Instant now = clock.instant();
        return jdbc.sql("""
                INSERT INTO integration.telegram_chat_locks (
                    tenant_id, chat_id, lease_owner, lease_expires_at, created_at, updated_at)
                VALUES (:tenantId, :chatId, :owner, :expires, :now, :now)
                ON CONFLICT (tenant_id, chat_id) DO UPDATE
                    SET lease_owner = excluded.lease_owner, lease_expires_at = excluded.lease_expires_at,
                        updated_at = excluded.updated_at
                    WHERE integration.telegram_chat_locks.lease_owner IS NULL
                       OR integration.telegram_chat_locks.lease_expires_at < :now
                """)
                        .param("tenantId", tenantId)
                        .param("chatId", chatId)
                        .param("owner", leaseOwner)
                        .param("expires", utc(now.plus(leaseDuration)))
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /** Releases a lease this caller holds. A lost race releasing nothing is correct, not an error. */
    public void release(UUID tenantId, long chatId, UUID leaseOwner) {
        jdbc.sql("""
                UPDATE integration.telegram_chat_locks
                SET lease_owner = NULL, lease_expires_at = NULL, updated_at = :now
                WHERE tenant_id = :tenantId AND chat_id = :chatId AND lease_owner = :owner
                """)
                .param("tenantId", tenantId)
                .param("chatId", chatId)
                .param("owner", leaseOwner)
                .param("now", utc(clock.instant()))
                .update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
