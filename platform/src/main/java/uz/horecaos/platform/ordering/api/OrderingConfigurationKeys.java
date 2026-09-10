package uz.horecaos.platform.ordering.api;

import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The ordering module's ADR 0030 configuration keys.
 *
 * <p>There is exactly one: how long an untouched cart stays active before
 * expiring. Wired 2026-09-10, for the identical reason recorded on {@code
 * pricing.api.PricingConfigurationKeys}: the declared default (sixty minutes)
 * disagreed with {@link
 * uz.horecaos.platform.ordering.application.CartService#CART_TTL} (four
 * hours) by 4x, harmlessly while the key was dead and not harmlessly the
 * moment it was wired without correcting the default.
 *
 * <p><strong>Declared twice.</strong> The registry ADR 0030's startup
 * validator consults lives in {@code tenancy.domain.configuration}, which is
 * internal to the tenancy module; importing it here is not possible and
 * importing this from there would make the two modules cyclic. The registry
 * therefore carries an identical declaration, and {@code
 * OrderingConfigurationKeyTests} fails the build if the two ever drift apart.
 */
public final class OrderingConfigurationKeys {

    /** The code both declarations share. */
    public static final String CART_EXPIRY_MINUTES_CODE = "ordering.cart_expiry_minutes";

    /**
     * Minutes an untouched cart stays active before expiring.
     *
     * <p>240 (four hours): long enough for a customer to be interrupted and
     * come back, deliberately far longer than the fifteen-minute quote TTL —
     * the cart survives an interruption, the price does not.
     */
    public static final ConfigurationKey<Integer> CART_EXPIRY_MINUTES = ConfigurationKey.of(
                    CART_EXPIRY_MINUTES_CODE, Integer.class)
            .defaultValue(240)
            .ownedBy("ordering")
            .tenantVisible()
            .describedAs("Minutes an untouched cart stays active before expiring.")
            .build();

    private OrderingConfigurationKeys() {}
}
