package uz.horecaos.platform.tenancy.domain;

import java.time.ZoneId;
import java.util.Objects;

import uz.horecaos.platform.tenancy.api.BrandId;
import uz.horecaos.platform.tenancy.api.LocationId;
import uz.horecaos.platform.tenancy.api.TenantId;

public final class Location {

    private final LocationId id;
    private final TenantId tenantId;
    private final BrandId brandId;
    private final String code;
    private final Slug slug;
    private final ZoneId timezone;
    private String displayName;
    private OperatingUnitStatus status;
    private LocationPlace place;

    private Location(
            LocationId id,
            TenantId tenantId,
            BrandId brandId,
            String code,
            Slug slug,
            String displayName,
            ZoneId timezone,
            OperatingUnitStatus status,
            LocationPlace place) {
        this.id = Objects.requireNonNull(id, "Location ID is required");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant ID is required");
        this.brandId = Objects.requireNonNull(brandId, "Brand ID is required");
        this.code = Brand.normalizedCode(code);
        this.slug = Objects.requireNonNull(slug, "Location slug is required");
        this.displayName = Brand.normalizedName(displayName);
        this.timezone = Objects.requireNonNull(timezone, "Location timezone is required");
        this.status = Objects.requireNonNull(status, "Location status is required");
        this.place = Objects.requireNonNull(place, "Location place is required");
    }

    public static Location draft(
            LocationId id,
            TenantId tenantId,
            BrandId brandId,
            String code,
            Slug slug,
            String displayName,
            ZoneId timezone) {
        return new Location(
                id,
                tenantId,
                brandId,
                code,
                slug,
                displayName,
                timezone,
                OperatingUnitStatus.DRAFT,
                // A branch is registered before anyone has stood outside it. The
                // address arrives during onboarding, and until it does the gap is
                // stated rather than implied by a scatter of nulls.
                LocationPlace.unknown());
    }

    public static Location reconstitute(
            LocationId id,
            TenantId tenantId,
            BrandId brandId,
            String code,
            Slug slug,
            String displayName,
            ZoneId timezone,
            OperatingUnitStatus status,
            LocationPlace place) {
        return new Location(
                id,
                tenantId,
                brandId,
                code,
                slug,
                displayName,
                timezone,
                status,
                place);
    }

    /**
     * Records where the branch is.
     *
     * <p>Allowed in every status, including {@code ARCHIVED}. A branch that has
     * closed still has an address, and refusing the correction would leave a wrong
     * one on every historical receipt that points at it.
     */
    public void describePlace(LocationPlace described) {
        this.place = Objects.requireNonNull(described, "Location place is required");
    }

    public void activate() {
        requireStatus(OperatingUnitStatus.DRAFT, OperatingUnitStatus.SUSPENDED);
        status = OperatingUnitStatus.ACTIVE;
    }

    public void suspend() {
        requireStatus(OperatingUnitStatus.ACTIVE);
        status = OperatingUnitStatus.SUSPENDED;
    }

    public void archive() {
        requireStatus(OperatingUnitStatus.DRAFT, OperatingUnitStatus.SUSPENDED);
        status = OperatingUnitStatus.ARCHIVED;
    }

    public LocationId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
    }

    public BrandId brandId() {
        return brandId;
    }

    public String code() {
        return code;
    }

    public Slug slug() {
        return slug;
    }

    public String displayName() {
        return displayName;
    }

    public ZoneId timezone() {
        return timezone;
    }

    public OperatingUnitStatus status() {
        return status;
    }

    public LocationPlace place() {
        return place;
    }

    private void requireStatus(OperatingUnitStatus... allowed) {
        for (OperatingUnitStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException("Location cannot transition from " + status);
    }
}
