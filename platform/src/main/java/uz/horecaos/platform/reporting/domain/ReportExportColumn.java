package uz.horecaos.platform.reporting.domain;

/**
 * One column an exportable report may offer (ADR 0043, wave P28).
 *
 * @param key  the stable, wire-level identifier a request names to ask for this column
 * @param pii  whether this column belongs to the PII group {@code customer.pii.export} gates —
 *             {@link uz.horecaos.platform.iam.api.Capability#REPORT_EXPORT} alone omits it
 */
public record ReportExportColumn(String key, boolean pii) {

    public ReportExportColumn {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("A report export column needs a key");
        }
    }
}
