package uz.horecaos.platform.ordering.application;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.api.OrderCrmLogExportPort;

/**
 * Implements {@link OrderCrmLogExportPort} over {@link OrderCrmLogQueryService} — the export
 * centre's {@code ORDER_CRM_LOG} report is exactly this module's own console read, bounded to a
 * row quota rather than paged (wave 9 w4-reports-distance-crm).
 *
 * <p>Writes no audit fact of its own: unlike {@code CustomerListQueryService#exportFiltered},
 * {@link OrderCrmLogQueryService#log} has no other caller that needs the same record, so the
 * export centre's own {@code report.export.completed} fact ({@code ReportExportService#run})
 * is the whole of this export's audit trail — see that class's own doc for what it records.
 */
@Service
public class OrderCrmLogExportAdapter implements OrderCrmLogExportPort {

    private final OrderCrmLogQueryService log;

    public OrderCrmLogExportAdapter(OrderCrmLogQueryService log) {
        this.log = log;
    }

    @Override
    @Transactional(readOnly = true)
    public ExportBundle export(
            UUID tenantId, Instant from, Instant to, List<UUID> locationIds, boolean includePii, int rowQuota) {
        int quota = includePii ? PII_ROW_LIMIT : rowQuota;
        // One row past the quota, same trick ReportingController#orders'
        // maybeMore uses: enough to tell "there was at least one more" from
        // "that was exactly all of it" without a second count query.
        List<OrderCrmLogQueryService.CrmLogEntry> entries = log.log(tenantId, from, to, locationIds, quota + 1, null);

        boolean truncated = entries.size() > quota;
        List<OrderCrmLogQueryService.CrmLogEntry> bounded = truncated ? entries.subList(0, quota) : entries;

        List<ExportedRow> rows = bounded.stream()
                .map(entry -> new ExportedRow(
                        entry.orderId(),
                        entry.occurredAt(),
                        entry.locationId(),
                        entry.customerType(),
                        includePii ? entry.customerName() : null,
                        includePii ? entry.customerPhone() : null,
                        entry.operatorPrincipalId(),
                        entry.courierDisplayReference()))
                .toList();

        return new ExportBundle(rows, truncated);
    }
}
