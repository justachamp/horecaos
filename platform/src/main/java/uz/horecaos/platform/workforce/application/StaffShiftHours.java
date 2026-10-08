package uz.horecaos.platform.workforce.application;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore.ShiftRow;

/**
 * The one query owner of payroll hours (ADR 0156, Facts, metrics and the payroll export).
 *
 * <p>The day close builds {@code reporting.fact_staff_shift} from {@link StaffShiftFactSourceImpl},
 * and the {@code STAFF_ATTENDANCE} export reads {@link StaffAttendanceExportAdapter}; both read the
 * closed shifts of a range through <em>this</em> method, so the export's total equals the fact's total
 * for the same range by construction and not by two queries that happen to agree. A test pins it.
 *
 * <p>"Closed" is the whole definition of a payroll hour: an open shift, one awaiting approval and a
 * voided one are not, and the range is business dates, inclusive at both ends.
 */
@Service
public class StaffShiftHours {

    /** A single read never returns more than this many rows; the export refuses a range over its own ceiling first. */
    static final int READ_LIMIT = 50_000;

    private final JdbcStaffShiftStore shifts;

    public StaffShiftHours(JdbcStaffShiftStore shifts) {
        this.shifts = shifts;
    }

    @Transactional(readOnly = true)
    public List<ShiftRow> closed(UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds) {
        return shifts.inRange(tenantId, from, to, locationIds, false, READ_LIMIT);
    }

    @Transactional(readOnly = true)
    public List<ShiftRow> closedIncludingUnresolved(
            UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds, int limit) {
        return shifts.inRange(tenantId, from, to, locationIds, true, limit);
    }

    /** Paid seconds across the closed shifts of a range: the figure a payroll file's total must equal. */
    @Transactional(readOnly = true)
    public long paidSeconds(UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds) {
        return closed(tenantId, from, to, locationIds).stream()
                .mapToLong(shift -> shift.paidSeconds() == null ? 0 : shift.paidSeconds())
                .sum();
    }
}
