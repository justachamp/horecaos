package uz.horecaos.platform.fulfillment;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.fulfillment.api.DeliveryConfigurationKeys;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * Gap map row {@code 10.13} (wave P38): {@code delivery.out_of_zone_policy},
 * declared twice and kept identical (ADR 0030), the same discipline {@code
 * NotificationConfigurationKeyTests} already applies to its own module's key.
 */
class DeliveryConfigurationKeyTests {

    @Test
    @DisplayName("the out-of-zone policy declaration is identical on both sides")
    void theOutOfZonePolicyKeysAgree() {
        assertThat(registered(DeliveryConfigurationKeys.OUT_OF_ZONE_POLICY_CODE))
                .isEqualTo(DeliveryConfigurationKeys.OUT_OF_ZONE_POLICY);
    }

    @Test
    @DisplayName("the key defaults to REJECT, matching DeliveryFeeResolver's unconditional refusal today")
    void theDefaultMatchesTodaysActualBehaviour() {
        assertThat(DeliveryConfigurationKeys.OUT_OF_ZONE_POLICY.defaultValue()).isEqualTo("REJECT");
    }

    @Test
    @DisplayName("the key is settable down to a branch and a tenant may see and set its own value")
    void theKeyIsTenantVisibleAndSettableDownToLocation() {
        assertThat(DeliveryConfigurationKeys.OUT_OF_ZONE_POLICY.tenantVisible()).isTrue();
        assertThat(DeliveryConfigurationKeys.OUT_OF_ZONE_POLICY.settableScopes())
                .contains(ScopeType.TENANT, ScopeType.BRAND, ScopeType.LOCATION);
    }

    private static ConfigurationKey<?> registered(String code) {
        return ConfigurationKeys.require(code);
    }
}
