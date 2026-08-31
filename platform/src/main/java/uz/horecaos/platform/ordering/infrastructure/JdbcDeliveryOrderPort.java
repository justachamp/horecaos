package uz.horecaos.platform.ordering.infrastructure;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.Waypoint;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.ordering.domain.DeliveryDestination;
import uz.horecaos.platform.ordering.domain.OrderPromise;
import uz.horecaos.platform.ordering.domain.OrderStatus;

/**
 * Ordering's answer to the one question sourcing asks it (ADR 0014, ADR 0019,
 * ADR 0029).
 *
 * <p>Implemented in ordering because the decrypt is ordering's. Fulfilment can
 * read the branch end of a journey for itself — a restaurant's address and phone
 * are published by the merchant and sit in clear on {@code tenant.locations} —
 * but the customer end is inside ADR 0029's envelope, bound to the order row by
 * the associated data, and reaching it is a decrypt with a recorded purpose. The
 * interface is declared in fulfilment and satisfied here, which is what keeps
 * that decrypt on this side of the module line and stops fulfilment from growing
 * a dependency on ordering.
 *
 * <p>An adapter with its own SQL, following {@code JdbcOrderCatalogSnapshot}: it
 * reads the columns it needs and nothing travels between the modules but the
 * record on the interface.
 *
 * <p>Every query carries the tenant. An order id is a UUID a caller supplies, and
 * this method returns a home address and a telephone number.
 */
@Component
public class JdbcDeliveryOrderPort implements DeliveryOrderPort {

    private static final String SNAPSHOT_TABLE = "ordering.order_customer_snapshots";
    private static final String NAME_COLUMN = "display_name_encrypted";
    private static final String CONTACT_COLUMN = "contact_encrypted";
    private static final String ADDRESS_COLUMN = "address_encrypted";
    private static final String INSTRUCTIONS_COLUMN = "delivery_instructions_encrypted";

    /** The ADR 0027 purpose recorded against every reveal this class performs. */
    private static final String PURPOSE = "DELIVERY_DISPATCH";

    /**
     * The statuses a courier should be sourced for.
     *
     * <p>Everything before {@code CONFIRMED} is an order the restaurant has not
     * committed to — a payment still authorizing, an approval still pending — and
     * sourcing one of those sends a courier to collect food nobody has agreed to
     * cook. Everything terminal is over. What is left is the window in which a
     * delivery is a real, live obligation, and a replayed confirmation an hour
     * later still falls inside it.
     */
    private static final String SOURCEABLE_STATUSES = Stream.of(
                    OrderStatus.CONFIRMED, OrderStatus.PREPARING, OrderStatus.READY, OrderStatus.FULFILLING)
            .map(status -> "'" + status.name() + "'")
            .collect(Collectors.joining(", "));

    private final JdbcClient jdbc;
    private final FieldProtection protection;
    private final ObjectMapper objectMapper;

