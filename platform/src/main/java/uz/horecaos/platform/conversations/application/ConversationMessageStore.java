package uz.horecaos.platform.conversations.application;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;

/**
 * {@code conversations.conversation_messages} (V0108, V0109). Envelope-encrypts
 * every body, every direction, per ADR 0059's PII posture — see the
 * migration's own comment on why an outbound message is not assumed safe.
 *
 * <p>{@link Direction#OPERATOR}, added in ADR 0059 stage 2, is a reply typed by
 * staff through the inbox — distinct from {@link Direction#OUTBOUND}, the flow
 * engine's own sends, so the inbox can tell "the engine answered" from "a
 * person answered" both for history display and for {@link
 * #latestDirection(UUID, UUID)}'s needs-reply computation.
 */
@Repository
class ConversationMessageStore {

    static final String TABLE = "conversations.conversation_messages";
    static final String BODY_COLUMN = "body_protected";

    enum Direction {
        INBOUND,
        OUTBOUND,
        OPERATOR
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final FieldProtection protection;

    ConversationMessageStore(JdbcClient jdbc, Clock clock, FieldProtection protection) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.protection = protection;
    }

    /**
     * Records an INBOUND or OUTBOUND message — never OPERATOR; see {@link
     * #recordOperatorReply}.
     *
     * @return the row just written, the same shape {@link #recordOperatorReply}
     *         already returns; every existing caller discards it, and {@code
     *         ConversationRetentionSweeperTests} is the first that needs the id
     */
    Row record(UUID tenantId, UUID conversationId, Direction direction, @Nullable String blockId, String body) {
        if (direction == Direction.OPERATOR) {
            throw new IllegalArgumentException(
                    "An OPERATOR message needs an acting principal — use recordOperatorReply");
        }
        return insert(tenantId, conversationId, direction, blockId, null, body);
    }

    /**
     * Records an operator's own reply, with the acting principal ADR 0027
     * needs on every audited action.
     *
     * @return the row just written, plaintext body already in hand — the
     *         caller never needs to re-read and re-decrypt what it just sent
     */
    Row recordOperatorReply(UUID tenantId, UUID conversationId, String actorPrincipalId, String body) {
        return insert(tenantId, conversationId, Direction.OPERATOR, null, actorPrincipalId, body);
    }

    private Row insert(
            UUID tenantId,
            UUID conversationId,
            Direction direction,
            @Nullable String blockId,
            @Nullable String actorPrincipalId,
            String body) {
        UUID id = UUID.randomUUID();
        String protectedBody = protection
                .protect(tenantId, DataClass.PERSONAL, new RecordRef(TABLE, BODY_COLUMN, id), body)
                .serialize();
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO conversations.conversation_messages (
                    id, tenant_id, conversation_id, direction, block_id, actor_principal_id,
                    body_protected, occurred_at)
                VALUES (:id, :tenantId, :conversationId, :direction, :blockId, :actorPrincipalId,
                    :body, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("direction", direction.name())
                .param("blockId", blockId)
                .param("actorPrincipalId", actorPrincipalId)
                .param("body", protectedBody)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
        return new Row(id, direction, blockId, actorPrincipalId, body, now);
    }

    /**
     * The full, decrypted history of a conversation, oldest first — the
     * inbox detail screen's whole purpose (ADR 0059: "the envelope-decrypted
     * free text is the inbox's purpose"). The caller (the inbox application
     * service) is responsible for the ADR 0027 audit fact this read needs;
     * this class only decrypts.
     */
    List<Row> history(UUID tenantId, UUID conversationId) {
        return jdbc.sql("""
                SELECT id, direction, block_id, actor_principal_id, body_protected, occurred_at
                FROM conversations.conversation_messages
                WHERE tenant_id = :tenantId AND conversation_id = :conversationId
                ORDER BY occurred_at, id
                """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .query((row, number) -> mapRow(row, tenantId, protection))
                .list();
    }

    /**
     * The direction of the most recently occurring message, if any — what
     * {@code ConversationInboxService} uses to decide a FLOW_ACTIVE
     * conversation's needs-reply flag: the newest message being INBOUND means
     * nobody, neither the flow engine nor an operator, has answered it since.
     */
    Optional<Direction> latestDirection(UUID tenantId, UUID conversationId) {
        return jdbc.sql("""
                SELECT direction FROM conversations.conversation_messages
                WHERE tenant_id = :tenantId AND conversation_id = :conversationId
                ORDER BY occurred_at DESC, id DESC
                LIMIT 1
                """)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .query(String.class)
                .optional()
                .map(Direction::valueOf);
    }

    /**
     * Hard-deletes messages older than the owning conversation's own {@code
     * retention_months} — the ADR 0029 gap V0108's own comment named:
     * "enforcement... is a named ADR 0029 gap, not built by this stage."
     *
     * <p>Batch-limited and lock-skipping, the same discipline {@code
     * JdbcCampaignStore#claimBatch} and {@code JdbcNotificationStore#claimDue}
     * already use for a bounded, concurrency-safe scan: {@code FOR UPDATE OF m
     * SKIP LOCKED} names only the message row to lock, not the conversation it
     * joins against, so this sweep never contends with an ordinary write to an
     * unrelated conversation.
     *
     * @return how many messages were deleted this call — never content, only
     *         the count, per this sweep's own logging discipline
     */
    int deleteExpired(Instant now, int batchSize) {
        return jdbc.sql("""
                WITH doomed AS (
                    SELECT m.id
                      FROM conversations.conversation_messages m
                      JOIN conversations.conversations c
                        ON c.tenant_id = m.tenant_id AND c.id = m.conversation_id
                     WHERE m.occurred_at < (CAST(:now AS timestamptz) - (c.retention_months * INTERVAL '1 month'))
                     ORDER BY m.occurred_at
                     LIMIT :batchSize
                     FOR UPDATE OF m SKIP LOCKED
                )
                DELETE FROM conversations.conversation_messages m
                 USING doomed
                 WHERE m.id = doomed.id
                """)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .param("batchSize", batchSize)
                .update();
    }

    private static Row mapRow(java.sql.ResultSet row, UUID tenantId, FieldProtection protection)
            throws java.sql.SQLException {
        UUID id = row.getObject("id", UUID.class);
        String bodyProtected = java.util.Objects.requireNonNull(row.getString("body_protected"));
        java.sql.Timestamp occurredAt = row.getTimestamp("occurred_at");
        String body = protection.reveal(
                tenantId,
                ProtectedValue.deserialize(bodyProtected),
                new RecordRef(TABLE, BODY_COLUMN, id),
                "conversations.inbox.history");
        return new Row(
                id,
                Direction.valueOf(row.getString("direction")),
                row.getString("block_id"),
                row.getString("actor_principal_id"),
                body,
                java.util.Objects.requireNonNull(occurredAt).toInstant());
    }

    /** One message, body already decrypted — {@link #history} never returns ciphertext. */
    record Row(
            UUID id,
            Direction direction,
            @Nullable String blockId,
            @Nullable String actorPrincipalId,
            String body,
            Instant occurredAt) {}
}
