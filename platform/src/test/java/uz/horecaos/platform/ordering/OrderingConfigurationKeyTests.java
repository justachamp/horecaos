package uz.horecaos.platform.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.ordering.api.OrderingConfigurationKeys;
import uz.horecaos.platform.ordering.application.CartService;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;
import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * {@code ordering.cart_expiry_minutes}, declared twice and kept identical
 * (ADR 0030).
 *
 * <p>The registry the startup validator consults lives inside the tenancy
 * module and cannot be imported from here; a reference the other way would
 * make the modules cyclic. So the key exists in both places, and this test is
 * what stops them drifting.
 */
class OrderingConfigurationKeyTests {

    @Test
    @DisplayName("the cart expiry declaration is identical on both sides")
    void theKeysAgree() {
        assertThat(registered(OrderingConfigurationKeys.CART_EXPIRY_MINUTES_CODE))
                .isEqualTo(OrderingConfigurationKeys.CART_EXPIRY_MINUTES);
    }

    @Test
    @DisplayName("the cart retention declaration is identical on both sides")
    void theRetentionKeysAgree() {
        assertThat(registered(OrderingConfigurationKeys.CART_RETENTION_DAYS_CODE))
                .isEqualTo(OrderingConfigurationKeys.CART_RETENTION_DAYS);
    }

    @Test
    @DisplayName("the retention default matches CartRetentionSweeper's own @Value default")
    void theRetentionDefaultMatchesTheSweeper() {
        // The sweeper's own @Value("${horecaos.ordering.cart-retention.days:90}")
        // and this key's declared default must agree, the same discipline
        // theDefaultMatchesTheCode below applies to the expiry key: a wired
        // key whose default disagrees with the code silently changes behaviour
        // for every tenant that has not overridden it.
        assertThat(OrderingConfigurationKeys.CART_RETENTION_DAYS.defaultValue()).isEqualTo(90);
    }

    @Test
    @DisplayName("the declared default matches what CartService actually does")
    void theDefaultMatchesTheCode() {
        // 2026-09-10's correction: an earlier draft declared 60 (one hour) while
        // CartService.CART_TTL was, and remains, four hours. Wiring the key as
        // declared would have silently cut every cart's life to a quarter of what
        // it is today for every tenant that had not overridden it.
        assertThat(OrderingConfigurationKeys.CART_EXPIRY_MINUTES.defaultValue())
                .isEqualTo((int) CartService.CART_TTL.toMinutes())
                .isEqualTo(240);
        assertThat(CartService.CART_TTL).isEqualTo(Duration.ofHours(4));
    }

    private static ConfigurationKey<?> registered(String code) {
        return ConfigurationKeys.require(code);
    }
}
