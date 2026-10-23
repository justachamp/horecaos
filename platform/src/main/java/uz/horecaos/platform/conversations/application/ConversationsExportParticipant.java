package uz.horecaos.platform.conversations.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.spi.CustomerExportParticipant;
import uz.horecaos.platform.customers.spi.ExportReveal;
import uz.horecaos.platform.customers.spi.ExportRow;
import uz.horecaos.platform.customers.spi.ExportSection;
import uz.horecaos.platform.customers.spi.ExportTable;
import uz.horecaos.platform.customers.spi.ExportsCustomerData;
import uz.horecaos.platform.iam.api.protection.FieldProtection;

/**
 * Her chats with the business (ADR 0029): each conversation she was in, every message in it (what she wrote and what was
 * written back, opened under the producing person's purpose) and what an automated flow captured from her on the way.
 * Messages are bounded by the conversation's own retention, which the retention sweeper enforces.
 *
 * <p>The operator who answered is the tenant's employee: the message shows that it was the business, not who.
 */
@Component
@ExportsCustomerData(
        name = "conversations",
        tables = {"conversations.conversations", "conversations.conversation_messages", "conversations.flow_runs"})
public class ConversationsExportParticipant implements CustomerExportParticipant {

    private static final int FETCH = ExportTable.MAX_ROWS + 1;

    private final JdbcClient jdbc;
    private final FieldProtection protection;

    public ConversationsExportParticipant(JdbcClient jdbc, FieldProtection protection) {
        this.jdbc = jdbc;
        this.protection = protection;
    }

    @Override
    @Transactional(readOnly = true)
    public ExportSection export(ExportSubject subject) {
        List<Map<String, @Nullable Object>> conversations = plain(subject, """
                SELECT id, brand_id, channel, state, created_at, updated_at
                  FROM conversations.conversations
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                 ORDER BY created_at DESC, id DESC
                 LIMIT :limit
                """);

        List<Map<String, @Nullable Object>> messages = new ArrayList<>();
        for (Map<String, Object> raw : query(subject, """
                SELECT m.id, m.conversation_id, m.direction, m.body_protected, m.occurred_at
                  FROM conversations.conversation_messages m
                  JOIN conversations.conversations c ON c.tenant_id = m.tenant_id AND c.id = m.conversation_id
                 WHERE c.tenant_id = :tenantId AND c.customer_account_id = :accountId
                 ORDER BY m.occurred_at DESC, m.id DESC
                 LIMIT :limit
                """)) {
            messages.add(ExportReveal.row(protection, subject, "conversations.conversation_messages", "id", raw));
        }

        List<Map<String, @Nullable Object>> flowRuns = new ArrayList<>();
        for (Map<String, Object> raw : query(subject, """
                SELECT r.id, r.conversation_id, r.flow_version, r.status, r.captured_fields_protected, r.created_at
                  FROM conversations.flow_runs r
                  JOIN conversations.conversations c ON c.tenant_id = r.tenant_id AND c.id = r.conversation_id
                 WHERE c.tenant_id = :tenantId AND c.customer_account_id = :accountId
                 ORDER BY r.created_at DESC, r.id DESC
                 LIMIT :limit
                """)) {
            flowRuns.add(ExportReveal.row(protection, subject, "conversations.flow_runs", "id", raw));
        }
        return new ExportSection(
                List.of(
                        ExportTable.ofNewestFirst("conversations.conversations", conversations),
                        ExportTable.ofNewestFirst("conversations.conversation_messages", messages),
                        ExportTable.ofNewestFirst("conversations.flow_runs", flowRuns)),
                List.of("conversation_messages.operator_identity"));
    }

    private List<Map<String, @Nullable Object>> plain(ExportSubject subject, String sql) {
        return query(subject, sql).stream().map(ExportRow::render).toList();
    }

    private List<Map<String, Object>> query(ExportSubject subject, String sql) {
        return jdbc.sql(sql)
                .param("tenantId", subject.tenantId())
                .param("accountId", subject.customerAccountId())
                .param("limit", FETCH)
                .query()
                .listOfRows();
    }
}
