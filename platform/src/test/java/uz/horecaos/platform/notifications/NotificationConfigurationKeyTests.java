package uz.horecaos.platform.notifications;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.notifications.api.NotificationConfigurationKeys;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * Gap map row {@code 10.9d} (wave P36): the two notification switches,
 * declared twice and kept identical (ADR 0030), the same discipline {@code
 * CourierConfigurationKeyTests} already applies to its own module's key.
 */
class NotificationConfigurationKeyTests {

    @Test
    @DisplayName("the payment-link auto-send declaration is identical on both sides")
    void thePaymentLinkAutoSendKeysAgree() {
        assertThat(registered(NotificationConfigurationKeys.PAYMENT_LINK_AUTO_SEND_CODE))
                .isEqualTo(NotificationConfigurationKeys.PAYMENT_LINK_AUTO_SEND);
    }

    @Test
    @DisplayName("the aggregator shift notification declaration is identical on both sides")
    void theAggregatorShiftKeysAgree() {
        assertThat(registered(NotificationConfigurationKeys.AGGREGATOR_SHIFT_NOTIFICATIONS_ENABLED_CODE))
                .isEqualTo(NotificationConfigurationKeys.AGGREGATOR_SHIFT_NOTIFICATIONS_ENABLED);
    }

    @Test
    @DisplayName("both switches default off and are settable down to brand, matching the settings scope bar")
    void bothSwitchesAreBrandSettable() {
        assertThat(NotificationConfigurationKeys.PAYMENT_LINK_AUTO_SEND.defaultValue())
                .isEqualTo(false);
        assertThat(NotificationConfigurationKeys.PAYMENT_LINK_AUTO_SEND.settableScopes())
                .contains(ScopeType.TENANT, ScopeType.BRAND);
        assertThat(NotificationConfigurationKeys.AGGREGATOR_SHIFT_NOTIFICATIONS_ENABLED.defaultValue())
                .isEqualTo(false);
        assertThat(NotificationConfigurationKeys.AGGREGATOR_SHIFT_NOTIFICATIONS_ENABLED.settableScopes())
                .contains(ScopeType.TENANT, ScopeType.BRAND);
    }

    private static ConfigurationKey<?> registered(String code) {
        return ConfigurationKeys.require(code);
    }
}
