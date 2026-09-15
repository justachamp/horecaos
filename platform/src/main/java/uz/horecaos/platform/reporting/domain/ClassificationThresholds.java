package uz.horecaos.platform.reporting.domain;

/**
 * T14 (7.7a/7.7b), ADR 0134: the boundaries a classification run classifies
 * against, and the pure functions that apply them.
 *
 * <p>Every field is basis points (0-10000 = 0-100%), the same unit {@code
 * BucketResponse.shareBasisPoints} already uses elsewhere in reporting, so a
 * class boundary is an exact integer rather than a rounded percentage nobody
 * can reproduce.
 *
 * @param abcThresholdA cumulative revenue share up to and including this
 *                       value is class A — statistics.md's printed "пороги
 *                       80/95%", the 80 half
 * @param abcThresholdB cumulative revenue share up to and including this
 *                       value is class B; above it is class C — the 95 half
 * @param xyzThresholdX coefficient of variation up to and including this
 *                       value is class X (steady demand)
 * @param xyzThresholdY coefficient of variation up to and including this
 *                       value is class Y (seasonal); above it is class Z
 *                       (erratic)
 */
public record ClassificationThresholds(int abcThresholdA, int abcThresholdB, int xyzThresholdX, int xyzThresholdY) {

    public ClassificationThresholds {
        if (abcThresholdA <= 0 || abcThresholdA >= abcThresholdB || abcThresholdB > 10_000) {
            throw new IllegalArgumentException("ABC thresholds must satisfy 0 < A < B <= 10000, were %d/%d"
                    .formatted(abcThresholdA, abcThresholdB));
        }
        if (xyzThresholdX <= 0 || xyzThresholdX >= xyzThresholdY) {
            throw new IllegalArgumentException(
                    "XYZ thresholds must satisfy 0 < X < Y, were %d/%d".formatted(xyzThresholdX, xyzThresholdY));
        }
    }

    /**
     * statistics.md's own numbers: 80% / 95% cumulative revenue share for
     * ABC. XYZ's 10% / 25% coefficient-of-variation boundary is not named by
     * the spec — it is the conventional textbook split (steady / seasonal /
     * erratic demand) chosen as the platform's default and recorded on every
     * run's own row precisely so it is a fact a run states rather than a
     * constant nobody can see; ADR 0134's open input names it as something
     * the platform owner may want to revise once real tenant data exists.
     */
    public static final ClassificationThresholds DEFAULT = new ClassificationThresholds(8_000, 9_500, 1_000, 2_500);

    /** Cumulative revenue share, in basis points, including this product's own share. */
    public char abcClassOf(int cumulativeShareBasisPoints) {
        if (cumulativeShareBasisPoints <= abcThresholdA) {
            return 'A';
        }
        return cumulativeShareBasisPoints <= abcThresholdB ? 'B' : 'C';
    }

    /** The coefficient of variation, in basis points (10000 = a CV of 1.0). */
    public char xyzClassOf(int coefficientOfVariationBasisPoints) {
        if (coefficientOfVariationBasisPoints <= xyzThresholdX) {
            return 'X';
        }
        return coefficientOfVariationBasisPoints <= xyzThresholdY ? 'Y' : 'Z';
    }
}
