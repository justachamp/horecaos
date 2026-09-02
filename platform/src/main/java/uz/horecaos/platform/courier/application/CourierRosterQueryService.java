package uz.horecaos.platform.courier.application;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierRosterRow;
import uz.horecaos.platform.fulfillment.api.InternalFleetPort;

/**
 * The in-house roster, with today's load (IA operations §3.3, §3.1's fleet
 * rail).
 *
 * <p>One query serves both screens deliberately: §3.3 Couriers and §3.1
 * Dispatch board's fleet rail are both "who is engaged and how loaded are
 * they right now", and a second, near-identical projection would drift from
 * this one the first time somebody fixed a bug in only one of them.
 *
 * <p>Load comes from {@link InternalFleetPort.ActiveAssignments}, the same
 * count sourcing itself reads before offering an order — imported from
 * {@code fulfillment.api} rather than reimplemented here, for the reason its
 * own Javadoc gives: two places counting {@code fulfillment.shipments} is how
 * a courier ends up carrying one more order than his vehicle class allows.
 */
@Service
public class CourierRosterQueryService {

    private final JdbcCourierStore couriers;
    private final InternalFleetPort.ActiveAssignments activeAssignments;

    public CourierRosterQueryService(JdbcCourierStore couriers, InternalFleetPort.ActiveAssignments activeAssignments) {
        this.couriers = couriers;
        this.activeAssignments = activeAssignments;
    }

    public List<RosterEntry> roster(UUID tenantId) {
        List<CourierRosterRow> rows = couriers.listCouriers(tenantId);
        Map<UUID, Integer> load = activeAssignments.byCourier(
                tenantId, rows.stream().map(CourierRosterRow::id).toList());
        return rows.stream()
                .map(row -> new RosterEntry(row, load.getOrDefault(row.id(), 0)))
                .toList();
    }

    /** @param activeAssignments carried orders right now; absent from the map reads as zero. */
    public record RosterEntry(CourierRosterRow courier, int activeAssignments) {}
}
