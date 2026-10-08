package uz.horecaos.platform.printing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.printing.application.PrintAgentClaims;
import uz.horecaos.platform.printing.application.PrintAgentService;
import uz.horecaos.platform.printing.application.PrintJobQueue;
import uz.horecaos.platform.printing.application.PrintJobQueue.AgentIdentity;
import uz.horecaos.platform.printing.application.PrintJobQueue.ArtifactResult;
import uz.horecaos.platform.printing.application.PrintJobQueue.ClaimedJob;
import uz.horecaos.platform.printing.application.PrintJobQueue.Recorded;
import uz.horecaos.platform.printing.application.PrintJobQueue.ReportedOutcome;
import uz.horecaos.platform.printing.application.PrintJobQueue.ReportedPrinter;
import uz.horecaos.platform.printing.domain.FailureCode;
import uz.horecaos.platform.printing.domain.PrinterStatus;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * What a print agent calls (ADR 0154, "Job lifecycle and the agent contract"): claim jobs for the printers it
 * can reach, fetch the bytes of a job it holds the lease of, and report what happened.
 *
 * <p>The agent is a device principal (ADR 0079) holding exactly {@code print.job.claim} and {@code
 * print.job.report} at its own branch, and it is a byte pipe: it receives bytes and a printer alias and
 * reports an outcome, and holds no template, no language and no business rule. Nothing in a reply tells it an
 * address to dial. Every call is subject to the capability check at the path's branch, and then to the
 * agent's own record: the ACTIVE print agent enrolled at exactly this branch, so a revoked agent's token that
 * is still valid stops at its very next request.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/print")
@Tag(name = "Print agent", description = "ADR 0154: what a branch's print agent calls, over outbound HTTPS")
public class PrintAgentController {

    private final PrintAgentService agents;
    private final PrintAgentClaims claims;
    private final PrintJobQueue queue;
    private final CurrentActor currentActor;

    public PrintAgentController(
            PrintAgentService agents, PrintAgentClaims claims, PrintJobQueue queue, CurrentActor currentActor) {
        this.agents = agents;
        this.claims = claims;
        this.queue = queue;
        this.currentActor = currentActor;
    }

    @PostMapping("/claims")
    @RequiresCapability(value = Capability.PRINT_JOB_CLAIM, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            operationId = "printClaimJobs",
            summary = "Claim print jobs",
            description = "Says which printers the agent can reach, by the alias it chose locally, and what "
                    + "each last reported (ONLINE, OFFLINE, OUT_OF_PAPER, COVER_OPEN, ERROR, UNKNOWN); a "
                    + "printer that reports anything but ONLINE stops being claimed from. Long-polls up to "
                    + "waitSeconds (at most 20). The reply first repeats this agent's own unexpired leases, "
                    + "so a retry after a lost reply returns what was claimed, and then claims the oldest "
                    + "claimable job of each idle printer, one at a time per printer in the order they were "
                    + "queued. A reply holds identifiers and 60-second lease tokens, never the paper. "
                    + "Requires an Idempotency-Key.")
    public ResponseEntity<PrintClaimResponse> claim(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody PrintClaimRequest body) {
        AgentIdentity agent = agents.requireAgent(currentActor.get().subject(), tenantId, brandId, locationId);
        List<ReportedPrinter> printers = body.printers() == null
                ? List.of()
                : body.printers().stream().map(PrintReportedPrinter::toReported).toList();
        List<ClaimedJob> jobs = claims.claim(agent, printers, body.agentBuild(), body.maxJobs(), body.waitSeconds());
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .body(new PrintClaimResponse(
                        jobs.stream().map(PrintClaimedJob::of).toList()));
    }

