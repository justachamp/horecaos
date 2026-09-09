package uz.horecaos.platform.pos.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.pos.application.PosApplyService;
import uz.horecaos.platform.pos.application.PosCapabilityService;
import uz.horecaos.platform.pos.application.PosCatalogSyncService;
import uz.horecaos.platform.pos.domain.ReviewOutcome;
import uz.horecaos.platform.pos.domain.SyncDifference;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosApplyStore.ApplyItemRow;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosSyncStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Catalog synchronization runs and their reports (ADR 0012, ADR 0031).
 *
 * <p>Reading a run, running one, and accepting what it found are three
 * different capabilities. {@link Capability#POS_SYNC_READ} and {@link
 * Capability#POS_SYNC_EXECUTE} apply nothing; {@link
 * Capability#POS_SYNC_APPLY} gates {@link #recordReviewDecision}, {@link
 * #applyRun}, and {@link #resumeRun} — the only three methods here that can
 * change a mapping or a menu, deliberately behind a capability separate from
 * the one that starts a run. There is no safe undo for a menu that was live
 * and wrong during a lunch rush, so the person who triggered the import is
 * never, by capability alone, the person who accepted what it found.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/pos-sync-runs")
@Tag(name = "POS catalog synchronization", description = "Reviewed catalog imports from a point of sale")
public class PosSyncRunController {

    private final PosCatalogSyncService sync;
    private final PosApplyService apply;
    private final PosCapabilityService capabilities;
    private final JdbcPosSyncStore runs;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final java.time.Clock clock;

    public PosSyncRunController(
            PosCatalogSyncService sync,
            PosApplyService apply,
            PosCapabilityService capabilities,
            JdbcPosSyncStore runs,
            AuditRecorder audit,
            CurrentActor currentActor,
            java.time.Clock clock) {
        this.sync = sync;
        this.apply = apply;
        this.capabilities = capabilities;
        this.runs = runs;
        this.audit = audit;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @PostMapping
    @RequiresCapability(value = Capability.POS_SYNC_EXECUTE, mutating = true)
    @Operation(
            summary = "Start a catalog import",
            description = "Reads the provider, stages a snapshot, and produces a difference report. "
                    + "It stops there: nothing in this call changes a menu.")
    ResponseEntity<Map<String, Object>> start(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @Valid @RequestBody StartRequest request) {

        PosCatalogSyncService.RunResult result = sync.run(tenantId, request.bindingId(), "MANUAL", dryRun);
        UUID runId = result.runId();

        if (runId != null) {
            audit.record(AuditFact.of("pos.catalog_sync_started", AuditClass.BUSINESS)
                    .by(ActorRef.user(currentActor.get().subject(), null))
                    .at(ResourceScope.tenant(tenantId))
                    .target("PosSyncRun", runId)
                    .because("Manual catalog import")
                    .changed(Map.of("bindingId", request.bindingId().toString(), "dryRun", Boolean.toString(dryRun)))
                    .usingCapability(Capability.POS_SYNC_EXECUTE.code())
                    .correlatedBy(runId.toString())
                    .occurredAt(clock.instant())
                    .build());
        }

        return ResponseEntity.ok(Map.of(
                "runId", runId == null ? "" : runId.toString(),
                "status", result.status(),
                "differenceCount", result.differenceCount(),
                "conflictCount", result.conflictCount(),
                "detail",
                        result.outcome().detail() == null
                                ? ""
                                : result.outcome().detail()));
    }

    @PostMapping("/{runId}/review-decisions")
    @RequiresCapability(value = Capability.POS_SYNC_APPLY, mutating = true)
    @Operation(
            summary = "Decide one difference recommended for review",
            description = "Only a difference the engine recommended REVIEW accepts a decision. "
                    + "APPROVED means the provider's version becomes HorecaOS's, subject to what "
                    + "apply can actually execute; REJECTED and DEFERRED never do.")
    ResponseEntity<Map<String, Object>> recordReviewDecision(
            @PathVariable UUID tenantId, @PathVariable UUID runId, @Valid @RequestBody ReviewDecisionRequest request) {

        PosApplyService.DecisionOutcome outcome = apply.decide(
                        tenantId,
                        runId,
                        request.differenceId(),
                        request.outcome(),
                        currentActor.get().subject(),
                        request.note())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such run or difference"));

        if (!outcome.accepted()) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, outcome.reason());
        }

        audit.record(AuditFact.of("pos.catalog_sync_review_decided", AuditClass.BUSINESS)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.tenant(tenantId))
                .target("PosSyncDifference", request.differenceId())
                .because("Catalog sync review decision")
                .changed(Map.of(
                        "runId", runId.toString(), "outcome", request.outcome().name()))
                .usingCapability(Capability.POS_SYNC_APPLY.code())
                .correlatedBy(runId.toString())
                .occurredAt(clock.instant())
                .build());

        return ResponseEntity.ok(Map.of(
                "runId",
                runId.toString(),
                "differenceId",
                request.differenceId().toString()));
    }

    @PostMapping("/{runId}/apply")
    @RequiresCapability(value = Capability.POS_SYNC_APPLY, mutating = true)
    @Operation(
            summary = "Execute every eligible item an approved run produces",
            description = "Refused for a dry run and for a run that is not REVIEW_REQUIRED. Plans "
                    + "every auto-applicable and approved difference, then executes what this build "
                    + "can — see PosApplyService for what that is today. An interrupted apply is "
                    + "picked up with resume, not by calling this again.")
    ResponseEntity<Map<String, Object>> applyRun(@PathVariable UUID tenantId, @PathVariable UUID runId) {

        PosApplyService.ApplyOutcome outcome = apply.apply(tenantId, runId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such run"));

        if (!outcome.started()) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, outcome.reason());
        }

        audit.record(AuditFact.of("pos.catalog_sync_applied", AuditClass.BUSINESS)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.tenant(tenantId))
                .target("PosSyncRun", runId)
                .because("Catalog sync apply")
                .changed(itemCounts(outcome.items()))
                .usingCapability(Capability.POS_SYNC_APPLY.code())
                .correlatedBy(runId.toString())
                .occurredAt(clock.instant())
                .build());

        return ResponseEntity.ok(applyResponse(runId, outcome.items()));
    }

    @PostMapping("/{runId}/resume")
    @RequiresCapability(value = Capability.POS_SYNC_APPLY, mutating = true)
    @Operation(
            summary = "Continue whatever this run's own interruption points left behind",
            description = "A run interrupted before REVIEW_REQUIRED cannot resume mid-fetch -- the "
                    + "first provider offers no incremental read -- so this asks for a fresh "
                    + "PosSyncRequested run instead. A run interrupted mid-apply resumes exactly "
                    + "that: executing whatever apply item is still PLANNED, never re-applying one "
                    + "already APPLIED.")
    ResponseEntity<Map<String, Object>> resumeRun(@PathVariable UUID tenantId, @PathVariable UUID runId) {

        PosApplyService.ResumeOutcome outcome = apply.resume(tenantId, runId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such run"));

        if (!outcome.accepted()) {
            throw new ApiException(ErrorCode.UNPROCESSABLE_STATE, outcome.reason());
        }

        Map<String, Object> changed = new LinkedHashMap<>();
        if (outcome.requestedRunId() != null) {
            changed.put("requestedRunId", outcome.requestedRunId().toString());
        }
        if (outcome.applyProgress() != null) {
            changed.putAll(itemCounts(outcome.applyProgress().items()));
        }

        audit.record(AuditFact.of("pos.catalog_sync_resumed", AuditClass.BUSINESS)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.tenant(tenantId))
                .target("PosSyncRun", runId)
                .because("Catalog sync resume")
                .changed(changed)
                .usingCapability(Capability.POS_SYNC_APPLY.code())
                .correlatedBy(runId.toString())
                .occurredAt(clock.instant())
                .build());

        Map<String, Object> body = new LinkedHashMap<>(changed);
        body.put("runId", runId.toString());
        return ResponseEntity.ok(body);
    }

    private static Map<String, Object> itemCounts(List<ApplyItemRow> items) {
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("itemCount", Integer.toString(items.size()));
        for (String status : List.of("APPLIED", "FAILED", "SKIPPED", "RETURNED_TO_REVIEW", "PLANNED")) {
            long count =
                    items.stream().filter(item -> status.equals(item.status())).count();
            if (count > 0) {
                counts.put(status.toLowerCase(java.util.Locale.ROOT) + "Count", Long.toString(count));
            }
        }
        return counts;
    }

    private static Map<String, Object> applyResponse(UUID runId, List<ApplyItemRow> items) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("runId", runId.toString());
        body.putAll(itemCounts(items));
        return body;
    }

    @GetMapping("/{runId}/differences")
    @RequiresCapability(Capability.POS_SYNC_READ)
    @Operation(
            summary = "The difference report",
            description = "Deterministic: re-running the comparison over the same snapshot produces "
                    + "this list again, in this order.")
    Page<DifferenceView> differences(
            @PathVariable UUID tenantId,
            @PathVariable UUID runId,
            @RequestParam(required = false) Integer limit,
            @RequestParam(defaultValue = "0") int offset) {

        int size = Page.limitOrDefault(limit);
        List<DifferenceView> rows = runs.differences(tenantId, runId, size, offset).stream()
                .map(PosSyncRunController::toView)
                .toList();

        // Offset paging, which ADR 0031 refuses for an Operations feed and which
        // is correct here for the reason that refusal gives. Offsets skip and
        // duplicate rows because the underlying collection changes while a user
        // pages; a run's differences do not change — they are written once, when
        // the comparison ran, and the comparison does not run again for that run.
        // The cursor is therefore the next offset, and the null that ends the
        // iteration is a short page.
        return rows.size() < size ? Page.last(rows) : new Page<>(rows, Integer.toString(offset + size));
    }

    @PostMapping("/capability-reconciliation")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, mutating = true)
    @Operation(
            summary = "Rediscover what an installation can do",
            description = "Probes the provider with this restaurant's own credential. Capability "
                    + "varies per installation because the credential acts as a staff user the "
                    + "restaurant chose, so this is discovery and not a lookup.")
    ResponseEntity<Map<String, Object>> reconcileCapabilities(
            @PathVariable UUID tenantId, @Valid @RequestBody ReconcileRequest request) {

        return capabilities
                .reconcile(tenantId, request.installationId(), request.providerType())
                .map(snapshot -> ResponseEntity.ok(Map.<String, Object>of(
                        "installationId", request.installationId().toString(),
                        "adapterVersion", snapshot.adapterVersion() == null ? "" : snapshot.adapterVersion(),
                        "capabilities",
                                snapshot.entries().entrySet().stream()
                                        .collect(java.util.stream.Collectors.toMap(
                                                entry -> entry.getKey().code(),
                                                entry -> entry.getValue()
                                                        .support()
                                                        .name())))))
                .orElseGet(() -> ResponseEntity.ok(Map.of(
                        "installationId", request.installationId().toString(),
                        "capabilities", Map.of(),
                        "detail", "No POS adapter is registered for " + request.providerType())));
    }

    private static DifferenceView toView(SyncDifference difference) {
        return new DifferenceView(
                difference.entityType().name(),
                difference.externalEntityId(),
                difference.horecaosEntityId(),
                difference.category().name(),
                difference.fieldPath(),
                difference.currentValue(),
                difference.importedValue(),
                difference.authority().name(),
                difference.severity().name(),
                difference.recommendedAction().name());
    }

    public record StartRequest(@NotNull UUID bindingId) {}

    public record ReconcileRequest(
            @NotNull UUID installationId, @NotNull String providerType) {}

    /** @param note kept as free text for the reviewer's own reasoning; never required */
    public record ReviewDecisionRequest(
            @NotNull UUID differenceId,
            @NotNull ReviewOutcome outcome,
            @Nullable String note) {}

    /**
     * One row of the difference report, as the API exposes it.
     *
     * @param authority who owns this field. A {@code HORECAOS} authority with a
     *                  recommended action of {@code IGNORE} is the ordinary case
     *                  and the important one: the provider disagrees, and the
     *                  provider does not win
     */
    public record DifferenceView(
            String entityType,
            String externalEntityId,
            @Nullable UUID horecaosEntityId,
            String category,
            @Nullable String fieldPath,
            @Nullable String currentValue,
            @Nullable String importedValue,
            String authority,
            String severity,
            String recommendedAction) {}
}
