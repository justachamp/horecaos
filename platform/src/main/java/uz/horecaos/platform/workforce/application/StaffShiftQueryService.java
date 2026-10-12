package uz.horecaos.platform.workforce.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.workforce.application.StaffPeople.Person;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore.Cursor;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore.ShiftRow;

/**
 * The timesheet list at branch, brand or tenant reach (ADR 0156): keyset paged, newest start first,
 * voided shifts included because voiding is a recorded fact and not a deletion.
 */
@Service
public class StaffShiftQueryService {

    static final int DEFAULT_PAGE = 50;
    static final int MAXIMUM_PAGE = 200;

    private final JdbcStaffShiftStore shifts;
    private final StaffPeople people;
    private final AttendanceSettings settings;

    public StaffShiftQueryService(JdbcStaffShiftStore shifts, StaffPeople people, AttendanceSettings settings) {
        this.shifts = shifts;
        this.people = people;
        this.settings = settings;
    }

    public record Item(ShiftRow shift, @Nullable Person person) {}

    public record Page(List<Item> items, @Nullable Cursor next) {}

    @Transactional(readOnly = true)
    public Page list(
            UUID tenantId,
            @Nullable UUID brandId,
            @Nullable UUID locationId,
            @Nullable UUID memberId,
            @Nullable LocalDate from,
            @Nullable LocalDate to,
            @Nullable Cursor after,
            int limit,
            boolean withNames) {
        settings.requireEnabled(tenantId);
        if (from != null && to != null && to.isBefore(from)) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "The range must run forward", Map.of("field", "from"));
        }
        int bounded = Math.clamp(limit, 1, MAXIMUM_PAGE);
        List<ShiftRow> rows = shifts.list(tenantId, brandId, locationId, memberId, from, to, after, bounded + 1);
        boolean more = rows.size() > bounded;
        List<ShiftRow> page = more ? rows.subList(0, bounded) : rows;
        Map<UUID, Person> directory = people.peopleOf(
                tenantId, page.stream().map(ShiftRow::memberId).distinct().toList(), withNames);
        List<Item> items = page.stream()
                .map(row -> new Item(row, directory.get(row.memberId())))
                .toList();
        Cursor next = more
                ? new Cursor(
                        page.get(page.size() - 1).startedAt(),
                        page.get(page.size() - 1).id())
                : null;
        return new Page(items, next);
    }

    /** Whether an instant is representable in a cursor: guards a hand-made one. */
    static boolean plausible(Instant instant) {
        return instant.isAfter(Instant.parse("2000-01-01T00:00:00Z"))
                && instant.isBefore(Instant.parse("2100-01-01T00:00:00Z"));
    }
}
