package uz.horecaos.platform.catalog.infrastructure.persistence;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.catalog.application.MxikReferenceImporter.ReferenceRow;

/**
 * Persistence for the ИКПУ/MXIK import (ADR 0173): the runs, what they found, the normalised rows a run would
 * write, and the writes that apply one to {@code catalog.mxik_reference} and its state row.
 *
 * <p>Platform-scoped on purpose: the national list is not a tenant's, so no statement here names a tenant. The
 * application role cannot delete from the list (V0540), and nothing here tries: a withdrawal is a date.
 */
@Repository
public class JdbcMxikImportStore {

    /** A few thousand rows per statement keeps the apply's statements bounded and its progress visible. */
    public static final int APPLY_BATCH = 3000;

    private final JdbcClient jdbc;

    public JdbcMxikImportStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ----------------------------------------------------------------------------- runs

    /** A run, as every read returns it. */
    public record RunRow(
            UUID id,
            String status,
            String mode,
            String mappingProfile,
            String sourceFileName,
            String sourceSha256,
            @Nullable String sourceObjectKey,
            @Nullable LocalDate sourcePublishedOn,
            int rowsTotal,
            int rowsNew,
            int rowsChanged,
            int rowsUnchanged,
            int rowsWithdrawn,
            int rowsReinstated,
            int rowsError,
            int labelsBorrowed,
            int withdrawalThresholdPercent,
            @Nullable Integer acknowledgedWithdrawals,
            String codeLengthHistogram,
            String createdBy,
            java.time.Instant createdAt,
            @Nullable String appliedBy,
            java.time.@Nullable Instant appliedAt,
            @Nullable Integer datasetVersion,
            @Nullable String failureReason,
            int version) {}

    private static final String RUN_COLUMNS = """
            id, status, mode, mapping_profile, source_file_name, source_sha256, source_object_key,
            source_published_on, rows_total, rows_new, rows_changed, rows_unchanged, rows_withdrawn,
            rows_reinstated, rows_error, labels_borrowed, withdrawal_threshold_percent,
            acknowledged_withdrawals, code_length_histogram::text AS histogram, created_by, created_at,
            applied_by, applied_at, dataset_version, failure_reason, version""";

    public void insertRun(
            UUID id,
            String status,
            String mode,
            String mappingProfile,
            String fileName,
            String sha256,
            @Nullable String objectKey,
            @Nullable LocalDate sourcePublishedOn,
            int thresholdPercent,
            String histogramJson,
            String createdBy,
            @Nullable String failureReason) {
        jdbc.sql("""
                INSERT INTO catalog.mxik_import_runs (
                    id, status, mode, mapping_profile, source_file_name, source_sha256, source_object_key,
                    source_published_on, withdrawal_threshold_percent, code_length_histogram, created_by,
                    failure_reason)
                VALUES (:id, :status, :mode, :profile, :fileName, :sha, :objectKey, :publishedOn, :threshold,
                    CAST(:histogram AS jsonb), :createdBy, :failureReason)
                """)
                .param("id", id)
                .param("status", status)
                .param("mode", mode)
                .param("profile", mappingProfile)
                .param("fileName", fileName)
                .param("sha", sha256)
                .param("objectKey", objectKey)
                .param("publishedOn", sourcePublishedOn)
                .param("threshold", thresholdPercent)
                .param("histogram", histogramJson)
                .param("createdBy", createdBy)
                .param("failureReason", failureReason)
                .update();
    }

    /** Writes the counts a dry run found and the status it ended in. */
    public void recordValidation(
            UUID id,
            String status,
            int total,
            int created,
            int changed,
            int unchanged,
            int withdrawn,
            int reinstated,
            int errors,
            int labelsBorrowed) {
        jdbc.sql("""
                UPDATE catalog.mxik_import_runs
                   SET status = :status, rows_total = :total, rows_new = :created, rows_changed = :changed,
                       rows_unchanged = :unchanged, rows_withdrawn = :withdrawn, rows_reinstated = :reinstated,
                       rows_error = :errors, labels_borrowed = :borrowed, version = version + 1
                 WHERE id = :id
                """)
                .param("id", id)
                .param("status", status)
                .param("total", total)
                .param("created", created)
                .param("changed", changed)
                .param("unchanged", unchanged)
                .param("withdrawn", withdrawn)
                .param("reinstated", reinstated)
                .param("errors", errors)
                .param("borrowed", labelsBorrowed)
                .update();
    }

