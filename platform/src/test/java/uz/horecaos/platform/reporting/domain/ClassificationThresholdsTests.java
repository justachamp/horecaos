package uz.horecaos.platform.reporting.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** T14 (7.7a/7.7b): the boundary arithmetic a disputed class-C ruling is checked against. */
class ClassificationThresholdsTests {

    private static final ClassificationThresholds DEFAULT = ClassificationThresholds.DEFAULT;

    @Test
    void theDefaultIsStatisticsMdsOwnEightyNinetyFiveSplit() {
        assertThat(DEFAULT.abcThresholdA()).isEqualTo(8_000);
        assertThat(DEFAULT.abcThresholdB()).isEqualTo(9_500);
    }

    @Test
    void cumulativeShareAtOrBelowTheFirstThresholdIsClassA() {
        assertThat(DEFAULT.abcClassOf(0)).isEqualTo('A');
        assertThat(DEFAULT.abcClassOf(8_000)).isEqualTo('A');
    }

    @Test
    void cumulativeShareBetweenTheTwoThresholdsIsClassB() {
        assertThat(DEFAULT.abcClassOf(8_001)).isEqualTo('B');
        assertThat(DEFAULT.abcClassOf(9_500)).isEqualTo('B');
    }

    @Test
    void cumulativeShareAboveTheSecondThresholdIsClassC() {
        assertThat(DEFAULT.abcClassOf(9_501)).isEqualTo('C');
        assertThat(DEFAULT.abcClassOf(10_000)).isEqualTo('C');
    }

    @Test
    void lowCoefficientOfVariationIsClassX() {
        assertThat(DEFAULT.xyzClassOf(0)).isEqualTo('X');
        assertThat(DEFAULT.xyzClassOf(1_000)).isEqualTo('X');
    }

    @Test
    void midCoefficientOfVariationIsClassY() {
        assertThat(DEFAULT.xyzClassOf(1_001)).isEqualTo('Y');
        assertThat(DEFAULT.xyzClassOf(2_500)).isEqualTo('Y');
    }

    @Test
    void highCoefficientOfVariationIsClassZ() {
        assertThat(DEFAULT.xyzClassOf(2_501)).isEqualTo('Z');
        assertThat(DEFAULT.xyzClassOf(50_000)).isEqualTo('Z');
    }

    @Test
    void thresholdsMustBeOrderedAndInRange() {
        assertThatThrownBy(() -> new ClassificationThresholds(9_000, 8_000, 1_000, 2_500))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassificationThresholds(8_000, 10_001, 1_000, 2_500))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClassificationThresholds(8_000, 9_500, 2_500, 1_000))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
