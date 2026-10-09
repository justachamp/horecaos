package uz.horecaos.platform.workforce.application;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.iam.api.staff.StaffPayrollIdentity;
import uz.horecaos.platform.iam.api.staff.StaffPayrollIdentity.PayrollIdentity;
import uz.horecaos.platform.workforce.api.StaffAttendanceExportPort;
import uz.horecaos.platform.workforce.domain.ShiftStatus;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore;
import uz.horecaos.platform.workforce.infrastructure.persistence.JdbcStaffShiftStore.ShiftRow;

/**
 * {@link StaffAttendanceExportPort}: the payroll file's rows (ADR 0156, ADR 0131).
 *
 * <p>The identity group is resolved <em>here</em>, through {@link StaffPayrollIdentity}, once per
 * distinct member of the export (hundreds, not once per row) and only when the caller holds {@code
 * staff.shift.export}; {@code reporting} never sees a name. The result goes into the file that route
 * authorised and nowhere else: not a log line, a metric, an event or an audit fact.
 *
 * <p>Hours, never pay: no wage, no overtime, no night or holiday premium and no deduction is computed
 * (ADR 0156 Decision 10). The location's IANA zone and the start and end instants are carried so a
 * payroll system can.
 */
@Service
public class StaffAttendanceExportAdapter implements StaffAttendanceExportPort {

    private final AttendanceSettings settings;
    private final JdbcStaffShiftStore shifts;
    private final StaffShiftHours hours;
    private final StaffPayrollIdentity identity;

    public StaffAttendanceExportAdapter(
            AttendanceSettings settings,
            JdbcStaffShiftStore shifts,
            StaffShiftHours hours,
            StaffPayrollIdentity identity) {
        this.settings = settings;
        this.shifts = shifts;
        this.hours = hours;
        this.identity = identity;
    }

    @Override
    public boolean attendanceEnabled(UUID tenantId) {
        return settings.enabled(tenantId);
    }

    @Override
    public int unresolvedShifts(UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds) {
        return shifts.countUnresolved(tenantId, from, to, locationIds);
    }

    @Override
    public int exportableShifts(
            UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds, boolean includeUnresolved) {
        return shifts.countExportable(tenantId, from, to, locationIds, includeUnresolved);
    }

    @Override
    public ExportBundle export(
            UUID tenantId,
            LocalDate from,
            LocalDate to,
            List<UUID> locationIds,
            boolean includeUnresolved,
            boolean includeIdentity,
            int rowCeiling) {
        int total = exportableShifts(tenantId, from, to, locationIds, includeUnresolved);
        List<ShiftRow> rows = includeUnresolved
                ? hours.closedIncludingUnresolved(tenantId, from, to, locationIds, rowCeiling)
                : hours.closed(tenantId, from, to, locationIds).stream()
                        .limit(rowCeiling)
                        .toList();

        List<UUID> memberIds = rows.stream().map(ShiftRow::memberId).distinct().toList();
        Map<UUID, String> references = shifts.displayReferences(tenantId, memberIds);
        Map<UUID, PayrollIdentity> identities = includeIdentity ? identity.identityOf(tenantId, memberIds) : Map.of();
        Map<UUID, String> zones = new HashMap<>();

        List<ExportedRow> exported = rows.stream()
                .map(shift -> {
                    PayrollIdentity who = identities.get(shift.memberId());
                    boolean settled = shift.status() == ShiftStatus.CLOSED;
                    return new ExportedRow(
                            references.getOrDefault(shift.memberId(), "S-0000"),
                            shift.businessDate(),
                            shift.locationId(),
                            shift.plannedSeconds(),
                            settled ? shift.workedSeconds() : null,
                            shift.breakSeconds(),
                            settled ? shift.paidSeconds() : null,
                            settled ? shift.varianceSeconds() : null,
                            settled
                                    ? (shift.approvedBy() == null ? "WITHIN_THRESHOLD" : "APPROVED")
                                    : shift.status().name(),
                            shift.startedAt(),
                            shift.endedAt(),
                            zones.computeIfAbsent(
                                    shift.locationId(),
                                    location -> shifts.timezoneOf(tenantId, location)
                                            .orElse("UTC")),
                            who == null ? null : who.employeeNumber(),
                            who == null ? null : who.name());
                })
                .toList();
        return new ExportBundle(exported, total);
    }
}
