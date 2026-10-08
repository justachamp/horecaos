package uz.horecaos.platform.workforce.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.staff.StaffSelfAuthorized;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.cache.RateLimiter;
import uz.horecaos.platform.web.idempotency.Idempotent;
import uz.horecaos.platform.workforce.application.StaffAttendanceSelfService;
import uz.horecaos.platform.workforce.application.StaffShiftService;
import uz.horecaos.platform.workforce.application.StaffShiftService.ShiftView;
import uz.horecaos.platform.workforce.web.WorkforceResponses.ShiftResponse;
import uz.horecaos.platform.workforce.web.WorkforceResponses.UpcomingEntryResponse;

/**
 * «Моя работа» → the caller's own attendance (ADR 0156): clock in, clock out, and what is next.
 *
 * <p>Authorised by {@link StaffSelfAuthorized}, like {@code StaffSelfController}: the capability is held
 * at <em>some</em> scope in the tenant, and <strong>no handler takes a member id</strong> -- every one
 * resolves the person from the token's subject and the path's tenant. A clock-in names a branch, and the
 * service answers 404 (the answer an unknown branch gets) unless the caller holds {@code
 * staff.shift.clock} at or above it. The time of a clock event is the server's; no position, network
 * address or device identifier is read or stored.
 *
 * <p>Limits (ADR 0033), per person: clock-in and clock-out ten a minute together, the attendance read
 * thirty. Both clock calls require an {@code Idempotency-Key}, so a double tap on a tablet replays the
 * first answer and neither opens a second shift nor answers a spurious conflict.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/staff")
@Tag(name = "My attendance", description = "A staff member's own clock-in, clock-out and shifts (ADR 0156)")
public class StaffAttendanceSelfController {

    private static final RateLimiter.Policy CLOCK_LIMIT = RateLimiter.Policy.strictPerMinute(10);
    private static final RateLimiter.Policy READ_LIMIT = RateLimiter.Policy.perMinute(30);

    private final StaffShiftService shifts;
    private final StaffAttendanceSelfService attendance;
    private final CurrentActor currentActor;
    private final RateLimiter rateLimiter;

    public StaffAttendanceSelfController(
            StaffShiftService shifts,
            StaffAttendanceSelfService attendance,
            CurrentActor currentActor,
            RateLimiter rateLimiter) {
        this.shifts = shifts;
        this.attendance = attendance;
        this.currentActor = currentActor;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/attendance")
    @StaffSelfAuthorized(Capability.STAFF_SELF_MANAGE)
    @Operation(
            summary = "Does this company record staff hours?",
            description = "Whether workforce.attendance.enabled is on for the tenant. The console shows the "
                    + "shift screens and the person-card tab only when it is. Reveals nothing about any person.")
    public AttendanceStatusResponse status(@PathVariable UUID tenantId) {
        return new AttendanceStatusResponse(attendance.enabled(tenantId));
    }

    @GetMapping("/me/attendance")
    @StaffSelfAuthorized(Capability.STAFF_SHIFT_CLOCK)
    @Operation(
            summary = "My open shift, my next shifts, my last shifts",
            description = "Resolved from the signed-in subject and this tenant; there is no member id to supply. "
                    + "404 when this tenant keeps no record for the account or has not switched attendance on. "
                    + "30 a minute.")
    public MyAttendanceResponse mine(@PathVariable UUID tenantId) {
        limit("staff.shift.me", tenantId, READ_LIMIT);
        var mine = attendance
                .mine(tenantId, subject())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such resource."));
        return new MyAttendanceResponse(
                mine.open() == null ? null : ShiftResponse.of(mine.open(), null),
                mine.upcoming().stream().map(UpcomingEntryResponse::of).toList(),
                mine.recent().stream()
                        .map(shift -> ShiftResponse.of(shift, null))
                        .toList());
    }

    @PostMapping("/me/attendance/clock-ins")
    @StaffSelfAuthorized(Capability.STAFF_SHIFT_CLOCK)
    @Idempotent
    @Operation(
            summary = "Start my shift at a branch",
            description = "Needs an Idempotency-Key and a branch where the caller holds staff.shift.clock; any "
                    + "other branch answers 404, the answer an unknown branch gets. The start is the server's "
                    + "clock. No published roster entry is needed. 409 SHIFT_ALREADY_OPEN when a shift is open "
                    + "(a repeat with the same key replays the first answer). Ten a minute with clock-out.")
    public ResponseEntity<ShiftResponse> clockIn(@PathVariable UUID tenantId, @Valid @RequestBody ClockInRequest body) {
        limit("staff.shift.clock", tenantId, CLOCK_LIMIT);
        ShiftView opened = shifts.clockIn(tenantId, subject(), body.locationId(), WorkforceWeb.correlation());
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(AggregateVersion.toETag(opened.shift().version()))
                .body(ShiftResponse.of(opened.shift(), null));
    }

    @PostMapping("/me/attendance/clock-outs")
    @StaffSelfAuthorized(Capability.STAFF_SHIFT_CLOCK)
    @Idempotent
    @Operation(
            summary = "End my open shift",
            description = "Needs an Idempotency-Key. The end is the server's clock. The break defaults to the "
                    + "matched roster entry's planned break. Over the variance threshold the shift waits for a "
                    + "manager's approval and the answer says AWAITING_APPROVAL. 422 NO_OPEN_SHIFT when none is "
                    + "open. Ten a minute with clock-in.")
    public ShiftResponse clockOut(@PathVariable UUID tenantId, @Valid @RequestBody ClockOutRequest body) {
        limit("staff.shift.clock", tenantId, CLOCK_LIMIT);
        return ShiftResponse.of(
                shifts.clockOut(tenantId, subject(), WorkforceWeb.correlation()).shift(), null);
    }

    // ------------------------------------------------------------------------------- plumbing

    private String subject() {
        return currentActor.get().subject();
    }

    private void limit(String operation, UUID tenantId, RateLimiter.Policy policy) {
        RateLimiter.Decision decision =
                rateLimiter.check(new RateLimiter.Key(operation, tenantId.toString(), subject()), policy);
        if (!decision.allowed()) {
            throw new ApiException(
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many requests. Try again shortly.",
                    Map.of(
                            "retryAfterSeconds",
                            Math.max(1, decision.retryAfter().toSeconds())));
        }
    }

    record ClockInRequest(@NotNull UUID locationId) {}

    /** An empty object: a body keeps the call a command and gives a client nothing to forget. */
    record ClockOutRequest() {}

    record AttendanceStatusResponse(boolean enabled) {}

    record MyAttendanceResponse(
            @org.jspecify.annotations.Nullable ShiftResponse open,
            List<UpcomingEntryResponse> upcoming,
            List<ShiftResponse> recent) {}
}
