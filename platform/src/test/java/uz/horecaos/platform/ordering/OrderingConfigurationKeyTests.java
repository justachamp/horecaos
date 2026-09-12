package uz.horecaos.platform.ordering;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
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

    /**
     * Wave P46 (gap map row {@code 10.3b}): settings.md §10.3 cards 2–5's
     * eleven registry entries, each declared twice for the same cyclic-import
     * reason as {@link #CART_EXPIRY_MINUTES_CODE}. One test walking the whole
     * list is what the brief calls for — "a registry drift test naming every
     * new key and its default" — rather than eleven near-identical methods.
     */
    @Test
    @DisplayName("every P46 order-policy key agrees between its two declarations and is tenant visible")
    void theOrderPolicyKeysAgreeAndAreTenantVisible() {
        List<ConfigurationKey<?>> declaredHere = List.of(
                OrderingConfigurationKeys.BUSINESS_DAY_START_HOUR,
                OrderingConfigurationKeys.AVERAGE_ORDER_MINUTES,
                OrderingConfigurationKeys.MAXIMUM_ORDER_MINUTES,
                OrderingConfigurationKeys.LATE_ORDER_THRESHOLD_MINUTES,
                OrderingConfigurationKeys.MINIMUM_ORDER_AMOUNT_MINOR,
                OrderingConfigurationKeys.VAT_RATE_PERCENT,
                OrderingConfigurationKeys.ROUTING_POLL_INTERVAL_MINUTES,
                OrderingConfigurationKeys.PREORDER_BRANCH_RESOLUTION,
                OrderingConfigurationKeys.OPERATOR_PROMO_CODE_ALLOWED,
                OrderingConfigurationKeys.AUTO_ACCEPT_ELIGIBLE_CHANNELS,
                OrderingConfigurationKeys.AUTO_ACCEPT_MIN_PRIOR_ORDERS);

        assertThat(declaredHere).hasSize(11);
        for (ConfigurationKey<?> key : declaredHere) {
            assertThat(registered(key.code()))
                    .as("registry entry for %s", key.code())
                    .isEqualTo(key);
            assertThat(key.tenantVisible())
                    .as("%s must be authorable through the tenant-facing operations surface", key.code())
                    .isTrue();
            assertThat(key.defaultValue())
                    .as("%s must declare a code default so the field never resolves to nothing", key.code())
                    .isNotNull();
        }
    }

    /** The literal defaults settings.md §10.3 names or implies, pinned so a later edit notices. */
    @Test
    @DisplayName("the order-policy defaults match what settings.md §10.3 and this wave's report name")
    void theOrderPolicyDefaultsAreThePublishedOnes() {
        assertThat(OrderingConfigurationKeys.BUSINESS_DAY_START_HOUR.defaultValue())
                .isEqualTo(6);
        assertThat(OrderingConfigurationKeys.AVERAGE_ORDER_MINUTES.defaultValue())
                .isEqualTo(30);
        assertThat(OrderingConfigurationKeys.MAXIMUM_ORDER_MINUTES.defaultValue())
                .isEqualTo(60);
        assertThat(OrderingConfigurationKeys.LATE_ORDER_THRESHOLD_MINUTES.defaultValue())
                .isEqualTo(45);
        assertThat(OrderingConfigurationKeys.MINIMUM_ORDER_AMOUNT_MINOR.defaultValue())
                .isEqualTo(0L);
        assertThat(OrderingConfigurationKeys.VAT_RATE_PERCENT.defaultValue()).isEqualByComparingTo("12");
        assertThat(OrderingConfigurationKeys.ROUTING_POLL_INTERVAL_MINUTES.defaultValue())
                .isEqualTo(2);
        assertThat(OrderingConfigurationKeys.PREORDER_BRANCH_RESOLUTION.defaultValue())
                .isEqualTo("BY_DISTANCE");
        assertThat(OrderingConfigurationKeys.OPERATOR_PROMO_CODE_ALLOWED.defaultValue())
                .isEqualTo(false);
        assertThat(OrderingConfigurationKeys.AUTO_ACCEPT_ELIGIBLE_CHANNELS.defaultValue())
                .as("unrestricted, so wiring the key changes nothing for a tenant that has not narrowed it")
                .isEqualTo("ALL");
        assertThat(OrderingConfigurationKeys.AUTO_ACCEPT_MIN_PRIOR_ORDERS.defaultValue())
                .as("no gate, preserving today's unconditional auto-accept")
                .isEqualTo(0);
    }

    private static ConfigurationKey<?> registered(String code) {
        return ConfigurationKeys.require(code);
    }
}
