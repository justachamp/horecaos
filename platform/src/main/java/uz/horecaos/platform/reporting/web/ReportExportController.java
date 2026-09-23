package uz.horecaos.platform.reporting.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.reporting.application.ReportExportService;
import uz.horecaos.platform.reporting.application.ReportExportService.ExportStatusView;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The export centre (ADR 0043's export line, ADR 0029's egress checklist; wave P28): {@code POST
 * /exports} queues a job under {@link Capability#REPORT_EXPORT}, {@code GET /reports/{id}} polls
 * it, and {@code GET /exports} is the job history the export centre screen renders.
 *
 * <p>The PII column group is never a 403. A caller who lacks {@link Capability#CUSTOMER_PII_EXPORT}
 * still queues successfully; the columns that group would have carried are silently absent from
 * {@code effectiveColumns} and the artefact it produces — see {@link
 * ReportExportService#requestExport}'s own doc for where that decision is made. The same grant is
 * re-checked, per viewer, on every poll and every history read: {@code GET .../reports/{id}} and
 * {@code GET .../exports} redact another principal's PII columns and download URL rather than
 * ever leaking them to a {@code REPORT_EXPORT}-only holder — see {@link
 * ReportExportService#status} and {@link ReportExportService#recentExports}.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/reporting")
@Tag(name = "Report exports", description = "ADR 0043/ADR 0029: the audited export centre job queue")
public class ReportExportController {

    private final ReportExportService exports;
    private final CurrentActor currentActor;
    private final AuthorizationService authorization;

    public ReportExportController(
            ReportExportService exports, CurrentActor currentActor, AuthorizationService authorization) {
        this.exports = exports;
        this.currentActor = currentActor;
        this.authorization = authorization;
    }

    @PostMapping("/exports")
    @RequiresCapability(value = Capability.REPORT_EXPORT, mutating = true)
    @Operation(
            summary = "Queue a report export",
            description = "Excel/CSV as an audited PII egress (ADR 0043/ADR 0029). The PII column "
                    + "group (customer.pii.export) is included only when the requesting principal "
                    + "holds it -- otherwise it is silently omitted from the artefact rather than "
                    + "refusing the whole request. Processed asynchronously; poll GET "
                    + ".../reporting/reports/{id}.")
    public ResponseEntity<ReportExportQueuedResponse> requestExport(
            @PathVariable UUID tenantId, @Valid @RequestBody ReportExportRequest request) {

        String subject = currentActor.get().subject();
        boolean holdsPiiCapability =
                authorization.has(subject, Capability.CUSTOMER_PII_EXPORT, ResourceScope.tenant(tenantId));

        UUID exportId = exports.requestExport(
                tenantId,
                request.reportKey(),
                request.columns(),
                request.status(),
                request.query(),
                request.from(),
                request.to(),
                request.locationId() == null ? List.of() : request.locationId(),
                request.purpose(),
                subject,
                holdsPiiCapability);

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(new ReportExportQueuedResponse(exportId, "QUEUED"));
    }

    @GetMapping("/reports/{id}")
    @RequiresCapability(Capability.REPORT_EXPORT)
    @Operation(
            summary = "One report export's status",
            description = "What the export centre screen polls: QUEUED/RUNNING/COMPLETE/FAILED, "
                    + "the effective columns actually produced, whether the row quota truncated "
                    + "the result, and a short-lived download URL once COMPLETE. A viewer who "
                    + "lacks customer.pii.export never sees another principal's PII columns or "
                    + "download URL here, even for their own completed export.")
    public ResponseEntity<ReportExportStatusResponse> reportExportStatus(
            @PathVariable UUID tenantId, @PathVariable UUID id) {
        return exports.status(tenantId, id, currentActorHoldsPiiCapability(tenantId))
                .map(view -> ResponseEntity.ok(ReportExportStatusResponse.of(view)))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such report export"));
    }

    @GetMapping("/exports")
    @RequiresCapability(Capability.REPORT_EXPORT)
    @Operation(
            summary = "Recent report exports",
            description = "The export centre screen's own job history, newest first. A viewer "
                    + "who lacks customer.pii.export never sees another principal's PII columns "
                    + "or download URL in this list.")
    public ResponseEntity<List<ReportExportStatusResponse>> recentExports(
            @PathVariable UUID tenantId, @RequestParam(defaultValue = "50") int limit) {
        int bounded = Math.clamp(limit, 1, 200);
        boolean holdsPiiCapability = currentActorHoldsPiiCapability(tenantId);
        return ResponseEntity.ok(exports.recentExports(tenantId, bounded, holdsPiiCapability).stream()
                .map(ReportExportStatusResponse::of)
                .toList());
    }

    /**
     * The polling/listing principal's own {@code customer.pii.export} grant — re-checked on
     * every read because it is never the same principal as the one who queued the export (the
     * export centre's job history is tenant-wide, not per-requester).
     */
    private boolean currentActorHoldsPiiCapability(UUID tenantId) {
        return authorization.has(
                currentActor.get().subject(), Capability.CUSTOMER_PII_EXPORT, ResourceScope.tenant(tenantId));
    }

    /**
     * @param from        {@code ORDER_CRM_LOG}'s own required range start; ignored by every
     *                    other report
     * @param to          {@code ORDER_CRM_LOG}'s own required range end
     * @param locationId  {@code ORDER_CRM_LOG}'s own optional branch filter; absent or empty
     *                    means every branch the caller's tenant-wide grant already covers
     */
    public record ReportExportRequest(
            @NotBlank String reportKey,
            @NotEmpty List<@NotBlank String> columns,
            @Nullable String status,
            @Nullable String query,
            @Nullable Instant from,
            @Nullable Instant to,
            @Nullable List<UUID> locationId,
            @NotBlank String purpose) {}

    public record ReportExportQueuedResponse(UUID exportId, String status) {}

    public record ReportExportStatusResponse(
            UUID exportId,
            String reportKey,
            String status,
            List<String> columns,
            boolean includesPiiColumns,
            int rowQuota,
            @Nullable Integer rowCount,
            boolean truncated,
            @Nullable String failureReason,
            Instant createdAt,
            @Nullable Instant completedAt,
            @Nullable URI downloadUrl) {
        static ReportExportStatusResponse of(ExportStatusView view) {
            return new ReportExportStatusResponse(
                    view.id(),
                    view.reportKey(),
                    view.status(),
                    view.effectiveColumns(),
                    view.includesPiiColumns(),
                    view.rowQuota(),
                    view.rowCount(),
                    view.truncated(),
                    view.failureReason(),
                    view.createdAt(),
                    view.completedAt(),
                    view.downloadUrl());
        }
    }
}
