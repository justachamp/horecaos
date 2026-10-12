package uz.horecaos.platform.kitchen.application.port;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The payment facts the kitchen reads about an order, and nothing else (ADR 0168).
 *
 * <p>Read-only, in the shape of {@link KitchenOrderSource}, implemented over {@code ordering.orders} by a
 * plain SQL read. {@code kitchen} reads the order's status and its payment projection and never decides
 * what they mean: that is {@code ordering.api.PaymentStatusFacts}, which {@code kitchen} and {@code
 * ordering} share. It carries no customer, no amount and no payment method.
 */
public interface KitchenPaymentSource {

    /**
     * The status and payment projection of each order, in one read. An order that does not exist for this
     * tenant is simply absent from the answer.
     */
    Map<UUID, OrderState> orderState(UUID tenantId, Collection<UUID> orderIds);

    /**
     * Buffered tickets that carry no hold whose order's payment is required and absent: the sweep's
     * candidates. {@code HELD} tickets only, oldest first, across every tenant.
     */
    List<UnpaidBufferedTicket> unpaidBufferedTickets(int limit);

    /**
     * @param status        the order's {@code OrderStatus} name
     * @param paymentStatus {@code ordering.orders.payment_status_projection}
     */
    record OrderState(String status, String paymentStatus) {}

    /** A buffered ticket and the projection that makes it a candidate. */
    record UnpaidBufferedTicket(UUID tenantId, UUID ticketId, UUID orderId, String orderStatus, String paymentStatus) {}

    /** A source that knows no order: what a kitchen assembled without ordering's rows sees. */
    KitchenPaymentSource NONE = new KitchenPaymentSource() {
        @Override
        public Map<UUID, OrderState> orderState(UUID tenantId, Collection<UUID> orderIds) {
            return Map.of();
        }

        @Override
        public List<UnpaidBufferedTicket> unpaidBufferedTickets(int limit) {
            return List.of();
        }
    };
}
