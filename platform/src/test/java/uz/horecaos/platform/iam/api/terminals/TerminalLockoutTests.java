package uz.horecaos.platform.iam.api.terminals;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ADR 0155, Open input 4: the lockout numbers are code constants, and the odds the Policy card states follow from them. */
class TerminalLockoutTests {

    @Test
    @DisplayName(
            "five consecutive wrong PINs lock a person on a device for fifteen minutes; twelve in a day lock the credential")
    void theConstantsAreTheOnesTheRecordNames() {
        assertThat(TerminalLockout.DEVICE_CONSECUTIVE_FAILURES).isEqualTo(5);
        assertThat(TerminalLockout.DEVICE_LOCK).isEqualTo(Duration.ofMinutes(15));
        assertThat(TerminalLockout.CREDENTIAL_FAILURES_PER_DAY).isEqualTo(12);
        assertThat(TerminalLockout.FAILURE_WINDOW).isEqualTo(Duration.ofHours(24));
    }

    @Test
    @DisplayName(
            "twelve a day against a million six-digit PINs is about 1 in 83,000; against ten thousand four-digit PINs about 1 in 830")
    void theGuessOddsAreTheRecordsOwn() {
        assertThat(TerminalLockout.guessOneIn(6)).isEqualTo(83_333);
        assertThat(TerminalLockout.guessOneIn(4)).isEqualTo(833);
    }
}
