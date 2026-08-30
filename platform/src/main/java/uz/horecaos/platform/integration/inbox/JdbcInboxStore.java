package uz.horecaos.platform.integration.inbox;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.integration.api.ExternalEventEnvelope;

/**
 * Inbox persistence (ADR 0005).
 *
 * <p>Claiming uses {@code ON CONFLICT DO NOTHING} rather than catching a
 * duplicate-key exception, because a constraint violation aborts the
 * surrounding PostgreSQL transaction and the follow-up read would fail. The
 * same lesson applies here as in ADR 0031's idempotency claim.
 */
@Repository
public class JdbcInboxStore {

    /** Bounds an in-flight claim so a dead worker cannot hold an item forever. */
    static final Duration PROCESSING_LEASE = Duration.ofMinutes(5);

    private final JdbcClient jdbc;
    private final Clock clock;

    public JdbcInboxStore(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * Records arrival, or reports what already exists for this consumer.
     *
     * @return empty when the row was inserted, otherwise the existing row
     */
    public Optional<InboxRow> claim(String consumerName, ExternalEventEnvelope<?> envelope, String payloadJson) {
        Instant now = clock.instant();
        UUID id = UUID.randomUUID();

        int inserted = jdbc.sql("""
                INSERT INTO integration.inbox_messages (
                    id, consumer_name, event_id, topic, partition, record_offset,
                    tenant_id, event_type, event_version, aggregate_type, aggregate_id,
                    correlation_id, causation_id, occurred_at, payload, payload_sha256,
                    status, available_at, received_at, updated_at)
                VALUES (
                    :id, :consumerName, :eventId, :topic, :partition, :offset,
                    :tenantId, :eventType, :eventVersion, :aggregateType, :aggregateId,
                    :correlationId, :causationId, :occurredAt, CAST(:payload AS jsonb), :hash,
                    'RECEIVED', :now, :now, :now)
                ON CONFLICT DO NOTHING
                """)
                .param("id", id)
                .param("consumerName", consumerName)
                .param("eventId", envelope.eventId())
                .param("topic", envelope.transport().topic())
                .param("partition", envelope.transport().partition())
                .param("offset", envelope.transport().offset())
                .param("tenantId", envelope.tenantId())
                .param("eventType", envelope.eventType())
                .param("eventVersion", envelope.eventVersion())
                .param("aggregateType", envelope.aggregateType())
                .param("aggregateId", envelope.aggregateId())
                .param("correlationId", envelope.correlationId())
                .param("causationId", envelope.causationId())
                .param("occurredAt", at(envelope.occurredAt()))
                .param("payload", payloadJson)
                .param("hash", envelope.payloadSha256())
                .param("now", at(now))
                .update();

        return inserted == 1 ? Optional.empty() : find(consumerName, envelope.eventId());
    }

    public Optional<InboxRow> find(String consumerName, UUID eventId) {
        return jdbc.sql("""
                SELECT id, status, payload_sha256, attempt_count, processing_started_at
                  FROM integration.inbox_messages
                 WHERE consumer_name = :consumerName AND event_id = :eventId
                """)
                .param("consumerName", consumerName)
                .param("eventId", eventId)
                .query((rs, rowNumber) -> new InboxRow(
                        rs.getObject("id", UUID.class),
                        rs.getString("status"),
                        rs.getString("payload_sha256"),
                        rs.getInt("attempt_count"),
                        Optional.ofNullable(rs.getObject("processing_started_at", OffsetDateTime.class))
                                .map(OffsetDateTime::toInstant)
                                .orElse(null)))
                .optional();
    }

    /** Takes an item for processing, or reports that another worker holds it. */
    public boolean beginProcessing(UUID id, UUID processingToken) {
        Instant now = clock.instant();
        return jdbc.sql("""
                UPDATE integration.inbox_messages
                   SET status = 'PROCESSING',
                       processing_token = :token,
                       processing_started_at = :now,
                       attempt_count = attempt_count + 1,
                       updated_at = :now
                 WHERE id = :id
                   AND (status IN ('RECEIVED', 'RETRY_PENDING')
                        OR (status = 'PROCESSING' AND processing_started_at <= :leaseCutoff))
                """)
                        .param("id", id)
                        .param("token", processingToken)
                        .param("now", at(now))
                        .param("leaseCutoff", at(now.minus(PROCESSING_LEASE)))
                        .update()
                == 1;
    }

    /**
     * Marks the item processed. Called inside the same transaction as the
     * business effect, so the two commit or roll back together.
     */
    public void markProcessed(UUID id, UUID processingToken) {
        int updated = jdbc.sql("""
                UPDATE integration.inbox_messages
                   SET status = 'PROCESSED',
                       processing_token = NULL,
                       processed_at = :now,
                       updated_at = :now
                 WHERE id = :id AND processing_token = :token
                """)
                .param("id", id)
                .param("token", processingToken)
                .param("now", at(clock.instant()))
                .update();

        if (updated != 1) {
            // The lease expired and another worker took over. Committing the
            // business effect here would duplicate it.
            throw new IllegalStateException(
                    "Inbox item %s is no longer held under this processing token".formatted(id));
        }
    }

    public void scheduleRetry(UUID id, Duration backoff, String errorCode, String safeError) {
        Instant now = clock.instant();
        jdbc.sql("""
                UPDATE integration.inbox_messages
                   SET status = 'RETRY_PENDING',
                       processing_token = NULL,
                       processing_started_at = NULL,
                       available_at = :availableAt,
                       last_error_code = :errorCode,
                       last_error = :error,
                       updated_at = :now
                 WHERE id = :id
                """)
                .param("id", id)
                .param("availableAt", at(now.plus(backoff)))
                .param("errorCode", errorCode)
                .param("error", truncate(safeError))
                .param("now", at(now))
                .update();
    }

    public void deadLetter(UUID id, String errorCode, String safeError) {
        Instant now = clock.instant();
        jdbc.sql("""
                UPDATE integration.inbox_messages
                   SET status = 'DEAD_LETTER',
                       processing_token = NULL,
                       processing_started_at = NULL,
                       dead_lettered_at = :now,
                       last_error_code = :errorCode,
                       last_error = :error,
                       updated_at = :now
                 WHERE id = :id
                """)
                .param("id", id)
                .param("errorCode", errorCode)
                .param("error", truncate(safeError))
                .param("now", at(now))
                .update();
    }

    /**
     * Parks an item behind an earlier unresolved sibling for the same aggregate.
     *
     * <p>Deliberately does not touch {@code attempt_count}. Being blocked is not
     * an attempt and not a failure of this item; charging it retry budget would
     * dead-letter a perfectly good event for the sin of arriving second, and the
     * operator would then have two items to judge instead of one.
     *
     * <p>The guard matches {@link #beginProcessing}'s exactly, so a row is parked
     * only when it could also have been claimed. Without the lease clause, an
     * abandoned {@code PROCESSING} row that turns out to be blocked would fail
     * this update silently and be re-selected on every pass forever.
     */
    public void blockOnEarlier(UUID id, Duration recheckDelay, String detail) {
        Instant now = clock.instant();
        jdbc.sql("""
                UPDATE integration.inbox_messages
                   SET status = 'RETRY_PENDING',
                       processing_token = NULL,
                       processing_started_at = NULL,
                       available_at = :availableAt,
                       last_error_code = 'BLOCKED_BY_EARLIER_EVENT',
                       last_error = :detail,
                       updated_at = :now
                 WHERE id = :id
                   AND (status IN ('RECEIVED', 'RETRY_PENDING')
                        OR (status = 'PROCESSING' AND processing_started_at <= :leaseCutoff))
                """)
                .param("id", id)
                .param("availableAt", at(now.plus(recheckDelay)))
                .param("detail", truncate(detail))
                .param("now", at(now))
                .param("leaseCutoff", at(now.minus(PROCESSING_LEASE)))
                .update();
    }

    /**
     * Work that is due for a retry worker: items whose backoff has elapsed, plus
     * items abandoned mid-flight by a worker whose lease has expired.
     *
     * <p>Two workers may legitimately select the same row here. The claim is not
     * taken by this query but by {@link #beginProcessing}, whose conditional
     * update is the compare-and-set that decides which one actually runs the
     * handler; the loser simply sees {@code false} and moves on. Locking here as
     * well would buy nothing and would hold a transaction open across the
     * handler.
     *
     * <p>Written as a union rather than one {@code OR} because the two halves are
     * served by two different partial indexes — {@code ix_inbox_due} and
     * {@code ix_inbox_stale_claim} — and an {@code OR} across them planned as a
     * scan of every row for the consumer, on a table that is overwhelmingly
     * {@code PROCESSED} and that nothing ever deletes from. Each branch takes the
     * limit so neither can flood the other out.
     */
    public List<StoredInboxItem> due(String consumerName, int limit) {
        Instant now = clock.instant();
        return jdbc.sql("""
                (
                    SELECT id, consumer_name, event_id, event_type, event_version, tenant_id,
                           aggregate_type, aggregate_id, correlation_id, causation_id,
                           occurred_at, payload::text AS payload, payload_sha256,
                           topic, partition, record_offset, status, attempt_count
                      FROM integration.inbox_messages
                     WHERE consumer_name = :consumerName
                       AND status IN ('RECEIVED', 'RETRY_PENDING')
                       AND available_at <= :now
                     ORDER BY occurred_at, event_id
                     LIMIT :limit
                )
                UNION ALL
                (
                    SELECT id, consumer_name, event_id, event_type, event_version, tenant_id,
                           aggregate_type, aggregate_id, correlation_id, causation_id,
                           occurred_at, payload::text AS payload, payload_sha256,
                           topic, partition, record_offset, status, attempt_count
                      FROM integration.inbox_messages
                     WHERE consumer_name = :consumerName
                       AND status = 'PROCESSING'
                       AND processing_started_at <= :leaseCutoff
                     ORDER BY occurred_at, event_id
                     LIMIT :limit
                )
                ORDER BY occurred_at, event_id
                LIMIT :limit
                """)
                .param("consumerName", consumerName)
                .param("now", at(now))
                .param("leaseCutoff", at(now.minus(PROCESSING_LEASE)))
                .param("limit", limit)
                .query((rs, rowNumber) -> new StoredInboxItem(
                        rs.getObject("id", UUID.class),
                        rs.getString("consumer_name"),
                        rs.getObject("event_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getInt("event_version"),
                        rs.getObject("tenant_id", UUID.class),
                        rs.getString("aggregate_type"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("correlation_id"),
                        rs.getString("causation_id"),
                        rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                        rs.getString("payload"),
                        rs.getString("payload_sha256"),
                        rs.getString("topic"),
                        rs.getInt("partition"),
                        rs.getLong("record_offset"),
                        rs.getString("status"),
                        rs.getInt("attempt_count")))
                .list();
    }

    /**
     * Whether an earlier unresolved item exists for the same aggregate, which
     * must be settled first so a retry cannot let a later event overtake it.
     */
    public boolean hasEarlierUnresolvedForAggregate(
            String consumerName, String topic, UUID aggregateId, Instant occurredAt, UUID eventId) {

        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1 FROM integration.inbox_messages
                     WHERE consumer_name = :consumerName
                       AND topic = :topic
                       AND aggregate_id = :aggregateId
                       AND status IN ('RECEIVED', 'PROCESSING', 'RETRY_PENDING', 'DEAD_LETTER')
                       AND (occurred_at, event_id) < (:occurredAt, :eventId)
                )
                """)
                .param("consumerName", consumerName)
                .param("topic", topic)
                .param("aggregateId", aggregateId)
                .param("occurredAt", at(occurredAt))
                .param("eventId", eventId)
                .query(Boolean.class)
                .single();
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 2000 ? error : error.substring(0, 2000);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /**
     * A stored item complete enough to rebuild its envelope and run it again.
     *
     * <p>The retry worker re-drives from PostgreSQL, not from Kafka: by the time
     * an item is due its record may be long past the broker's retention, and the
     * inbox row has been the authoritative copy of the work since ADR 0005.
     */
    public record StoredInboxItem(
            UUID id,
            String consumerName,
            UUID eventId,
            String eventType,
            int eventVersion,
            UUID tenantId,
            String aggregateType,
            UUID aggregateId,
            String correlationId,
            String causationId,
            Instant occurredAt,
            String payloadJson,
            String payloadSha256,
            String topic,
            int partition,
            long recordOffset,
            String status,
            int attemptCount) {}

    /** The subset of an inbox row the executor needs to decide what to do. */
    public record InboxRow(
            UUID id, String status, String payloadSha256, int attemptCount, Instant processingStartedAt) {

        public boolean isProcessed() {
            return "PROCESSED".equals(status);
        }

        public boolean isDeadLettered() {
            return "DEAD_LETTER".equals(status);
        }
    }
}