    @GetMapping(value = "/jobs/{jobId}/artifact", produces = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    @RequiresCapability(value = Capability.PRINT_JOB_CLAIM, scope = ScopeType.LOCATION)
    @Operation(
            operationId = "printJobArtifact",
            summary = "The bytes of a claimed job",
            description = "The ESC/POS for the bound printer's width, rendered now from the stored document. "
                    + "Readable only with the job's live lease token (the lease query parameter) and only by "
                    + "the agent that holds it: a stale, expired or foreign lease is 409 and nothing is drawn. "
                    + "A document that cannot be drawn is 422 RENDER_FAILED and the job is dead.")
    public ResponseEntity<byte[]> artifact(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID jobId,
            @RequestParam("lease") UUID lease) {
        AgentIdentity agent = agents.requireAgent(currentActor.get().subject(), tenantId, brandId, locationId);
        return switch (queue.artifact(agent, jobId, lease)) {
            case ArtifactResult.Bytes bytes ->
                ResponseEntity.ok()
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .cacheControl(CacheControl.noStore())
                        .header("X-Print-Protocol", bytes.protocol())
                        .body(bytes.content());
            case ArtifactResult.Unrenderable ignored ->
                throw new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE,
                        "That document cannot be drawn, so the job is dead and nothing will be retried",
                        Map.of("reason", "RENDER_FAILED"));
        };
    }

    @PostMapping("/jobs/{jobId}/results")
    @RequiresCapability(value = Capability.PRINT_JOB_REPORT, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            operationId = "printReportResult",
            summary = "Report what happened to a claimed job",
            description = "PRINTED ends the job. FAILED, with a failureCode (PRINTER_OFFLINE, OUT_OF_PAPER, "
                    + "COVER_OPEN, TRANSPORT, RENDER), returns it to the queue after a backoff (5 s, 15 s, "
                    + "45 s, 2 min, 5 min) or ends it DEAD on the sixth attempt. A stale or foreign lease "
                    + "token is 409 and changes nothing; the same PRINTED repeated is recognised and answered "
                    + "200 REPLAYED. Requires an Idempotency-Key.")
    public ResponseEntity<PrintResultResponse> result(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID jobId,
            @Valid @RequestBody PrintResultRequest body) {
        AgentIdentity agent = agents.requireAgent(currentActor.get().subject(), tenantId, brandId, locationId);
        FailureCode code = null;
        if (body.failureCode() != null) {
            code = FailureCode.parse(body.failureCode())
                    .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown failureCode"));
        }
        Recorded recorded = queue.report(agent, jobId, body.leaseToken(), body.outcome(), code);
        return ResponseEntity.ok(new PrintResultResponse(recorded.name()));
    }

    // ================================================================ messages

    public record PrintReportedPrinter(
            @NotBlank @Size(max = 64) String key, @NotBlank String status) {

        ReportedPrinter toReported() {
            PrinterStatus parsed = PrinterStatus.parse(status)
                    .orElseThrow(() -> new ApiException(
                            ErrorCode.VALIDATION_FAILED,
                            "A printer status is ONLINE, OFFLINE, OUT_OF_PAPER, COVER_OPEN, ERROR or UNKNOWN"));
            return new ReportedPrinter(key, parsed);
        }
    }

    /**
     * @param maxJobs     at most this many jobs in the reply; default 1, at most 10
     * @param waitSeconds how long to wait for work; default 0, at most 20
     * @param agentBuild  the agent's own build string, which a test page prints
     */
    public record PrintClaimRequest(
            @Nullable @Size(max = 32) List<@Valid PrintReportedPrinter> printers,
            @Nullable Integer maxJobs,
            @Nullable Integer waitSeconds,
            @Nullable @Size(max = 64) String agentBuild) {}

    public record PrintClaimedJob(
            UUID jobId, String printerKey, String documentType, UUID leaseToken, Instant leaseExpiresAt) {

        static PrintClaimedJob of(ClaimedJob job) {
            return new PrintClaimedJob(
                    job.jobId(), job.printerKey(), job.documentType(), job.leaseToken(), job.leaseExpiresAt());
        }
    }

    public record PrintClaimResponse(List<PrintClaimedJob> jobs) {}

    public record PrintResultRequest(
            @NotNull UUID leaseToken,
            @NotNull ReportedOutcome outcome,
            @Nullable String failureCode) {}

    public record PrintResultResponse(String recorded) {}
}
