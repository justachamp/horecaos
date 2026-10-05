package uz.horecaos.platform.inventory.api;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The inventory module's ADR 0030 configuration keys.
 *
 * <p>{@link #RESERVATION_TTL_SECONDS} was the first: how long a stock
 * reservation is held before expiry. Wired 2026-09-10. Unlike {@code pricing.api.PricingConfigurationKeys}
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

    /** The code both declarations share (gap map row {@code 4.4d}, wave P46). */
    public static final String CATALOG_USE_STOCK_LOGIC_CODE = "catalog.use_stock_logic";

    /**
     * Whether the tenant tracks counted stock ({@link
     * uz.horecaos.platform.inventory.api.TrackingMode#QUANTITY}) rather than
     * only the binary available/sold-out state — settings.md's catalog base
     * settings, "turn quantity tracking on for the whole company in one
     * place" rather than per variant per location, which is unusable on a
     * six-hundred-item catalogue.
     *
     * <p><strong>Off by default, and real (batch 11, gap map row 4.4c).</strong>
     * {@code InventoryService#evaluateAvailability} gates every {@code
     * QUANTITY} item's enforcement on this flag: off, a {@code QUANTITY}
     * item behaves exactly like {@code UNTRACKED} — quantities are recorded
     * but never block a hold; on, {@code on_hand}/{@code reserved} are
     * enforced for real. A variant may be listed {@code QUANTITY} either
     * way, so an operator can reconcile counts before turning the switch on
     * for the whole tenant.
     */
    public static final ConfigurationKey<Boolean> CATALOG_USE_STOCK_LOGIC = ConfigurationKey.of(
                    CATALOG_USE_STOCK_LOGIC_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("inventory")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("Turns counted-stock (QUANTITY) tracking on for the whole tenant. Off, a "
                    + "QUANTITY-listed item behaves like UNTRACKED.")
            .build();

    /** The code both declarations share (ADR 0141, rollback switch 1). */
    public static final String STOPS_CREATION_ENABLED_CODE = "inventory.stops.creation_enabled";

    /**
     * ADR 0141, rollback switch one: <em>freeze, do not disable</em>.
     *
     * <p>Off, a new {@code OPERATOR} or {@code BOT} stop answers {@code 409
     * RESOURCE_CONFLICT} with {@code conflict: STOPS_FROZEN} and the console says
     * scope stops are paused. Everything else continues: lifts, expiry, the
     * resolver's reads — so every stop already made stays in force on every channel
     * and nothing is sold again — and the POS poll keeps writing and ending its own
     * {@code POS} stops, because after the POS source moved onto stops they are the
     * only record of a POS stop. A {@code BINARY} item is still stoppable through
     * the position toggle exactly as before stops existed; an {@code UNTRACKED} or
     * {@code QUANTITY} item is not, as before.
     *
     * <p>On by default. The switch that can sell a stopped dish again
     * ({@code inventory.stops.read_enabled}) is a decommission and is deliberately
     * not here.
     */
    public static final ConfigurationKey<Boolean> STOPS_CREATION_ENABLED = ConfigurationKey.of(
                    STOPS_CREATION_ENABLED_CODE, Boolean.class)
            .defaultValue(true)
            .ownedBy("inventory")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("Whether a new operator or bot stop may be created. Off freezes new "
                    + "stops (STOPS_FROZEN); stops already in force, lifts, expiry and POS stops continue.")
            .build();

    /** The code both declarations share (ADR 0141, rollback switch 3). */
    public static final String STOPS_READ_ENABLED_CODE = "inventory.stops.read_enabled";

    /**
     * ADR 0141, rollback switch three: <em>stop consulting stops</em> -- a decommission and not a
     * rollback.
     *
     * <p>Off, the resolver, the stop list's overlay and every other reader ignore the stops of the
     * brand, so a stopped dish sells again unless a position says otherwise. That is why the write
     * is refused (409 {@code MATERIALISATION_REQUIRED}) until a materialisation run has written
     * every stop it can onto positions and the report of what it could not carry was acknowledged
     * by a holder of {@code inventory.stop.manage} at brand scope; a stop created after the run
     * re-blocks it. Rows are never deleted: with the switch off the active stops stay, ignored, and
     * turning it back on resumes them. While it is off a new operator or bot stop is refused as
     * frozen (an ignored stop would only mislead), and the POS poll goes back to writing the
     * position boolean.
     *
     * <p>On by default, platform and tenant scope, and not tenant-visible.
     */
    public static final ConfigurationKey<Boolean> STOPS_READ_ENABLED = ConfigurationKey.of(
                    STOPS_READ_ENABLED_CODE, Boolean.class)
            .defaultValue(true)
            .ownedBy("inventory")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("Whether stops are consulted at all. Turning it off is a decommission: it sells "
                    + "every stopped dish again that a materialisation run could not land on a position, and "
                    + "is refused (MATERIALISATION_REQUIRED) until the run's report was acknowledged.")
            .build();

    private InventoryConfigurationKeys() {}
}
