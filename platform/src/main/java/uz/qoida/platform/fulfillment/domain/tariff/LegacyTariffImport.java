package uz.qoida.platform.fulfillment.domain.tariff;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Turns one legacy branch's {@code vendors.delivery} JSON into a Qoida rate table
 * (ADR 0037, added in V0032).
 *
 * <p>A pure function, deliberately. The migration wave has to be able to run this
 * against every branch row and diff the answer against the legacy one before a
 * single tariff is activated, and it cannot do that if the mapping is buried in a
 * loader that also talks to two databases.
 *
 * <p>Every choice below exists because the legacy reader,
 * {@code apps/customer/services/cart/calculate_delivery_price.py}, does something
 * the earlier reading of the column's key names did not show:
 *
 * <ul>
 *   <li>{@code prices_per_km} is a list of steps, each charging only its own width,
 *       accumulated across all of them. It becomes a band per step, with the base
 *       fare on the first band and nothing on the rest.</li>
 *   <li>The reader's loop simply ends when it runs out of steps, so any distance
 *       past the last step is free. That is preserved as an explicit zero-rate tail
 *       band rather than left as a gap — a gap would refuse the address, and the
 *       legacy served it.</li>
 *   <li>A {@code peak_hours} entry replaces {@code distance}, {@code distance_price}
 *       and {@code prices_per_km} together. It becomes a band set of its own, put in
 *       force by a time rule at par: no multiplier, no surcharge.</li>
 *   <li>Legacy windows are closed at both ends and may wrap midnight; Qoida's are
 *       half-open and may not. A window is widened by one second at its close so
 *       the boundary minute keeps its legacy answer, and a wrapping window is split
 *       into two rules.</li>
 *   <li>The reader takes the <em>first</em> matching peak entry, not the best. List
 *       order becomes descending priority, so the same entry still wins.</li>
 *   <li>{@code max_distance} is compared with {@code <}, so the legacy serves that
 *       exact distance. Qoida's reach is half-open, so it imports as one metre more.</li>
 * </ul>
 *
 * <p>Two things are deliberately not imported. {@code min_order_price}, top level
 * and inside {@code discount}, is read by no code in the legacy backend, so
 * carrying it into the zone's enforced minimum would impose a refusal these
 * branches have never been subject to. And {@code visibility_distance} is a second,
 * independent radius that decides whether a branch is listed at all; it belongs to
 * serviceability search, not to this rate table's reach.
 */
public final class LegacyTariffImport {

    /** What the legacy rounds every fee and every discount to. */
    private static final long ROUNDING_STEP_SOM = 500L;

    private LegacyTariffImport() {
    }

    /** One entry of {@code prices_per_km}: a band {@code width} metres wide at {@code perKm}. */
    public record LegacyStep(int width, long perKm) { }

    /** One {@code peak_hours} entry: a complete alternative rate table plus its window. */
    public record LegacyPeak(LocalTime start, LocalTime end, int distance, long distancePrice,
            List<LegacyStep> steps) { }

    /** One {@code discount.times} entry, and {@code working hours} generally. */
    public record LegacyWindow(LocalTime start, LocalTime end) { }

    /**
     * {@code VendorDeliveryDiscountSchema}.
     *
     * @param mode           {@code "amount"} or {@code "distance"}
     * @param minOrderPrice  read by nothing. Carried on the record so the importer
     *                       can say out loud that it drops it, rather than the
     *                       parser silently never having had it
     */
    public record LegacyDiscount(long value, String mode, Long minOrderPrice,
            List<LegacyWindow> times) { }

    /** {@code VendorDeliveryConfig}. Every field, including the inert ones. */
    public record LegacyDeliveryConfig(
            int distance,
            int maxDistance,
            long distancePrice,
            Long minOrderPrice,
            LegacyDiscount discount,
            List<LegacyStep> pricesPerKm,
            List<LegacyPeak> peakHours) { }

    /**
     * The rate table that charges what this branch charges.
     *
     * @param currency  always UZS in the legacy population, and a minor unit is a
     *                  whole som — every amount in the JSON is already minor units
     * @param routingInstallationId the ADR 0026 routing binding that will measure
     *                  what the legacy map SDK measured. Required: the fallback
     *                  factor is left at par, matching the legacy's own offline
     *                  fallback to a raw straight line
     */
    public static DeliveryTariff toTariff(UUID tariffId, LegacyDeliveryConfig legacy,
            String currency, UUID routingInstallationId) {

        if (routingInstallationId == null) {
            // Not a default. The legacy measures road distance through its map SDK
            // and only falls back to a straight line when the provider is down, so
            // importing a branch as RADIUS would shorten every journey by the
            // detour factor and drop every fee — quietly, and in the direction
            // nobody audits.
            throw new IllegalArgumentException(
                    "A legacy branch measured road distance; import it with the routing "
                            + "installation that will keep measuring it, not without one");
        }

        // Inclusive to half-open. The legacy refuses only when max_distance is
        // strictly less than the distance, so the branch does serve a customer at
        // exactly that distance and must keep doing so.
        int reach = legacy.maxDistance() + 1;

        List<TariffBand> bands = new ArrayList<>(
                bandsFor(TariffBand.BASE_SET, legacy.distance(), legacy.distancePrice(),
                        legacy.pricesPerKm(), reach));

        List<TariffTimeRule> rules = new ArrayList<>();
        List<LegacyPeak> peaks = legacy.peakHours() == null ? List.of() : legacy.peakHours();
        for (int i = 0; i < peaks.size(); i++) {
            LegacyPeak peak = peaks.get(i);
            String set = "PEAK_" + i;
            bands.addAll(bandsFor(set, peak.distance(), peak.distancePrice(), peak.steps(), reach));

            // Descending, so the earlier entry outranks the later one when two
            // windows overlap. The legacy reader breaks on the first match, which
            // is the same rule expressed as list order.
            int priority = peaks.size() - i;
            for (LocalTimeRange range : halfOpen(peak.start(), peak.end())) {
                rules.add(new TariffTimeRule(rules.size(), priority, EVERY_DAY,
                        range.from(), range.to(), set, 10_000, 0L));
            }
        }

        List<TariffDiscount> discounts = discountsFor(legacy.discount());

        return new DeliveryTariff(tariffId, 0, uz.qoida.platform.fulfillment.domain.VersionStatus.DRAFT,
                currency, FeeSource.TARIFF, DistanceMode.ROAD, 10_000, routingInstallationId,
                reach, 0L, null,
                DistanceAccrual.PRORATED_METRE, ROUNDING_STEP_SOM, RoundingRule.HALF_EVEN,
                bands, rules, discounts);
    }

