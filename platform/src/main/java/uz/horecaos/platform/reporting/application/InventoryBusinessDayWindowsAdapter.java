package uz.horecaos.platform.reporting.application;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.inventory.api.BusinessDayWindows;
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;

/**
 * Reporting's answer to the Inventory module's {@link BusinessDayWindows}
 * port (ADR 0017 QUANTITY branch, gap map row 4.4c) — the inventory-side twin
 * of {@link CourierBusinessDayWindowsAdapter} and {@link
 * BusinessDayWindowsAdapter}.
 *
 * <p>Thin on purpose, the same reason those two adapters' own doc gives: the
 * arithmetic is {@link BusinessDayBoundary}'s and the resolution order is
 * {@link BusinessDayService}'s, and neither is re-stated here.
 */
@Component
public class InventoryBusinessDayWindowsAdapter implements BusinessDayWindows {

    private final BusinessDayService businessDays;

    public InventoryBusinessDayWindowsAdapter(BusinessDayService businessDays) {
        this.businessDays = businessDays;
    }

    @Override
    public LocalDate businessDateOf(UUID tenantId, Instant at) {
        BusinessDayBoundary boundary = businessDays.boundaryFor(tenantId);
        return boundary.dateOf(at);
    }
}
