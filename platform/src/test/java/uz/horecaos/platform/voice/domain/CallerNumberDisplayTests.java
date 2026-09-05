package uz.horecaos.platform.voice.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CallerNumberDisplayTests {

    @Test
    void masksEverythingButTheLastFourDigits() {
        assertThat(CallerNumberDisplay.mask("+998901234567")).isEqualTo("•••••••••4567");
    }

    @Test
    void aShortNumberIsMaskedEntirely() {
        // What would still pass if this were broken: returning the input
        // unchanged for a short value, which is exactly the "not actually
        // masked" failure this test exists to catch.
        assertThat(CallerNumberDisplay.mask("42")).isEqualTo("••");
        assertThat(CallerNumberDisplay.mask("42")).doesNotContain("42");
    }

    @Test
    void neverContainsMoreThanTheLastFourOriginalCharacters() {
        String masked = CallerNumberDisplay.mask("+998901234567");
        assertThat(masked).endsWith("4567");
        assertThat(masked).doesNotContain("901234");
    }
}
