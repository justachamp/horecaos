package uz.horecaos.platform.catalog.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.catalog.api.CatalogConfigurationKeys;
import uz.horecaos.platform.catalog.api.MxikReferenceApplied;
import uz.horecaos.platform.catalog.application.MxikMappingProfile.MxikImportFormatException;
import uz.horecaos.platform.catalog.application.MxikReferenceImporter.Normalised;
import uz.horecaos.platform.catalog.application.MxikReferenceImporter.RowError;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMxikImportStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMxikImportStore.Diff;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMxikImportStore.FindingRow;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMxikImportStore.RunRow;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.media.api.ObjectStorage;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The way the national ИКПУ/MXIK list gets into {@code catalog.mxik_reference} (ADR 0173): an audited,
 * two-step, idempotent import that a platform administrator runs from a file they downloaded from the tax
 * authority.
 *
 * <p><strong>A dry run first, always.</strong> {@link #validate} stores the raw file under its content hash,
 * parses and normalises it, writes the rows it would write to a staging table and reports counts and findings.
 * It changes nothing in the list. {@link #apply} is a second call that names the run; it writes the list in
 * one transaction (parents first), dates the withdrawal of any code a full snapshot no longer contains and
 * reinstates one that returned. Nothing is ever deleted.
 *
 * <p><strong>A safety valve.</strong> A full snapshot that would withdraw more than {@code
 * catalog.mxik_import_withdrawal_threshold_percent} of the valid rows is held for review and applies only when
 * the administrator repeats {@code apply} naming the exact withdrawal count they were shown, the
 * acknowledgement ADR 0141 uses for a decommission report. A truncated or wrong file otherwise empties the list
 * in one request.
 *
 * <p>The source is a person uploading a file. Nothing here scrapes a portal or calls an undocumented
 * interface; if the authority documents a feed, an adapter may fetch the same file and call {@link #validate}.
 */
@Service
public class MxikReferenceImportService {

    private static final Logger log = LoggerFactory.getLogger(MxikReferenceImportService.class);

    /** The findings cap per run (ADR 0173): a wrong file must not turn the findings table into a dump. */
    public static final int MAX_FINDINGS = 5_000;

    private final JdbcMxikImportStore store;
    private final MxikFileParser parser;
    private final MxikReferenceImporter importer;
    private final ObjectStorage objectStorage;
    private final ConfigurationResolver configuration;
    private final AuditRecorder audit;
    private final MeterRegistry metrics;
    private final ApplicationEventPublisher events;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate independently;
    private final TransactionTemplate atomically;
    private final String bucket;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public MxikReferenceImportService(
            JdbcMxikImportStore store,
            MxikFileParser parser,
            MxikReferenceImporter importer,
            ObjectStorage objectStorage,
            ConfigurationResolver configuration,
            AuditRecorder audit,
            MeterRegistry metrics,
            ApplicationEventPublisher events,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager,
            @Value("${horecaos.catalog.mxik.bucket:${horecaos.media.bucket:horecaos-media}}") String bucket) {
        this.store = store;
        this.parser = parser;
        this.importer = importer;
        this.objectStorage = objectStorage;
        this.configuration = configuration;
        this.audit = audit;
        this.metrics = metrics;
        this.events = events;
        this.objectMapper = objectMapper;
        this.clock = clock;
        TransactionTemplate isolated = new TransactionTemplate(transactionManager);
        // A failure is recorded on the run in its own transaction: the apply's rollback must not undo the note
        // that says it failed.
        isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.independently = isolated;
        this.atomically = new TransactionTemplate(transactionManager);
        this.bucket = bucket;
    }

    /** What a dry run found. */
    public record ValidationResult(RunRow run, List<RowError> sampleErrors) {}

    // ----------------------------------------------------------------------------- large files

    private static final String UPLOAD_PREFIX = "mxik-uploads/";

    /** Where to put a file the JSON body cannot carry: a short-lived signed URL and the key that names it. */
    public record UploadTicket(String objectKey, ObjectStorage.PresignedUpload upload, long maxBytes) {}

    /**
     * A signed upload for a file larger than the API's request-body cap (ADR 0173, "a file beyond that goes
     * through the object store and is named by its key"). The bytes never pass through the application; the
     * import that names the key reads them back once, bounded by {@code catalog.mxik_import_max_bytes}.
     */
    public UploadTicket requestUpload() {
        long limit = orDefault(
                configuration.value(CatalogConfigurationKeys.MXIK_IMPORT_MAX_BYTES, ResourceScope.platform()));
        String key = UPLOAD_PREFIX + uz.horecaos.platform.configuration.Ids.newId();
        ObjectStorage.PresignedUpload upload = objectStorage.presignUpload(
                bucket, key, "application/octet-stream", limit, java.time.Duration.ofMinutes(15));
        return new UploadTicket(key, upload, limit);
    }

    /** Reads an uploaded object and runs the same dry run as a file sent in the body. */
    @Transactional
    public ValidationResult validateUploaded(
            String fileName,
            String objectKey,
            String mode,
            @Nullable LocalDate sourcePublishedOn,
            String actorSubject) {
        long limit = orDefault(
                configuration.value(CatalogConfigurationKeys.MXIK_IMPORT_MAX_BYTES, ResourceScope.platform()));
        if (!objectKey.startsWith(UPLOAD_PREFIX) || objectKey.contains("..")) {
            throw refused("UPLOAD_KEY_INVALID", "objectKey is a key from an upload ticket");
        }
        ObjectStorage.StoredObject stored = objectStorage
                .head(bucket, objectKey)
                .orElseThrow(() -> refused("UPLOAD_NOT_FOUND", "Nothing was uploaded under that key"));
        if (stored.sizeBytes() > limit) {
            throw refused("FILE_TOO_LARGE", "The file is larger than the " + limit + " bytes this importer accepts");
        }
        byte[] content = objectStorage.readPrefix(bucket, objectKey, (int) stored.sizeBytes());
        if (content.length != stored.sizeBytes()) {
            throw refused("UPLOAD_UNREADABLE", "The uploaded file could not be read back whole");
        }
        ValidationResult result = validate(fileName, content, mode, sourcePublishedOn, actorSubject);
        try {
            // The raw file is kept under its content hash; the signed-upload staging copy has done its job.
            objectStorage.delete(bucket, objectKey);
        } catch (RuntimeException cleanup) {
            log.warn("The staging upload {} could not be removed", objectKey, cleanup);
        }
        return result;
    }

    // ------------------------------------------------------------------------------- the dry run

    /**
     * Stores the file, parses it, and records a run that has changed nothing in the list.
     *
     * @param mode            {@code FULL_SNAPSHOT} or {@code PARTIAL}; only a full snapshot can withdraw a code
     * @param sourcePublishedOn the date the authority published the file, if the operator knows it
     * @throws ApiException {@code UNPROCESSABLE_STATE} with {@code findingCode} when the file is not this
     *         profile's file, after recording a failed run so the evidence (the hash, the stored file) exists
     */
    @Transactional
    public ValidationResult validate(
            String fileName, byte[] content, String mode, @Nullable LocalDate sourcePublishedOn, String actorSubject) {
        long limit = orDefault(
                configuration.value(CatalogConfigurationKeys.MXIK_IMPORT_MAX_BYTES, ResourceScope.platform()));
        if (content.length == 0) {
            throw refused("FILE_EMPTY", "The uploaded file is empty");
        }
        if (content.length > limit) {
            throw refused("FILE_TOO_LARGE", "The file is larger than the " + limit + " bytes this importer accepts");
        }
        boolean full = "FULL_SNAPSHOT".equals(mode);
        if (!full && !"PARTIAL".equals(mode)) {
            throw refused("MODE_UNKNOWN", "mode is FULL_SNAPSHOT or PARTIAL");
        }
        String sha256 = sha256(content);
        UUID runId = uz.horecaos.platform.configuration.Ids.newId();
        MxikMappingProfile profile = MxikMappingProfile.forFile(fileName);
        int threshold = thresholdPercent();

        // The raw file is stored before it is parsed, so a parse failure still leaves the evidence.
        String objectKey = "mxik-imports/" + sha256 + (profile == MxikMappingProfile.TASNIF_XLSX_V1 ? ".xlsx" : ".csv");
        try {
            objectStorage.put(
                    bucket,
                    objectKey,
                    profile == MxikMappingProfile.TASNIF_XLSX_V1
                            ? "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                            : "text/csv",
                    content);
        } catch (RuntimeException unstored) {
            log.error("The ИКПУ/MXIK file {} could not be stored", sha256, unstored);
            throw refused(
                    "RAW_FILE_NOT_STORED", "The file could not be kept in object storage, so nothing was imported");
        }

        MxikFileParser.ParsedFile parsed;
        try {
            parsed = parser.parse(fileName, content);
        } catch (MxikImportFormatException unreadable) {
            // The run exists for the evidence; the file is wrong, not the administrator.
            // In a transaction of its own: the refusal below rolls this one back, and the note is the evidence.
            independently.executeWithoutResult(ignored -> {
                store.insertRun(
                        runId,
                        "FAILED",
                        mode,
                        profile.name(),
                        fileName,
                        sha256,
                        objectKey,
                        sourcePublishedOn,
                        threshold,
                        "{}",
                        actorSubject,
                        unreadable.code());
                record(
                        "import-failed",
                        runId,
                        null,
                        "FAILED",
                        sha256,
                        mode,
                        Map.of("reason", unreadable.code()),
                        actorSubject);
            });
            metrics.counter("horecaos.fiscal_reference.import", "outcome", "failed")
                    .increment();
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    unreadable.getMessage(),
                    Map.of("findingCode", unreadable.code(), "runId", runId.toString()));
        }

        Set<String> inFile = parsed.rows().stream()
                .map(row -> row.values().get(MxikMappingProfile.Column.CODE))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
        Set<String> parentsNeeded = parsed.rows().stream()
                .map(row -> row.values().get(MxikMappingProfile.Column.PARENT_CODE))
                .filter(java.util.Objects::nonNull)
                .filter(parent -> !inFile.contains(parent))
                .collect(Collectors.toSet());
        Set<String> existingParents = store.existingCodes(parentsNeeded);
        Normalised normalised = importer.normalise(parsed, existingParents::contains);

        store.insertRun(
                runId,
                "VALIDATED",
                mode,
                profile.name(),
                fileName,
                sha256,
                objectKey,
                sourcePublishedOn,
                threshold,
                histogramJson(normalised.codeLengths()),
                actorSubject,
                null);
        store.insertRows(runId, normalised.rows());
        Diff diff = store.diff(runId, full);

        List<FindingRow> findings = new ArrayList<>();
        for (RowError error : normalised.errors()) {
            findings.add(new FindingRow("ROW_ERROR", error.code(), error.detailCode() + "@" + error.row()));
        }
        int room = Math.max(0, MAX_FINDINGS - findings.size());
        if (full && room > 0) {
            store.codesToWithdraw(runId, room)
                    .forEach(code -> findings.add(new FindingRow("WITHDRAWN", code, "NOT_IN_FILE")));
        }
        room = Math.max(0, MAX_FINDINGS - findings.size());
        if (room > 0) {
            store.codesToReinstate(runId, room)
                    .forEach(code -> findings.add(new FindingRow("REINSTATED", code, "RETURNED")));
        }
        room = Math.max(0, MAX_FINDINGS - findings.size());
        if (room > 0) {
            store.codesWhosePackageDefaultsChange(runId, room)
                    .forEach(code -> findings.add(new FindingRow("PACKAGE_DEFAULTS_CHANGED", code, "DEFAULTS_DIFFER")));
        }
        store.insertFindings(runId, 1, findings.subList(0, Math.min(findings.size(), MAX_FINDINGS)));

        int validRows = store.validRows();
        boolean held = full && validRows > 0 && (long) diff.withdrawn() * 100 > (long) validRows * threshold;
        store.recordValidation(
                runId,
                held ? "HELD_FOR_REVIEW" : "VALIDATED",
                normalised.rows().size(),
                diff.created(),
                diff.changed(),
                diff.unchanged(),
                diff.withdrawn(),
                diff.reinstated(),
                normalised.errors().size(),
                normalised.labelsBorrowed());

        record(
                held ? "import-held" : "import-validated",
                runId,
                null,
                held ? "HELD_FOR_REVIEW" : "VALIDATED",
                sha256,
                mode,
                Map.of(
                        "rowsTotal", normalised.rows().size(),
                        "rowsNew", diff.created(),
                        "rowsChanged", diff.changed(),
                        "rowsWithdrawn", diff.withdrawn(),
                        "rowsReinstated", diff.reinstated(),
                        "rowsError", normalised.errors().size()),
                actorSubject);
        metrics.counter("horecaos.fiscal_reference.import", "outcome", held ? "held" : "validated")
                .increment();

        RunRow run = store.findRun(runId).orElseThrow();
        return new ValidationResult(run, normalised.errors().stream().limit(20).toList());
    }

    // --------------------------------------------------------------------------------- apply

    /**
     * Writes a validated run into the list.
     *
     * @param expectedVersion the run's version the caller read (ADR 0031)
     * @param acknowledgedWithdrawals the exact withdrawal count the administrator was shown; required, and must
     *        match, when the run is held for review
     */
    public RunRow apply(
            UUID runId, int expectedVersion, @Nullable Integer acknowledgedWithdrawals, String actorSubject) {
        try {
            return atomically.execute(
                    status -> applyInTransaction(runId, expectedVersion, acknowledgedWithdrawals, actorSubject));
        } catch (ApiException refusedOrStale) {
            // A request the administrator can correct: nothing was attempted, so there is nothing to record.
            throw refusedOrStale;
        } catch (RuntimeException failure) {
            // The apply's transaction has rolled back, so the list is as it was and the run's row is no longer
            // locked by it. The note goes in a transaction of its own: it must outlive the rollback, and writing
            // it from inside the rolled-back transaction would only roll back with it.
            independently.executeWithoutResult(ignored -> store.markFailed(runId, "APPLY_FAILED"));
            metrics.counter("horecaos.fiscal_reference.import", "outcome", "failed")
                    .increment();
            log.error("Applying ИКПУ/MXIK run {} failed; the list is unchanged", runId, failure);
            throw failure;
        }
    }

    private RunRow applyInTransaction(
            UUID runId, int expectedVersion, @Nullable Integer acknowledgedWithdrawals, String actorSubject) {
        RunRow run = store.findRunForUpdate(runId).orElseThrow(() -> notFound(runId));
        if (run.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, run.version());
        }
        if (!Set.of("VALIDATED", "HELD_FOR_REVIEW").contains(run.status())) {
            throw refused("RUN_NOT_APPLICABLE", "A run that is " + run.status() + " cannot be applied");
        }
        if ("HELD_FOR_REVIEW".equals(run.status())
                && (acknowledgedWithdrawals == null || acknowledgedWithdrawals != run.rowsWithdrawn())) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "This run would withdraw %d codes, more than %d%% of the valid list; repeat the request naming that exact count to apply it"
                            .formatted(run.rowsWithdrawn(), run.withdrawalThresholdPercent()),
                    Map.of("findingCode", "WITHDRAWAL_ACKNOWLEDGEMENT_REQUIRED", "withdrawals", run.rowsWithdrawn()));
        }
        Optional<JdbcMxikImportStore.StateRow> state = store.state();
        if (state.isPresent() && !state.get().importedAt().isBefore(run.createdAt())) {
            // The list moved after this run was looked at; the counts the administrator read are stale.
            throw refused("RUN_IS_STALE", "The list changed after this run was validated; upload the file again");
        }
        if (!store.transition(runId, List.of("VALIDATED", "HELD_FOR_REVIEW"), "APPLYING", expectedVersion)) {
            throw ApiException.staleVersion(expectedVersion, run.version());
        }

        int datasetVersion =
                state.map(JdbcMxikImportStore.StateRow::datasetVersion).orElse(0) + 1;
        boolean full = "FULL_SNAPSHOT".equals(run.mode());
        int rows = writeRows(runId, datasetVersion);
        Diff applied = new Diff(run.rowsNew(), run.rowsChanged(), run.rowsUnchanged(), 0, run.rowsReinstated());
        int withdrawn = full
                ? store.withdrawMissing(
                        runId,
                        run.sourcePublishedOn() != null
                                ? run.sourcePublishedOn()
                                : LocalDate.now(clock.withZone(ZoneOffset.UTC)),
                        datasetVersion)
                : 0;
        int valid = store.validRows();
        int withdrawnRows = store.withdrawnRows();
        store.writeState(datasetVersion, run.sourceSha256(), run.sourcePublishedOn(), valid, withdrawnRows, runId);
        store.markApplied(runId, actorSubject, datasetVersion, acknowledgedWithdrawals, new int[] {
            applied.created(), applied.changed(), applied.unchanged(), withdrawn, applied.reinstated()
        });
        store.clearRows(runId);
        record(
                "import-applied",
                runId,
                run.status(),
                "APPLIED",
                run.sourceSha256(),
                run.mode(),
                Map.of(
                        "datasetVersion", datasetVersion,
                        "rowsWritten", rows,
                        "rowsWithdrawn", withdrawn,
                        "validRows", valid),
                actorSubject);
        metrics.counter("horecaos.fiscal_reference.import", "outcome", "applied")
                .increment();
        // The alert module resolves "not loaded" and "stale" when it hears this (it owns the incidents; the catalog
        // may not depend on it, and a daily check there is what raises them).
        events.publishEvent(new MxikReferenceApplied(runId, datasetVersion, actorSubject));
        return store.findRun(runId).orElseThrow();
    }

    private int writeRows(UUID runId, int datasetVersion) {
        int written = 0;
        for (int depth : store.depths(runId)) {
            List<Integer> ordinals = store.ordinalsAtDepth(runId, depth);
            for (int from = 0; from < ordinals.size(); from += JdbcMxikImportStore.APPLY_BATCH) {
                int last = Math.min(ordinals.size(), from + JdbcMxikImportStore.APPLY_BATCH) - 1;
                store.upsertSlice(runId, depth, ordinals.get(from), ordinals.get(last), datasetVersion);
                written += last - from + 1;
            }
        }
        return written;
    }

    // ---------------------------------------------------------------------------------- reject

    @Transactional
    public RunRow reject(UUID runId, int expectedVersion, String actorSubject) {
        RunRow run = store.findRunForUpdate(runId).orElseThrow(() -> notFound(runId));
        if (run.version() != expectedVersion) {
            throw ApiException.staleVersion(expectedVersion, run.version());
        }
        if (!store.transition(runId, List.of("VALIDATED", "HELD_FOR_REVIEW"), "REJECTED", expectedVersion)) {
            throw refused("RUN_NOT_APPLICABLE", "A run that is " + run.status() + " cannot be rejected");
        }
        store.clearRows(runId);
        record(
                "import-rejected",
                runId,
                run.status(),
                "REJECTED",
                run.sourceSha256(),
                run.mode(),
                Map.of("rowsTotal", run.rowsTotal()),
                actorSubject);
        metrics.counter("horecaos.fiscal_reference.import", "outcome", "rejected")
                .increment();
        return store.findRun(runId).orElseThrow();
    }

    // ----------------------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public RunRow run(UUID runId) {
        return store.findRun(runId).orElseThrow(() -> notFound(runId));
    }

    @Transactional(readOnly = true)
    public List<RunRow> recentRuns(int limit) {
        return store.recentRuns(Math.clamp(limit, 1, 100));
    }

    @Transactional(readOnly = true)
    public List<JdbcMxikImportStore.StoredFinding> findings(UUID runId, int afterOrdinal, int limit) {
        run(runId);
        return store.findings(runId, afterOrdinal, Math.clamp(limit, 1, 500));
    }

    // ---------------------------------------------------------------------------- internals

    private int thresholdPercent() {
        Integer value = configuration.value(
                CatalogConfigurationKeys.MXIK_IMPORT_WITHDRAWAL_THRESHOLD_PERCENT, ResourceScope.platform());
        return value == null ? 2 : value;
    }

    private static long orDefault(@Nullable Long value) {
        return value == null ? 33_554_432L : value;
    }

    private String histogramJson(Map<Integer, Integer> lengths) {
        Map<String, Integer> asText = new java.util.LinkedHashMap<>();
        lengths.forEach((length, count) -> asText.put(String.valueOf(length), count));
        return objectMapper.writeValueAsString(asText);
    }

    /**
     * Audits one step of a run (ADR 0027): the status it left (none when the step creates the run) and the status
     * it reached, with the file's hash, the mode and the counts, and never a file body or row content.
     */
    private void record(
            String action,
            UUID runId,
            @Nullable String fromStatus,
            String toStatus,
            String sha256,
            String mode,
            Map<String, Object> counts,
            String actorSubject) {
        Map<String, Object> before = new java.util.LinkedHashMap<>();
        if (fromStatus != null) {
            before.put("status", fromStatus);
        }
        Map<String, Object> after = new java.util.LinkedHashMap<>();
        after.put("status", toStatus);
        after.put("sourceSha256", sha256);
        after.put("mode", mode);
        after.putAll(counts);
        Map<String, Object> document = ChangeDocuments.diff(before, after);
        audit.record(AuditFact.of("catalog.mxikReference." + action, AuditClass.BUSINESS)
                .by(ActorRef.user(actorSubject, null))
                .at(ResourceScope.platform())
                .target("MxikImportRun", runId)
                .because("ИКПУ/MXIK reference import")
                .usingCapability(Capability.PLATFORM_ADMIN.code())
                .changed(document)
                .correlatedBy(runId.toString())
                .occurredAt(clock.instant())
                .build());
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    private static ApiException notFound(UUID runId) {
        return new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No ИКПУ/MXIK import run " + runId);
    }

    private static ApiException refused(String findingCode, String detail) {
        return new ApiException(ErrorCode.UNPROCESSABLE_STATE, detail, Map.of("findingCode", findingCode));
    }
}
