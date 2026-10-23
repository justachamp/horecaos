package uz.horecaos.platform.conversations.application;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.customers.spi.ErasesCustomerData;
import uz.horecaos.platform.customers.spi.ErasureCounts;

/**
 * Deletes the conversations an erased customer had with the restaurant (ADR 0029, ADR 0059, ADR 0069).
 *
 * <p>A conversation is the transcript of what a person typed into the restaurant's bot or answered to its flow: the words
 * themselves, envelope-encrypted (V0108), the answers a flow captured, and the Telegram chat id that says which person's
 * phone it was. The retention sweeper already deletes this after {@code retention_months}; an erasure is the same deletion
 * ahead of time, for the conversations linked to her account. It includes the assistant's side of the transcript, since
 * the assistant's replies are messages in the same conversation (the turn ledger holds no words, V0511).
 *
 * <p>Deleted, not blanked: a conversation row without its messages is an inbox entry for nobody, and the chat id on it is
 * the identifier the erasure exists to remove. Messages and flow runs go first, as {@code fk_flow_run_conversation} has no
 * cascade. A conversation never linked to an account (a guest who typed to the bot) is not reachable from an account
 * and stays until its retention ends.
 */
@Component
@ErasesCustomerData(
        name = "conversations",
        tables = {"conversations.conversation_messages", "conversations.flow_runs", "conversations.conversations"})
public class ConversationsErasureParticipant implements CustomerErasureParticipant {

    private final JdbcClient jdbc;

    public ConversationsErasureParticipant(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public ErasureCounts erase(UUID tenantId, UUID customerAccountId, ErasedContacts held) {
        int messages = jdbc.sql("""
                DELETE FROM conversations.conversation_messages m
                 USING conversations.conversations c
                 WHERE c.tenant_id = :tenantId AND c.customer_account_id = :accountId
                   AND m.tenant_id = :tenantId AND m.conversation_id = c.id
                """)
                .param("tenantId", tenantId)
                .param("accountId", customerAccountId)
                .update();
        int runs = jdbc.sql("""
                DELETE FROM conversations.flow_runs r
                 USING conversations.conversations c
                 WHERE c.tenant_id = :tenantId AND c.customer_account_id = :accountId
                   AND r.tenant_id = :tenantId AND r.conversation_id = c.id
                """)
                .param("tenantId", tenantId)
                .param("accountId", customerAccountId)
                .update();
        int conversations = jdbc.sql("""
                DELETE FROM conversations.conversations
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                """)
                .param("tenantId", tenantId)
                .param("accountId", customerAccountId)
                .update();
        return ErasureCounts.builder()
                .add("conversations.conversation_messages", messages)
                .add("conversations.flow_runs", runs)
                .add("conversations.conversations", conversations)
                .build();
    }
}
