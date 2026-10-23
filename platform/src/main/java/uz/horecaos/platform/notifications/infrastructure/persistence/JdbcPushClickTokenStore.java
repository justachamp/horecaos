package uz.horecaos.platform.notifications.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What a push notification's click token stands for (V0673, ADR 0182, D14).
 *
 * <p>Only the token's SHA-256 is stored, so a reader of the table cannot report a read for a message.
 * The row names the notification and nothing about the customer; the tenant is read from the row the
 * hash finds, not taken from the caller, who is a service worker holding nothing but the token.
 */
@Repository
public class JdbcPushClickTokenStore {

    private final JdbcClient jdbc;

    public JdbcPushClickTokenStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(String tokenHash, UUID tenantId, UUID notificationId, Instant createdAt, Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO notifications.push_click_tokens (token_hash, tenant_id, notification_id, created_at, expires_at)
                VALUES (:hash, :tenantId, :notificationId, :createdAt, :expiresAt)
                """)
                .param("hash", tokenHash)
                .param("tenantId", tenantId)
                .param("notificationId", notificationId)
                .param("createdAt", utc(createdAt))
                .param("expiresAt", utc(expiresAt))
                .update();
    }

    /** The live token with this hash, or empty for an unknown or an expired one (the same answer). */
    public Optional<Click> find(String tokenHash, Instant now) {
        return jdbc.sql("""
                SELECT tenant_id, notification_id
                  FROM notifications.push_click_tokens
                 WHERE token_hash = :hash AND expires_at > :now
                """)
                .param("hash", tokenHash)
                .param("now", utc(now))
                .query((row, number) ->
                        new Click(row.getObject("tenant_id", UUID.class), row.getObject("notification_id", UUID.class)))
                .optional();
    }

    /** Removes tokens past their expiry: a token is useless after it and holds a row for nothing. */
    public int deleteExpired(Instant now, int limit) {
        return jdbc.sql("""
                DELETE FROM notifications.push_click_tokens
                 WHERE token_hash IN (
                     SELECT token_hash FROM notifications.push_click_tokens
                      WHERE expires_at <= :now ORDER BY expires_at LIMIT :limit)
                """).param("now", utc(now)).param("limit", limit).update();
    }

    /** What a click token names. Identifiers only. */
    public record Click(UUID tenantId, UUID notificationId) {}

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
