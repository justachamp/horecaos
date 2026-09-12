package uz.horecaos.platform.reporting.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
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
import org.jspecify.annotations.Nullable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.reporting.application.BusinessCalendarService;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcBusinessCalendarStore.TenantHoliday;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * 10.10b: the tenant's own trading calendar — its weekend, its own holidays,
 * and the business-day boundary editor {@code BusinessDayService.setBoundary}
 * never had a caller for.
 *
 * <p>Reads are {@link Capability#REPORTING_READ}, the same capability {@link
 * ReportingController} uses for the reports this calendar's boundary dates;
 * writes are {@link Capability#TENANT_CONFIGURATION_WRITE}, because declaring
 * a weekend or moving the boundary is exactly the kind of tenant-owned
 * setting that capability already names, and both are held together on
 * {@code tenant-owner} and {@code tenant-admin} today (see {@code
 * PlatformRole}) — no new bundle to wire.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/business-calendar")
@Tag(name = "Business calendar", description = "A tenant's weekend, its own holidays, and its business-day boundary")
public class BusinessCalendarController {

    private final BusinessCalendarService calendars;
    private final CurrentActor currentActor;

    public BusinessCalendarController(BusinessCalendarService calendars, CurrentActor currentActor) {
        this.calendars = calendars;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.REPORTING_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The tenant's business calendar",
            description = "The resolved business-day boundary and timezone, whether a recut is "
                    + "outstanding after a previous boundary change, the declared weekend, and "
                    + "this tenant's own holidays.")
    public ResponseEntity<CalendarResponse> get(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(CalendarResponse.of(calendars.calendar(tenantId)));
    }

    @PutMapping("/boundary")
    @RequiresCapability(value = Capability.TENANT_CONFIGURATION_WRITE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Move the business-day boundary",
            description = "Warns in its own response rather than performing silently: moving the "
                    + "boundary changes which business date every future fact is filed under and "
                    + "leaves a recut outstanding until DayCloseService has caught the historical "
                    + "range up. Gated by the ADR 0027 approval model (ADR 0050 action "
                    + "reporting.business-day-boundary.change).")
    public ResponseEntity<BoundaryChangeResponse> changeBoundary(
            @PathVariable UUID tenantId, @Valid @RequestBody ChangeBoundaryRequest body) {
        BusinessCalendarService.BoundaryChange change =
                calendars.changeBoundary(tenantId, body.businessDayStart(), actor(), body.reason());
        return ResponseEntity.ok(new BoundaryChangeResponse(change.status(), change.approvalRequestId()));
    }

    @PutMapping("/weekend")
    @RequiresCapability(value = Capability.TENANT_CONFIGURATION_WRITE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Declare the tenant's weekend",
            description = "ISO-8601 weekday numbers (1=Monday..7=Sunday). An empty list means "
                    + "the tenant trades every day, which is also today's assumed default.")
    public ResponseEntity<Void> setWeekend(@PathVariable UUID tenantId, @Valid @RequestBody SetWeekendRequest body) {
        calendars.setWeekendDays(tenantId, body.weekendDays(), actor(), body.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/holidays")
    @RequiresCapability(value = Capability.TENANT_CONFIGURATION_WRITE, scope = ScopeType.TENANT, mutating = true)
    @Operation(
            summary = "Add a closure to this tenant's own calendar",
            description = "A month and day for one that recurs every year, or a date for a "
                    + "one-off or movable closure. Additional to the platform's own per-country "
                    + "public holidays, never a replacement for them.")
    public ResponseEntity<HolidayAddedResponse> addHoliday(
            @PathVariable UUID tenantId, @Valid @RequestBody TenantCalendarHolidayRequest body) {
        UUID id = calendars.addHoliday(
                tenantId, body.name(), body.month(), body.day(), body.date(), actor(), body.reason());
        return ResponseEntity.ok(new HolidayAddedResponse(id));
    }

    @DeleteMapping("/holidays/{holidayId}")
    @RequiresCapability(value = Capability.TENANT_CONFIGURATION_WRITE, scope = ScopeType.TENANT, mutating = true)
    @Operation(summary = "Remove a closure from this tenant's own calendar")
    public ResponseEntity<Void> removeHoliday(
            @PathVariable UUID tenantId,
            @PathVariable UUID holidayId,
            @Valid @RequestBody RemoveTenantCalendarHolidayRequest body) {
        calendars.removeHoliday(tenantId, holidayId, actor(), body.reason());
        return ResponseEntity.noContent().build();
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    public record CalendarResponse(
            String timezone,
            LocalTime businessDayStart,
            int boundaryVersion,
            @Nullable LocalDate recutCompletedThrough,
            List<Integer> weekendDays,
            List<HolidayResponse> holidays) {

        static CalendarResponse of(BusinessCalendarService.Calendar calendar) {
            return new CalendarResponse(
                    calendar.timezone(),
                    calendar.businessDayStart(),
                    calendar.boundaryVersion(),
                    calendar.recutCompletedThrough(),
                    calendar.weekendDays(),
                    calendar.holidays().stream().map(HolidayResponse::of).toList());
        }
    }

    public record HolidayResponse(
            UUID holidayId,
            String name,
            @Nullable Integer month,
            @Nullable Integer day,
            @Nullable String date) {

        static HolidayResponse of(TenantHoliday holiday) {
            LocalDate date = holiday.date();
            return new HolidayResponse(
                    holiday.id(),
                    holiday.name(),
                    holiday.month(),
                    holiday.day(),
                    date == null ? null : date.toString());
        }
    }

    public record ChangeBoundaryRequest(
            @NotNull LocalTime businessDayStart,
            @NotBlank @Size(max = 1000) String reason) {}

    public record BoundaryChangeResponse(
            String status, @Nullable UUID approvalRequestId) {}

    public record SetWeekendRequest(
            @NotNull List<@Min(1) @Max(7) Integer> weekendDays,
            @NotBlank @Size(max = 1000) String reason) {

        public SetWeekendRequest {
            weekendDays = List.copyOf(weekendDays);
        }
    }

    public record HolidayAddedResponse(UUID holidayId) {}

    public record TenantCalendarHolidayRequest(
            @NotBlank @Size(max = 200) String name,
            @Min(1) @Max(12) Integer month,
            @Min(1) @Max(31) Integer day,
            LocalDate date,
            @NotBlank @Size(max = 1000) String reason) {}

    public record RemoveTenantCalendarHolidayRequest(
            @NotBlank @Size(max = 1000) String reason) {}
}
