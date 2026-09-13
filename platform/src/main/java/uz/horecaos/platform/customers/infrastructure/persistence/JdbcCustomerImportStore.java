package uz.horecaos.platform.customers.infrastructure.persistence;

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
 * Persistence for one customer CSV import run and its per-row report (row
 * {@code X.13}/{@code 5.1b}, V0232/V0233) — {@code
 * integration.sendpulse_import_runs}' own shape (V0111), narrowed to a
 * generic address-book import, plus the one thing that import never needed:
 * a {@code QUEUED} state and a claim query, because a synchronous POST has
 * no reason to be claimed by anything.
 */
@Repository
public class JdbcCustomerImportStore {

    private final JdbcClient jdbc;

    public JdbcCustomerImportStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insertQueuedRun(
            UUID runId,
            UUID tenantId,
            UUID brandId,
            boolean dryRun,
            String sourceFileName,
            String encryptedContent,
            String importedByPrincipalId,
            int rowsTotal,
            Instant createdAt) {
        jdbc.sql("""
                INSERT INTO customer.customer_import_runs (
                    id, tenant_id, brand_id, dry_run, status, source_file_name, encrypted_content,
                    imported_by_principal_id, rows_total, created_at)
                VALUES (:id, :tenantId, :brandId, :dryRun, 'QUEUED', :fileName, :content,
                    :importedBy, :rowsTotal, :createdAt)
                """)
                .param("id", runId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("dryRun", dryRun)
                .param("fileName", sourceFileName)
                .param("content", encryptedContent)
                .param("importedBy", importedByPrincipalId)
                .param("rowsTotal", rowsTotal)
                .param("createdAt", utc(createdAt))
                .update();
    }

    /**
     * Claims the oldest {@code QUEUED} run across every tenant, if one exists.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} inside its own {@code REQUIRES_NEW}
     * transaction — {@code JdbcVerificationJobStore#claim}'s own idiom, so the
     * claim commits on its own regardless of how the caller's own processing
     * later succeeds or fails.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedRun> claimNextQueuedRun(Instant now) {
        return jdbc.sql("""
                WITH candidate AS (
                    SELECT id
                    FROM customer.customer_import_runs
                    WHERE status = 'QUEUED'
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE customer.customer_import_runs AS run
                SET status = 'RUNNING', started_at = :now
                FROM candidate
                WHERE run.id = candidate.id
                RETURNING run.id, run.tenant_id, run.brand_id, run.dry_run, run.encrypted_content,
                          run.source_file_name, run.rows_total
                """)
                .param("now", utc(now))
                .query((row, number) -> new ClaimedRun(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getBoolean("dry_run"),
                        row.getString("encrypted_content"),
                        row.getString("source_file_name"),
                        row.getInt("rows_total")))
                .optional();
    }

    /** One row processed: advances {@code rows_processed} and exactly one outcome counter. */
    public void advanceProgress(UUID tenantId, UUID runId, String outcome) {
        jdbc.sql("""
                UPDATE customer.customer_import_runs
                SET rows_processed = rows_processed + 1,
                    rows_created_customer = rows_created_customer
                        + CASE WHEN :outcome = 'CREATED_CUSTOMER' THEN 1 ELSE 0 END,
                    rows_matched_customer = rows_matched_customer
                        + CASE WHEN :outcome = 'MATCHED_CUSTOMER' THEN 1 ELSE 0 END,
                    rows_rejected = rows_rejected + CASE WHEN :outcome = 'REJECTED' THEN 1 ELSE 0 END
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
            @Nullable UUID customerAccountId,
            @Nullable String rejectReason,
            Instant now) {
        jdbc.sql("""
                INSERT INTO customer.customer_import_run_rows (
                    id, tenant_id, run_id, row_number, outcome, customer_account_id, reject_reason, created_at)
                VALUES (:id, :tenantId, :runId, :rowNumber, :outcome, :customerAccountId, :rejectReason, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("runId", runId)
                .param("rowNumber", rowNumber)
                .param("outcome", outcome)
                .param("customerAccountId", customerAccountId)
                .param("rejectReason", rejectReason, Types.VARCHAR)
                .param("now", utc(now))
                .update();
    }

    /** Freezes the final status and clears the source file — see V0232's own retention comment. */
    public void completeRun(UUID tenantId, UUID runId, boolean dryRun, Instant completedAt) {
        jdbc.sql("""
                UPDATE customer.customer_import_runs
                SET status = :status, completed_at = :completedAt, encrypted_content = NULL
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
                UPDATE customer.customer_import_runs
                SET status = 'FAILED', failure_reason = :reason, completed_at = :completedAt,
                    encrypted_content = NULL
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
                SELECT id, brand_id, dry_run, status, source_file_name, rows_total, rows_processed,
                       rows_created_customer, rows_matched_customer, rows_rejected, failure_reason,
                       created_at, started_at, completed_at
                FROM customer.customer_import_runs
                WHERE tenant_id = :tenantId AND id = :runId
                """)
                .param("tenantId", tenantId)
                .param("runId", runId)
                .query((row, number) -> new RunRow(
                        row.getObject("id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getBoolean("dry_run"),
                        row.getString("status"),
                        row.getString("source_file_name"),
                        row.getInt("rows_total"),
                        row.getInt("rows_processed"),
                        row.getInt("rows_created_customer"),
                        row.getInt("rows_matched_customer"),
                        row.getInt("rows_rejected"),
                        row.getString("failure_reason"),
                        toInstant(row.getObject("started_at", OffsetDateTime.class)),
                        toInstant(row.getObject("completed_at", OffsetDateTime.class))))
                .optional();
    }

    /** Every row of one run's report, in the order it was written. */
    public List<ImportRowView> rows(UUID tenantId, UUID runId, int limit, int offset) {
        return jdbc.sql("""
                SELECT row_number, outcome, customer_account_id, reject_reason
                FROM customer.customer_import_run_rows
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
                        row.getObject("customer_account_id", UUID.class),
                        row.getString("reject_reason")))
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
            boolean dryRun,
            String encryptedContent,
            String sourceFileName,
            int rowsTotal) {}

    public record RunRow(
            UUID id,
            UUID brandId,
            boolean dryRun,
            String status,
            String sourceFileName,
            int rowsTotal,
            int rowsProcessed,
            int rowsCreatedCustomer,
            int rowsMatchedCustomer,
            int rowsRejected,
            @Nullable String failureReason,
            @Nullable Instant startedAt,
            @Nullable Instant completedAt) {}

    public record ImportRowView(
            int rowNumber,
            String outcome,
            @Nullable UUID customerAccountId,
            @Nullable String rejectReason) {}
}
