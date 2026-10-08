package uz.horecaos.platform.workforce.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;
import uz.horecaos.platform.web.cache.RateLimiter;
import uz.horecaos.platform.workforce.application.Branch;
import uz.horecaos.platform.workforce.application.StaffShiftQueryService;
import uz.horecaos.platform.workforce.application.StaffShiftService;
import uz.horecaos.platform.workforce.application.StaffShiftService.ShiftView;
import uz.horecaos.platform.workforce.application.StaffTodayService;
import uz.horecaos.platform.workforce.domain.ReasonCode;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore.Cursor;
import uz.horecaos.platform.workforce.web.WorkforceResponses.ShiftPageResponse;
import uz.horecaos.platform.workforce.web.WorkforceResponses.ShiftResponse;
import uz.horecaos.platform.workforce.web.WorkforceResponses.TodayResponse;
import uz.horecaos.platform.workforce.web.WorkforceResponses.TodayRowResponse;

/**
 * The timesheet of one branch (ADR 0156): «Сегодня», the shift list, and a manager recording,
 * correcting, voiding and approving hours.
 *
 * <p>A shift is a timesheet and never a permission: nothing here, and nothing a screen does with it,
 * refuses an order action, a kitchen action or a sign-in for want of an open shift. {@code
 * staff.shift.read} reads, {@code staff.shift.manage} writes and {@code staff.shift.approve} approves
 * -- one shift at a time, deliberately not a bulk act. Every write carries an {@code Idempotency-Key}
 * and, on an existing shift, an {@code If-Match} version. A time in the past needs a reason from the
 * closed {@link ReasonCode} list, and a shift older than the retroactive-edit window can be changed
 * only by a holder of {@code staff.shift.approve}.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/staff/shifts")
@Tag(name = "Staff shifts", description = "The timesheet of non-courier staff at one branch (ADR 0156)")
public class StaffShiftController {

    private static final RateLimiter.Policy TODAY_LIMIT = RateLimiter.Policy.perMinute(30);

    private final StaffShiftService shifts;
    private final StaffShiftQueryService queries;
    private final StaffTodayService today;
    private final CurrentActor currentActor;
    private final AuthorizationService authorization;
    private final RateLimiter rateLimiter;

    public StaffShiftController(
            StaffShiftService shifts,
            StaffShiftQueryService queries,
            StaffTodayService today,
            CurrentActor currentActor,
            AuthorizationService authorization,
            RateLimiter rateLimiter) {
        this.shifts = shifts;
        this.queries = queries;
        this.today = today;
        this.currentActor = currentActor;
        this.authorization = authorization;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping("/today")
    @RequiresCapability(value = Capability.STAFF_SHIFT_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Today at this branch, per person",
            description = "Derived status in the spec's order: LATE, MISSED_TODAY, WORKING_WITHOUT_SHIFT, "
                    + "OPEN_TOO_LONG, WORKING, CLOSED (and SCHEDULED for a later start). \"Today\" is the "
                    + "tenant's business day and a future day never reads as missed. WORKING_WITHOUT_SHIFT "
                    + "is raised from order attribution only, so it sees people who take orders and never "
                    + "someone who only cooks (ordersOnly is always true). Polled every thirty seconds; 30 a minute.")
    public TodayResponse today(@PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        limit("staff.shift.today", tenantId, TODAY_LIMIT);
        Branch branch = new Branch(tenantId, brandId, locationId);
        var view = today.today(branch, seesNames(branch));
        return new TodayResponse(
                view.businessDate(),
                view.from(),
                view.to(),
                view.asOf(),
                true,
                view.rows().stream().map(TodayRowResponse::of).toList());
    }

    @GetMapping
    @RequiresCapability(value = Capability.STAFF_SHIFT_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Shifts at this branch",
            description = "Newest start first, cursor paged. from and to are business dates, inclusive. "
                    + "Voided shifts are listed: voiding is a recorded fact, not a deletion.")
    public ShiftPageResponse list(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate to,
            @RequestParam(required = false) @Nullable UUID memberId,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        Branch branch = new Branch(tenantId, brandId, locationId);
        return page(queries.list(
                tenantId, brandId, locationId, memberId, from, to, decode(cursor), limit, seesNames(branch)));
    }

    @PostMapping
    @RequiresCapability(value = Capability.STAFF_SHIFT_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Record that someone started",
            description = "A manager opens a shift for an active person with a job here. The start is the "
                    + "request's, may not be in the future, and needs reasonCode when it is more than five "
                    + "minutes in the past. 409 SHIFT_ALREADY_OPEN when the person already has an open shift.")
    public ResponseEntity<ShiftResponse> open(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody OpenRequest body) {
        ShiftView opened = shifts.openFor(
                new Branch(tenantId, brandId, locationId),
                body.memberId(),
                body.startedAt(),
                body.reasonCode(),
                actor(),
                WorkforceWeb.correlation());
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(AggregateVersion.toETag(opened.shift().version()))
                .body(ShiftResponse.of(opened));
    }

    @PostMapping("/{shiftId}/closures")
    @RequiresCapability(value = Capability.STAFF_SHIFT_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Record that someone finished",
            description = "Closes an open shift at endedAt (needs If-Match). The break defaults to the matched "
                    + "roster entry's planned break unless breakSeconds is set. Over the variance threshold "
                    + "the shift waits in AWAITING_APPROVAL and is out of payroll facts until approved.")
    public ShiftResponse close(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID shiftId,
            @Valid @RequestBody CloseRequest body,
            HttpServletRequest request) {
        ShiftView closed = shifts.closeFor(
                new Branch(tenantId, brandId, locationId),
                shiftId,
                (int) AggregateVersion.requireIfMatch(request),
                body.endedAt(),
                body.breakSeconds(),
                body.reasonCode(),
                actor(),
                WorkforceWeb.correlation());
        return ShiftResponse.of(closed);
    }

    @PutMapping("/{shiftId}")
    @RequiresCapability(value = Capability.STAFF_SHIFT_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Correct a shift's times or break",
            description = "Needs If-Match and a reasonCode. Time is corrected, never overwritten: the audit fact "
                    + "carries the old and new times. A shift older than workforce.shift.retroactive_edit_days "
                    + "can be corrected only by a holder of staff.shift.approve. An earlier approval does not "
                    + "survive a change of the hours.")
    public ShiftResponse correct(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID shiftId,
            @Valid @RequestBody CorrectionRequest body,
            HttpServletRequest request) {
        ShiftView corrected = shifts.correct(
                new Branch(tenantId, brandId, locationId),
                shiftId,
                (int) AggregateVersion.requireIfMatch(request),
                body.startedAt(),
                body.endedAt(),
                body.breakSeconds(),
                body.reasonCode(),
                actor(),
                WorkforceWeb.correlation());
        return ShiftResponse.of(corrected);
    }

    @PostMapping("/{shiftId}/voidings")
    @RequiresCapability(value = Capability.STAFF_SHIFT_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Void a mistaken shift",
            description = "VOIDED with a reason and never deleted; it leaves payroll facts. If-Match is optional.")
    public ShiftResponse voidShift(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID shiftId,
            @Valid @RequestBody ReasonRequest body,
            HttpServletRequest request) {
        ShiftView voided = shifts.voidShift(
                new Branch(tenantId, brandId, locationId),
                shiftId,
                AggregateVersion.fromIfMatch(request).map(Long::intValue).orElse(null),
                body.reasonCode(),
                actor(),
                WorkforceWeb.correlation());
        return ShiftResponse.of(voided);
    }

    @PostMapping("/{shiftId}/approvals")
    @RequiresCapability(value = Capability.STAFF_SHIFT_APPROVE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Approve one shift over the threshold",
            description = "AWAITING_APPROVAL -> CLOSED with a reason, one shift at a time (approval is not a bulk "
                    + "action), needs If-Match. The approver may be the person who edited the hours unless the "
                    + "tenant sets workforce.shift.approver_must_differ; the audit fact records both.")
    public ShiftResponse approve(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID shiftId,
            @Valid @RequestBody ReasonRequest body,
            HttpServletRequest request) {
        ShiftView approved = shifts.approve(
                new Branch(tenantId, brandId, locationId),
                shiftId,
                (int) AggregateVersion.requireIfMatch(request),
                body.reasonCode(),
                actor(),
                WorkforceWeb.correlation());
        return ShiftResponse.of(approved);
    }

    // ------------------------------------------------------------------------------- plumbing

    private boolean seesNames(Branch branch) {
        return authorization.has(currentActor.get().subject(), Capability.STAFF_PROFILE_READ, branch.scope());
    }

    private String actor() {
        return currentActor.get().subject();
    }

    private void limit(String operation, UUID tenantId, RateLimiter.Policy policy) {
        RateLimiter.Decision decision = rateLimiter.check(
                new RateLimiter.Key(
                        operation, tenantId.toString(), currentActor.get().subject()),
                policy);
        if (!decision.allowed()) {
            throw new ApiException(
                    ErrorCode.RATE_LIMIT_EXCEEDED,
                    "Too many requests. Try again shortly.",
                    Map.of(
                            "retryAfterSeconds",
                            Math.max(1, decision.retryAfter().toSeconds())));
        }
    }

    static ShiftPageResponse page(StaffShiftQueryService.Page page) {
        return new ShiftPageResponse(
                page.items().stream()
                        .map(item -> ShiftResponse.of(item.shift(), item.person()))
                        .toList(),
                page.next() == null ? null : encode(page.next()));
    }

    static String encode(Cursor cursor) {
        String raw = cursor.startedAt().toEpochMilli() + ":" + cursor.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static @Nullable Cursor decode(@Nullable String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        try {
            String raw = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            int colon = raw.indexOf(':');
            Instant at = Instant.ofEpochMilli(Long.parseLong(raw.substring(0, colon)));
            return new Cursor(at, UUID.fromString(raw.substring(colon + 1)));
        } catch (RuntimeException malformed) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "That cursor is not one this list issued", Map.of());
        }
    }

    /** Every optional field is nullable: Jackson 3 refuses a missing primitive. */
    record OpenRequest(
            @NotNull UUID memberId,
            @NotNull Instant startedAt,
            @Nullable ReasonCode reasonCode) {}

    record CloseRequest(
            @NotNull Instant endedAt,
            @Min(0) @Nullable Integer breakSeconds,
            @Nullable ReasonCode reasonCode) {}

    record CorrectionRequest(
            @Nullable Instant startedAt,
            @Nullable Instant endedAt,
            @Min(0) @Nullable Integer breakSeconds,
            @NotNull ReasonCode reasonCode) {}

    record ReasonRequest(@NotNull ReasonCode reasonCode) {}
}
