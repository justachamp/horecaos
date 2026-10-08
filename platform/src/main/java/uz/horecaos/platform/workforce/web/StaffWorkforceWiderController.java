package uz.horecaos.platform.workforce.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.authorization.RequiresCapability;
import uz.horecaos.platform.workforce.application.StaffRosterService;
import uz.horecaos.platform.workforce.application.StaffShiftQueryService;
import uz.horecaos.platform.workforce.web.WorkforceResponses.EntryResponse;
import uz.horecaos.platform.workforce.web.WorkforceResponses.ShiftPageResponse;
import uz.horecaos.platform.workforce.web.WorkforceResponses.WeekResponse;

/**
 * The brand and tenant reads of the rota and the timesheet (ADR 0156): the same lists as a branch's,
 * at {@code BRAND} and {@code TENANT} scope, for an owner, an administrator, finance or a brand manager
 * who is not at one branch. Read only -- every write is a branch's, so a broader grant covers it
 * downward and nothing is authored from here.
 *
 * <p>A brand or tenant list spans branches in more than one timezone, so the rota's day boundary here is
 * UTC and the response says so; a branch's own grid draws local days. «Сегодня» is per branch and is
 * not offered at a wider scope. A holder without {@code staff.profile.read} at that scope sees display
 * references and no names.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}")
@Tag(name = "Staff shifts (wider reach)", description = "Brand and tenant reads of the rota and timesheet (ADR 0156)")
public class StaffWorkforceWiderController {

    private final StaffRosterService roster;
    private final StaffShiftQueryService queries;
    private final CurrentActor currentActor;
    private final AuthorizationService authorization;

    public StaffWorkforceWiderController(
            StaffRosterService roster,
            StaffShiftQueryService queries,
            CurrentActor currentActor,
            AuthorizationService authorization) {
        this.roster = roster;
        this.queries = queries;
        this.currentActor = currentActor;
        this.authorization = authorization;
    }

    @GetMapping("/staff/roster")
    @RequiresCapability(value = Capability.STAFF_SHIFT_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "The whole company's rota between two days",
            description = "Entries overlapping the UTC days from..to (inclusive, at most 62 days), every branch.")
    public WeekResponse tenantRoster(
            @PathVariable UUID tenantId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return wider(tenantId, null, from, to, ResourceScope.tenant(tenantId));
    }

    @GetMapping("/brands/{brandId}/staff/roster")
    @RequiresCapability(value = Capability.STAFF_SHIFT_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "One brand's rota between two days",
            description =
                    "Entries overlapping the UTC days from..to (inclusive, at most 62 days), every branch of the brand.")
    public WeekResponse brandRoster(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return wider(tenantId, brandId, from, to, ResourceScope.brand(tenantId, brandId));
    }

    @GetMapping("/staff/shifts")
    @RequiresCapability(value = Capability.STAFF_SHIFT_READ, scope = ScopeType.TENANT)
    @Operation(
            summary = "Every shift of the company",
            description = "Newest start first, cursor paged; from and to are business dates, inclusive.")
    public ShiftPageResponse tenantShifts(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate to,
            @RequestParam(required = false) @Nullable UUID memberId,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return StaffShiftController.page(queries.list(
                tenantId,
                null,
                null,
                memberId,
                from,
                to,
                StaffShiftController.decode(cursor),
                limit,
                seesNames(ResourceScope.tenant(tenantId))));
    }

    @GetMapping("/brands/{brandId}/staff/shifts")
    @RequiresCapability(value = Capability.STAFF_SHIFT_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Every shift of one brand",
            description = "Newest start first, cursor paged; from and to are business dates, inclusive.")
    public ShiftPageResponse brandShifts(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) @Nullable LocalDate to,
            @RequestParam(required = false) @Nullable UUID memberId,
            @RequestParam(required = false) @Nullable String cursor,
            @RequestParam(defaultValue = "50") int limit) {
        return StaffShiftController.page(queries.list(
                tenantId,
                brandId,
                null,
                memberId,
                from,
                to,
                StaffShiftController.decode(cursor),
                limit,
                seesNames(ResourceScope.brand(tenantId, brandId))));
    }

    private WeekResponse wider(
            UUID tenantId, @Nullable UUID brandId, LocalDate from, LocalDate to, ResourceScope scope) {
        var view = roster.weekWider(tenantId, brandId, from, to, seesNames(scope));
        return new WeekResponse(
                view.timezone(),
                view.from(),
                view.to(),
                view.entries().stream().map(EntryResponse::of).toList());
    }

    private boolean seesNames(ResourceScope scope) {
        return authorization.has(currentActor.get().subject(), Capability.STAFF_PROFILE_READ, scope);
    }
}
