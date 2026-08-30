package uz.qoida.platform.commercial.api;

import uz.qoida.platform.iam.api.ResourceScope.ScopeType;
import uz.qoida.platform.tenancy.api.ConfigurationKey;

/**
 * The commercial module's ADR 0030 configuration keys.
 *
 * <p>There is exactly one, and it is the switch that decides whether any of this
 * module's enforcement has an effect. It is a configuration value rather than a
 * commercial column because that is precisely what it is: a scoped setting with
 * a platform default, a per-tenant exception, and an existing resolver that
 * already handles precedence, explicit nulls, and explanation. ADR 0030 exists
 * so that this kind of value does not get its own bespoke table, and an
 * entitlement ceiling is not special enough to be the exception.
 *
 * <p><strong>Declared twice.</strong> The registry ADR 0030's startup validator
 * consults lives in {@code tenancy.domain.configuration}, which is internal to
 * the tenancy module; importing it here is not possible and importing this from
 * there would make the two modules cyclic. The registry therefore carries an
 * identical declaration, and {@code EnforcementCeilingKeyTests} fails the build
 * if the two ever drift apart.
 */
public final class CommercialConfigurationKeys {

    /** The code both declarations share. */
    public static final String ENFORCEMENT_CEILING_CODE = "commercial.enforcement_ceiling";

    /**
     * The strongest enforcement any entitlement may reach for this scope.
     *
     * <p>The platform default is {@code METER_ONLY} and it is deliberately not
     * configurable to anything stronger by accident: raising it is a deliberate,
     * audited, per-tenant act, taken after the meter has produced evidence that
     * nothing would have been refused unfairly. Rolling back is setting it to
     * {@code METER_ONLY} again, which changes behaviour without rewriting a
     * single historical usage row.
     */
    public static final ConfigurationKey<String> ENFORCEMENT_CEILING =
            ConfigurationKey.of(ENFORCEMENT_CEILING_CODE, String.class)
                    .defaultValue(EnforcementMode.METER_ONLY.name())
                    .ownedBy("commercial")
                    .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
                    .describedAs("The strongest enforcement mode entitlement checks may apply for this tenant. "
                            + "METER_ONLY measures and refuses nothing.")
                    .build();

    private CommercialConfigurationKeys() {
    }
}
