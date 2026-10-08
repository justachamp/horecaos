package uz.horecaos.platform.workforce.web;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.workforce.application.StaffPeople.Person;
import uz.horecaos.platform.workforce.application.StaffRosterService.EntryView;
import uz.horecaos.platform.workforce.application.StaffShiftService.ShiftView;
import uz.horecaos.platform.workforce.application.StaffTodayService.Row;
import uz.horecaos.platform.workforce.domain.RosterStatus;
import uz.horecaos.platform.workforce.domain.ShiftStatus;
import uz.horecaos.platform.workforce.domain.TodayStatus;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffRosterStore.EntryRow;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore.ShiftRow;

/**
 * The wire shapes of the rota and the timesheet (ADR 0156). Optional fields are nullable and no
 * response carries a primitive a client could omit: Jackson 3 refuses a missing primitive in a
 * request, and a response with one invites the matching client.
 *
 * <p>A person is a member id, the non-personal display reference («S-0142») and, only when the caller
 * may see names, a name. Nothing here carries a phone, a position, an address or a device.
 */
final class WorkforceResponses {

    private WorkforceResponses() {}

    record PersonResponse(
            UUID memberId,
            String displayReference,
            @Nullable String name) {
        static @Nullable PersonResponse of(UUID memberId, @Nullable Person person) {
            return person == null ? null : new PersonResponse(memberId, person.displayReference(), person.name());
        }

        @Override
        public String toString() {
            return "PersonResponse[" + displayReference + "]";
        }
    }

    record EntryResponse(
            UUID id,
            UUID locationId,
            @Nullable PersonResponse person,
            RosterStatus status,
            Instant plannedStart,
            Instant plannedEnd,
            int plannedBreakMinutes,
            @Nullable Instant publishedAt,
            int version) {
        static EntryResponse of(EntryView view) {
            return new EntryResponse(
                    view.id(),
                    view.locationId(),
                    PersonResponse.of(view.memberId(), view.person()),
                    view.status(),
                    view.plannedStart(),
                    view.plannedEnd(),
                    view.plannedBreakMinutes(),
                    view.publishedAt(),
                    view.version());
        }
    }

    record WeekResponse(String timezone, Instant from, Instant to, List<EntryResponse> entries) {}

    record ShiftResponse(
            UUID id,
            UUID locationId,
            @Nullable PersonResponse person,
            ShiftStatus status,
            Instant startedAt,
            @Nullable Instant endedAt,
            String openSource,
            @Nullable String closeSource,
            int breakSeconds,
            @Nullable Long workedSeconds,
            @Nullable Long paidSeconds,
            @Nullable Long plannedSeconds,
            @Nullable Long varianceSeconds,
            @Nullable Integer varianceThresholdMinutes,
            LocalDate businessDate,
            boolean hadRosterEntry,
            @Nullable String editReasonCode,
            @Nullable Instant approvedAt,
            @Nullable String voidReasonCode,
            int version) {
        static ShiftResponse of(ShiftView view) {
            return of(view.shift(), view.person());
        }

        static ShiftResponse of(ShiftRow shift, @Nullable Person person) {
            return new ShiftResponse(
                    shift.id(),
                    shift.locationId(),
                    PersonResponse.of(shift.memberId(), person),
                    shift.status(),
                    shift.startedAt(),
                    shift.endedAt(),
                    shift.openSource().name(),
                    shift.closeSource() == null ? null : shift.closeSource().name(),
                    shift.breakSeconds(),
                    shift.workedSeconds(),
                    shift.paidSeconds(),
                    shift.plannedSeconds(),
                    shift.varianceSeconds(),
                    shift.varianceThresholdMinutes(),
                    shift.businessDate(),
                    shift.rosterEntryId() != null,
                    shift.editReasonCode(),
                    shift.approvedAt(),
                    shift.voidReasonCode(),
                    shift.version());
        }
    }

    record ShiftPageResponse(
            List<ShiftResponse> items, @Nullable String nextCursor) {}

    record TodayRowResponse(
            @Nullable PersonResponse person,
            TodayStatus status,
            @Nullable Instant plannedStart,
            @Nullable Instant plannedEnd,
            @Nullable UUID shiftId,
            @Nullable Instant startedAt,
            @Nullable Instant endedAt,
            @Nullable ShiftStatus shiftStatus,
            @Nullable Integer shiftVersion) {
        static TodayRowResponse of(Row row) {
            return new TodayRowResponse(
                    PersonResponse.of(row.memberId(), row.person()),
                    row.status(),
                    row.plannedStart(),
                    row.plannedEnd(),
                    row.shiftId(),
                    row.startedAt(),
                    row.endedAt(),
                    row.shiftStatus(),
                    row.shiftVersion());
        }
    }

    /**
     * @param ordersOnly always true: the screen says in words that «Работает без смены» sees only
     *     people who take orders, because a shared device principal advances every kitchen ticket
     */
    record TodayResponse(
            LocalDate businessDate,
            Instant from,
            Instant to,
            Instant asOf,
            boolean ordersOnly,
            List<TodayRowResponse> rows) {}

    record UpcomingEntryResponse(
            UUID id, UUID locationId, Instant plannedStart, Instant plannedEnd, int plannedBreakMinutes) {
        static UpcomingEntryResponse of(EntryRow entry) {
            return new UpcomingEntryResponse(
                    entry.id(),
                    entry.locationId(),
                    entry.plannedStart(),
                    entry.plannedEnd(),
                    entry.plannedBreakMinutes());
        }
    }
}
