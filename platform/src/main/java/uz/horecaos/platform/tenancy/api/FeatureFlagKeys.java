package uz.horecaos.platform.tenancy.api;

import uz.horecaos.platform.tenancy.domain.configuration.ConfigurationKeys;

/**
 * The feature-flag keys (ADR 0082) that modules other than tenancy read.
 *
 * <p>A flag is declared once, in the registry ADR 0030's startup validator consults, and only
 * reachable from other modules through this facade: {@code tenancy.domain.configuration} is internal
 * to tenancy, so a module that needs the key object to resolve it asks here, and no second copy of the
 * declaration exists to drift. (A module-owned <em>policy</em> key is the other pattern -- declared
 * twice with a drift test -- because it carries a module's own default and meaning.)
 */
public final class FeatureFlagKeys {

    /**
     * ADR 0161: whether marked-goods capture is on for a tenant. Off for every tenant until one names a
     * marked SKU: while off, publishing a classification with {@code marking_required} is refused, the
     * capture endpoints answer {@code FEATURE_DISABLED} and the handover guard is skipped, so a tenant
     * with no marked goods pays nothing for any of it.
     */
    public static final ConfigurationKey<Boolean> MARKED_GOODS = ConfigurationKeys.FEATURE_MARKED_GOODS;

    private FeatureFlagKeys() {}
}
