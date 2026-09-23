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
import java.util.Objects;
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
import uz.horecaos.platform.ordering.api.OrderCrmLogExportPort;
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
 * <p>{@link ReportExportRegistry#CUSTOMER_DIRECTORY} and {@link ReportExportRegistry#ORDER_CRM_LOG}
 * are wired — see that class's own doc for why the next report is one more {@code case} in
 * {@link #run} rather than a pluggable abstraction.
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
    private final OrderCrmLogExportPort orderCrmLog;
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
            OrderCrmLogExportPort orderCrmLog,
            FieldProtection protection,
            AuditRecorder audit,
            ObjectStorage storage,
            TransactionTemplate transactions,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${horecaos.reporting.exports.bucket:${horecaos.media.bucket:horecaos-media}}") String bucket) {
        this.store = store;
        this.customerDirectory = customerDirectory;
        this.orderCrmLog = orderCrmLog;
        this.protection = protection;
        this.audit = audit;
        this.storage = storage;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.bucket = bucket;
    }

    /**
     * Queues a {@link ReportExportRegistry#CUSTOMER_DIRECTORY} export — see the full overload for
     * the shared doc. Kept as its own method rather than defaulting {@code from}/{@code to}/{@code
     * locationIds} at every call site.
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
        return requestExport(
                tenantId,
                reportKey,
                requestedColumns,
                status,
                query,
                null,
                null,
                List.of(),
                purpose,
                requestedBySubject,
                holdsPiiCapability);
    }

    /**
     * Queues an export. The PII omission is decided here, once, from {@code holdsPiiCapability} —
     * the caller's own {@code customer.pii.export} check — and stored as {@code
     * effective_columns}/{@code includes_pii_columns} so nothing later has to re-ask.
     *
     * @param from        {@link ReportExportRegistry#ORDER_CRM_LOG}'s own required range start;
     *                    ignored by every other report
     * @param to          {@link ReportExportRegistry#ORDER_CRM_LOG}'s own required range end
     * @param locationIds {@link ReportExportRegistry#ORDER_CRM_LOG}'s own optional branch filter;
     *                    empty means every branch the caller's tenant-wide grant already covers
     * @throws ApiException {@code VALIDATION_FAILED} for an unknown report key or column, or for
     *                       {@code ORDER_CRM_LOG} with no range
     */
    @Transactional
    public UUID requestExport(
            UUID tenantId,
            String reportKey,
            List<String> requestedColumns,
            @Nullable String status,
            @Nullable String query,
            @Nullable Instant from,
            @Nullable Instant to,
            List<UUID> locationIds,
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
        if (ReportExportRegistry.ORDER_CRM_LOG.equals(reportKey) && (from == null || to == null)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "ORDER_CRM_LOG requires both from and to");
        }

        List<String> effectiveColumns = definition.effectiveColumns(requestedColumns, holdsPiiCapability);
        boolean includesPii = effectiveColumns.stream().anyMatch(definition::isPiiColumn);
        int rowQuota = rowQuotaFor(reportKey, includesPii);

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
        if (from != null) {
            filters.put("from", from.toString());
        }
        if (to != null) {
            filters.put("to", to.toString());
        }
        if (!locationIds.isEmpty()) {
            filters.put("locationIds", locationIds.stream().map(UUID::toString).toList());
        }

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

    private static int rowQuotaFor(String reportKey, boolean includesPii) {
        if (!includesPii) {
            return DEFAULT_ROW_QUOTA;
        }
        return ReportExportRegistry.ORDER_CRM_LOG.equals(reportKey)
                ? OrderCrmLogExportPort.PII_ROW_LIMIT
                : CustomerDirectoryExportPort.PII_ROW_LIMIT;
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
        ExportOutcome outcome =
                switch (job.reportKey()) {
                    case ReportExportRegistry.CUSTOMER_DIRECTORY -> runCustomerDirectory(job);
                    case ReportExportRegistry.ORDER_CRM_LOG -> runOrderCrmLog(job);
                    default ->
                        throw new IllegalStateException(
                                "No export source registered for report key " + job.reportKey());
                };

        byte[] csv = writeCsv(job.effectiveColumns(), outcome.rows());
        String objectKey = "tenants/%s/report-exports/%s.csv".formatted(job.tenantId(), job.id());
        storage.put(bucket, objectKey, "text/csv", csv);
        String checksum = sha256Base64(csv);

        Instant now = clock.instant();
        int rowCount = outcome.rows().size();
        boolean truncated = outcome.truncated();
        transactions.executeWithoutResult(status2 -> {
            store.completeExport(
                    job.id(), bucket, objectKey, "text/csv", csv.length, checksum, rowCount, truncated, now);
            audit.record(AuditFact.of("report.export.completed", AuditClass.SECURITY)
                    .by(ActorRef.user(job.requestedBySubject(), null))
                    .at(ResourceScope.tenant(job.tenantId()))
                    .target("report_export", job.id())
                    .because(job.purpose())
                    .changed(Map.of(
                            "reportKey",
                            job.reportKey(),
                            "rowCount",
                            rowCount,
                            "truncated",
                            truncated,
                            "piiColumnGroup",
                            job.includesPiiColumns() ? "INCLUDED" : "EXCLUDED",
                            "filters",
                            job.filters()))
                    .correlatedBy(job.id().toString())
                    .occurredAt(now)
                    .build());
        });
        log.info("Report export {} completed: {} row(s), truncated={}", job.id(), rowCount, truncated);
    }

    private ExportOutcome runCustomerDirectory(ClaimedExport job) {
        String query = job.encryptedQuery() == null
                ? null
                : protection.reveal(
                        job.tenantId(),
                        ProtectedValue.deserialize(job.encryptedQuery()),
                        new RecordRef(QUERY_TABLE, QUERY_COLUMN, job.id()),
                        REVEAL_PURPOSE);
        String status = (String) job.filters().get("status");

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
        return new ExportOutcome(rows, bundle.truncated());
    }

    /**
     * Wave 9 w4-reports-distance-crm (7.2a): the console order log's own audited PII egress —
     * {@link OrderCrmLogExportPort}, never {@code reporting.fact_order}. {@code from}/{@code to}
     * are required at queue time ({@link #requestExport}'s own guard), so an absent value here
     * means a row this class itself never wrote, not a caller's mistake.
     */
    private ExportOutcome runOrderCrmLog(ClaimedExport job) {
        Instant from = Instant.parse(
                (String) Objects.requireNonNull(job.filters().get("from"), "ORDER_CRM_LOG queued with no from"));
        Instant to = Instant.parse(
                (String) Objects.requireNonNull(job.filters().get("to"), "ORDER_CRM_LOG queued with no to"));
        @SuppressWarnings("unchecked")
        List<String> storedLocationIds = (List<String>) job.filters().getOrDefault("locationIds", List.of());
        List<UUID> locationIds =
                storedLocationIds.stream().map(UUID::fromString).toList();

        OrderCrmLogExportPort.ExportBundle bundle =
                orderCrmLog.export(job.tenantId(), from, to, locationIds, job.includesPiiColumns(), job.rowQuota());

        List<Map<String, String>> rows = bundle.rows().stream()
                .map(row -> crmLogRowAsColumns(row, job.effectiveColumns()))
                .toList();
        return new ExportOutcome(rows, bundle.truncated());
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

    private static Map<String, String> crmLogRowAsColumns(
            OrderCrmLogExportPort.ExportedRow row, List<String> effectiveColumns) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String column : effectiveColumns) {
            values.put(
                    column,
                    switch (column) {
                        case "orderId" -> row.orderId().toString();
                        case "occurredAt" -> row.occurredAt().toString();
                        case "locationId" -> row.locationId().toString();
                        case "customerType" -> row.customerType();
                        case "customerName" -> row.customerName() == null ? "" : row.customerName();
                        case "customerPhone" -> row.customerPhone() == null ? "" : row.customerPhone();
                        case "operatorPrincipalId" -> row.operatorPrincipalId();
                        case "courierDisplayReference" ->
                            row.courierDisplayReference() == null ? "" : row.courierDisplayReference();
                        default -> "";
                    });
        }
        return values;
    }

    /** One report's produced rows, in wire form, and whether the row quota cut it short. */
    private record ExportOutcome(List<Map<String, String>> rows, boolean truncated) {}

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
