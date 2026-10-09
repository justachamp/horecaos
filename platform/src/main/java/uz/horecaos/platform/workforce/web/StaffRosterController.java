package uz.horecaos.platform.workforce.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
import uz.horecaos.platform.web.authorization.RequiresCapability;
import uz.horecaos.platform.workforce.application.Branch;
import uz.horecaos.platform.workforce.application.StaffRosterService;
import uz.horecaos.platform.workforce.application.StaffRosterService.CreateEntry;
import uz.horecaos.platform.workforce.application.StaffRosterService.EntryView;
import uz.horecaos.platform.workforce.application.StaffRosterService.WeekView;
import uz.horecaos.platform.workforce.domain.ReasonCode;
import uz.horecaos.platform.workforce.web.WorkforceResponses.PersonResponse;
import uz.horecaos.platform.workforce.web.WorkforceResponses.StaffRosterEntryResponse;
import uz.horecaos.platform.workforce.web.WorkforceResponses.WeekResponse;

/**
 * The rota of one branch (ADR 0156): «Неделя» on the staff-shifts screen.
 *
 * <p>All under {@code /api/v1/operations/**}, so ADR 0057's {@code operations} document group holds it.
 * Reads need {@code staff.shift.read} and writes {@code staff.shift.manage}, both at {@code LOCATION}
 * scope: a broader grant covers the branch below it. Every write carries an {@code Idempotency-Key},
 * and every update an {@code If-Match} version. While the tenant has not switched attendance on every
 * route answers {@code RESOURCE_NOT_FOUND}.
 *
 * <p>A roster entry is a plan, never a source of paid time and never a gate. Names appear only for a
 * caller who also holds {@code staff.profile.read} at the branch; otherwise a person is the
 * display reference.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/locations/{locationId}/staff/roster")
@Tag(name = "Staff roster", description = "The rota of non-courier staff at one branch (ADR 0156)")
public class StaffRosterController {

    private final StaffRosterService roster;
    private final CurrentActor currentActor;
    private final AuthorizationService authorization;

    public StaffRosterController(
            StaffRosterService roster, CurrentActor currentActor, AuthorizationService authorization) {
        this.roster = roster;
        this.currentActor = currentActor;
        this.authorization = authorization;
    }

    @GetMapping
    @RequiresCapability(value = Capability.STAFF_SHIFT_READ, scope = ScopeType.LOCATION)
    @Operation(
            summary = "The branch's rota between two days",
            description = "Entries that overlap the local days from..to (inclusive, in the branch's own timezone, "
                    + "at most 62 days). Cancelled entries are not on the grid. A name is shown only to a "
                    + "caller who also holds staff.profile.read.")
    public WeekResponse week(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        Branch branch = new Branch(tenantId, brandId, locationId);
        WeekView view = roster.week(branch, from, to, seesNames(branch));
        return new WeekResponse(
                view.timezone(),
                view.from(),
                view.to(),
                view.entries().stream().map(StaffRosterEntryResponse::of).toList());
    }

    @GetMapping("/people")
    @RequiresCapability(value = Capability.STAFF_SHIFT_MANAGE, scope = ScopeType.LOCATION)
    @Operation(
            summary = "Who can be put on this branch's rota",
            description = "Active staff holding a job that covers this branch: the picker behind «Добавить смену».")
    public List<PersonResponse> people(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID locationId) {
        Branch branch = new Branch(tenantId, brandId, locationId);
        return roster.rosterable(branch, seesNames(branch)).stream()
                .map(person -> PersonResponse.of(person.memberId(), person))
                .toList();
    }

    @PostMapping("/entries")
    @RequiresCapability(value = Capability.STAFF_SHIFT_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Draft a roster entry",
            description = "A DRAFT entry for an active person with a job at this branch. 409 ROSTER_OVERLAP when "
                    + "the person already has a live entry overlapping that time, at any branch. Sixteen hours "
                    + "is a data-sanity bound, not a legal limit.")
    public ResponseEntity<StaffRosterEntryResponse> create(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody EntryRequest body) {
        Branch branch = new Branch(tenantId, brandId, locationId);
        EntryView created = roster.create(branch, body.toCommand(), actor(), correlation());
        return ResponseEntity.status(HttpStatus.CREATED)
                .location(URI.create("/api/v1/operations/tenants/%s/brands/%s/locations/%s/staff/roster/entries/%s"
                        .formatted(tenantId, brandId, locationId, created.id())))
                .eTag(AggregateVersion.toETag(created.version()))
                .body(StaffRosterEntryResponse.of(created));
    }

    @PutMapping("/entries/{entryId}")
    @RequiresCapability(value = Capability.STAFF_SHIFT_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Move a draft or published entry",
            description = "Needs If-Match with the entry's version. The person cannot change: cancel and draft "
                    + "another, so the trail says who was taken off and who was put on.")
    public ResponseEntity<StaffRosterEntryResponse> update(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID entryId,
            @Valid @RequestBody EntryRequest body,
            HttpServletRequest request) {
        Branch branch = new Branch(tenantId, brandId, locationId);
        EntryView updated = roster.update(
                branch,
                entryId,
                (int) AggregateVersion.requireIfMatch(request),
                body.toCommand(),
                actor(),
                correlation());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(updated.version()))
                .body(StaffRosterEntryResponse.of(updated));
    }

    @PostMapping("/entries/{entryId}/cancellations")
    @RequiresCapability(value = Capability.STAFF_SHIFT_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Cancel a draft or published entry",
            description = "Needs If-Match and a reason code from the closed list. A published entry that was "
                    + "worked is CONSUMED and cannot be cancelled.")
    public ResponseEntity<Void> cancel(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @PathVariable UUID entryId,
            @Valid @RequestBody RosterReasonRequest body,
            HttpServletRequest request) {
        roster.cancel(
                new Branch(tenantId, brandId, locationId),
                entryId,
                (int) AggregateVersion.requireIfMatch(request),
                body.reasonCode(),
                actor(),
                correlation());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/publications")
    @RequiresCapability(value = Capability.STAFF_SHIFT_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Publish a range of the rota",
            description = "DRAFT -> PUBLISHED for entries starting on the local days from..to (inclusive). "
                    + "Answers the count and the people by display reference, for the confirmation.")
    public RosterPublicationResponse publish(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody RangeRequest body) {
        var result = roster.publish(
                new Branch(tenantId, brandId, locationId), body.from(), body.to(), actor(), correlation());
        return new RosterPublicationResponse(result.published(), result.people());
    }

    @PostMapping("/copies")
    @RequiresCapability(value = Capability.STAFF_SHIFT_MANAGE, scope = ScopeType.LOCATION, mutating = true)
    @Operation(
            summary = "Copy a week of the rota as drafts",
            description = "Copies the non-cancelled entries starting in the seven days from fromWeek onto the "
                    + "seven days from toWeek, as drafts, shifting by whole local days. An entry whose person "
                    + "is no longer active here, or that would overlap, is skipped and counted.")
    public CopyResponse copy(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID locationId,
            @Valid @RequestBody CopyRequest body) {
        var result = roster.copyWeek(
                new Branch(tenantId, brandId, locationId), body.fromWeek(), body.toWeek(), actor(), correlation());
        return new CopyResponse(result.created(), result.skipped());
    }

    // ------------------------------------------------------------------------------- plumbing

    private boolean seesNames(Branch branch) {
        return authorization.has(currentActor.get().subject(), Capability.STAFF_PROFILE_READ, branch.scope());
    }

    private String actor() {
        return currentActor.get().subject();
    }

    private static String correlation() {
        return WorkforceWeb.correlation();
    }

    /** Every field boxed or checked: Jackson 3 refuses a missing primitive. */
    record EntryRequest(
            @NotNull UUID memberId,
            @NotNull Instant plannedStart,
            @NotNull Instant plannedEnd,

            @Min(0) @Max(240) @org.jspecify.annotations.Nullable
            Integer plannedBreakMinutes) {
        CreateEntry toCommand() {
            return new CreateEntry(
                    memberId, plannedStart, plannedEnd, plannedBreakMinutes == null ? 0 : plannedBreakMinutes);
        }
    }

    record RosterReasonRequest(@NotNull ReasonCode reasonCode) {}

    record RangeRequest(@NotNull LocalDate from, @NotNull LocalDate to) {}

    record CopyRequest(@NotNull LocalDate fromWeek, @NotNull LocalDate toWeek) {}

    record RosterPublicationResponse(int published, List<String> people) {}

    record CopyResponse(int created, int skipped) {}
}