    public Optional<RunRow> findRun(UUID id) {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM catalog.mxik_import_runs WHERE id = :id")
                .param("id", id)
                .query(JdbcMxikImportStore::mapRun)
                .optional();
    }

    public Optional<RunRow> findRunForUpdate(UUID id) {
        return jdbc.sql("SELECT " + RUN_COLUMNS + " FROM catalog.mxik_import_runs WHERE id = :id FOR UPDATE")
                .param("id", id)
                .query(JdbcMxikImportStore::mapRun)
                .optional();
    }

    public List<RunRow> recentRuns(int limit) {
        return jdbc.sql("SELECT " + RUN_COLUMNS
                        + " FROM catalog.mxik_import_runs ORDER BY created_at DESC LIMIT :limit")
                .param("limit", limit)
                .query(JdbcMxikImportStore::mapRun)
                .list();
    }

    /** The run moves only from one of these statuses; a run that moved already returns false. */
    public boolean transition(UUID id, Collection<String> from, String to, int expectedVersion) {
        return jdbc.sql("""
                        UPDATE catalog.mxik_import_runs
                           SET status = :to, version = version + 1
                         WHERE id = :id AND version = :version AND status = ANY(:from)
                        """)
                        .param("id", id)
                        .param("to", to)
                        .param("version", expectedVersion)
                        .param("from", from.toArray(String[]::new))
                        .update()
                == 1;
    }

    public void markApplied(UUID id, String by, int datasetVersion, @Nullable Integer acknowledged, int[] counts) {
        jdbc.sql("""
                UPDATE catalog.mxik_import_runs
                   SET status = 'APPLIED', applied_by = :by, applied_at = now(), dataset_version = :datasetVersion,
                       acknowledged_withdrawals = :acknowledged, rows_new = :created, rows_changed = :changed,
                       rows_unchanged = :unchanged, rows_withdrawn = :withdrawn, rows_reinstated = :reinstated,
                       version = version + 1
                 WHERE id = :id
                """)
                .param("id", id)
                .param("by", by)
                .param("datasetVersion", datasetVersion)
                .param("acknowledged", acknowledged)
                .param("created", counts[0])
                .param("changed", counts[1])
                .param("unchanged", counts[2])
                .param("withdrawn", counts[3])
                .param("reinstated", counts[4])
                .update();
    }

    public void markFailed(UUID id, String reason) {
        jdbc.sql("""
                UPDATE catalog.mxik_import_runs
                   SET status = 'FAILED', failure_reason = :reason, version = version + 1
                 WHERE id = :id AND status IN ('VALIDATED', 'HELD_FOR_REVIEW', 'APPLYING')
                """).param("id", id).param("reason", reason).update();
    }

    // -------------------------------------------------------------------------- findings

    public record FindingRow(String kind, @Nullable String code, String detailCode) {}

    /** Appends findings in order, ordinals continuing from {@code startOrdinal}. */
    public void insertFindings(UUID runId, int startOrdinal, List<FindingRow> findings) {
        int ordinal = startOrdinal;
        for (FindingRow finding : findings) {
            jdbc.sql("""
                    INSERT INTO catalog.mxik_import_run_findings (run_id, ordinal, kind, code, detail_code)
                    VALUES (:run, :ordinal, :kind, :code, :detail)
                    """)
                    .param("run", runId)
                    .param("ordinal", ordinal++)
                    .param("kind", finding.kind())
                    .param("code", finding.code())
                    .param("detail", finding.detailCode())
                    .update();
        }
    }

    /** A stored finding with its position in the run, which is the cursor a client pages by. */
    public record StoredFinding(
            int ordinal, String kind, @Nullable String code, String detailCode) {}

