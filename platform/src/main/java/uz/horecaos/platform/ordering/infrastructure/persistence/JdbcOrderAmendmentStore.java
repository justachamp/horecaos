package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.ordering.domain.AmendmentCommandType;
import uz.horecaos.platform.ordering.domain.AmendmentStatus;

/**
 * Amendment persistence (ADR 0039).
 *
 * <p>Two properties are settled here rather than in the application, because the
 * application cannot settle them: only one amendment may be open on an order at a
 * time, and only one caller may apply a given amendment.
 *
 * <p>The first is a partial unique index — two operators opening the dialog on
 * one order is routine, and without the index both would build a change against
 * the same base revision and the second would silently lose the first one's work.
 * The second is a conditional UPDATE naming the status and version it expects, so
 * a duplicate apply affects no row rather than appending a second revision.
 */
@Repository
public class JdbcOrderAmendmentStore {

    private final JdbcClient jdbc;

    public JdbcOrderAmendmentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(NewAmendment amendment) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", amendment.id());
        params.put("tenantId", amendment.tenantId());
        params.put("orderId", amendment.orderId());
        params.put("status", amendment.status().name());
        params.put("baseRevision", amendment.baseRevision());
        params.put("quoteId", amendment.quoteId());
        params.put("delta", amendment.deltaTotalMinor());
        params.put("requiresApproval", amendment.requiresApproval());
        params.put("approvalRequestId", amendment.approvalRequestId());
        params.put("idempotencyKey", amendment.idempotencyKey());
        params.put("expiresAt", utc(amendment.expiresAt()));
        params.put("actorType", amendment.createdByActorType());
        params.put("actorId", amendment.createdByActorId());
        params.put("now", utc(amendment.createdAt()));

