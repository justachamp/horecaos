package uz.horecaos.platform.fulfillment.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.tenancy.api.GeoPoint;

/**
 * Everything fee resolution needs, and nothing it does not (ADR 0037).
 *
 * <p>The destination is a coordinate and never an address. ADR 0029 keeps
 * personal data inside envelope encryption, and the resolver has no use for the
 * text: it needs a point to test containment with and a point to measure from.
 * Nothing on this record is written to the evidence row.
 *
 * @param locationId       the location the customer chose. Resolution is against
 *                         this branch and no other; there is no path that
 *                         substitutes a covering one
 * @param quoteId          the quote this resolution will be pinned to, or null for
 *                         a simulation that writes nothing and has no quote to name
 * @param pricingAuthority read from {@code ordering.orders.pricing_authority}
 *                         once ADR 0040 adds it, and seeded meanwhile from the
 *                         channel's {@code externally_priced} default. Passed in
 *                         rather than looked up, because the gate belongs on the
 *                         order and this module must not be the second place that
 *                         decides it
 * @param goodsSubtotalMinor not read by the resolver, and pricing passes zero. The zone's minimum basket
 *                         and free-delivery threshold travel back on the resolved charge and are compared
 *                         by {@code PricingEngine} against the post-discount goods subtotal (ADR 0037,
 *                         steps 7 and 8), which excludes the delivery fee and any service charge:
 *                         comparing against a total that includes the fee makes the fee oscillate, since
 *                         adding it crosses the threshold, which removes it, which uncrosses it. A caller
 *                         that simulates a fee for a basket (the zone editor) may still say what the basket
 *                         is; the figure is advisory and decides nothing here
 * @param at               the instant the quote is being created, which is what
 *                         the time rules are evaluated against in the location's
 *                         own timezone
 */
public record DeliveryFeeQuery(
        UUID tenantId,
        UUID brandId,
        UUID locationId,
        @Nullable UUID quoteId,
        GeoPoint destination,
        String currency,
        long goodsSubtotalMinor,
        PricingAuthority pricingAuthority,
        Instant at) {

    public DeliveryFeeQuery {
        Objects.requireNonNull(tenantId, "A tenant is required");
        Objects.requireNonNull(brandId, "A brand is required");
        Objects.requireNonNull(locationId, "A location is required");
        Objects.requireNonNull(destination, "A destination point is required");
        Objects.requireNonNull(currency, "A currency is required");
        Objects.requireNonNull(pricingAuthority, "A pricing authority is required");
        Objects.requireNonNull(at, "An instant is required");
        if (goodsSubtotalMinor < 0) {
            throw new IllegalArgumentException("A goods subtotal cannot be negative, was " + goodsSubtotalMinor);
        }
    }
}
