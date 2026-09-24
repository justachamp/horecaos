package uz.horecaos.platform.tenancy.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.application.ServiceScheduleService;
import uz.horecaos.platform.tenancy.domain.channel.WeeklySchedule;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcServiceabilityStore;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.authorization.RequiresCapability;

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

    /**
     * Every timetable this brand owns, named and with how many locations
     * currently bind it — wave P43 (gap map row {@code 10.2c}).
     *
     * <p>Before this wave the controller had a {@code POST} and two {@code PUT}s
     * and no {@code GET} at all: a location's Hours tab could show the
     * schedule it already had bound (via {@code
     * LocationServiceOperationsController.serviceSummary}), but rebinding a
     * fulfilment mode to a <em>different</em> timetable had nothing to pick
     * from except a raw schedule id typed in blind. {@link
     * ServiceScheduleService#schedulesForBrand} already existed as a
     * passthrough to {@link JdbcServiceabilityStore#schedulesForBrand} — that
     * store method's own doc names exactly this screen and this banner — it
     * simply had no HTTP caller.
     *
     * <p>{@code boundLocationCount} is the same count the shared-schedule
     * warning needs before a save: editing a schedule bound to more than one
     * location changes every one of them silently unless the editor says so
     * first.
     */
    @GetMapping
    @RequiresCapability(value = Capability.LOCATION_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Every named timetable this brand owns",
            description = "The Hours tab's rebind picker and the shared-schedule warning's source: "
                    + "each schedule's name and how many locations currently bind it.")
    public List<ScheduleSummaryResponse> list(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return schedules.schedulesForBrand(tenantId, brandId).stream()
                .map(ScheduleSummaryResponse::of)
                .toList();
    }

    @PostMapping
    @RequiresCapability(value = Capability.SERVICEABILITY_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Create a named timetable",
            description = "Named and reusable, so thirty branches on one Ramadan timetable edit "
                    + "one object. That is the point, and also the accident.")
    public ResponseEntity<ScheduleView> create(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody CreateScheduleRequest body) {

        UUID scheduleId = schedules.createSchedule(
                tenantId,
                brandId,
                new ServiceScheduleService.CreateScheduleCommand(
                        body.name(),
                        body.acceptsScheduledOrders(),
                        body.rules().stream().map(RuleRequest::toRule).toList()));
        return ResponseEntity.ok(new ScheduleView(scheduleId, body.name(), body.acceptsScheduledOrders()));
    }

    @PutMapping("/{scheduleId}/rules")
    @RequiresCapability(value = Capability.SERVICEABILITY_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Replace the weekly windows",
            description = "A closing time at or before the opening time means the window ends on "
                    + "the following day, so 18:00-02:00 is one row and not two.")
    public ResponseEntity<Void> replaceRules(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID scheduleId,
            @Valid @RequestBody RulesRequest body) {
        schedules.replaceRules(
                tenantId,
                brandId,
                scheduleId,
                body.rules().stream().map(RuleRequest::toRule).toList());
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{scheduleId}/exceptions")
    @RequiresCapability(value = Capability.SERVICEABILITY_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Close a date, or give it replacement hours",
            description = "A dated exception replaces the weekly rule for its date rather than "
                    + "adding to it: \"we close early on the 31st\" must not leave the normal "
                    + "evening window in place.")
    public ResponseEntity<Void> upsertException(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID scheduleId,
            @Valid @RequestBody ExceptionRequest body) {

        if (body.closedAllDay()) {
            schedules.closeForDay(tenantId, brandId, scheduleId, body.date(), body.label(), body.reason());
        } else {
            schedules.shortenDay(
                    tenantId,
                    brandId,
                    scheduleId,
                    body.date(),
                    body.opensAt(),
                    body.closesAt(),
                    body.label(),
                    body.reason());
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * Actually removes a dated exception — gap map row {@code 10.2c}.
     *
     * <p>Before this endpoint existed, a dated exception taken out of the
     * Hours grid and saved was never deleted server-side: {@link
     * #upsertException} is an upsert by date, with no matching delete, so the
     * row simply reappeared on the next reload. The console's local draft is
     * now wired to call this for every removed row rather than silently
     * dropping it from the PUT.
     *
     * <p>{@code If-Match} carries the owning schedule's version — an
     * exception has no version of its own — and the response's {@code ETag}
     * carries the version this delete produced, for the caller's next write.
     */
    @DeleteMapping("/{scheduleId}/exceptions/{date}")
    @RequiresCapability(value = Capability.SERVICEABILITY_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Delete a dated exception",
            description =
                    "If-Match carries the owning schedule's version, since a dated " + "exception has none of its own.")
    public ResponseEntity<Void> deleteException(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID scheduleId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            HttpServletRequest request) {

        long expected = AggregateVersion.requireIfMatch(request);
        int newVersion = schedules.deleteException(tenantId, brandId, scheduleId, date, (int) expected);
        return ResponseEntity.noContent()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(newVersion))
                .build();
    }

    record CreateScheduleRequest(
            @NotBlank @Size(max = 200) String name,
            boolean acceptsScheduledOrders,
            @NotNull List<@Valid RuleRequest> rules) {}

    record RulesRequest(@NotNull List<@Valid RuleRequest> rules) {}

    record RuleRequest(
            @Min(1) @Max(7) int dayOfWeek,

            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
            LocalTime opensAt,

            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.TIME)
            LocalTime closesAt) {

        WeeklySchedule.Rule toRule() {
            return new WeeklySchedule.Rule(dayOfWeek, opensAt, closesAt);
        }
    }

    record ExceptionRequest(
            @NotNull @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date,

            boolean closedAllDay,
            LocalTime opensAt,
            LocalTime closesAt,
            @NotBlank @Size(max = 200) String label,
            @NotBlank @Size(max = 400) String reason) {}

    public record ScheduleView(UUID id, String name, boolean acceptsScheduledOrders) {}

    /**
     * One brand-owned timetable, named, for the rebind picker — {@link #list}.
     *
     * @param version the schedule's own version — the {@code If-Match} token
     *                {@link #deleteException} needs, since a dated exception
     *                carries none of its own
     */
    public record ScheduleSummaryResponse(
            UUID id, String name, boolean acceptsScheduledOrders, long boundLocationCount, int version) {

        static ScheduleSummaryResponse of(JdbcServiceabilityStore.ScheduleSummary summary) {
            return new ScheduleSummaryResponse(
                    summary.id(),
                    summary.name(),
                    summary.acceptsScheduledOrders(),
                    summary.boundLocationCount(),
                    summary.version());
        }
    }
}
