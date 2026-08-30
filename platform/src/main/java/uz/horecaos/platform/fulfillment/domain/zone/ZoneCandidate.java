package uz.horecaos.platform.fulfillment.domain.zone;

import java.util.Comparator;
import java.util.UUID;

/**
 * One {@code ACTIVE} zone version that contains the address (ADR 0037).
 *
 * <p>Overlap is legal and expected — a premium inner-city zone inside a wider city
 * zone is a normal configuration — so candidates are ranked rather than
 * prevented.
 */
public record ZoneCandidate(
        UUID zoneId,
        int zoneVersion,
        int priority,
        double areaSquareMeters,
        String currency,
        UUID deliveryTariffId,
        Long freeDeliveryFromMinor,
        Long minBasketMinor) {

    /**
     * The total order that decides which zone prices an address.
     *
     * <p>Priority descending, then the smaller area, then the zone id ascending.
     * The third key is the one that matters and the one most likely to be left
     * out: without it two equally ranked zones resolve by whatever the query
     * planner emitted first, and the same address prices differently on
     * consecutive requests — including after a {@code VACUUM FULL}, which
     * rewrites the heap and changes that order for no reason a customer could
     * ever be told. ADR 0018's price-book resolution carries the same final
     * tiebreak for the same reason.
     *
     * <p>Smaller area wins because the smaller polygon is the more specific
     * statement. An operator who draws a tight zone inside a loose one is saying
     * something about the tight one.
     */
    public static final Comparator<ZoneCandidate> RANKING =
            Comparator.comparingInt(ZoneCandidate::priority).reversed()
                    .thenComparingDouble(ZoneCandidate::areaSquareMeters)
                    .thenComparing(ZoneCandidate::zoneId);
}
