package uz.horecaos.platform.iam.application.staff;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR 0139: a contact phone is plausible, never checked against a colleague, and masked in a list. */
class StaffPhonesTests {

    @Test
    @DisplayName("formatting is ignored and the plus is kept; the digits feed the lookup hash")
    void formattingIsIgnored() {
        var phone = StaffPhones.parse(" +998 (90) 123-45-67 ").orElseThrow();

        assertThat(phone.stored()).isEqualTo("+998901234567");
        assertThat(phone.digits()).isEqualTo("998901234567");
        assertThat(StaffPhones.parse("998901234567").orElseThrow().digits()).isEqualTo(phone.digits());
    }

    @Test
    @DisplayName("blank is no phone; anything else that is not seven to fifteen digits is refused")
    void blankIsNoPhoneAndGarbageIsRefused() {
        assertThat(StaffPhones.parse(null)).isEmpty();
        assertThat(StaffPhones.parse("   ")).isEmpty();
        assertThatThrownBy(() -> StaffPhones.parse("12345")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StaffPhones.parse("+99890123456789012")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StaffPhones.parse("90-ABC-1234")).isInstanceOf(IllegalArgumentException.class);
        assertThat(StaffPhones.parseLeniently("90-ABC-1234"))
                .as("an invitation must not fail over a phone")
                .isEmpty();
    }

    @Test
    @DisplayName("the list's mask keeps the operator code and the last two digits and nothing a scraper could dial")
    void theMaskHidesTheMiddle() {
        assertThat(StaffPhones.mask("+998901234567")).isEqualTo("+998 90 ••• •• 67");
        assertThat(StaffPhones.mask("+998901234567")).doesNotContain("1234", "34");
        assertThat(StaffPhones.mask("123456")).isEqualTo("••••••");
        assertThat(StaffPhones.mask("+441234567890"))
                .startsWith("+441")
                .endsWith("90")
                .contains("•");
        assertThat(StaffPhones.mask(null)).isNull();
        assertThat(StaffPhones.mask("")).isNull();
    }

    @Test
    @DisplayName("a phone never prints itself")
    void aPhoneNeverPrintsItself() {
        assertThat(StaffPhones.parse("+998901234567").orElseThrow().toString())
                .doesNotContain("998")
                .doesNotContain("901234567");
    }
}
