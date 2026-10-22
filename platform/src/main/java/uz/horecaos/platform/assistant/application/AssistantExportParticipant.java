package uz.horecaos.platform.assistant.application;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.spi.CustomerExportParticipant;
import uz.horecaos.platform.customers.spi.ExportRow;
import uz.horecaos.platform.customers.spi.ExportSection;
import uz.horecaos.platform.customers.spi.ExportTable;
import uz.horecaos.platform.customers.spi.ExportsCustomerData;
import uz.horecaos.platform.iam.api.protection.FieldProtection;

/**
 * The questions she put to the assistant (ADR 0029, ADR 0069), as the assistant recorded them: when, in which language, what
 * kind of question, and how it was answered or why it was not. The words of the exchange are in the conversations section;
 * the turn log holds the shape, the model and the cost, and a keyed pseudonym of her account that this finds her by.
 */
@Component
@ExportsCustomerData(
        name = "assistant",
        tables = {"assistant.turns"})
public class AssistantExportParticipant implements CustomerExportParticipant {

    private static final int FETCH = ExportTable.MAX_ROWS + 1;

    private final JdbcClient jdbc;
    private final FieldProtection protection;

    public AssistantExportParticipant(JdbcClient jdbc, FieldProtection protection) {
        this.jdbc = jdbc;
        this.protection = protection;
    }

    @Override
    @Transactional(readOnly = true)
    public ExportSection export(ExportSubject subject) {
        String pseudonym = protection.lookupHash(
                subject.tenantId(),
                AssistantTurnService.CUSTOMER_PSEUDONYM_DOMAIN,
                subject.customerAccountId().toString());
        List<Map<String, @Nullable Object>> rows = jdbc
                .sql("""
                SELECT id, conversation_id, occurred_at, locale, question_kinds, outcome, refusal_reason,
                       served_from_cache
                  FROM assistant.turns
                 WHERE tenant_id = :tenantId AND customer_pseudonym = :pseudonym
                 ORDER BY occurred_at DESC, id DESC
                 LIMIT :limit
                """)
                .param("tenantId", subject.tenantId())
                .param("pseudonym", pseudonym)
                .param("limit", FETCH)
                .query()
                .listOfRows()
                .stream()
                .map(ExportRow::render)
                .toList();
        return ExportSection.of(ExportTable.ofNewestFirst("assistant.turns", rows));
    }
}
