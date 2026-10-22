package uz.horecaos.platform.notifications.application;

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
 * How a customer asked to be told things and what she was told (ADR 0029): her preferences per class and channel with quiet
 * hours, the delivery endpoints on file for her (which kind, whether verified, since when; the number itself is her contact
 * point in the customers section), and the log of messages sent to her (class, channel, template, status, time). The log
 * holds no recipient and no body by design (V0026), so there is nothing more to give.
 */
@Component
@ExportsCustomerData(
        name = "notifications",
        tables = {
            "notifications.notification_preferences",
            "notifications.recipient_endpoints",
            "notifications.notifications"
        })
public class NotificationsExportParticipant implements CustomerExportParticipant {

    private static final int FETCH = ExportTable.MAX_ROWS + 1;

    private final JdbcClient jdbc;

    public NotificationsExportParticipant(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public ExportSection export(ExportSubject subject) {
        return ExportSection.of(
                table(subject, "notifications.notification_preferences", """
                        SELECT brand_id, notification_class, channel, enabled, quiet_hours_start, quiet_hours_end,
                               timezone, updated_at
                          FROM notifications.notification_preferences
                         WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                         ORDER BY updated_at DESC, id DESC
                         LIMIT :limit
                        """),
                table(subject, "notifications.recipient_endpoints", """
                        SELECT id, endpoint_type, verification_status, status, last_verified_at, created_at
                          FROM notifications.recipient_endpoints
                         WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                         ORDER BY created_at DESC, id DESC
                         LIMIT :limit
                        """),
                table(subject, "notifications.notifications", """
                        SELECT id, notification_class, channel, template_key, locale, status, suppression_reason,
                               scheduled_at
                          FROM notifications.notifications
                         WHERE tenant_id = :tenantId AND recipient_account_id = :accountId
                         ORDER BY scheduled_at DESC, id DESC
                         LIMIT :limit
                        """));
    }

    private ExportTable table(ExportSubject subject, String name, String sql) {
        List<Map<String, @Nullable Object>> rows = jdbc
                .sql(sql)
                .param("tenantId", subject.tenantId())
                .param("accountId", subject.customerAccountId())
                .param("limit", FETCH)
                .query()
                .listOfRows()
                .stream()
                .map(ExportRow::render)
                .toList();
        return ExportTable.ofNewestFirst(name, rows);
    }
}
