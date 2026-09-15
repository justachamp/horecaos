package uz.horecaos.platform.reporting.application;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.customers.api.CustomerDirectoryExportPort;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.media.api.ObjectStorage;
import uz.horecaos.platform.reporting.domain.ReportExportDefinition;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportExportStore;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportExportStore.ClaimedExport;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportExportStore.ExportRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The export centre's own job (ADR 0043's export line, ADR 0029's egress checklist; wave P28).
 *
 * <p>{@link #requestExport} runs on the request thread and does the one thing that must happen
 * before anything is queued: it decides, from the capability check the controller already made,
 * which of the requested columns actually leave the building — the PII group is silently dropped
 * here, at request time, rather than the worker re-deciding it later with no principal in scope.
 * {@link #processNextQueued}, called only by {@link ReportExportWorker} under no HTTP request,
 * claims one row and produces its artefact; see that class's own doc for why this and not a batch
 * loop.
 *
 * <p>Only {@link ReportExportRegistry#CUSTOMER_DIRECTORY} is wired today — see that class's own
 * doc for why a second report is one more {@code case} here rather than a pluggable abstraction
 * built for a catalogue of one.
 */
@Service
public class ReportExportService {

    private static final Logger log = LoggerFactory.getLogger(ReportExportService.class);

    /**
     * The row ceiling for an export whose effective columns carry no PII. A decrypted
     * (PII-included) export is bound instead by {@link CustomerDirectoryExportPort#PII_ROW_LIMIT}
     * — see {@link #rowQuotaFor}.
     */
    static final int DEFAULT_ROW_QUOTA = 5_000;

    private static final String QUERY_TABLE = "reporting.report_exports";
    private static final String QUERY_COLUMN = "encrypted_query";
    private static final String REVEAL_PURPOSE = "Report export processing";
    private static final String FAILURE_REASON = "EXPORT_FAILED";

    private final JdbcReportExportStore store;
    private final CustomerDirectoryExportPort customerDirectory;
    private final FieldProtection protection;
    private final AuditRecorder audit;
    private final ObjectStorage storage;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String bucket;

    public ReportExportService(
            JdbcReportExportStore store,
            CustomerDirectoryExportPort customerDirectory,
            FieldProtection protection,
            AuditRecorder audit,
            ObjectStorage storage,
            TransactionTemplate transactions,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${horecaos.reporting.exports.bucket:${horecaos.media.bucket:horecaos-media}}") String bucket) {
        this.store = store;
        this.customerDirectory = customerDirectory;
        this.protection = protection;
        this.audit = audit;
        this.storage = storage;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.bucket = bucket;
    }

    /**
     * Queues an export. The PII omission is decided here, once, from {@code holdsPiiCapability} —
     * the caller's own {@code customer.pii.export} check — and stored as {@code
     * effective_columns}/{@code includes_pii_columns} so nothing later has to re-ask.
     *
     * @throws ApiException {@code VALIDATION_FAILED} for an unknown report key or column
     */
    @Transactional
    public UUID requestExport(
            UUID tenantId,
            String reportKey,
            List<String> requestedColumns,
            @Nullable String status,
            @Nullable String query,
            String purpose,
            String requestedBySubject,
            boolean holdsPiiCapability) {

        ReportExportDefinition definition = ReportExportRegistry.find(reportKey)
                .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown report key"));
        for (String column : requestedColumns) {
            if (!definition.isKnownColumn(column)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown export column: " + column);
            }
        }

        List<String> effectiveColumns = definition.effectiveColumns(requestedColumns, holdsPiiCapability);
        boolean includesPii = effectiveColumns.stream().anyMatch(definition::isPiiColumn);
        int rowQuota = rowQuotaFor(includesPii);

        UUID id = Ids.newId();
        Instant now = clock.instant();

        boolean hasSearchQuery = query != null && !query.isBlank();
        String encryptedQuery = null;
        if (query != null && !query.isBlank()) {
            encryptedQuery = protection
                    .protect(tenantId, DataClass.PERSONAL, new RecordRef(QUERY_TABLE, QUERY_COLUMN, id), query)
                    .serialize();
        }

        // Never the query text itself — see this table's own migration header and
        // CustomerListQueryService#exportFiltered's identical restraint on its own audit fact.
        Map<String, Object> filters = new LinkedHashMap<>();
        if (status != null) {
            filters.put("status", status);
        }
        filters.put("hadSearchQuery", hasSearchQuery);

        store.insertQueued(
                id,
                tenantId,
                reportKey,
                requestedBySubject,
                purpose,
                objectMapper.writeValueAsString(filters),
                encryptedQuery,
                requestedColumns,
                effectiveColumns,
                includesPii,
                rowQuota,
                now);

        return id;
    }

    private static int rowQuotaFor(boolean includesPii) {
        return includesPii ? CustomerDirectoryExportPort.PII_ROW_LIMIT : DEFAULT_ROW_QUOTA;
    }

    /**
     * Claims and processes one queued export. Deliberately not {@code @Transactional}: the
     * external object-store write below must never run inside a database transaction (the
     * discipline {@code ExternalCallTransactionBoundaryTests} enforces on the media module's own
     * equivalent path, {@code MediaAssetService#verifyUpload}), so the claim is its own short
     * transaction ({@link JdbcReportExportStore#claimNextQueued}) and the settlement below is a
     * second one, opened only after the object store has answered.
     *
     * @return true when a row was claimed, whether or not it then succeeded
     */
    public boolean processNextQueued() {
        Optional<ClaimedExport> claimed = store.claimNextQueued(clock.instant());
        if (claimed.isEmpty()) {
            return false;
        }
        ClaimedExport job = claimed.get();
        try {
            run(job);
        } catch (RuntimeException failure) {
            log.error("Report export {} failed while processing", job.id(), failure);
            store.failExport(job.id(), FAILURE_REASON, clock.instant());
        }
        return true;
    }

    private void run(ClaimedExport job) {
        String query = job.encryptedQuery() == null
                ? null
                : protection.reveal(
                        job.tenantId(),
                        ProtectedValue.deserialize(job.encryptedQuery()),
                        new RecordRef(QUERY_TABLE, QUERY_COLUMN, job.id()),
                        REVEAL_PURPOSE);
        String status = (String) job.filters().get("status");

        // Only ReportExportRegistry.CUSTOMER_DIRECTORY is wired — see this class's own doc.
        if (!ReportExportRegistry.CUSTOMER_DIRECTORY.equals(job.reportKey())) {
            throw new IllegalStateException("No export source registered for report key " + job.reportKey());
        }

        CustomerDirectoryExportPort.ExportBundle bundle = customerDirectory.export(
                job.tenantId(),
                status,
                query,
                job.includesPiiColumns(),
                job.rowQuota(),
                job.purpose(),
                ActorRef.user(job.requestedBySubject(), null));

        List<Map<String, String>> rows = bundle.rows().stream()
                .map(row -> rowAsColumns(row, job.effectiveColumns()))
                .toList();

        byte[] csv = writeCsv(job.effectiveColumns(), rows);
        String objectKey = "tenants/%s/report-exports/%s.csv".formatted(job.tenantId(), job.id());
        storage.put(bucket, objectKey, "text/csv", csv);
        String checksum = sha256Base64(csv);

        Instant now = clock.instant();
        int rowCount = rows.size();
        transactions.executeWithoutResult(status2 -> {
            store.completeExport(
                    job.id(), bucket, objectKey, "text/csv", csv.length, checksum, rowCount, bundle.truncated(), now);
            audit.record(AuditFact.of("report.export.completed", AuditClass.SECURITY)
                    .by(ActorRef.user(job.requestedBySubject(), null))
                    .at(ResourceScope.tenant(job.tenantId()))
                    .target("report_export", job.id())
                    .because(job.purpose())
                    .changed(Map.of(
                            "reportKey", job.reportKey(),
                            "rowCount", rowCount,
                            "truncated", bundle.truncated(),
                            "piiColumnGroup", job.includesPiiColumns() ? "INCLUDED" : "EXCLUDED",
                            "filters", job.filters()))
                    .correlatedBy(job.id().toString())
                    .occurredAt(now)
                    .build());
        });
        log.info("Report export {} completed: {} row(s), truncated={}", job.id(), rowCount, bundle.truncated());
    }

    private static Map<String, String> rowAsColumns(
            CustomerDirectoryExportPort.ExportedRow row, List<String> effectiveColumns) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String column : effectiveColumns) {
            values.put(
                    column,
                    switch (column) {
                        case "accountId" -> row.accountId().toString();
                        case "status" -> row.status();
                        case "displayName" -> row.displayName() == null ? "" : row.displayName();
                        case "phone" -> row.phone() == null ? "" : row.phone();
                        default -> "";
                    });
        }
        return values;
    }

    private static byte[] writeCsv(List<String> columns, List<Map<String, String>> rows) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CSVFormat format = CSVFormat.DEFAULT
                .builder()
                .setHeader(columns.toArray(String[]::new))
                .build();
        try (CSVPrinter printer =
                new CSVPrinter(new java.io.OutputStreamWriter(buffer, StandardCharsets.UTF_8), format)) {
            for (Map<String, String> row : rows) {
                printer.printRecord(columns.stream().map(row::get).toList());
            }
        } catch (IOException writeFailure) {
            throw new UncheckedIOException(writeFailure);
        }
        return buffer.toByteArray();
    }

    private static String sha256Base64(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a required JDK algorithm", impossible);
        }
    }

    /**
     * @param viewerHoldsPiiCapability the polling principal's own {@code customer.pii.export}
     *     grant (not the exporter's, which is already baked into the row) — see {@link
     *     #toView} for why a second principal's grant has to be re-checked on every read
     */
    @Transactional(readOnly = true)
    public Optional<ExportStatusView> status(UUID tenantId, UUID id, boolean viewerHoldsPiiCapability) {
        return store.find(tenantId, id).map(row -> toView(row, viewerHoldsPiiCapability));
    }

    /** @param viewerHoldsPiiCapability see {@link #status}'s own doc */
    @Transactional(readOnly = true)
    public List<ExportStatusView> recentExports(UUID tenantId, int limit, boolean viewerHoldsPiiCapability) {
        return store.listRecent(tenantId, limit).stream()
                .map(row -> toView(row, viewerHoldsPiiCapability))
                .toList();
    }

    /**
     * A stored row's {@code includesPiiColumns}/{@code effectiveColumns} were decided once, at
     * queue time, from the <em>requester's</em> own {@code customer.pii.export} grant ({@link
     * #requestExport}'s own doc). Every {@code REPORT_EXPORT} holder in the tenant can poll or
     * list that same row (P28's export-centre history), so the PII group is re-redacted here,
     * per read, against the <em>viewer's</em> own grant: a row whose PII columns the viewer is
     * not entitled to see reports {@code includesPiiColumns = false}, strips the PII columns out
     * of {@code effectiveColumns}, and never presigns a download URL.
     */
    private ExportStatusView toView(ExportRow row, boolean viewerHoldsPiiCapability) {
        boolean redact = row.includesPiiColumns() && !viewerHoldsPiiCapability;

        List<String> storedColumns = row.effectiveColumns();
        List<String> effectiveColumns = storedColumns;
        if (redact) {
            Optional<ReportExportDefinition> definition = ReportExportRegistry.find(row.reportKey());
            effectiveColumns = definition
                    .map(def -> storedColumns.stream()
                            .filter(column -> !def.isPiiColumn(column))
                            .toList())
                    // Defensive: an unknown report key can't tell PII columns from ordinary
                    // ones, so redact every column rather than guess and risk a leak.
                    .orElse(List.of());
        }

        URI downloadUrl = null;
        if (!redact
                && "COMPLETE".equals(row.status())
                && row.artifactBucket() != null
                && row.artifactObjectKey() != null) {
            downloadUrl = storage.presignDownload(row.artifactBucket(), row.artifactObjectKey(), Duration.ofMinutes(5));
        }
        return new ExportStatusView(
                row.id(),
                row.reportKey(),
                row.status(),
                effectiveColumns,
                redact ? false : row.includesPiiColumns(),
                row.rowQuota(),
                row.rowCount(),
                row.truncated(),
                row.failureReason(),
                row.createdAt(),
                row.completedAt(),
                downloadUrl);
    }

    public record ExportStatusView(
            UUID id,
            String reportKey,
            String status,
            List<String> effectiveColumns,
            boolean includesPiiColumns,
            int rowQuota,
            @Nullable Integer rowCount,
            boolean truncated,
            @Nullable String failureReason,
            Instant createdAt,
            @Nullable Instant completedAt,
            @Nullable URI downloadUrl) {}
}
