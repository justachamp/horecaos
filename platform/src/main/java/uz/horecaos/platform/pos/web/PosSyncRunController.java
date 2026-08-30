package uz.horecaos.platform.pos.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.pos.application.PosCapabilityService;
import uz.horecaos.platform.pos.application.PosCatalogSyncService;
import uz.horecaos.platform.pos.domain.SyncDifference;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosSyncStore;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Catalog synchronization runs and their reports (ADR 0012, ADR 0031).
 *
 * <p>Reading a run and running one are different capabilities, and neither of
 * them applies anything. Applying an approved comparison is
 * {@link Capability#POS_SYNC_APPLY} and is not exposed here yet: ADR 0012's
 * rollout is explicit that the first months deliver reports rather than
 * automation, and an apply endpoint that exists is an apply endpoint somebody
 * will call.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/pos-sync-runs")
@Tag(name = "POS catalog synchronization",
        description = "Reviewed catalog imports from a point of sale")
public class PosSyncRunController {

    private final PosCatalogSyncService sync;
    private final PosCapabilityService capabilities;
    private final JdbcPosSyncStore runs;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final java.time.Clock clock;

    public PosSyncRunController(PosCatalogSyncService sync, PosCapabilityService capabilities,
            JdbcPosSyncStore runs, AuditRecorder audit, CurrentActor currentActor,
            java.time.Clock clock) {
        this.sync = sync;
        this.capabilities = capabilities;
        this.runs = runs;
        this.audit = audit;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @PostMapping
    @RequiresCapability(value = Capability.POS_SYNC_EXECUTE, mutating = true)
    @Operation(summary = "Start a catalog import",
            description = "Reads the provider, stages a snapshot, and produces a difference report. "
                    + "It stops there: nothing in this call changes a menu.")
    ResponseEntity<Map<String, Object>> start(
            @PathVariable UUID tenantId,
            @RequestParam(defaultValue = "true") boolean dryRun,
            @Valid @RequestBody StartRequest request) {

        PosCatalogSyncService.RunResult result =
                sync.run(tenantId, request.bindingId(), "MANUAL", dryRun);

        if (result.started()) {
            audit.record(AuditFact.of("pos.catalog_sync_started", AuditClass.BUSINESS)
                    .by(ActorRef.user(currentActor.get().subject(), null))
                    .at(ResourceScope.tenant(tenantId))
                    .target("PosSyncRun", result.runId())
                    .because("Manual catalog import")
                    .changed(Map.of("bindingId", request.bindingId().toString(),
                            "dryRun", Boolean.toString(dryRun)))
                    .usingCapability(Capability.POS_SYNC_EXECUTE.code())
                    .correlatedBy(result.runId().toString())
                    .occurredAt(clock.instant())
                    .build());
        }

        return ResponseEntity.ok(Map.of(
                "runId", result.runId() == null ? "" : result.runId().toString(),
                "status", result.status(),
                "differenceCount", result.differenceCount(),
                "conflictCount", result.conflictCount(),
                "detail", result.outcome().detail() == null ? "" : result.outcome().detail()));
    }

    @GetMapping("/{runId}/differences")
    @RequiresCapability(Capability.POS_SYNC_READ)
    @Operation(summary = "The difference report",
            description = "Deterministic: re-running the comparison over the same snapshot produces "
                    + "this list again, in this order.")
    Page<DifferenceView> differences(
            @PathVariable UUID tenantId, @PathVariable UUID runId,
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
        return rows.size() < size
                ? Page.last(rows)
                : new Page<>(rows, Integer.toString(offset + size));
    }

    @PostMapping("/capability-reconciliation")
    @RequiresCapability(value = Capability.INTEGRATION_INSTALLATION_MANAGE, mutating = true)
    @Operation(summary = "Rediscover what an installation can do",
            description = "Probes the provider with this restaurant's own credential. Capability "
                    + "varies per installation because the credential acts as a staff user the "
                    + "restaurant chose, so this is discovery and not a lookup.")
    ResponseEntity<Map<String, Object>> reconcileCapabilities(
            @PathVariable UUID tenantId, @Valid @RequestBody ReconcileRequest request) {

        return capabilities.reconcile(tenantId, request.installationId(), request.providerType())
                .map(snapshot -> ResponseEntity.ok(Map.<String, Object>of(
                        "installationId", request.installationId().toString(),
                        "adapterVersion", snapshot.adapterVersion() == null
                                ? "" : snapshot.adapterVersion(),
                        "capabilities", snapshot.entries().entrySet().stream()
                                .collect(java.util.stream.Collectors.toMap(
                                        entry -> entry.getKey().code(),
                                        entry -> entry.getValue().support().name())))))
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

    public record StartRequest(@NotNull UUID bindingId) { }

    public record ReconcileRequest(@NotNull UUID installationId, @NotNull String providerType) { }

    /**
     * @param authority who owns this field. A {@code HORECAOS} authority with a
     *                  recommended action of {@code IGNORE} is the ordinary case
     *                  and the important one: the provider disagrees, and the
     *                  provider does not win
     */
    public record DifferenceView(
            String entityType, String externalEntityId, UUID horecaosEntityId, String category,
            String fieldPath, String currentValue, String importedValue, String authority,
            String severity, String recommendedAction) { }
}
