package uz.horecaos.platform.workforce.application;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.workforce.api.StaffShiftFactSource;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore.ShiftRow;

/**
 * {@link StaffShiftFactSource} over {@link StaffShiftHours}: the closed shifts of a business day, with
 * the non-personal display reference and no name (ADR 0156). Whether {@code workforce.attendance.enabled}
 * is on does not change what a closed shift is -- a tenant that turned the feature off keeps its rows
 * and its facts; it only stops recording new ones.
 */
@Service
public class StaffShiftFactSourceImpl implements StaffShiftFactSource {

    private final StaffShiftHours hours;
    private final JdbcStaffShiftStore shifts;

    public StaffShiftFactSourceImpl(StaffShiftHours hours, JdbcStaffShiftStore shifts) {
        this.hours = hours;
        this.shifts = shifts;
    }

    @Override
    public List<ClosedShift> closedShiftsOn(UUID tenantId, LocalDate businessDate) {
        List<ShiftRow> closed = hours.closed(tenantId, businessDate, businessDate, List.of());
        Map<UUID, String> references = shifts.displayReferences(
                tenantId, closed.stream().map(ShiftRow::memberId).distinct().toList());
        return closed.stream()
                .map(shift -> new ClosedShift(
                        shift.id(),
                        shift.brandId(),
                        shift.locationId(),
                        shift.memberId(),
                        references.getOrDefault(shift.memberId(), "S-0000"),
                        shift.plannedSeconds(),
                        shift.workedSeconds() == null ? 0 : shift.workedSeconds(),
                        shift.breakSeconds(),
                        shift.paidSeconds() == null ? 0 : shift.paidSeconds(),
                        shift.varianceSeconds(),
                        shift.approvedBy() == null ? ApprovalStatus.WITHIN_THRESHOLD : ApprovalStatus.APPROVED,
                        shift.rosterEntryId() != null,
                        shift.startedAt(),
                        shift.endedAt() == null ? shift.startedAt() : shift.endedAt()))
                .toList();
    }
}
