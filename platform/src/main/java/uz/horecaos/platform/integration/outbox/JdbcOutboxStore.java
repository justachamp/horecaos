package uz.horecaos.platform.integration.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcOutboxStore implements RelayStore {

    private final JdbcClient jdbc;

    public JdbcOutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Writes one event for {@link OutboxRelay} to publish later, in the caller's own transaction. */
    public void append(NewOutboxEvent event) {
        jdbc.sql("""
                        INSERT INTO integration.outbox_events (
                            event_id, event_type, event_version, tenant_id,
                            aggregate_type, aggregate_id, topic, partition_key,
                            correlation_id, causation_id, occurred_at, payload,
                            trace_context, next_attempt_at
                        ) VALUES (
                            :eventId, :eventType, :eventVersion, :tenantId,
                            :aggregateType, :aggregateId, :topic, :partitionKey,
                            :correlationId, :causationId, :occurredAt,
                            CAST(:payload AS jsonb), CAST(:traceContext AS jsonb),
                            :occurredAt
                        )
                        """)
                .param("eventId", event.eventId())
                .param("eventType", event.eventType())
                .param("eventVersion", event.eventVersion())
                .param("tenantId", event.tenantId())
                .param("aggregateType", event.aggregateType())
                .param("aggregateId", event.aggregateId())
                .param("topic", event.topic())
                .param("partitionKey", event.partitionKey())
                .param("correlationId", event.correlationId())
                .param("causationId", event.causationId())
                .param("occurredAt", utc(event.occurredAt()))
                .param("payload", event.payloadJson())
                .param("traceContext", event.traceContextJson())
                .update();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ClaimedOutboxEvent> claimBatch(Instant now, Duration leaseDuration, int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("Outbox batch size must be positive");
        }
        UUID claimToken = UUID.randomUUID();
        Instant staleBefore = now.minus(leaseDuration);

        return jdbc.sql("""
                        WITH candidates AS (
                            SELECT candidate.event_id
                            FROM integration.outbox_events AS candidate
                            WHERE (
                                    (candidate.status = 'PENDING' AND candidate.next_attempt_at <= :now)
                                    OR
                                    (candidate.status = 'PUBLISHING' AND candidate.claimed_at < :staleBefore)
                                )
                              AND NOT EXISTS (
                                  SELECT 1
                                  FROM integration.outbox_events AS earlier
                                  WHERE earlier.topic = candidate.topic
                                    AND earlier.partition_key = candidate.partition_key
                                    AND earlier.status IN ('PENDING', 'PUBLISHING', 'DEAD_LETTER')
                                    AND (earlier.occurred_at, earlier.event_id)
                                        < (candidate.occurred_at, candidate.event_id)
                              )
                            ORDER BY candidate.occurred_at, candidate.event_id
                            FOR UPDATE SKIP LOCKED
                            LIMIT :batchSize
                        )
                        UPDATE integration.outbox_events AS outbox
                        SET status = 'PUBLISHING',
                            attempt_count = attempt_count + 1,
                            claim_token = :claimToken,
                            claimed_at = :now,
                            updated_at = :now
                        FROM candidates
                        WHERE outbox.event_id = candidates.event_id
                        RETURNING outbox.event_id, outbox.event_type,
                                  outbox.event_version, outbox.tenant_id,
                                  outbox.aggregate_type, outbox.aggregate_id,
                                  outbox.topic, outbox.partition_key,
                                  outbox.correlation_id, outbox.causation_id,
                                  outbox.occurred_at, outbox.payload::text AS payload,
                                  outbox.trace_context::text AS trace_context,
                                  outbox.attempt_count, outbox.claim_token
                        """)
                .param("now", utc(now))
                .param("staleBefore", utc(staleBefore))
                .param("batchSize", batchSize)
                .param("claimToken", claimToken)
                .query(JdbcOutboxStore::mapClaimedEvent)
                .list();
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markPublished(UUID eventId, UUID claimToken, Instant publishedAt) {
        return jdbc.sql("""
                        UPDATE integration.outbox_events
                        SET status = 'PUBLISHED',
                            claim_token = NULL,
                            claimed_at = NULL,
                            published_at = :publishedAt,
                            last_error = NULL,
                            updated_at = :publishedAt
                        WHERE event_id = :eventId
                          AND status = 'PUBLISHING'
                          AND claim_token = :claimToken
                        """)
                        .param("eventId", eventId)
                        .param("claimToken", claimToken)
                        .param("publishedAt", utc(publishedAt))
                        .update()
                == 1;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean markFailed(
            UUID eventId, UUID claimToken, Instant now, Instant nextAttemptAt, String error, boolean deadLetter) {
        return jdbc.sql("""
                        UPDATE integration.outbox_events
                        SET status = CASE WHEN :deadLetter THEN 'DEAD_LETTER' ELSE 'PENDING' END,
                            claim_token = NULL,
                            claimed_at = NULL,
                            next_attempt_at = :nextAttemptAt,
                            dead_lettered_at = CASE WHEN :deadLetter THEN :now ELSE NULL END,
                            last_error = :error,
                            updated_at = :now
                        WHERE event_id = :eventId
                          AND status = 'PUBLISHING'
                          AND claim_token = :claimToken
                        """)
                        .param("eventId", eventId)
                        .param("claimToken", claimToken)
                        .param("now", utc(now))
                        .param("nextAttemptAt", utc(nextAttemptAt))
                        .param("error", error)
                        .param("deadLetter", deadLetter)
                        .update()
                == 1;
    }

    private static ClaimedOutboxEvent mapClaimedEvent(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ClaimedOutboxEvent(
                resultSet.getObject("event_id", UUID.class),
                resultSet.getString("event_type"),
                resultSet.getInt("event_version"),
                resultSet.getObject("tenant_id", UUID.class),
                resultSet.getString("aggregate_type"),
                resultSet.getObject("aggregate_id", UUID.class),
                resultSet.getString("topic"),
                resultSet.getString("partition_key"),
                resultSet.getString("correlation_id"),
                resultSet.getString("causation_id"),
                resultSet.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                resultSet.getString("payload"),
                resultSet.getString("trace_context"),
                resultSet.getInt("attempt_count"),
                resultSet.getObject("claim_token", UUID.class));
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
