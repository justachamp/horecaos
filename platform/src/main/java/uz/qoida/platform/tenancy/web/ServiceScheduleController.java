package uz.qoida.platform.tenancy.web;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import uz.qoida.platform.iam.api.Capability;
import uz.qoida.platform.iam.api.ResourceScope.ScopeType;
import uz.qoida.platform.tenancy.application.ServiceScheduleService;
import uz.qoida.platform.tenancy.domain.channel.WeeklySchedule;
import uz.qoida.platform.web.authorization.RequiresCapability;

/**
 * Authoring opening timetables (ADR 0036).
 *
 * <p>Brand-scoped, which is the narrowest the path supports and also the truth: a
 * schedule may only be bound to locations of its own brand, and the composite
 * foreign key in V0020 enforces that.
 *
 * <p>Everything here is a whole-object write. Rules are replaced as a set rather
 * than appended, so a timetable is always exactly what an operator last reviewed
 * instead of an accumulation nobody has read end to end.
 */
@RestController
@RequestMapping("/api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/service-schedules")
@Tag(name = "Service schedules", description = "Named, reusable opening timetables")
public class ServiceScheduleController {

    private final ServiceScheduleService schedules;

    public ServiceScheduleController(ServiceScheduleService schedules) {
        this.schedules = schedules;
    }

    @PostMapping
    @RequiresCapability(value = Capability.SERVICEABILITY_MANAGE, scope = ScopeType.BRAND,
            mutating = true)
    @Operation(summary = "Create a named timetable",
            description = "Named and reusable, so thirty branches on one Ramadan timetable edit "
                    + "one object. That is the point, and also the accident.")
    public ResponseEntity<ScheduleView> create(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @Valid @RequestBody CreateScheduleRequest body) {

        UUID scheduleId = schedules.createSchedule(tenantId, brandId,
                new ServiceScheduleService.CreateScheduleCommand(
                        body.name(), body.acceptsScheduledOrders(),
                        body.rules().stream().map(RuleRequest::toRule).toList()));
        return ResponseEntity.ok(new ScheduleView(scheduleId, body.name(),
                body.acceptsScheduledOrders()));
    }

    @PutMapping("/{scheduleId}/rules")
    @RequiresCapability(value = Capability.SERVICEABILITY_MANAGE, scope = ScopeType.BRAND,
            mutating = true)
    @Operation(summary = "Replace the weekly windows",
            description = "A closing time at or before the opening time means the window ends on "
                    + "the following day, so 18:00-02:00 is one row and not two.")
    public ResponseEntity<Void> replaceRules(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @PathVariable UUID scheduleId,
            @Valid @RequestBody RulesRequest body) {
        schedules.replaceRules(tenantId, brandId, scheduleId,
                body.rules().stream().map(RuleRequest::toRule).toList());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{scheduleId}/exceptions")
    @RequiresCapability(value = Capability.SERVICEABILITY_MANAGE, scope = ScopeType.BRAND,
            mutating = true)
    @Operation(summary = "Close a date, or give it replacement hours",
            description = "A dated exception replaces the weekly rule for its date rather than "
                    + "adding to it: \"we close early on the 31st\" must not leave the normal "
                    + "evening window in place.")
    public ResponseEntity<Void> upsertException(@PathVariable UUID tenantId,
            @PathVariable UUID brandId, @PathVariable UUID scheduleId,
            @Valid @RequestBody ExceptionRequest body) {

        if (body.closedAllDay()) {
            schedules.closeForDay(tenantId, brandId, scheduleId, body.date(), body.label(), body.reason());
        } else {
            schedules.shortenDay(tenantId, brandId, scheduleId, body.date(), body.opensAt(), body.closesAt(),
                    body.label(), body.reason());
        }
        return ResponseEntity.noContent().build();
    }

    record CreateScheduleRequest(
            @NotBlank @Size(max = 200) String name,
            boolean acceptsScheduledOrders,
            @NotNull List<@Valid RuleRequest> rules) { }

    record RulesRequest(@NotNull List<@Valid RuleRequest> rules) { }

    record RuleRequest(
            @Min(1) @Max(7) int dayOfWeek,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime opensAt,
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.TIME) LocalTime closesAt) {

        WeeklySchedule.Rule toRule() {
            return new WeeklySchedule.Rule(dayOfWeek, opensAt, closesAt);
        }
    }

    record ExceptionRequest(
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            boolean closedAllDay,
            LocalTime opensAt,
            LocalTime closesAt,
            @NotBlank @Size(max = 200) String label,
            @NotBlank @Size(max = 400) String reason) { }

    public record ScheduleView(UUID id, String name, boolean acceptsScheduledOrders) { }
}
