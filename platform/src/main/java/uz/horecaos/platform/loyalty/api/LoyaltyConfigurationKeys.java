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

    public static final String POS_SYNC_PUSH_INTERVAL_SECONDS_CODE = "loyalty.pos_sync.push_interval_seconds";

    public static final String POS_SYNC_REQUIRE_CONSENT_CODE = "loyalty.pos_sync.require_consent";

    /** The consent purpose the dark gate asks {@code ConsentDirectory} about (ADR 0171, open input 4). */
    public static final String POS_SYNC_CONSENT_PURPOSE = "loyalty.pos_sync";

    /** The defaults as plain numbers, for a caller that must not unbox a nullable. */
    public static final long POS_SYNC_MAX_AUTO_CREDIT_DEFAULT = 100_000L;

    public static final long POS_SYNC_MAX_BINDING_CREDIT_PER_DAY_DEFAULT = 2_000_000L;

    public static final int POS_SYNC_PULL_INTERVAL_DEFAULT_SECONDS = 300;

    /**
     * Half the 60 seconds ADR 0171 promises a platform movement: the push sweep is asked every ten seconds and sends what has
     * moved when 30 have passed since its last pass, so a movement reaches the till in under 60 with a pass's own running
     * time to spare.
     */
    public static final int POS_SYNC_PUSH_INTERVAL_DEFAULT_SECONDS = 30;

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

    /**
     * How often the platform sends a till the numbers that moved on the platform's side (ADR 0171 Decision 3 and the accepted
     * convergence window: a platform movement reaches the till within 60 seconds): 30 seconds. A sweep of its own, not a
     * step of the pull pass, which runs every five minutes.
     */
    public static final ConfigurationKey<Integer> POS_SYNC_PUSH_INTERVAL_SECONDS = ConfigurationKey.of(
                    POS_SYNC_PUSH_INTERVAL_SECONDS_CODE, Integer.class)
            .defaultValue(POS_SYNC_PUSH_INTERVAL_DEFAULT_SECONDS)
            .ownedBy("loyalty")
            .settableAt(ScopeType.PLATFORM)
            .describedAs(
                    "Seconds between sends to a point-of-sale terminal of the balances that moved on the platform.")
            .build();

    /**
     * Whether syncing a customer's balance with the tenant's own till needs the customer's consent (ADR 0171, open input 4).
     * Off: counsel's accepted default is that it does not, since no customer data is sent to the till. On: a customer whose
     * consent for {@link #POS_SYNC_CONSENT_PURPOSE} is absent or withheld is not matched and appears in the reconciliation
     * report as {@code NOT_CONSENTED}; one already paired is suspended on the next pass, whether the key was off when they
     * were paired or they withdraw later, and syncs again when they consent or the key is turned off. Built dark so that a
     * "yes" from counsel is a configuration flip and not a release.
     */
    public static final ConfigurationKey<Boolean> POS_SYNC_REQUIRE_CONSENT = ConfigurationKey.of(
                    POS_SYNC_REQUIRE_CONSENT_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("loyalty")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT, ScopeType.BRAND)
            .describedAs("Whether a customer must have consented before their balance is synced with the point-of-sale "
                    + "till.")
            .build();

    private LoyaltyConfigurationKeys() {}
}
