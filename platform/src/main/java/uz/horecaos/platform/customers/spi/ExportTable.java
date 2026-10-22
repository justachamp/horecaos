package uz.horecaos.platform.customers.spi;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The rows one table contributes to a data-subject export.
 *
 * <p>A row is a flat map of column name to a JSON-shaped value: text, a number, a boolean, a nested map or list, or
 * null. {@link ExportRow} builds one and renders instants, dates, ids and enums as text so the bundle reads the same
 * wherever it is opened. Money is an amount in minor units next to its currency, never a decimal (the platform rule).
 *
 * @param truncated true when the table held more than {@link #MAX_ROWS} rows for this subject and only the newest are
 *                  here; the manifest says so, and a subject who needs the rest asks again
 */
public record ExportTable(String table, List<Map<String, @Nullable Object>> rows, boolean truncated) {

    /** The most rows one table contributes: the bundle is a document a person opens, not a data warehouse load. */
    public static final int MAX_ROWS = 5000;

    public ExportTable {
        rows = List.copyOf(rows);
        if (rows.size() > MAX_ROWS) {
            throw new IllegalArgumentException(table + " contributes " + rows.size() + " rows; the cap is " + MAX_ROWS);
        }
    }

    /**
     * A table's rows as the store returned them, newest first, asked for with a limit of {@code MAX_ROWS + 1} so that
     * one extra row says "there was more" without a second count query.
     */
    public static ExportTable ofNewestFirst(String table, List<Map<String, @Nullable Object>> fetched) {
        boolean more = fetched.size() > MAX_ROWS;
        return new ExportTable(table, more ? fetched.subList(0, MAX_ROWS) : fetched, more);
    }

    public static ExportTable of(String table, List<Map<String, @Nullable Object>> rows) {
        return new ExportTable(table, rows, false);
    }
}
