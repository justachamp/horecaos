package uz.horecaos.platform.migration.infrastructure.legacy;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.migration.api.ExtractionSpec;
import uz.horecaos.platform.migration.api.LegacyRecord;
import uz.horecaos.platform.migration.application.importing.LegacySourceReader;
import uz.horecaos.platform.migration.application.importing.SourcePage;

/**
 * Keyset paging over the legacy PostgreSQL (ADR 0024, step 3).
 *
 * <p>Every page is {@code WHERE key > :afterKey ORDER BY key LIMIT :limit}, and
 * never an offset. The legacy database is serving customers while this reads it,
 * so an offset silently skips rows whenever the table shifts underneath the
 * reader — and the row skipped would be a legacy record nobody then accounts for,
 * which is the one claim ADR 0024 exists to be able to make.
 *
 * <p>The cursor is stored as text and compared as the column's own type.
 * {@code entity_mappings.legacy_id} and {@code source_cursors.last_stable_key}
 * are both text because the legacy estate keys on bigints, uuids and strings, and
 * one cursor type is what stops a crosswalk inventing identities. Comparing in
 * text would still be a total order and would still page correctly, but it would
 * be the lexicographic one — '1000' before '9' — so the cursor an operator reads
 * mid-run would not correspond to any prefix of the table. The bound is therefore
 * cast back to the column's declared type, read from the source's own catalogue
 * rather than guessed at, and PostgreSQL orders it natively.
 *
 * <p>Identifiers are interpolated rather than bound, because a table name and an
 * ORDER BY are syntax and cannot be parameters. Every identifier has been
 * pattern-checked by {@link ExtractionSpec}'s constructor, which is the only
 * construction path; the optional filter predicate, which no identifier pattern
 * fits, is checked by {@link #checkedFilter} here; values are always bound.
 */
@Repository
@ConditionalOnProperty(prefix = "horecaos.migration.legacy", name = "enabled", havingValue = "true")
public class JdbcLegacySourceReader implements LegacySourceReader {

    private static final int MAX_FILTER_LENGTH = 500;

    private static final Pattern SAFE_PREDICATE = Pattern.compile("^[A-Za-z0-9_.,'%()\\[\\]\\s<>=!+*/|-]+$");

    /** Everything the allowlist above cannot exclude on its own. */
    private static final List<String> FORBIDDEN_IN_FILTER = List.of(";", "--", "/*", "*/", "||");

    private final JdbcClient jdbc;

