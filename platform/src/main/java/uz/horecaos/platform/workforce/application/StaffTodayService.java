package uz.horecaos.platform.workforce.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.api.OperatorActivity;
import uz.horecaos.platform.workforce.api.AttendanceBusinessDay;
import uz.horecaos.platform.workforce.application.StaffPeople.Person;
import uz.horecaos.platform.workforce.domain.RosterStatus;
import uz.horecaos.platform.workforce.domain.ShiftStatus;
import uz.horecaos.platform.workforce.domain.TodayStatus;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffRosterStore;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffRosterStore.EntryRow;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore.ShiftRow;

/**
 * «Сегодня» (ADR 0156, Specification): who is late, who is working, who has a shift open too long,
 * derived per person from the roster, the shifts, the tenant's business day and order attribution.
 *
 * <p>Every statement of "today" is the tenant's <em>business day</em> (ADR 0043), and a future day
 * never renders as missed -- only entries whose start falls inside today's window are read at all.
 * The list is polled every thirty seconds by the console; no realtime channel is added.
 *
 * <p><strong>«Работает без смены» sees only people who take orders.</strong> It is raised when the
 * person created or accepted an order at this branch today and has no open shift, and only order
 * attribution can raise it: a cook who only marks tickets ready is never flagged, because a shared
 * device principal advances every kitchen ticket and names no human (ADR 0079). The screen says so in
 * words. Its only effect is that a manager can open or correct a shift afterwards; it refuses nothing
 * at the time, and nothing in this class can.
 */
@Service
public class StaffTodayService {

    private final JdbcStaffRosterStore roster;
    private final JdbcStaffShiftStore shifts;
    private final StaffPeople people;
    private final AttendanceSettings settings;
    private final AttendanceBusinessDay businessDays;
    private final OperatorActivity orders;
    private final Clock clock;

    public StaffTodayService(
            JdbcStaffRosterStore roster,
            JdbcStaffShiftStore shifts,
            StaffPeople people,
            AttendanceSettings settings,
            AttendanceBusinessDay businessDays,
            OperatorActivity orders,
            Clock clock) {
        this.roster = roster;
        this.shifts = shifts;
        this.people = people;
        this.settings = settings;
        this.businessDays = businessDays;
        this.orders = orders;
        this.clock = clock;
    }

    /**
     * One person on today's list. {@code person} carries a name only when the caller may see names.
     */
    public record Row(
            UUID memberId,
            @Nullable Person person,
            TodayStatus status,
            @Nullable Instant plannedStart,
            @Nullable Instant plannedEnd,
            @Nullable UUID shiftId,
            @Nullable Instant startedAt,
            @Nullable Instant endedAt,
            @Nullable ShiftStatus shiftStatus,
            @Nullable Integer shiftVersion) {}

    public record TodayView(LocalDate businessDate, Instant from, Instant to, Instant asOf, List<Row> rows) {}

