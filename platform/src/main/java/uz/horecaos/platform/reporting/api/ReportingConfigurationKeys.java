package uz.horecaos.platform.reporting.api;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The reporting module's ADR 0030 configuration keys.
 *
 * <p><strong>Declared twice.</strong> The registry ADR 0030's startup validator consults lives in
 * {@code tenancy.domain.configuration}, which is internal to the tenancy module; importing it here is
 * not possible and importing this from there would make the two modules cyclic. The registry therefore
 * carries an identical declaration, and {@code ReportingConfigurationKeyTests} fails the build if the
 * two ever drift apart.
 */
public final class ReportingConfigurationKeys {

    /** The code both declarations share. */
    public static final String FUNNEL_RETENTION_DAYS_CODE = "reporting.funnel_retention_days";

    /** The default, as a plain int for a caller that must not unbox a nullable. */
    public static final int FUNNEL_RETENTION_DAYS_DEFAULT = 400;

    /**
     * Days the funnel, registration and link-click series rows are kept, by business date (ADR 0167
     * Decision 7). 400: a year-over-year comparison plus a month, the figure ADR 0043 proposed for
     * behavioural history. The rows hold counts and no personal data, so the period is a judgement
     * about usefulness and not a legal minimum -- which is why it is settable at the platform only:
     * a tenant lengthening it would not be buying a right to hold anything.
     *
     * <p>ADR 0167 marks this "provisional so the ADR 0029 startup guard keeps asking". This build has
     * no provisional-retention register to mark it in (the guard exists for courier tracks alone), so
     * the flag is not set here; see the record's implementation status.
     */
    public static final ConfigurationKey<Integer> FUNNEL_RETENTION_DAYS = ConfigurationKey.of(
                    FUNNEL_RETENTION_DAYS_CODE, Integer.class)
            .defaultValue(FUNNEL_RETENTION_DAYS_DEFAULT)
            .ownedBy("reporting")
            .settableAt(ScopeType.PLATFORM)
            .describedAs("Days the customer funnel, registration and attribution-link click counts are "
                    + "kept, by business date. The rows hold counts and no personal data.")
            .build();

    private ReportingConfigurationKeys() {}
}
