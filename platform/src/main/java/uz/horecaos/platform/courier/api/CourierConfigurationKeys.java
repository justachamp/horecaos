package uz.horecaos.platform.courier.api;

import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.api.ConfigurationKey;

/**
 * The courier module's ADR 0030 configuration keys.
 *
 * <p>There is exactly one so far: months an unverified courier applicant's
 * engagement is kept before {@code CourierApplicantRetentionSweeper} erases
 * their name and archives the application (ADR 0092). Wired 2026-09-12 for
 * Settings 10.11 (ADR 0109): before this key existed, the only place this
 * number lived was a {@code @Value}-injected application property, identical
 * for every tenant with no self-service surface anywhere — the "only surface
 * is PLATFORM-scoped" gap the data-privacy screen named, except here there
 * was no surface at all, platform or tenant.
 *
 * <p><strong>Declared twice.</strong> The registry ADR 0030's startup
 * validator consults lives in {@code tenancy.domain.configuration}, which is
 * internal to the tenancy module; importing it here is not possible and
 * importing this from there would make the two modules cyclic. The registry
 * therefore carries an identical declaration, and {@code
 * CourierConfigurationKeyTests} fails the build if the two ever drift apart.
 */
public final class CourierConfigurationKeys {

    /** The code both declarations share. */
    public static final String APPLICANT_RETENTION_MONTHS_CODE = "courier.applicant_retention_months";

    /**
     * Months an unverified courier applicant's engagement is kept before
     * their name is overwritten and the application archived. Twelve,
     * matching {@code CourierApplicantRetentionSweeper}'s own {@code @Value}
     * default exactly. Settable at the platform and per tenant, and only
     * ever lengthened by {@code CourierApplicantRetentionSweeper}, which
     * sweeps on the longer of the platform default and the largest
     * tenant-configured value — the same rule {@code
     * TrackRetentionSweeper.effectiveRetentionDays} already uses for courier
     * location tracks, so a shorter stored value can never erase another
     * tenant's applicant early.
     */
    public static final ConfigurationKey<Integer> APPLICANT_RETENTION_MONTHS = ConfigurationKey.of(
                    APPLICANT_RETENTION_MONTHS_CODE, Integer.class)
            .defaultValue(12)
            .ownedBy("courier")
            .tenantVisible()
            .settableAt(ScopeType.PLATFORM, ScopeType.TENANT)
            .describedAs("Months an unverified courier applicant's engagement is kept before "
                    + "their name is erased and the application archived.")
            .build();

    private CourierConfigurationKeys() {}
}
