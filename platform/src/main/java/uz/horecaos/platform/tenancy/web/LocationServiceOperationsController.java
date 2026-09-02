package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.BrandId;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.LocationId;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.application.ServiceScheduleService;
import uz.horecaos.platform.tenancy.application.TenantControlPlaneService;
import uz.horecaos.platform.tenancy.application.TenantControlPlaneService.LocationView;
import uz.horecaos.platform.tenancy.domain.channel.ServiceMode;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcServiceabilityStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * What a branch does to itself during service (ADR 0036).
 *
 * <p>Everything here is {@code LOCATION} scope, which is the point rather than an
 * implementation detail: a branch manager closes their own branch when the fryer
 * dies without holding any authority over the rest of the network.
 *
 * <p>ADR 0036 writes these paths as {@code /api/v1/operations/locations/...}.
 * They carry the tenant and brand here because a {@code LOCATION}-scoped
 * capability is resolved from the {@code tenantId}, {@code brandId} and
 * {@code locationId} path variables — a bare location id would leave the
 * authorization decision with nothing to resolve against.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/locations/{locationId}")
@Tag(
        name = "Location service operations",
        description = "Manual open/closed state, capacity, and " + "preparation bands")
public class LocationServiceOperationsController {

    private final ServiceScheduleService schedules;
    private final TenantControlPlaneService tenants;

    public LocationServiceOperationsController(ServiceScheduleService schedules, TenantControlPlaneService tenants) {
        this.schedules = schedules;
        this.tenants = tenants;
    }

    @GetMapping
    @RequiresCapability(value = Capability.LOCATION_READ, scope = ScopeType.LOCATION)
    @Operation(summary = "The location's own profile, for the Settings 10.2 detail screen")
    public LocationView profile(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        return tenants.getLocation(new TenantId(tenantId), new BrandId(brandId), new LocationId(locationId));
    }

