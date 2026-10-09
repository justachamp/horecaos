package uz.horecaos.platform.customers.api;

import java.util.Optional;
import java.util.UUID;

/**
 * Whether an order or a reservation is the one a lead says it became (ADR 0111 §4).
 *
 * <p>A lead is {@code CONVERTED} against a fact, not a claim: the order or the reservation exists,
 * belongs to this tenant and to the lead's own brand. The tables live in {@code ordering} and
 * {@code dinein}, both of which import {@code customers.api}, so the question is declared here and
 * answered there -- the direction {@link CustomerOrderActivityPort} already established.
 */
public interface LeadConversionTargets {

    /** An order of this brand exists. */
    boolean orderExists(UUID tenantId, UUID brandId, UUID orderId);

    /** A reservation of this brand exists. */
    boolean reservationExists(UUID tenantId, UUID brandId, UUID reservationId);

    /**
     * The account the order was taken for, when it has one. A lead that converts into this order is
     * that guest: the order is the evidence the call centre acted on, so the lead is linked to the
     * account without anybody matching a phone number (ADR 0015).
     */
    default Optional<UUID> orderCustomer(UUID tenantId, UUID brandId, UUID orderId) {
        return Optional.empty();
    }

    /** The account the reservation was made by, when it was made by one. */
    default Optional<UUID> reservationCustomer(UUID tenantId, UUID brandId, UUID reservationId) {
        return Optional.empty();
    }
}
