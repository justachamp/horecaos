package uz.horecaos.platform.inventory.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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
import uz.horecaos.platform.inventory.api.StopScopeType;
import uz.horecaos.platform.inventory.api.StopSource;
import uz.horecaos.platform.inventory.application.AvailabilityStopService;
import uz.horecaos.platform.inventory.application.AvailabilityStopService.StaleStopException;
import uz.horecaos.platform.inventory.application.AvailabilityStopService.StopOutcome;
import uz.horecaos.platform.inventory.application.AvailabilityStopService.StopTargetNotFoundException;
import uz.horecaos.platform.inventory.application.AvailabilityStopService.StopsFrozenException;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.inventory.application.InventoryStopGestureService;
import uz.horecaos.platform.inventory.application.InventoryStopGestureService.Gesture;
import uz.horecaos.platform.inventory.application.InventoryStopGestureService.GestureResult;
import uz.horecaos.platform.inventory.application.InventoryStopGestureService.ItemOutcome;
import uz.horecaos.platform.inventory.application.StopReadSwitch;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcAvailabilityStopStore.StopRow;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.api.Page;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Stops with a scope, a source and an optional end (ADR 0141, gap map row 2.5a).
 *
 * <p>Two create routes, because one endpoint can declare only one capability scope and a
 * location manager must not be able to take a dish off sale across the brand: the
 * <em>brand</em> route ({@link Capability#INVENTORY_STOP_MANAGE} at {@code BRAND}) writes
 * {@code BRAND}, {@code MENU} and {@code CHANNEL}-everywhere stops; the <em>location</em>
 * route ({@link Capability#INVENTORY_AVAILABILITY_MANAGE} at {@code LOCATION}, the grant a
 * branch manager already has) writes {@code LOCATION} and {@code CHANNEL}-at-this-branch
 * stops. Each has its own lift, and the location one can lift only what touches that branch
 * alone.
 *
 * <p>Every mutation carries an {@code Idempotency-Key} (ADR 0031) and a lift carries {@code
 * If-Match}. Reasons are short enumerated codes, never free text (ADR 0029): they are kept
 * permanently in the audit trail and published on {@code inventory.events}.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}")
@Tag(name = "Inventory stops", description = "Stops with a scope, a source and an optional end (ADR 0141)")
public class InventoryStopController {

    private final InventoryStopGestureService gestures;
    private final AvailabilityStopService stops;
    private final InventoryService inventory;
    private final CurrentActor currentActor;
    private final StopReadSwitch readSwitch;

    public InventoryStopController(
            InventoryStopGestureService gestures,
            AvailabilityStopService stops,
            InventoryService inventory,
            CurrentActor currentActor,
            StopReadSwitch readSwitch) {
        this.gestures = gestures;
        this.stops = stops;
        this.inventory = inventory;
        this.currentActor = currentActor;
        this.readSwitch = readSwitch;
    }

    // ------------------------------------------------------------------ create

    @PostMapping("/inventory/stops")
    @RequiresCapability(value = Capability.INVENTORY_STOP_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Stop dishes across the brand, a menu or a channel",
            description = "ADR 0141. Scope BRAND stops every branch of the brand, including one bound "
                    + "later; MENU stops every (branch, channel) that publishes from the menu; CHANNEL "
                    + "stops one sales channel at every branch it runs. Name variantIds (at most 200) or "
                    + "a productId, which expands to its variants under one group. Each variant is "
                    + "applied independently and reported with its own outcome. The union of covering "
                    + "stops stops the sale; a branch cannot override a brand stop. reasonCode is an "
                    + "enumerated code, never free text. endsAt or untilEndOfTradingDay is optional; "
                    + "omitted, the stop is indefinite. 409 STOPS_FROZEN when new stops are paused.")
    public ResponseEntity<StopGestureResponse> stopAtBrand(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody CreateStopRequest body) {
        if (body.scope() != StopScopeType.BRAND
                && body.scope() != StopScopeType.MENU
                && body.scope() != StopScopeType.CHANNEL) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "This route stops at BRAND, MENU or CHANNEL scope; a LOCATION stop is made at the branch");
        }
        return ResponseEntity.ok(apply(tenantId, brandId, null, body, Capability.INVENTORY_STOP_MANAGE));
    }

    @PostMapping("/locations/{locationId}/inventory/stops")
    @RequiresCapability(value = Capability.INVENTORY_AVAILABILITY_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Stop dishes at one branch, or one channel at one branch",
            description = "ADR 0141. Scope LOCATION stops every channel at this branch; CHANNEL stops one "
                    + "sales channel here. Unlike the position toggle this also stops an UNTRACKED or "
                    + "QUANTITY dish. Same body, per-item outcomes and refusals as the brand route.")
    public ResponseEntity<StopGestureResponse> stopAtLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody CreateStopRequest body) {
        if (body.scope() != StopScopeType.LOCATION && body.scope() != StopScopeType.CHANNEL) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "This route stops at LOCATION or CHANNEL scope; a brand-wide stop needs inventory.stop.manage");
        }
        return ResponseEntity.ok(apply(tenantId, brandId, locationId, body, Capability.INVENTORY_AVAILABILITY_MANAGE));
    }

    private StopGestureResponse apply(
            UUID tenantId, UUID brandId, @Nullable UUID locationId, CreateStopRequest body, Capability capability) {
        try {
            GestureResult result = gestures.apply(new Gesture(
                    tenantId,
                    brandId,
                    body.scope(),
                    locationId,
                    body.menuId(),
                    body.channelId(),
                    body.variantIds(),
                    body.productId(),
                    body.reasonCode(),
                    body.endsAt(),
                    Boolean.TRUE.equals(body.untilEndOfTradingDay()),
                    StopSource.OPERATOR,
                    currentActor.get().subject(),
                    capability));
            return StopGestureResponse.of(result);
        } catch (StopsFrozenException frozen) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, frozen.getMessage(), Map.of("conflict", "STOPS_FROZEN"));
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
        }
    }

    // ------------------------------------------------------------------ lift

    @DeleteMapping("/inventory/stops/{stopId}")
    @RequiresCapability(value = Capability.INVENTORY_STOP_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Lift a stop",
            description = "Requires If-Match carrying the stop's version. Any stop of the brand, whatever "
                    + "its scope or source. Lifting an already-lifted stop is a no-op that returns it.")
    public ResponseEntity<StopResponse> liftAtBrand(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID stopId,
            HttpServletRequest request) {
        return lift(tenantId, brandId, stopId, null, request, Capability.INVENTORY_STOP_MANAGE);
    }

    @DeleteMapping("/locations/{locationId}/inventory/stops/{stopId}")
    @RequiresCapability(value = Capability.INVENTORY_AVAILABILITY_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Lift a stop that touches this branch alone",
            description = "Requires If-Match. A LOCATION stop here, or a CHANNEL stop at this branch; a "
                    + "brand-wide, menu or all-branches channel stop answers 404 here and is lifted "
                    + "through the brand route by someone holding inventory.stop.manage.")
    public ResponseEntity<StopResponse> liftAtLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID stopId,
            HttpServletRequest request) {
        return lift(tenantId, brandId, stopId, locationId, request, Capability.INVENTORY_AVAILABILITY_MANAGE);
    }

    private ResponseEntity<StopResponse> lift(
            UUID tenantId,
            UUID brandId,
            UUID stopId,
            @Nullable UUID locationId,
            HttpServletRequest request,
            Capability capability) {
        long expected = AggregateVersion.requireIfMatch(request);
        try {
            StopOutcome outcome = stops.lift(
                    tenantId,
                    brandId,
                    stopId,
                    (int) expected,
                    currentActor.get().subject(),
                    locationId,
                    capability);
            return ResponseEntity.ok()
                    .eTag(AggregateVersion.toETag(outcome.stop().version()))
                    .body(StopResponse.of(outcome.stop(), !readSwitch.readsEnabled(tenantId, brandId)));
        } catch (StopTargetNotFoundException absent) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such stop");
        } catch (StaleStopException stale) {
            throw ApiException.staleVersion(stale.expected(), stale.actual());
        }
    }

    // ------------------------------------------------------------------ reads

    @GetMapping("/inventory/stops")
    @RequiresCapability(value = Capability.INVENTORY_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The brand's stops",
            description = "Newest first. By default only stops in force now; activeOnly=false includes "
                    + "the lifted and expired history. Narrow by variantId, scope and source.")
    public Page<StopResponse> listAtBrand(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(required = false) @Nullable UUID variantId,
            @RequestParam(required = false) @Nullable StopScopeType scope,
            @RequestParam(required = false) @Nullable StopSource source,
            @RequestParam(required = false, defaultValue = "true") boolean activeOnly,
            @RequestParam(required = false) @Nullable UUID cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        return list(tenantId, brandId, variantId, scope, source, null, activeOnly, cursor, limit);
    }

    @GetMapping("/locations/{locationId}/inventory/stops")
    @RequiresCapability(value = Capability.INVENTORY_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The stops that touch this branch",
            description = "The branch's own LOCATION and CHANNEL stops and the brand-wide ones that reach "
                    + "it. Same filters and paging as the brand read.")
    public Page<StopResponse> listAtLocation(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam(required = false) @Nullable UUID variantId,
            @RequestParam(required = false) @Nullable StopScopeType scope,
            @RequestParam(required = false) @Nullable StopSource source,
            @RequestParam(required = false, defaultValue = "true") boolean activeOnly,
            @RequestParam(required = false) @Nullable UUID cursor,
            @RequestParam(required = false) @Nullable Integer limit) {
        return list(tenantId, brandId, variantId, scope, source, locationId, activeOnly, cursor, limit);
    }

    private Page<StopResponse> list(
            UUID tenantId,
            UUID brandId,
            @Nullable UUID variantId,
            @Nullable StopScopeType scope,
            @Nullable StopSource source,
            @Nullable UUID locationId,
            boolean activeOnly,
            @Nullable UUID cursor,
            @Nullable Integer limit) {
        int pageSize = Page.limitOrDefault(limit);
        List<StopRow> rows =
                stops.list(tenantId, brandId, variantId, scope, source, locationId, activeOnly, cursor, pageSize);
        boolean ignored = !readSwitch.readsEnabled(tenantId, brandId);
        List<StopResponse> items =
                rows.stream().map(row -> StopResponse.of(row, ignored)).toList();
        String nextCursor =
                items.size() < pageSize ? null : rows.get(rows.size() - 1).id().toString();
        return new Page<>(items, nextCursor);
    }

    @GetMapping("/locations/{locationId}/inventory/variants/{variantId}/availability-explanation")
    @RequiresCapability(value = Capability.INVENTORY_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Why can't I sell this?",
            description = "ADR 0141 Decision 1: whether the dish is sellable at this branch on the named "
                    + "channel (omitted: no channel, so only the stops covering every channel apply), the "
                    + "reasons (ON_STOP, SOLD_OUT, CHANNEL_STOPPED, NOT_STOCKED_AT_LOCATION), and every "
                    + "stop that covers it, not the first.")
    public ResponseEntity<ExplanationResponse> explain(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID variantId,
            @RequestParam(required = false) @Nullable String channel) {
        InventoryService.Explanation explanation =
                inventory.explainOnChannelCode(tenantId, brandId, locationId, variantId, channel);
        boolean consulted = readSwitch.readsEnabled(tenantId, brandId);
        return ResponseEntity.ok(new ExplanationResponse(
                explanation.sellable(),
                explanation.reasons(),
                explanation.coveringStops().stream()
                        .map(row -> StopResponse.of(row, !consulted))
                        .toList(),
                consulted));
    }

    // ------------------------------------------------------------------ bodies

    /**
     * Exactly one of {@code variantIds} and {@code productId}. {@code reasonCode} is a short
     * enumerated code — never free text an operator typed (ADR 0029). Every optional field is
     * boxed: an omitted primitive is a 400 under Jackson 3.
     *
     * @param menuId   required for scope MENU
     * @param channelId required for scope CHANNEL
     * @param endsAt   an explicit end, in the future; mutually exclusive with {@code untilEndOfTradingDay}
     */
    public record CreateStopRequest(
            @Nullable @Size(max = InventoryStopGestureService.MAX_ITEMS)
            List<UUID> variantIds,

            @Nullable UUID productId,
            @NotNull StopScopeType scope,
            @Nullable UUID menuId,
            @Nullable UUID channelId,

            @NotBlank @Pattern(regexp = "^[A-Z_]{1,48}$") @Size(max = 64)
            String reasonCode,

            @Nullable Instant endsAt,
            @Nullable Boolean untilEndOfTradingDay) {}

    public record StopResponse(
            UUID id,
            UUID variantId,
            String scopeType,
            @Nullable UUID locationId,
            @Nullable UUID menuId,
            @Nullable UUID channelId,
            String source,
            String reasonCode,
            @Nullable Instant endsAt,
            String status,
            @Nullable UUID groupId,
            Instant createdAt,
            @Nullable Instant liftedAt,
            int version,
            boolean ignored) {

        /** A stop that is read: the usual case. */
        static StopResponse of(StopRow row) {
            return of(row, false);
        }

        /**
         * @param ignored true once stops have been decommissioned for the brand (ADR 0141, rollback
         *     switch three): the row stays and nothing reads it, until stops are switched back on
         */
        static StopResponse of(StopRow row, boolean ignored) {
            return new StopResponse(
                    row.id(),
                    row.variantId(),
                    row.scopeType().name(),
                    row.locationId(),
                    row.menuId(),
                    row.channelId(),
                    row.source().name(),
                    row.reasonCode(),
                    row.endsAt(),
                    row.status(),
                    row.groupId(),
                    row.createdAt(),
                    row.liftedAt(),
                    row.version(),
                    ignored);
        }
    }

    public record StopItemOutcome(
            UUID variantId,
            String status,
            boolean changed,
            @Nullable UUID stopId,
            @Nullable String problemCode) {

        static StopItemOutcome of(ItemOutcome outcome) {
            return new StopItemOutcome(
                    outcome.variantId(),
                    outcome.status().name(),
                    outcome.changed(),
                    outcome.stopId(),
                    outcome.problemCode());
        }
    }

    public record StopGestureResponse(
            UUID groupId, int requestedCount, int appliedCount, int failedCount, List<StopItemOutcome> items) {

        static StopGestureResponse of(GestureResult result) {
            long applied = result.items().stream()
                    .filter(item -> item.status() == InventoryStopGestureService.ItemStatus.APPLIED)
                    .count();
            return new StopGestureResponse(
                    result.groupId(),
                    result.items().size(),
                    (int) applied,
                    result.items().size() - (int) applied,
                    result.items().stream().map(StopItemOutcome::of).toList());
        }
    }

    /**
     * @param stopsConsulted false once stops are switched off for the brand (ADR 0141, rollback
     *     switch three): then {@code stops} is empty because nothing reads a stop, not because
     *     none exists
     */
    public record ExplanationResponse(
            boolean sellable, List<String> reasons, List<StopResponse> stops, boolean stopsConsulted) {}
}
