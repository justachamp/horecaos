package uz.horecaos.platform.fulfillment.domain;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeOutcome;
import uz.horecaos.platform.fulfillment.api.ResolvedDeliveryCharge;
import uz.horecaos.platform.fulfillment.domain.tariff.DistanceMode;
import uz.horecaos.platform.fulfillment.domain.tariff.DistanceSource;

/**
 * One run of the resolution order, with everything it decided and everything it
 * decided against (ADR 0037).
 *
 * <p>This is what gets stored, and storing it is the point of the ADR. The
 * question asked six weeks later is never "what was the fee" — that is on the
 * order — but "why was it that", and it is answerable only from a pinned zone
 * version, a pinned tariff version, a band, a time rule, a distance and the source
 * of that distance. A row that carries the amount and not the derivation records
 * a number nobody can defend to a tenant.
 *
 * <p>Refusals carry as much detail as successes. "Why did this address get no
 * delivery option" is asked at least as often as "why was it this much", and a
 * refusal that records only its reason code cannot distinguish an address two
 * hundred metres past the tariff's reach from one in another city.
 */
public record DeliveryFeeResolution(
        UUID id,
        UUID tenantId,
        UUID quoteId,
        UUID locationId,
        DeliveryFeeOutcome outcome,
        String reasonCode,
        String currency,
        UUID zoneId,
        Integer zoneVersion,
        UUID tariffId,
        Integer tariffVersion,
        Integer bandSequence,
        Integer timeRuleSequence,
        Integer distanceMeters,
        DistanceMode distanceMode,
        DistanceSource distanceSource,
        String routingProvider,
        Long providerQuoteMinor,
        Long computedFeeMinor,
        Long finalFeeMinor,
        Long tariffDiscountMinor,
        Integer discountSequence,
        Long minBasketMinor,
        Long freeDeliveryFromMinor,
        List<UUID> losingZoneIds,
        Map<String, Object> evidence) {

    /**
     * Bumped whenever the resolution order itself changes.
     *
     * <p>Recorded on every row for the reason {@code PricingEngine.CALCULATION_VERSION}
     * is: an old resolution must never be re-explained by new rules and quietly
     * disagreed with, because the disagreement surfaces as a tenant being told two
     * different stories about one order.
     */
    public static final int RESOLUTION_VERSION = 2;

    public DeliveryFeeResolution {
        losingZoneIds = losingZoneIds == null ? List.of() : List.copyOf(losingZoneIds);
        evidence = evidence == null ? Map.of() : Map.copyOf(evidence);
    }

    /** The narrow view pricing consumes. Everything else here is evidence, not contract. */
    public ResolvedDeliveryCharge toCharge() {
        return new ResolvedDeliveryCharge(
                outcome,
                currency,
                finalFeeMinor == null ? 0L : finalFeeMinor,
                tariffDiscountMinor == null ? 0L : tariffDiscountMinor,
                minBasketMinor,
                freeDeliveryFromMinor,
                zoneId,
                zoneVersion,
                tariffId,
                tariffVersion,
                bandSequence,
                timeRuleSequence,
                distanceMeters,
                distanceMode == null ? null : distanceMode.name(),
                distanceSource == null ? null : distanceSource.name(),
                losingZoneIds);
    }
}
