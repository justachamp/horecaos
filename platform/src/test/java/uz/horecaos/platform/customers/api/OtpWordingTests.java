package uz.horecaos.platform.customers.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ADR 0184, D19: the one-time-code sentence is the platform's, one text that is both what the SMS route
 * sends and what Tab 1 shows read-only.
 */
class OtpWordingTests {

    @Test
    @DisplayName("each language renders the sentence the SMS route has always sent")
    void theSentencesAreUnchanged() {
        assertThat(OtpWording.render("482913", Duration.ofMinutes(5), "ru"))
                .isEqualTo("HorecaOS: kod 482913. Deystvitelen 5 min. Nikomu ego ne soobshchayte.");
        assertThat(OtpWording.render("482913", Duration.ofMinutes(5), "en"))
                .isEqualTo("HorecaOS: your code is 482913. Valid for 5 min. Do not share it.");
        assertThat(OtpWording.render("482913", Duration.ofMinutes(5), "uz"))
                .isEqualTo("HorecaOS: kod 482913. 5 daqiqa amal qiladi. Hech kimga aytmang.");
    }

    @Test
    @DisplayName("a regional tag reads the same language, and Uzbek is what a customer with no preference reads")
    void languageSelection() {
        assertThat(OtpWording.render("1", Duration.ofMinutes(5), "ru-RU"))
                .isEqualTo(OtpWording.render("1", Duration.ofMinutes(5), "ru"));
        assertThat(OtpWording.render("1", Duration.ofMinutes(5), null))
                .isEqualTo(OtpWording.render("1", Duration.ofMinutes(5), "uz-Latn"));
        assertThat(OtpWording.render("1", Duration.ofMinutes(5), "xx")).contains("daqiqa");
    }

    @Test
    @DisplayName("the console is shown the three sentences with their placeholders still in them")
    void theConsoleSeesThePatterns() {
        List<OtpWording.Sentence> sentences = OtpWording.sentences();
        assertThat(sentences).extracting(OtpWording.Sentence::locale).containsExactly("ru", "uz-Latn", "en");
        for (OtpWording.Sentence sentence : sentences) {
            assertThat(sentence.pattern()).contains(OtpWording.CODE).contains(OtpWording.MINUTES);
        }
    }

    @Test
    @DisplayName("every sentence stays ASCII and under one 70-character segment with a six-digit code")
    void everySentenceIsOneSegment() {
        for (OtpWording.Sentence sentence : OtpWording.sentences()) {
            // Five minutes, as the code is issued and as SmsGateCodeTests holds the adapter's seam to.
            String text = OtpWording.render("482913", Duration.ofMinutes(5), sentence.locale());
            assertThat(text.getBytes(StandardCharsets.US_ASCII))
                    .asString(StandardCharsets.US_ASCII)
                    .isEqualTo(text);
            assertThat(text.length()).as(sentence.locale()).isLessThan(70);
        }
    }

    @Test
    @DisplayName("a validity under a minute reads as one minute, never zero")
    void validityIsAtLeastAMinute() {
        assertThat(OtpWording.render("1", Duration.ofSeconds(30), "en")).contains("Valid for 1 min");
    }
}
