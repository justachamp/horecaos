package uz.qoida.platform.migration.web;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.qoida.platform.iam.api.Capability;
import uz.qoida.platform.iam.api.ResourceScope.ScopeType;
import uz.qoida.platform.migration.application.MigrationRunService;
import uz.qoida.platform.migration.application.MigrationRunStore.RunRow;
import uz.qoida.platform.migration.domain.RunStatus;
import uz.qoida.platform.migration.domain.RunType;
import uz.qoida.platform.web.api.AggregateVersion;
import uz.qoida.platform.web.authorization.RequiresCapability;
import uz.qoida.platform.web.idempotency.IdempotencyInterceptor;

/**
 * Opening and closing migration runs (ADR 0024).
 *
 * <p>Three of {@code MigrationRunService}'s methods are deliberately absent from
 * this surface, and the omissions are the design rather than an unfinished
 * controller. Checkpointing is a worker reporting progress thousands of times per
 * run against a run an operator already authorised; it carries no capability
 * check and no audit fact by design, and putting it behind an
 * {@code Idempotency-Key} and a platform-admin token would mean the migration
 * only ran while somebody was logged in. Resuming is the same call from the other
 * side. And the import-context wrapper is a code path, not a request. What
 * belongs here is what an operator decides: starting a run, reading one, and
 * ending one.
 *
 * <p>{@link Capability#MIGRATION_RUN_EXECUTE} rather than
 * {@code MIGRATION_SCOPE_MANAGE}, because pointing a migrator at a live tenant's
 * source during a window and re-planning a scope's route through the states are
 * different powers held by different people, and the on-call engineer who needs
 * the first has no business holding the second.
 */
@RestController
@RequestMapping("/api/v1/platform-admin/migration")
@Tag(name = "Migration runs", description = "Backfill, catch-up, remediation, and reconciliation runs")
public class MigrationRunController {

    private final MigrationRunService runs;

    public MigrationRunController(MigrationRunService runs) {
        this.runs = runs;
    }

    /**
     * Opens a run over a scope.
     *
     * <p>A retry with the same key joins the run it already started rather than
     * opening a second. Two backfills over one scope double every counter, and
     * the reconciliation that follows would then be arithmetic about a run that
     * never happened.
     */
    @PostMapping("/scopes/{scopeId}/runs")
    @RequiresCapability(value = Capability.MIGRATION_RUN_EXECUTE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(summary = "Start a run over a scope",
            description = "A new run of a type that already has a live one is refused rather than "
                    + "queued, and the refusal names the run that is already going.")
    ResponseEntity<RunView> start(
            @PathVariable UUID scopeId,
            @RequestParam UUID tenantId,
            @RequestHeader(IdempotencyInterceptor.IDEMPOTENCY_KEY_HEADER) String idempotencyKey,
            @Valid @RequestBody StartRunRequest body) {

        RunRow run = runs.start(tenantId, scopeId, new MigrationRunService.StartRunCommand(
                body.runType(), body.transformationVersion(), body.startedBy(), body.reason(),
                idempotencyKey));

        URI location = ServletUriComponentsBuilder.fromCurrentContextPath()
                .path("/api/v1/platform-admin/migration/runs/{runId}")
                .queryParam("tenantId", tenantId)
                .buildAndExpand(run.id())
                .toUri();
        return ResponseEntity.created(location)
                .eTag(AggregateVersion.toETag(run.version()))
                .body(RunView.of(run));
    }

    @GetMapping("/runs/{runId}")
    @RequiresCapability(value = Capability.MIGRATION_READ, scope = ScopeType.PLATFORM)
    @Operation(summary = "Get a run and its counters")
    ResponseEntity<RunView> get(@PathVariable UUID runId, @RequestParam UUID tenantId) {
        RunRow run = runs.get(tenantId, runId);
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(run.version()))
                .body(RunView.of(run));
    }

    /**
     * Ends the run.
     *
     * <p>{@code FAILED} and {@code CANCELLED} are separate outcomes on this
     * endpoint for the reason the domain keeps them separate: what a failed run
     * leaves behind is a checkpoint to resume from, and what a cancelled one
     * leaves behind is a decision somebody made. Collapsing them would make an
     * operator stopping a run look like the migration breaking.
     *
     * <p>After this the row freezes. A correction is a remediation run, not an
     * edit, because the counters and the checksum are what a reconciliation is
     * compared against and evidence that can be edited after the comparison
     * proves nothing.
     */
    @PostMapping("/runs/{runId}/outcome")
    @RequiresCapability(value = Capability.MIGRATION_RUN_EXECUTE, scope = ScopeType.PLATFORM, mutating = true)
    @Operation(summary = "Complete, fail, or cancel a run")
    ResponseEntity<RunView> finish(
            @PathVariable UUID runId,
            @RequestParam UUID tenantId,
            @Valid @RequestBody FinishRunRequest body) {

        RunRow run = runs.finish(tenantId, runId, new MigrationRunService.FinishRunCommand(
                body.status(), body.checksum(), body.expectedVersion(), body.reason()));
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(run.version()))
                .body(RunView.of(run));
    }

    /**
     * @param transformationVersion the version of the transformation code this run
     *                              applies, so every row it writes can be traced
     *                              back to the mapping that wrote it. A changed
     *                              mapping is a new REMEDIATION run and never a
     *                              silent second semantics in one entity family
     * @param startedBy             the migrator or operator the run is opened on
     *                              behalf of, recorded on the run itself
     */
    record StartRunRequest(
            @NotNull RunType runType,
            @Positive int transformationVersion,
            @NotBlank @Size(max = 255) String startedBy,
            @NotBlank @Size(max = 1000) String reason) { }

    /**
     * @param status   COMPLETED, FAILED, or CANCELLED. RUNNING is not an outcome
     * @param checksum hex sha-256 of what the pass produced, on a completed run
     */
    record FinishRunRequest(
            @NotNull RunStatus status,
            @Pattern(regexp = "[0-9a-f]{64}")
            @Schema(example = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
            String checksum,
            @Positive int expectedVersion,
            @NotBlank @Size(max = 1000) String reason) { }
}
