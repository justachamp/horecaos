package uz.horecaos.platform.conversations.application;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;

/**
 * {@code conversations.conversation_messages} (V0108). Envelope-encrypts
 * every body, both directions, per ADR 0059's PII posture — see the
 * migration's own comment on why an outbound message is not assumed safe.
 */
@Repository
class ConversationMessageStore {

    static final String TABLE = "conversations.conversation_messages";
    static final String BODY_COLUMN = "body_protected";

    enum Direction {
        INBOUND,
        OUTBOUND
    }

    private final JdbcClient jdbc;
    private final Clock clock;
    private final FieldProtection protection;

    ConversationMessageStore(JdbcClient jdbc, Clock clock, FieldProtection protection) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.protection = protection;
    }

    void record(UUID tenantId, UUID conversationId, Direction direction, @Nullable String blockId, String body) {
        UUID id = UUID.randomUUID();
        String protectedBody = protection
                .protect(tenantId, DataClass.PERSONAL, new RecordRef(TABLE, BODY_COLUMN, id), body)
                .serialize();
        Instant now = clock.instant();
        jdbc.sql("""
                INSERT INTO conversations.conversation_messages (
                    id, tenant_id, conversation_id, direction, block_id, body_protected, occurred_at)
                VALUES (:id, :tenantId, :conversationId, :direction, :blockId, :body, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("conversationId", conversationId)
                .param("direction", direction.name())
                .param("blockId", blockId)
                .param("body", protectedBody)
                .param("now", OffsetDateTime.ofInstant(now, ZoneOffset.UTC))
                .update();
    }
}
