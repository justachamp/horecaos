package uz.horecaos.platform.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.reporting.domain.CoefficientOfVariation.SeriesStatistics;

/**
 * T14 (7.7b): the XYZ coefficient of variation over a known series, with no
 * database and no clock — {@link CoefficientOfVariation} is a pure function.
 */
class CoefficientOfVariationTests {

    @Test
    void aKnownSeriesProducesTheTextbookMeanStdDevAndCv() {
        // Population stddev of {10, 20, 30, 40}: mean 25, deviations
        // {-15,-5,5,15}, squared {225,25,25,225}, sum 500, /4 = 125,
        // sqrt(125) = 11.180339887498949. CV = stddev / mean.
        SeriesStatistics stats = CoefficientOfVariation.of(List.of(10L, 20L, 30L, 40L));

        assertThat(stats.mean()).isEqualTo(25.0);
        assertThat(stats.populationStandardDeviation()).isCloseTo(11.180339887498949, within(1e-9));
        assertThat(stats.coefficientOfVariation()).isCloseTo(0.4472135954999579, within(1e-9));
    }

    @Test
    void aConstantSeriesHasZeroVariationEvenThoughDemandIsNonZero() {
        SeriesStatistics stats = CoefficientOfVariation.of(List.of(10L, 10L, 10L, 10L));

        assertThat(stats.mean()).isEqualTo(10.0);
        assertThat(stats.populationStandardDeviation()).isZero();
        assertThat(stats.coefficientOfVariation())
                .as("perfectly steady demand is the X class's whole point")
                .isZero();
    }

    @Test
    void aSingleSpikeInAnOtherwiseIdleSeriesReadsAsHighlyErratic() {
        // Everything sold in one bucket out of four: mean 10, deviations
        // {30,-10,-10,-10}, squared {900,100,100,100}, sum 1200, /4 = 300,
        // sqrt(300) = 17.320508... CV = 1.7320508..., well past any
        // reasonable X or Y boundary.
        SeriesStatistics stats = CoefficientOfVariation.of(List.of(40L, 0L, 0L, 0L));

        assertThat(stats.coefficientOfVariation()).isGreaterThan(1.0);
    }

    @Test
    void anAllZeroSeriesHasNoRatioRatherThanDividingByZero() {
        SeriesStatistics stats = CoefficientOfVariation.of(List.of(0L, 0L, 0L));

        assertThat(stats.mean()).isZero();
        assertThat(stats.populationStandardDeviation()).isZero();
        assertThat(stats.coefficientOfVariation())
                .as("nothing measured across every bucket is not the same claim as an undefined ratio")
                .isZero();
    }

    @Test
    void anEmptySeriesIsRefusedRatherThanAveragingNothing() {
        assertThatThrownBy(() -> CoefficientOfVariation.of(List.of())).isInstanceOf(IllegalArgumentException.class);
    }

    /**
     * Proves the assertion can fail: a broken implementation dividing by
     * {@code n - 1} (the sample-statistics correction) instead of {@code n}
     * would report a different stddev for this series, and this test would
     * catch it — see CLAUDE.md's own rule about writing an assertion that can
     * fail.
     */
    @Test
    void populationStdDevDiffersFromTheSampleCorrection() {
        SeriesStatistics stats = CoefficientOfVariation.of(List.of(10L, 20L, 30L, 40L));

        double sampleStdDev = Math.sqrt(500.0 / 3); // n - 1 = 3
        assertThat(stats.populationStandardDeviation()).isNotCloseTo(sampleStdDev, within(1e-9));
    }
}
