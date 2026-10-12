package uz.horecaos.platform.workforce.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.workforce.application.StaffPeople.Person;
import uz.horecaos.platform.workforce.domain.ReasonCode;
import uz.horecaos.platform.workforce.domain.RosterStatus;
import uz.horecaos.platform.workforce.domain.ShiftArithmetic;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffRosterStore;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffRosterStore.EntryRow;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffRosterStore.Published;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore;

/**
 * The rota of one branch (ADR 0156 Decision 3): drafting, moving, publishing, copying and cancelling
 * roster entries for non-courier staff.
 *
 * <p>A rota, not an offer: an employee is scheduled and does not accept, so there is no accepted or
 * declined state, and a person who cannot work tells the manager, who edits the entry. The roster is
 * the <em>plan</em> and never a source of paid time -- hours come from the shift, the fact. It never
 * gates anything: a Friday with no rota still has hours, because a clock-in needs no entry.
 *
 * <p>Every route is behind {@code workforce.attendance.enabled}; with it off each call answers
 * {@code RESOURCE_NOT_FOUND} before touching a table. Rostering needs the person's employment {@code
 * ACTIVE} and a job at the branch, and answers "no such member" for anyone else.
 */
@Service
public class StaffRosterService {

    /** A planned window is a week grid; more than this at once is a mistake, not a roster. */
    static final int MAXIMUM_RANGE_DAYS = 62;

    private final JdbcStaffRosterStore roster;
    private final JdbcStaffShiftStore shifts;
    private final StaffPeople people;
    private final AttendanceSettings settings;
    private final WorkforceAudit audit;
    private final Clock clock;

    public StaffRosterService(
            JdbcStaffRosterStore roster,
            JdbcStaffShiftStore shifts,
            StaffPeople people,
            AttendanceSettings settings,
            WorkforceAudit audit,
            Clock clock) {
        this.roster = roster;
        this.shifts = shifts;
        this.people = people;
        this.settings = settings;
        this.audit = audit;
        this.clock = clock;
    }

    /** One entry as a screen shows it. {@code person} carries a name only when the caller may see names. */
    public record EntryView(
            UUID id,
            UUID locationId,
            @Nullable Person person,
            UUID memberId,
            RosterStatus status,
            Instant plannedStart,
            Instant plannedEnd,
            int plannedBreakMinutes,
            @Nullable Instant publishedAt,
            int version) {}

    public record WeekView(String timezone, Instant from, Instant to, List<EntryView> entries) {}

    public record CreateEntry(UUID memberId, Instant plannedStart, Instant plannedEnd, int plannedBreakMinutes) {}

    public record PublishResult(int published, List<String> people) {}

    public record CopyResult(int created, int skipped) {}

    // ------------------------------------------------------------------------------------ reads

    @Transactional(readOnly = true)
    public WeekView week(Branch branch, LocalDate from, LocalDate to, boolean withNames) {
        settings.requireEnabled(branch.tenantId());
        ZoneId zone = zoneOf(branch);
        Instant start = startOf(from, zone);
        Instant end = startOf(to.plusDays(1), zone);
        requireRange(from, to);
        List<EntryRow> rows = roster.overlapping(branch.tenantId(), branch.locationId(), start, end);
        return new WeekView(zone.getId(), start, end, views(branch.tenantId(), rows, withNames));
    }

    /** The same grid for a brand or the whole tenant (BRAND and TENANT reads of the same list). */
    @Transactional(readOnly = true)
    public WeekView weekWider(UUID tenantId, @Nullable UUID brandId, LocalDate from, LocalDate to, boolean withNames) {
        settings.requireEnabled(tenantId);
        requireRange(from, to);
        // A brand or tenant read spans branches in more than one zone; the day boundary used here is
        // UTC and the response says so. A branch's own grid is the one that draws local days.
        Instant start = startOf(from, ZoneId.of("UTC"));
        Instant end = startOf(to.plusDays(1), ZoneId.of("UTC"));
        List<EntryRow> rows = roster.overlappingWider(tenantId, brandId, start, end, 2000);
        return new WeekView("UTC", start, end, views(tenantId, rows, withNames));
    }

