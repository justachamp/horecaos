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

    /**
     * The latest attempt of one notification, locked (ADR 0182): how a push click finds the message it
     * is about, since the platform's own push has no binding for {@link #lockAttempt} to filter on.
     */
    public Optional<ReceiptAttempt> lockLatestAttempt(UUID tenantId, UUID notificationId) {
        return jdbc.sql("""
                SELECT a.id, a.notification_id, a.status, a.provider_binding_id,
                       a.requested_at, n.recipient_account_id, n.brand_id
                  FROM notifications.delivery_attempts a
                  JOIN notifications.notifications n
                    ON n.id = a.notification_id AND n.tenant_id = a.tenant_id
                 WHERE a.tenant_id = :tenantId AND a.notification_id = :notificationId
                 ORDER BY a.attempt_number DESC
                 LIMIT 1
                 FOR UPDATE OF a
                """)
                .param("tenantId", tenantId)
                .param("notificationId", notificationId)
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

    /**
     * What each of these notifications has come to, for a campaign's recipient list
     * (ADR 0146, ADR 0182): the notification's own status, why it was withheld if it
     * was, and its latest attempt with what a gateway has said about it and billed.
     *
     * <p>A notification with no attempt yet is present, with {@code attempt} null: a
     * message that was suppressed or has not been tried is evidence too, and a
     * recipient row that said nothing about it left a marketer asking why.
     */
    public java.util.Map<UUID, MessageEvidence> evidenceFor(UUID tenantId, Collection<UUID> notificationIds) {
        if (notificationIds.isEmpty()) {
            return java.util.Map.of();
        }
        java.util.Map<UUID, MessageEvidence> latest = new java.util.LinkedHashMap<>();
        jdbc.sql("SELECT n.id AS notification_id, " + EVIDENCE_COLUMNS + " " + EVIDENCE_FROM
                        + " WHERE n.tenant_id = :tenantId AND n.id IN (:ids)")
                .param("tenantId", tenantId)
                .param("ids", notificationIds)
                .query((row, number) ->
                        java.util.Map.entry(row.getObject("notification_id", UUID.class), evidence(row)))
                .list()
                .forEach(entry -> latest.put(entry.getKey(), entry.getValue()));
        return latest;
    }

    /**
     * The same evidence for every message of one subject, grouped (ADR 0182): how many
     * notifications of a campaign stand in each combination of notification status,
     * suppression reason, latest-attempt status, receipt state and read mark, and how
     * many segments the gateway says it billed across them.
     *
     * <p>A join read at the moment it is asked, so a receipt that arrives a second
     * later is in the next answer. Nothing is copied onto a campaign or a recipient
     * row, which is the rule ADR 0146 states for {@code terminal_status}.
     */
    public List<EvidenceGroup> evidenceGroups(UUID tenantId, String subjectType, UUID subjectId) {
        return jdbc.sql("SELECT " + EVIDENCE_COLUMNS + ", COUNT(*) AS total,"
                        + " COALESCE(SUM(a.provider_segments), 0) AS segments " + EVIDENCE_FROM
                        + " WHERE n.tenant_id = :tenantId AND n.subject_type = :subjectType"
                        + " AND n.subject_id = :subjectId"
                        + " GROUP BY n.status, n.suppression_reason, a.status, a.receipt_state,"
                        + " a.provider_segments, a.failure_code, a.read_seen")
                .param("tenantId", tenantId)
                .param("subjectType", subjectType)
                .param("subjectId", subjectId)
                .query((row, number) -> new EvidenceGroup(evidence(row), row.getInt("total"), row.getLong("segments")))
                .list();
    }

    private static final String EVIDENCE_COLUMNS = """
            n.status AS notification_status, n.suppression_reason,
            a.status AS attempt_status, a.receipt_state, a.provider_segments, a.failure_code, a.read_seen""";

    private static final String EVIDENCE_FROM = """
            FROM notifications.notifications n
            LEFT JOIN LATERAL (
                SELECT att.status, att.receipt_state, att.provider_segments, att.failure_code,
                       EXISTS (SELECT 1 FROM notifications.delivery_status_events e
                                WHERE e.tenant_id = att.tenant_id AND e.attempt_id = att.id
                                  AND e.normalized_status = 'READ') AS read_seen
                  FROM notifications.delivery_attempts att
                 WHERE att.tenant_id = n.tenant_id AND att.notification_id = n.id
                 ORDER BY att.attempt_number DESC
                 LIMIT 1) a ON true""";

    private static MessageEvidence evidence(ResultSet row) throws SQLException {
        String attemptStatus = row.getString("attempt_status");
        AttemptEvidence attempt = attemptStatus == null
                ? null
                : new AttemptEvidence(
                        attemptStatus,
                        row.getString("receipt_state"),
                        row.getObject("provider_segments", Integer.class),
                        row.getBoolean("read_seen"),
                        row.getString("failure_code"));
        return new MessageEvidence(row.getString("notification_status"), row.getString("suppression_reason"), attempt);
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
            @Nullable UUID providerBindingId,
            Instant requestedAt,
            @Nullable UUID recipientAccountId,
            @Nullable UUID brandId) {}

    /**
     * The latest attempt of one notification.
     *
     * @param read whether a {@code READ} status event is on record for it: a channel that reports
     *             opens or clicks writes one, and the attempt's own status stays {@code DELIVERED}
     * @param failureCode the bounded reason code the attempt closed with, never a provider's text
     */
    public record AttemptEvidence(
            String status,
            @Nullable String receiptState,
            @Nullable Integer segmentsBilled,
            boolean read,
            @Nullable String failureCode) {}

    /** What one notification has come to: its status, why it was withheld, and its latest attempt. */
    public record MessageEvidence(
            String notificationStatus,
            @Nullable String suppressionReason,
            @Nullable AttemptEvidence attempt) {}

    /** How many notifications stand in one {@link MessageEvidence}, and the segments billed across them. */
    public record EvidenceGroup(MessageEvidence evidence, int count, long segmentsBilled) {}

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
