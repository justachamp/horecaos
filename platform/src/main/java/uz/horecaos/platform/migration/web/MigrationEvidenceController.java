package uz.horecaos.platform.migration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.migration.domain.MappingStatus;
import uz.horecaos.platform.migration.domain.ReconciliationSeverity;
import uz.horecaos.platform.migration.domain.ReconciliationStatus;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcEntityMappingStore;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcEntityMappingStore.EntityMappingRow;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcReconciliationStore;
import uz.horecaos.platform.migration.infrastructure.persistence.JdbcReconciliationStore.ReconciliationResultRow;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Two crosswalk reads ADR 0024 has always written and nothing served over
 * HTTP: the legacy-to-target identity mapping (control-plane IA 9.2), and one
 * reconciliation run's per-rule comparison (IA 9.3's "legacy vs HorecaOS
 * output diffing").
 *
 * <p>{@link JdbcEntityMappingStore#listForScope} and {@link
 * JdbcReconciliationStore#listForRun} are not new queries — {@code
 * ImportService} has written the crosswalk since the migration module
 * shipped, and the reconciliation runner has recorded per-rule results since
 * {@code ReconciliationService} did — only the read was never reachable from
 * a console. Both stores are injected directly rather than through {@code
 * MigrationReconciliationStore}, which {@link
 * uz.horecaos.platform.migration.application.MigrationReconciliationStore}'s
 * own javadoc documents as deliberately narrow to the cutover gate's single
 * question; widening that port for a diagnostics screen would break the
 * contract it exists to keep small. This follows the precedent {@code
 * CommercialControlPlaneController} already set, referencing {@code
 * JdbcUsageStore}'s row type directly for exactly this reason.
 *
 * <p>Single page, no continuation: like {@code FailureOperationsController}'s
 * dead-letter reads, this is a diagnostics screen capped at {@code limit}
 * rather than a browsable archive, so there is no cursor to encode.
 */
@RestController
@RequestMapping("/api/v1/platform-admin/migration")
@Tag(name = "Migration evidence", description = "Entity-mapping crosswalk and reconciliation results (ADR 0024)")
public class MigrationEvidenceController {

    private final JdbcEntityMappingStore entityMappings;
    private final JdbcReconciliationStore reconciliation;

    public MigrationEvidenceController(JdbcEntityMappingStore entityMappings, JdbcReconciliationStore reconciliation) {
        this.entityMappings = entityMappings;
        this.reconciliation = reconciliation;
    }

    @GetMapping("/scopes/{scopeId}/entity-mappings")
    @RequiresCapability(value = Capability.MIGRATION_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "One scope's legacy-to-target crosswalk, for one entity type",
            description = "Oldest first, capped at limit. QUARANTINED rows are included: a mapping "
                    + "that could not be made is still evidence that the legacy row was seen.")
    Page<EntityMappingResponse> entityMappings(
            @PathVariable UUID scopeId,
            @RequestParam UUID tenantId,
            @RequestParam String entityType,
            @RequestParam(required = false) @Nullable Integer limit) {

        int pageSize = Page.limitOrDefault(limit);
        List<EntityMappingRow> rows = entityMappings.listForScope(tenantId, scopeId, entityType, null, pageSize);
        return Page.last(rows.stream().map(EntityMappingResponse::of).toList());
    }

    @GetMapping("/runs/{runId}/reconciliation-results")
    @RequiresCapability(value = Capability.MIGRATION_READ, scope = ScopeType.PLATFORM)
    @Operation(
            summary = "One reconciliation run's per-rule comparisons, most recent first",
            description = "The dual-run diff itself: what each rule expected from the legacy source "
                    + "against what it found in HorecaOS, by dimension, with the settlement state "
                    + "of every difference found.")
    Page<ReconciliationResultResponse> reconciliationResults(
            @PathVariable UUID runId,
            @RequestParam UUID tenantId,
            @RequestParam(required = false) @Nullable Integer limit) {

        int pageSize = Page.limitOrDefault(limit);
        List<ReconciliationResultRow> rows = reconciliation.listForRun(tenantId, runId, null, pageSize);
        return Page.last(rows.stream().map(ReconciliationResultResponse::of).toList());
    }

    /** One legacy identity's crosswalk entry. */
    public record EntityMappingResponse(
            UUID mappingId,
            String entityType,
            String legacyId,
            @Nullable UUID targetId,
            MappingStatus status,
            @Nullable UUID supersededByMappingId,
            UUID runId,
            Instant createdAt) {

        static EntityMappingResponse of(EntityMappingRow row) {
            return new EntityMappingResponse(
                    row.mappingId(),
                    row.entityType(),
                    row.legacyId(),
                    row.targetId(),
                    row.status(),
                    row.supersededByMappingId(),
                    row.runId(),
                    row.createdAt());
        }
    }

    /** One rule's comparison over one dimension. */
    public record ReconciliationResultResponse(
            UUID resultId,
            String ruleCode,
            int ruleVersion,
            String dimensionKey,
            ReconciliationSeverity severity,
            String measureKind,
            @Nullable BigInteger expected,
            @Nullable BigInteger actual,
            @Nullable BigInteger difference,
            ReconciliationStatus status,
            @Nullable String approvedBy,
            @Nullable Instant resolvedAt) {

        static ReconciliationResultResponse of(ReconciliationResultRow row) {
            return new ReconciliationResultResponse(
                    row.resultId(),
                    row.ruleCode(),
                    row.ruleVersion(),
                    row.dimensionKey(),
                    row.severity(),
                    row.measure().kind(),
                    row.measure().expected(),
                    row.measure().actual(),
                    row.difference(),
                    row.status(),
                    row.approvedBy(),
                    row.resolvedAt());
        }
    }
}
