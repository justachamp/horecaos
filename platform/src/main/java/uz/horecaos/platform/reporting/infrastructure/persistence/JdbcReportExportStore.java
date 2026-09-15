package uz.horecaos.platform.reporting.infrastructure.persistence;

import java.sql.ResultSet;
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
import tools.jackson.databind.ObjectMapper;

/** Persistence for {@code reporting.report_exports} (ADR 0043/ADR 0029, wave P28). */
@Repository
public class JdbcReportExportStore {

    private final JdbcClient jdbc;
    private final ObjectMapper objectMapper;

    public JdbcReportExportStore(JdbcClient jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void insertQueued(
            UUID id,
            UUID tenantId,
            String reportKey,
            String requestedBySubject,
            String purpose,
            String filtersJson,
            @Nullable String encryptedQuery,
            List<String> requestedColumns,
            List<String> effectiveColumns,
            boolean includesPiiColumns,
            int rowQuota,
            Instant now) {
        jdbc.sql("""
                INSERT INTO reporting.report_exports (
                    id, tenant_id, report_key, status, requested_by_subject, purpose,
                    filters, encrypted_query, requested_columns, effective_columns,
                    includes_pii_columns, row_quota, created_at)
                VALUES (
                    :id, :tenantId, :reportKey, 'QUEUED', :subject, :purpose,
                    :filters::jsonb, :encryptedQuery, :requestedColumns::jsonb, :effectiveColumns::jsonb,
                    :includesPii, :rowQuota, :now)
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("reportKey", reportKey)
                .param("subject", requestedBySubject)
                .param("purpose", purpose)
                .param("filters", filtersJson)
                .param("encryptedQuery", encryptedQuery)
                .param("requestedColumns", writeJson(requestedColumns))
                .param("effectiveColumns", writeJson(effectiveColumns))
                .param("includesPii", includesPiiColumns)
                .param("rowQuota", rowQuota)
                .param("now", utc(now))
                .update();
    }

    /**
     * Claims the oldest {@code QUEUED} row, the same {@code FOR UPDATE SKIP LOCKED} idiom {@code
     * JdbcCustomerImportStore#claimNextQueuedRun} uses. Its own {@code REQUIRES_NEW} transaction
     * so the claim commits regardless of how the caller's own processing later succeeds or fails.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<ClaimedExport> claimNextQueued(Instant now) {
        return jdbc.sql("""
                WITH candidate AS (
                    SELECT id
                    FROM reporting.report_exports
                    WHERE status = 'QUEUED'
                    ORDER BY created_at
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                UPDATE reporting.report_exports AS export
                SET status = 'RUNNING', started_at = :now
                FROM candidate
                WHERE export.id = candidate.id
                RETURNING export.id, export.tenant_id, export.report_key, export.requested_by_subject,
                          export.purpose, export.filters, export.encrypted_query,
                          export.effective_columns, export.includes_pii_columns, export.row_quota
                """)
                .param("now", utc(now))
                .query((row, number) -> mapClaimed(row))
                .optional();
    }

    private ClaimedExport mapClaimed(ResultSet row) throws java.sql.SQLException {
        return new ClaimedExport(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getString("report_key"),
                row.getString("requested_by_subject"),
                row.getString("purpose"),
                readMap(row.getString("filters")),
                row.getString("encrypted_query"),
                readStringList(row.getString("effective_columns")),
                row.getBoolean("includes_pii_columns"),
                row.getInt("row_quota"));
    }

    /**
     * Settles a claimed export as {@code COMPLETE} and clears the one field ADR 0029 says must not
     * outlive its purpose — see this table's own {@code ck_report_export_query_retention}.
     */
    public void completeExport(
            UUID id,
            String artifactBucket,
            String artifactObjectKey,
            String artifactContentType,
            long artifactSizeBytes,
            String artifactChecksumSha256,
            int rowCount,
            boolean truncated,
            Instant now) {
        jdbc.sql("""
                UPDATE reporting.report_exports
                SET status = 'COMPLETE',
                    artifact_bucket = :bucket,
                    artifact_object_key = :objectKey,
                    artifact_content_type = :contentType,
                    artifact_size_bytes = :sizeBytes,
                    artifact_checksum_sha256 = :checksum,
                    row_count = :rowCount,
                    truncated = :truncated,
                    encrypted_query = NULL,
                    completed_at = :now
                WHERE id = :id
                """)
                .param("id", id)
                .param("bucket", artifactBucket)
                .param("objectKey", artifactObjectKey)
                .param("contentType", artifactContentType)
                .param("sizeBytes", artifactSizeBytes)
                .param("checksum", artifactChecksumSha256)
                .param("rowCount", rowCount)
                .param("truncated", truncated)
                .param("now", utc(now))
                .update();
    }

    public void failExport(UUID id, String failureReason, Instant now) {
        jdbc.sql("""
                UPDATE reporting.report_exports
                SET status = 'FAILED', failure_reason = :reason, encrypted_query = NULL, completed_at = :now
                WHERE id = :id
                """)
                .param("id", id)
                .param("reason", failureReason)
                .param("now", utc(now))
                .update();
    }

    public Optional<ExportRow> find(UUID tenantId, UUID id) {
        return jdbc.sql("""
                SELECT id, tenant_id, report_key, status, effective_columns, includes_pii_columns,
                       row_quota, row_count, truncated, artifact_bucket, artifact_object_key,
                       artifact_content_type, artifact_size_bytes, failure_reason, created_at,
                       completed_at
                FROM reporting.report_exports
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId)
                .param("id", id)
                .query(this::mapExportRow)
                .optional();
    }

    /** Newest first, for the export centre's own history list. */
    public List<ExportRow> listRecent(UUID tenantId, int limit) {
        return jdbc.sql("""
                SELECT id, tenant_id, report_key, status, effective_columns, includes_pii_columns,
                       row_quota, row_count, truncated, artifact_bucket, artifact_object_key,
                       artifact_content_type, artifact_size_bytes, failure_reason, created_at,
                       completed_at
                FROM reporting.report_exports
                WHERE tenant_id = :tenantId
                ORDER BY created_at DESC
                LIMIT :limit
                """)
                .param("tenantId", tenantId)
                .param("limit", limit)
                .query(this::mapExportRow)
                .list();
    }

    private ExportRow mapExportRow(ResultSet row, int number) throws java.sql.SQLException {
        Instant completedAt = row.getObject("completed_at", OffsetDateTime.class) == null
                ? null
                : row.getObject("completed_at", OffsetDateTime.class).toInstant();
        Long sizeBytes = (Long) row.getObject("artifact_size_bytes");
        Integer rowCount = (Integer) row.getObject("row_count");
        return new ExportRow(
                row.getObject("id", UUID.class),
                row.getObject("tenant_id", UUID.class),
                row.getString("report_key"),
                row.getString("status"),
                readStringList(row.getString("effective_columns")),
                row.getBoolean("includes_pii_columns"),
                row.getInt("row_quota"),
                rowCount,
                row.getBoolean("truncated"),
                row.getString("artifact_bucket"),
                row.getString("artifact_object_key"),
                row.getString("artifact_content_type"),
                sizeBytes,
                row.getString("failure_reason"),
                row.getObject("created_at", OffsetDateTime.class).toInstant(),
                completedAt);
    }

    private String writeJson(Object value) {
        return objectMapper.writeValueAsString(value);
    }

    @SuppressWarnings("unchecked")
    private List<String> readStringList(@Nullable String json) {
        return json == null ? List.of() : objectMapper.readValue(json, List.class);
    }

    @SuppressWarnings("unchecked")
    private java.util.Map<String, Object> readMap(@Nullable String json) {
        return json == null ? java.util.Map.of() : objectMapper.readValue(json, java.util.Map.class);
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public record ClaimedExport(
            UUID id,
            UUID tenantId,
            String reportKey,
            String requestedBySubject,
            String purpose,
            java.util.Map<String, Object> filters,
            @Nullable String encryptedQuery,
            List<String> effectiveColumns,
            boolean includesPiiColumns,
            int rowQuota) {}

    public record ExportRow(
            UUID id,
            UUID tenantId,
            String reportKey,
            String status,
            List<String> effectiveColumns,
            boolean includesPiiColumns,
            int rowQuota,
            @Nullable Integer rowCount,
            boolean truncated,
            @Nullable String artifactBucket,
            @Nullable String artifactObjectKey,
            @Nullable String artifactContentType,
            @Nullable Long artifactSizeBytes,
            @Nullable String failureReason,
            Instant createdAt,
            @Nullable Instant completedAt) {}
}
