package uz.horecaos.platform.ordering.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The masked form orders.md §1.5 shows on the board and the detail screen. */
class PhoneMaskingTests {

    @Test
    void aUzbekNumberMasksToTheDocumentedShape() {
        assertThat(PhoneMasking.mask("+998901234567")).isEqualTo("+998 90 ••• •• 67");
    }

    @Test
    void theRawDigitsNeverAppearInTheMaskedForm() {
        String masked = PhoneMasking.mask("+998901234567");
        assertThat(masked).doesNotContain("1234567").doesNotContain("901234567");
        // Only the operator code and the last two digits survive.
        assertThat(masked).contains("90").contains("67");
    }

    @Test
    void nullAndBlankMaskToNull() {
        assertThat(PhoneMasking.mask(null)).isNull();
        assertThat(PhoneMasking.mask("")).isNull();
        assertThat(PhoneMasking.mask("   ")).isNull();
    }

    @Test
    void aNonUzbekShapeIsStillMaskedRatherThanPassedThrough() {
        String masked = PhoneMasking.mask("+1-212-555-0199");
        assertThat(masked).isNotEqualTo("+1-212-555-0199");
        assertThat(masked).doesNotContain("555");
        assertThat(masked).startsWith("+1-2").endsWith("99");
    }

    @Test
    void aVeryShortValueIsFullyMasked() {
        assertThat(PhoneMasking.mask("123")).isEqualTo("•••");
    }
}
