package uz.horecaos.platform.printing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.iam.api.devices.DeviceEnrolmentPort.DevicePrincipalView;
import uz.horecaos.platform.printing.application.PosExportHint;
import uz.horecaos.platform.printing.application.PrintAgentService;
import uz.horecaos.platform.printing.application.PrintAgentService.AgentView;
import uz.horecaos.platform.printing.application.PrintJobQueue;
import uz.horecaos.platform.printing.application.PrintOnDemandService;
import uz.horecaos.platform.printing.application.PrintOnDemandService.PreviewRequest;
import uz.horecaos.platform.printing.application.PrinterService;
import uz.horecaos.platform.printing.application.PrinterService.PrinterDraft;
import uz.horecaos.platform.printing.application.RouteService;
import uz.horecaos.platform.printing.application.RouteService.RouteDraft;
import uz.horecaos.platform.printing.domain.PrintDocumentType;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.AdvertRow;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.JobRow;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.PrinterRow;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore.RouteRow;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.Cursor;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Printing and receipts for a branch, as the operations console uses them (ADR 0154, "Console endpoints"):
 * printers and their routes, the print queue, a test page and a reprint, the preview of what a job would put
 * on paper, and the branch's print agents.
 *
 * <p>Reads need {@code print.read}; changing a printer, a route or an agent needs {@code print.manage}; asking
 * for paper on demand needs {@code print.reprint}, which a cashier holds. Every write is an aggregate under
 * {@code If-Match} and an effect under {@code Idempotency-Key} (ADR 0031). With {@code feature.printing} off
 * for the tenant the lists answer what exists and every write is refused with {@code PRINTING_OFF}.
 *
 * <p>What a print agent itself calls -- claim, artifact, result -- is {@link PrintAgentController}, under its
 * own two capabilities, so a console role can never be made to claim a job and an agent can never read a
 * printer.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}")
@Tag(name = "Printing", description = "ADR 0154: printers, routes, the print queue, reprints and print agents")
public class PrintConsoleController {

    private final PrinterService printers;
    private final RouteService routes;
    private final PrintJobQueue queue;
    private final PrintOnDemandService onDemand;
    private final PrintAgentService agents;
    private final PosExportHint posExport;
    private final CurrentActor currentActor;

    public PrintConsoleController(
            PrinterService printers,
            RouteService routes,
            PrintJobQueue queue,
            PrintOnDemandService onDemand,
            PrintAgentService agents,
            PosExportHint posExport,
            CurrentActor currentActor) {
        this.printers = printers;
        this.routes = routes;
        this.queue = queue;
        this.onDemand = onDemand;
        this.agents = agents;
        this.posExport = posExport;
        this.currentActor = currentActor;
    }

    // ================================================================ printers

    @GetMapping("/print/printers")
    @RequiresCapability(value = Capability.PRINT_READ, scope = ScopeType.LOCATION)
    @Operation(
            operationId = "printListPrinters",
            summary = "The branch's printers",
            description = "Active and retired, with the roll, how each is reached and what its agent last "
                    + "reported. Never an address: the platform cannot make a branch machine dial one.")
    public ResponseEntity<List<PrintPrinterResponse>> listPrinters(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return ResponseEntity.ok(printers.list(tenantId, locationId).stream()
                .map(PrintPrinterResponse::of)
                .toList());
    }

    @PostMapping("/print/printers")
    @RequiresCapability(value = Capability.PRINT_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            operationId = "printRegisterPrinter",
            summary = "Register a printer",
            description = "An agent printer names the agent (agentDeviceId) and the alias that agent "
                    + "advertises (agentPrinterKey); a browser printer names neither. A roll is 58 or 80 mm. "
                    + "Refused 422 PRINTING_OFF while feature.printing is off for the tenant.")
    public ResponseEntity<PrintPrinterResponse> registerPrinter(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody PrintPrinterRequest body) {
        PrinterRow created = printers.register(tenantId, brandId, locationId, body.toDraft(), actor());
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(AggregateVersion.toETag(created.version()))
                .body(PrintPrinterResponse.of(created));
    }

