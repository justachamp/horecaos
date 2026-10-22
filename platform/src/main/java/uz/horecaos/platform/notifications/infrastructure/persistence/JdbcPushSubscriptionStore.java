package uz.horecaos.platform.notifications.infrastructure.persistence;

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
 * The registry of a customer's Web Push subscriptions (V0673, ADR 0182, D14).
 *
 * <p>This class moves ciphertext and never looks inside it: the subscription's push service URL and
 * keys are one ADR 0029 value that {@code PushSubscriptionService} protects before it gets here and
 * reveals after it leaves. Nothing returned by a method of this class identifies a device.
 *
 * <p>The two unique indexes of V0673 are what make the rules true under concurrency: one live
 * subscription per customer per brand, and one live customer per browser. Both are enforced by
 * ending the old row in the same transaction that inserts the new one, and the indexes catch the
 * race the transaction cannot see.
 */
@Repository
public class JdbcPushSubscriptionStore {

    /** Why a subscription ended. Bounded codes: never a push service's text. */
    public static final String ENDED_GONE = "PUSH_SERVICE_GONE";

    public static final String ENDED_BY_CUSTOMER = "CUSTOMER_TURNED_OFF";
    public static final String ENDED_REPLACED = "REPLACED_BY_NEWER";
    public static final String ENDED_BROWSER_MOVED = "BROWSER_USED_BY_ANOTHER_CUSTOMER";
    public static final String ENDED_EXPIRED = "BROWSER_EXPIRATION_PASSED";
    public static final String ENDED_ERASED = "CUSTOMER_ERASED";

    private final JdbcClient jdbc;

    public JdbcPushSubscriptionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID customerAccountId,
            String secretCiphertext,
            String endpointHash,
            @Nullable Instant expiresAt,
            Instant now) {
        jdbc.sql("""
                INSERT INTO notifications.push_subscriptions (
                    id, tenant_id, brand_id, customer_account_id, secret_ciphertext, endpoint_hash,
                    status, expires_at, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :accountId, :secret, :hash,
                        'ACTIVE', :expiresAt, :now, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("accountId", customerAccountId)
                .param("secret", secretCiphertext)
                .param("hash", endpointHash)
                .param("expiresAt", expiresAt == null ? null : utc(expiresAt))
                .param("now", utc(now))
                .update();
    }

    /**
     * Ends every live subscription of this customer at this brand, and every live subscription in the
     * tenant that is this browser. Returns the ids it ended, so the caller retires their endpoints.
     */
    public List<UUID> endLiveFor(
            UUID tenantId, UUID brandId, UUID customerAccountId, String endpointHash, String reason, Instant now) {
        return jdbc.sql("""
                UPDATE notifications.push_subscriptions
                   SET status = 'REVOKED', ended_reason = CASE
                           WHEN customer_account_id = :accountId THEN :replaced ELSE :moved END,
                       secret_ciphertext = NULL, ended_at = :now, updated_at = :now
                 WHERE tenant_id = :tenantId AND status = 'ACTIVE'
                   AND ((brand_id = :brandId AND customer_account_id = :accountId) OR endpoint_hash = :hash)
                RETURNING id
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("accountId", customerAccountId)
                .param("hash", endpointHash)
                .param("replaced", reason)
                .param("moved", ENDED_BROWSER_MOVED)
                .param("now", utc(now))
                .query(UUID.class)
                .list();
    }

    /** Ends one subscription. Idempotent: an ended one stays as it ended. */
    public boolean end(UUID tenantId, UUID subscriptionId, String status, String reason, Instant now) {
        return jdbc.sql("""
                UPDATE notifications.push_subscriptions
                   SET status = :status, ended_reason = :reason, secret_ciphertext = NULL,
                       ended_at = :now, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'ACTIVE'
                """)
                        .param("status", status)
                        .param("reason", reason)
                        .param("now", utc(now))
                        .param("tenantId", tenantId)
                        .param("id", subscriptionId)
                        .update()
                == 1;
    }

    /** Ends the customer's live subscription at a brand, returning its id when there was one. */
    public Optional<UUID> endLiveOf(UUID tenantId, UUID brandId, UUID customerAccountId, String reason, Instant now) {
        return jdbc.sql("""
                UPDATE notifications.push_subscriptions
                   SET status = 'REVOKED', ended_reason = :reason, secret_ciphertext = NULL,
                       ended_at = :now, updated_at = :now
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND customer_account_id = :accountId
                   AND status = 'ACTIVE'
                RETURNING id
                """)
                .param("reason", reason)
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("accountId", customerAccountId)
                .query(UUID.class)
                .optional();
    }

    /** Ends every live subscription of one customer in every brand: an erasure. */
    public List<UUID> endAllOf(UUID tenantId, UUID customerAccountId, String reason, Instant now) {
        return jdbc.sql("""
                UPDATE notifications.push_subscriptions
                   SET status = 'REVOKED', ended_reason = :reason, secret_ciphertext = NULL,
                       ended_at = :now, updated_at = :now
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId AND status = 'ACTIVE'
                RETURNING id
                """)
                .param("reason", reason)
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("accountId", customerAccountId)
                .query(UUID.class)
                .list();
    }

    /** Whether the customer has a live subscription at this brand. */
    public Optional<Live> liveOf(UUID tenantId, UUID brandId, UUID customerAccountId, Instant now) {
        return jdbc.sql("""
                SELECT id, expires_at FROM notifications.push_subscriptions
                 WHERE tenant_id = :tenantId AND brand_id = :brandId AND customer_account_id = :accountId
                   AND status = 'ACTIVE' AND (expires_at IS NULL OR expires_at > :now)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("accountId", customerAccountId)
                .param("now", utc(now))
                .query((row, number) -> new Live(row.getObject("id", UUID.class)))
                .optional();
    }

    /** The ciphertext of a live subscription, or empty once it has ended (or never was). */
    public Optional<String> secretOf(UUID tenantId, UUID subscriptionId) {
        return jdbc.sql("""
                SELECT secret_ciphertext FROM notifications.push_subscriptions
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'ACTIVE'
                """)
                .param("tenantId", tenantId)
                .param("id", subscriptionId)
                .query(String.class)
                .optional();
    }

    /** The account a subscription belongs to and the state it is in: identifiers and a status. */
    public Optional<Row> find(UUID tenantId, UUID subscriptionId) {
        return jdbc.sql("""
                SELECT id, brand_id, customer_account_id, status, ended_reason, last_accepted_at
                  FROM notifications.push_subscriptions
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", subscriptionId)
                .query((row, number) -> new Row(
                        row.getObject("id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("customer_account_id", UUID.class),
                        row.getString("status"),
                        row.getString("ended_reason"),
                        instant(row.getObject("last_accepted_at", OffsetDateTime.class))))
                .optional();
    }

    public void touchAccepted(UUID tenantId, UUID subscriptionId, Instant now) {
        jdbc.sql("""
                UPDATE notifications.push_subscriptions
                   SET last_accepted_at = :now, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND status = 'ACTIVE'
                """)
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("id", subscriptionId)
                .update();
    }

    /** What the registry says about a customer's live subscription. Nothing about the device. */
    public record Live(UUID id) {}

    /** A subscription's identity and state, never its secret. */
    public record Row(
            UUID id,
            UUID brandId,
            UUID customerAccountId,
            String status,
            @Nullable String endedReason,
            @Nullable Instant lastAcceptedAt) {}

    private static @Nullable Instant instant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
