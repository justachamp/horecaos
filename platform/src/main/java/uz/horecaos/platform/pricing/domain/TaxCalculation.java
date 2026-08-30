package uz.horecaos.platform.pricing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Tax arithmetic (ADR 0018).
 *
 * <p>Prices at HorecaOS are VAT-inclusive: the menu price is what the customer pays
 * and tax is extracted from it. That is not a display preference — it changes the
 * arithmetic. Extracting gives {@code gross × rate ÷ (10000 + rate)}, where
 * adding would give {@code net × rate ÷ 10000}, and the two produce different
 * fiscal figures for the same customer-visible price.
 *
 * <p>It also means a discount reduces tax proportionally, because the discount
 * reduces the gross amount that tax is extracted from.
 *
 * <p>All arithmetic is integer or {@link BigDecimal}. A {@code double} rate would
 * round differently on different machines, which would make a quote irreproducible
 * — the one thing it must not be.
 */
public final class TaxCalculation {

    /**
     * Half-up, not half-even. Banker's rounding is less biased over many
     * transactions, but a merchant checking one receipt by hand expects half to
     * go up, and an unexplainable receipt costs more than the bias.
     */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private TaxCalculation() {}

    /**
     * The tax contained within a VAT-inclusive amount.
     *
     * @param rateBasisPoints 1200 for 12%
     */
    public static long extractInclusiveTax(long grossMinor, int rateBasisPoints) {
        if (rateBasisPoints == 0) {
            return 0;
        }
        return BigDecimal.valueOf(grossMinor)
                .multiply(BigDecimal.valueOf(rateBasisPoints))
                .divide(BigDecimal.valueOf(10_000L + rateBasisPoints), 0, ROUNDING)
                .longValueExact();
    }

    /**
     * The tax to add to a net amount.
     *
     * <p>Present for a future jurisdiction that quotes net prices. The first
     * slice rejects EXCLUSIVE profiles rather than using this, because a mode
     * that is half-implemented is worse than one that is refused.
     */
    public static long addExclusiveTax(long netMinor, int rateBasisPoints) {
        if (rateBasisPoints == 0) {
            return 0;
        }
        return BigDecimal.valueOf(netMinor)
                .multiply(BigDecimal.valueOf(rateBasisPoints))
                .divide(BigDecimal.valueOf(10_000L), 0, ROUNDING)
                .longValueExact();
    }

    /**
     * Splits a total across lines so the parts sum exactly to the whole.
     *
     * <p>Rounding each line independently leaves a remainder of a som or two, and
     * a total that does not equal the sum of its lines is the kind of thing an
     * accountant finds and nobody can explain. The remainder goes to the largest
     * line — the least visible place for it — and on a tie to the first of them,
     * so the split is reproducible rather than merely fair.
     *
     * @return per-line amounts, in the order the weights were given
     */
    public static long[] apportion(long totalMinor, long[] weights) {
        long weightSum = 0;
        for (long weight : weights) {
            weightSum += weight;
        }
        long[] shares = new long[weights.length];
        if (weightSum == 0) {
            return shares;
        }

        long allocated = 0;
        int largestIndex = 0;
        for (int i = 0; i < weights.length; i++) {
            shares[i] = BigDecimal.valueOf(totalMinor)
                    .multiply(BigDecimal.valueOf(weights[i]))
                    .divide(BigDecimal.valueOf(weightSum), 0, RoundingMode.DOWN)
                    .longValueExact();
            allocated += shares[i];
            if (weights[i] > weights[largestIndex]) {
                largestIndex = i;
            }
        }
        shares[largestIndex] += totalMinor - allocated;
        return shares;
    }
}
