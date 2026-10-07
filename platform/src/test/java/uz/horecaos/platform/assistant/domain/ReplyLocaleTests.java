package uz.horecaos.platform.assistant.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReplyLocaleTests {

    @Test
    void theQuestionsOwnLanguageWinsOverAStoredPreference() {
        assertThat(ReplyLocale.detect("Сколько стоит плов?", "en")).isEqualTo("ru");
        assertThat(ReplyLocale.detect("How much is the plov", "ru")).isEqualTo("en");
        assertThat(ReplyLocale.detect("Plov narxi qancha, bormi", "ru")).isEqualTo("uz");
        assertThat(ReplyLocale.detect("Нарх қанча, ўзи?", "ru")).isEqualTo("uz");
    }

    @Test
    void aMessageThatSaysNothingAboutItsLanguageIsAnsweredInTheFallback() {
        assertThat(ReplyLocale.detect("plov", "uz")).isEqualTo("uz");
        assertThat(ReplyLocale.detect("12345", "en")).isEqualTo("en");
    }

    @Test
    void aStoredPreferenceIsReducedToOneOfTheThreeSupportedLanguages() {
        assertThat(ReplyLocale.fromPreference("uz-Latn", "ru")).isEqualTo("uz");
        assertThat(ReplyLocale.fromPreference("en-GB", "ru")).isEqualTo("en");
        assertThat(ReplyLocale.fromPreference("ru", "en")).isEqualTo("ru");
        assertThat(ReplyLocale.fromPreference("fr", "uz")).isEqualTo("uz");
        assertThat(ReplyLocale.fromPreference(null, "uz")).isEqualTo("uz");
    }
}