    /** Bit 0 to bit 6: the legacy applies a peak window on every day of the week. */
    private static final int EVERY_DAY = 0b1111111;

    /**
     * The base fare and the steps, as a tiling band set.
     *
     * <p>The base fare rides on the first band whatever its width, because the
     * legacy adds {@code distance_price} unconditionally and only then subtracts
     * {@code distance} from the journey. A configuration with {@code distance = 0}
     * therefore still pays the fare, and a band {@code [0, 0)} is not a thing.
     */
    private static List<TariffBand> bandsFor(String set, int baseDistance, long basePrice,
            List<LegacyStep> steps, int reach) {

        record Segment(int width, long perKm) { }
        List<Segment> segments = new ArrayList<>();
        segments.add(new Segment(baseDistance, 0L));
        if (steps != null) {
            steps.forEach(step -> segments.add(new Segment(step.width(), step.perKm())));
        }

        List<TariffBand> bands = new ArrayList<>();
        int from = 0;
        long pendingBase = basePrice;
        int sequence = 0;
        for (Segment segment : segments) {
            if (segment.width() <= 0) {
                // A zero-width segment prices nothing but must not swallow the
                // fare that was going to ride on it.
                continue;
            }
            int to = Math.min(from + segment.width(), reach);
            if (to <= from) {
                break;
            }
            bands.add(new TariffBand(sequence++, set, from, to, pendingBase, segment.perKm()));
            pendingBase = 0L;
            from = to;
        }

        if (from < reach) {
            // The legacy loop ends when the steps run out and charges nothing for
            // whatever is left. Stated as a zero-rate band rather than left as a
            // hole: a hole refuses the address, and the legacy delivered to it.
            bands.add(new TariffBand(sequence, set, from, reach, pendingBase, 0L));
        } else if (bands.isEmpty()) {
            bands.add(new TariffBand(0, set, 0, reach, pendingBase, 0L));
        }
        return bands;
    }

    /**
     * The discount, once per window.
     *
     * <p>A discount with no windows imports as nothing at all, because the legacy
     * reader only ever sets {@code apply_discount} inside the window loop: a
     * discount with an empty or absent {@code times} has never reduced a single
     * fee, whatever value it carries.
     */
    private static List<TariffDiscount> discountsFor(LegacyDiscount discount) {
        if (discount == null || discount.times() == null || discount.times().isEmpty()) {
            return List.of();
        }
        TariffDiscount.Kind kind = "distance".equals(discount.mode())
                ? TariffDiscount.Kind.DISTANCE_ALLOWANCE
                : TariffDiscount.Kind.AMOUNT;

        List<TariffDiscount> discounts = new ArrayList<>();
        for (int i = 0; i < discount.times().size(); i++) {
            LegacyWindow window = discount.times().get(i);
            int priority = discount.times().size() - i;
            for (LocalTimeRange range : halfOpen(window.start(), window.end())) {
                discounts.add(new TariffDiscount(discounts.size(), priority, kind,
                        kind == TariffDiscount.Kind.AMOUNT ? discount.value() : null,
                        kind == TariffDiscount.Kind.DISTANCE_ALLOWANCE
                                ? Math.toIntExact(discount.value()) : null,
                        EVERY_DAY, range.from(), range.to()));
            }
        }
        return discounts;
    }

    private record LocalTimeRange(LocalTime from, LocalTime to) { }

    /**
     * A closed, possibly wrapping legacy window as one or two half-open Qoida ones.
     *
     * <p>The close moves out by a second because the legacy comparison is
     * {@code current <= end}: a window stated as 18:00 to 22:00 covers 22:00:00
     * itself, and a half-open window that stopped there would hand that minute back
     * to the base rate. A window ending at the last second of the day is capped at
     * {@link LocalTime#MAX} instead, since there is no second after it to move to.
     */
    private static List<LocalTimeRange> halfOpen(LocalTime start, LocalTime end) {
        if (start.equals(end)) {
            // A degenerate legacy window matches exactly one second. Kept, because
            // dropping it would silently change what a branch charges.
            return List.of(new LocalTimeRange(start, exclusiveEnd(end)));
        }
        if (start.isBefore(end)) {
            return List.of(new LocalTimeRange(start, exclusiveEnd(end)));
        }
        // Wraps midnight. Two rules rather than one row the evaluator has to
        // special-case, which is the special case every implementation forgets.
        List<LocalTimeRange> ranges = new ArrayList<>();
        ranges.add(new LocalTimeRange(start, LocalTime.MAX));
        ranges.add(new LocalTimeRange(LocalTime.MIN, exclusiveEnd(end)));
        return ranges;
    }

    private static LocalTime exclusiveEnd(LocalTime end) {
        LocalTime next = end.plusSeconds(1);
        return next.isAfter(end) ? next : LocalTime.MAX;
    }
}
