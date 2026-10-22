package uz.horecaos.platform.catalog.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.catalog.api.CatalogConfigurationKeys;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * The КБЖУ accuracy line is a switch that is off until something turns it on (ADR 0137, row 4.2c), and the
 * key that carries it is declared the same in the module and in the registry the startup validator reads.
 */
class ConfiguredNutritionNoticeSwitchTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();

    @Test
    @DisplayName("nobody turned it on: off")
    void offByDefault() {
        assertThat(new ConfiguredNutritionNoticeSwitch(new FakeConfigurationResolver()).enabled(TENANT, BRAND))
                .isFalse();
        assertThat(CatalogConfigurationKeys.NUTRITION_ACCURACY_LINE_ENABLED.defaultValue())
                .isFalse();
    }

    @Test
    @DisplayName("something in the chain says on: on")
    void onWhenConfigured() {
        FakeConfigurationResolver on = new FakeConfigurationResolver(
                Map.of(CatalogConfigurationKeys.NUTRITION_ACCURACY_LINE_ENABLED_CODE, true));

        assertThat(new ConfiguredNutritionNoticeSwitch(on).enabled(TENANT, BRAND))
                .isTrue();
    }

    @Test
    @DisplayName("the module's declaration and the registry's are the same key, settable where a brand needs it")
    void theTwoDeclarationsAgree() {
        var registry = ConfigurationKeys.require(CatalogConfigurationKeys.NUTRITION_ACCURACY_LINE_ENABLED_CODE);

        assertThat(registry).isEqualTo(CatalogConfigurationKeys.NUTRITION_ACCURACY_LINE_ENABLED);
        assertThat(registry.settableScopes()).contains(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND);
        assertThat(registry.tenantVisible())
                .as("the wording is legal's, so a tenant does not flip it on its own")
                .isFalse();
    }
}
