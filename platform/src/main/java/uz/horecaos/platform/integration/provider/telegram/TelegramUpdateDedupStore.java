package uz.horecaos.platform.integration.provider.telegram;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * At-least-once dedup for {@link TelegramUpdateHandler}'s entry point (ADR
 * 0032), added as part of ADR 0059 stage 1: a redelivered {@code update_id} —
 * a webhook retry, or the local long-polling consumer racing a slow ack —
 * must not restart or re-answer a flow, and nothing in this path deduplicated
 * before this (a known platform gap, not specific to conversations: every
 * caller of {@link TelegramUpdateHandler#handle} benefits, not only the flow
 * engine's own callers).
 *
 * <p>{@code update_id} is per-bot, monotonically issued by Telegram, so
 * {@code (installation_id, update_id)} alone would be the natural key —
 * {@code tenant_id} joins it not merely as a passenger column but inside the
 * primary key itself, because {@code TenantScopedReferenceCatalogTests}
 * specifically checks for a composite key of caller-supplied ids with no
 * foreign key on any of them and no tenant column: the one cross-tenant
 * write shape an ordinary foreign-key sweep cannot see.
 */
@Repository
public class TelegramUpdateDedupStore {

    private final JdbcClient jdbc;
    private final Clock clock;

    public TelegramUpdateDedupStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Records this update as processed.
     *
     * @return true the first time this {@code (installationId, updateId)} pair
     *         is seen — the caller should proceed; false on a redelivery —
     *         the caller must not act on it again
     */
    public boolean recordIfNew(UUID tenantId, UUID installationId, long updateId) {
        Instant now = clock.instant();
        return jdbc.sql("""
                INSERT INTO integration.telegram_processed_updates (tenant_id, installation_id, update_id, processed_at)
                VALUES (:tenantId, :installationId, :updateId, :now)
                ON CONFLICT (tenant_id, installation_id, update_id) DO NOTHING
                """)
                        .param("tenantId", tenantId)
                        .param("installationId", installationId)
                        .param("updateId", updateId)
                        .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                        .update()
                == 1;
    }
}
