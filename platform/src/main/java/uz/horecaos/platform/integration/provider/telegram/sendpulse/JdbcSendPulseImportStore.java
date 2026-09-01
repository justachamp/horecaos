package uz.horecaos.platform.integration.provider.telegram.sendpulse;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Persistence for one SendPulse import run and its per-row report (ADR 0059
 * stage 3, V0111) — the {@code pos.sync_runs}/{@code pos_sync_differences}
 * shape (ADR 0012), narrowed to what an import needs: a header row with
 * counts, and one child row per parsed input line, written identically on a
 * dry run and a real one.
 */
@Repository
public class JdbcSendPulseImportStore {

    private final JdbcClient jdbc;

    public JdbcSendPulseImportStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void openRun(
            UUID runId,
            UUID tenantId,
            UUID installationId,
            UUID brandId,
            boolean dryRun,
            SendPulseImportFormat format,
            String sourceFileName,
            String importedByPrincipalId,
            Instant startedAt) {
        jdbc.sql("""
                INSERT INTO integration.sendpulse_import_runs (
                    id, tenant_id, installation_id, brand_id, dry_run, status,
                    source_format, source_file_name, imported_by_principal_id, started_at)
                VALUES (:id, :tenantId, :installationId, :brandId, :dryRun, 'FAILED',
                    :format, :fileName, :importedBy, :startedAt)
                """)
                .param("id", runId)
                .param("tenantId", tenantId)
                .param("installationId", installationId)
                .param("brandId", brandId)
                .param("dryRun", dryRun)
                .param("format", format.name())
                .param("fileName", sourceFileName)
                .param("importedBy", importedByPrincipalId)
                .param("startedAt", utc(startedAt))
                .update();
    }

    /**
     * Freezes the final counts and status. Started as {@code FAILED} above so
     * a run that throws before reaching here — a malformed document, per
     * {@link SendPulseContactFileParser.SendPulseImportFormatException} —
     * leaves an honest, already-written row behind rather than none at all.
     */
    public void completeRun(UUID tenantId, UUID runId, RunCounts counts, boolean dryRun, Instant completedAt) {
        jdbc.sql("""
                UPDATE integration.sendpulse_import_runs
                SET status = :status,
                    rows_total = :total,
                    rows_created_customer = :created,
                    rows_matched_customer = :matched,
                    rows_skipped_already_linked = :skipped,
                    rows_rejected = :rejected,
                    rows_subscribed = :subscribed,
                    rows_unsubscribed = :unsubscribed,
                    completed_at = :completedAt
                WHERE tenant_id = :tenantId AND id = :runId
                """)
                .param("status", dryRun ? "DRY_RUN_COMPLETE" : "COMPLETE")
                .param("total", counts.total())
                .param("created", counts.createdCustomer())
                .param("matched", counts.matchedCustomer())
                .param("skipped", counts.skippedAlreadyLinked())
                .param("rejected", counts.rejected())
                .param("subscribed", counts.subscribed())
                .param("unsubscribed", counts.unsubscribed())
                .param("completedAt", utc(completedAt))
                .param("tenantId", tenantId)
                .param("runId", runId)
                .update();
    }

    public void insertRow(
            UUID tenantId,
            UUID runId,
            int rowNumber,
            @Nullable Long chatId,
            @Nullable Boolean subscribed,
            String outcome,
            @Nullable UUID customerAccountId,
            @Nullable String rejectReason,
            Instant now) {
        jdbc.sql("""
                INSERT INTO integration.sendpulse_import_run_rows (
                    id, tenant_id, run_id, row_number, chat_id, subscribed, outcome,
                    customer_account_id, reject_reason, created_at)
                VALUES (:id, :tenantId, :runId, :rowNumber, :chatId, :subscribed, :outcome,
                    :customerAccountId, :rejectReason, :now)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("runId", runId)
                .param("rowNumber", rowNumber)
                .param("chatId", chatId, java.sql.Types.BIGINT)
                .param("subscribed", subscribed, java.sql.Types.BOOLEAN)
                .param("outcome", outcome)
                .param("customerAccountId", customerAccountId)
                .param("rejectReason", rejectReason, java.sql.Types.VARCHAR)
                .param("now", utc(now))
                .update();
    }

    public Optional<RunRow> run(UUID tenantId, UUID runId) {
        return jdbc.sql("""
                SELECT id, installation_id, brand_id, dry_run, status, source_format, source_file_name,
                       imported_by_principal_id, rows_total, rows_created_customer, rows_matched_customer,
                       rows_skipped_already_linked, rows_rejected, rows_subscribed, rows_unsubscribed,
                       started_at, completed_at
                FROM integration.sendpulse_import_runs
                WHERE tenant_id = :tenantId AND id = :runId
                """)
                .param("tenantId", tenantId)
                .param("runId", runId)
                .query((row, number) -> new RunRow(
                        row.getObject("id", UUID.class),
                        row.getObject("installation_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getBoolean("dry_run"),
                        row.getString("status"),
                        row.getString("source_format"),
                        row.getString("source_file_name"),
                        row.getString("imported_by_principal_id"),
                        new RunCounts(
                                row.getInt("rows_total"),
                                row.getInt("rows_created_customer"),
                                row.getInt("rows_matched_customer"),
                                row.getInt("rows_skipped_already_linked"),
                                row.getInt("rows_rejected"),
                                row.getInt("rows_subscribed"),
                                row.getInt("rows_unsubscribed")),
                        row.getObject("started_at", OffsetDateTime.class).toInstant(),
                        row.getObject("completed_at", OffsetDateTime.class) == null
                                ? null
                                : row.getObject("completed_at", OffsetDateTime.class)
                                        .toInstant()))
                .optional();
    }

    /** Every row of one run's report, in the order it was written — deterministic, like {@code pos_sync_differences}. */
    public List<ImportRowView> rows(UUID tenantId, UUID runId, int limit, int offset) {
        return jdbc.sql("""
                SELECT row_number, chat_id, subscribed, outcome, customer_account_id, reject_reason
                FROM integration.sendpulse_import_run_rows
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
                        (Long) row.getObject("chat_id"),
                        (Boolean) row.getObject("subscribed"),
                        row.getString("outcome"),
                        row.getObject("customer_account_id", UUID.class),
                        row.getString("reject_reason")))
                .list();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record RunCounts(
            int total,
            int createdCustomer,
            int matchedCustomer,
            int skippedAlreadyLinked,
            int rejected,
            int subscribed,
            int unsubscribed) {

        public static RunCounts zero() {
            return new RunCounts(0, 0, 0, 0, 0, 0, 0);
        }
    }

    public record RunRow(
            UUID id,
            UUID installationId,
            UUID brandId,
            boolean dryRun,
            String status,
            String sourceFormat,
            String sourceFileName,
            String importedByPrincipalId,
            RunCounts counts,
            Instant startedAt,
            @Nullable Instant completedAt) {}

    public record ImportRowView(
            int rowNumber,
            @Nullable Long chatId,
            @Nullable Boolean subscribed,
            String outcome,
            @Nullable UUID customerAccountId,
            @Nullable String rejectReason) {}
}
