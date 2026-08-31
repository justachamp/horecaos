package uz.horecaos.platform.kitchen.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The order facts the kitchen needs to build a ticket (ADR 0041).
 *
 * <p>Read-only, and deliberately narrow. The kitchen needs to know which variants
 * were ordered so it can route them, how many of each, what the branch promised,
 * and the number the pass calls out. It needs nothing else, and in particular it
 * must not carry the customer's note or name: ADR 0029 keeps those under envelope
 * encryption in {@code ordering.order_lines}, and a display that needs them
 * resolves them through an authorized read against the order rather than through
 * a copy the kitchen made.
 */
public interface KitchenOrderSource {

    /**
     * Finds the order facts a ticket is built from.
     *
     * @return empty when no order of that id belongs to this tenant, which is the
     *         same answer as "it does not exist" and deliberately so
     */
    Optional<OrderForKitchen> find(UUID tenantId, UUID orderId);

    /**
     * The order facts one ticket is built from.
     *
     * @param promisedAt          when the customer was promised the food, or null
     *                            when V0023 recorded the promise as NOT_PROMISED —
     *                            which is honest and must not be read as "now"
     * @param promisePrepMinutes  the preparation component. Null when V0023 did
     *                            not model it, not zero
     * @param promiseTravelMinutes the road component. Null on a delivery order
     *                            means travel was not modelled at all, not that it
     *                            was zero, so the kitchen must not subtract it
     * @param status              the order's ADR 0019 status at the moment of the
     *                            read, so the kitchen can refuse to build a ticket
     *                            for an order that never reached CONFIRMED
     */
    record OrderForKitchen(
            UUID orderId,
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String publicOrderNumber,
            String fulfillmentMode,
            String channelCode,
            String status,
            @Nullable Instant promisedAt,
            @Nullable Integer promisePrepMinutes,
            @Nullable Integer promiseTravelMinutes,
            int version,
            List<OrderLineForKitchen> lines) {

        public OrderForKitchen {
            lines = List.copyOf(lines);
        }
    }

    /**
     * One line of one order, as the kitchen needs to route and count it.
     *
     * @param productId nullable in {@code ordering.order_lines}, so routing must
     *                  cope with a line that names only a variant rather than
     *                  assuming a product level exists to fall back to
     */
    record OrderLineForKitchen(
            UUID orderLineId, int lineNumber, @Nullable UUID productId, UUID variantId, int quantity) {}
}
