package uz.horecaos.platform.integration.camel.sms;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The provider's code table and the sentence it carries, held to
 * {@code docs/providers/sms-gateway-vas.md}.
 */
class SmsGateCodeTests {

    /** Every code the document lists. A gap here is a code that would read as undocumented. */
    private static final List<Integer> DOCUMENTED = List.of(
            0, 1, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27);

    @Test
    @DisplayName("every code the document lists is known, and nothing else is")
    void theTableMatchesTheDocument() {
        for (int wireValue : DOCUMENTED) {
            assertThat(SmsGateCode.of(wireValue))
                    .as("code %d is in the provider document", wireValue)
                    .isNotEqualTo(SmsGateCode.UNDOCUMENTED);
        }

        // A code outside the table, and the absence of one altogether. Both are
        // uncertain rather than refused: refusing would assert that nothing was
        // sent, which nobody knows.
        assertThat(SmsGateCode.of(99)).isEqualTo(SmsGateCode.UNDOCUMENTED);
        assertThat(SmsGateCode.of(null)).isEqualTo(SmsGateCode.UNDOCUMENTED);
        assertThat(SmsGateCode.UNDOCUMENTED.effect()).isEqualTo(SmsGateCode.Effect.UNCERTAIN);
    }

    @Test
    @DisplayName("27 is the only retryable code")
    void onlyTheServerErrorRetries() {
        List<SmsGateCode> retryable = java.util.Arrays.stream(SmsGateCode.values())
                .filter(code -> code.effect() == SmsGateCode.Effect.RETRYABLE)
                .toList();

        // The document's own mapping. In particular 1 spam is not here: it is an
        // alarm about our own limiter, and backing off would hide it.
        assertThat(retryable).containsExactly(SmsGateCode.SERVER_ERROR);
        assertThat(SmsGateCode.SPAM.effect()).isEqualTo(SmsGateCode.Effect.REFUSED);
    }

    @Test
    @DisplayName("the blacklist keeps a reason code of its own")
    void blacklistIsNotFoldedIntoAGenericRefusal() {
        // Shared with any other code, this would reach a customer as "try again"
        // — and they never can.
        assertThat(SmsGateCode.RECEIVER_IN_BLACKLIST.reasonCode())
                .isEqualTo("SMS_RECEIVER_BLACKLISTED");

        long sharing = java.util.Arrays.stream(SmsGateCode.values())
                .filter(code -> code.reasonCode().equals("SMS_RECEIVER_BLACKLISTED"))
                .count();
        assertThat(sharing).isEqualTo(1);
    }

    @Test
    @DisplayName("the message fits one GSM-7 segment in every language it renders")
    void everyVariantIsOneSegment() {
        for (String locale : List.of("uz", "uz-UZ", "ru", "ru-RU", "en", "", "xx")) {
            String text = VerificationCodeText.render("482913", Duration.ofMinutes(5), locale);

            // Non-ASCII forces the whole message into UCS-2, where a segment is 70
            // characters rather than 160 — and the provider charges and reports
            // `parts` per segment. One Cyrillic character in the Russian variant
            // would silently double the cost of every code this platform sends.
            assertThat(text.getBytes(StandardCharsets.US_ASCII))
                    .as("%s renders outside ASCII", locale)
                    .asString(StandardCharsets.US_ASCII)
                    .isEqualTo(text);
            assertThat(text.length()).as("%s is longer than one segment", locale).isLessThan(70);
            assertThat(text).contains("482913");
        }
    }

    @Test
    @DisplayName("the minutes the customer reads are the minutes they were given")
    void validityIsToldRatherThanAssumed() {
        assertThat(VerificationCodeText.render("482913", Duration.ofMinutes(10), "uz"))
                .contains("10 daqiqa");
        // Never "0 minutes": a sub-minute validity still has to read as something a
        // person can act on.
        assertThat(VerificationCodeText.render("482913", Duration.ofSeconds(30), "en"))
                .contains("1 min");
    }
}