    public List<StoredFinding> findings(UUID runId, int afterOrdinal, int limit) {
        return jdbc.sql("""
                SELECT ordinal, kind, code, detail_code FROM catalog.mxik_import_run_findings
                 WHERE run_id = :run AND ordinal > :after ORDER BY ordinal LIMIT :limit
                """)
                .param("run", runId)
                .param("after", afterOrdinal)
                .param("limit", limit)
                .query((row, number) -> new StoredFinding(
                        row.getInt("ordinal"),
                        row.getString("kind"),
                        row.getString("code"),
                        row.getString("detail_code")))
                .list();
    }

    // -------------------------------------------------------------------- staging rows

    /** The codes among these that are already rows of the national list, valid or withdrawn. */
    public Set<String> existingCodes(Collection<String> codes) {
        if (codes.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("SELECT code FROM catalog.mxik_reference WHERE code = ANY(:codes)")
                .param("codes", codes.toArray(String[]::new))
                .query(String.class)
                .list());
    }

    /** Stores the normalised rows a validated run would write, in statements of a few thousand. */
    public void insertRows(UUID runId, List<ReferenceRow> rows) {
        for (int from = 0; from < rows.size(); from += APPLY_BATCH) {
            List<ReferenceRow> batch = rows.subList(from, Math.min(rows.size(), from + APPLY_BATCH));
            jdbc.sql("""
                    INSERT INTO catalog.mxik_import_run_rows (
                        run_id, ordinal, code, parent_code, label_ru, label_uz, label_en,
                        default_package_codes, default_unit_codes, replaced_by_code, level, valid_from, depth)
                    SELECT :run, t.ordinal, t.code, NULLIF(t.parent, ''), t.ru, t.uz, NULLIF(t.en, ''),
                           CASE WHEN t.packages = '' THEN '{}'::text[] ELSE string_to_array(t.packages, ';') END,
                           CASE WHEN t.units = '' THEN '{}'::integer[]
                                ELSE string_to_array(t.units, ';')::integer[] END,
                           NULLIF(t.replaced, ''), NULLIF(t.level, '')::smallint, t.valid_from::date, t.depth
                      FROM unnest(
                               CAST(:ordinals AS integer[]), CAST(:codes AS text[]), CAST(:parents AS text[]),
                               CAST(:ru AS text[]), CAST(:uz AS text[]), CAST(:en AS text[]),
                               CAST(:packages AS text[]), CAST(:units AS text[]), CAST(:replaced AS text[]),
                               CAST(:levels AS text[]), CAST(:validFrom AS text[]), CAST(:depths AS integer[]))
                           AS t(ordinal, code, parent, ru, uz, en, packages, units, replaced, level, valid_from, depth)
                    """)
                    .param("run", runId)
                    .param("ordinals", batch.stream().map(ReferenceRow::ordinal).toArray(Integer[]::new))
                    .param("codes", batch.stream().map(ReferenceRow::code).toArray(String[]::new))
                    .param(
                            "parents",
                            batch.stream()
                                    .map(row -> nullToEmpty(row.parentCode()))
                                    .toArray(String[]::new))
                    .param("ru", batch.stream().map(ReferenceRow::labelRu).toArray(String[]::new))
                    .param("uz", batch.stream().map(ReferenceRow::labelUz).toArray(String[]::new))
                    .param(
                            "en",
                            batch.stream()
                                    .map(row -> nullToEmpty(row.labelEn()))
                                    .toArray(String[]::new))
                    .param(
                            "packages",
                            batch.stream()
                                    .map(row -> String.join(";", row.packageCodes()))
                                    .toArray(String[]::new))
                    .param(
                            "units",
                            batch.stream()
                                    .map(row -> row.unitCodes().stream()
                                            .map(String::valueOf)
                                            .collect(Collectors.joining(";")))
                                    .toArray(String[]::new))
                    .param(
                            "replaced",
                            batch.stream()
                                    .map(row -> nullToEmpty(row.replacedByCode()))
                                    .toArray(String[]::new))
                    .param(
                            "levels",
                            batch.stream()
                                    .map(row -> row.level() == null ? "" : String.valueOf(row.level()))
                                    .toArray(String[]::new))
                    .param(
                            "validFrom",
                            batch.stream()
                                    .map(row -> row.validFrom().toString())
                                    .toArray(String[]::new))
                    .param("depths", batch.stream().map(ReferenceRow::depth).toArray(Integer[]::new))
                    .update();
        }
    }

