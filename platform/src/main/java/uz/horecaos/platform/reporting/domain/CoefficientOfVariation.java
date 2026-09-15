package uz.horecaos.platform.reporting.domain;

import java.util.List;

/**
 * The XYZ half of 7.7b's classification (T14, ADR 0134): how erratic a
 * product's demand is across a series of equal-length buckets, as a single
 * dimensionless number that a revenue figure alone cannot give.
 *
 * <p>Population statistics, not sample statistics — {@code stddev / N}, never
 * Bessel's {@code N - 1} correction. The series passed in is not a sample
 * drawn from a larger population; it is every bucket the requested window
 * actually has, so the population is exactly what is being described.
 *
 * <p>A pure function over a list of counts, deliberately: no clock, no
 * database, no tenant. {@link uz.horecaos.platform.reporting.application.ProductClassificationService}
 * is the only caller, and keeping this free of every dependency is what
 * makes "the coefficient of variation on a known series" directly testable
 * without a schema.
 */
public final class CoefficientOfVariation {

    private CoefficientOfVariation() {}

    /**
     * @param mean                    the arithmetic mean of the series
     * @param populationStandardDeviation the population standard deviation of the series
     * @param coefficientOfVariation populationStandardDeviation / mean, or 0
     *                               when the mean is 0 — a series that never
     *                               moved off zero has nothing to vary,
     *                               which is a different statement from an
     *                               undefined ratio
     */
    public record SeriesStatistics(double mean, double populationStandardDeviation, double coefficientOfVariation) {}

    /**
     * @param bucketQuantities one entry per bucket in the window, in order,
     *                         zero for a bucket with no sales — never omitted,
     *                         because a missing entry and a zero entry are
     *                         different facts about demand
     * @throws IllegalArgumentException if the series is empty; a
     *                                  classification run's own bucket count
     *                                  is always at least 1, so an empty
     *                                  series reaching here is a caller bug
     */
    public static SeriesStatistics of(List<Long> bucketQuantities) {
        if (bucketQuantities.isEmpty()) {
            throw new IllegalArgumentException("A coefficient of variation needs at least one bucket");
        }
        int n = bucketQuantities.size();
        double mean =
                bucketQuantities.stream().mapToLong(Long::longValue).average().orElseThrow();
        double sumSquaredDeviation = 0.0;
        for (Long quantity : bucketQuantities) {
            double deviation = quantity - mean;
            sumSquaredDeviation += deviation * deviation;
        }
        double populationStdDev = Math.sqrt(sumSquaredDeviation / n);
        double coefficientOfVariation = mean == 0.0 ? 0.0 : populationStdDev / mean;
        return new SeriesStatistics(mean, populationStdDev, coefficientOfVariation);
    }
}
