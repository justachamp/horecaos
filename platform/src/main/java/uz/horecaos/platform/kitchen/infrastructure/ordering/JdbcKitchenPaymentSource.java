package uz.horecaos.platform.kitchen.infrastructure.ordering;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.kitchen.application.port.KitchenPaymentSource;
import uz.horecaos.platform.ordering.api.PaymentStatusFacts;

/**
 * {@link KitchenPaymentSource} over {@code ordering.orders} (ADR 0168), the idiom {@code
 * JdbcKitchenOrderSource} uses: a plain read, no write, no import of an ordering table's class.
 *
 * <p>The ticket and the order are one tenant by composite key, so every statement carries the tenant. The
 * sweep's candidate read is the only one that does not name a tenant, on purpose: it is a system sweep
 * across every tenant's buffer, like {@code claimDueForRelease}, and the tenant travels back on each row.
 */
@Component
public class JdbcKitchenPaymentSource implements KitchenPaymentSource {

    private final JdbcClient jdbc;

    public JdbcKitchenPaymentSource(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, OrderState> orderState(UUID tenantId, Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, OrderState> states = new HashMap<>();
        jdbc.sql("""
                SELECT id, status, payment_status_projection
                FROM ordering.orders
                WHERE tenant_id = :tenantId AND id IN (:orderIds)
                """)
                .param("tenantId", tenantId)
                .param("orderIds", orderIds)
                .query((row, number) -> Map.entry(
                        row.getObject("id", UUID.class),
                        new OrderState(row.getString("status"), row.getString("payment_status_projection"))))
                .list()
                .forEach(entry -> states.put(entry.getKey(), entry.getValue()));
        return states;
    }

    @Override
    public List<UnpaidBufferedTicket> unpaidBufferedTickets(int limit) {
        return jdbc.sql("""
                SELECT t.tenant_id, t.id AS ticket_id, t.order_id, o.status, o.payment_status_projection
                FROM kitchen.tickets t
                JOIN ordering.orders o ON o.id = t.order_id AND o.tenant_id = t.tenant_id
                WHERE t.status = 'HELD' AND t.hold_reason IS NULL
                  AND o.payment_status_projection IN (:requiredAndAbsent)
                ORDER BY t.created_at
                LIMIT :limit
                """)
                .param("requiredAndAbsent", PaymentStatusFacts.REQUIRED_AND_ABSENT)
                .param("limit", limit)
                .query((row, number) -> new UnpaidBufferedTicket(
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("ticket_id", UUID.class),
                        row.getObject("order_id", UUID.class),
                        row.getString("status"),
                        row.getString("payment_status_projection")))
                .list();
    }
}
