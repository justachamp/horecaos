package uz.horecaos.platform.integration.api;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The marketplace availability reconciler's ADR 0030 keys (ADR 0141).
 *
 * <p>Declared twice: the registry the startup validator consults lives in {@code
 * tenancy.domain.configuration}, internal to the tenancy module, and a reference the other
 * way would make the two modules cyclic. {@code MarketplaceConfigurationKeyTests} fails the
 * build if the declarations drift.
 */
public final class MarketplaceConfigurationKeys {

    public static final String RECONCILE_ENABLED_CODE = "marketplace.availability.reconcile_enabled";
    public static final String RESYNC_INTERVAL_SECONDS_CODE = "marketplace.availability.resync_interval_seconds";
    public static final String STALE_AFTER_SECONDS_CODE = "marketplace.availability.stale_after_seconds";

    /**
     * ADR 0141, rollback switch two: <em>suspend the reconciler</em>. Off, no call reaches any
     * partner, every affected channel shows {@code MANUAL} with "not propagated automatically",
     * and the resolver is unaffected — the platform still refuses the dish on every channel it
     * owns. On resume, every row of the binding has its confirmation withdrawn first, so the
     * resumption resends everything once (the partner portal may have been edited by hand in
     * the meantime; the stop-list banner tells the operator to do exactly that).
     */
    public static final ConfigurationKey<Boolean> RECONCILE_ENABLED = ConfigurationKey.of(
                    RECONCILE_ENABLED_CODE, Boolean.class)
            .defaultValue(true)
            .ownedBy("integration")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("Whether the platform pushes stop-list changes to connected marketplaces. Off "
                    + "suspends every push; resuming resends every item once.")
            .build();

    /**
     * How often every mapped item of every active marketplace binding is recomputed in full
     * through the resolver — the guarantee, where the dirty markers are only an accelerator
     * (ADR 0141 Decision 7). Jittered per binding by the reconciler. Five minutes by default.
     */
    public static final ConfigurationKey<Integer> RESYNC_INTERVAL_SECONDS = ConfigurationKey.of(
                    RESYNC_INTERVAL_SECONDS_CODE, Integer.class)
            .defaultValue(300)
            .ownedBy("integration")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("Seconds between full recomputations of every mapped marketplace item. The "
                    + "guarantee that a marketplace converges on the platform's current answer.")
            .build();

    /**
     * How long a marketplace binding's outbound pushes may go unconfirmed before the binding is
     * treated as stale (ADR 0040's liveness watermark): the console says so, and on recovery
     * every item is resent once.
     */
    public static final ConfigurationKey<Integer> STALE_AFTER_SECONDS = ConfigurationKey.of(
                    STALE_AFTER_SECONDS_CODE, Integer.class)
            .defaultValue(1800)
            .ownedBy("integration")
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("Seconds a marketplace binding may go without a confirmed availability push "
                    + "before it is treated as stale.")
            .build();

    private MarketplaceConfigurationKeys() {}
}
