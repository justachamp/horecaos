package uz.horecaos.platform.reporting.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import uz.horecaos.platform.customers.api.CustomerDirectoryExportPort;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.ordering.api.OrderCrmLogExportPort;
import uz.horecaos.platform.reporting.domain.ReportExportColumn;
import uz.horecaos.platform.reporting.domain.ReportExportDefinition;
import uz.horecaos.platform.reporting.domain.ReportExportDefinition.OverQuota;

/**
 * The code-owned catalogue of exportable reports (ADR 0043, wave P28's own export centre).
 *
 * <p>A report key is declared here, not read from a table, for the same reason {@code
 * Capability}'s own doc gives for capabilities: the wire-level vocabulary is fixed at compile
 * time, so an unknown key fails the request loudly instead of a caller discovering at export time
 * that a typo silently matched nothing.
 *
 * <p><strong>{@link #CUSTOMER_DIRECTORY}, {@link #ORDER_CRM_LOG}, {@link #ORDER_REPORT_LOG} and
 * {@link #ORDER_REPORT_SUMMARY} are wired end to end.</strong> The registry exists so the next
 * report an export needs a case for is one new entry plus one new branch in {@link
 * ReportExportService}, not a second job queue — see that class's own doc for why a pluggable-
 * source abstraction was deliberately not built for a catalogue this size (AGENTS.md: "Do not
 * introduce infrastructure or patterns only for hypothetical scale").
 */
final class ReportExportRegistry {

    /**
     * The CRM grid ({@code CustomerListQueryService}'s own list), the report P28's own brief
     * names as the shape to model every other export on. {@code phone} is the one PII column;
     * everything else is already unencrypted on the row.
     */
    static final String CUSTOMER_DIRECTORY = "CUSTOMER_DIRECTORY";

    /**
     * Wave 9 w4-reports-distance-crm (7.2a): the console order log's own CRM columns — customer,
     * operator, courier — read via {@code ordering.api.OrderCrmLogExportPort}, never {@code
     * reporting.fact_order}. {@code customerName} and {@code customerPhone} are the PII group;
     * every other column is already unencrypted on the row.
     */
    static final String ORDER_CRM_LOG = "ORDER_CRM_LOG";

    /**
     * Wave 10 w5-reports-exports (7.2e): the row's own named «Заказы» report — the order-grain
     * commercial columns {@code order-reports-page.ts}'s «Заказы» tab renders, straight off {@code
     * reporting.fact_order} via {@code ReportQueryService#orders}. Deliberately carries no
     * customer/operator/courier column: {@link #ORDER_CRM_LOG} already exports that half from
     * {@code ordering.api.OrderCrmLogExportPort}, and joining the two server-side, inside this
     * module, would re-link a reporting fact to a customer by {@code orderId} — exactly what {@code
     * SubjectPseudonym} (row 7.2a) exists to prevent. The console page's own client-side join is
     * the only place the two halves meet. No column here is PII (ADR 0029: reporting carries no
     * PERSONAL field at all), so {@code customer.pii.export} gates nothing on this report — a
     * viewer without it still gets every column, which is the correct behaviour, not a bug.
     */
    static final String ORDER_REPORT_LOG = "ORDER_REPORT_LOG";

    /**
     * Wave 10 w5-reports-exports (7.2e): the row's own named «Сводка» report — the by-branch,
     * by-channel, by-fulfilment revenue rollup {@code order-reports-page.ts}'s «Сводка» tab
     * renders (both of its own pivots read from these same folded buckets), off {@code
     * ReportQueryService#run} grouped by {@code LOCATION}/{@code CHANNEL}/{@code FULFILMENT_TYPE}/
     * {@code LEGAL_ENTITY} and folded back across the date and entity axes exactly as {@code
     * order-summary-grid.ts}'s own doc explains. An aggregate over money and counts, no PII column
     * either.
     */
    static final String ORDER_REPORT_SUMMARY = "ORDER_REPORT_SUMMARY";

    /**
     * ADR 0156: the payroll export -- one row per closed staff shift, hours and never pay, read through
     * {@code workforce.api.StaffAttendanceExportPort} so the identity group is resolved in {@code
     * workforce} and never here. {@code employeeNumber} and {@code staffName} are the PII group, gated
     * by {@code staff.shift.export} and not by {@code customer.pii.export}. The ceiling is 20,000 rows
     * with or without the group, and a range over it is <em>refused</em> with the count and never
     * truncated: a payroll file whose total quietly stops short is worse than no file.
     */
    static final String STAFF_ATTENDANCE = "STAFF_ATTENDANCE";

    /** ADR 0156, Open inputs: about nine months of a 100-person tenant in one file. A tenant-settable ceiling is not built. */
    static final int STAFF_ATTENDANCE_ROW_CEILING = 20_000;

