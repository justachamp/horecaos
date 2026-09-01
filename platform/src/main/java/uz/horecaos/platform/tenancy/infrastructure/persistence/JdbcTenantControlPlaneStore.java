package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Currency;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import uz.horecaos.platform.tenancy.api.BrandId;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.api.LocationId;
import uz.horecaos.platform.tenancy.api.TenantId;
import uz.horecaos.platform.tenancy.application.port.TenantControlPlaneStore;
import uz.horecaos.platform.tenancy.domain.Brand;
import uz.horecaos.platform.tenancy.domain.CoordinateSource;
import uz.horecaos.platform.tenancy.domain.CustomerIdentityMode;
import uz.horecaos.platform.tenancy.domain.CustomerIdentityPolicy;
import uz.horecaos.platform.tenancy.domain.Location;
import uz.horecaos.platform.tenancy.domain.LocationPlace;
import uz.horecaos.platform.tenancy.domain.OperatingUnitStatus;
import uz.horecaos.platform.tenancy.domain.Slug;
import uz.horecaos.platform.tenancy.domain.Tenant;
import uz.horecaos.platform.tenancy.domain.TenantStatus;

@Repository
public class JdbcTenantControlPlaneStore implements TenantControlPlaneStore {

    private final JdbcClient jdbc;

    public JdbcTenantControlPlaneStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean tenantSlugExists(Slug slug) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM tenant.tenants WHERE slug = :slug)")
                .param("slug", slug.value())
                .query(Boolean.class)
                .single();
    }

    @Override
    public void insertTenant(Tenant tenant) {
        jdbc.sql("""
                        INSERT INTO tenant.tenants (
                            id, slug, legal_name, display_name, default_currency,
                            default_timezone, keycloak_organization_id, status
                        ) VALUES (
                            :id, :slug, :legalName, :displayName, :currency,
                            :timezone, :organizationId, :status
                        )
                        """)
                .param("id", tenant.id().value())
                .param("slug", tenant.slug().value())
                .param("legalName", tenant.legalName())
                .param("displayName", tenant.displayName())
                .param("currency", tenant.defaultCurrency().getCurrencyCode())
                .param("timezone", tenant.defaultTimezone().getId())
                .param("organizationId", tenant.keycloakOrganizationId().orElse(null))
                .param("status", tenant.status().name())
                .update();
    }

    @Override
    public Optional<Tenant> findTenant(TenantId tenantId) {
        return jdbc.sql("""
                        SELECT id, slug, legal_name, display_name, default_currency,
                               default_timezone, keycloak_organization_id, status
                        FROM tenant.tenants
                        WHERE id = :tenantId
                        """)
                .param("tenantId", tenantId.value())
                .query(JdbcTenantControlPlaneStore::mapTenant)
                .optional();
    }

    @Override
    public Optional<Tenant> findTenantBySlug(Slug slug) {
        return jdbc.sql("""
                        SELECT id, slug, legal_name, display_name, default_currency,
                               default_timezone, keycloak_organization_id, status
                        FROM tenant.tenants
                        WHERE slug = :slug
                        """)
                .param("slug", slug.value())
                .query(JdbcTenantControlPlaneStore::mapTenant)
                .optional();
    }

    @Override
    public void linkKeycloakOrganization(Tenant tenant) {
        String organizationId = tenant.keycloakOrganizationId().orElseThrow();
        int updated = jdbc.sql("""
                        UPDATE tenant.tenants
                        SET keycloak_organization_id = :organizationId,
                            updated_at = now(),
                            version = version + 1
                        WHERE id = :tenantId
                          AND (keycloak_organization_id IS NULL
                               OR keycloak_organization_id = :organizationId)
                        """)
                .param("organizationId", organizationId)
                .param("tenantId", tenant.id().value())
                .update();
        if (updated != 1) {
            throw new OptimisticLockingFailureException("Tenant organization link changed concurrently");
        }
    }

    @Override
    public void insertCustomerIdentityPolicy(CustomerIdentityPolicy policy) {
        jdbc.sql("""
                        INSERT INTO tenant.customer_identity_policies (
                            id, tenant_id, version, identity_mode, effective_from, superseded_at
                        ) VALUES (
                            :id, :tenantId, :version, :identityMode, :effectiveFrom, :supersededAt
                        )
                        """)
                .param("id", policy.id())
                .param("tenantId", policy.tenantId().value())
                .param("version", policy.version())
                .param("identityMode", policy.mode().name())
                .param("effectiveFrom", OffsetDateTime.ofInstant(policy.effectiveFrom(), ZoneOffset.UTC))
                .param(
                        "supersededAt",
                        policy.supersededAt() == null
                                ? null
                                : OffsetDateTime.ofInstant(policy.supersededAt(), ZoneOffset.UTC),
                        Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    /**
     * Reads through {@code tenant.current_customer_identity_policy} (V0063) rather
     * than repeating its predicate.
     *
     * <p>The rule for which row of a versioned table is current was written out
     * here and again in the customers module, and both copies were wrong the same
     * way: {@code superseded_at IS NULL} with no test of {@code effective_from},
     * so a row dated for a future cutover governed from the moment it was
     * inserted. Two copies of one rule in two modules is the shape of the bug
     * V0060 fixed, so there is now one definition and it lives beside the table.
     */
    @Override
    public Optional<CustomerIdentityMode> findCurrentCustomerIdentityMode(TenantId tenantId, Instant at) {
        return jdbc.sql("""
                        SELECT identity_mode
                        FROM tenant.current_customer_identity_policy(:tenantId, :at)
                        """)
                .param("tenantId", tenantId.value())
                .param("at", OffsetDateTime.ofInstant(at, ZoneOffset.UTC))
                .query(String.class)
                .optional()
                .map(CustomerIdentityMode::valueOf);
    }

    @Override
    public boolean brandCodeOrSlugExists(TenantId tenantId, String code, Slug slug) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM tenant.brands
                            WHERE tenant_id = :tenantId AND (code = :code OR slug = :slug)
                        )
                        """)
                .param("tenantId", tenantId.value())
                .param("code", code)
                .param("slug", slug.value())
                .query(Boolean.class)
                .single();
    }

    @Override
    public void insertBrand(Brand brand) {
        jdbc.sql("""
                        INSERT INTO tenant.brands (
                            id, tenant_id, code, slug, display_name, status
                        ) VALUES (
                            :id, :tenantId, :code, :slug, :displayName, :status
                        )
                        """)
                .param("id", brand.id().value())
                .param("tenantId", brand.tenantId().value())
                .param("code", brand.code())
                .param("slug", brand.slug().value())
                .param("displayName", brand.displayName())
                .param("status", brand.status().name())
                .update();
    }

    @Override
    public Optional<Brand> findBrand(TenantId tenantId, BrandId brandId) {
        return jdbc.sql("""
                        SELECT id, tenant_id, code, slug, display_name, status
                        FROM tenant.brands
                        WHERE tenant_id = :tenantId AND id = :brandId
                        """)
                .param("tenantId", tenantId.value())
                .param("brandId", brandId.value())
                .query(JdbcTenantControlPlaneStore::mapBrand)
                .optional();
    }

    @Override
    public List<Brand> findBrands(TenantId tenantId) {
        return jdbc.sql("""
                        SELECT id, tenant_id, code, slug, display_name, status
                        FROM tenant.brands
                        WHERE tenant_id = :tenantId
                        ORDER BY display_name, id
                        """)
                .param("tenantId", tenantId.value())
                .query(JdbcTenantControlPlaneStore::mapBrand)
                .list();
    }

    @Override
    public void updateBrandStatus(Brand brand) {
        jdbc.sql("""
                        UPDATE tenant.brands
                        SET status = :status, updated_at = now(), version = version + 1
                        WHERE id = :id AND tenant_id = :tenantId
                        """)
                .param("id", brand.id().value())
                .param("tenantId", brand.tenantId().value())
                .param("status", brand.status().name())
                .update();
    }

    @Override
    public boolean locationCodeOrSlugExists(Brand brand, String code, Slug slug) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM tenant.locations
                            WHERE tenant_id = :tenantId
                              AND brand_id = :brandId
                              AND (code = :code OR slug = :slug)
                        )
                        """)
                .param("tenantId", brand.tenantId().value())
                .param("brandId", brand.id().value())
                .param("code", code)
                .param("slug", slug.value())
                .query(Boolean.class)
                .single();
    }

    @Override
    public void insertLocation(Location location) {
        jdbc.sql("""
                        INSERT INTO tenant.locations (
                            id, tenant_id, brand_id, code, slug, display_name, timezone, status,
                            latitude, longitude, coordinate_source,
                            address_line, district, city, landmark, contact_phone
                        ) VALUES (
                            :id, :tenantId, :brandId, :code, :slug, :displayName, :timezone, :status,
                            :latitude, :longitude, :coordinateSource,
                            :addressLine, :district, :city, :landmark, :contactPhone
                        )
                        """)
                .param("id", location.id().value())
                .param("tenantId", location.tenantId().value())
                .param("brandId", location.brandId().value())
                .param("code", location.code())
                .param("slug", location.slug().value())
                .param("displayName", location.displayName())
                .param("timezone", location.timezone().getId())
                .param("status", location.status().name())
                .params(placeParams(location.place()))
                .update();
    }

    /**
     * Updates only the physical facts.
     *
     * <p>Separate from the identity columns on purpose. Correcting a pin is
     * something support does mid-service on a live branch, and folding it into a
     * whole-row update would put the code, slug and status of that branch on the
     * wire every time somebody nudged a marker on a map.
     */
    @Override
    public void updateLocationPlace(Location location) {
        jdbc.sql("""
                        UPDATE tenant.locations SET
                            latitude = :latitude,
                            longitude = :longitude,
                            coordinate_source = :coordinateSource,
                            address_line = :addressLine,
                            district = :district,
                            city = :city,
                            landmark = :landmark,
                            contact_phone = :contactPhone,
                            updated_at = now()
                        WHERE id = :id AND tenant_id = :tenantId
                        """)
                .param("id", location.id().value())
                .param("tenantId", location.tenantId().value())
                .params(placeParams(location.place()))
                .update();
    }

    @Override
    public void updateLocationStatus(Location location) {
        jdbc.sql("""
                        UPDATE tenant.locations
                        SET status = :status, updated_at = now(), version = version + 1
                        WHERE id = :id AND tenant_id = :tenantId
                        """)
                .param("id", location.id().value())
                .param("tenantId", location.tenantId().value())
                .param("status", location.status().name())
                .update();
    }

    /**
     * The place columns, shared by the insert and the update so the two cannot
     * disagree about what a null means.
     */
    private static Map<String, Object> placeParams(LocationPlace place) {
        // A HashMap rather than Map.of, which rejects the nulls that a branch
        // nobody has visited yet is entirely made of.
        Map<String, Object> params = new HashMap<>();
        params.put("latitude", place.point().map(GeoPoint::latitude).orElse(null));
        params.put("longitude", place.point().map(GeoPoint::longitude).orElse(null));
        params.put("coordinateSource", place.coordinateSource().name());
        params.put("addressLine", place.addressLine());
        params.put("district", place.district());
        params.put("city", place.city());
        params.put("landmark", place.landmark());
        params.put("contactPhone", place.contactPhone());
        return params;
    }

    @Override
    public List<Location> findLocations(Brand brand) {
        return jdbc.sql("""
                        SELECT id, tenant_id, brand_id, code, slug, display_name, timezone, status,
                               latitude, longitude, coordinate_source,
                               address_line, district, city, landmark, contact_phone
                        FROM tenant.locations
                        WHERE tenant_id = :tenantId AND brand_id = :brandId
                        ORDER BY display_name, id
                        """)
                .param("tenantId", brand.tenantId().value())
                .param("brandId", brand.id().value())
                .query(JdbcTenantControlPlaneStore::mapLocation)
                .list();
    }

    private static Tenant mapTenant(ResultSet resultSet, int rowNumber) throws SQLException {
        return Tenant.reconstitute(
                new TenantId(resultSet.getObject("id", UUID.class)),
                new Slug(resultSet.getString("slug")),
                resultSet.getString("legal_name"),
                resultSet.getString("display_name"),
                Currency.getInstance(resultSet.getString("default_currency")),
                ZoneId.of(resultSet.getString("default_timezone")),
                resultSet.getString("keycloak_organization_id"),
                TenantStatus.valueOf(resultSet.getString("status")));
    }

    private static Brand mapBrand(ResultSet resultSet, int rowNumber) throws SQLException {
        return Brand.reconstitute(
                new BrandId(resultSet.getObject("id", UUID.class)),
                new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                resultSet.getString("code"),
                new Slug(resultSet.getString("slug")),
                resultSet.getString("display_name"),
                OperatingUnitStatus.valueOf(resultSet.getString("status")));
    }

    private static Location mapLocation(ResultSet resultSet, int rowNumber) throws SQLException {
        return Location.reconstitute(
                new LocationId(resultSet.getObject("id", UUID.class)),
                new TenantId(resultSet.getObject("tenant_id", UUID.class)),
                new BrandId(resultSet.getObject("brand_id", UUID.class)),
                resultSet.getString("code"),
                new Slug(resultSet.getString("slug")),
                resultSet.getString("display_name"),
                ZoneId.of(resultSet.getString("timezone")),
                OperatingUnitStatus.valueOf(resultSet.getString("status")),
                mapPlace(resultSet));
    }

    /**
     * Rebuilds the place from its columns.
     *
     * <p>The coordinate is read through {@code getObject(Double.class)} rather than
     * {@code getDouble}, which answers 0.0 for a SQL NULL — and 0.0/0.0 is a real
     * point in the Gulf of Guinea that every distance calculation would happily
     * accept. The schema forbids half a pair, so testing latitude alone is enough
     * to decide whether there is a point at all.
     */
    private static LocationPlace mapPlace(ResultSet resultSet) throws SQLException {
        Double latitude = resultSet.getObject("latitude", Double.class);
        Double longitude = resultSet.getObject("longitude", Double.class);
        return new LocationPlace(
                resultSet.getString("address_line"),
                resultSet.getString("district"),
                resultSet.getString("city"),
                resultSet.getString("landmark"),
                resultSet.getString("contact_phone"),
                latitude == null ? null : new GeoPoint(latitude, longitude),
                CoordinateSource.valueOf(resultSet.getString("coordinate_source")));
    }
}
