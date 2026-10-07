package uz.horecaos.platform.customers.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PhoneMasksTests {

    @Test
    void anUzbekMobileKeepsItsPrefixAndItsLastFourDigits() {
        assertThat(PhoneMasks.mask("+998901234567")).isEqualTo("+998 ** *** 45 67");
    }

    @Test
    void anythingElseKeepsOnlyItsLastFourDigits() {
        assertThat(PhoneMasks.mask("+79161234567")).isEqualTo("*** 4567");
    }

    @Test
    void theMaskNeverContainsTheDigitsItHides() {
        assertThat(PhoneMasks.mask("+998901234567")).doesNotContain("9012").doesNotContain("123");
    }

    @Test
    void aShortValueDoesNotThrow() {
        assertThat(PhoneMasks.mask("+99")).isEqualTo("*** 99");
    }

    @Test
    void everySpellingOfOneNumberNormalizesToOneMask() {
        for (String spelling : new String[] {"+998 90 123 45 67", "998901234567", "+998 (90) 123-45-67"}) {
            assertThat(PhoneMasks.mask(LeadService.normalizePhone(spelling))).isEqualTo("+998 ** *** 45 67");
        }
    }
}
