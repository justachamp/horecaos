package uz.qoida.platform.fulfillment.infrastructure.sourcing;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import uz.qoida.platform.fulfillment.api.InternalFleetPort;

/**
 * How many orders each courier is already carrying (ADR 0014).
 *
 * <p>Counted from {@code fulfillment.shipments} and not from
 * {@code assignment_attempts}. An accepted attempt is the moment a courier said
 * yes; the shipment is the order in his hands, and it is the shipment that
 * reaches {@code DELIVERED} or {@code CANCELLED} and stops being carried. Both
 * tables would agree today because an acceptance creates exactly one shipment,
 * but only one of them is updated when the order finishes, and counting the
 * other would leave every courier permanently at capacity by the end of his
 * first evening.
 *
 * <p>The predicate is V0054's {@code ix_shipment_courier_open} verbatim —
 * {@code courier_id IS NOT NULL AND status NOT IN ('DELIVERED', 'CANCELLED')} —
 * so this query uses the partial index rather than scanning around it. That
 * index was created for this question and, until ADR 0042's dispatch half
 * landed, was asked it by nobody.
 *
 * <p>Tenant-scoped, and the courier ids are constrained inside the tenant they
 * arrived with rather than trusted on their own. The caller is the courier
 * module answering a question about one branch; a courier id that belongs to
 * another tenant simply produces no row.
 */
@Component
public class JdbcActiveAssignments implements InternalFleetPort.ActiveAssignments {

    private final JdbcClient jdbc;

    public JdbcActiveAssignments(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, Integer> byCourier(UUID tenantId, Collection<UUID> courierIds) {
        if (courierIds.isEmpty()) {
            // NamedParameterJdbcTemplate renders an empty collection as `IN ()`,
            // which PostgreSQL rejects. An empty question also has an answer.
            return Map.of();
        }

        List<Carrying> rows = jdbc.sql("""
                SELECT courier_id, count(*) AS carrying
                  FROM fulfillment.shipments
                 WHERE tenant_id = :tenantId
                   AND courier_id IN (:courierIds)
                   AND status NOT IN ('DELIVERED', 'CANCELLED')
                 GROUP BY courier_id
                """)
                .param("tenantId", tenantId)
                .param("courierIds", courierIds)
                .query((row, rowNumber) -> new Carrying(
                        row.getObject("courier_id", UUID.class), row.getInt("carrying")))
                .list();

        Map<UUID, Integer> carried = new HashMap<>();
        for (Carrying row : rows) {
            carried.put(row.courierId(), row.carrying());
        }
        return Map.copyOf(carried);
    }

    private record Carrying(UUID courierId, int carrying) { }
}
