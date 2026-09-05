package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.api.OrderDirectory;

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

    @Override
    @Transactional(readOnly = true)
    public List<ApprovalDeadlineWarning> ordersNearingApprovalDeadline(Instant now, Duration within, int limit) {
        return orders.ordersNearingApprovalDeadline(now, now.plus(within), limit);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecentOrder> recentForCustomer(UUID tenantId, UUID brandId, UUID customerAccountId, int limit) {
        return orders.listForCustomer(tenantId, brandId, customerAccountId, null, null, limit).stream()
                .map(row -> new RecentOrder(
                        row.orderId(),
                        row.publicOrderNumber(),
                        row.locationId(),
                        row.status().name(),
                        row.currency(),
                        row.totalMinor(),
                        row.createdAt()))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Counts counts(UUID tenantId, UUID brandId, UUID locationId) {
        var row = orders.counts(tenantId, brandId, locationId);
        return new Counts(
                row.newOrders(),
                row.awaitingApproval(),
                row.inKitchen(),
                row.ready(),
                row.fulfilling(),
                row.completed(),
                row.cancelled(),
                row.totalNonTerminal(),
                row.total());
    }
}