    @Transactional(readOnly = true)
    public TodayView today(Branch branch, boolean withNames) {
        settings.requireEnabled(branch.tenantId());
        Instant now = clock.instant();
        LocalDate date = businessDays.businessDateOf(branch.tenantId(), now);
        AttendanceBusinessDay.Window window = businessDays.windowOf(branch.tenantId(), date);

        List<EntryRow> entries =
                roster.overlapping(branch.tenantId(), branch.locationId(), window.from(), window.to()).stream()
                        .filter(entry -> !entry.plannedStart().isBefore(window.from())
                                && entry.plannedStart().isBefore(window.to()))
                        .toList();
        List<ShiftRow> todays = shifts.forToday(branch.tenantId(), branch.locationId(), window.from(), window.to());

        Set<String> operatorSubjects = orders.operatorsAt(
                branch.tenantId(), branch.locationId(), window.from(), now.isBefore(window.to()) ? now : window.to());

        Set<UUID> memberIds = new LinkedHashSet<>();
        entries.forEach(entry -> memberIds.add(entry.memberId()));
        todays.forEach(shift -> memberIds.add(shift.memberId()));
        Map<String, UUID> operatorMembers = new HashMap<>();
        for (String subject : operatorSubjects) {
            people.memberIdOf(branch.tenantId(), subject).ifPresent(member -> {
                operatorMembers.put(subject, member);
                memberIds.add(member);
            });
        }
        Set<UUID> operatorMemberIds = new HashSet<>(operatorMembers.values());
        Map<UUID, Person> directory = people.peopleOf(branch.tenantId(), memberIds, withNames);

        Duration lateAfter = Duration.ofMinutes(settings.lateAfterMinutes(branch));
        Duration maxOpen = Duration.ofHours(settings.maxOpenHours(branch));

        List<Row> rows = new ArrayList<>();
        for (UUID memberId : memberIds) {
            Person person = directory.get(memberId);
            if (person == null) {
                continue;
            }
            List<EntryRow> theirEntries = entries.stream()
                    .filter(entry -> entry.memberId().equals(memberId))
                    .toList();
            List<ShiftRow> theirShifts = todays.stream()
                    .filter(shift -> shift.memberId().equals(memberId))
                    .toList();
            rows.add(derive(
                    memberId,
                    person,
                    theirEntries,
                    theirShifts,
                    operatorMemberIds.contains(memberId),
                    now,
                    lateAfter,
                    maxOpen));
        }
        rows.sort(Comparator.comparing((Row row) -> row.status().ordinal())
                .thenComparing(
                        row -> row.plannedStart() == null ? row.startedAt() : row.plannedStart(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(row -> row.person() == null ? "" : row.person().displayReference()));
        return new TodayView(date, window.from(), window.to(), now, rows);
    }

    /**
     * The status in the spec's order of urgency: late, missed today, working without a shift, open
     * too long, working, closed, and finally merely scheduled. Each is checked only if its condition
     * holds, so the first that does is the answer.
     */
    static Row derive(
            UUID memberId,
            Person person,
            List<EntryRow> entries,
            List<ShiftRow> shifts,
            boolean tookOrdersToday,
            Instant now,
            Duration lateAfter,
            Duration maxOpen) {
        @Nullable
        ShiftRow open = shifts.stream()
                .filter(shift -> shift.status() == ShiftStatus.OPEN)
                .findFirst()
                .orElse(null);
        @Nullable
        ShiftRow lastClosed = shifts.stream()
                .filter(shift ->
                        shift.status() == ShiftStatus.CLOSED || shift.status() == ShiftStatus.AWAITING_APPROVAL)
                .max(Comparator.comparing(ShiftRow::startedAt))
                .orElse(null);
        @Nullable
        EntryRow uncovered = entries.stream()
                .filter(entry -> entry.status() == RosterStatus.PUBLISHED || entry.status() == RosterStatus.MISSED)
                .min(Comparator.comparing(EntryRow::plannedStart))
                .orElse(null);
        @Nullable
        EntryRow shown = uncovered != null
                ? uncovered
                : entries.stream()
                        .min(Comparator.comparing(EntryRow::plannedStart))
                        .orElse(null);

        TodayStatus status;
        if (open == null
                && uncovered != null
                && uncovered.status() == RosterStatus.PUBLISHED
                && uncovered.plannedEnd().isAfter(now)
                && uncovered.plannedStart().plus(lateAfter).isBefore(now)) {
            status = TodayStatus.LATE;
        } else if (open == null
                && uncovered != null
                && (uncovered.status() == RosterStatus.MISSED
                        || (uncovered.status() == RosterStatus.PUBLISHED
                                && !uncovered.plannedEnd().isAfter(now)))) {
            status = TodayStatus.MISSED_TODAY;
        } else if (open == null && tookOrdersToday) {
            status = TodayStatus.WORKING_WITHOUT_SHIFT;
        } else if (open != null && open.startedAt().plus(maxOpen).isBefore(now)) {
            status = TodayStatus.OPEN_TOO_LONG;
        } else if (open != null) {
            status = TodayStatus.WORKING;
        } else if (lastClosed != null) {
            status = TodayStatus.CLOSED;
        } else {
            status = TodayStatus.SCHEDULED;
        }

        @Nullable ShiftRow shownShift = open != null ? open : lastClosed;
        return new Row(
                memberId,
                person,
                status,
                shown == null ? null : shown.plannedStart(),
                shown == null ? null : shown.plannedEnd(),
                shownShift == null ? null : shownShift.id(),
                shownShift == null ? null : shownShift.startedAt(),
                shownShift == null ? null : shownShift.endedAt(),
                shownShift == null ? null : shownShift.status(),
                shownShift == null ? null : shownShift.version());
    }
}
