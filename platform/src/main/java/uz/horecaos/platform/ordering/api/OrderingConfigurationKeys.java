package uz.horecaos.platform.ordering.api;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The ordering module's ADR 0030 configuration keys.
 *
 * <p>How long an untouched cart stays active before expiring, wired
 * 2026-09-10 for the identical reason recorded on {@code
 * pricing.api.PricingConfigurationKeys}: the declared default (sixty minutes)
 * disagreed with {@link
 * uz.horecaos.platform.ordering.application.CartService#CART_TTL} (four
 * hours) by 4x, harmlessly while the key was dead and not harmlessly the
 * moment it was wired without correcting the default.
 *
 * <p>And, since ADR 0109 (Settings 10.11): how long an abandoned cart —
 * one that expired without becoming an order — is kept before {@code
 * CartRetentionSweeper} deletes it outright. A different question from the
 * one above: {@link #CART_EXPIRY_MINUTES} decides when a cart stops being
 * usable at checkout, this decides how long its record survives afterwards
 * for the data-privacy self-service screen's own "retention periods" gap.
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

    /** The code both declarations share. */
    public static final String CART_RETENTION_DAYS_CODE = "ordering.cart_retention_days";

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

    /**
     * Days an abandoned cart is kept before {@code CartRetentionSweeper}
     * deletes it (ADR 0092). Ninety, matching that class's own {@code
     * @Value} default exactly — the same "a wired key's default is the live
     * value" discipline {@link #CART_EXPIRY_MINUTES} follows. Settable at the
     * platform and per tenant, and only ever lengthened: {@code
     * CartRetentionSweeper} sweeps on the longer of the platform default and
     * the largest tenant-configured value, the same rule {@code
     * TrackRetentionSweeper.effectiveRetentionDays} already uses, because a
     * shorter stored value must never delete another tenant's cart early.
     */
    public static final ConfigurationKey<Integer> CART_RETENTION_DAYS = ConfigurationKey.of(
                    CART_RETENTION_DAYS_CODE, Integer.class)
            .defaultValue(90)
            .ownedBy("ordering")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("Days an abandoned cart (one that expired without becoming an order) "
                    + "is kept before it is deleted outright.")
            .build();

    private OrderingConfigurationKeys() {}
}
