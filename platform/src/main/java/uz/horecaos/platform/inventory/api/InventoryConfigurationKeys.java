package uz.horecaos.platform.inventory.api;

import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The inventory module's ADR 0030 configuration keys.
 *
 * <p>There is exactly one: how long a stock reservation is held before
 * expiry. Wired 2026-09-10. Unlike {@code pricing.api.PricingConfigurationKeys}
 * and {@code ordering.api.OrderingConfigurationKeys}, the declared default
 * (900 seconds) already agreed with {@link
 * uz.horecaos.platform.inventory.application.InventoryService#RESERVATION_TTL}
 * — but agreement on the default is not the whole story. {@code
 * InventoryService.RESERVATION_TTL}'s own doc records that it is deliberately
 * coupled to the pricing module's quote TTL, each citing the other, so a hold
 * never outlives the price it was taken for. Making both independently
 * settable — this key at any scope, {@code pricing.quote_ttl_seconds} at any
 * scope, possibly different scopes, possibly changed at different times —
 * would let an operator configure a reservation shorter than the quote it
 * backs, and stock would release while the price was still acceptable at
 * checkout: overselling.
 *
 * <p>That is why {@link
 * uz.horecaos.platform.inventory.application.InventoryService#reserveForQuote}
 * does not resolve this key in isolation and trust it against a separately
 * resolved quote TTL — two independent resolutions of two different keys,
 * possibly at different times, cannot be trusted to agree. It resolves this
 * key for the configured floor and then never returns an expiry earlier than
 * the specific quote's own stored {@code expiresAt}, passed in by the caller
 * that already holds it ({@code uz.horecaos.platform.inventory.domain.ReservationExpiry}).
 * That is a fact about one live quote, not a second config resolution that
 * could disagree with the first.
 *
 * <p><strong>Declared twice.</strong> The registry ADR 0030's startup
 * validator consults lives in {@code tenancy.domain.configuration}, which is
 * internal to the tenancy module; importing it here is not possible and
 * importing this from there would make the two modules cyclic. The registry
 * therefore carries an identical declaration, and {@code
 * InventoryConfigurationKeyTests} fails the build if the two ever drift
 * apart.
 */
public final class InventoryConfigurationKeys {

    /** The code both declarations share. */
    public static final String RESERVATION_TTL_SECONDS_CODE = "inventory.reservation_ttl_seconds";

    /**
     * Seconds an inventory reservation is held before expiry.
     *
     * <p>900 (fifteen minutes) — the floor a reservation is taken for, never
     * the ceiling: see this class's own doc for why a reservation may still be
     * held longer, against a specific quote's own expiry, than this value
     * alone would produce.
     */
    public static final ConfigurationKey<Integer> RESERVATION_TTL_SECONDS = ConfigurationKey.of(
                    RESERVATION_TTL_SECONDS_CODE, Integer.class)
            .defaultValue(900)
            .ownedBy("inventory")
            .describedAs("Seconds an inventory reservation is held before expiry.")
            .build();

    private InventoryConfigurationKeys() {}
}
