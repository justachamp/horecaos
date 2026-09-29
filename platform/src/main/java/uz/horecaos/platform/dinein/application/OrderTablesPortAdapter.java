package uz.horecaos.platform.dinein.application;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.dinein.api.OrderTablesPort;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore;
import uz.horecaos.platform.dinein.infrastructure.persistence.JdbcDineInStore.OrderTableRow;

/**
 * Answers {@link OrderTablesPort} from the session tables (ADR 0047).
 *
 * <p>Folds the store's (order, table) pairs into one {@link OrderTablesPort.OrderTable}
 * per order. An order belongs to at most one session (V0034's {@code
 * uq_session_order_once}), so a single order never mixes two sessions' tables;
 * the fold keys on the order id and trusts that constraint rather than
 * re-deriving it.
 */
@Component
public class OrderTablesPortAdapter implements OrderTablesPort {

    private final JdbcDineInStore store;

    public OrderTablesPortAdapter(JdbcDineInStore store) {
        this.store = store;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, OrderTable> tablesByOrders(UUID tenantId, Collection<UUID> orderIds) {
        if (orderIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Party> partyByOrder = new LinkedHashMap<>();
        for (OrderTableRow row : store.tablesForOrders(tenantId, orderIds)) {
            partyByOrder
                    .computeIfAbsent(row.orderId(), ignored -> new Party(row.sessionId()))
                    .tables
                    .add(new TableRef(row.tableId(), row.code(), row.displayName()));
        }
        Map<UUID, OrderTable> result = new LinkedHashMap<>();
        partyByOrder.forEach((orderId, party) -> result.put(orderId, new OrderTable(party.sessionId, party.tables)));
        return result;
    }

    /** One order's session and the tables read for it so far. */
    private static final class Party {
        private final UUID sessionId;
        private final List<TableRef> tables = new ArrayList<>();

        private Party(UUID sessionId) {
            this.sessionId = sessionId;
        }
    }
}
