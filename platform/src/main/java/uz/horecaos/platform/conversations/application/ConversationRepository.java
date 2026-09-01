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
import uz.horecaos.platform.conversations.api.ChannelKind;
import uz.horecaos.platform.conversations.api.ConversationChannelRef;
import uz.horecaos.platform.conversations.application.ConversationMessageStore.Direction;
import uz.horecaos.platform.conversations.domain.ConversationState;

/** {@code conversations.conversations} (V0108, V0109). */
@Repository
class ConversationRepository {

    static final int DEFAULT_RETENTION_MONTHS = 12;

    private static final String COLUMNS = """
            id, tenant_id, brand_id, installation_id, channel, channel_chat_id,
            customer_account_id, state, retention_months, assigned_to, last_read_at,
            last_read_by, updated_at, version
            """;

    private final JdbcClient jdbc;
    private final Clock clock;

    ConversationRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Optional<Row> findById(UUID tenantId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS
                        + " FROM conversations.conversations WHERE tenant_id = :tenantId AND id = :id")
                .param("tenantId", tenantId)
                .param("id", id)
                .query(ConversationRepository::map)
                .optional();
    }

    Optional<Row> find(UUID tenantId, UUID brandId, ChannelKind channel, long externalChatId) {
        return jdbc.sql("""
                SELECT %s
                FROM conversations.conversations
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND channel = :channel
                  AND channel_chat_id = :chatId
                """.formatted(COLUMNS))
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("channel", channel.name())
                .param("chatId", externalChatId)
                .query(ConversationRepository::map)
                .optional();
    }

    /**
     * A brand's conversations, needs-attention first (ADR 0059 stage 2): every
     * {@code HANDED_TO_OPERATOR} conversation, then every other conversation
     * whose newest message is INBOUND (the flow engine has an active run but
     * has not — or cannot — answer it, e.g. free text arriving while a run
     * waits on a delay or an unmatched button), then everything else by
     * last-activity descending. "Last activity" is the newer of the
     * conversation's own {@code updated_at} (a state transition) and its
     * newest message's {@code occurred_at}, via the lateral join below —
     * {@code updated_at} alone would rank a long-silent HANDED_TO_OPERATOR
     * conversation above one the customer just wrote into again.
     *
     * <p>No cursor: mirrors {@code OperationsOrderController.list}'s own
     * simple {@code limit}, the explicit operations-surface precedent this
     * endpoint follows.
     */
    List<ListRow> listForBrand(UUID tenantId, UUID brandId, int limit) {
        return jdbc.sql("""
                SELECT c.id, c.channel, c.customer_account_id, c.state, c.updated_at,
                       lm.direction AS last_message_direction,
                       GREATEST(c.updated_at, COALESCE(lm.occurred_at, c.updated_at)) AS last_activity_at
                FROM conversations.conversations c
                LEFT JOIN LATERAL (
                    SELECT direction, occurred_at
                    FROM conversations.conversation_messages m
                    WHERE m.tenant_id = c.tenant_id AND m.conversation_id = c.id
                    ORDER BY m.occurred_at DESC, m.id DESC
                    LIMIT 1
                ) lm ON true
                WHERE c.tenant_id = :tenantId AND c.brand_id = :brandId
                ORDER BY
                    CASE
                        WHEN c.state = 'HANDED_TO_OPERATOR' THEN 0
                        WHEN c.state = 'FLOW_ACTIVE' AND lm.direction = 'INBOUND' THEN 1
                        ELSE 2
                    END,
                    last_activity_at DESC
                LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("limit", limit)
                .query(ConversationRepository::mapListRow)
                .list();
    }

    /**
     * The conversation for this channel identity, creating it on first
     * contact — a chat with no linked customer is still a conversation (ADR
     * 0059). Races the same channel identity's own first message from a
     * second delivery onto {@code ON CONFLICT DO NOTHING}; either writer's
     * subsequent read sees the one row that won.
     */
    Row getOrCreate(ConversationChannelRef channel) {
        Optional<Row> existing =
                find(channel.tenantId(), channel.brandId(), channel.channel(), channel.externalChatId());
        if (existing.isPresent()) {
            return existing.get();
        }
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO conversations.conversations (
                    id, tenant_id, brand_id, installation_id, channel, channel_chat_id,
                    customer_account_id, state, retention_months, created_at, updated_at)
                VALUES (:id, :tenantId, :brandId, :installationId, :channelKind, :chatId,
                    :customerAccountId, 'IDLE', :retentionMonths, :now, :now)
                ON CONFLICT (tenant_id, brand_id, channel, channel_chat_id) DO NOTHING
                """)
                .param("id", id)
                .param("tenantId", channel.tenantId())
                .param("brandId", channel.brandId())
                .param("installationId", channel.installationId())
                .param("channelKind", channel.channel().name())
                .param("chatId", channel.externalChatId())
                .param("customerAccountId", channel.customerAccountId())
                .param("retentionMonths", DEFAULT_RETENTION_MONTHS)
                .param("now", utc(now))
                .update();
        return find(channel.tenantId(), channel.brandId(), channel.channel(), channel.externalChatId())
                .orElseThrow(() -> new IllegalStateException("Conversation insert-or-find lost its own row"));
    }

    /** Unconditional — used only by the flow engine, whose own {@code flow_runs} CAS already serializes these writes. */
    void updateState(UUID tenantId, UUID conversationId, ConversationState newState) {
        jdbc.sql("""
                UPDATE conversations.conversations
                SET state = :state, version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("state", newState.name())
                .param("tenantId", tenantId)
                .param("id", conversationId)
                .param("now", utc(clock.instant()))
                .update();
    }

    /**
     * The inbox's own state transition, guarded by the aggregate version an
     * operator's client last read (ADR 0031's {@code If-Match} discipline) —
     * unlike {@link #updateState}, two operators racing the same conversation
     * (two takeovers, a takeover racing a close) settle at one outcome rather
     * than one silently overwriting the other. {@code assignedTo} is written
     * in the same statement: takeover sets it to the acting operator,
     * return-to-flow and close both pass null to clear it.
     *
     * @return whether this call's version was still current
     */
    boolean transition(
            UUID tenantId,
            UUID conversationId,
            long expectedVersion,
            ConversationState newState,
            @Nullable String assignedTo) {
        return jdbc.sql("""
                UPDATE conversations.conversations
                SET state = :state, assigned_to = :assignedTo, version = version + 1, updated_at = :now
                WHERE tenant_id = :tenantId AND id = :id AND version = :expectedVersion
                """)
                        .param("state", newState.name())
                        .param("assignedTo", assignedTo)
                        .param("now", utc(clock.instant()))
                        .param("tenantId", tenantId)
                        .param("id", conversationId)
                        .param("expectedVersion", expectedVersion)
                        .update()
                == 1;
    }

    /**
     * Records that {@code principalSubject} opened this conversation's
     * history right now — {@code ConversationInboxService} calls this after
     * deciding (from the row this replaces) whether that decision needed a
     * fresh ADR 0027 audit fact. Deliberately not part of the {@code
     * version}-guarded aggregate: a read marker racing a concurrent state
     * transition is not a conflict worth refusing either side over.
     */
    void markRead(UUID tenantId, UUID conversationId, String principalSubject) {
        jdbc.sql("""
                UPDATE conversations.conversations
                SET last_read_at = :now, last_read_by = :principal
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("now", utc(clock.instant()))
                .param("principal", principalSubject)
                .param("tenantId", tenantId)
                .param("id", conversationId)
                .update();
    }

    /**
     * Claims up to {@code batchSize} {@code CLOSED} conversations whose own
     * retention window has passed and that carry no live message — {@code
     * ConversationRetentionSweeper}'s own candidate set, before it deletes
     * anything.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} holds each row for the rest of the
     * caller's transaction: the message-emptiness check here and the actual
     * deletes the sweeper issues next must see the same set, not one a
     * concurrent inbound message (which reopens a {@code CLOSED} conversation
     * to {@code HANDED_TO_OPERATOR} — see {@code ConversationEngine}) could
     * have changed in between.
     */
    List<Ref> claimClosedAndExpired(Instant now, int batchSize) {
        return jdbc.sql("""
                SELECT c.id, c.tenant_id
                  FROM conversations.conversations c
                 WHERE c.state = 'CLOSED'
                   AND c.updated_at < (CAST(:now AS timestamptz) - (c.retention_months * INTERVAL '1 month'))
                   AND NOT EXISTS (
                       SELECT 1 FROM conversations.conversation_messages m
                        WHERE m.tenant_id = c.tenant_id AND m.conversation_id = c.id
                   )
                 ORDER BY c.updated_at
                 LIMIT :batchSize
                 FOR UPDATE OF c SKIP LOCKED
                """)
                .param("now", utc(now))
                .param("batchSize", batchSize)
                .query((row, number) ->
                        new Ref(row.getObject("tenant_id", UUID.class), row.getObject("id", UUID.class)))
                .list();
    }

    /**
     * Deletes exactly the conversations {@link #claimClosedAndExpired}
     * claimed. The caller has already deleted their {@code flow_runs} first
     * ({@code fk_flow_run_conversation} has no cascade): a conversation this
     * sweep is allowed to remove has no active run by construction (closing a
     * conversation always ends its run first — see {@code
     * ConversationInboxService#close}), so nothing here is destroying live
     * work, only history the sweep's own claim already proved has aged out.
     */
    int deleteByIds(List<UUID> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("DELETE FROM conversations.conversations WHERE id = ANY(:ids)")
                .param("ids", ids.toArray(UUID[]::new))
                .update();
    }

    /** A conversation identity without its whole row — what a cross-tenant sweep claims. */
    record Ref(UUID tenantId, UUID conversationId) {}

    private static Row map(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        java.sql.Timestamp lastReadAt = row.getTimestamp("last_read_at");
        return new Row(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getObject("brand_id", UUID.class),
                row.getObject("installation_id", UUID.class),
                ChannelKind.valueOf(row.getString("channel")),
                row.getLong("channel_chat_id"),
                row.getObject("customer_account_id", UUID.class),
                ConversationState.valueOf(row.getString("state")),
                row.getInt("retention_months"),
                row.getString("assigned_to"),
                lastReadAt == null ? null : lastReadAt.toInstant(),
                row.getString("last_read_by"),
                java.util.Objects.requireNonNull(row.getTimestamp("updated_at")).toInstant(),
                row.getLong("version"));
    }

    private static ListRow mapListRow(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        String lastMessageDirection = row.getString("last_message_direction");
        return new ListRow(
                row.getObject("id", UUID.class),
                ChannelKind.valueOf(row.getString("channel")),
                row.getObject("customer_account_id", UUID.class),
                ConversationState.valueOf(row.getString("state")),
                lastMessageDirection == null ? null : Direction.valueOf(lastMessageDirection),
                java.util.Objects.requireNonNull(row.getTimestamp("last_activity_at"))
                        .toInstant());
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    record Row(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID installationId,
            ChannelKind channel,
            long channelChatId,
            @Nullable UUID customerAccountId,
            ConversationState state,
            int retentionMonths,
            @Nullable String assignedTo,
            @Nullable Instant lastReadAt,
            @Nullable String lastReadBy,
            Instant updatedAt,
            long version) {}

    /** One row of {@link #listForBrand} — no message bodies, ever (ADR 0059 stage 2's PII posture for the list). */
    record ListRow(
            UUID id,
            ChannelKind channel,
            @Nullable UUID customerAccountId,
            ConversationState state,
            @Nullable Direction lastMessageDirection,
            Instant lastActivityAt) {

        /**
         * HANDED_TO_OPERATOR always needs attention; a FLOW_ACTIVE
         * conversation needs it exactly when the newest message is INBOUND —
         * neither the engine nor an operator has answered it since.
         */
        boolean needsReply() {
            return state == ConversationState.HANDED_TO_OPERATOR
                    || (state == ConversationState.FLOW_ACTIVE && lastMessageDirection == Direction.INBOUND);
        }
    }
}
