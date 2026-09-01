package uz.horecaos.platform.tenancy.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import uz.horecaos.platform.tenancy.api.BrandId;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.domain.Brand;
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

    /** Persists the brand's current status (activate/suspend/archive). */
    void updateBrandStatus(Brand brand);

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

    List<Location> findLocations(Brand brand);
}
