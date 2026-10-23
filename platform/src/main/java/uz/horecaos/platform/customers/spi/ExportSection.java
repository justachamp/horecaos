package uz.horecaos.platform.customers.spi;

import java.util.List;

/**
 * One participant's part of a data-subject export: its tables, and what it left out on purpose.
 *
 * @param withheld a short, stable code per thing the participant did not include and why ({@code
 *                 blacklist_reason_internal_note}), shown in the manifest. Codes, never values.
 */
public record ExportSection(List<ExportTable> tables, List<String> withheld) {

    public ExportSection {
        tables = List.copyOf(tables);
        withheld = List.copyOf(withheld);
    }

    public static ExportSection of(ExportTable... tables) {
        return new ExportSection(List.of(tables), List.of());
    }

    public static ExportSection of(List<ExportTable> tables) {
        return new ExportSection(tables, List.of());
    }

    public ExportSection withholding(String... codes) {
        return new ExportSection(tables, List.of(codes));
    }

    public static ExportSection empty() {
        return new ExportSection(List.of(), List.of());
    }
}
