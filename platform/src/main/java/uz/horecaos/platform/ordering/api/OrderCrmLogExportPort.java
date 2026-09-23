package uz.horecaos.platform.ordering.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the export centre's {@code ORDER_CRM_LOG} report needs from the {@code ordering} module
 * (wave 9 w4-reports-distance-crm, ADR 0043/ADR 0029), on the same inverted-dependency shape
 * {@code CustomerDirectoryExportPort}'s own doc explains for {@code CUSTOMER_DIRECTORY}: {@code
 * reporting -> ordering.api} keeps the dependency arrow pointing one way, rather than {@code
 * reporting.application} reaching into {@code ordering.application.OrderCrmLogQueryService}
 * directly, which {@code ModularArchitectureTests} refuses.
 *
 * <p>This is the one export the export centre queues that never reads {@code
 * reporting.fact_order} — row 7.2a's own point: the console order log's customer, operator and
 * courier columns come from {@code ordering}/{@code fulfillment} directly, the same read {@code
 * OrderCrmLogController} serves, so an export of it is an export of that read, not of a
 * reporting fact.
 */
public interface OrderCrmLogExportPort {

    /**
     * The row ceiling a decrypted (PII-included) export is bound to regardless of what {@code
     * rowQuota} the caller of the export passes — the same reasoning {@code
     * CustomerDirectoryExportPort#PII_ROW_LIMIT} states for its own report.
     */
    int PII_ROW_LIMIT = 2000;

    /**
     * @param includePii whether the customer name/phone columns may be revealed at all — the
     *                   export centre's own {@code customer.pii.export} decision, already made
     *                   by the time this is called. {@code false} never decrypts a column.
     * @param rowQuota   the ceiling to apply when {@code includePii} is {@code false}. Ignored
     *                   when it is {@code true}: {@link #PII_ROW_LIMIT} governs regardless.
     */
    ExportBundle export(
            UUID tenantId, Instant from, Instant to, List<UUID> locationIds, boolean includePii, int rowQuota);

    /**
     * @param customerName  null unless the export included the PII group, or the order carried
     *                      no customer snapshot
     * @param customerPhone null unless the export included the PII group, or the order carried
     *                      no phone
     */
    record ExportedRow(
            UUID orderId,
            Instant occurredAt,
            UUID locationId,
            String customerType,
            @Nullable String customerName,
            @Nullable String customerPhone,
            String operatorPrincipalId,
            @Nullable String courierDisplayReference) {}

    record ExportBundle(List<ExportedRow> rows, boolean truncated) {}
}