    /**
     * Everything the Settings 10.2 "Часы" and "Загрузка и приготовление" tabs
     * show before a person opens an editor: the manual override (if any), the
     * timetable bound to each fulfilment mode with its full weekly grid and
     * dated exceptions, the preparation bands, and how many orders are
     * currently holding capacity.
     *
     * <p>Every write this controller already offered — {@code /service-state},
     * {@code /capacity}, {@code /service-bindings}, {@code /preparation-bands}
     * — had no matching read anywhere. A screen that can only ever write is
     * the write-blind form the operations spec's §1.4 "Loading" state warns
     * against, so this one read composes what the four writers already
     * persist rather than adding a new table.
     */
    @GetMapping("/service-summary")
    @RequiresCapability(value = Capability.LOCATION_READ, scope = ScopeType.LOCATION)
    @Operation(summary = "Manual override, bound schedules with their grids, preparation bands, and live capacity")
    public ServiceSummaryResponse serviceSummary(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {

        JdbcServiceabilityStore.ServiceState state = schedules.currentState(tenantId, locationId);

        List<ModeBindingResponse> bindings = new ArrayList<>();
        for (FulfillmentMode mode : FulfillmentMode.values()) {
            Optional<JdbcServiceabilityStore.BoundSchedule> bound = schedules.scheduleFor(tenantId, locationId, mode);
            if (bound.isEmpty()) {
                continue;
            }
            Optional<JdbcServiceabilityStore.NamedSchedule> named =
                    schedules.scheduleDetail(tenantId, brandId, bound.get().scheduleId());
            named.ifPresent(schedule -> bindings.add(new ModeBindingResponse(
                    mode,
                    bound.get().scheduleId(),
                    schedule.name(),
                    schedule.schedule().acceptsScheduledOrders(),
                    schedule.boundLocationCount(),
                    schedule.schedule().rules().stream()
                            .map(rule -> new RuleResponse(rule.dayOfWeek(), rule.opensAt(), rule.closesAt()))
                            .toList(),
                    schedule.schedule().exceptions().entrySet().stream()
                            .map(entry -> new ExceptionResponse(
                                    entry.getKey(),
                                    entry.getValue().closedAllDay(),
                                    entry.getValue().opensAt(),
                                    entry.getValue().closesAt()))
                            .toList())));
        }

        List<BandResponse> bands = schedules.preparationBands(tenantId, locationId).stream()
                .map(band -> new BandResponse(
                        band.mode(),
                        band.dayOfWeek(),
                        band.startsAt(),
                        band.endsAt(),
                        band.durationMinutes(),
                        band.priority()))
                .toList();

        return new ServiceSummaryResponse(
                state.mode().name(),
                state.effectiveMode(Instant.now()).name(),
                state.reasonCode(),
                state.effectiveUntil(),
                state.maxConcurrentOrders(),
                schedules.openCapacityHolds(tenantId, locationId),
                bindings,
                bands);
    }

    @PostMapping("/service-state")
    @RequiresCapability(value = Capability.LOCATION_SERVICE_STATE_CHANGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Close, force open, or return to the schedule",
            description = "A manual override is never a bare boolean: it carries an actor, a "
                    + "reason code, and either an expiry or an explicit \"until I reopen it\". "
                    + "The failure that prevents is a branch closed at 19:00 for a broken fryer "
                    + "and still closed on Saturday.")
    public ResponseEntity<Void> changeServiceState(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody ServiceStateRequest body) {
        try {
            schedules.changeServiceState(
                    tenantId,
                    brandId,
                    locationId,
                    new ServiceScheduleService.ChangeServiceStateCommand(
                            body.mode(), body.reasonCode(), body.note(), body.effectiveUntil()));
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException refused) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, refused.getMessage());
        }
    }

    @PutMapping("/capacity")
    @RequiresCapability(value = Capability.SERVICEABILITY_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Set or clear the concurrent-order ceiling",
            description = "Advisory at browse and authoritative at checkout, where it is a "
                    + "conditional count inside the transaction rather than a cached number.")
    public ResponseEntity<Void> setCapacity(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody CapacityRequest body) {
        schedules.setCapacity(tenantId, brandId, locationId, body.maxConcurrentOrders());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/service-bindings")
    @RequiresCapability(value = Capability.SERVICEABILITY_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Bind a timetable to one fulfilment mode here",
            description = "One binding per mode, so pickup may close before dine-in without "
                    + "either needing its own column on the branch.")
    public ResponseEntity<Void> bindSchedule(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody BindingRequest body) {
        schedules.bind(tenantId, brandId, locationId, body.fulfillmentMode(), body.scheduleId());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/preparation-bands")
    @RequiresCapability(value = Capability.SERVICEABILITY_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Replace this location's preparation bands",
            description = "Whole set, so the coverage is always exactly what an operator last "
                    + "reviewed. The band is one of three inputs to the promised time; the "
                    + "longest of the band and any item override wins.")
    public ResponseEntity<Void> replacePreparationBands(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody BandsRequest body) {
        schedules.replacePreparationBands(
                tenantId,
                brandId,
                locationId,
                body.bands().stream().map(BandRequest::toBand).toList());
        return ResponseEntity.noContent().build();
    }

    record ServiceStateRequest(
            @NotNull ServiceMode mode,
            @Size(max = 48) String reasonCode,
            @Size(max = 400) String note,
            Instant effectiveUntil) {}

    record CapacityRequest(@Min(1) Integer maxConcurrentOrders) {}

    record BindingRequest(
            @NotNull FulfillmentMode fulfillmentMode,
            @NotNull UUID scheduleId) {}

    record BandsRequest(@NotNull List<@Valid BandRequest> bands) {}

    record BandRequest(
            FulfillmentMode fulfillmentMode,
            @Min(1) @Max(7) Integer dayOfWeek,
            @NotNull LocalTime startsAt,
            @NotNull LocalTime endsAt,
            @Min(1) @Max(1440) int durationMinutes,
            int priority) {

        JdbcServiceabilityStore.Band toBand() {
            return new JdbcServiceabilityStore.Band(
                    fulfillmentMode, dayOfWeek, startsAt, endsAt, durationMinutes, priority);
        }
    }

    public record ServiceSummaryResponse(
            String mode,
            String effectiveMode,
            @Nullable String reasonCode,
            @Nullable Instant effectiveUntil,
            @Nullable Integer maxConcurrentOrders,
            long openOrderCount,
            List<ModeBindingResponse> bindings,
            List<BandResponse> preparationBands) {}

    /** One fulfilment mode's bound timetable, named and with its full grid, read-only. */
    public record ModeBindingResponse(
            FulfillmentMode fulfillmentMode,
            UUID scheduleId,
            String scheduleName,
            boolean acceptsScheduledOrders,
            long sharedWithLocationCount,
            List<RuleResponse> rules,
            List<ExceptionResponse> exceptions) {}

    public record RuleResponse(int dayOfWeek, LocalTime opensAt, LocalTime closesAt) {}

    public record ExceptionResponse(
            LocalDate date,
            boolean closedAllDay,
            @Nullable LocalTime opensAt,
            @Nullable LocalTime closesAt) {}

    public record BandResponse(
            @Nullable FulfillmentMode fulfillmentMode,
            @Nullable Integer dayOfWeek,
            LocalTime startsAt,
            LocalTime endsAt,
            int durationMinutes,
            int priority) {}
}
