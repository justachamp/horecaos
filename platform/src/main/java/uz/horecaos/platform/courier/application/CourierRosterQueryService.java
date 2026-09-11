package uz.horecaos.platform.courier.application;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.BranchBindingRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.ComplianceSummaryRow;
import uz.horecaos.platform.courier.infrastructure.persistence.JdbcCourierStore.CourierGroupRow;
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

    /**
     * One courier as the detail pane behind the roster reads them (IA 3.3).
     *
     * <p>Everything here is either already on the list row or is presence rather
     * than content: which compliance fields exist, which groups the courier is
     * in, which branches they ride for. Not one decrypted value — opening a
     * courier shows a manager that the file is complete, and reading what is in
     * it is the separate, audited act {@code courier.pii.reveal} gates.
     */
    public Optional<CourierDetail> detail(UUID tenantId, UUID courierId) {
        return couriers.findRosterEntry(tenantId, courierId)
                .map(row -> new CourierDetail(
                        new RosterEntry(
                                row,
                                activeAssignments
                                        .byCourier(tenantId, List.of(row.id()))
                                        .getOrDefault(row.id(), 0)),
                        couriers.findComplianceSummary(tenantId, courierId).orElseThrow(),
                        couriers.groupsOf(tenantId, courierId),
                        couriers.bindingsOf(tenantId, courierId)));
    }

    /** @param activeAssignments carried orders right now; absent from the map reads as zero. */
    public record RosterEntry(CourierRosterRow courier, int activeAssignments) {}

    public record CourierDetail(
            RosterEntry entry,
            ComplianceSummaryRow compliance,
            List<CourierGroupRow> groups,
            List<BranchBindingRow> branches) {}
}
