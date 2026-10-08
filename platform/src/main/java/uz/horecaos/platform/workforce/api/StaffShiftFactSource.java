package uz.horecaos.platform.workforce.api;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The closed shifts of a business day, for the day-close fact {@code reporting.fact_staff_shift}
 * (ADR 0156, ADR 0043).
 *
 * <p>Implemented by {@code workforce}, read by {@code reporting} through this port -- never a report
 * reading a {@code workforce} table (ADR 0023). It answers {@code CLOSED} shifts only: an {@code
 * OPEN} or {@code AWAITING_APPROVAL} shift is not a fact until it settles, so "a shift past the
 * threshold waits for approval and stays out of the fact until approved" is true by construction.
 * The payroll export reads the same query owner ({@code StaffShiftHours}), so the export's total and
 * the fact's total for one range cannot disagree about which shifts count.
 *
 * <p>No name, phone or employee number crosses it: {@code staffDisplayReference} («S-0142») is the
 * non-personal handle ADR 0029 allows in a fact.
 */
public interface StaffShiftFactSource {

    List<ClosedShift> closedShiftsOn(UUID tenantId, LocalDate businessDate);

    /**
     * @param plannedSeconds null when no roster entry was matched -- null is not zero
     * @param varianceSeconds null exactly when {@code plannedSeconds} is
     */
    record ClosedShift(
            UUID shiftId,
            UUID brandId,
            UUID locationId,
            UUID staffMemberId,
            String staffDisplayReference,
            @Nullable Long plannedSeconds,
            long workedSeconds,
            int breakSeconds,
            long paidSeconds,
            @Nullable Long varianceSeconds,
            ApprovalStatus approvalStatus,
            boolean hadRosterEntry,
            Instant startedAt,
            Instant endedAt) {}

    /** {@code WITHIN_THRESHOLD}: settled at close. {@code APPROVED}: waited, and a person looked. */
    enum ApprovalStatus {
        WITHIN_THRESHOLD,
        APPROVED
    }
}
