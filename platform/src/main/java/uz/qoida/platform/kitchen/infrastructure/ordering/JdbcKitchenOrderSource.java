package uz.qoida.platform.kitchen.infrastructure.ordering;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import uz.qoida.platform.kitchen.application.port.KitchenOrderSource;

/**
 * Reads the handful of order facts a ticket is built from (ADR 0041).
 *
 * <p>Reads, and only reads. ADR 0041 is explicit that the kitchen never writes
 * {@code ordering.orders}; the sole write path back into an order is
 * {@code OrderProgressPort}, which proposes rather than writes.
 *
 * <p>Two columns are read with {@code getObject} rather than {@code getInt} on
 * purpose. {@code promise_prep_minutes} and {@code promise_travel_minutes} are
 * null exactly when V0023 recorded that the component was not modelled, and a
 * silent zero there would make the kitchen subtract nothing for the road and put
 * every delivery ticket on the pass twenty minutes late.
 *
 * <p>Neither the customer's note nor the dish name is selected. Both live on the
 * order, the note under ADR 0029 envelope encryption, and a display resolves them
 * through an authorized read against the order rather than through a copy the
 * kitchen keeps.
 */
@Component
public class JdbcKitchenOrderSource implements KitchenOrderSource {

    private final JdbcClient jdbc;

    public JdbcKitchenOrderSource(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<OrderForKitchen> find(UUID tenantId, UUID orderId) {
        Optional<Header> header = jdbc.sql("""
                SELECT id, tenant_id, brand_id, location_id, public_order_number,
                       fulfillment_mode, channel_code_snapshot, status,
                       promised_at, promise_prep_minutes, promise_travel_minutes, version
                FROM ordering.orders
                WHERE tenant_id = :tenantId AND id = :id
                """)
                .param("tenantId", tenantId).param("id", orderId)
                .query((row, number) -> new Header(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getString("public_order_number"),
                        row.getString("fulfillment_mode"),
                        row.getString("channel_code_snapshot"),
                        row.getString("status"),
                        instant(row.getObject("promised_at", OffsetDateTime.class)),
                        row.getObject("promise_prep_minutes", Integer.class),
                        row.getObject("promise_travel_minutes", Integer.class),
                        row.getInt("version")))
                .optional();

        if (header.isEmpty()) {
            return Optional.empty();
        }

        List<OrderLineForKitchen> lines = jdbc.sql("""
                SELECT id, line_number, source_product_id, source_variant_id, quantity
                FROM ordering.order_lines
                WHERE tenant_id = :tenantId AND order_id = :orderId
                ORDER BY line_number
                """)
                .param("tenantId", tenantId).param("orderId", orderId)
                .query((row, number) -> new OrderLineForKitchen(
                        row.getObject("id", UUID.class),
                        row.getInt("line_number"),
                        // Nullable in V0022: a line may name only a variant.
                        row.getObject("source_product_id", UUID.class),
                        row.getObject("source_variant_id", UUID.class),
                        row.getInt("quantity")))
                .list();

        Header found = header.get();
        return Optional.of(new OrderForKitchen(found.id(), found.tenantId(), found.brandId(),
                found.locationId(), found.publicOrderNumber(), found.fulfillmentMode(),
                found.channelCode(), found.status(), found.promisedAt(), found.prepMinutes(),
                found.travelMinutes(), found.version(), lines));
    }

    private static Instant instant(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private record Header(UUID id, UUID tenantId, UUID brandId, UUID locationId,
            String publicOrderNumber, String fulfillmentMode, String channelCode, String status,
            Instant promisedAt, Integer prepMinutes, Integer travelMinutes, int version) { }
}