    /** Who may be put on this branch's rota: active, with a job covering the branch. */
    @Transactional(readOnly = true)
    public List<Person> rosterable(Branch branch, boolean withNames) {
        settings.requireEnabled(branch.tenantId());
        return people.rosterableAt(branch, withNames);
    }

    // ----------------------------------------------------------------------------------- writes

    @Transactional
    public EntryView create(Branch branch, CreateEntry command, String actor, String correlationId) {
        settings.requireEnabled(branch.tenantId());
        Person person = eligible(branch, command.memberId());
        validateWindow(command.plannedStart(), command.plannedEnd(), command.plannedBreakMinutes());
        Instant now = clock.instant();
        EntryRow entry = new EntryRow(
                Ids.newId(),
                branch.tenantId(),
                branch.brandId(),
                branch.locationId(),
                command.memberId(),
                RosterStatus.DRAFT,
                command.plannedStart(),
                command.plannedEnd(),
                command.plannedBreakMinutes(),
                actor,
                null,
                null,
                null,
                1,
                now,
                now);
        try {
            roster.insert(entry);
        } catch (DataIntegrityViolationException failure) {
            throw conflict(failure);
        }
        audit.recordCreated(
                "workforce.roster.drafted",
                branch,
                actor,
                "workforce_roster_entry",
                entry.id(),
                "A roster entry was drafted",
                entryDocument(person.displayReference(), entry),
                "staff.shift.manage",
                correlationId,
                now);
        return view(entry, person);
    }

