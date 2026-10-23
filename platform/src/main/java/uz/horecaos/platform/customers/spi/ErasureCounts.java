package uz.horecaos.platform.customers.spi;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * How many rows one participant overwrote or removed, per table (ADR 0029: "proof: produce evidence of what was
 * erased, when, and by whom").
 *
 * <p>Counts and table names, never an identifier of a row and never a value: the proof is stored on the erasure
 * request and read by staff, so it must be as free of personal data as the audit trail is. A table the participant
 * declared and did not report is read as {@code 0} by the caller, so a participant reports what it changed and
 * need not list what it found nothing in.
 */
public record ErasureCounts(Map<String, Integer> rowsByTable) {

    public ErasureCounts {
        rowsByTable = Map.copyOf(rowsByTable);
    }

    /** A participant that found nothing to change. */
    public static ErasureCounts none() {
        return new ErasureCounts(Map.of());
    }

    /** One table's count. */
    public static ErasureCounts of(String table, int rows) {
        return new ErasureCounts(Map.of(table, rows));
    }

    /** A builder for a participant that touches several tables. */
    public static Builder builder() {
        return new Builder();
    }

    /** The total number of rows across tables, for a log line that names no table. */
    public int total() {
        return rowsByTable.values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Accumulates counts as a participant works through its tables. */
    public static final class Builder {

        private final Map<String, Integer> rows = new LinkedHashMap<>();

        private Builder() {}

        /** Adds {@code count} to {@code table}; a table touched twice sums. */
        public Builder add(String table, int count) {
            rows.merge(table, count, Integer::sum);
            return this;
        }

        public ErasureCounts build() {
            return new ErasureCounts(rows);
        }
    }
}
