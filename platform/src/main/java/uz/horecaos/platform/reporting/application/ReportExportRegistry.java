package uz.horecaos.platform.reporting.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import uz.horecaos.platform.reporting.domain.ReportExportColumn;
import uz.horecaos.platform.reporting.domain.ReportExportDefinition;

/**
 * The code-owned catalogue of exportable reports (ADR 0043, wave P28's own export centre).
 *
 * <p>A report key is declared here, not read from a table, for the same reason {@code
 * Capability}'s own doc gives for capabilities: the wire-level vocabulary is fixed at compile
 * time, so an unknown key fails the request loudly instead of a caller discovering at export time
 * that a typo silently matched nothing.
 *
 * <p><strong>Only {@link #CUSTOMER_DIRECTORY} is wired end to end today.</strong> The registry
 * exists so the next report an export needs a case for is one new entry plus one new branch in
 * {@link ReportExportService}, not a second job queue — see that class's own doc for why a
 * pluggable-source abstraction was deliberately not built for a catalogue of one (AGENTS.md: "Do
 * not introduce infrastructure or patterns only for hypothetical scale").
 */
final class ReportExportRegistry {

    /**
     * The CRM grid ({@code CustomerListQueryService}'s own list), the report P28's own brief
     * names as the shape to model every other export on. {@code phone} is the one PII column;
     * everything else is already unencrypted on the row.
     */
    static final String CUSTOMER_DIRECTORY = "CUSTOMER_DIRECTORY";

    private static final Map<String, ReportExportDefinition> DEFINITIONS = Map.of(
            CUSTOMER_DIRECTORY,
            new ReportExportDefinition(
                    CUSTOMER_DIRECTORY,
                    List.of(
                            new ReportExportColumn("accountId", false),
                            new ReportExportColumn("status", false),
                            new ReportExportColumn("displayName", false),
                            new ReportExportColumn("phone", true))));

    private ReportExportRegistry() {}

    static Optional<ReportExportDefinition> find(String reportKey) {
        return Optional.ofNullable(DEFINITIONS.get(reportKey));
    }
}
