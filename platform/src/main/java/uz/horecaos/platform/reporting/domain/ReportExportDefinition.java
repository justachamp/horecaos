package uz.horecaos.platform.reporting.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import uz.horecaos.platform.iam.api.Capability;

/**
 * What one exportable report offers (ADR 0043, wave P28): its stable key, the ordered columns a caller
 * may ask {@code POST .../reporting/exports} for, and the three things that differ between reports
 * (ADR 0156, ADR 0131).
 *
 * <p>A registry entry, not a table row -- {@link
 * uz.horecaos.platform.reporting.application.ReportExportRegistry}'s own doc explains why an unknown
 * report key fails the request rather than silently denying it, the same reasoning ADR 0025 gives for
 * {@code Capability} itself.
 *
 * @param piiCapability the capability that gates this report's PII column group. The customer reports
 *     use {@code customer.pii.export}; the payroll export uses {@code staff.shift.export}. A caller
 *     without it still gets the file, minus the group -- and the history redacts by <em>this</em>
 *     report's capability, so a finance clerk holding {@code staff.shift.export} alone sees her own
 *     payroll export whole and a holder of {@code customer.pii.export} alone does not see its identity
 *     group or download URL
 * @param rowQuota the row ceiling for an export whose effective columns carry no PII
 * @param piiRowQuota the ceiling when they do
 * @param overQuota what happens to a result over the ceiling: cut short and flagged ({@link
 *     OverQuota#TRUNCATE}, what every report did before ADR 0156) or refused with the count ({@link
 *     OverQuota#REFUSE}, what a payroll file must do, because a total that quietly stops short is
 *     worse than no file)
 */
public record ReportExportDefinition(
        String reportKey,
        List<ReportExportColumn> columns,
        Capability piiCapability,
        int rowQuota,
        int piiRowQuota,
        OverQuota overQuota) {

    /** The row ceiling every report had before it was a per-definition setting. */
    public static final int DEFAULT_ROW_QUOTA = 5_000;

    /** The ceiling every PII-carrying report had before it was a per-definition setting. */
    public static final int DEFAULT_PII_ROW_QUOTA = 2_000;

    /** What an export does with a result over its ceiling. */
    public enum OverQuota {
        TRUNCATE,
        REFUSE
    }

    public ReportExportDefinition {
        if (reportKey == null || reportKey.isBlank()) {
            throw new IllegalArgumentException("A report export definition needs a key");
        }
        if (rowQuota < 1 || piiRowQuota < 1) {
            throw new IllegalArgumentException("A report export needs a positive row ceiling");
        }
        columns = List.copyOf(columns);
    }

    /** A report gated by {@code customer.pii.export} that truncates at the platform's default ceilings. */
    public ReportExportDefinition(String reportKey, List<ReportExportColumn> columns) {
        this(
                reportKey,
                columns,
                Capability.CUSTOMER_PII_EXPORT,
                DEFAULT_ROW_QUOTA,
                DEFAULT_PII_ROW_QUOTA,
                OverQuota.TRUNCATE);
    }

    public boolean isKnownColumn(String columnKey) {
        return columns.stream().anyMatch(column -> column.key().equals(columnKey));
    }

    public boolean isPiiColumn(String columnKey) {
        return columns.stream().anyMatch(column -> column.key().equals(columnKey) && column.pii());
    }

    /** Every column's own key, in the order this definition declares them. */
    public List<String> allColumnKeys() {
        return columns.stream().map(ReportExportColumn::key).toList();
    }

    /** The ceiling for an export whose effective columns do or do not carry the PII group. */
    public int rowQuotaFor(boolean includesPii) {
        return includesPii ? piiRowQuota : rowQuota;
    }

    /**
     * {@code requestedColumns}, minus the PII group when {@code includePii} is false -- the
     * omission {@link uz.horecaos.platform.iam.api.Capability#REPORT_EXPORT}'s own doc names,
     * computed here rather than duplicated at each call site.
     */
    public List<String> effectiveColumns(List<String> requestedColumns, boolean includePii) {
        Set<String> known = new LinkedHashSet<>();
        for (String key : requestedColumns) {
            if (!isKnownColumn(key)) {
                continue;
            }
            if (!includePii && isPiiColumn(key)) {
                continue;
            }
            known.add(key);
        }
        return List.copyOf(known);
    }
}
