package uz.horecaos.platform.support;

import java.time.Clock;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.commercial.api.UsageMeter;
import uz.horecaos.platform.commercial.application.EnforcementCeiling;
import uz.horecaos.platform.commercial.application.EntitlementQueryService;
import uz.horecaos.platform.commercial.application.UsageMeteringService;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcModuleStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcPlanStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcSubscriptionStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcUsageStore;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationResolver;

/**
 * The real ADR 0021 {@link EntitlementService} and {@link UsageMeter}, wired
 * against a test's own PostgreSQL handle, for suites outside {@code commercial}
 * that gained a dependency on one or both ports and have no interest in
 * commercial policy — {@code CatalogAuthoringService}'s new constructor
 * parameters, for instance.
 *
 * <p>Deliberately the production classes and not a stub. A tenant with no plan
 * and no subscription — every tenant these unrelated suites create — resolves
 * every key to its code-owned safe default: unlimited, meter-only, and
 * {@link EntitlementService#require} guaranteed not to throw (see {@code
 * EntitlementKey}'s own invariant that a catalogue default can never refuse).
 * A hand-written stub would have to reimplement that guarantee to be trusted;
 * the real resolver already carries it, exercised elsewhere in {@code
 * CommercialPlatformTests} and {@code DigestEntitlementGateTests}, which wire
 * the same four classes inline. This factors that wiring out for the suites
 * that only need it to compile and behave, not to assert against.
 */
public final class CommercialDefaults {

    private CommercialDefaults() {}

    /** Entitlements and usage metering, both backed by {@code jdbc}. */
    public static Wired wire(JdbcClient jdbc, Clock clock) {
        JdbcPlanStore plans = new JdbcPlanStore(jdbc);
        JdbcSubscriptionStore subscriptions = new JdbcSubscriptionStore(jdbc);
        JdbcUsageStore usage = new JdbcUsageStore(jdbc, JsonMapper.builder().build());
        EnforcementCeiling ceiling = new EnforcementCeiling(new JdbcConfigurationResolver(jdbc));
        EntitlementQueryService entitlements =
                new EntitlementQueryService(subscriptions, plans, usage, new JdbcModuleStore(jdbc), ceiling, clock);
        UsageMeteringService metering = new UsageMeteringService(usage, entitlements, clock);
        return new Wired(entitlements, metering);
    }

    /**
     * What {@link #wire} built.
     *
     * @param entitlements typed as the port; the concrete class also satisfies it
     */
    public record Wired(EntitlementService entitlements, UsageMeter usage) {}
}
