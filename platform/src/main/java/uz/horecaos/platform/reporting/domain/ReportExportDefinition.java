package uz.horecaos.platform.reporting.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What one exportable report offers (ADR 0043, wave P28): its stable key and the ordered columns
 * a caller may ask {@code POST .../reporting/exports} for.
 *
 * <p>A registry entry, not a table row — {@link
 * uz.horecaos.platform.reporting.application.ReportExportRegistry}'s own doc explains why an
 * unknown report key fails the request rather than silently denying it, the same reasoning ADR
 * 0025 gives for {@code Capability} itself.
 */
public record ReportExportDefinition(String reportKey, List<ReportExportColumn> columns) {

    public ReportExportDefinition {
        if (reportKey == null || reportKey.isBlank()) {
            throw new IllegalArgumentException("A report export definition needs a key");
        }
        columns = List.copyOf(columns);
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

    /**
     * {@code requestedColumns}, minus the PII group when {@code includePii} is false — the
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
