package uz.horecaos.platform.inventory.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.inventory.application.StopMaterialisationService;
import uz.horecaos.platform.inventory.application.StopMaterialisationService.RunInProgressException;
import uz.horecaos.platform.inventory.application.StopMaterialisationService.RunNotFoundException;
import uz.horecaos.platform.inventory.application.StopMaterialisationService.StaleRunException;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcStopMaterialisationStore.LineRow;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcStopMaterialisationStore.RunRow;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The materialisation run that precedes the decommission of stops, and the acknowledgement of its
 * report (ADR 0141, "Rollback: freeze, do not disable", switch three).
 *
 * <p>Brand scope throughout: a run is of one brand, its report is the brand's, and it is
 * acknowledged by a holder of {@link Capability#INVENTORY_STOP_MANAGE} at that brand -- the same
 * grant that makes a brand-wide stop, so the person who accepts that those dishes will be on sale
 * again is a person who could have stopped them. The switch itself ({@code
 * inventory.stops.read_enabled}) is an ordinary ADR 0030 configuration write, which is refused
 * with {@code 409 RESOURCE_CONFLICT {conflict: MATERIALISATION_REQUIRED}} until a run has been
 * acknowledged and no stop has been made since.
 *
 * <p>The report carries identifiers and stable codes only; the console resolves dish and branch
 * names through the authorized catalog and tenancy APIs, so no name is copied into a table that
 * outlives the dish (ADR 0029).
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/inventory/stop-materialisation-runs")
@Tag(
        name = "Inventory stop decommission",
        description = "The materialisation run and its report, before stops are switched off (ADR 0141)")
public class StopMaterialisationController {

    private final StopMaterialisationService runs;
    private final CurrentActor currentActor;

    public StopMaterialisationController(StopMaterialisationService runs, CurrentActor currentActor) {
        this.runs = runs;
        this.currentActor = currentActor;
    }

    @PostMapping
    @RequiresCapability(value = Capability.INVENTORY_STOP_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Materialise the brand's stops onto positions and report what could not be carried",
            description = "ADR 0141, rollback switch three. Writes every stop in force that has an exact "
                    + "position onto it (binary_available = false on a BINARY item, the stop's own source, "
                    + "movement reason EMBARGO_MATERIALISED) and reports the rest: UNTRACKED and QUANTITY items, "
                    + "CHANNEL stops and menus published to only some channels of a branch. Runs to completion "
                    + "in the request; repeatable. 409 RUN_IN_PROGRESS while another run of this brand is going.")
    public ResponseEntity<RunResponse> run(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        try {
            RunRow row = runs.run(tenantId, brandId, currentActor.get().subject());
            return ResponseEntity.status(201)
                    .eTag(AggregateVersion.toETag(row.version()))
                    .body(RunResponse.of(row));
        } catch (RunInProgressException running) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, running.getMessage(), Map.of("conflict", "RUN_IN_PROGRESS"));
        }
    }

    @PostMapping("/{runId}/acknowledgement")
    @RequiresCapability(value = Capability.INVENTORY_STOP_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Acknowledge a run's report",
            description = "Requires If-Match carrying the run's version. The acknowledgement says the owner "
                    + "has read what could not be carried and accepts that those dishes will be on sale "
                    + "again once stops are switched off. Only a finished run, and only once.")
    public ResponseEntity<RunResponse> acknowledge(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID runId,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        try {
            RunRow row = runs.acknowledge(
                    tenantId, brandId, runId, (int) expected, currentActor.get().subject());
            return ResponseEntity.ok()
                    .eTag(AggregateVersion.toETag(row.version()))
                    .body(RunResponse.of(row));
        } catch (RunNotFoundException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such materialisation run");
        } catch (StaleRunException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        }
    }

    @GetMapping
    @RequiresCapability(value = Capability.INVENTORY_READ, scope = ScopeType.BRAND)
    @Operation(summary = "The brand's materialisation runs", description = "Newest first. limit 1 to 200, default 50.")
    public Page<RunResponse> list(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(required = false) @Nullable Integer limit) {
        List<RunResponse> items = runs.list(tenantId, brandId, Page.limitOrDefault(limit)).stream()
                .map(RunResponse::of)
                .toList();
        return Page.last(items);
    }

    @GetMapping("/{runId}")
    @RequiresCapability(value = Capability.INVENTORY_READ, scope = ScopeType.BRAND)
    @Operation(summary = "One materialisation run")
    public ResponseEntity<RunResponse> get(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID runId) {
        try {
            RunRow row = runs.get(tenantId, brandId, runId);
            return ResponseEntity.ok()
                    .eTag(AggregateVersion.toETag(row.version()))
                    .body(RunResponse.of(row));
        } catch (RunNotFoundException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such materialisation run");
        }
    }

    @GetMapping("/{runId}/report")
    @RequiresCapability(value = Capability.INVENTORY_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "What the run could not carry",
            description = "The report the owner acknowledges: one line per stop and location that did not land "
                    + "on a position, with a stable reason code. Identifiers and codes only. Cursor-paged; "
                    + "cursor is the id of the last line seen.")
    public Page<ReportLineResponse> report(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID runId,
            @RequestParam(required = false) @Nullable UUID cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        int pageSize = Page.limitOrDefault(limit);
        try {
            List<LineRow> rows = runs.report(tenantId, brandId, runId, cursor, pageSize);
            List<ReportLineResponse> items =
                    rows.stream().map(ReportLineResponse::of).toList();
            String next = rows.size() < pageSize
                    ? null
                    : rows.get(rows.size() - 1).id().toString();
            return new Page<>(items, next);
        } catch (RunNotFoundException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such materialisation run");
        }
    }

    /**
     * One run. {@code positionsWritten} and {@code positionsAlreadyUnavailable} are stops that landed
     * on a position; {@code notCarried} is the number of report lines (failed stops included);
     * {@code failedStops} are stops whose write failed and which therefore keep the switch blocked.
     */
    public record RunResponse(
            UUID id,
            String status,
            Instant startedAt,
            @Nullable Instant completedAt,
            int stopsSeen,
            int positionsWritten,
            int positionsAlreadyUnavailable,
            int notCarried,
            int failedStops,
            boolean acknowledged,
            @Nullable Instant acknowledgedAt,
            int version) {

        static RunResponse of(RunRow row) {
            return new RunResponse(
                    row.id(),
                    row.status(),
                    row.startedAt(),
                    row.completedAt(),
                    row.stopsSeen(),
                    row.positionsWritten(),
                    row.positionsAlreadyUnavailable(),
                    row.notCarried(),
                    row.failedStops(),
                    row.acknowledgedAt() != null,
                    row.acknowledgedAt(),
                    row.version());
        }
    }

    /** One stop (at one location) that did not land on a position, and why. */
    public record ReportLineResponse(
            UUID id,
            UUID stopId,
            UUID variantId,
            String scopeType,
            String source,
            @Nullable UUID locationId,
            @Nullable UUID channelId,
            @Nullable UUID menuId,
            String reasonCode) {

        static ReportLineResponse of(LineRow row) {
            return new ReportLineResponse(
                    row.id(),
                    row.stopId(),
                    row.variantId(),
                    row.scopeType(),
                    row.source(),
                    row.locationId(),
                    row.channelId(),
                    row.menuId(),
                    row.reasonCode());
        }
    }
}
