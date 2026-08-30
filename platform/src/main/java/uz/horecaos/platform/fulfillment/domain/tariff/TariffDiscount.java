package uz.horecaos.platform.fulfillment.domain.tariff;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * A standing reduction of the delivery fee, carried by the rate table (ADR 0037,
 * added in V0032).
 *
 * <p>This is the piece V0025 had no concept of. The legacy dashboard's
 * {@code VendorDeliveryConfig.discount} is a <em>required</em> field, not an
 * optional extra — a branch row without one was not written by that schema at all
 * — and it carries its own time windows, independent of the peak windows. ADR 0037
 * had two things that look adjacent and are not: a threshold waiver, which asks
 * about the basket, and a promotion benefit, which arrives from ADR 0018. Neither
 * asks about the clock or the distance, so neither could express "delivery is
 * 5,000 so'm cheaper at lunchtime" or "the first three kilometres are free before
 * noon", and every migrated branch has one of those.
 *
 * <p>It belongs to the rate table rather than to the pricing pipeline for the same
 * reason the fee does: it is a function of distance and the local clock, both of
 * which are resolved before {@code PricingEngine} runs and neither of which the
 * engine is allowed to read.
 *
 * @param kind             what {@code amountMinor} or {@code allowanceMeters} means
 * @param amountMinor      set exactly when the kind is {@link Kind#AMOUNT}
 * @param allowanceMeters  set exactly when the kind is {@link Kind#DISTANCE_ALLOWANCE}
 * @param dayMask          bit 0 is Monday, as on {@link TariffTimeRule}
 */
public record TariffDiscount(
        int sequence,
        int priority,
        Kind kind,
        Long amountMinor,
        Integer allowanceMeters,
        int dayMask,
        LocalTime fromTime,
        LocalTime toTime) {

    public enum Kind {

        /** A flat sum off the fee. */
        AMOUNT,

        /**
         * The first {@code allowanceMeters} are free: the discount is what the same
         * band table would have charged for that distance.
         *
         * <p>Evaluated against whichever table the matching time rule put in force,
         * so an allowance stays worth the peak price during a peak window. The
         * legacy reader does the same thing, by reading the same local variables it
         * just substituted.
         */
        DISTANCE_ALLOWANCE
    }

    public TariffDiscount {
        if (kind == null) {
            throw new IllegalArgumentException("A discount must say what kind it is");
        }
        // Stated as two equivalences rather than a null check on the pair the kind
        // happens to select. Either field set for the wrong kind is a value nothing
        // would ever read, which is how a discount silently becomes zero.
        if ((kind == Kind.AMOUNT) != (amountMinor != null)) {
            throw new IllegalArgumentException("An AMOUNT discount carries an amount and nothing else does");
        }
        if ((kind == Kind.DISTANCE_ALLOWANCE) != (allowanceMeters != null)) {
            throw new IllegalArgumentException(
                    "A DISTANCE_ALLOWANCE discount carries an allowance and nothing else does");
        }
        if (amountMinor != null && amountMinor < 0) {
            throw new IllegalArgumentException("A discount cannot add to the fee");
        }
        if (allowanceMeters != null && allowanceMeters < 0) {
            throw new IllegalArgumentException("A distance allowance cannot be negative");
        }
        if (dayMask < 1 || dayMask > 127) {
            throw new IllegalArgumentException("A day mask must select at least one day, was " + dayMask);
        }
        if (!toTime.isAfter(fromTime)) {
            throw new IllegalArgumentException(
                    "A discount window must not wrap midnight; author two instead of " + fromTime + " to " + toTime);
        }
    }

    /** Half-open on the closing edge, matching {@link TariffTimeRule#matches}. */
    public boolean matches(DayOfWeek day, LocalTime localTime) {
        boolean dayMatches = (dayMask & (1 << (day.getValue() - 1))) != 0;
        return dayMatches && !localTime.isBefore(fromTime) && localTime.isBefore(toTime);
    }
}
