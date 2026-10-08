package uz.horecaos.platform.kitchen;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.kitchen.api.KitchenConfigurationKeys;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * {@code kitchen.display.not_seen_after_minutes}, declared twice and kept identical (ADR 0030, ADR 0151):
 * the registry the startup validator consults lives inside the tenancy module and cannot be imported
 * from here, so this is what stops the two drifting.
 */
class KitchenConfigurationKeyTests {

    @Test
    @DisplayName("the not-seen-after declaration is identical on both sides")
    void theKeysAgree() {
        ConfigurationKey<?> registered = ConfigurationKeys.all().stream()
                .filter(key -> key.code().equals(KitchenConfigurationKeys.DISPLAY_NOT_SEEN_AFTER_MINUTES_CODE))
                .findFirst()
                .orElseThrow();

        assertThat(registered).isEqualTo(KitchenConfigurationKeys.DISPLAY_NOT_SEEN_AFTER_MINUTES);
    }

    @Test
    @DisplayName("five minutes by default, and not a control an operator tunes")
    void fiveMinutesAndNotTenantVisible() {
        assertThat(KitchenConfigurationKeys.DISPLAY_NOT_SEEN_AFTER_MINUTES.defaultValue())
                .isEqualTo(5);
        assertThat(KitchenConfigurationKeys.DISPLAY_NOT_SEEN_AFTER_MINUTES.tenantVisible())
                .isFalse();
    }
}
