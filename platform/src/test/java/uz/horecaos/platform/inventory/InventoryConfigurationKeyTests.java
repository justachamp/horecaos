package uz.horecaos.platform.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.inventory.api.InventoryConfigurationKeys;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * {@code inventory.reservation_ttl_seconds}, declared twice and kept
 * identical (ADR 0030).
 *
 * <p>The registry the startup validator consults lives inside the tenancy
 * module and cannot be imported from here; a reference the other way would
 * make the modules cyclic. So the key exists in both places, and this test is
 * what stops them drifting.
 *
 * <p>Unlike pricing's and ordering's counterparts, this key's declared
 * default already agreed with the code before it was wired. The invariant
 * this key cannot express alone — that a reservation must never expire
 * before the quote it backs — is proven separately, against the actual
 * floor logic, by {@code ReservationExpiryTests}.
 */
class InventoryConfigurationKeyTests {

    @Test
    @DisplayName("the reservation TTL declaration is identical on both sides")
    void theKeysAgree() {
        assertThat(registered(InventoryConfigurationKeys.RESERVATION_TTL_SECONDS_CODE))
                .isEqualTo(InventoryConfigurationKeys.RESERVATION_TTL_SECONDS);
    }

    @Test
    @DisplayName("the declared default matches what InventoryService actually does")
    void theDefaultMatchesTheCode() {
        assertThat(InventoryConfigurationKeys.RESERVATION_TTL_SECONDS.defaultValue())
                .isEqualTo((int) InventoryService.RESERVATION_TTL.toSeconds())
                .isEqualTo(900);
        assertThat(InventoryService.RESERVATION_TTL).isEqualTo(Duration.ofMinutes(15));
    }

    private static ConfigurationKey<?> registered(String code) {
        return ConfigurationKeys.require(code);
    }
}
