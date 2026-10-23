package uz.horecaos.platform.ordering.application;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.customers.spi.CustomerErasureGuard;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderErasureStore;

/**
 * Reports the orders a customer still expects (ADR 0029: refuse while a delivery is open).
 *
 * <p>Erasing the snapshot of an order that is still being prepared would strip the kitchen and the courier of the name,
 * the number and the address the order cannot be delivered without. The erasure waits for the order to be completed,
 * cancelled, rejected or expired, and then runs unchanged; the request stays {@code PENDING} meanwhile. Reports a count,
 * never an order id.
 */
@Component
public class OrderingErasureGuard implements CustomerErasureGuard {

    /** The code the console maps to words. */
    public static final String OPEN_ORDER = "OPEN_ORDER";

    private final JdbcOrderErasureStore orders;

    public OrderingErasureGuard(JdbcOrderErasureStore orders) {
        this.orders = orders;
    }

    @Override
    public List<Block> blocksFor(UUID tenantId, UUID customerAccountId) {
        int open = orders.openOrders(tenantId, customerAccountId);
        return open == 0 ? List.of() : List.of(new Block(OPEN_ORDER, open));
    }
}
