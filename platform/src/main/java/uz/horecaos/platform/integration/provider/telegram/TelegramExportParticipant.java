package uz.horecaos.platform.integration.provider.telegram;

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

/**
 * Where an import from a messaging list matched her (ADR 0029, ADR 0063): the Telegram chat the list named, whether she was
 * subscribed there and what the import did with the row. The one-time codes that link a Telegram chat to an account are
 * credentials and are not part of what she carries out.
 */
@Component
@ExportsCustomerData(
        name = "telegram",
        tables = {"integration.sendpulse_import_run_rows"})
public class TelegramExportParticipant implements CustomerExportParticipant {

    private static final int FETCH = ExportTable.MAX_ROWS + 1;

    private final JdbcClient jdbc;

    public TelegramExportParticipant(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public ExportSection export(ExportSubject subject) {
        List<Map<String, @Nullable Object>> rows = jdbc
                .sql("""
                SELECT id, run_id, chat_id, subscribed, outcome, created_at
                  FROM integration.sendpulse_import_run_rows
                 WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                 ORDER BY created_at DESC, id DESC
                 LIMIT :limit
                """)
                .param("tenantId", subject.tenantId())
                .param("accountId", subject.customerAccountId())
                .param("limit", FETCH)
                .query()
                .listOfRows()
                .stream()
                .map(ExportRow::render)
                .toList();
        return ExportSection.of(ExportTable.ofNewestFirst("integration.sendpulse_import_run_rows", rows));
    }
}
