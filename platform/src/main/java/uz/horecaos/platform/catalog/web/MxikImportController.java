package uz.horecaos.platform.catalog.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.catalog.application.MxikReferenceImportService;
import uz.horecaos.platform.catalog.application.MxikReferenceImportService.UploadTicket;
import uz.horecaos.platform.catalog.application.MxikReferenceImportService.ValidationResult;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMxikImportStore.RunRow;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMxikImportStore.StoredFinding;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Loading the national ИКПУ/MXIK list (ADR 0173, gap-map row 4.2e): a platform administrator uploads the file
 * they downloaded from the tax authority, reads what it would do, and applies it as a separate, explicit act.
 *
 * <p>Platform-scoped: the list is a national fact, not a tenant's, so no path here carries a tenant and every
 * write needs {@code platform.admin} at platform scope (the capability every platform-wide write here already
 * uses; ADR 0173 introduces none). An upload is <strong>always a dry run</strong>: it records a run and changes
 * nothing in the list. {@code apply} names the run, carries its version in {@code If-Match} (ADR 0031) and, for
 * a run held because it would withdraw more than the configured share of the list, the exact withdrawal count
 * the administrator was shown.
 */
@RestController
@RequestMapping("/api/v1/control-plane/fiscal-reference/mxik/imports")
@Tag(name = "Fiscal reference", description = "The ИКПУ/MXIK product classification reference")
public class MxikImportController {

    private final MxikReferenceImportService imports;
    private final CurrentActor currentActor;
    private final ObjectMapper objectMapper;

