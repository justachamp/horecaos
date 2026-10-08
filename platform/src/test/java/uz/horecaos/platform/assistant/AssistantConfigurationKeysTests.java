package uz.horecaos.platform.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.assistant.api.AssistantConfigurationKeys;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * The assistant's keys are declared twice -- in the module that consumes them and
 * in the tenancy registry the startup validator reads -- and must stay one key
 * (ADR 0030), the arrangement {@code CustomerConfigurationKeysTests} holds for
 * the customers module.
 */
class AssistantConfigurationKeysTests {

    private static void sameKey(ConfigurationKey<?> registered, ConfigurationKey<?> used) {
        assertThat(registered.valueType()).isEqualTo(used.valueType());
        assertThat(registered.defaultValue()).isEqualTo(used.defaultValue());
        assertThat(registered.settableScopes()).isEqualTo(used.settableScopes());
        assertThat(registered.owningModule()).isEqualTo(used.owningModule());
        assertThat(registered.tenantVisible()).isEqualTo(used.tenantVisible());
        assertThat(registered.explicitNullTerminates()).isEqualTo(used.explicitNullTerminates());
    }

    @Test
    void theRegistryAndTheAssistantModuleDeclareTheSameSevenKeys() {
        sameKey(ConfigurationKeys.require(AssistantConfigurationKeys.ENABLED_CODE), AssistantConfigurationKeys.ENABLED);
        sameKey(
                ConfigurationKeys.require(AssistantConfigurationKeys.MONTHLY_SPEND_CEILING_USD_CENTS_CODE),
                AssistantConfigurationKeys.MONTHLY_SPEND_CEILING_USD_CENTS);
        sameKey(
                ConfigurationKeys.require(AssistantConfigurationKeys.CONVERSATION_TURN_CAP_CODE),
                AssistantConfigurationKeys.CONVERSATION_TURN_CAP);
        sameKey(
                ConfigurationKeys.require(AssistantConfigurationKeys.PRICE_CHANNEL_CODE_CODE),
                AssistantConfigurationKeys.PRICE_CHANNEL_CODE);
        sameKey(
                ConfigurationKeys.require(AssistantConfigurationKeys.DISCLOSURE_TEXT_EN_CODE),
                AssistantConfigurationKeys.DISCLOSURE_TEXT_EN);
        sameKey(
                ConfigurationKeys.require(AssistantConfigurationKeys.DISCLOSURE_TEXT_RU_CODE),
                AssistantConfigurationKeys.DISCLOSURE_TEXT_RU);
        sameKey(
                ConfigurationKeys.require(AssistantConfigurationKeys.DISCLOSURE_TEXT_UZ_CODE),
                AssistantConfigurationKeys.DISCLOSURE_TEXT_UZ);
    }

    @Test
    @DisplayName(
            "the disclosure is the tenant's to word: visible to it, settable down to a brand, blank by default, and one key per reply language")
    void theDisclosureIsTheTenantsToWord() {
        for (String locale : new String[] {"en", "ru", "uz"}) {
            var key = AssistantConfigurationKeys.disclosureKeyFor(locale);
            assertThat(key.code()).isEqualTo("assistant.disclosure_text_" + locale);
            assertThat(key.tenantVisible()).isTrue();
            assertThat(key.defaultValue()).isEqualTo("");
            assertThat(key.settableScopes())
                    .containsExactlyInAnyOrder(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND);
        }
        assertThat(AssistantConfigurationKeys.disclosureKeyFor("de"))
                .isSameAs(AssistantConfigurationKeys.DISCLOSURE_TEXT_EN);
    }

    @Test
    @DisplayName(
            "the assistant ships off: the switch defaults to false, so no tenant is answered until one is turned on")
    void theSwitchDefaultsOff() {
        assertThat(AssistantConfigurationKeys.ENABLED.defaultValue()).isFalse();
        assertThat(AssistantConfigurationKeys.ENABLED.settableScopes())
                .containsExactlyInAnyOrder(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND);
    }

    @Test
    @DisplayName(
            "the spend ceiling is the platform's to set: it is not visible to a tenant, and not settable below the tenant")
    void theCeilingIsThePlatformsToSet() {
        assertThat(AssistantConfigurationKeys.MONTHLY_SPEND_CEILING_USD_CENTS.tenantVisible())
                .isFalse();
        assertThat(AssistantConfigurationKeys.MONTHLY_SPEND_CEILING_USD_CENTS.settableScopes())
                .containsExactlyInAnyOrder(ScopeType.PLATFORM, ScopeType.TENANT);
        assertThat(AssistantConfigurationKeys.MONTHLY_SPEND_CEILING_USD_CENTS.defaultValue())
                .isEqualTo(2_500L);
    }

    @Test
    @DisplayName("the price channel defaults to the one ADR 0075's bot builds carts on")
    void thePriceChannelIsTheOneTheBotOrdersOn() {
        assertThat(AssistantConfigurationKeys.PRICE_CHANNEL_CODE.defaultValue()).isEqualTo("STOREFRONT");
    }
}