    @PutMapping("/print/printers/{printerId}")
    @RequiresCapability(value = Capability.PRINT_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            operationId = "printUpdatePrinter",
            summary = "Change or retire a printer",
            description = "The version the form was opened at is If-Match; a second manager's save in "
                    + "between is STALE_VERSION. status RETIRED retires the printer: its routes stop "
                    + "printing and its jobs stay.")
    public ResponseEntity<PrintPrinterResponse> updatePrinter(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID printerId,
            @Valid @RequestBody PrintPrinterRequest body,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        PrinterRow updated = printers.update(
                tenantId,
                brandId,
                locationId,
                printerId,
                body.toDraft(),
                body.status() == null ? "ACTIVE" : body.status(),
                expected,
                actor());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(updated.version()))
                .body(PrintPrinterResponse.of(updated));
    }

    @PostMapping("/print/printers/{printerId}/test-page")
    @RequiresCapability(value = Capability.PRINT_REPRINT, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            operationId = "printTestPage",
            summary = "Print a test page",
            description = "The roll's width, a ruler, every glyph the three languages use, a QR and the "
                    + "printer's name. A browser printer's job is recorded HANDED_OFF and its bitmap is the "
                    + "preview; an agent printer's is queued like any other.")
    public ResponseEntity<PrintJobResponse> testPage(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID printerId) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PrintJobResponse.of(onDemand.testPage(tenantId, brandId, locationId, printerId, actor())));
    }

    // ================================================================== routes

    @GetMapping("/print/routes")
    @RequiresCapability(value = Capability.PRINT_READ, scope = ScopeType.LOCATION)
    @Operation(
            operationId = "printListRoutes",
            summary = "What prints where at the branch",
            description = "(Document type, optional station) to printer, with copies and auto_print. A "
                    + "branch with no routes prints nothing.")
    public ResponseEntity<List<PrintRouteResponse>> listRoutes(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return ResponseEntity.ok(routes.list(tenantId, locationId).stream()
                .map(PrintRouteResponse::of)
                .toList());
    }

    @PostMapping("/print/routes")
    @RequiresCapability(value = Capability.PRINT_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            operationId = "printCreateRoute",
            summary = "Create a route",
            description = "A station applies to a KITCHEN_TICKET only; a ticket for a station with no route "
                    + "of its own goes to the route with no station. autoPrint needs an agent printer.")
    public ResponseEntity<PrintRouteResponse> createRoute(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody PrintRouteRequest body) {
        RouteRow created = routes.create(tenantId, brandId, locationId, body.toDraft(), actor());
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(AggregateVersion.toETag(created.version()))
                .body(PrintRouteResponse.of(created));
    }

    @PutMapping("/print/routes/{routeId}")
    @RequiresCapability(value = Capability.PRINT_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            operationId = "printUpdateRoute",
            summary = "Change a route",
            description = "Under If-Match. A route's document type does not change.")
    public ResponseEntity<PrintRouteResponse> updateRoute(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID routeId,
            @Valid @RequestBody PrintRouteRequest body,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        RouteRow updated = routes.update(tenantId, brandId, locationId, routeId, body.toDraft(), expected, actor());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(updated.version()))
                .body(PrintRouteResponse.of(updated));
    }

    @DeleteMapping("/print/routes/{routeId}")
    @RequiresCapability(value = Capability.PRINT_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            operationId = "printDeleteRoute",
            summary = "Delete a route",
            description = "Under If-Match. Jobs already made from it stay.")
    public ResponseEntity<Void> deleteRoute(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID routeId,
            HttpServletRequest request) {
        routes.delete(tenantId, brandId, locationId, routeId, AggregateVersion.requireIfMatch(request), actor());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/print/pos-export")
    @RequiresCapability(value = Capability.PRINT_READ, scope = ScopeType.LOCATION)
    @Operation(
            operationId = "printGetPosExport",
            summary = "Whether the branch's point of sale may already print",
            description = "True when the branch has an active binding that provides ORDER_EXPORT. The platform "
                    + "cannot read whether the till prints what it receives, so the routes screen says \"your "
                    + "point of sale may already print this\" beside the kitchen-ticket route. A statement: it "
                    + "creates, removes and refuses nothing.")
    public ResponseEntity<PrintPosExportResponse> posExportState(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return ResponseEntity.ok(new PrintPosExportResponse(posExport.orderExportBound(tenantId, brandId, locationId)));
    }

    // ==================================================================== jobs

    @GetMapping("/print/jobs")
    @RequiresCapability(value = Capability.PRINT_READ, scope = ScopeType.LOCATION)
    @Operation(
            operationId = "printListJobs",
            summary = "The print queue",
            description = "Newest first, optionally one state (QUEUED, CLAIMED, PRINTED, HANDED_OFF, DEAD, "
                    + "CANCELLED, EXPIRED). The stored document is never returned: a list needs identifiers "
                    + "and outcomes, not the paper.")
    public ResponseEntity<Page<PrintJobResponse>> listJobs(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam(required = false) @Nullable String status,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        List<String> statuses = status == null || status.isBlank() ? List.of() : List.of(status.strip());
        statuses.forEach(PrintConsoleController::requireJobStatus);
        String filter = String.join(",", statuses);
        Cursor decoded = Cursor.decodeUnsigned(cursor, filter).orElse(null);
        if (cursor != null && !cursor.isBlank() && decoded == null) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "That cursor is not usable with these filters");
        }
        int pageSize = Page.limitOrDefault(limit);
        Instant beforeAt = null;
        UUID beforeId = null;
        if (decoded != null) {
            String[] parts = decoded.sortKey().split("~", 2);
            try {
                beforeAt = Instant.parse(parts[0]);
                beforeId = UUID.fromString(parts[1]);
            } catch (RuntimeException malformed) {
                throw new ApiException(ErrorCode.INVALID_REQUEST, "That cursor is not usable with these filters");
            }
        }
        List<JobRow> rows = queue.list(tenantId, locationId, statuses, beforeAt, beforeId, pageSize + 1);
        boolean more = rows.size() > pageSize;
        List<JobRow> page = more ? rows.subList(0, pageSize) : rows;
        String next = null;
        if (more) {
            JobRow last = page.getLast();
            next = new Cursor(last.queuedAt() + "~" + last.id(), filter).encodeUnsigned();
        }
        return ResponseEntity.ok(
                new Page<>(page.stream().map(PrintJobResponse::of).toList(), next));
    }

    @GetMapping("/print/jobs/{jobId}")
    @RequiresCapability(value = Capability.PRINT_READ, scope = ScopeType.LOCATION)
    @Operation(
            operationId = "printGetJob",
            summary = "One print job",
            description = "Its state, attempts and failure, never its document.")
    public ResponseEntity<PrintJobResponse> job(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID jobId) {
        return ResponseEntity.ok(PrintJobResponse.of(queue.find(tenantId, locationId, jobId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such print job"))));
    }

    @PostMapping("/print/jobs/{jobId}/cancel")
    @RequiresCapability(value = Capability.PRINT_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            operationId = "printCancelJob",
            summary = "Cancel a queued job",
            description = "Only a queued one: a claimed job is already on its way to paper (422 otherwise).")
    public ResponseEntity<PrintJobResponse> cancel(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID jobId) {
        return ResponseEntity.ok(PrintJobResponse.of(queue.cancel(tenantId, locationId, jobId)));
    }

    @PostMapping("/print/jobs/{jobId}/reprint")
    @RequiresCapability(value = Capability.PRINT_REPRINT, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            operationId = "printReprintJob",
            summary = "Print a finished job's document again",
            description = "For a dead, expired, cancelled or printed job: a new job, regenerated from the facts "
                    + "as they are now, marked as a copy, linked to the first print and recorded with the "
                    + "person and the reason. printerId chooses the printer; without it the document goes to "
                    + "the printer the old job was for, which must still be active. A job still queued or "
                    + "claimed, and a test page, are refused 422; a fiscal receipt is refused naming the "
                    + "state if its document is no longer an ISSUED SALE.")
    public ResponseEntity<PrintJobResponse> reprintJob(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID jobId,
            @Valid @RequestBody PrintReprintJobRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(PrintJobResponse.of(onDemand.reprintJob(
                        tenantId, brandId, locationId, jobId, body.printerId(), body.reasonCode(), actor())));
    }

    @PostMapping("/orders/{orderId}/print-jobs")
    @RequiresCapability(value = Capability.PRINT_REPRINT, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            operationId = "printReprintOrderDocument",
            summary = "Print an order's document again",
            description = "documentType is KITCHEN_TICKET, ORDER_SLIP or FISCAL_RECEIPT. A reprint is a new "
                    + "job, regenerated from the current facts, marked as a copy, linked to the first and "
                    + "recorded with the person and the reason. printerId chooses the printer; without it "
                    + "the document goes where its route says, and a document with no route is refused "
                    + "(NO_ROUTE). A fiscal receipt prints only for an ISSUED SALE document, and the "
                    + "refusal names the state. A kitchen ticket makes one job per station with items.")
    public ResponseEntity<List<PrintJobResponse>> reprint(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID orderId,
            @Valid @RequestBody PrintReprintRequest body) {
        List<JobRow> made = onDemand.reprint(
                tenantId,
                brandId,
                locationId,
                orderId,
                body.documentType(),
                body.printerId(),
                body.reasonCode(),
                actor());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(made.stream().map(PrintJobResponse::of).toList());
    }

    @PostMapping(value = "/print/documents/preview", produces = MediaType.IMAGE_PNG_VALUE)
    @RequiresCapability(value = Capability.PRINT_READ, scope = ScopeType.LOCATION)
    @Operation(
            operationId = "printPreviewDocument",
            summary = "The picture of what a job would print",
            description = "A PNG of the same 1-bit bitmap the browser prints and an agent receives as raster, "
                    + "for a stored job (jobId) or for a document built the way a job would be (documentType "
                    + "and orderId, optionally stationId, printerId or tapeWidthMm). A POST because it "
                    + "carries a body; it changes nothing, so it declares its capability and needs no "
                    + "Idempotency-Key, and it reveals a line note only as the ticket prints it.")
    public ResponseEntity<byte[]> preview(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody PrintPreviewRequest body) {
        byte[] png = onDemand.preview(
                tenantId,
                brandId,
                locationId,
                new PreviewRequest(
                        body.jobId(),
                        body.documentType(),
                        body.orderId(),
                        body.stationId(),
                        body.printerId(),
                        body.tapeWidthMm()));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .body(png);
    }

    @GetMapping(value = "/print/jobs/{jobId}/picture", produces = MediaType.IMAGE_PNG_VALUE)
    @RequiresCapability(value = Capability.PRINT_REPRINT, scope = ScopeType.LOCATION)
    @Operation(
            operationId = "printHandedOffPicture",
            summary = "The picture of a job the caller handed to the browser",
            description = "The workstation prints the preview bitmap through the browser (ADR 0154, Decision 10), "
                    + "so the person who asked for paper on a browser printer fetches the picture here: the same "
                    + "1-bit bitmap the preview draws, for a job in state HANDED_OFF that this caller requested. A "
                    + "job that is anyone else's, is not handed off, or does not exist answers 404. It changes "
                    + "nothing. It declares print.reprint, the capability that made the job, because the preview's "
                    + "print.read -- a picture of any document of the branch -- is held by managers and not by the "
                    + "floor.")
    public ResponseEntity<byte[]> handedOffPicture(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID jobId) {
        byte[] png = onDemand.handedOffPicture(tenantId, brandId, locationId, jobId, actor());
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .body(png);
    }

    // =================================================================== agents

    @GetMapping("/print/agents")
    @RequiresCapability(value = Capability.PRINT_READ, scope = ScopeType.LOCATION)
    @Operation(
            operationId = "printListAgents",
            summary = "The branch's print agents",
            description = "Device principals of class PRINT_AGENT, active and revoked alike, the printers each "
                    + "advertises (alias and last status) and the printer rows bound to it.")
    public ResponseEntity<List<PrintAgentResponse>> listAgents(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return ResponseEntity.ok(agents.list(tenantId, brandId, locationId).stream()
                .map(PrintAgentResponse::of)
                .toList());
    }

    @GetMapping("/print/agents/enrolments/{userCode}")
    @RequiresCapability(value = Capability.PRINT_MANAGE, scope = ScopeType.LOCATION)
    @Operation(
            operationId = "printPendingEnrolment",
            summary = "What a pending code claims",
            description = "The class the device asked for, shown to the approver before approving. An "
                    + "unknown, spent or expired code is the same 404.")
    public ResponseEntity<PrintPendingAgentResponse> pending(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable String userCode) {
        return ResponseEntity.ok(agents.pending(userCode)
                .map(found -> new PrintPendingAgentResponse(
                        found.requestedClass().name(), found.requestedLabel(), found.expiresAt()))
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No pending enrolment")));
    }

    @PostMapping("/print/agents/enrolments/{userCode}/approve")
    @RequiresCapability(value = Capability.PRINT_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            operationId = "printApproveAgent",
            summary = "Approve a pending print agent",
            description = "The userCode is read off the agent's own screen or log. The agent is granted "
                    + "exactly print.job.claim and print.job.report at this branch, never more; a code that "
                    + "asked to be anything else is refused 400 with nothing written. Writes an ADR 0027 "
                    + "security fact naming this manager and the device.")
    public ResponseEntity<PrintAgentDeviceResponse> approve(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable String userCode,
            @Valid @RequestBody PrintApproveAgentRequest body) {
        return ResponseEntity.ok(PrintAgentDeviceResponse.of(
                agents.approve(tenantId, brandId, locationId, userCode, body.displayName(), actor())));
    }

    @PostMapping("/print/agents/{deviceId}/revoke")
    @RequiresCapability(value = Capability.PRINT_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            operationId = "printRevokeAgent",
            summary = "Revoke a print agent",
            description = "Its grant is revoked first, cache-evicted at once, so the agent is refused on its "
                    + "very next request whatever its token's remaining life. A second press on a revoked "
                    + "agent is not an error.")
    public ResponseEntity<PrintRevokeAgentResponse> revoke(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID deviceId,
            @Valid @RequestBody PrintRevokeAgentRequest body) {
        return ResponseEntity.ok(new PrintRevokeAgentResponse(
                agents.revoke(tenantId, brandId, locationId, deviceId, body.reason(), actor())));
    }

    // ================================================================= helpers

    private String actor() {
        return currentActor.get().subject();
    }

    private static void requireJobStatus(String status) {
        for (var known : uz.horecaos.platform.printing.domain.JobStatus.values()) {
            if (known.name().equals(status)) {
                return;
            }
        }
        throw new ApiException(ErrorCode.INVALID_REQUEST, "Unknown job status \"" + status + "\"");
    }

    // ================================================================ messages

    /**
     * @param agentDeviceId the print agent that serves the printer; with {@code agentPrinterKey}, an agent
     *                      printer. Both null is a browser printer
     * @param status        ACTIVE or RETIRED; ignored on create
     */
    public record PrintPrinterRequest(
            @NotBlank @Size(max = 120) String displayName,
            @NotNull Integer tapeWidthMm,
            @Nullable UUID agentDeviceId,
            @Nullable @Size(max = 64) String agentPrinterKey,
            @Nullable String status) {

        PrinterDraft toDraft() {
            return new PrinterDraft(displayName, tapeWidthMm, agentDeviceId, agentPrinterKey);
        }
    }

    public record PrintPrinterResponse(
            UUID id,
            String displayName,
            int tapeWidthMm,
            String connectionKind,
            @Nullable UUID agentDeviceId,
            @Nullable String agentPrinterKey,
            String status,
            @Nullable String lastStatus,
            @Nullable Instant lastStatusAt,
            int version) {

        static PrintPrinterResponse of(PrinterRow row) {
            return new PrintPrinterResponse(
                    row.id(),
                    row.displayName(),
                    row.tapeWidthMm(),
                    row.connectionKind(),
                    row.agentDeviceId(),
                    row.agentPrinterKey(),
                    row.status(),
                    row.lastStatus() == null ? null : row.lastStatus().name(),
                    row.lastStatusAt(),
                    row.version());
        }
    }

    /**
     * @param stationId null on a KITCHEN_TICKET route is the fallback for a station with none of its own;
     *                  must be null on every other type
     * @param copies    1 to 3; default 1
     * @param autoPrint default false: nothing prints by default
     * @param status    ACTIVE (default) or INACTIVE
     */
    public record PrintRouteRequest(
            @NotNull PrintDocumentType documentType,
            @Nullable UUID stationId,
            @NotNull UUID printerId,
            @Nullable Integer copies,
            @Nullable Boolean autoPrint,
            @Nullable String status) {

        RouteDraft toDraft() {
            if (status != null && !"ACTIVE".equals(status) && !"INACTIVE".equals(status)) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "A route's status is ACTIVE or INACTIVE");
            }
            return new RouteDraft(
                    documentType,
                    stationId,
                    printerId,
                    copies == null ? 1 : copies,
                    Boolean.TRUE.equals(autoPrint),
                    !"INACTIVE".equals(status));
        }
    }

    public record PrintRouteResponse(
            UUID id,
            String documentType,
            @Nullable UUID stationId,
            UUID printerId,
            int copies,
            boolean autoPrint,
            String status,
            int version) {

        static PrintRouteResponse of(RouteRow row) {
            return new PrintRouteResponse(
                    row.id(),
                    row.documentType().name(),
                    row.stationId(),
                    row.printerId(),
                    row.copies(),
                    row.autoPrint(),
                    row.status(),
                    row.version());
        }
    }

    public record PrintJobResponse(
            UUID id,
            UUID printerId,
            @Nullable UUID routeId,
            String documentType,
            String status,
            String origin,
            String sourceType,
            @Nullable UUID sourceId,
            int sourceRevision,
            @Nullable UUID stationId,
            int copyNo,
            @Nullable String requestedBy,
            @Nullable String reasonCode,
            @Nullable UUID reprintOf,
            int attempt,
            @Nullable String failureCode,
            String locale,
            int tapeWidthMm,
            Instant queuedAt,
            Instant expiresAt,
            @Nullable Instant printedAt,
            boolean documentErased,
            int version) {

        static PrintJobResponse of(JobRow row) {
            return new PrintJobResponse(
                    row.id(),
                    row.printerId(),
                    row.routeId(),
                    row.documentType().name(),
                    row.status().name(),
                    row.origin(),
                    row.sourceType(),
                    row.sourceId(),
                    row.sourceRevision(),
                    row.stationId(),
                    row.copyNo(),
                    row.requestedBy(),
                    row.reasonCode(),
                    row.reprintOf(),
                    row.attempt(),
                    row.failureCode() == null ? null : row.failureCode().name(),
                    row.locale(),
                    row.tapeWidthMm(),
                    row.queuedAt(),
                    row.expiresAt(),
                    row.printedAt(),
                    row.documentErased(),
                    row.version());
        }
    }

    public record PrintReprintJobRequest(
            @Nullable UUID printerId, @NotNull PrintOnDemandService.Reason reasonCode) {}

    public record PrintPosExportResponse(boolean orderExportActive) {}

    public record PrintReprintRequest(
            @NotNull PrintDocumentType documentType,
            @Nullable UUID printerId,
            @NotNull PrintOnDemandService.Reason reasonCode) {}

    /**
     * @param jobId        a stored job's picture; or build one from {@code documentType} with {@code orderId}
     * @param tapeWidthMm  58 or 80 when no printer is named; default 80
     */
    public record PrintPreviewRequest(
            @Nullable UUID jobId,
            @Nullable PrintDocumentType documentType,
            @Nullable UUID orderId,
            @Nullable UUID stationId,
            @Nullable UUID printerId,
            @Nullable Integer tapeWidthMm) {}

    public record PrintPendingAgentResponse(
            String requestedClass, @Nullable String requestedLabel, Instant expiresAt) {}

    public record PrintApproveAgentRequest(
            @NotBlank @Size(max = 120) String displayName) {}

    public record PrintRevokeAgentRequest(
            @NotBlank @Size(max = 500) String reason) {}

    public record PrintRevokeAgentResponse(boolean revoked) {}

    public record PrintAgentDeviceResponse(
            UUID deviceId,
            String deviceClass,
            String displayName,
            String status,
            String enrolledBy,
            Instant enrolledAt,
            @Nullable String revokedBy,
            @Nullable Instant revokedAt,
            @Nullable String revokedReason) {

        static PrintAgentDeviceResponse of(DevicePrincipalView device) {
            return new PrintAgentDeviceResponse(
                    device.id(),
                    device.deviceClass().name(),
                    device.displayName(),
                    device.status(),
                    device.enrolledBy(),
                    device.enrolledAt(),
                    device.revokedBy(),
                    device.revokedAt(),
                    device.revokedReason());
        }
    }

    public record PrintAdvertisedPrinter(
            String key, String status, @Nullable String agentBuild, Instant seenAt) {}

    public record PrintAgentResponse(
            PrintAgentDeviceResponse device, List<PrintAdvertisedPrinter> advertised, List<UUID> boundPrinterIds) {

        static PrintAgentResponse of(AgentView view) {
            return new PrintAgentResponse(
                    PrintAgentDeviceResponse.of(view.device()),
                    view.advertised().stream().map(PrintAgentResponse::advert).toList(),
                    view.boundPrinters().stream().map(PrinterRow::id).toList());
        }

        private static PrintAdvertisedPrinter advert(AdvertRow row) {
            return new PrintAdvertisedPrinter(row.printerKey(), row.status().name(), row.agentBuild(), row.seenAt());
        }
    }
}
