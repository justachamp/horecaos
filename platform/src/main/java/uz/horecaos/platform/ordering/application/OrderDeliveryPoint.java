package uz.horecaos.platform.ordering.application;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.ordering.domain.DeliveryDestination;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderRow;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * Where a delivery order is going, as the point pricing resolves a delivery against.
 *
 * <p>An order is re-priced by the things that change it after checkout -- an amendment
 * (ADR 0039) and a weight captured at the scale (ADR 0137) -- and a delivery order's price
 * is not the goods alone: which delivery promotions and zone-bound promotions apply depends on
 * where it is going. Pricing needs the coordinates and nothing else, and the order holds them
 * only inside the customer snapshot's address, which is envelope-encrypted (ADR 0029).
 *
 * <p>The coordinates are revealed for the re-price only. They are returned to the caller, who
 * hands them to pricing; they are never logged, stored or put on an event, and the reveal is
 * itself an audit fact carrying the {@code purpose} (ADR 0027). A class of its own, rather
 * than a private method of either caller, so the two ways an order is re-priced cannot come
 * to disagree about where it is going.
 */
@Component
public class OrderDeliveryPoint {

    private static final String SNAPSHOT_TABLE = "ordering.order_customer_snapshots";

    private final JdbcOrderStore orders;
    private final FieldProtection protection;
    private final ObjectMapper objectMapper;

    public OrderDeliveryPoint(JdbcOrderStore orders, FieldProtection protection, ObjectMapper objectMapper) {
        this.orders = orders;
        this.protection = protection;
        this.objectMapper = objectMapper;
    }

    /**
     * The order's delivery point, or null for an order that is not delivered or whose snapshot
     * holds no address.
     *
     * @param purpose why the address is being read, recorded with the reveal
     */
    public @Nullable GeoPoint of(OrderRow order, String purpose) {
        if (order.fulfillmentMode() != FulfillmentMode.DELIVERY) {
            return null;
        }
        var snapshot =
                orders.customerSnapshot(order.tenantId(), order.orderId()).orElse(null);
        if (snapshot == null || snapshot.addressEncrypted() == null) {
            return null;
        }
        String json = protection.reveal(
                order.tenantId(),
                ProtectedValue.deserialize(snapshot.addressEncrypted()),
                new RecordRef(SNAPSHOT_TABLE, "address_encrypted", order.orderId()),
                purpose);
        DeliveryDestination destination = objectMapper.readValue(json, DeliveryDestination.class);
        return new GeoPoint(destination.latitude(), destination.longitude());
    }
}
