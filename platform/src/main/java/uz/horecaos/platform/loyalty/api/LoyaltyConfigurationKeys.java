package uz.horecaos.platform.loyalty.api;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The loyalty module's ADR 0030 configuration keys, all of them ADR 0171's (row 6.3b): the caps that hold what a
 * person has not seen, the tenant's statement that its till keeps a balance of its own, and how often the platform
 * looks at the till.
 *
 * <p><strong>Declared twice.</strong> The registry ADR 0030's startup validator consults lives in {@code
 * tenancy.domain.configuration}, which is internal to the tenancy module; importing it here is not possible and
 * importing this from there would make the two modules cyclic. The registry therefore carries an identical
 * declaration of each, and {@code LoyaltyConfigurationKeyTests} fails the build if the two ever drift apart.
 */
public final class LoyaltyConfigurationKeys {

    /** The code both declarations share. */
    public static final String POS_SYNC_MAX_AUTO_CREDIT_MINOR_CODE = "loyalty.pos_sync.max_auto_credit_minor";

    public static final String POS_SYNC_MAX_BINDING_CREDIT_MINOR_PER_DAY_CODE =
            "loyalty.pos_sync.max_binding_credit_minor_per_day";

    public static final String POS_TILL_KEEPS_BALANCE_CODE = "loyalty.pos.till_keeps_balance";

    public static final String POS_SYNC_PULL_INTERVAL_SECONDS_CODE = "loyalty.pos_sync.pull_interval_seconds";

    /** The defaults as plain numbers, for a caller that must not unbox a nullable. */
    public static final long POS_SYNC_MAX_AUTO_CREDIT_DEFAULT = 100_000L;

    public static final long POS_SYNC_MAX_BINDING_CREDIT_PER_DAY_DEFAULT = 2_000_000L;

    public static final int POS_SYNC_PULL_INTERVAL_DEFAULT_SECONDS = 300;

    /**
     * The most points one customer may be credited from the till in one business day without a person looking
     * (ADR 0171 Decision 5). 100 000: an order of magnitude above a normal day's earning on an 84 000 som order,
     * chosen to hold too much rather than too little. A till-originated credit above it is stored {@code HELD},
     * opens a discrepancy and credits nothing until someone with {@code loyalty.sync.manage} resolves it.
     */
    public static final ConfigurationKey<Long> POS_SYNC_MAX_AUTO_CREDIT_MINOR = ConfigurationKey.of(
                    POS_SYNC_MAX_AUTO_CREDIT_MINOR_CODE, Long.class)
            .defaultValue(POS_SYNC_MAX_AUTO_CREDIT_DEFAULT)
            .ownedBy("loyalty")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
            .describedAs("Points one customer may be credited from the till in one business day before the "
                    + "credit is held for review.")
            .build();

    /** The same bound for a whole binding in one business day (ADR 0171 Decision 5): 2 000 000. */
    public static final ConfigurationKey<Long> POS_SYNC_MAX_BINDING_CREDIT_MINOR_PER_DAY = ConfigurationKey.of(
                    POS_SYNC_MAX_BINDING_CREDIT_MINOR_PER_DAY_CODE, Long.class)
            .defaultValue(POS_SYNC_MAX_BINDING_CREDIT_PER_DAY_DEFAULT)
            .ownedBy("loyalty")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
            .describedAs("Points one point-of-sale binding may credit from the till in one business day before "
                    + "further credits are held for review.")
            .build();

    /**
     * The tenant tells the platform that this brand's till keeps a bonus balance of its own (ADR 0171 Decision 8).
     * With no binding that carries {@code LOYALTY_BALANCE_SYNC}, the loyalty page then says so in a standing
     * notice: "A customer's balance here and at the till can differ." Redemption is not blocked; the notice is the
     * honest statement.
     */
    public static final ConfigurationKey<Boolean> POS_TILL_KEEPS_BALANCE = ConfigurationKey.of(
                    POS_TILL_KEEPS_BALANCE_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("loyalty")
            .tenantVisible()
            .settableAt(ScopeType.BRAND)
            .describedAs("Whether this brand's point-of-sale terminal keeps a customer bonus balance of its own.")
            .build();

    /** How often the platform pulls what happened at a till (ADR 0171 Decision 3/4): 300 seconds. */
    public static final ConfigurationKey<Integer> POS_SYNC_PULL_INTERVAL_SECONDS = ConfigurationKey.of(
                    POS_SYNC_PULL_INTERVAL_SECONDS_CODE, Integer.class)
            .defaultValue(POS_SYNC_PULL_INTERVAL_DEFAULT_SECONDS)
            .ownedBy("loyalty")
            .settableAt(ScopeType.PLATFORM)
            .describedAs("Seconds between pulls of the movements that happened at a point-of-sale terminal.")
            .build();

    private LoyaltyConfigurationKeys() {}
}
