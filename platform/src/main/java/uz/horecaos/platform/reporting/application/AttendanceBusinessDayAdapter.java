package uz.horecaos.platform.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;
import uz.horecaos.platform.workforce.api.AttendanceBusinessDay;

/**
 * Reporting's answer to the workforce module's {@link AttendanceBusinessDay} port (ADR 0156, ADR 0043)
 * -- the staff-hours twin of {@link CourierBusinessDayWindowsAdapter}, {@link BusinessDayWindowsAdapter}
 * and their siblings.
 *
 * <p>Thin on purpose, for the reason {@link BusinessDayWindowsAdapter}'s own doc gives: the arithmetic
 * is {@link BusinessDayBoundary}'s and the resolution order is {@link BusinessDayService}'s, and neither
 * is re-stated here. A shift's business date is therefore the date the tenant's {@code
 * reporting.business_day_start} would give, and a payroll fact keyed by it agrees with every other fact
 * of the same day.
 */
@Component
public class AttendanceBusinessDayAdapter implements AttendanceBusinessDay {

    private final BusinessDayService businessDays;

    public AttendanceBusinessDayAdapter(BusinessDayService businessDays) {
        this.businessDays = businessDays;
    }

    @Override
    public LocalDate businessDateOf(UUID tenantId, Instant at) {
        return businessDays.boundaryFor(tenantId).dateOf(at);
    }

    @Override
    public Window windowOf(UUID tenantId, LocalDate businessDate) {
        BusinessDayBoundary boundary = businessDays.boundaryFor(tenantId);
        return new Window(boundary.startOf(businessDate), boundary.endOf(businessDate));
    }
}
