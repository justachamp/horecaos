package uz.horecaos.platform.pricing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * The arithmetic of a price quoted per weight quantum (ADR 0137).
 *
 * <p>A catchweight variant's price row means "this much per {@code quantumGrams}",
 * not "this much per unit". The charge for a weight is therefore
 * {@code price * grams / quantum}, <em>rounded once, at the end, to a whole minor
 * unit</em> -- never per gram and never per quantum first. ADR 0137 rejected pricing
 * linearly per gram for exactly this reason: a market prices "per 100 g", and
 * rounding a 1.5-som gram up to 2 before multiplying produces materially different
 * totals at typical portion sizes. One division, one rounding, {@code HALF_UP}, the
 * rounding every other money figure in the platform already uses.
 *
 * <p>Integer minor units in and out; the weight is the only decimal and it is
 * consumed in the same expression.
 */
public final class CatchweightPricing {

    private CatchweightPricing() {}

    /**
     * What {@code grams} of the variant cost at {@code pricePerQuantumMinor} per
     * {@code quantumGrams}.
     *
     * @throws ArithmeticException on overflow, which no menu price can reach
     */
    public static long priceOf(long pricePerQuantumMinor, int quantumGrams, BigDecimal grams) {
        return BigDecimal.valueOf(pricePerQuantumMinor)
                .multiply(grams)
                .divide(BigDecimal.valueOf(quantumGrams), 0, RoundingMode.HALF_UP)
                .longValueExact();
    }

    /** {@link #priceOf(long, int, BigDecimal)} for a whole number of grams. */
    public static long priceOf(long pricePerQuantumMinor, int quantumGrams, long grams) {
        return priceOf(pricePerQuantumMinor, quantumGrams, BigDecimal.valueOf(grams));
    }
}
