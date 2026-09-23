package uz.horecaos.platform.ordering.infrastructure.persistence;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;

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
    private final JdbcClient jdbc;

    public JdbcOrderDirectory(JdbcOrderStore orders, JdbcClient jdbc) {
        this.orders = orders;
        this.jdbc = jdbc;
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

    /**
     * Joins straight to {@code tenant.sales_channels} rather than through a
     * tenancy port, the same posture {@code JdbcTemplateStore.smsWordingAwaitsGateway}
     * already takes for a cross-schema read this small: the rule needs one
     * column ({@code system_type}), not tenancy's own types.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<NotificationContext> notificationContext(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT o.fulfillment_mode, c.system_type
                  FROM ordering.orders o
                  JOIN tenant.sales_channels c ON c.tenant_id = o.tenant_id AND c.id = o.channel_id
                 WHERE o.tenant_id = :tenantId AND o.id = :orderId
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> new NotificationContext(
                        FulfillmentMode.valueOf(row.getString("fulfillment_mode")),
                        SalesChannelSystemType.require(row.getString("system_type"))))
                .optional();
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
