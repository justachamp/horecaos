package uz.horecaos.platform.notifications.infrastructure.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What a campaign email's unsubscribe token stands for (V0671, ADR 0182).
 *
 * <p>Only the token's SHA-256 is stored, so a reader of the table cannot unsubscribe anyone, and
 * no address is stored at all: the row names a notification, and the notification names an
 * account. A lookup matches on the hash alone, and the tenant is read from the row it finds, not
 * taken from the caller: the caller is a customer who has nothing but the token.
 */
@Repository
public class JdbcUnsubscribeTokenStore {

    private final JdbcClient jdbc;

    public JdbcUnsubscribeTokenStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            String tokenHash,
            UUID tenantId,
            UUID brandId,
            UUID customerAccountId,
            UUID notificationId,
            String consentPurpose,
            @Nullable String locale,
            Instant createdAt,
            Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO notifications.unsubscribe_tokens (
                    token_hash, tenant_id, brand_id, customer_account_id, notification_id,
                    consent_purpose, locale, created_at, expires_at)
                VALUES (:hash, :tenantId, :brandId, :accountId, :notificationId,
                        :purpose, :locale, :createdAt, :expiresAt)
                """)
                .param("hash", tokenHash)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("accountId", customerAccountId)
                .param("notificationId", notificationId)
                .param("purpose", consentPurpose)
                .param("locale", locale)
                .param("createdAt", utc(createdAt))
                .param("expiresAt", utc(expiresAt))
                .update();
    }

    /** The live token with this hash, or empty for an unknown or an expired one (the same answer). */
    public Optional<UnsubscribeToken> find(String tokenHash, Instant now) {
        return jdbc.sql("""
                SELECT tenant_id, brand_id, customer_account_id, notification_id, consent_purpose, locale
                  FROM notifications.unsubscribe_tokens
                 WHERE token_hash = :hash AND expires_at > :now
                """)
                .param("hash", tokenHash)
                .param("now", utc(now))
                .query((row, number) -> new UnsubscribeToken(
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("customer_account_id", UUID.class),
                        row.getObject("notification_id", UUID.class),
                        row.getString("consent_purpose"),
                        row.getString("locale")))
                .optional();
    }

    /**
     * Removes up to {@code limit} tokens past their expiry: after it a token is the same answer as an
     * unknown one, and the row names a customer account and a notification for nothing.
     */
    public int deleteExpired(Instant now, int limit) {
        return jdbc.sql("""
                DELETE FROM notifications.unsubscribe_tokens
                 WHERE token_hash IN (
                     SELECT token_hash FROM notifications.unsubscribe_tokens
                      WHERE expires_at <= :now ORDER BY expires_at LIMIT :limit)
                """).param("now", utc(now)).param("limit", limit).update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** What a token stands for. Identifiers only: a customer account id is pseudonymous (ADR 0076). */
    public record UnsubscribeToken(
            UUID tenantId,
            UUID brandId,
            UUID customerAccountId,
            UUID notificationId,
            String consentPurpose,
            @Nullable String locale) {}
}
