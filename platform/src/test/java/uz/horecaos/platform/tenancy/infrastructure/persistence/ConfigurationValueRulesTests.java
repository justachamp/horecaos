package uz.horecaos.platform.tenancy.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.assistant.api.AssistantConfigurationKeys;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/** The rules a typed key cannot express on its own (ADR 0030), as the one writer applies them. */
class ConfigurationValueRulesTests {

    private static final String DISCLOSURE_RU = "assistant.disclosure_text_ru";

    private static void validate(String code, Object value) {
        ConfigurationValueRules.validate(ConfigurationKeys.require(code), value);
    }

    @Test
    @DisplayName(
            "the longest disclosure the writer accepts is the number the assistant module declares: one limit, two places")
    void theDisclosureLimitIsOneNumber() {
        assertThat(ConfigurationValueRules.DISCLOSURE_TEXT_MAXIMUM_CHARACTERS)
                .isEqualTo(AssistantConfigurationKeys.DISCLOSURE_TEXT_MAXIMUM_CHARACTERS);
    }

    @Test
    @DisplayName("a disclosure is a short plain sentence, or blank to keep the platform's wording")
    void aDisclosureIsAShortPlainSentence() {
        assertThatCode(() -> validate(DISCLOSURE_RU, "")).doesNotThrowAnyException();
        assertThatCode(() -> validate(DISCLOSURE_RU, "Вам отвечает робот.\nВопрос уходит во внешний ИИ."))
                .doesNotThrowAnyException();
        assertThatCode(() -> validate(DISCLOSURE_RU, "я".repeat(500))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a disclosure over the limit, or carrying a control character, is refused where it is written")
    void anOverlongOrControlLadenDisclosureIsRefused() {
        for (String code :
                new String[] {"assistant.disclosure_text_en", DISCLOSURE_RU, "assistant.disclosure_text_uz"}) {
            assertThatThrownBy(() -> validate(code, "x".repeat(501)))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
            assertThatThrownBy(() -> validate(code, "robot\u0007"))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            failure -> assertThat(failure.errorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED));
            assertThatThrownBy(() -> validate(code, "tab\there")).isInstanceOf(ApiException.class);
        }
    }

    @Test
    @DisplayName("a key with no rule is unchanged: the assistant's ordinary keys accept what their type accepts")
    void aKeyWithNoRuleIsUnchanged() {
        assertThatCode(() -> validate("assistant.enabled", true)).doesNotThrowAnyException();
        assertThatCode(() -> validate("assistant.price_channel_code", "x".repeat(2_000)))
                .doesNotThrowAnyException();
    }
}
