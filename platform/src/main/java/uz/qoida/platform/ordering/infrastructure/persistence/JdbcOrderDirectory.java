package uz.qoida.platform.ordering.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import uz.qoida.platform.ordering.api.OrderDirectory;

/**
 * {@link OrderDirectory} over the order store (ADR 0019).
 *
 * <p>A projection of {@code OrderRow} rather than a second query, so the tenant
 * predicate that store already applies is the one that applies here too, and the
 * two cannot drift into disagreeing about what a consumer may see.
 */
@Service
public class JdbcOrderDirectory implements OrderDirectory {

    private final JdbcOrderStore orders;

    public JdbcOrderDirectory(JdbcOrderStore orders) {
        this.orders = orders;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<OrderSummary> summary(UUID tenantId, UUID orderId) {
        return orders.find(tenantId, orderId)
                .map(order -> new OrderSummary(
                        order.orderId(),
                        order.tenantId(),
                        order.brandId(),
                        order.locationId(),
                        order.publicOrderNumber(),
                        order.customerAccountId(),
                        order.guestReferenceHash(),
                        order.status().name(),
                        order.currency(),
                        order.totalMinor(),
                        order.version()));
    }
}
