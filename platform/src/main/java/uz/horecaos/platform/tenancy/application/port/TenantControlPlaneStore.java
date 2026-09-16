package uz.horecaos.platform.tenancy.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import uz.horecaos.platform.tenancy.api.BrandId;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.domain.Brand;
import uz.horecaos.platform.tenancy.domain.BrandProfile;
import uz.horecaos.platform.tenancy.domain.CustomerIdentityMode;
import uz.horecaos.platform.tenancy.domain.CustomerIdentityPolicy;
import uz.horecaos.platform.tenancy.domain.Location;
import uz.horecaos.platform.tenancy.domain.Slug;
import uz.horecaos.platform.tenancy.domain.Tenant;

public interface TenantControlPlaneStore {

    boolean tenantSlugExists(Slug slug);

    void insertTenant(Tenant tenant);

    Optional<Tenant> findTenant(TenantId tenantId);

    /**
     * A page of every tenant, oldest first, for the control-plane directory
     * (control-plane IA 2.1).
     *
     * <p>Keyset over {@code id} rather than {@code created_at}: two tenants can
     * share a creation instant at second resolution and a timestamp cursor would
     * either skip or repeat one of them, the same reasoning
     * {@code MigrationScopeController}'s own list already documents. {@code id}
     * is a random {@link java.util.UUID} rather than a time-ordered one, so this
     * is a stable page order, not a chronological one — {@code createdAt} is
     * still returned on each row for the screen to sort or display by.
     */
    List<TenantSummary> listTenants(@org.jspecify.annotations.Nullable TenantId afterTenantId, int limit);

    /**
     * One row of the control-plane tenant directory.
     *
     * <p>Deliberately narrower than {@link Tenant}: a directory page reads many
     * rows at once and has no use for {@link Tenant}'s behaviour, and the
     * directory's own honest gap — no plan, business type, country, or health
     * score column exists anywhere in this schema yet — is easiest to see when
     * the row type names only what is real.
     */
    record TenantSummary(
            TenantId id,
            Slug slug,
            String legalName,
            String displayName,
            String defaultCurrency,
            String defaultTimezone,
            uz.horecaos.platform.tenancy.domain.TenantStatus status,
            Instant createdAt,
            String countryCode,
            String businessType) {}

    /**
     * The tenant holding this slug, if any.
     *
     * <p>Slugs are the one tenant identifier a caller can know before the tenant
     * does: a provisioning tool choosing a fixed, human-legible slug (rather than
     * inventing a fresh one per run) has no id to look up by until this exists.
     * {@link #tenantSlugExists(Slug)} only ever answered "yes" or "no" — enough to
     * refuse a duplicate create, not enough for a caller to discover what the
     * duplicate already is and reconcile against it instead.
     */
    Optional<Tenant> findTenantBySlug(Slug slug);

    void linkKeycloakOrganization(Tenant tenant);

    /**
     * Persists the tenant's current status (suspend/reactivate).
     *
     * <p>Brands and locations have had this since the control plane shipped;
     * tenants did not, which is why {@code Tenant.suspend()} existed with no way
     * to reach it from production and no way to store its result.
     */
    void updateTenantStatus(Tenant tenant);

    void insertCustomerIdentityPolicy(CustomerIdentityPolicy policy);

    /**
     * The identity mode governing a tenant at an instant, or empty when the
     * tenant has configured none.
     *
     * <p>The instant is a parameter because "current" is a question about a
     * versioned table: a policy row dated for a future cutover has been recorded
     * but is not in effect. This used to match on {@code superseded_at IS NULL}
     * alone, which made every future-dated row take effect the moment it was
     * inserted. Taking the instant from the caller also keeps the answer under
     * the caller's clock rather than the database's.
     */
    Optional<CustomerIdentityMode> findCurrentCustomerIdentityMode(TenantId tenantId, Instant at);

    boolean brandCodeOrSlugExists(TenantId tenantId, String code, Slug slug);

    void insertBrand(Brand brand);

    Optional<Brand> findBrand(TenantId tenantId, BrandId brandId);

    List<Brand> findBrands(TenantId tenantId);

