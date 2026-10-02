package uz.horecaos.platform.pricing.api;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
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

    /** The code both declarations share (gap map row {@code 4.4d}, wave w7). */
    public static final String CATALOG_QR_KIOSK_PRICE_PLANE_CODE = "catalog.qr_kiosk_price_plane";

    /**
     * Whether a QR or kiosk channel with no price plane of its own takes the
     * tenant's hall (POS) channel's prices automatically.
     *
     * <p>Off by default. {@code QuoteService} and {@code PriceQueryService}
     * resolve it at {@code TENANT} scope and, when on, substitute the
     * tenant's single active POS channel for a QR_TABLE or KIOSK channel
     * whose own {@code price_plane_channel_id} is unset — never for a channel
     * an operator already pointed somewhere by hand, and never when the
     * tenant has zero or several POS channels, since neither state names an
     * unambiguous hall.
     *
     * <p><strong>Declared twice.</strong> The registry ADR 0030's startup
     * validator consults lives in {@code tenancy.domain.configuration},
     * which is internal to the tenancy module; importing it here is not
     * possible and importing this from there would make the two modules
     * cyclic. The registry therefore carries an identical declaration, and
     * {@code PricingConfigurationKeyTests} fails the build if the two ever
     * drift apart — the same arrangement {@code
     * inventory.api.InventoryConfigurationKeys#CATALOG_USE_STOCK_LOGIC} uses.
     */
    public static final ConfigurationKey<Boolean> CATALOG_QR_KIOSK_PRICE_PLANE = ConfigurationKey.of(
                    CATALOG_QR_KIOSK_PRICE_PLANE_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("pricing")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("QR and kiosk channels with no price plane of their own take the tenant's "
                    + "single hall (POS) channel's prices automatically. A channel with a manual "
                    + "override, or a tenant with zero or several POS channels, is unaffected.")
            .build();

    /**
     * ADR 0140: the percentage, in basis points, above which activating a promotion
     * needs a second person (ADR 0018 asks for four-eyes "above configured risk
     * thresholds"). 3000 is 30%: provisional, finance to confirm.
     */
    public static final ConfigurationKey<Integer> PROMOTION_APPROVAL_PERCENTAGE_OVER_BP = ConfigurationKey.of(
                    "pricing.promotion.approval.percentage_over_bp", Integer.class)
            .defaultValue(3000)
            .ownedBy("pricing")
            .tenantVisible()
            .describedAs("Activating a promotion whose largest percentage exceeds this many basis points "
                    + "needs a second signature (ADR 0027).")
            .build();

    /**
     * ADR 0140: the fixed amount, in minor units, above which activating a promotion
     * needs a second person. 100 000 (som): provisional, finance to confirm.
     */
    public static final ConfigurationKey<Long> PROMOTION_APPROVAL_AMOUNT_OVER_MINOR = ConfigurationKey.of(
                    "pricing.promotion.approval.amount_over_minor", Long.class)
            .defaultValue(100000L)
            .ownedBy("pricing")
            .tenantVisible()
            .describedAs("Activating a promotion whose largest fixed amount exceeds this many minor units "
                    + "needs a second signature (ADR 0027).")
            .build();

    /**
     * ADR 0140: whether activating a markup always needs a second person. A markup
     * changes what customers pay rather than what they are given; on by default.
     */
    public static final ConfigurationKey<Boolean> PROMOTION_APPROVAL_ALWAYS_FOR_MARKUP = ConfigurationKey.of(
                    "pricing.promotion.approval.always_for_markup", Boolean.class)
            .defaultValue(true)
            .ownedBy("pricing")
            .tenantVisible()
            .describedAs("Activating a markup always needs a second signature (ADR 0027).")
            .build();

    /** ADR 0140: the most lines a simulated cart may hold; the simulator is a read and must stay cheap. */
    public static final ConfigurationKey<Integer> PROMOTION_SIMULATE_MAX_LINES = ConfigurationKey.of(
                    "pricing.promotion.simulate.max_lines", Integer.class)
            .defaultValue(50)
            .ownedBy("pricing")
            .tenantVisible()
            .describedAs("The most lines a promotion simulation may price.")
            .build();

    private PricingConfigurationKeys() {}
}
