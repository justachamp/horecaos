package uz.horecaos.platform.pos.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.pos.domain.LineFingerprint.Line;

/**
 * The recovery fingerprint over a quantity that may now be a fraction (ADR 0137).
 *
 * <p>A fingerprint is recorded against a provider order when it is sent and compared against
 * the till's own copy later, so it has to keep meaning what it meant: every order of whole
 * portions already exported must hash exactly as it did when quantity was an integer. The
 * canonical text is assembled here by hand for that reason, not read back from the class.
 */
class LineFingerprintTests {

    @Test
    @DisplayName("a whole quantity hashes exactly as the integer it replaced")
    void wholeQuantitiesAreUnchanged() {
        String expected = sha256("41:2:32000|");

        assertThat(LineFingerprint.of(List.of(new Line("41", 2, 32_000L)))).isEqualTo(expected);
        assertThat(LineFingerprint.of(List.of(new Line("41", new BigDecimal("2.000"), 32_000L))))
                .as("2 and 2.000 are the same quantity")
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("a half portion hashes as 0.5, and differs from one portion")
    void aFractionIsPartOfTheFingerprint() {
        assertThat(LineFingerprint.of(List.of(new Line("41", new BigDecimal("0.5"), 32_000L))))
                .isEqualTo(sha256("41:0.5:32000|"))
                .isNotEqualTo(LineFingerprint.of(List.of(new Line("41", 1, 32_000L))));
    }

    @Test
    @DisplayName("a tenfold quantity is 10, never 1E+1")
    void aRoundTenIsNotScientific() {
        assertThat(LineFingerprint.of(List.of(new Line("41", new BigDecimal("10.0"), 100L))))
                .isEqualTo(sha256("41:10:100|"));
    }

    @Test
    @DisplayName("lines are ordered by product, then by quantity as a number, then by price")
    void linesAreOrderedNumerically() {
        String expected = sha256("41:0.5:100|41:9:100|41:10:100|42:1:100|");

        assertThat(LineFingerprint.of(List.of(
                        new Line("42", 1, 100L),
                        new Line("41", 10, 100L),
                        new Line("41", new BigDecimal("0.5"), 100L),
                        new Line("41", 9, 100L))))
                .as("9 before 10 before 42, whichever order the lines were handed in")
                .isEqualTo(expected);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
