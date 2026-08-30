package uz.qoida.platform.fulfillment.domain.tariff;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * A peak-hours rule (ADR 0037).
 *
 * <p>The window is local wall-clock in the location's IANA timezone, evaluated at
 * quote creation. A recurring evening surcharge is a rule about local time, so
 * storing it as an instant would make it drift the moment a zone changes — the
 * same reasoning V0020 gives for opening hours.
 *
 * <p>Windows do not wrap midnight. "22:00 to 02:00" is authored as two rules
 * because a single row needs the evaluator to special-case {@code from > to}, and
 * that special case is silently missing in every implementation that forgets it.
 *
 * <p>A rule can do two independent things, and V0025 only had the second.
 * {@link #bandSet()} <em>replaces</em> the rate table for the window; the
 * multiplier and surcharge <em>adjust</em> whatever table is in force. The legacy
 * dashboard does the first and ADR 0037 originally modelled only the second, which
 * is not a simplification — adding a surcharge to a base rate computes a different
 * number from swapping the rate out, and the difference is money. Both are
 * available and they compose: substitute nothing and surcharge, substitute and
 * leave the multiplier at par, or both.
 *
 * @param bandSet the {@link TariffBand#bandSet()} this rule puts in force, or null
 *                to leave {@link TariffBand#BASE_SET} standing
 * @param dayMask bit 0 is Monday, matching {@link DayOfWeek}'s 1..7 shifted down
 * @param multiplierBasisPoints applied before the surcharge, per ADR 0037
 */
public record TariffTimeRule(
        int sequence,
        int priority,
        int dayMask,
        LocalTime fromTime,
        LocalTime toTime,
        String bandSet,
        int multiplierBasisPoints,
        long surchargeMinor) {

    /** A rule that only surcharges, leaving the base table in force. */
    public TariffTimeRule(int sequence, int priority, int dayMask, LocalTime fromTime,
            LocalTime toTime, int multiplierBasisPoints, long surchargeMinor) {
        this(sequence, priority, dayMask, fromTime, toTime, null,
                multiplierBasisPoints, surchargeMinor);
    }

    public TariffTimeRule {
        if (dayMask < 1 || dayMask > 127) {
            throw new IllegalArgumentException(
                    "A day mask must select at least one day, was " + dayMask);
        }
        if (!toTime.isAfter(fromTime)) {
            throw new IllegalArgumentException(
                    "A time rule must not wrap midnight; author two rules instead of "
                            + fromTime + " to " + toTime);
        }
        if (multiplierBasisPoints <= 0 || surchargeMinor < 0) {
            throw new IllegalArgumentException("A time rule cannot reduce a fee below zero");
        }
        if (bandSet != null && (bandSet.isBlank() || TariffBand.BASE_SET.equals(bandSet))) {
            throw new IllegalArgumentException(
                    "A rule that substitutes the base set for itself substitutes nothing; "
                            + "leave the band set unset instead");
        }
    }

    /** Half-open on the closing edge, so two adjacent windows cannot both claim 18:00. */
    public boolean matches(DayOfWeek day, LocalTime localTime) {
        boolean dayMatches = (dayMask & (1 << (day.getValue() - 1))) != 0;
        return dayMatches && !localTime.isBefore(fromTime) && localTime.isBefore(toTime);
    }

    /** The table in force under this rule. */
    public String effectiveBandSet() {
        return bandSet == null ? TariffBand.BASE_SET : bandSet;
    }
}
