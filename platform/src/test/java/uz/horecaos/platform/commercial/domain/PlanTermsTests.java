package uz.horecaos.platform.commercial.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** ADR 0093: a term discount in whole minor units, rounded half up, and none where no term is offered. */
class PlanTermsTests {

    @Test
    void aTermDiscountIsRoundedToTheNearestMinorUnit() {
        PlanTerms terms = new PlanTerms(14, 500_000, Map.of(12, 1_000, 6, 250));

        assertThat(terms.monthlyPriceOn(1_200_000, 12)).isEqualTo(1_080_000);
        assertThat(terms.monthlyPriceOn(999, 12))
                .as("99.9 of discount rounds to 100")
                .isEqualTo(899);
        assertThat(terms.monthlyPriceOn(998, 12)).as("99.8 rounds to 100 too").isEqualTo(898);
        assertThat(terms.monthlyPriceOn(994, 12)).as("99.4 rounds down").isEqualTo(895);
        assertThat(terms.monthlyPriceOn(1_200_000, 1))
                .as("month to month has no discount")
                .isEqualTo(1_200_000);
        assertThat(terms.monthlyPriceOn(1_200_000, 3))
                .as("a term not offered has none")
                .isEqualTo(1_200_000);
        assertThat(terms.termDiscounts().keySet()).containsExactly(6, 12);
    }
}
