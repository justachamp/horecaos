package uz.horecaos.platform.voice.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.spi.CustomerExportParticipant;
import uz.horecaos.platform.customers.spi.ExportReveal;
import uz.horecaos.platform.customers.spi.ExportSection;
import uz.horecaos.platform.customers.spi.ExportTable;
import uz.horecaos.platform.customers.spi.ExportsCustomerData;
import uz.horecaos.platform.iam.api.protection.FieldProtection;

/**
 * The calls she made to the business (ADR 0029): when, in which direction, how long, and the number she called from, opened
 * under the producing person's purpose. The operator who answered, the line and the roster at the time are the tenant's.
 */
@Component
@ExportsCustomerData(
        name = "voice",
        tables = {"voice.call_events"})
public class VoiceExportParticipant implements CustomerExportParticipant {

    private static final int FETCH = ExportTable.MAX_ROWS + 1;

    private final JdbcClient jdbc;
    private final FieldProtection protection;

    public VoiceExportParticipant(JdbcClient jdbc, FieldProtection protection) {
        this.jdbc = jdbc;
        this.protection = protection;
    }

    @Override
    @Transactional(readOnly = true)
    public ExportSection export(ExportSubject subject) {
        List<Map<String, @Nullable Object>> rows = new ArrayList<>();
        for (Map<String, Object> raw : jdbc.sql("""
                SELECT id, brand_id, event_type, direction, caller_number_encrypted, duration_seconds, occurred_at
                  FROM voice.call_events
                 WHERE tenant_id = :tenantId AND resolved_customer_account_id = :accountId
                 ORDER BY occurred_at DESC, id DESC
                 LIMIT :limit
                """)
                .param("tenantId", subject.tenantId())
                .param("accountId", subject.customerAccountId())
                .param("limit", FETCH)
                .query()
                .listOfRows()) {
            rows.add(ExportReveal.row(protection, subject, "voice.call_events", "id", raw));
        }
        return ExportSection.of(ExportTable.ofNewestFirst("voice.call_events", rows));
    }
}
