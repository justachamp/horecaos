package uz.horecaos.platform.conversations.application;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.conversations.api.ChannelKind;
import uz.horecaos.platform.conversations.api.ConversationChannelRef;
import uz.horecaos.platform.conversations.domain.ConversationState;

/** {@code conversations.conversations} (V0108). */
@Repository
class ConversationRepository {

    static final int DEFAULT_RETENTION_MONTHS = 12;

    private final JdbcClient jdbc;
    private final Clock clock;

    ConversationRepository(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    Optional<Row> findById(UUID tenantId, UUID id) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, installation_id, channel, channel_chat_id,
                       customer_account_id, state, retention_months, version
                FROM conversations.conversations
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", id)
                .query(ConversationRepository::map)
                .optional();
    }

    Optional<Row> find(UUID tenantId, UUID brandId, ChannelKind channel, long externalChatId) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, installation_id, channel, channel_chat_id,
                       customer_account_id, state, retention_months, version
                FROM conversations.conversations
                WHERE tenant_id = :tenantId AND brand_id = :brandId AND channel = :channel
                  AND channel_chat_id = :chatId
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("channel", channel.name())
                .param("chatId", externalChatId)
                .query(ConversationRepository::map)
                .optional();
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

    private static Row map(java.sql.ResultSet row, int number) throws java.sql.SQLException {
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
                row.getLong("version"));
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
            long version) {}
}
