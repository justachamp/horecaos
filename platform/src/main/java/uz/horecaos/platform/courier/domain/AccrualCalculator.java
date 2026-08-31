package uz.horecaos.platform.courier.domain;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * What a courier is owed, computed from the snapshotted rate card alone
 * (ADR 0042).
 *
 * <p>Pure and deterministic on purpose. The same order under the same card
 * version produces a byte-identical figure whatever the customer paid: changing
 * a delivery tariff or running a free-delivery promotion cannot move it, because
 * nothing here can see either. The gap between the two is margin, and where it
 * is negative it is an ADR 0013 delivery cost subsidy rather than a reduced
 * accrual.
 */
public final class AccrualCalculator {

    private static final int METRES_PER_KM = 1000;

    private AccrualCalculator() {}

    /**
     * One delivered order.
     *
     * <p>Per-kilometre money is accumulated across bands in thousandths of a
     * minor unit and rounded once, half up, at the end. Rounding inside each
     * band instead would make a three-band journey pay differently from the same
     * distance under a one-band card, which is a difference no courier could be
     * shown the reason for.
     */
    public static CourierAccrual forDelivery(RateCard card, int distanceMeters) {
        if (distanceMeters < 0) {
            throw new IllegalArgumentException("A distance is never negative");
        }

        long perOrder = card.of(RateComponentType.PER_ORDER).stream()
                .mapToLong(RateComponent::amountMinor)
                .sum();

        long thousandths = 0;
        List<RateComponent> bands = card.of(RateComponentType.PER_KM_BAND).stream()
                .sorted(Comparator.comparingInt(RateComponent::bandFromMeters))
                .toList();
        for (RateComponent band : bands) {
            // bandFromMeters is required for every PER_KM_BAND component (the
            // compact constructor enforces it); this loop only ever sees
            // PER_KM_BAND components, filtered above.
            int from = Objects.requireNonNull(band.bandFromMeters());
            int to = band.bandToMeters() == null ? Integer.MAX_VALUE : band.bandToMeters();
            int metresInBand = Math.max(0, Math.min(distanceMeters, to) - from);
            thousandths = Math.addExact(thousandths, Math.multiplyExact((long) metresInBand, band.amountMinor()));
        }
        long perKm = roundHalfUp(thousandths, METRES_PER_KM);

        long floor = card.of(RateComponentType.PER_ORDER_MINIMUM).stream()
                .mapToLong(RateComponent::amountMinor)
                .max()
                .orElse(0L);
        long topUp = Math.max(0, floor - (perOrder + perKm));

        return new CourierAccrual(0, perOrder, perKm, topUp);
    }

    /**
     * One closed shift.
     *
     * <p>Paid seconds exclude breaks, so a shift opened, spent on break and
     * closed earns nothing fixed — which is the whole reason the qualifier
     * exists.
     */
    public static CourierAccrual forShift(RateCard card, long paidSeconds) {
        if (paidSeconds < 0) {
            throw new IllegalArgumentException("Paid seconds are never negative");
        }
        long fixed = card.of(RateComponentType.PER_SHIFT_FIXED).stream()
                .filter(component ->
                        component.minimumPaidSeconds() == null || paidSeconds >= component.minimumPaidSeconds())
                .mapToLong(RateComponent::amountMinor)
                .sum();
        return new CourierAccrual(fixed, 0, 0, 0);
    }

    private static long roundHalfUp(long numerator, long denominator) {
        return (numerator + denominator / 2) / denominator;
    }
}
