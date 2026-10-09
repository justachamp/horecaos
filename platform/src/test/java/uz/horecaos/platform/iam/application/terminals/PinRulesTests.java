package uz.horecaos.platform.iam.application.terminals;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.application.terminals.PinRules.Refusal;

/** ADR 0155, Decision 3: what a PIN may not be, refused at entry with the reason stated. */
class PinRulesTests {

    @Test
    @DisplayName(
            "a run of one digit, an ascending or descending run of the whole length, and the deny-list are refused")
    void weakPinsAreRefusedWithTheirReason() {
        assertThat(PinRules.check("111111", 6, Set.of())).isEqualTo(Refusal.REPEATED_DIGIT);
        assertThat(PinRules.check("0000", 4, Set.of())).isEqualTo(Refusal.REPEATED_DIGIT);
        assertThat(PinRules.check("123456", 6, Set.of())).isEqualTo(Refusal.SEQUENCE);
        assertThat(PinRules.check("234567", 6, Set.of()))
                .as("any ascending run, not only the deny-listed one")
                .isEqualTo(Refusal.SEQUENCE);
        assertThat(PinRules.check("654321", 6, Set.of())).isEqualTo(Refusal.SEQUENCE);
        assertThat(PinRules.check("9876", 4, Set.of())).isEqualTo(Refusal.SEQUENCE);
        assertThat(PinRules.check("121212", 6, Set.of()))
                .as("the named deny-list entry")
                .isEqualTo(Refusal.COMMON);
        assertThat(PinRules.check("1212", 4, Set.of())).isEqualTo(Refusal.COMMON);
    }

    @Test
    @DisplayName("a PIN equal to the last digits of the person's own phone or employee number is refused")
    void aPinThatIsASliceOfSomethingAboutThePersonIsRefused() {
        assertThat(PinRules.check("567890", 6, Set.of("567890"))).isEqualTo(Refusal.PERSONAL_NUMBER);
        assertThat(PinRules.check("567891", 6, Set.of("567890"))).isNull();
    }

    @Test
    @DisplayName("only digits, and only the length the tenant chose")
    void shapeIsChecked() {
        assertThat(PinRules.check("12ab56", 6, Set.of())).isEqualTo(Refusal.NOT_DIGITS);
        assertThat(PinRules.check("", 6, Set.of())).isEqualTo(Refusal.WRONG_LENGTH);
        assertThat(PinRules.check("48291", 6, Set.of())).isEqualTo(Refusal.WRONG_LENGTH);
        assertThat(PinRules.check("4829", 6, Set.of())).isEqualTo(Refusal.WRONG_LENGTH);
        assertThat(PinRules.check("482915", 4, Set.of())).isEqualTo(Refusal.WRONG_LENGTH);
    }

    @Test
    @DisplayName("an ordinary PIN is accepted at both lengths")
    void anOrdinaryPinPasses() {
        assertThat(PinRules.check("482915", 6, Set.of())).isNull();
        assertThat(PinRules.check("4829", 4, Set.of())).isNull();
        assertThat(PinRules.check("135790", 6, Set.of()))
                .as("not a run: the digits skip")
                .isNull();
    }
}
