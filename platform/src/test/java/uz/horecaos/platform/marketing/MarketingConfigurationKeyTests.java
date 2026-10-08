package uz.horecaos.platform.marketing;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.marketing.api.MarketingConfigurationKeys;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * ADR 0112's three ADR 0030 keys, declared twice and kept identical: once beside the code that
 * reads them ({@link MarketingConfigurationKeys}), once in the registry the startup validator
 * and the settings screens read ({@link ConfigurationKeys}). Importing across that boundary
 * would make {@code marketing} and {@code tenancy} cyclic, so the two declarations are held
 * together by this test instead, the discipline {@code NotificationConfigurationKeyTests}
 * applies to its own module.
 */
class MarketingConfigurationKeyTests {

    @Test
    @DisplayName("the channel priority order is declared identically on both sides")
    void theChannelPriorityKeysAgree() {
        assertThat(registered(MarketingConfigurationKeys.CHANNEL_PRIORITY_ORDER_CODE))
                .isEqualTo(MarketingConfigurationKeys.CHANNEL_PRIORITY_ORDER);
    }

    @Test
    @DisplayName("the in-app show cap is declared identically on both sides")
    void theInAppCapKeysAgree() {
        assertThat(registered(MarketingConfigurationKeys.IN_APP_SHOW_CAP_PER_DAY_CODE))
                .isEqualTo(MarketingConfigurationKeys.IN_APP_SHOW_CAP_PER_DAY);
    }

    @Test
    @DisplayName("the default control group is declared identically on both sides")
    void theControlGroupKeysAgree() {
        assertThat(registered(MarketingConfigurationKeys.SCENARIO_CONTROL_GROUP_PERCENT_DEFAULT_CODE))
                .isEqualTo(MarketingConfigurationKeys.SCENARIO_CONTROL_GROUP_PERCENT_DEFAULT);
    }

    @Test
    @DisplayName("the defaults are the ones ADR 0112 states, and each key is settable where a tenant can reach it")
    void defaultsAndScopes() {
        // No ranking by default: a tie goes to the broadcast already under way.
        assertThat(MarketingConfigurationKeys.CHANNEL_PRIORITY_ORDER.defaultValue())
                .isEqualTo("");
        assertThat(MarketingConfigurationKeys.CHANNEL_PRIORITY_ORDER.settableScopes())
                .contains(ScopeType.PLATFORM, ScopeType.TENANT);

        assertThat(MarketingConfigurationKeys.IN_APP_SHOW_CAP_PER_DAY.defaultValue())
                .isEqualTo(3);
        assertThat(MarketingConfigurationKeys.IN_APP_SHOW_CAP_PER_DAY.settableScopes())
                .contains(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND);

        assertThat(MarketingConfigurationKeys.SCENARIO_CONTROL_GROUP_PERCENT_DEFAULT.defaultValue())
                .isEqualTo(10);
        assertThat(MarketingConfigurationKeys.SCENARIO_CONTROL_GROUP_PERCENT_DEFAULT.settableScopes())
                .contains(ScopeType.PLATFORM, ScopeType.TENANT);
    }

    @Test
    @DisplayName("every key is visible to a tenant's own settings screen and owned by marketing")
    void everyKeyIsATenantsToSee() {
        for (ConfigurationKey<?> key : java.util.List.of(
                MarketingConfigurationKeys.CHANNEL_PRIORITY_ORDER,
                MarketingConfigurationKeys.IN_APP_SHOW_CAP_PER_DAY,
                MarketingConfigurationKeys.SCENARIO_CONTROL_GROUP_PERCENT_DEFAULT)) {
            assertThat(key.owningModule()).as(key.code()).isEqualTo("marketing");
            assertThat(key.tenantVisible()).as(key.code()).isTrue();
        }
    }

    private static ConfigurationKey<?> registered(String code) {
        return ConfigurationKeys.require(code);
    }
}
