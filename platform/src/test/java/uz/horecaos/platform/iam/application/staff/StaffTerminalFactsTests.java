package uz.horecaos.platform.iam.application.staff;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR 0155: how a colleague reads on a shared screen. */
class StaffTerminalFactsTests {

    @Test
    @DisplayName("first name and the initial of the last name; nothing more")
    void nameWithInitial() {
        assertThat(StaffTerminalFacts.nameWithInitial("Aziza", "Karimova")).isEqualTo("Aziza K.");
        assertThat(StaffTerminalFacts.nameWithInitial(" Aziza ", " karimova ")).isEqualTo("Aziza k.");
        assertThat(StaffTerminalFacts.nameWithInitial("Aziza", null)).isEqualTo("Aziza");
        assertThat(StaffTerminalFacts.nameWithInitial("Aziza", " ")).isEqualTo("Aziza");
        assertThat(StaffTerminalFacts.nameWithInitial(null, "Karimova"))
                .as("no first name: the roster reads the non-personal reference instead")
                .isNull();
        assertThat(StaffTerminalFacts.nameWithInitial(" ", "Karimova")).isNull();
    }

    @Test
    @DisplayName("an initial is a code point, not a UTF-16 unit")
    void theInitialSurvivesNonBmpCharacters() {
        assertThat(StaffTerminalFacts.nameWithInitial("Мария", "Ёлкина")).isEqualTo("Мария Ё.");
        assertThat(StaffTerminalFacts.nameWithInitial("Test", "𝒜BC")).isEqualTo("Test 𝒜.");
    }
}