    /** How many staging rows a run holds. */
    public int stagedRows(UUID runId) {
        return jdbc.sql("SELECT count(*) FROM catalog.mxik_import_run_rows WHERE run_id = :run")
                .param("run", runId)
                .query(Integer.class)
                .single();
    }

    /** Counts of what applying this run would do to the list as it stands. */
    public record Diff(int created, int changed, int unchanged, int withdrawn, int reinstated) {}

    public Diff diff(UUID runId, boolean fullSnapshot) {
        return jdbc.sql("""
                        SELECT
                          count(*) FILTER (WHERE m.code IS NULL) AS created,
                          count(*) FILTER (WHERE m.code IS NOT NULL AND m.valid_until IS NOT NULL) AS reinstated,
                          count(*) FILTER (WHERE m.code IS NOT NULL AND m.valid_until IS NULL AND (%s)) AS changed,
                          count(*) FILTER (WHERE m.code IS NOT NULL AND m.valid_until IS NULL AND NOT (%s)) AS unchanged
                          FROM catalog.mxik_import_run_rows s
                          LEFT JOIN catalog.mxik_reference m ON m.code = s.code
                         WHERE s.run_id = :run
                        """.formatted(DIFFERS, DIFFERS))
                .param("run", runId)
                .query((row, number) -> new Diff(
                        row.getInt("created"),
                        row.getInt("changed"),
                        row.getInt("unchanged"),
                        fullSnapshot ? withdrawnCount(runId) : 0,
                        row.getInt("reinstated")))
                .single();
    }

    private int withdrawnCount(UUID runId) {
        return jdbc.sql("""
                        SELECT count(*) FROM catalog.mxik_reference m
                         WHERE m.valid_until IS NULL
                           AND NOT EXISTS (SELECT 1 FROM catalog.mxik_import_run_rows s
                                            WHERE s.run_id = :run AND s.code = m.code)
                        """).param("run", runId).query(Integer.class).single();
    }

    private static final String DIFFERS = """
            m.parent_code IS DISTINCT FROM s.parent_code OR m.label_ru <> s.label_ru OR m.label_uz <> s.label_uz
            OR m.label_en IS DISTINCT FROM s.label_en
            OR m.default_package_codes IS DISTINCT FROM s.default_package_codes
            OR m.default_unit_codes IS DISTINCT FROM s.default_unit_codes
            OR m.replaced_by_code IS DISTINCT FROM s.replaced_by_code OR m.level IS DISTINCT FROM s.level""";

