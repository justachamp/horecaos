package uz.horecaos.platform.workforce.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The source of the {@code STAFF_ATTENDANCE} payroll export (ADR 0156, ADR 0131).
 *
 * <p>Implemented by {@code workforce} and read by {@code reporting}'s export service, the way
 * {@code CustomerDirectoryExportPort} is: the identity columns (a name and an employee number) are
 * resolved here, through {@code iam}'s {@code StaffPayrollIdentity}, and never in {@code reporting}
 * (ADR 0139). The counts exist so the export service can refuse <em>before</em> a request is queued
 * -- a payroll file that will be refused is refused at the door, and not found {@code FAILED} in the
 * history a minute later.
 *
 * <p>The range is business dates, inclusive at both ends, in the tenant's business day. {@code
 * locationIds} empty means every branch the caller's tenant-wide grant already covers.
 */
public interface StaffAttendanceExportPort {

    /** Whether the tenant has turned attendance on ({@code workforce.attendance.enabled}). */
    boolean attendanceEnabled(UUID tenantId);

    /** Shifts in range that are still open or awaiting approval: hours nobody has settled. */
    int unresolvedShifts(UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds);

    /** The rows the file would hold: closed shifts, plus the unresolved ones when asked for. */
    int exportableShifts(
            UUID tenantId, LocalDate from, LocalDate to, List<UUID> locationIds, boolean includeUnresolved);

    /**
     * Every row of the file, in a stable order. The caller has already checked {@link #exportableShifts}
     * against its ceiling and checks again after this returns; {@code rowCeiling} bounds the read so a
     * range that grew between the two cannot read the table.
     *
     * @param includeIdentity true only for a caller holding {@code staff.shift.export}
     */
    ExportBundle export(
            UUID tenantId,
            LocalDate from,
            LocalDate to,
            List<UUID> locationIds,
            boolean includeUnresolved,
            boolean includeIdentity,
            int rowCeiling);

    /** @param totalRows the number of rows the range holds, which may exceed {@code rows} when it grew past the ceiling */
    record ExportBundle(List<ExportedRow> rows, int totalRows) {}

    /**
     * One shift as a payroll file row. Instants and the location's IANA zone are carried so the
     * receiving system can compute night and holiday premiums; the platform computes none.
     *
     * @param approvalStatus {@code WITHIN_THRESHOLD}, {@code APPROVED}, or -- only when unresolved
     *     shifts were asked for -- {@code OPEN} or {@code AWAITING_APPROVAL}, with null paid seconds
     * @param staffName null unless the identity group was asked for and allowed
     * @param employeeNumber likewise
     */
    record ExportedRow(
            String staffDisplayReference,
            LocalDate businessDate,
            UUID locationId,
            @Nullable Long plannedSeconds,
            @Nullable Long workedSeconds,
            int breakSeconds,
            @Nullable Long paidSeconds,
            @Nullable Long varianceSeconds,
            String approvalStatus,
            Instant startedAt,
            @Nullable Instant endedAt,
            String timezone,
            @Nullable String employeeNumber,
            @Nullable String staffName) {

        @Override
        public String toString() {
            return "ExportedRow[" + staffDisplayReference + ", " + businessDate + "]";
        }
    }
}
