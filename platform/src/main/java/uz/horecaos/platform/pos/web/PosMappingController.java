package uz.horecaos.platform.pos.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
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
import uz.horecaos.platform.integration.api.provider.MappingEntityType;
import uz.horecaos.platform.pos.application.PosMappingService;
import uz.horecaos.platform.pos.application.PosMappingService.BulkAutoMatchResult;
import uz.horecaos.platform.pos.application.PosMappingService.CreateOutcome;
import uz.horecaos.platform.pos.application.PosMappingService.MatchConflict;
import uz.horecaos.platform.pos.application.PosMappingService.RetireOutcome;
import uz.horecaos.platform.pos.application.PosMappingService.UnmappedResult;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosMappingStore;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosMappingStore.ExternalCandidate;
import uz.horecaos.platform.pos.infrastructure.persistence.JdbcPosMappingStore.MappingRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The tenant-facing mapping pane over {@code integration.provider_entity_mappings}
 * (ADR 0012/0026, gap-map rows 10.8b and X.24).
 *
 * <p>Reads are {@link Capability#POS_SYNC_READ}; every mutation here is {@link
 * Capability#POS_SYNC_MANAGE} — deliberately not {@link Capability#POS_SYNC_APPLY},
 * which accepts what a sync run's own reviewed difference report found. Pairing
 * a payment type, a discount, a courier, a cancellation reason or a channel's
 * POS code by hand has no run to review against; see that capability's own
 * Javadoc for the full argument.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/pos-mappings")
@Tag(name = "POS entity mappings", description = "Pairing HorecaOS's own records with a provider's external ids")
public class PosMappingController {

    private final PosMappingService service;
    private final JdbcPosMappingStore store;
    private final AuditRecorder audit;
    private final CurrentActor currentActor;
    private final Clock clock;

    public PosMappingController(
            PosMappingService service,
            JdbcPosMappingStore store,
            AuditRecorder audit,
            CurrentActor currentActor,
            Clock clock) {
        this.service = service;
        this.store = store;
        this.audit = audit;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    @GetMapping
    @RequiresCapability(Capability.POS_SYNC_READ)
    @Operation(
            summary = "A binding's mappings for one entity type",
            description =
                    "Optionally filtered by status (PROPOSED, ACTIVE, CONFLICTED, RETIRED). " + "Newest-updated first.")
    Page<MappingView> list(
            @PathVariable UUID tenantId,
            @RequestParam UUID bindingId,
            @RequestParam MappingEntityType entityType,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) String cursor) {

        int size = Page.limitOrDefault(limit);
        List<MappingRow> rows = store.list(tenantId, bindingId, entityType, status, size, cursor);
        List<MappingView> views =
                rows.stream().map(PosMappingController::toView).toList();
        String nextCursor = rows.size() < size ? null : JdbcPosMappingStore.cursorFor(rows.getLast());
        return new Page<>(views, nextCursor);
    }

    @GetMapping("/unmapped")
    @RequiresCapability(Capability.POS_SYNC_READ)
    @Operation(
            summary = "The provider's own candidates for this type, not yet mapped",
            description = "sourced=false means this build has no way to read the provider's list for "
                    + "this entity type yet (gap-map row 10.8b) — creating a mapping by typing the "
                    + "provider's own code still works.")
    UnmappedExternalResponse unmapped(
            @PathVariable UUID tenantId, @RequestParam UUID bindingId, @RequestParam MappingEntityType entityType) {

        UnmappedResult result = service.listUnmappedExternal(tenantId, bindingId, entityType);
        return new UnmappedExternalResponse(
                result.sourced(),
                result.detail(),
                result.entities().stream()
                        .map(PosMappingController::toUnmappedView)
                        .toList());
    }

    @PostMapping
    @RequiresCapability(value = Capability.POS_SYNC_MANAGE, mutating = true)
    @Operation(
            summary = "Pair one HorecaOS record with the provider's external id by hand",
            description = "OPERATOR-sourced (V0013's CHECK has always allowed it; nothing wrote it "
                    + "before this). Refused with RESOURCE_CONFLICT when an ACTIVE mapping already "
                    + "claims either side, or when a RETIRED row already burned this external or "
                    + "HorecaOS id for this binding and type — see PosMappingService.create's own doc.")
    MappingCreated create(@PathVariable UUID tenantId, @Valid @RequestBody CreateMappingRequest request) {

        CreateOutcome outcome = service.create(
                tenantId,
                request.bindingId(),
                request.entityType(),
                request.horecaosEntityId(),
                request.externalEntityId(),
                request.externalParentId());

        UUID mappingId =
                switch (outcome.kind()) {
                    case CREATED -> outcome.mappingId();
                    case CONFLICT -> throw new ApiException(ErrorCode.RESOURCE_CONFLICT, outcome.detail());
                    case NOT_FOUND -> throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, outcome.detail());
                };
        UUID createdId = Objects.requireNonNull(mappingId, "CREATED always carries a mappingId");

        audit.record(AuditFact.of("pos.mapping_created", AuditClass.BUSINESS)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.tenant(tenantId))
                .target("ProviderEntityMapping", createdId)
                .because("Operator-sourced mapping")
                .changed(Map.of(
                        "bindingId", request.bindingId().toString(),
                        "entityType", request.entityType().name(),
                        "mappingSource", "OPERATOR"))
                .usingCapability(Capability.POS_SYNC_MANAGE.code())
                .correlatedBy(createdId.toString())
                .occurredAt(clock.instant())
                .build());

        return new MappingCreated(createdId, "ACTIVE");
    }

    @PostMapping("/{mappingId}/retire")
    @RequiresCapability(value = Capability.POS_SYNC_MANAGE, mutating = true)
    @Operation(
            summary = "Retire a mapping",
            description = "Terminal: V0013's unique constraints are table-wide, not ACTIVE-only, so "
                    + "this external id and this HorecaOS id are burned for this binding and type "
                    + "afterward, the same as a sync run's own retirement.")
    MappingRetired retire(
            @PathVariable UUID tenantId,
            @PathVariable UUID mappingId,
            @Valid @RequestBody RetireMappingRequest request) {

        RetireOutcome outcome = service.retire(tenantId, mappingId, request.expectedVersion());
        switch (outcome.kind()) {
            case RETIRED -> {}
            case STALE_VERSION ->
                throw new ApiException(ErrorCode.STALE_VERSION, "This mapping changed since it was read");
            case NOT_FOUND -> throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such mapping");
        }

        audit.record(AuditFact.of("pos.mapping_retired", AuditClass.BUSINESS)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.tenant(tenantId))
                .target("ProviderEntityMapping", mappingId)
                .because("Operator retired mapping")
                .changed(Map.of("status", "RETIRED"))
                .usingCapability(Capability.POS_SYNC_MANAGE.code())
                .correlatedBy(mappingId.toString())
                .occurredAt(clock.instant())
                .build());

        return new MappingRetired(mappingId, "RETIRED");
    }

    @PostMapping("/bulk-auto-match")
    @RequiresCapability(value = Capability.POS_SYNC_MANAGE, mutating = true)
    @Operation(
            summary = "Match every unambiguous name pair and create an OPERATOR mapping for each",
            description = "Two or more candidates sharing one name on either side are never resolved "
                    + "by picking one — they come back as a conflict for the two-sided conflict card, "
                    + "never a last-write-wins guess.")
    BulkAutoMatchResponse bulkAutoMatch(@PathVariable UUID tenantId, @Valid @RequestBody BulkAutoMatchRequest request) {

        BulkAutoMatchResult result = service.bulkAutoMatch(tenantId, request.bindingId(), request.entityType());

        audit.record(AuditFact.of("pos.mapping_bulk_auto_matched", AuditClass.BUSINESS)
                .by(ActorRef.user(currentActor.get().subject(), null))
                .at(ResourceScope.tenant(tenantId))
                .target("PosBinding", request.bindingId())
                .because("Bulk auto-match by name")
                .changed(Map.of(
                        "entityType", request.entityType().name(),
                        "matchedCount", Integer.toString(result.matchedCount()),
                        "conflictCount", Integer.toString(result.conflicts().size())))
                .usingCapability(Capability.POS_SYNC_MANAGE.code())
                .correlatedBy(request.bindingId().toString())
                .occurredAt(clock.instant())
                .build());

        return new BulkAutoMatchResponse(
                result.sourced(),
                result.detail(),
                result.matchedCount(),
                result.conflicts().stream()
                        .map(PosMappingController::toConflictView)
                        .toList());
    }

    private static MappingView toView(MappingRow row) {
        return new MappingView(
                row.id(),
                row.bindingId(),
                row.entityType(),
                row.horecaosEntityId(),
                row.externalEntityId(),
                row.externalParentId(),
                row.status(),
                row.mappingSource(),
                row.lastSeenAt(),
                row.version(),
                row.updatedAt());
    }

    private static UnmappedExternalView toUnmappedView(ExternalCandidate candidate) {
        return new UnmappedExternalView(candidate.externalId(), candidate.name(), candidate.externalParentId());
    }

    private static MappingConflictView toConflictView(MatchConflict conflict) {
        return new MappingConflictView(conflict.name(), conflict.externalIds(), conflict.horecaosEntityIds());
    }

    public record CreateMappingRequest(
            @NotNull UUID bindingId,
            @NotNull MappingEntityType entityType,
            @NotNull UUID horecaosEntityId,
            @NotNull String externalEntityId,
            @Nullable String externalParentId) {}

    public record RetireMappingRequest(@NotNull Long expectedVersion) {}

    public record BulkAutoMatchRequest(
            @NotNull UUID bindingId, @NotNull MappingEntityType entityType) {}

    public record MappingCreated(UUID mappingId, String status) {}

    public record MappingRetired(UUID mappingId, String status) {}

    /** One mapping, as the API exposes it. Never the linked HorecaOS row's own name — that is a separate, capability-gated read. */
    public record MappingView(
            UUID mappingId,
            UUID bindingId,
            String entityType,
            UUID horecaosEntityId,
            String externalEntityId,
            @Nullable String externalParentId,
            String status,
            String mappingSource,
            @Nullable Instant lastSeenAt,
            long version,
            Instant updatedAt) {}

    /** One provider-side candidate the mapping pane's right-hand list shows. */
    public record UnmappedExternalView(
            String externalId,
            @Nullable String name,
            @Nullable String externalParentId) {}

    public record UnmappedExternalResponse(
            boolean sourced, @Nullable String detail, List<UnmappedExternalView> entities) {}

    /** The two-sided conflict card's own data: a shared name and every candidate id on each side. */
    public record MappingConflictView(String name, List<String> externalIds, List<UUID> horecaosEntityIds) {}

    public record BulkAutoMatchResponse(
            boolean sourced, @Nullable String detail, int matchedCount, List<MappingConflictView> conflicts) {}
}
