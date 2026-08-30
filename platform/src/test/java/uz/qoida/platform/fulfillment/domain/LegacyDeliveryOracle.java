package uz.qoida.platform.fulfillment.domain;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

import uz.qoida.platform.fulfillment.domain.tariff.LegacyTariffImport.LegacyDeliveryConfig;
import uz.qoida.platform.fulfillment.domain.tariff.LegacyTariffImport.LegacyDiscount;
import uz.qoida.platform.fulfillment.domain.tariff.LegacyTariffImport.LegacyPeak;
import uz.qoida.platform.fulfillment.domain.tariff.LegacyTariffImport.LegacyStep;
import uz.qoida.platform.fulfillment.domain.tariff.LegacyTariffImport.LegacyWindow;

/**
 * {@code calculate_delivery_price.py}, transcribed.
 *
 * <p>Line for line, including the {@code double} arithmetic, the half-to-even
 * rounding Python's {@code round} performs, the {@code > base_distance} guard in
 * the distance-discount branch that the fee path states as {@code > 0}, and the
 * silent free tail when the step list runs out. It is the reference, so it is
 * not allowed to be an improvement on the original.
 */
public final class LegacyDeliveryOracle {

    private LegacyDeliveryOracle() {
    }

    public static long price(LegacyDeliveryConfig config, int distance, LocalDateTime at) {
        return run(config, distance, at)[0];
    }

    public static long discount(LegacyDeliveryConfig config, int distance, LocalDateTime at) {
        return run(config, distance, at)[1];
    }

    private static long[] run(LegacyDeliveryConfig vendor, int distanceIn, LocalDateTime at) {
        double deliveryPrice = 0;
        double deliveryDiscount = 0;
        int distance = distanceIn;
        LocalTime now = at.toLocalTime();

        LegacyPeak peakHour = null;
        for (LegacyPeak peak : orEmpty(vendor.peakHours())) {
            if (timeInRange(peak.start(), peak.end(), now)) {
                peakHour = peak;
                break;
            }
        }

        int baseDistance;
        long basePrice;
        List<LegacyStep> pricesPerKm;
        if (peakHour != null) {
            baseDistance = peakHour.distance();
            basePrice = peakHour.distancePrice();
            pricesPerKm = orEmpty(peakHour.steps());
        } else {
            baseDistance = vendor.distance();
            basePrice = vendor.distancePrice();
            pricesPerKm = orEmpty(vendor.pricesPerKm());
        }

        deliveryPrice += basePrice;
        distance -= baseDistance;
        if (distance > 0) {
            for (LegacyStep step : pricesPerKm) {
                if (distance > step.width()) {
                    deliveryPrice += step.width() / 1000.0 * step.perKm();
                    distance -= step.width();
                } else {
                    deliveryPrice += distance / 1000.0 * step.perKm();
                    break;
                }
            }
        }

        LegacyDiscount discount = vendor.discount();
        if (discount != null) {
            boolean applyDiscount = false;
            if (discount.times() != null) {
                for (LegacyWindow window : discount.times()) {
                    if (timeInRange(window.start(), window.end(), now)) {
                        applyDiscount = true;
                        break;
                    }
                }
            }
            if (applyDiscount) {
                if ("amount".equals(discount.mode())) {
                    deliveryDiscount = discount.value();
                } else if ("distance".equals(discount.mode())) {
                    int allowance = Math.toIntExact(discount.value());
                    deliveryDiscount += basePrice;
                    allowance -= baseDistance;
                    // The guard the fee path writes as `> 0`. Transcribed as it
                    // stands, because an oracle that quietly fixes the system it
                    // is measuring measures nothing.
                    if (allowance > baseDistance) {
                        for (LegacyStep step : pricesPerKm) {
                            if (allowance > step.width()) {
                                deliveryDiscount += step.width() / 1000.0 * step.perKm();
                                allowance -= step.width();
                            } else {
                                deliveryDiscount += allowance / 1000.0 * step.perKm();
                                break;
                            }
                        }
                    }
                }
            }
        }

        long price = deliveryPrice == 0 ? 0 : roundToStep(deliveryPrice);
        long finalDiscount = 0;
        if (deliveryDiscount != 0) {
            finalDiscount = Math.min(roundToStep(deliveryDiscount), price);
        }
        return new long[] { price, finalDiscount };
    }

    /** {@code int(round(x / 500) * 500)}. Python's round is half to even. */
    private static long roundToStep(double amount) {
        return (long) (Math.rint(amount / 500.0) * 500);
    }

    private static boolean timeInRange(LocalTime start, LocalTime end, LocalTime current) {
        if (!start.isAfter(end)) {
            return !start.isAfter(current) && !current.isAfter(end);
        }
        return !current.isBefore(start) || !current.isAfter(end);
    }

    private static <T> List<T> orEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }
}
