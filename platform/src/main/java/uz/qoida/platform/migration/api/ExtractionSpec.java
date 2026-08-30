package uz.qoida.platform.migration.api;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * What to read from the legacy database, and how to page it (ADR 0024).
 *
 * <p>Every field here becomes part of a SQL statement, and none of them can be a
 * bind parameter: a table name, a column list and an ORDER BY are syntax, not
 * values. So they are validated as identifiers here rather than at the reader,
 * once, at the point a caller states them — which is also the only place the
 * failure is legible. A spec that reached {@code JdbcLegacySourceReader} with an
 * unchecked string would be concatenation into SQL, and the fact that the caller
 * is a developer rather than a request does not make that acceptable in a class
 * that connects to a production database.
 *
 * @param entityType        the crosswalk's name for this family, upper case, as
 *                          {@code migration.entity_mappings.entity_type} spells it
 * @param table             the legacy table, optionally schema-qualified
 * @param stableKeyColumn   the column pages are ordered by. Must be unique and
 *                          non-null in the source, because it is both the page
 *                          boundary and the crosswalk key: a repeated value makes
 *                          the exclusive bound skip rows, and a null one makes
 *                          them unreachable
 * @param watermarkColumn   the change column a catch-up run resumes from, or null
 *                          for a family with no incremental feed. On this legacy
 *                          estate it is {@code updated}, which the base model sets
 *                          on every write and which is naive local time
 * @param columns           the columns to select, named rather than {@code *}. A
 *                          star select makes a column added to the legacy schema
 *                          silently appear in the transformation's input, and
 *                          makes an accidental read of a column ADR 0029 does not
 *                          allow out of the source impossible to review
 * @param filter            an optional SQL predicate, for the cases where the
 *                          scope is narrower than the table. It is syntax like
 *                          everything else here and is written by a migration
 *                          author, never assembled from input
 */
public record ExtractionSpec(
        String entityType,
        String table,
        String stableKeyColumn,
        String watermarkColumn,
        List<String> columns,
        String filter) {

    private static final Pattern ENTITY_TYPE = Pattern.compile("^[A-Z][A-Z0-9_]{0,63}$");
    private static final Pattern IDENTIFIER = Pattern.compile("^[a-z_][a-z0-9_]{0,62}$");
    private static final Pattern QUALIFIED = Pattern.compile("^[a-z_][a-z0-9_]{0,62}(\\.[a-z_][a-z0-9_]{0,62})?$");

    public ExtractionSpec {
        if (entityType == null || !ENTITY_TYPE.matcher(entityType).matches()) {
            throw new IllegalArgumentException(
                    "An entity type is an upper-case code such as ORDER or CUSTOMER_ADDRESS");
        }
        if (table == null || !QUALIFIED.matcher(table).matches()) {
            throw new IllegalArgumentException(
                    "A legacy table is a lower-case identifier, optionally schema-qualified: " + table);
        }
        requireIdentifier(stableKeyColumn, "stable key column");
        if (watermarkColumn != null) {
            requireIdentifier(watermarkColumn, "watermark column");
        }
        Objects.requireNonNull(columns, "A named column list is required");
        columns = List.copyOf(columns);
        if (columns.isEmpty()) {
            throw new IllegalArgumentException(
                    "Extraction selects a named column list. A star select would put whatever the "
                            + "legacy schema gains next in front of the transformation unreviewed.");
        }
        columns.forEach(column -> requireIdentifier(column, "selected column"));
        if (!columns.contains(stableKeyColumn)) {
            throw new IllegalArgumentException(
                    "The stable key must be selected: it is the crosswalk key and the page bound");
        }
        if (watermarkColumn != null && !columns.contains(watermarkColumn)) {
            throw new IllegalArgumentException("The watermark column must be selected");
        }
        if (filter != null && filter.isBlank()) {
            throw new IllegalArgumentException("A blank filter is not a filter; use null");
        }
    }

    public boolean hasWatermark() {
        return watermarkColumn != null;
    }

    private static void requireIdentifier(String value, String what) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "A %s is a lower-case SQL identifier: %s".formatted(what, value));
        }
    }
}
