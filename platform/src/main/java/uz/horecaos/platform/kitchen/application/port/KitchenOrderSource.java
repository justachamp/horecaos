package uz.horecaos.platform.kitchen.application.port;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.web.api.Quantities;

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
     * The lines of this order that an amendment has closed (ADR 0039), which {@link #find} does
     * not return: a line is never edited, it is closed and its replacement appended.
     *
     * <p>Asked for when an amendment reaches a ticket that is already open, to tell a line that
     * was replaced from one that was added, and to know what the replaced one had already put on
     * the pass.
     */
    List<OrderLineForKitchen> closedLines(UUID tenantId, UUID orderId);

    /**
     * The provider-assigned identifier a courier or customer would quote for
     * each of these orders — never {@code sequenceLabel}, which is HorecaOS's
     * own number (gap map row 2.4, IA 2.4's "provider-assigned external
     * identifiers shown to humans"; ADR 0040's {@code
     * ordering.order_external_references}).
     *
     * <p>An order carries several reference rows — {@code PARTNER_ORDER_ID},
     * {@code PARTNER_DISPLAY_CODE}, {@code PARTNER_VENUE_ORDER_NO}, and the
     * non-partner {@code DELIVERY_CLAIM_ID}/{@code POS_ORDER_ID} kinds this
     * method excludes entirely — and this picks one per order: the partner's
     * own display code first, since that is what a partner's app actually
     * shows a courier or a customer, then the partner's order id, then its
     * venue order number, tied by which arrived first.
     *
     * @return only orders that carry at least one {@code issued_by = 'PARTNER'}
     *         reference; an order with none is simply absent from the map,
     *         which the caller reads the same way {@link #channelSystemTypes}
     *         in the sibling {@code JdbcKitchenStore} treats an unresolved code
     */
    Map<UUID, String> externalReferences(UUID tenantId, Set<UUID> orderIds);

    /**
     * The facts a lateness clock is measured from, one per order (ADR 0150 decision 1; ADR 0036).
     *
     * <p>A ticket's own {@code created_at} is when the kitchen opened it, which for an order that
     * waited in {@code AWAITING_APPROVAL} or was taken for a slot is long after checkout, and its
     * {@code target_ready_at} is the promise less the road. Neither is what the order board measures
     * from. The board's rule is the order's: late once {@code promised_at + lateAfter} has passed, or,
     * with no promise, once {@code created_at + noPromiseFallback} has, and never for a terminal order.
     * A wall or a queue that read the ticket's two instants would start the clock again on acceptance
     * (which ADR 0150 refuses) and call a delivery order late a road-time before the board does, so the
     * kitchen's surfaces read these instead.
     *
     * @return only orders of this tenant that exist; an id with no order is absent from the map
     */
    Map<UUID, OrderClock> clocksByOrders(UUID tenantId, Set<UUID> orderIds);

    /**
     * One order's lateness inputs.
     *
     * @param createdAt  when the order was created: where the no-promise fallback measures from
     * @param promisedAt the promise made at checkout, null when V0023 recorded it as NOT_PROMISED
     * @param terminal   the order is over (completed, cancelled, rejected, expired, payment failed): a
     *                   terminal order is never flagged, whatever its history
     */
    record OrderClock(Instant createdAt, @Nullable Instant promisedAt, boolean terminal) {}

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
     * @param quantity a decimal since ADR 0137: half a portion is half a plate, and the
     *                 ticket says so
     * @param productId nullable in {@code ordering.order_lines}, so routing must
     *                  cope with a line that names only a variant rather than
     *                  assuming a product level exists to fall back to
     * @param comboSelectionId ADR 0136: the key the component lines of one combo purchase
     *                  share, null on every other line. It is carried onto the ticket item so
     *                  a display can group a combo's items under one header; it is an id and
     *                  not a name, because kitchen rows carry no names (ADR 0041)
     * @param comboContainerVariantId the combo this component was bought as part of, set
     *                  exactly when {@code comboSelectionId} is. Routing never reads it: a
     *                  component routes by its own variant, so the grill and the bar each
     *                  still see only their own item
     */
    record OrderLineForKitchen(
            UUID orderLineId,
            int lineNumber,
            @Nullable UUID productId,
            UUID variantId,
            BigDecimal quantity,
            @Nullable UUID comboSelectionId,
            @Nullable UUID comboContainerVariantId) {

        public OrderLineForKitchen {
            quantity = Quantities.normalise(quantity);
        }

        /** A line that is not part of a combo, which is every line before ADR 0136. */
        public OrderLineForKitchen(
                UUID orderLineId, int lineNumber, @Nullable UUID productId, UUID variantId, BigDecimal quantity) {
            this(orderLineId, lineNumber, productId, variantId, quantity, null, null);
        }

        /** A whole number of portions, which is every line there was before ADR 0137. */
        public OrderLineForKitchen(
                UUID orderLineId, int lineNumber, @Nullable UUID productId, UUID variantId, int quantity) {
            this(orderLineId, lineNumber, productId, variantId, BigDecimal.valueOf(quantity), null, null);
        }
    }
}
