package uz.horecaos.platform.fulfillment.domain.sourcing;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Everything a dispatch rule can ask about an order (ADR 0142).
 *
 * <p>The closed vocabulary, as a value: the document's conditions are a conjunction over
 * exactly these and nothing else, so a rule cannot depend on something the runtime and the
 * simulator do not both have. No customer, no address, no coordinates and no money -- the
 * evaluator is a pure function over facts that are safe to put in a simulator request, a
 * log line and a stored decision.
 *
 * @param sourceSystemType {@code tenant.sales_channels.system_type} of the order's channel
 *                         (ADR 0036's closed set), or null when the order's channel is unknown
 * @param channelId        the order's sales channel, or null when unknown
 * @param zoneId           the delivery zone the order was priced in, from its fee-resolution
 *                         evidence, or null for an order with none. A rule that names zones
 *                         does not match a null
 * @param preparation      the kitchen's estimate
 * @param distanceMeters   branch to door
 * @param confirmedAt      when the order was confirmed, read against {@code branchZone} for the
 *                         local-time condition
 * @param branchZone       the location's IANA timezone, never a delivery zone
 * @param prepaid          whether HorecaOS already took the money
 */
public record DispatchFacts(
        @Nullable String sourceSystemType,
        @Nullable UUID channelId,
        @Nullable UUID zoneId,
        UUID brandId,
        UUID locationId,
        Duration preparation,
        int distanceMeters,
        Instant confirmedAt,
        ZoneId branchZone,
        boolean prepaid) {

    public DispatchFacts {
        Objects.requireNonNull(brandId, "A brand is required");
        Objects.requireNonNull(locationId, "A location is required");
        Objects.requireNonNull(preparation, "A preparation estimate is required");
        Objects.requireNonNull(confirmedAt, "A confirmation instant is required");
        Objects.requireNonNull(branchZone, "A branch timezone is required");
        if (preparation.isNegative()) {
            throw new IllegalArgumentException("A preparation estimate cannot be negative");
        }
        if (distanceMeters < 0) {
            throw new IllegalArgumentException("A delivery distance cannot be negative, was " + distanceMeters);
        }
    }

    /** Whole minutes of preparation, rounded down: "45 minutes or less" is a statement about whole minutes. */
    public int preparationMinutes() {
        return (int) preparation.toMinutes();
    }
}
