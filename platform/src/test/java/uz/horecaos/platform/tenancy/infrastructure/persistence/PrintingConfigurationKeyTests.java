package uz.horecaos.platform.tenancy.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.printing.api.PrintingConfigurationKeys;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * The four keys printing declares (ADR 0154, ADR 0030, ADR 0082), declared twice and kept identical -- once in
 * the module's own {@code api} package, where its code reads them, and once in the tenancy registry, where
 * the settings screen and the validators find them -- and the one list that is written in three places (the
 * languages a document can be printed in) held together. Lives in the validators' package because the locale
 * list it compares is package-private there: a module may not reach into another's internals for it, and a
 * test may.
 */
class PrintingConfigurationKeyTests {

    @Test
    @DisplayName("each key is declared identically in the module and in the registry")
    void theKeysAgree() {
        assertThat(ConfigurationKeys.require(PrintingConfigurationKeys.FEATURE_PRINTING_CODE))
                .isEqualTo(PrintingConfigurationKeys.FEATURE_PRINTING);
        assertThat(ConfigurationKeys.require(PrintingConfigurationKeys.DOCUMENT_LOCALE_CODE))
                .isEqualTo(PrintingConfigurationKeys.DOCUMENT_LOCALE);
        assertThat(ConfigurationKeys.require(PrintingConfigurationKeys.KITCHEN_TICKET_EXPIRE_MINUTES_CODE))
                .isEqualTo(PrintingConfigurationKeys.KITCHEN_TICKET_EXPIRE_MINUTES);
        assertThat(ConfigurationKeys.require(PrintingConfigurationKeys.LATE_MARKER_MINUTES_CODE))
                .isEqualTo(PrintingConfigurationKeys.LATE_MARKER_MINUTES);
    }

    @Test
    @DisplayName("the languages a document can be printed in are the same three everywhere")
    void theLocaleListsAgree() {
        assertThat(ConfigurationValueRules.PRINT_DOCUMENT_LOCALES)
                .isEqualTo(PrintingConfigurationKeys.DOCUMENT_LOCALES)
                .containsExactly("ru", "uz-Latn", "en");
    }

    @Test
    @DisplayName("nothing prints by default: the flag is off, and the defaults are the ADR's")
    void theDefaultsAreTheDecisions() {
        assertThat(PrintingConfigurationKeys.FEATURE_PRINTING.defaultValue()).isFalse();
        assertThat(PrintingConfigurationKeys.DOCUMENT_LOCALE.defaultValue()).isEqualTo("ru");
        assertThat(PrintingConfigurationKeys.KITCHEN_TICKET_EXPIRE_MINUTES.defaultValue())
                .isEqualTo(30);
        assertThat(PrintingConfigurationKeys.LATE_MARKER_MINUTES.defaultValue()).isEqualTo(5);
    }

    @Test
    @DisplayName("the flag is switched by the platform or the tenant, never by a brand or a branch")
    void theFlagIsATenantLevelSwitch() {
        assertThat(PrintingConfigurationKeys.FEATURE_PRINTING.settableScopes())
                .containsExactlyInAnyOrder(ScopeType.PLATFORM, ScopeType.TENANT);
    }

    @Test
    @DisplayName("a language the platform does not print in is refused, a language it does is not")
    void aLanguageIsOneOfThree() {
        for (String accepted : PrintingConfigurationKeys.DOCUMENT_LOCALES) {
            ConfigurationValueRules.validate(PrintingConfigurationKeys.DOCUMENT_LOCALE, accepted);
        }

        assertThatThrownBy(() -> ConfigurationValueRules.validate(PrintingConfigurationKeys.DOCUMENT_LOCALE, "de"))
                .hasMessageContaining("printing.document_locale");
        assertThatThrownBy(() -> ConfigurationValueRules.validate(PrintingConfigurationKeys.DOCUMENT_LOCALE, "RU"))
                .as("the tags are exact")
                .hasMessageContaining("printing.document_locale");
    }

    @Test
    @DisplayName("a kitchen ticket's expiry is five minutes to four hours, at both ends")
    void theExpiryIsBounded() {
        ConfigurationValueRules.validate(PrintingConfigurationKeys.KITCHEN_TICKET_EXPIRE_MINUTES, 5);
        ConfigurationValueRules.validate(PrintingConfigurationKeys.KITCHEN_TICKET_EXPIRE_MINUTES, 240);

        assertThatThrownBy(() ->
                        ConfigurationValueRules.validate(PrintingConfigurationKeys.KITCHEN_TICKET_EXPIRE_MINUTES, 4))
                .hasMessageContaining("printing.kitchen_ticket.expire_minutes");
        assertThatThrownBy(() ->
                        ConfigurationValueRules.validate(PrintingConfigurationKeys.KITCHEN_TICKET_EXPIRE_MINUTES, 241))
                .hasMessageContaining("printing.kitchen_ticket.expire_minutes");
    }

    @Test
    @DisplayName("the delayed marker is one minute to a day, at both ends")
    void theLateMarkerIsBounded() {
        ConfigurationValueRules.validate(PrintingConfigurationKeys.LATE_MARKER_MINUTES, 1);
        ConfigurationValueRules.validate(PrintingConfigurationKeys.LATE_MARKER_MINUTES, 1440);

        assertThatThrownBy(() -> ConfigurationValueRules.validate(PrintingConfigurationKeys.LATE_MARKER_MINUTES, 0))
                .hasMessageContaining("printing.late_marker_minutes");
        assertThatThrownBy(() -> ConfigurationValueRules.validate(PrintingConfigurationKeys.LATE_MARKER_MINUTES, 1441))
                .hasMessageContaining("printing.late_marker_minutes");
    }
}
