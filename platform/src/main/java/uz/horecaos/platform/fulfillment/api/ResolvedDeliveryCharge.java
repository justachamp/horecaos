package uz.horecaos.platform.fulfillment.api;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The charge ADR 0018's stage 5 consumes (ADR 0037).
 *
 * <p>Resolved before the engine runs and handed to it as a value, so
 * {@code PricingEngine} stays the pure function ADR 0018 promises: no clock, no
 * database, and no geometry inside the thing that decides an amount.
 *
 * <p>Amounts are minor units and a currency rather than pricing's {@code Money},
 * deliberately. Fulfillment must not depend on pricing — pricing depends on
 * fulfillment, and a type shared in the other direction would close that into a
 * cycle.
 *
 * <p>Every identifier here enters the quote's context hash. That is what makes a
 * zone edit or a peak-window boundary invalidate an in-flight quote with
 * {@code PRICE_CHANGED}, exactly as a price-book edit already does.
 *
 * @param feeMinor              the fee after the tariff's own clamp, before the
 *                              stage 8 waiver and stage 9 benefit
 * @param tariffDiscountMinor   the rate table's own standing discount, already
 *                              capped at {@code feeMinor}. Reported beside the fee
 *                              rather than subtracted into it, because the fee line
 *                              on a receipt is the gross charge and the reduction
 *                              is its own line — and a fee stored net cannot be
 *                              told apart from a cheaper tariff
 * @param minBasketMinor        the zone's minimum basket, or null when it sets
 *                              none. Compared in the pipeline, against the same
 *                              post-discount goods subtotal the threshold uses
 * @param freeDeliveryFromMinor the zone's waiver threshold, or null
 * @param losingZoneIds         the candidates that contained the point and lost
 *                              the ranking, so "the other zone's price applies"
 *                              is answerable
 */
public record ResolvedDeliveryCharge(
        DeliveryFeeOutcome outcome,
        String currency,
        long feeMinor,
        long tariffDiscountMinor,
        @Nullable Long minBasketMinor,
        @Nullable Long freeDeliveryFromMinor,
        @Nullable UUID zoneId,
        @Nullable Integer zoneVersion,
        @Nullable UUID tariffId,
        @Nullable Integer tariffVersion,
        @Nullable Integer bandSequence,
        @Nullable Integer timeRuleSequence,
        @Nullable Integer distanceMeters,
        @Nullable String distanceMode,
        @Nullable String distanceSource,
        List<UUID> losingZoneIds) {

    public ResolvedDeliveryCharge {
        losingZoneIds = losingZoneIds == null ? List.of() : List.copyOf(losingZoneIds);
    }

    /**
     * A refusal or an externally-priced order, carrying no fee.
     *
     * <p>The currency is still required: an outcome without one cannot be turned
     * into a shortfall message, and defaulting it here would pick a currency for a
     * tenant that never named one.
     */
    public static ResolvedDeliveryCharge none(DeliveryFeeOutcome outcome, String currency) {
        return new ResolvedDeliveryCharge(
                outcome, currency, 0L, 0L, null, null, null, null, null, null, null, null, null, null, null, List.of());
    }

    public boolean isResolved() {
        return outcome == DeliveryFeeOutcome.RESOLVED;
    }

    /**
     * The canonical form that enters the ADR 0018 context hash.
     *
     * <p>Field order is pinned and every value is rendered explicitly, including
     * the nulls. A hash that skipped absent fields would collide two different
     * resolutions — a zone with no waiver and a zone whose waiver was removed —
     * and an irreproducible or colliding hash proves nothing.
     */
    public String canonicalForm() {
        return new StringBuilder()
                .append("outcome=")
                .append(outcome)
                .append(":currency=")
                .append(currency)
                .append(":fee=")
                .append(feeMinor)
                .append(":tariffDiscount=")
                .append(tariffDiscountMinor)
                .append(":zone=")
                .append(zoneId)
                .append('@')
                .append(zoneVersion)
                .append(":tariff=")
                .append(tariffId)
                .append('@')
                .append(tariffVersion)
                .append(":band=")
                .append(bandSequence)
                .append(":rule=")
                .append(timeRuleSequence)
                .append(":distance=")
                .append(distanceMeters)
                .append('/')
                .append(distanceMode)
                .append('/')
                .append(distanceSource)
                .append(":minBasket=")
                .append(minBasketMinor)
                .append(":freeFrom=")
                .append(freeDeliveryFromMinor)
                .toString();
    }
}