    public MxikImportController(
            MxikReferenceImportService imports, CurrentActor currentActor, ObjectMapper objectMapper) {
        this.imports = imports;
        this.currentActor = currentActor;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/uploads")
    @RequiresCapability(value = Capability.PLATFORM_ADMIN, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "A signed upload for a file too large for the request body",
            description = "The API refuses a request body over a mebibyte, so a full national list goes to the "
                    + "object store directly: PUT the file to the returned URL with the required headers, then "
                    + "post the import naming the returned objectKey. The key is single-purpose and the URL "
                    + "expires; the file is read back once, bounded by catalog.mxik_import_max_bytes.")
    public MxikUploadTicketResponse requestUpload() {
        UploadTicket ticket = imports.requestUpload();
        return new MxikUploadTicketResponse(
                ticket.objectKey(),
                ticket.upload().url().toString(),
                ticket.upload().requiredHeaders(),
                ticket.upload().expiresAt(),
                ticket.maxBytes());
    }

    @PostMapping
    @RequiresCapability(value = Capability.PLATFORM_ADMIN, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Upload the official file: always a dry run",
            description = "Supply the file as contentBase64 (a small file, within the request-body cap) or as "
                    + "objectKey from an upload ticket. Format follows the file's extension (.xlsx or .csv), "
                    + "never a byte sniff. The raw file is kept under its content hash, then parsed; the run "
                    + "records counts, a row-length distribution and findings, and the list is untouched. "
                    + "mode FULL_SNAPSHOT may withdraw codes the file no longer contains; PARTIAL only inserts "
                    + "and updates. A full snapshot that would withdraw more than "
                    + "catalog.mxik_import_withdrawal_threshold_percent of the valid rows answers "
                    + "HELD_FOR_REVIEW. 422 with findingCode UNKNOWN_HEADER, DUPLICATE_HEADER, MISSING_COLUMN, "
                    + "FILE_EMPTY, FILE_TOO_LARGE or RAW_FILE_NOT_STORED when the file cannot be used.")
    public ResponseEntity<MxikImportRunResponse> upload(@Valid @RequestBody MxikImportRequest request) {
        String inline = request.contentBase64();
        String objectKey = request.objectKey();
        if ((inline == null) == (objectKey == null)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Supply exactly one of contentBase64 and objectKey");
        }
        ValidationResult result;
        if (inline != null) {
            byte[] content;
            try {
                content = Base64.getDecoder().decode(inline);
            } catch (IllegalArgumentException notBase64) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "contentBase64 is not valid Base64");
            }
            result = imports.validate(
                    request.fileName(), content, request.mode(), request.sourcePublishedOn(), subject());
        } else {
            result = imports.validateUploaded(
                    request.fileName(), objectKey, request.mode(), request.sourcePublishedOn(), subject());
        }
        return respond(
                result.run(),
                result.sampleErrors().stream()
                        .map(error -> new MxikImportSampleError(error.row(), error.code(), error.detailCode()))
                        .toList());
    }

    @GetMapping
    @RequiresCapability(value = Capability.PLATFORM_ADMIN, scope = ScopeType.PLATFORM)
    @Operation(summary = "The most recent import runs")
    public List<MxikImportRunResponse> recent(@RequestParam(required = false) @Nullable Integer limit) {
        return imports.recentRuns(limit == null ? 20 : limit).stream()
                .map(run -> MxikImportRunResponse.of(run, objectMapper, List.of()))
                .toList();
    }

    @GetMapping("/{runId}")
    @RequiresCapability(value = Capability.PLATFORM_ADMIN, scope = ScopeType.PLATFORM)
    @Operation(summary = "One import run: its counts, its distribution of code lengths and where it stands")
    public ResponseEntity<MxikImportRunResponse> read(@PathVariable UUID runId) {
        return respond(imports.run(runId), List.of());
    }

    @GetMapping("/{runId}/findings")
    @RequiresCapability(value = Capability.PLATFORM_ADMIN, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "What the run found, in order",
            description = "Row errors, the codes a full snapshot would withdraw, the codes it would reinstate "
                    + "and the codes whose package defaults change. Capped at 5,000 per run. Cursor-paged: pass "
                    + "the previous page's nextCursor as after. The detail never repeats a row's content.")
    public Page<MxikImportFindingResponse> findings(
            @PathVariable UUID runId,
            @RequestParam(required = false) @Nullable String after,
            @RequestParam(required = false) @Nullable Integer limit) {
        int pageSize = Page.limitOrDefault(limit);
        int afterOrdinal = 0;
        if (after != null) {
            try {
                afterOrdinal = Integer.parseInt(after);
            } catch (NumberFormatException malformed) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "after is a cursor from a previous page");
            }
        }
        List<StoredFinding> found = imports.findings(runId, afterOrdinal, pageSize);
        String next = found.size() == pageSize
                ? String.valueOf(found.get(found.size() - 1).ordinal())
                : null;
        return new Page<>(
                found.stream()
                        .map(finding ->
                                new MxikImportFindingResponse(finding.kind(), finding.code(), finding.detailCode()))
                        .toList(),
                next);
    }

    @PostMapping("/{runId}/apply")
    @RequiresCapability(value = Capability.PLATFORM_ADMIN, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Apply a validated run to the list",
            description = "Requires If-Match carrying the run's version. Writes the list parents-first in one "
                    + "transaction, dates the withdrawal of any code a full snapshot no longer contains and "
                    + "reinstates one that returned; nothing is ever deleted. A run that is HELD_FOR_REVIEW "
                    + "applies only when acknowledgedWithdrawals repeats the exact count it would withdraw "
                    + "(422 WITHDRAWAL_ACKNOWLEDGEMENT_REQUIRED otherwise). 422 RUN_IS_STALE when the list "
                    + "changed after the run was validated; upload the file again.")
    public ResponseEntity<MxikImportRunResponse> apply(
            @PathVariable UUID runId,
            @Valid @RequestBody(required = false) @Nullable MxikImportApplyRequest request,
            HttpServletRequest httpRequest) {
        int expected = Math.toIntExact(AggregateVersion.requireIfMatch(httpRequest));
        Integer acknowledged = request == null ? null : request.acknowledgedWithdrawals();
        return respond(imports.apply(runId, expected, acknowledged, subject()), List.of());
    }

    @PostMapping("/{runId}/reject")
    @RequiresCapability(value = Capability.PLATFORM_ADMIN, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(
            summary = "Reject a validated run",
            description = "Requires If-Match. The run is kept as a record; its staged rows are cleared and the "
                    + "list is untouched.")
    public ResponseEntity<MxikImportRunResponse> reject(@PathVariable UUID runId, HttpServletRequest httpRequest) {
        int expected = Math.toIntExact(AggregateVersion.requireIfMatch(httpRequest));
        return respond(imports.reject(runId, expected, subject()), List.of());
    }

    private String subject() {
        return currentActor.get().subject();
    }

    private ResponseEntity<MxikImportRunResponse> respond(RunRow run, List<MxikImportSampleError> sample) {
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(run.version()))
                .body(MxikImportRunResponse.of(run, objectMapper, sample));
    }

    // -------------------------------------------------------------------------- shapes

    /**
     * @param contentBase64   the file, Base64, for a file within the request-body cap
     * @param objectKey       the key from an upload ticket, for a larger file
     * @param mode            FULL_SNAPSHOT or PARTIAL
     * @param sourcePublishedOn the date the authority published the file, if the administrator knows it; the
     *                        date a full snapshot dates its withdrawals with
     */
    public record MxikImportRequest(
            @NotBlank @Size(max = 255) String fileName,
            @Nullable String contentBase64,
            @Nullable @Size(max = 255) String objectKey,
            @NotNull String mode,
            @Nullable LocalDate sourcePublishedOn) {}

    /** @param acknowledgedWithdrawals the exact count a held run would withdraw, to release it */
    public record MxikImportApplyRequest(@Nullable Integer acknowledgedWithdrawals) {}

    public record MxikUploadTicketResponse(
            String objectKey, String url, Map<String, String> requiredHeaders, Instant expiresAt, long maxBytes) {}

    public record MxikImportSampleError(int row, @Nullable String code, String detailCode) {}

    public record MxikImportFindingResponse(
            String kind, @Nullable String code, String detailCode) {}

    /** A run, without the file or any row of it. */
    public record MxikImportRunResponse(
            UUID runId,
            String status,
            String mode,
            String mappingProfile,
            String sourceFileName,
            String sourceSha256,
            @Nullable LocalDate sourcePublishedOn,
            int rowsTotal,
            int rowsNew,
            int rowsChanged,
            int rowsUnchanged,
            int rowsWithdrawn,
            int rowsReinstated,
            int rowsError,
            int labelsBorrowed,
            Map<String, Integer> codeLengthHistogram,
            int withdrawalThresholdPercent,
            @Nullable Integer acknowledgedWithdrawals,
            String createdBy,
            Instant createdAt,
            @Nullable String appliedBy,
            @Nullable Instant appliedAt,
            @Nullable Integer datasetVersion,
            @Nullable String failureReason,
            int version,
            List<MxikImportSampleError> sampleErrors) {

        static MxikImportRunResponse of(RunRow run, ObjectMapper objectMapper, List<MxikImportSampleError> sample) {
            Map<String, Integer> histogram = new LinkedHashMap<>();
            objectMapper
                    .readTree(run.codeLengthHistogram())
                    .properties()
                    .forEach(entry ->
                            histogram.put(entry.getKey(), entry.getValue().asInt()));
            return new MxikImportRunResponse(
                    run.id(),
                    run.status(),
                    run.mode(),
                    run.mappingProfile(),
                    run.sourceFileName(),
                    run.sourceSha256(),
                    run.sourcePublishedOn(),
                    run.rowsTotal(),
                    run.rowsNew(),
                    run.rowsChanged(),
                    run.rowsUnchanged(),
                    run.rowsWithdrawn(),
                    run.rowsReinstated(),
                    run.rowsError(),
                    run.labelsBorrowed(),
                    histogram,
                    run.withdrawalThresholdPercent(),
                    run.acknowledgedWithdrawals(),
                    run.createdBy(),
                    run.createdAt(),
                    run.appliedBy(),
                    run.appliedAt(),
                    run.datasetVersion(),
                    run.failureReason(),
                    run.version(),
                    sample);
        }
    }
}
