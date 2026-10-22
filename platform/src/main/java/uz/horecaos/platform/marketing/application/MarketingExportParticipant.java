package uz.horecaos.platform.marketing.application;

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
 * What marketing has worked out about a customer and done with it (ADR 0029, ADR 0044): the profile its segments are built
 * from (orders, spend, how she arrived), which audiences she was placed in and why not, the channels she asked not to be
 * contacted on, and the marketing messages sent to her.
 *
 * <p>The staff member who applied a suppression, and the campaign internals, are the tenant's; the fact that she was
 * suppressed, on which channel and since when is hers.
 */
@Component
@ExportsCustomerData(
        name = "marketing",
        tables = {
            "marketing.customer_metrics",
            "marketing.audience_snapshot_members",
            "marketing.suppressions",
            "marketing.marketing_sends"
        })
public class MarketingExportParticipant implements CustomerExportParticipant {

    private static final int FETCH = ExportTable.MAX_ROWS + 1;

    private final JdbcClient jdbc;

    public MarketingExportParticipant(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional(readOnly = true)
    public ExportSection export(ExportSubject subject) {
        return ExportSection.of(
                table(subject, "marketing.customer_metrics", """
                        SELECT brand_id, order_count, completed_order_count, cancelled_order_count, gross_spend_minor,
                               net_spend_minor, average_check_minor, first_order_at, last_order_at, registered_at,
                               acquisition_channel, preferred_locale, birth_month_day, last_marketing_message_at,
                               marketing_messages_7d, marketing_messages_30d, updated_at
                          FROM marketing.customer_metrics
                         WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                         ORDER BY updated_at DESC, brand_id
                         LIMIT :limit
                        """),
                table(subject, "marketing.audience_snapshot_members", """
                        SELECT m.snapshot_id, m.inclusion_status, m.exclusion_reason, m.locale_at_evaluation,
                               m.completed_order_count_at_evaluation, m.net_spend_minor_at_evaluation,
                               m.days_since_last_order_at_evaluation
                          FROM marketing.audience_snapshot_members m
                         WHERE m.tenant_id = :tenantId AND m.customer_account_id = :accountId
                         ORDER BY m.snapshot_id DESC
                         LIMIT :limit
                        """),
                table(subject, "marketing.suppressions", """
                        SELECT id, brand_id, channel, reason, applied_at, expires_at, lifted_at
                          FROM marketing.suppressions
                         WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                         ORDER BY applied_at DESC, id DESC
                         LIMIT :limit
                        """),
                table(subject, "marketing.marketing_sends", """
                        SELECT id, brand_id, channel, source_type, sent_at
                          FROM marketing.marketing_sends
                         WHERE tenant_id = :tenantId AND customer_account_id = :accountId
                         ORDER BY sent_at DESC, id DESC
                         LIMIT :limit
                        """));
    }

    private ExportTable table(ExportSubject subject, String name, String sql) {
        List<Map<String, Object>> raw = jdbc.sql(sql)
                .param("tenantId", subject.tenantId())
                .param("accountId", subject.customerAccountId())
                .param("limit", FETCH)
                .query()
                .listOfRows();
        List<Map<String, @Nullable Object>> rows =
                raw.stream().map(ExportRow::render).toList();
        return ExportTable.ofNewestFirst(name, rows);
    }
}
