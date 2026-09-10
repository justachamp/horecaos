package uz.horecaos.platform.commercial.domain;

import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * What a plan version sells beside its monthly price (ADR 0093): a trial
 * length, an activation deposit, and a discount for committing to a longer
 * term.
 *
 * @param trialDays              the trial a subscription starts with when none is
 *                               named, or null for none
 * @param activationDepositMinor charged once, in the month the subscription starts
 * @param termDiscounts          term in months to discount in basis points of the
 *                               monthly price
 */
public record PlanTerms(@Nullable Integer trialDays, long activationDepositMinor, Map<Integer, Integer> termDiscounts) {

    public static final PlanTerms NONE = new PlanTerms(null, 0, Map.of());

    public PlanTerms {
        termDiscounts = java.util.Collections.unmodifiableMap(new TreeMap<>(termDiscounts));
    }

    /** The discount for a term, zero for month to month or a term not offered. */
    public int discountFor(int termMonths) {
        return termDiscounts.getOrDefault(termMonths, 0);
    }

    /**
     * The monthly price on a term, in whole minor units.
     *
     * <p>The discount is rounded to the nearest minor unit, half up, so a
     * tenant is never charged a fraction of a som.
     */
    public long monthlyPriceOn(long priceMinor, int termMonths) {
        long discount =
                Math.floorDiv(Math.addExact(Math.multiplyExact(priceMinor, discountFor(termMonths)), 5_000L), 10_000L);
        return priceMinor - discount;
    }
}
