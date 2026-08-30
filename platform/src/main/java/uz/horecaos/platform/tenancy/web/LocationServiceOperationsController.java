package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.application.ServiceScheduleService;
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

    public LocationServiceOperationsController(ServiceScheduleService schedules) {
        this.schedules = schedules;
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
}
