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
     * <p>ADR 0167 marks this provisional so the ADR 0029 startup guard keeps asking, and it is (ADR 0183): 400 is the figure
     * ADR 0043 proposed, finance and counsel have not confirmed it, and the key says so with {@code provisionalRetention}.
     * It governs no personal data, so {@code ProvisionalRetentionStartupCheck} reports it at every non-local start and never
     * refuses over it. Confirming it is writing the value at {@code PLATFORM} scope (400 is a fine value to write), which
     * ADR 0030 audits and which clears the flag.
     */
    public static final ConfigurationKey<Integer> FUNNEL_RETENTION_DAYS = ConfigurationKey.of(
                    FUNNEL_RETENTION_DAYS_CODE, Integer.class)
            .defaultValue(FUNNEL_RETENTION_DAYS_DEFAULT)
            .ownedBy("reporting")
            .settableAt(ScopeType.PLATFORM)
            .provisionalRetention(false, "finance and legal counsel (ADR 0167 open input 4)")
            .describedAs("Days the customer funnel, registration and attribution-link click counts are "
                    + "kept, by business date. The rows hold counts and no personal data.")
            .build();

    /** The code both declarations of the page-load flag share. */
    public static final String PAGE_LOAD_COUNT_ENABLED_CODE = "reporting.page_load_count_enabled";

    /**
     * Whether a storefront's page-load ping is counted at all (ADR 0183 D18). A cookie-less, identifier-less count of how many
     * times a tab opened the shop, labelled «page loads, not people»: whether even that needs a lawful basis is counsel's
     * call, so it is {@code PLATFORM}-only, off, and while it is off nothing is counted and no storefront sends the ping.
     * Turning it on is the platform owner's decision after counsel answers, and it is audited as any value write is.
     */
    public static final ConfigurationKey<Boolean> PAGE_LOAD_COUNT_ENABLED = ConfigurationKey.of(
                    PAGE_LOAD_COUNT_ENABLED_CODE, Boolean.class)
            .defaultValue(false)
            .ownedBy("reporting")
            .settableAt(ScopeType.PLATFORM)
            .describedAs("Whether a storefront's page-load ping is counted into the customer funnel's visits figure "
                    + "(page loads, not people). Off until counsel answers (ADR 0183 D18).")
            .build();

    private ReportingConfigurationKeys() {}
}
