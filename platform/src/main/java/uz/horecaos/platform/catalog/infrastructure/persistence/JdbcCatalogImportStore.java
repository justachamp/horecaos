package uz.horecaos.platform.catalog.infrastructure.persistence;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persistence for one catalog CSV/Excel import run and its per-row report
 * (row 4.5b, V0380) — {@code JdbcCustomerImportStore}'s own shape (V0232/
 * V0233), narrowed to a brand's catalog instead of a customer address book.
 */
@Repository
public class JdbcCatalogImportStore {

    private final JdbcClient jdbc;

    public JdbcCatalogImportStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertQueuedRun(
            UUID runId,
            UUID tenantId,
            UUID brandId,
            UUID catalogId,
            boolean dryRun,
            String sourceFileName,
            String content,
            String importedByPrincipalId,
            int rowsTotal,
            Instant createdAt) {
        jdbc.sql("""
                INSERT INTO catalog.import_runs (
                    id, tenant_id, brand_id, catalog_id, dry_run, status, source_file_name, content,
                    imported_by_principal_id, rows_total, created_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, :dryRun, 'QUEUED', :fileName, :content,
                    :importedBy, :rowsTotal, :createdAt)
                """)
                .param("id", runId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("catalogId", catalogId)
                .param("dryRun", dryRun)
                .param("fileName", sourceFileName)
                .param("content", content)
                .param("importedBy", importedByPrincipalId)
                .param("rowsTotal", rowsTotal)
                .param("createdAt", utc(createdAt))
                .update();
    }

    /**
     * Claims the oldest {@code QUEUED} run across every tenant, if one exists.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} inside its own {@code REQUIRES_NEW}
     * transaction, matching {@code JdbcCustomerImportStore#claimNextQueuedRun}'s
     * own idiom, so the claim commits on its own regardless of how the
     * caller's own processing later succeeds or fails.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedRun> claimNextQueuedRun(Instant now) {
        return jdbc.sql("""
                WITH candidate AS (
                    SELECT id
                    FROM catalog.import_runs
                    WHERE status = 'QUEUED'
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE catalog.import_runs AS run
                SET status = 'RUNNING', started_at = :now
                FROM candidate
                WHERE run.id = candidate.id
                RETURNING run.id, run.tenant_id, run.brand_id, run.catalog_id, run.dry_run, run.content,
                          run.source_file_name, run.rows_total, run.imported_by_principal_id
                """)
                .param("now", utc(now))
                .query((row, number) -> new ClaimedRun(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("catalog_id", UUID.class),
                        row.getBoolean("dry_run"),
                        row.getString("content"),
                        row.getString("source_file_name"),
                        row.getInt("rows_total"),
                        row.getString("imported_by_principal_id")))
                .optional();
    }

    /** One row processed: advances {@code rows_processed} and exactly one outcome counter. */
    public void advanceProgress(UUID tenantId, UUID runId, String outcome) {
        jdbc.sql("""
                UPDATE catalog.import_runs
                SET rows_processed = rows_processed + 1,
                    rows_created = rows_created + CASE WHEN :outcome = 'CREATED' THEN 1 ELSE 0 END,
                    rows_updated = rows_updated + CASE WHEN :outcome = 'UPDATED' THEN 1 ELSE 0 END,
                    rows_skipped = rows_skipped + CASE WHEN :outcome = 'SKIPPED' THEN 1 ELSE 0 END,
                    rows_error = rows_error + CASE WHEN :outcome = 'ERROR' THEN 1 ELSE 0 END
                WHERE tenant_id = :tenantId AND id = :runId
                """)
                .param("outcome", outcome)
                .param("tenantId", tenantId)
                .param("runId", runId)
                .update();
    }

    public void insertRow(
            UUID tenantId,
            UUID runId,
            int rowNumber,
            String outcome,
            @Nullable UUID productId,
            @Nullable UUID variantId,
            @Nullable String errorReason,
            Instant now) {
        jdbc.sql("""
                INSERT INTO catalog.import_run_rows (
                    id, tenant_id, run_id, row_number, outcome, product_id, variant_id, error_reason, created_at)
                VALUES (:id, :tenantId, :runId, :rowNumber, :outcome, :productId, :variantId, :errorReason, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("runId", runId)
                .param("rowNumber", rowNumber)
                .param("outcome", outcome)
                .param("productId", productId, Types.OTHER)
                .param("variantId", variantId, Types.OTHER)
                .param("errorReason", errorReason, Types.VARCHAR)
                .param("now", utc(now))
                .update();
    }

    /** Freezes the final status and clears the source content — see V0380's own retention comment. */
    public void completeRun(UUID tenantId, UUID runId, boolean dryRun, Instant completedAt) {
        jdbc.sql("""
                UPDATE catalog.import_runs
                SET status = :status, completed_at = :completedAt, content = NULL
                WHERE tenant_id = :tenantId AND id = :runId
                """)
                .param("status", dryRun ? "DRY_RUN_COMPLETE" : "COMPLETE")
                .param("completedAt", utc(completedAt))
                .param("tenantId", tenantId)
                .param("runId", runId)
                .update();
    }

    public void failRun(UUID tenantId, UUID runId, String failureReason, Instant completedAt) {
        jdbc.sql("""
                UPDATE catalog.import_runs
                SET status = 'FAILED', failure_reason = :reason, completed_at = :completedAt, content = NULL
                WHERE tenant_id = :tenantId AND id = :runId
                """)
                .param("reason", failureReason)
                .param("completedAt", utc(completedAt))
                .param("tenantId", tenantId)
                .param("runId", runId)
                .update();
    }

    public Optional<RunRow> run(UUID tenantId, UUID runId) {
        return jdbc.sql("""
                SELECT id, brand_id, catalog_id, dry_run, status, source_file_name, rows_total, rows_processed,
                       rows_created, rows_updated, rows_skipped, rows_error, failure_reason,
                       created_at, started_at, completed_at
                FROM catalog.import_runs
                WHERE tenant_id = :tenantId AND id = :runId
                """)
                .param("tenantId", tenantId)
                .param("runId", runId)
                .query((row, number) -> new RunRow(
                        row.getObject("id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("catalog_id", UUID.class),
                        row.getBoolean("dry_run"),
                        row.getString("status"),
                        row.getString("source_file_name"),
                        row.getInt("rows_total"),
                        row.getInt("rows_processed"),
                        row.getInt("rows_created"),
                        row.getInt("rows_updated"),
                        row.getInt("rows_skipped"),
                        row.getInt("rows_error"),
                        row.getString("failure_reason"),
                        toInstant(row.getObject("started_at", OffsetDateTime.class)),
                        toInstant(row.getObject("completed_at", OffsetDateTime.class))))
                .optional();
    }

    /** Every run for one brand, newest first — the run history list. */
    public List<RunRow> runsForBrand(UUID tenantId, UUID brandId, int limit) {
        return jdbc.sql("""
                SELECT id, brand_id, catalog_id, dry_run, status, source_file_name, rows_total, rows_processed,
                       rows_created, rows_updated, rows_skipped, rows_error, failure_reason,
                       created_at, started_at, completed_at
                FROM catalog.import_runs
                WHERE tenant_id = :tenantId AND brand_id = :brandId
                ORDER BY created_at DESC
                LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("limit", limit)
                .query((row, number) -> new RunRow(
                        row.getObject("id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("catalog_id", UUID.class),
                        row.getBoolean("dry_run"),
                        row.getString("status"),
                        row.getString("source_file_name"),
                        row.getInt("rows_total"),
                        row.getInt("rows_processed"),
                        row.getInt("rows_created"),
                        row.getInt("rows_updated"),
                        row.getInt("rows_skipped"),
                        row.getInt("rows_error"),
                        row.getString("failure_reason"),
                        toInstant(row.getObject("started_at", OffsetDateTime.class)),
                        toInstant(row.getObject("completed_at", OffsetDateTime.class))))
                .list();
    }

    /** Every row of one run's report, in the order it was written. */
    public List<ImportRowView> rows(UUID tenantId, UUID runId, int limit, int offset) {
        return jdbc.sql("""
                SELECT row_number, outcome, product_id, variant_id, error_reason
                FROM catalog.import_run_rows
                WHERE tenant_id = :tenantId AND run_id = :runId
                ORDER BY row_number
                LIMIT :limit OFFSET :offset
                """)
                .param("tenantId", tenantId)
                .param("runId", runId)
                .param("limit", limit)
                .param("offset", offset)
                .query((row, number) -> new ImportRowView(
                        row.getInt("row_number"),
                        row.getString("outcome"),
                        row.getObject("product_id", UUID.class),
                        row.getObject("variant_id", UUID.class),
                        row.getString("error_reason")))
                .list();
    }

    private static @Nullable Instant toInstant(@Nullable OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record ClaimedRun(
            UUID id,
            UUID tenantId,
            UUID brandId,
            UUID catalogId,
            boolean dryRun,
            String content,
            String sourceFileName,
            int rowsTotal,
            String importedByPrincipalId) {}

    public record RunRow(
            UUID id,
            UUID brandId,
            UUID catalogId,
            boolean dryRun,
            String status,
            String sourceFileName,
            int rowsTotal,
            int rowsProcessed,
            int rowsCreated,
            int rowsUpdated,
            int rowsSkipped,
            int rowsError,
            @Nullable String failureReason,
            @Nullable Instant startedAt,
            @Nullable Instant completedAt) {}

    public record ImportRowView(
            int rowNumber,
            String outcome,
            @Nullable UUID productId,
            @Nullable UUID variantId,
            @Nullable String errorReason) {}
}