    public JdbcDeliveryOrderPort(JdbcClient jdbc, FieldProtection protection, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.protection = protection;
        this.objectMapper = objectMapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>The four reasons this answers empty — not this tenant's, not a delivery,
     * not in a sourceable state, no destination snapshot — are one answer on
     * purpose. A caller can do nothing different with any of them, and telling
     * them apart would let one tenant learn that another tenant's order id is
     * real.
     *
     * <p>The missing-destination case should be unreachable: checkout refuses a
     * delivery cart that has not said where it is going, precisely so that this is
     * never the place it is discovered. It is still handled here rather than
     * asserted, because an order predating that rule, or one whose personal
     * columns an ADR 0029 retention sweep has blanked, would otherwise become a
     * courier dispatched to a null address.
     */
    @Override
    public Optional<DeliveryOrder> deliveryOrder(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT o.id,
                       o.public_order_number,
                       o.promise_prep_minutes,
                       o.currency,
                       o.fee_minor,
                       o.total_minor,
                       o.payment_status_projection,
                       s.display_name_encrypted,
                       s.contact_encrypted,
                       s.address_encrypted,
                       s.delivery_instructions_encrypted,
                       (SELECT r.id
                          FROM fulfillment.delivery_fee_resolutions r
                         WHERE r.tenant_id = o.tenant_id
                           AND r.quote_id = o.pricing_quote_id
                           AND r.outcome IN ('RESOLVED', 'EXTERNALLY_PRICED')
                         ORDER BY r.created_at DESC
                         LIMIT 1) AS delivery_fee_resolution_id
                FROM ordering.orders o
                LEFT JOIN ordering.order_customer_snapshots s
                       ON s.order_id = o.id AND s.tenant_id = o.tenant_id
                WHERE o.tenant_id = :tenantId
                  AND o.id = :orderId
                  AND o.fulfillment_mode = 'DELIVERY'
                  AND o.status IN (%s)
                """.formatted(SOURCEABLE_STATUSES))
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> new OrderRow(
                        row.getObject("id", UUID.class),
                        row.getString("public_order_number"),
                        (Integer) row.getObject("promise_prep_minutes"),
                        row.getString("currency"),
                        row.getLong("fee_minor"),
                        row.getLong("total_minor"),
                        row.getString("payment_status_projection"),
                        row.getString(NAME_COLUMN),
                        row.getString(CONTACT_COLUMN),
                        row.getString(ADDRESS_COLUMN),
                        row.getString(INSTRUCTIONS_COLUMN),
                        row.getObject("delivery_fee_resolution_id", UUID.class)))
                .optional()
                .filter(row -> row.addressEncrypted() != null)
                .map(row -> assemble(tenantId, row));
    }

    private DeliveryOrder assemble(UUID tenantId, OrderRow row) {
        DeliveryDestination destination = objectMapper.readValue(
                reveal(tenantId, row.orderId(), ADDRESS_COLUMN, row.addressEncrypted()), DeliveryDestination.class);

        Waypoint dropoff = new Waypoint(
                destination.latitude(),
                destination.longitude(),
                destination.addressLine(),
                reveal(tenantId, row.orderId(), NAME_COLUMN, row.nameEncrypted()),
                reveal(tenantId, row.orderId(), CONTACT_COLUMN, row.contactEncrypted()),
                reveal(tenantId, row.orderId(), INSTRUCTIONS_COLUMN, row.instructionsEncrypted()),
                destination.entrance(),
                destination.floor(),
                destination.apartment());

        return new DeliveryOrder(
                row.orderId(),
                row.publicOrderNumber(),
                preparation(row.prepMinutes()),
                // What the customer was actually charged for delivery, taken from
                // the order rather than from ADR 0037's evidence row. The evidence
                // records the fee the tariff produced, before the zone's free
                // delivery threshold waived it; the order records what was paid.
                // Delivery is the only fee ordering charges today, and the moment a
                // second kind exists this must become a fee of its own rather than
                // the total of them.
                row.feeMinor(),
                row.deliveryFeeResolutionId(),
                row.currency(),
                // Prepaid means HorecaOS has the money. NOT_REQUIRED is the cash
                // order — the courier collects at the door — and PENDING is money
                // that has not arrived. Getting this wrong charges the customer
                // twice or lets them pay nobody.
                "AUTHORIZED".equals(row.paymentStatus()) || "CAPTURED".equals(row.paymentStatus()),
                // The goods the courier is carrying, which is the total less what
                // was charged for carrying them. A partner insuring the load or
                // collecting cash for it must not be told the delivery fee is part
                // of the parcel's worth.
                Math.max(0, row.totalMinor() - row.feeMinor()),
                dropoff);
    }

    /**
     * The kitchen's estimate, as ADR 0036 recorded it at checkout.
     *
     * <p>Absent for a promise no duration produced — an operator override, or an
     * order taken while no band covered the branch. The platform's own
     * unflattering fallback is used rather than zero, because a plan built on a
     * zero-minute preparation sources a courier immediately and parks them outside
     * a kitchen that has not started.
     */
    private static Duration preparation(Integer prepMinutes) {
        return Duration.ofMinutes(prepMinutes == null ? OrderPromise.DEFAULT_PREP_MINUTES : prepMinutes);
    }

    private @Nullable String reveal(UUID tenantId, UUID orderId, String column, @Nullable String ciphertext) {
        if (ciphertext == null) {
            return null;
        }
        return protection.reveal(
                tenantId,
                ProtectedValue.deserialize(ciphertext),
                new RecordRef(SNAPSHOT_TABLE, column, orderId),
                PURPOSE);
    }

    /** Four ciphertexts and the commercial facts around them. Never printed whole. */
    private record OrderRow(
            UUID orderId,
            String publicOrderNumber,
            Integer prepMinutes,
            String currency,
            long feeMinor,
            long totalMinor,
            String paymentStatus,
            String nameEncrypted,
            String contactEncrypted,
            String addressEncrypted,
            String instructionsEncrypted,
            UUID deliveryFeeResolutionId) {

        @Override
        public String toString() {
            return "OrderRow[order=%s, reference=%s]".formatted(orderId, publicOrderNumber);
        }
    }
}