    /**
     * Every brand of this tenant that can sell as a result of activating —
     * onboarding readiness (ADR 0099's spirit: activation must prove the
     * tenant can take an order, not that every brand row is complete) judges
     * only these, so that a tenant whose other brand is ready is never
     * blocked by one that is not.
     *
     * <p>That set is {@code ACTIVE} brands, plus — only when the tenant has
     * never yet had any brand reach {@code ACTIVE} — its {@code DRAFT} ones
     * too. The second half is not an exception to "only {@code ACTIVE}
     * sells"; it falls out of how activation itself works: {@code
     * OnboardingService#activateDraftBrandsAndLocations} promotes every
     * {@code DRAFT} brand of the tenant in one sweep the moment activation
     * succeeds, so a brand-new tenant's very first brand — the one this
     * activation is <em>for</em> — is {@code DRAFT} at the exact moment
     * readiness is checked, simply because nothing has activated yet. Once
     * one brand has gone live, a later {@code DRAFT} brand is presumed not
     * part of what a subsequent activation is proving and is treated the
     * same as {@code SUSPENDED} or {@code ARCHIVED}: skipped, not required.
     */
    List<Brand> findActiveBrands(TenantId tenantId);

    /** Persists the brand's current status (activate/suspend/archive). */
    void updateBrandStatus(Brand brand);

    /** Whether a brand of this tenant other than {@code brand} already holds its code or slug. */
    boolean brandCodeOrSlugTakenByAnother(Brand brand);

    /**
     * Persists a revised brand's code, slug and name, provided nothing has
     * written it since it was read.
     *
     * @return false when the stored version is no longer {@link Brand#version()}
     */
    boolean updateBrandIdentity(Brand brand);

    /**
     * Deletes a brand, provided nothing has written it since it was read.
     *
     * @return false when the stored version is no longer {@link Brand#version()}
     * @throws uz.horecaos.platform.tenancy.application.OperatingUnitNotDeletableException
     *         when another record still refers to it
     */
    boolean deleteBrand(Brand brand);

    /**
     * One brand's profile (10.1, 10.12) — empty ({@link BrandProfile#empty()}
     * shaped) rather than {@code Optional}, since a brand that has configured
     * none of this yet is a normal, expected state, not an absent row.
     */
    BrandProfile findBrandProfile(TenantId tenantId, BrandId brandId);

    /**
     * Every brand's profile in one tenant, batched.
     *
     * <p>{@link #findBrands} already answers a directory in one query; reading
     * each row's profile with a separate {@link #findBrandProfile} call per
     * brand would be exactly the N+1 the branch list's own service-state read
     * is this wave's other half of closing. A brand absent from the returned
     * map has configured nothing — the caller substitutes {@link
     * BrandProfile#empty()}.
     */
    Map<BrandId, BrandProfile> findBrandProfiles(TenantId tenantId);

    /** Replaces a brand's whole profile — contact, media and its locale set together. */
    void updateBrandProfile(TenantId tenantId, BrandId brandId, BrandProfile profile);

    boolean locationCodeOrSlugExists(Brand brand, String code, Slug slug);

    void insertLocation(Location location);

    /**
     * Persists the branch's address, telephone and point.
     *
     * <p>Narrow on purpose: correcting a pin happens on a live branch mid-service,
     * and it must not carry that branch's identity columns along with it.
     */
    void updateLocationPlace(Location location);

    /** Persists the location's current status (activate/suspend/archive). */
    void updateLocationStatus(Location location);

    /** Whether a location of the same brand other than {@code location} already holds its code or slug. */
    boolean locationCodeOrSlugTakenByAnother(Location location);

    /**
     * Persists a revised location's code, slug, name and timezone, provided
     * nothing has written it since it was read. The place is not touched; see
     * {@link #updateLocationPlace}.
     *
     * @return false when the stored version is no longer {@link Location#version()}
     */
    boolean updateLocationIdentity(Location location);

    /**
     * Deletes a location, provided nothing has written it since it was read.
     *
     * @return false when the stored version is no longer {@link Location#version()}
     * @throws uz.horecaos.platform.tenancy.application.OperatingUnitNotDeletableException
     *         when another record still refers to it
     */
    boolean deleteLocation(Location location);

    List<Location> findLocations(Brand brand);
}