    public JdbcLegacySourceReader(@Qualifier(LegacySourceConfiguration.LEGACY_JDBC_CLIENT) JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public SourcePage readPage(ExtractionSpec spec, @Nullable String afterKey, int limit) {
        // Checked before anything is read, so a bad predicate fails on the spec
        // rather than after a catalogue round trip against the legacy database.
        String filter = checkedFilter(spec);
        String sql = """
                SELECT %s
                FROM %s
                WHERE (CAST(:afterKey AS text) IS NULL OR %s > CAST(:afterKey AS %s))
                %s
                ORDER BY %s
                LIMIT :limit
                """.formatted(
                        String.join(", ", spec.columns()),
                        spec.table(),
                        spec.stableKeyColumn(),
                        keyTypeOf(spec),
                        filter,
                        spec.stableKeyColumn());

        Map<String, Object> params = new HashMap<>();
        params.put("afterKey", afterKey);
        params.put("limit", limit);

        List<LegacyRecord> records = jdbc.sql(sql)
                .params(params)
                .query((row, number) -> toRecord(row, spec))
                .list();

        return page(records, afterKey, limit);
    }

    @Override
    public SourcePage readChanges(
            ExtractionSpec spec, @Nullable String watermark, @Nullable String afterKey, int limit) {
        if (!spec.hasWatermark()) {
            throw new IllegalArgumentException(
                    "%s declares no watermark column, so it has no incremental feed".formatted(spec.entityType()));
        }

        // Inclusive on the watermark and ordered by (watermark, key). The legacy
        // change column is a naive timestamp with no ordering guarantee within a
        // value, so an exclusive bound would drop every row sharing the last one's
        // timestamp. The boundary rows are re-read on every catch-up instead, which
        // the crosswalk's upsert makes free.
        //
        // The watermark is compared as the column's own type, not as text: naive
        // timestamps do not order lexicographically once the source writes them in
        // more than one format, and the legacy writer demonstrably does.
        String filter = checkedFilter(spec);
        String sql = """
                SELECT %s
                FROM %s
                WHERE (CAST(:watermark AS text) IS NULL
                       OR (%s, %s) >= (CAST(:watermark AS %s), CAST(:afterKey AS %s)))
                %s
                ORDER BY %s, %s
                LIMIT :limit
                """.formatted(
                        String.join(", ", spec.columns()),
                        spec.table(),
                        spec.watermarkColumn(),
                        spec.stableKeyColumn(),
                        watermarkTypeOf(spec),
                        keyTypeOf(spec),
                        filter,
                        spec.watermarkColumn(),
                        spec.stableKeyColumn());

        Map<String, Object> params = new HashMap<>();
        params.put("watermark", watermark);
        // The row comparison needs both halves or neither: a watermark with no key
        // starts the tuple at the lowest key of that instant, which is what an
        // empty string cannot express and what null would make the whole predicate
        // unknown for.
        params.put("afterKey", afterKey == null ? "" : afterKey);
        params.put("limit", limit);

        List<LegacyRecord> records = jdbc.sql(sql)
                .params(params)
                .query((row, number) -> toRecord(row, spec))
                .list();

        return page(records, afterKey, limit);
    }

    /**
     * The filter clause, or nothing, having checked what is about to be
     * concatenated.
     *
     * <p>{@link ExtractionSpec} pattern-checks the table, the key, the watermark
     * and every selected column in its constructor and does not check this one,
     * because a predicate is not an identifier and no identifier pattern fits it.
     * That left the single field on the spec that reaches SQL unexamined — in a
     * class that connects to the production legacy database with the credentials
     * that read customer rows.
     *
     * <p>So it is checked here, where it becomes SQL. A predicate cannot be a bind
     * parameter, so the check is a shape rather than an escape: identifiers,
     * numbers, single-quoted literals, comparison and boolean operators, and
     * nothing that could end the statement or start a second one. A migration
     * author writing {@code deleted_at IS NULL} or {@code status IN ('DONE')}
     * passes; a statement terminator, a comment introducer, a dollar-quote or a
     * stray quote does not.
     *
     * <p>The right long-term home is the spec's constructor, next to its siblings,
     * so a bad filter fails where a developer states it rather than on the first
     * page of a run. This is the second-best place and the one reachable without
     * changing the record.
     */
    private static String checkedFilter(ExtractionSpec spec) {
        String filter = spec.filter();
        if (filter == null) {
            return "";
        }
        return "AND (" + requireSafePredicate(filter) + ")";
    }

    static String requireSafePredicate(String filter) {
        if (filter.length() > MAX_FILTER_LENGTH) {
            throw new IllegalArgumentException(
                    "A legacy extraction filter is a short predicate, not a query: " + filter.length() + " characters");
        }
        if (!SAFE_PREDICATE.matcher(filter).matches()) {
            throw new IllegalArgumentException(
                    "A legacy extraction filter may contain only identifiers, numbers, quoted "
                            + "literals and comparison operators: " + filter);
        }
        for (String forbidden : FORBIDDEN_IN_FILTER) {
            if (filter.contains(forbidden)) {
                throw new IllegalArgumentException(
                        "A legacy extraction filter may not contain '" + forbidden + "': " + filter);
            }
        }
        // An odd count means a literal is left open, which would swallow the ORDER
        // BY that follows it into the string rather than failing as syntax.
        if (filter.chars().filter(character -> character == '\'').count() % 2 != 0) {
            throw new IllegalArgumentException(
                    "A legacy extraction filter has an unterminated quoted literal: " + filter);
        }
        return filter;
    }

    /**
     * The stored bound is text and the column is not, so the cast has to name a
     * type. Resolved from the source's own catalogue rather than guessed, because
     * casting a bigint key to text would order '1000' before '9' and page the
     * table in an order no operator reading the cursor would predict.
     */
    private String keyTypeOf(ExtractionSpec spec) {
        return columnType(spec.table(), spec.stableKeyColumn());
    }

    private String watermarkTypeOf(ExtractionSpec spec) {
        // Only ever called once readChanges has already confirmed hasWatermark(),
        // but that characteristic-predicate link is invisible to NullAway across
        // the two calls.
        return columnType(spec.table(), Objects.requireNonNull(spec.watermarkColumn(), "hasWatermark() said so"));
    }

    private String columnType(String table, String column) {
        String schema = table.contains(".") ? table.substring(0, table.indexOf('.')) : "public";
        String name = table.contains(".") ? table.substring(table.indexOf('.') + 1) : table;
        return jdbc.sql("""
                SELECT format_type(a.atttypid, a.atttypmod)
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = :schema AND c.relname = :table AND a.attname = :column
                  AND a.attnum > 0 AND NOT a.attisdropped
                """)
                .param("schema", schema)
                .param("table", name)
                .param("column", column)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        ("The legacy source has no column %s.%s. The extraction spec describes a "
                                        + "schema this database does not have, which is a mapping error "
                                        + "rather than an empty page.")
                                .formatted(table, column)));
    }

    private SourcePage page(List<LegacyRecord> records, @Nullable String afterKey, int limit) {
        boolean exhausted = records.size() < limit;
        // The previous bound unchanged on an empty page, never null: null means
        // "start from the beginning", and returning it would restart the entity
        // type from scratch every time it reached the end.
        String nextKey =
                records.isEmpty() ? afterKey : records.get(records.size() - 1).stableKey();
        return new SourcePage(records, nextKey, exhausted);
    }

    private LegacyRecord toRecord(ResultSet row, ExtractionSpec spec) throws SQLException {
        // A HashMap and not Map.of: SQL NULL is a legitimate value for most of
        // these columns, and an absent key would make "the column does not exist"
        // and "the column is empty" the same answer.
        Map<String, Object> values = new HashMap<>();
        for (String column : spec.columns()) {
            values.put(column, row.getObject(column));
        }

        Object key = values.get(spec.stableKeyColumn());
        if (key == null) {
            // Unreachable through a well-chosen key and worth stating anyway: a null
            // key cannot be paged past and cannot be crosswalked, so the row would
            // be re-read forever or silently lost.
            throw new IllegalStateException(
                    ("A %s row has a null %s. The stable key must be unique and non-null in the "
                                    + "source; this one cannot be paged from or mapped.")
                            .formatted(spec.entityType(), spec.stableKeyColumn()));
        }

        Object version = spec.hasWatermark() ? values.get(spec.watermarkColumn()) : null;
        return new LegacyRecord(key.toString(), version == null ? null : version.toString(), values);
    }
}
