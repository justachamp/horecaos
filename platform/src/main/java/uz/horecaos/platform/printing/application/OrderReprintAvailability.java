package uz.horecaos.platform.printing.application;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.api.OrderReprintPort;
import uz.horecaos.platform.printing.infrastructure.JdbcPrintingStore;

/**
 * {@link OrderReprintPort} over printing's own switch and printer list (ADR 0154, orders.md §2.12's {@code p}).
 *
 * <p>A branch can print when {@code feature.printing} is on for it (the tenant's switch, dark by default) and an
 * {@code ACTIVE} printer is registered there. The switch is resolved per branch because a brand's branches can
 * differ (ADR 0030); the printer list is read once for the page. No document is built and nothing is queued: this
 * only says whether offering «Печать» would lead anywhere.
 */
@Component
public class OrderReprintAvailability implements OrderReprintPort {

    private final PrintingPolicy policy;
    private final JdbcPrintingStore store;

    public OrderReprintAvailability(PrintingPolicy policy, JdbcPrintingStore store) {
        this.policy = policy;
        this.store = store;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<UUID> branchesThatCanPrint(UUID tenantId, UUID brandId, Collection<UUID> locationIds) {
        Set<UUID> switchedOn = new LinkedHashSet<>();
        for (UUID locationId : locationIds) {
            if (policy.enabled(tenantId, brandId, locationId)) {
                switchedOn.add(locationId);
            }
        }
        if (switchedOn.isEmpty()) {
            return Set.of();
        }
        Set<UUID> withAPrinter = store.locationsWithActivePrinter(tenantId, switchedOn);
        switchedOn.retainAll(withAPrinter);
        return switchedOn;
    }
}