    private static final Map<String, ReportExportDefinition> DEFINITIONS = Map.of(
            CUSTOMER_DIRECTORY,
            new ReportExportDefinition(
                    CUSTOMER_DIRECTORY,
                    List.of(
                            new ReportExportColumn("accountId", false),
                            new ReportExportColumn("status", false),
                            new ReportExportColumn("displayName", false),
                            new ReportExportColumn("phone", true)),
                    Capability.CUSTOMER_PII_EXPORT,
                    ReportExportDefinition.DEFAULT_ROW_QUOTA,
                    CustomerDirectoryExportPort.PII_ROW_LIMIT,
                    OverQuota.TRUNCATE),
            ORDER_CRM_LOG,
            new ReportExportDefinition(
                    ORDER_CRM_LOG,
                    List.of(
                            new ReportExportColumn("orderId", false),
                            new ReportExportColumn("occurredAt", false),
                            new ReportExportColumn("locationId", false),
                            new ReportExportColumn("customerType", false),
                            new ReportExportColumn("customerName", true),
                            new ReportExportColumn("customerPhone", true),
                            new ReportExportColumn("operatorPrincipalId", false),
                            new ReportExportColumn("courierDisplayReference", false)),
                    Capability.CUSTOMER_PII_EXPORT,
                    ReportExportDefinition.DEFAULT_ROW_QUOTA,
                    OrderCrmLogExportPort.PII_ROW_LIMIT,
                    OverQuota.TRUNCATE),
            ORDER_REPORT_LOG,
            new ReportExportDefinition(
                    ORDER_REPORT_LOG,
                    List.of(
                            new ReportExportColumn("orderId", false),
                            new ReportExportColumn("businessDate", false),
                            new ReportExportColumn("locationId", false),
                            new ReportExportColumn("channelCode", false),
                            new ReportExportColumn("fulfilmentType", false),
                            new ReportExportColumn("terminalStatus", false),
                            new ReportExportColumn("isPreorder", false),
                            new ReportExportColumn("grossRevenueSom", false),
                            new ReportExportColumn("discountSom", false),
                            new ReportExportColumn("deliveryFeeSom", false),
                            new ReportExportColumn("netRevenueSom", false),
                            new ReportExportColumn("itemCount", false)),
                    Capability.CUSTOMER_PII_EXPORT,
                    ReportExportDefinition.DEFAULT_ROW_QUOTA,
                    CustomerDirectoryExportPort.PII_ROW_LIMIT,
                    OverQuota.TRUNCATE),
            ORDER_REPORT_SUMMARY,
            new ReportExportDefinition(
                    ORDER_REPORT_SUMMARY,
                    List.of(
                            new ReportExportColumn("locationId", false),
                            new ReportExportColumn("channelCode", false),
                            new ReportExportColumn("fulfilmentType", false),
                            new ReportExportColumn("orderCount", false),
                            new ReportExportColumn("grossSom", false),
                            new ReportExportColumn("deliveryFeeSom", false),
                            new ReportExportColumn("netSom", false)),
                    Capability.CUSTOMER_PII_EXPORT,
                    ReportExportDefinition.DEFAULT_ROW_QUOTA,
                    CustomerDirectoryExportPort.PII_ROW_LIMIT,
                    OverQuota.TRUNCATE),
            STAFF_ATTENDANCE,
            new ReportExportDefinition(
                    STAFF_ATTENDANCE,
                    List.of(
                            new ReportExportColumn("staffDisplayReference", false),
                            new ReportExportColumn("businessDate", false),
                            new ReportExportColumn("locationId", false),
                            new ReportExportColumn("plannedSeconds", false),
                            new ReportExportColumn("workedSeconds", false),
                            new ReportExportColumn("breakSeconds", false),
                            new ReportExportColumn("paidSeconds", false),
                            new ReportExportColumn("varianceSeconds", false),
                            new ReportExportColumn("approvalStatus", false),
                            new ReportExportColumn("startedAt", false),
                            new ReportExportColumn("endedAt", false),
                            new ReportExportColumn("timezone", false),
                            new ReportExportColumn("employeeNumber", true),
                            new ReportExportColumn("staffName", true)),
                    Capability.STAFF_SHIFT_EXPORT,
                    STAFF_ATTENDANCE_ROW_CEILING,
                    STAFF_ATTENDANCE_ROW_CEILING,
                    OverQuota.REFUSE));

    private ReportExportRegistry() {}

    static Optional<ReportExportDefinition> find(String reportKey) {
        return Optional.ofNullable(DEFINITIONS.get(reportKey));
    }

    /**
     * Every distinct capability that gates a report's PII group: two today, {@code customer.pii.export}
     * and {@code staff.shift.export}. The controller asks the caller about each once (one {@code
     * authorization.has} each) and the service asks, per row, about <em>that row's report</em>.
     */
    static Set<Capability> piiCapabilities() {
        return DEFINITIONS.values().stream()
                .map(ReportExportDefinition::piiCapability)
                .collect(Collectors.toUnmodifiableSet());
    }
}