    @Transactional
    public EntryView update(
            Branch branch, UUID entryId, int expectedVersion, CreateEntry command, String actor, String correlationId) {
        settings.requireEnabled(branch.tenantId());
        EntryRow before = find(branch, entryId);
        Person person = eligible(branch, before.memberId());
        if (!command.memberId().equals(before.memberId())) {
            // Moving an entry to another person is a new entry: cancel this one and draft another, so the
            // audit trail says who was taken off the rota and who was put on.
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "An entry's person cannot be changed", Map.of("field", "memberId"));
        }
        if (before.status() != RosterStatus.DRAFT && before.status() != RosterStatus.PUBLISHED) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "Only a draft or published entry can be changed",
                    Map.of("status", before.status().name()));
        }
        validateWindow(command.plannedStart(), command.plannedEnd(), command.plannedBreakMinutes());
        int changed;
        try {
            changed = roster.updateWindow(
                    branch.tenantId(),
                    entryId,
                    expectedVersion,
                    command.plannedStart(),
                    command.plannedEnd(),
                    command.plannedBreakMinutes());
        } catch (DataIntegrityViolationException failure) {
            throw conflict(failure);
        }
        if (changed == 0) {
            throw stale(expectedVersion, before.version());
        }
        Instant now = clock.instant();
        audit.recordChange(
                "workforce.roster.revised",
                branch,
                actor,
                "workforce_roster_entry",
                entryId,
                "A roster entry's window was changed",
                window(before),
                window(command),
                "staff.shift.manage",
                correlationId,
                now);
        return view(find(branch, entryId), person);
    }

    @Transactional
    public void cancel(
            Branch branch, UUID entryId, int expectedVersion, ReasonCode reason, String actor, String correlationId) {
        settings.requireEnabled(branch.tenantId());
        EntryRow before = find(branch, entryId);
        if (before.status() != RosterStatus.DRAFT && before.status() != RosterStatus.PUBLISHED) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    "Only a draft or published entry can be cancelled",
                    Map.of("status", before.status().name()));
        }
        if (roster.cancel(branch.tenantId(), entryId, expectedVersion, reason.name()) == 0) {
            throw stale(expectedVersion, before.version());
        }
        Map<String, String> reference =
                people.peopleOf(branch.tenantId(), List.of(before.memberId()), false).values().stream()
                        .findFirst()
                        .map(person -> Map.of("staffRef", person.displayReference()))
                        .orElse(Map.of());
        Map<String, Object> beforeDoc = new LinkedHashMap<>(reference);
        beforeDoc.put("status", before.status().name());
        Map<String, Object> afterDoc = new LinkedHashMap<>(reference);
        afterDoc.put("status", RosterStatus.CANCELLED.name());
        audit.recordChange(
                "workforce.roster.cancelled",
                branch,
                actor,
                "workforce_roster_entry",
                entryId,
                reason.name(),
                beforeDoc,
                afterDoc,
                "staff.shift.manage",
                correlationId,
                clock.instant());
    }

    /**
     * DRAFT -> PUBLISHED for the entries starting in {@code [from, to]} (local days of the branch).
     * One fact for the publication, naming the people by display reference.
     */
    @Transactional
    public PublishResult publish(Branch branch, LocalDate from, LocalDate to, String actor, String correlationId) {
        settings.requireEnabled(branch.tenantId());
        requireRange(from, to);
        ZoneId zone = zoneOf(branch);
        Instant now = clock.instant();
        List<Published> published = roster.publishDrafts(
                branch.tenantId(), branch.locationId(), startOf(from, zone), startOf(to.plusDays(1), zone), actor, now);
        List<String> references = people
                .peopleOf(
                        branch.tenantId(),
                        published.stream().map(Published::memberId).distinct().toList(),
                        false)
                .values()
                .stream()
                .map(Person::displayReference)
                .sorted()
                .toList();
        if (!published.isEmpty()) {
            Map<String, Object> after = new LinkedHashMap<>();
            after.put("status", RosterStatus.PUBLISHED.name());
            after.put("entryCount", published.size());
            after.put("people", references);
            after.put("from", from.toString());
            after.put("to", to.toString());
            audit.recordChange(
                    "workforce.roster.published",
                    branch,
                    actor,
                    "workforce_roster",
                    branch.locationId(),
                    "A range of the rota was published",
                    Map.of("status", RosterStatus.DRAFT.name()),
                    after,
                    "staff.shift.manage",
                    correlationId,
                    now);
        }
        return new PublishResult(published.size(), references);
    }

    /**
     * Copies a week's non-cancelled entries onto another week as drafts, shifting by whole local days
     * (so a wall-clock shift stays on its wall clock across a clock change). An entry whose person is
     * no longer active at this branch, or that overlaps something already on the target week, is
     * skipped and counted, never forced.
     */
    @Transactional
    public CopyResult copyWeek(
            Branch branch, LocalDate fromWeek, LocalDate toWeek, String actor, String correlationId) {
        settings.requireEnabled(branch.tenantId());
        if (fromWeek.equals(toWeek)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "Copy a week onto a different week", Map.of("field", "toWeek"));
        }
        ZoneId zone = zoneOf(branch);
        Instant sourceFrom = startOf(fromWeek, zone);
        Instant sourceTo = startOf(fromWeek.plusDays(7), zone);
        long shiftDays = ChronoUnit.DAYS.between(fromWeek, toWeek);
        Instant now = clock.instant();
        int created = 0;
        int skipped = 0;
        for (EntryRow source : roster.overlapping(branch.tenantId(), branch.locationId(), sourceFrom, sourceTo)) {
            if (source.plannedStart().isBefore(sourceFrom)
                    || !source.plannedStart().isBefore(sourceTo)) {
                continue;
            }
            if (people.eligibleAt(branch, source.memberId(), false).isEmpty()) {
                skipped++;
                continue;
            }
            Instant start = ZonedDateTime.ofInstant(source.plannedStart(), zone)
                    .plusDays(shiftDays)
                    .toInstant();
            Instant end = ZonedDateTime.ofInstant(source.plannedEnd(), zone)
                    .plusDays(shiftDays)
                    .toInstant();
            EntryRow copy = new EntryRow(
                    Ids.newId(),
                    branch.tenantId(),
                    branch.brandId(),
                    branch.locationId(),
                    source.memberId(),
                    RosterStatus.DRAFT,
                    start,
                    end,
                    source.plannedBreakMinutes(),
                    actor,
                    null,
                    null,
                    null,
                    1,
                    now,
                    now);
            // Not a try/catch around insert(): a violation inside this transaction would abort it, and
            // every statement after it (the next copy, the audit fact) would be refused. An overlap here
            // means the target week already holds something for this person at that time.
            if (roster.insertIfFree(copy)) {
                created++;
            } else {
                skipped++;
            }
        }
        if (created > 0) {
            Map<String, Object> document = new LinkedHashMap<>();
            document.put("entryCount", created);
            document.put("from", fromWeek.toString());
            document.put("to", toWeek.toString());
            audit.recordCreated(
                    "workforce.roster.drafted",
                    branch,
                    actor,
                    "workforce_roster",
                    branch.locationId(),
                    "A week of the rota was copied as drafts",
                    document,
                    "staff.shift.manage",
                    correlationId,
                    now);
        }
        return new CopyResult(created, skipped);
    }

    // ---------------------------------------------------------------------------------- internals

    private List<EntryView> views(UUID tenantId, List<EntryRow> rows, boolean withNames) {
        Map<UUID, Person> directory = people.peopleOf(
                tenantId, rows.stream().map(EntryRow::memberId).distinct().toList(), withNames);
        List<EntryView> views = new ArrayList<>(rows.size());
        for (EntryRow row : rows) {
            views.add(view(row, directory.get(row.memberId())));
        }
        return views;
    }

    private static EntryView view(EntryRow row, @Nullable Person person) {
        return new EntryView(
                row.id(),
                row.locationId(),
                person,
                row.memberId(),
                row.status(),
                row.plannedStart(),
                row.plannedEnd(),
                row.plannedBreakMinutes(),
                row.publishedAt(),
                row.version());
    }

    private Person eligible(Branch branch, UUID memberId) {
        return people.eligibleAt(branch, memberId, false)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such staff member."));
    }

    private EntryRow find(Branch branch, UUID entryId) {
        return roster.find(branch.tenantId(), branch.locationId(), entryId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such roster entry."));
    }

    private ZoneId zoneOf(Branch branch) {
        return shifts.timezoneOf(branch.tenantId(), branch.locationId())
                .map(ZoneId::of)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such branch."));
    }

    private static Instant startOf(LocalDate date, ZoneId zone) {
        return date.atStartOfDay(zone).toInstant();
    }

    private static void requireRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) > MAXIMUM_RANGE_DAYS) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "The range must run forward and cover at most " + MAXIMUM_RANGE_DAYS + " days",
                    Map.of("field", "from"));
        }
    }

    static void validateWindow(Instant start, Instant end, int plannedBreakMinutes) {
        if (!ShiftArithmetic.isSaneSpan(start, end)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A planned window ends after it starts and lasts at most " + ShiftArithmetic.MAXIMUM_SPAN.toHours()
                            + " hours",
                    Map.of("field", "plannedEnd"));
        }
        if (plannedBreakMinutes < 0
                || plannedBreakMinutes > 240
                || Duration.ofMinutes(plannedBreakMinutes).compareTo(Duration.between(start, end)) > 0) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "A planned break is between 0 and 240 minutes and no longer than the window",
                    Map.of("field", "plannedBreakMinutes"));
        }
    }

    private static Map<String, Object> entryDocument(String staffRef, EntryRow entry) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("staffRef", staffRef);
        document.putAll(window(entry));
        return document;
    }

    private static Map<String, Object> window(EntryRow entry) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("plannedStart", entry.plannedStart().toString());
        document.put("plannedEnd", entry.plannedEnd().toString());
        document.put("plannedBreakMinutes", entry.plannedBreakMinutes());
        return document;
    }

    private static Map<String, Object> window(CreateEntry command) {
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("plannedStart", command.plannedStart().toString());
        document.put("plannedEnd", command.plannedEnd().toString());
        document.put("plannedBreakMinutes", command.plannedBreakMinutes());
        return document;
    }

    private static ApiException conflict(DataIntegrityViolationException failure) {
        return new ApiException(
                ErrorCode.RESOURCE_CONFLICT,
                "This person already has a roster entry overlapping that time.",
                Map.of("reason", "ROSTER_OVERLAP"));
    }

    private static ApiException stale(int expected, int actual) {
        return ApiException.staleVersion(expected, actual);
    }
}
