package uz.horecaos.platform.notifications.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * What a delivery receipt reads and writes on an attempt (ADR 0146 Decisions 4
 * and 5).
 *
 * <p>Kept apart from {@link JdbcNotificationStore}, which is already the whole
 * of the send path: this is the other direction, where the provider speaks first
 * and the platform may only advance what it already holds.
 *
 * <p>Every statement is tenant-scoped, and a receipt is matched on the bindings of
 * the installation it arrived on, never on a message id alone: an id is the
 * gateway's, and two tenants' gateways can reuse one.
 */
@Repository
public class JdbcDeliveryReceiptStore {

    private final JdbcClient jdbc;

    public JdbcDeliveryReceiptStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The attempt a provider message id names, under any of the given bindings.
     *
     * <p>Locked {@code FOR UPDATE}: two receipts for one message (a duplicate, or
     * {@code Sent} racing {@code Delivered}) are applied one after the other, so
     * the monotonic rule reads what the first left behind rather than what was there
     * before either began.
     */
    public Optional<ReceiptAttempt> lockAttempt(UUID tenantId, Collection<UUID> bindingIds, String providerMessageId) {
        if (bindingIds.isEmpty()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT a.id, a.notification_id, a.status, a.provider_binding_id,
                       a.requested_at, n.recipient_account_id, n.brand_id
                  FROM notifications.delivery_attempts a
                  JOIN notifications.notifications n
                    ON n.id = a.notification_id AND n.tenant_id = a.tenant_id
                 WHERE a.tenant_id = :tenantId
                   AND a.provider_binding_id IN (:bindingIds)
                   AND a.external_message_id = :messageId
                 ORDER BY a.requested_at DESC
                 LIMIT 1
                 FOR UPDATE OF a
                """)
                .param("tenantId", tenantId)
                .param("bindingIds", bindingIds)
                .param("messageId", providerMessageId)
                .query(JdbcDeliveryReceiptStore::attempt)
                .optional();
    }

    /** Moves an attempt to the terminal state a receipt reported, and clears any "no receipt" mark. */
    public void advance(
            UUID tenantId,
            UUID attemptId,
            String attemptStatus,
            @Nullable String failureCode,
            @Nullable Instant acknowledgedAt,
            Instant now) {
        jdbc.sql("""
                UPDATE notifications.delivery_attempts
                   SET status = :status,
                       failure_code = :failureCode,
                       acknowledged_at = :acknowledgedAt,
                       receipt_state = NULL,
                       updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("status", attemptStatus)
                .param("failureCode", failureCode)
                .param("acknowledgedAt", acknowledgedAt == null ? null : utc(acknowledgedAt))
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("id", attemptId)
                .update();
    }

    /** A receipt that is not terminal still proves somebody is listening: a late one clears "no receipt". */
    public void clearNoReceipt(UUID tenantId, UUID attemptId, Instant now) {
        jdbc.sql("""
                UPDATE notifications.delivery_attempts
                   SET receipt_state = NULL, updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id AND receipt_state IS NOT NULL
                """)
                .param("now", utc(now))
                .param("tenantId", tenantId)
                .param("id", attemptId)
                .update();
    }

    /**
     * Accepted attempts of one provider type that no receipt has reported on, older
     * than {@code requestedBefore}.
     *
     * <p>"Reported on" is any status event other than the send's own answer
     * ({@code ACCEPTED}). Cross-tenant by design, like every sweeper's candidate
     * query; the caller applies each tenant's own window.
     */
    public List<ReceiptCandidate> awaitingReceipt(String providerType, Instant requestedBefore, int limit) {
        return jdbc.sql("""
                SELECT a.id, a.tenant_id, a.requested_at
                  FROM notifications.delivery_attempts a
                 WHERE a.status = 'ACCEPTED' AND a.receipt_state IS NULL
                   AND a.provider_type = :providerType
                   AND a.requested_at < :before
                   AND NOT EXISTS (
                       SELECT 1 FROM notifications.delivery_status_events e
                        WHERE e.tenant_id = a.tenant_id AND e.attempt_id = a.id
                          AND e.normalized_status <> 'ACCEPTED')
                 ORDER BY a.requested_at
                 LIMIT :limit
                """)
                .param("providerType", providerType)
                .param("before", utc(requestedBefore))
                .param("limit", limit)
                .query((row, number) -> new ReceiptCandidate(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("requested_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    /** Marks an accepted attempt "no receipt". Guarded on its state, so a receipt that raced it wins. */
    public boolean markNoReceipt(UUID tenantId, UUID attemptId, Instant now) {
        return jdbc.sql("""
                UPDATE notifications.delivery_attempts
                   SET receipt_state = 'NO_RECEIPT', updated_at = :now
                 WHERE tenant_id = :tenantId AND id = :id
                   AND status = 'ACCEPTED' AND receipt_state IS NULL
                   AND NOT EXISTS (
                       SELECT 1 FROM notifications.delivery_status_events e
                        WHERE e.tenant_id = delivery_attempts.tenant_id AND e.attempt_id = delivery_attempts.id
                          AND e.normalized_status <> 'ACCEPTED')
                """)
                        .param("now", utc(now))
                        .param("tenantId", tenantId)
                        .param("id", attemptId)
                        .update()
                == 1;
    }

    /**
     * Accepted attempts a provider reported as {@code UNKNOWN} and nothing since:
     * the only attempts ADR 0146 Decision 5 lets a pull chase.
     */
    public List<UnknownStateAttempt> reportedUnknown(String providerType, Instant olderThan, int limit) {
        return jdbc.sql("""
                SELECT a.id, a.tenant_id, a.notification_id, a.external_message_id, a.requested_at,
                       a.provider_idempotency_key, a.provider_binding_id, n.brand_id, n.location_id,
                       n.rendered_content_hash, n.channel
                  FROM notifications.delivery_attempts a
                  JOIN notifications.notifications n
                    ON n.id = a.notification_id AND n.tenant_id = a.tenant_id
                 WHERE a.status = 'ACCEPTED' AND a.provider_type = :providerType
                   AND a.requested_at < :before
                   AND EXISTS (
                       SELECT 1 FROM notifications.delivery_status_events e
                        WHERE e.tenant_id = a.tenant_id AND e.attempt_id = a.id
                          AND e.normalized_status = 'UNKNOWN')
                   AND NOT EXISTS (
                       SELECT 1 FROM notifications.delivery_status_events e
                        WHERE e.tenant_id = a.tenant_id AND e.attempt_id = a.id
                          AND e.normalized_status IN ('DELIVERED', 'READ', 'FAILED'))
                 ORDER BY a.requested_at
                 LIMIT :limit
                """)
                .param("providerType", providerType)
                .param("before", utc(olderThan))
                .param("limit", limit)
                .query((row, number) -> new UnknownStateAttempt(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("notification_id", UUID.class),
                        row.getString("external_message_id"),
                        row.getObject("requested_at", OffsetDateTime.class).toInstant(),
                        row.getString("provider_idempotency_key"),
                        row.getObject("provider_binding_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getString("rendered_content_hash"),
                        row.getString("channel")))
                .list();
    }

    private static ReceiptAttempt attempt(ResultSet row, int number) throws SQLException {
        return new ReceiptAttempt(
                row.getObject("id", UUID.class),
                row.getObject("notification_id", UUID.class),
                row.getString("status"),
                row.getObject("provider_binding_id", UUID.class),
                row.getObject("requested_at", OffsetDateTime.class).toInstant(),
                row.getObject("recipient_account_id", UUID.class),
                row.getObject("brand_id", UUID.class));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /** The attempt a receipt names, and the account and brand it concerns (identifiers only). */
    public record ReceiptAttempt(
            UUID id,
            UUID notificationId,
            String status,
            UUID providerBindingId,
            Instant requestedAt,
            @Nullable UUID recipientAccountId,
            @Nullable UUID brandId) {}

    public record ReceiptCandidate(UUID attemptId, UUID tenantId, Instant requestedAt) {}

    public record UnknownStateAttempt(
            UUID attemptId,
            UUID tenantId,
            UUID notificationId,
            @Nullable String externalMessageId,
            Instant requestedAt,
            String providerIdempotencyKey,
            @Nullable UUID providerBindingId,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            @Nullable String renderedContentHash,
            String channel) {}
}