    /** Codes the run would withdraw, up to {@code limit}. */
    public List<String> codesToWithdraw(UUID runId, int limit) {
        return jdbc.sql("""
                        SELECT m.code FROM catalog.mxik_reference m
                         WHERE m.valid_until IS NULL
                           AND NOT EXISTS (SELECT 1 FROM catalog.mxik_import_run_rows s
                                            WHERE s.run_id = :run AND s.code = m.code)
                         ORDER BY m.code LIMIT :limit
                        """)
                .param("run", runId)
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    public List<String> codesToReinstate(UUID runId, int limit) {
        return jdbc.sql("""
                        SELECT s.code FROM catalog.mxik_import_run_rows s
                          JOIN catalog.mxik_reference m ON m.code = s.code
                         WHERE s.run_id = :run AND m.valid_until IS NOT NULL
                         ORDER BY s.code LIMIT :limit
                        """)
                .param("run", runId)
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    public List<String> codesWhosePackageDefaultsChange(UUID runId, int limit) {
        return jdbc.sql("""
                        SELECT s.code FROM catalog.mxik_import_run_rows s
                          JOIN catalog.mxik_reference m ON m.code = s.code
                         WHERE s.run_id = :run AND m.valid_until IS NULL
                           AND m.default_package_codes IS DISTINCT FROM s.default_package_codes
                         ORDER BY s.code LIMIT :limit
                        """)
                .param("run", runId)
                .param("limit", limit)
                .query(String.class)
                .list();
    }

    /** Whether any tenant has given any node an ИКПУ/MXIK code, which is what makes an empty list a problem. */
    public boolean anyTenantHasClassifiedNode() {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM catalog.fiscal_classifications WHERE mxik_code IS NOT NULL)")
                .query(Boolean.class)
                .single();
    }

    /** The currently valid rows of the list. */
    public int validRows() {
        return jdbc.sql("SELECT count(*) FROM catalog.mxik_reference WHERE " + MXIK_VALID_TODAY)
                .query(Integer.class)
                .single();
    }

    public int withdrawnRows() {
        return jdbc.sql("SELECT count(*) FROM catalog.mxik_reference"
                        + " WHERE valid_until IS NOT NULL AND CURRENT_DATE >= valid_until")
                .query(Integer.class)
                .single();
    }

    private static final String MXIK_VALID_TODAY = uz.horecaos.platform.catalog.domain.MxikValidity.SQL_VALID_TODAY;

    // ------------------------------------------------------------------------- the apply

    /** Distinct depths of a run's rows, shallowest first. */
    public List<Integer> depths(UUID runId) {
        return jdbc.sql("SELECT DISTINCT depth FROM catalog.mxik_import_run_rows WHERE run_id = :run ORDER BY depth")
                .param("run", runId)
                .query(Integer.class)
                .list();
    }

    public List<Integer> ordinalsAtDepth(UUID runId, int depth) {
        return jdbc.sql("""
                SELECT ordinal FROM catalog.mxik_import_run_rows
                 WHERE run_id = :run AND depth = :depth ORDER BY ordinal
                """)
                .param("run", runId)
                .param("depth", depth)
                .query(Integer.class)
                .list();
    }

    /**
     * Writes one slice of a depth's rows, idempotent on {@code code}: a new code is inserted, a known one has
     * its content refreshed, and a withdrawn one is reinstated. {@code valid_from} and {@code first_run_id} of
     * an existing row are kept. The importer always fills {@code valid_from} (a file with no start date takes
     * the 17-digit regime's first day, ADR 0173); the fallback here only keeps the statement total.
     */
    public void upsertSlice(UUID runId, int depth, int fromOrdinal, int toOrdinal, int datasetVersion) {
        jdbc.sql("""
                INSERT INTO catalog.mxik_reference (
                    code, parent_code, label_ru, label_uz, label_en, default_package_codes, default_unit_codes,
                    valid_from, valid_until, imported_at, dataset_version, first_run_id, last_run_id,
                    withdrawn_run_id, replaced_by_code, level)
                SELECT s.code, s.parent_code, s.label_ru, s.label_uz, s.label_en, s.default_package_codes,
                       s.default_unit_codes, COALESCE(s.valid_from, DATE '2021-08-01'), NULL, now(),
                       :version, :run, :run, NULL, s.replaced_by_code, s.level
                  FROM catalog.mxik_import_run_rows s
                 WHERE s.run_id = :run AND s.depth = :depth AND s.ordinal BETWEEN :from AND :to
                ON CONFLICT (code) DO UPDATE
                   SET parent_code = EXCLUDED.parent_code, label_ru = EXCLUDED.label_ru,
                       label_uz = EXCLUDED.label_uz, label_en = EXCLUDED.label_en,
                       default_package_codes = EXCLUDED.default_package_codes,
                       default_unit_codes = EXCLUDED.default_unit_codes,
                       valid_until = NULL, withdrawn_run_id = NULL, imported_at = EXCLUDED.imported_at,
                       dataset_version = EXCLUDED.dataset_version, last_run_id = EXCLUDED.last_run_id,
                       replaced_by_code = EXCLUDED.replaced_by_code, level = EXCLUDED.level
                """)
                .param("run", runId)
                .param("depth", depth)
                .param("from", fromOrdinal)
                .param("to", toOrdinal)
                .param("version", datasetVersion)
                .update();
    }

    /** Dates the withdrawal of every valid code the run's file does not contain. Never deletes. */
    public int withdrawMissing(UUID runId, LocalDate asOf, int datasetVersion) {
        return jdbc.sql("""
                UPDATE catalog.mxik_reference m
                   SET valid_until = GREATEST(CAST(:asOf AS date), m.valid_from + 1),
                       withdrawn_run_id = :run, last_run_id = :run, dataset_version = :version
                 WHERE m.valid_until IS NULL
                   AND NOT EXISTS (SELECT 1 FROM catalog.mxik_import_run_rows s
                                    WHERE s.run_id = :run AND s.code = m.code)
                """)
                .param("run", runId)
                .param("asOf", asOf)
                .param("version", datasetVersion)
                .update();
    }

    public void clearRows(UUID runId) {
        jdbc.sql("DELETE FROM catalog.mxik_import_run_rows WHERE run_id = :run")
                .param("run", runId)
                .update();
    }

    // --------------------------------------------------------------------------- the state

    public record StateRow(
            int datasetVersion,
            java.time.Instant importedAt,
            String sourceSha256,
            @Nullable LocalDate sourcePublishedOn,
            int validRows,
            int withdrawnRows,
            @Nullable UUID lastRunId) {}

    public Optional<StateRow> state() {
        return jdbc.sql("""
                        SELECT dataset_version, imported_at, source_sha256, source_published_on, valid_rows,
                               withdrawn_rows, last_run_id
                          FROM catalog.mxik_reference_state WHERE id = 1
                        """)
                .query((row, number) -> new StateRow(
                        row.getInt("dataset_version"),
                        row.getObject("imported_at", OffsetDateTime.class).toInstant(),
                        row.getString("source_sha256"),
                        row.getObject("source_published_on", LocalDate.class),
                        row.getInt("valid_rows"),
                        row.getInt("withdrawn_rows"),
                        row.getObject("last_run_id", UUID.class)))
                .optional();
    }

    public void writeState(
            int datasetVersion,
            String sha256,
            @Nullable LocalDate publishedOn,
            int validRows,
            int withdrawnRows,
            UUID runId) {
        jdbc.sql("""
                INSERT INTO catalog.mxik_reference_state (
                    id, dataset_version, imported_at, source_sha256, source_published_on, valid_rows,
                    withdrawn_rows, last_run_id)
                VALUES (1, :version, now(), :sha, :publishedOn, :valid, :withdrawn, :run)
                ON CONFLICT (id) DO UPDATE
                   SET dataset_version = EXCLUDED.dataset_version, imported_at = EXCLUDED.imported_at,
                       source_sha256 = EXCLUDED.source_sha256, source_published_on = EXCLUDED.source_published_on,
                       valid_rows = EXCLUDED.valid_rows, withdrawn_rows = EXCLUDED.withdrawn_rows,
                       last_run_id = EXCLUDED.last_run_id
                """)
                .param("version", datasetVersion)
                .param("sha", sha256)
                .param("publishedOn", publishedOn)
                .param("valid", validRows)
                .param("withdrawn", withdrawnRows)
                .param("run", runId)
                .update();
    }

    // ------------------------------------------------------------------------- internals

    private static String nullToEmpty(@Nullable String value) {
        return value == null ? "" : value;
    }

    private static RunRow mapRun(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        return new RunRow(
                row.getObject("id", UUID.class),
                row.getString("status"),
                row.getString("mode"),
                row.getString("mapping_profile"),
                row.getString("source_file_name"),
                row.getString("source_sha256"),
                row.getString("source_object_key"),
                row.getObject("source_published_on", LocalDate.class),
                row.getInt("rows_total"),
                row.getInt("rows_new"),
                row.getInt("rows_changed"),
                row.getInt("rows_unchanged"),
                row.getInt("rows_withdrawn"),
                row.getInt("rows_reinstated"),
                row.getInt("rows_error"),
                row.getInt("labels_borrowed"),
                row.getInt("withdrawal_threshold_percent"),
                row.getObject("acknowledged_withdrawals", Integer.class),
                row.getString("histogram"),
                row.getString("created_by"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                row.getString("applied_by"),
                row.getObject("applied_at", OffsetDateTime.class) == null
                        ? null
                        : row.getObject("applied_at", OffsetDateTime.class).toInstant(),
                row.getObject("dataset_version", Integer.class),
                row.getString("failure_reason"),
                row.getInt("version"));
    }
}