        jdbc.sql("""
                INSERT INTO ordering.order_amendments (
                    id, tenant_id, order_id, status, base_revision, quote_id, delta_total_minor,
                    requires_approval, approval_request_id, idempotency_key, expires_at,
                    created_by_actor_type, created_by_actor_id, version, created_at, updated_at)
                VALUES (:id, :tenantId, :orderId, :status, :baseRevision, :quoteId, :delta,
                    :requiresApproval, :approvalRequestId, :idempotencyKey, :expiresAt,
                    :actorType, :actorId, 1, :now, :now)
                """).params(params).update();
    }

    public void insertCommand(
            UUID amendmentId, UUID tenantId, int sequence, AmendmentCommandType type, String payloadJson) {
        jdbc.sql("""
                INSERT INTO ordering.order_amendment_commands (
                    amendment_id, sequence, tenant_id, command_type, payload_json)
                VALUES (:amendmentId, :sequence, :tenantId, :type, :payload::jsonb)
                """)
                .param("amendmentId", amendmentId)
                .param("sequence", sequence)
                .param("tenantId", tenantId)
                .param("type", type.name())
                .param("payload", payloadJson)
                .update();
    }

    /**
     * Records the customer's agreement to an increase.
     *
     * <p>Guarded on the amendment's own version, so an attestation cannot land on
     * an amendment that expired or was withdrawn between the operator reading the
     * delta aloud and pressing the button.
     */
    public Optional<Integer> attestConfirmation(
            UUID tenantId, UUID amendmentId, int expectedVersion, String attestedBy, String channel, Instant now) {
        return jdbc.sql("""
                UPDATE ordering.order_amendments
                SET confirmation_attested_by = :by,
                    confirmation_attested_at = :now,
                    confirmation_channel = :channel,
                    status = 'PRICED',
                    version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                  AND status IN ('PRICED', 'AWAITING_CUSTOMER_CONFIRMATION')
                RETURNING version
                """)
                .param("tenantId", tenantId)
                .param("id", amendmentId)
                .param("expectedVersion", expectedVersion)
                .param("by", attestedBy)
                .param("channel", channel)
                .param("now", utc(now))
                .query(Integer.class)
                .optional();
    }

    /**
     * Marks the amendment applied and names the revision it produced.
     *
     * <p>The status predicate is inside the UPDATE. A duplicate apply affects no
     * row and the caller is told the amendment had already settled, rather than
     * appending a second revision for one operator's single click.
     */
    public Optional<Integer> markApplied(
            UUID tenantId, UUID amendmentId, int expectedVersion, int appliedRevision, Instant now) {
        return jdbc.sql("""
                UPDATE ordering.order_amendments
                SET status = 'APPLIED',
                    applied_revision = :revision,
                    settled_at = :now,
                    version = version + 1,
                    updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                  AND status = 'PRICED'
                RETURNING version
                """)
                .param("tenantId", tenantId)
                .param("id", amendmentId)
                .param("expectedVersion", expectedVersion)
                .param("revision", appliedRevision)
                .param("now", utc(now))
                .query(Integer.class)
                .optional();
    }

    /** Withdraws an open amendment. The row stays: it is evidence of what was tried. */
    public boolean markRejected(UUID tenantId, UUID amendmentId, String reasonCode, Instant now) {
        return jdbc.sql("""
                UPDATE ordering.order_amendments
                SET status = 'REJECTED', rejected_reason_code = :reason, settled_at = :now,
                    version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id
                  AND status IN ('DRAFT', 'PRICED', 'AWAITING_CUSTOMER_CONFIRMATION',
                                 'AWAITING_PAYMENT')
                """)
                        .param("tenantId", tenantId)
                        .param("id", amendmentId)
                        .param("reason", reasonCode)
                        .param("now", utc(now))
                        .update()
                == 1;
    }

    /**
     * Expires amendments whose ADR 0018 quote TTL has run out.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} so two workers never expire one amendment
     * twice and a slow worker does not stall the rest behind it.
     *
     * @return how many were expired
     */
    public int expireOverdue(Instant now, int batchSize) {
        return jdbc.sql("""
                WITH due AS (
                    SELECT id FROM ordering.order_amendments
                    WHERE expires_at <= :now
                      AND status IN ('DRAFT', 'PRICED', 'AWAITING_CUSTOMER_CONFIRMATION',
                                     'AWAITING_PAYMENT')
                    ORDER BY expires_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT :batchSize
                )
                UPDATE ordering.order_amendments AS amendment
                SET status = 'EXPIRED', settled_at = :now, version = amendment.version + 1,
                    updated_at = :now
                FROM due
                WHERE amendment.id = due.id
                """)
                .param("now", utc(now))
                .param("batchSize", batchSize)
                .update();
    }

    public Optional<AmendmentRow> find(UUID tenantId, UUID amendmentId) {
        return jdbc.sql(SELECT_AMENDMENT + " WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", amendmentId)
                .query(JdbcOrderAmendmentStore::mapAmendment)
                .optional();
    }

    public Optional<AmendmentRow> findByIdempotencyKey(UUID tenantId, String idempotencyKey) {
        return jdbc.sql(SELECT_AMENDMENT + " WHERE tenant_id = :tenantId AND idempotency_key = :key")
                .param("tenantId", tenantId)
                .param("key", idempotencyKey)
                .query(JdbcOrderAmendmentStore::mapAmendment)
                .optional();
    }

    /** The open amendment on this order, if one operator already has it. */
    public Optional<AmendmentRow> findOpen(UUID tenantId, UUID orderId) {
        return jdbc.sql(SELECT_AMENDMENT + """
                 WHERE tenant_id = :tenantId AND order_id = :orderId
                   AND status IN ('DRAFT', 'PRICED', 'AWAITING_CUSTOMER_CONFIRMATION',
                                  'AWAITING_PAYMENT')
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(JdbcOrderAmendmentStore::mapAmendment)
                .optional();
    }

    public List<AmendmentRow> forOrder(UUID tenantId, UUID orderId) {
        return jdbc.sql(SELECT_AMENDMENT + """
                 WHERE tenant_id = :tenantId AND order_id = :orderId
                 ORDER BY created_at
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query(JdbcOrderAmendmentStore::mapAmendment)
                .list();
    }

    public List<CommandRow> commands(UUID tenantId, UUID amendmentId) {
        return jdbc.sql("""
                SELECT sequence, command_type, payload_json::text AS payload_json,
                       rejected_reason_code
                FROM ordering.order_amendment_commands
                WHERE tenant_id = :tenantId AND amendment_id = :amendmentId
                ORDER BY sequence
                """)
                .param("tenantId", tenantId)
                .param("amendmentId", amendmentId)
                .query((row, number) -> new CommandRow(
                        row.getInt("sequence"),
                        AmendmentCommandType.valueOf(row.getString("command_type")),
                        row.getString("payload_json"),
                        row.getString("rejected_reason_code")))
                .list();
    }

    private static final String SELECT_AMENDMENT = """
            SELECT id, tenant_id, order_id, status, base_revision, applied_revision, quote_id,
                   delta_total_minor, requires_approval, approval_request_id,
                   confirmation_attested_by, confirmation_attested_at, confirmation_channel,
                   idempotency_key, expires_at, rejected_reason_code, created_by_actor_type,
                   created_by_actor_id, version, created_at, settled_at
            FROM ordering.order_amendments""";

    private static AmendmentRow mapAmendment(ResultSet row, int number) throws SQLException {
        return new AmendmentRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("order_id", UUID.class),
                AmendmentStatus.valueOf(row.getString("status")),
                row.getInt("base_revision"),
                // getInt answers 0 for SQL NULL, and revision 0 does not exist:
                // an amendment nobody applied would read as one that produced a
                // revision numbered zero.
                row.getObject("applied_revision", Integer.class),
                row.getObject("quote_id", UUID.class),
                row.getLong("delta_total_minor"),
                row.getBoolean("requires_approval"),
                row.getObject("approval_request_id", UUID.class),
                row.getString("confirmation_attested_by"),
                instantOrNull(row, "confirmation_attested_at"),
                row.getString("confirmation_channel"),
                row.getString("idempotency_key"),
                row.getObject("expires_at", OffsetDateTime.class).toInstant(),
                row.getString("rejected_reason_code"),
                row.getString("created_by_actor_type"),
                row.getString("created_by_actor_id"),
                row.getInt("version"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                instantOrNull(row, "settled_at"));
    }

    private static @Nullable Instant instantOrNull(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record NewAmendment(
            UUID id,
            UUID tenantId,
            UUID orderId,
            AmendmentStatus status,
            int baseRevision,
            @Nullable UUID quoteId,
            long deltaTotalMinor,
            boolean requiresApproval,
            @Nullable UUID approvalRequestId,
            String idempotencyKey,
            Instant expiresAt,
            String createdByActorType,
            String createdByActorId,
            Instant createdAt) {}

    public record AmendmentRow(
            UUID id,
            UUID tenantId,
            UUID orderId,
            AmendmentStatus status,
            int baseRevision,
            Integer appliedRevision,
            UUID quoteId,
            long deltaTotalMinor,
            boolean requiresApproval,
            UUID approvalRequestId,
            String confirmationAttestedBy,
            @Nullable Instant confirmationAttestedAt,
            String confirmationChannel,
            String idempotencyKey,
            Instant expiresAt,
            String rejectedReasonCode,
            String createdByActorType,
            String createdByActorId,
            int version,
            Instant createdAt,
            @Nullable Instant settledAt) {}

    public record CommandRow(
            int sequence, AmendmentCommandType commandType, String payloadJson, String rejectedReasonCode) {}
}
