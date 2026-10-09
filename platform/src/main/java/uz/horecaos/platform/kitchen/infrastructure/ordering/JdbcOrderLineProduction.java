package uz.horecaos.platform.kitchen.infrastructure.ordering;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.ordering.api.OrderLineProductionPort;

/**
 * Answers ordering's question about how far the kitchen has got with a line (ADR 0169), from the ticket items.
 *
 * <p>Reads only; the kitchen never writes an order. A line with several items (an amendment can re-queue a
 * difference) reports the most advanced one that is still alive, because one finished item means a dish exists.
 * A cancelled item is a line the kitchen no longer cooks and counts as nothing.
 */
@Component
public class JdbcOrderLineProduction implements OrderLineProductionPort {

    private final JdbcClient jdbc;

    public JdbcOrderLineProduction(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<UUID, State> stateOfLines(UUID tenantId, UUID orderId, Collection<UUID> orderLineIds) {
        if (orderLineIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, State> byLine = new HashMap<>();
        jdbc.sql("""
                SELECT ti.order_line_id, ti.status
                  FROM kitchen.ticket_items ti
                  JOIN kitchen.tickets t ON t.tenant_id = ti.tenant_id AND t.id = ti.ticket_id
                 WHERE ti.tenant_id = :tenantId AND ti.order_line_id = ANY(:lines)
                   AND t.order_id = :orderId AND ti.status <> 'CANCELLED'
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .param("lines", orderLineIds.toArray(UUID[]::new))
                .query((row, number) -> {
                    State state =
                            switch (row.getString("status")) {
                                case "QUEUED" -> State.NOT_STARTED;
                                case "STARTED" -> State.STARTED;
                                case "READY" -> State.MADE;
                                default -> State.UNKNOWN;
                            };
                    byLine.merge(
                            row.getObject("order_line_id", UUID.class), state, JdbcOrderLineProduction::moreAdvanced);
                    return state;
                })
                .list();
        return Map.copyOf(byLine);
    }

    private static State moreAdvanced(State left, State right) {
        return rank(left) >= rank(right) ? left : right;
    }

    private static int rank(State state) {
        return switch (state) {
            case UNKNOWN -> 0;
            case NOT_STARTED -> 1;
            case STARTED -> 2;
            case MADE -> 3;
        };
    }
}
