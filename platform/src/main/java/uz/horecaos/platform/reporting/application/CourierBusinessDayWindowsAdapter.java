package uz.horecaos.platform.reporting.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.courier.api.BusinessDayWindows;
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;

/**
 * Reporting's answer to the Courier module's {@link BusinessDayWindows} port
 * (ADR 0043, the T11 review that found {@code CourierAccrualService} stamping
 * a UTC calendar date instead) — the courier-side twin of {@link
 * BusinessDayWindowsAdapter}, which answers the identically-directed port
 * {@code ordering.api} declares.
 *
 * <p>Thin on purpose, the same reason {@link BusinessDayWindowsAdapter}'s own
 * doc gives: the arithmetic is {@link BusinessDayBoundary}'s and the
 * resolution order is {@link BusinessDayService}'s, and neither is re-stated
 * here.
 */
@Component
public class CourierBusinessDayWindowsAdapter implements BusinessDayWindows {

    private final BusinessDayService businessDays;

    public CourierBusinessDayWindowsAdapter(BusinessDayService businessDays) {
        this.businessDays = businessDays;
    }

    @Override
    public java.time.LocalDate businessDateOf(UUID tenantId, Instant at) {
        BusinessDayBoundary boundary = businessDays.boundaryFor(tenantId);
        return boundary.dateOf(at);
    }
}
