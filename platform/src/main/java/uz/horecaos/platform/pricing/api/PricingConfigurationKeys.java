package uz.horecaos.platform.pricing.api;

import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The pricing module's ADR 0030 configuration keys.
 *
 * <p>There is exactly one: how long a quote stays acceptable at checkout.
 * Wired 2026-09-10 — the key passed ADR 0030's startup validator from the
 * day the registry declared it, but nothing resolved it, and a
 * repository-wide search found {@link
 * uz.horecaos.platform.pricing.application.QuoteService#QUOTE_TTL} hardcoded
 * to fifteen minutes while the declared default said five. That 3x
 * disagreement was inert only because the key was dead; wiring it without
 * correcting the default would have silently cut every quote's life by two
 * thirds for every tenant that had not overridden it.
 *
 * <p><strong>Declared twice.</strong> The registry ADR 0030's startup
 * validator consults lives in {@code tenancy.domain.configuration}, which is
 * internal to the tenancy module; importing it here is not possible and
 * importing this from there would make the two modules cyclic. The registry
 * therefore carries an identical declaration, and {@code
 * PricingConfigurationKeyTests} fails the build if the two ever drift apart —
 * the same arrangement ADR 0021's enforcement ceiling already uses.
 *
 * <p><strong>Not the last word on a reservation's own expiry.</strong> This
 * key sets how long a quote itself lives; a quote taken at checkout also
 * holds inventory (ADR 0017), and that hold must never expire first,
 * or stock releases while the price is still acceptable — overselling. That
 * invariant is enforced in {@code InventoryService.reserveForQuote} against
 * the specific quote's own stored {@code expiresAt}, not by cross-reading
 * this key from the inventory module and trusting the two to still agree.
 */
public final class PricingConfigurationKeys {

    /** The code both declarations share. */
    public static final String QUOTE_TTL_SECONDS_CODE = "pricing.quote_ttl_seconds";

    /**
     * Seconds a pricing quote stays acceptable at checkout.
     *
     * <p>900 (fifteen minutes): long enough to finish a checkout, short enough
     * that a sold-out item or a price change is caught before payment rather
     * than after.
     */
    public static final ConfigurationKey<Integer> QUOTE_TTL_SECONDS = ConfigurationKey.of(
                    QUOTE_TTL_SECONDS_CODE, Integer.class)
            .defaultValue(900)
            .ownedBy("pricing")
            .describedAs("Seconds a pricing quote stays acceptable at checkout.")
            .build();

    private PricingConfigurationKeys() {}
}
