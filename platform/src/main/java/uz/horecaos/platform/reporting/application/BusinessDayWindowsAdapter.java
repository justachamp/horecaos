package uz.horecaos.platform.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.ordering.api.BusinessDayWindows;
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;

/**
 * Reporting's answer to ordering's {@link BusinessDayWindows} port.
 *
 * <p>Thin on purpose: the arithmetic is {@link BusinessDayBoundary}'s and the
 * resolution order is {@link BusinessDayService}'s, and neither is re-stated
 * here. What this class adds is only the direction of the dependency — ordering
 * declares the port, reporting implements it — which is what keeps the two
 * modules acyclic while leaving ADR 0043's boundary in the single place that
 * owns it.
 *
 * <p>It resolves the boundary on every call rather than caching one. A tenant
 * changing its business-day start is a rare, audited act, and a cached window
 * would go on cutting the live board at the old boundary for as long as the
 * cache lived — the exact silent-wrongness ADR 0043 exists to prevent. The read
 * is one indexed row.
 */
@Component
public class BusinessDayWindowsAdapter implements BusinessDayWindows {

    private final BusinessDayService businessDays;

    public BusinessDayWindowsAdapter(BusinessDayService businessDays) {
        this.businessDays = businessDays;
    }

    @Override
    public Window businessDayContaining(UUID tenantId, Instant at) {
        BusinessDayBoundary boundary = businessDays.boundaryFor(tenantId);
        LocalDate date = boundary.dateOf(at);
        return new Window(boundary.startOf(date), boundary.endOf(date));
    }
}
