package uz.horecaos.platform.fulfillment.application;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.fulfillment.api.ActiveCourierAssignmentsPort;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcAssignmentStore;

/**
 * {@link ActiveCourierAssignmentsPort}: a thin read over {@code
 * fulfillment.shipments}, batched over a page of orders (gap map row 1.1).
 *
 * <p>Deliberately its own tiny service rather than folded into {@code
 * JdbcAssignmentStore} itself, which already answers to a dozen sourcing/
 * dispatch call sites that have no business acquiring a port bean — the same
 * separation {@code ShipmentCancellationService} keeps from that store.
 */
@Service
public class CourierAssignmentQueryService implements ActiveCourierAssignmentsPort {

    private final JdbcAssignmentStore assignments;

    public CourierAssignmentQueryService(JdbcAssignmentStore assignments) {
        this.assignments = assignments;
    }

    @Override
    public Map<UUID, UUID> assignedCouriers(UUID tenantId, Set<UUID> orderIds) {
        return assignments.courierIdsByOrders(tenantId, orderIds);
    }
}
