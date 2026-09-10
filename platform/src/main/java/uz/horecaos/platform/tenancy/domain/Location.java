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
    private String code;
    private Slug slug;
    private ZoneId timezone;
    private String displayName;
    private OperatingUnitStatus status;
    private LocationPlace place;
    private final long version;

    private Location(
            LocationId id,
            TenantId tenantId,
            BrandId brandId,
            String code,
            Slug slug,
            String displayName,
            ZoneId timezone,
            OperatingUnitStatus status,
            LocationPlace place,
            long version) {
        this.id = Objects.requireNonNull(id, "Location ID is required");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant ID is required");
        this.brandId = Objects.requireNonNull(brandId, "Brand ID is required");
        this.code = Brand.normalizedCode(code);
        this.slug = Objects.requireNonNull(slug, "Location slug is required");
        this.displayName = Brand.normalizedName(displayName);
        this.timezone = Objects.requireNonNull(timezone, "Location timezone is required");
        this.status = Objects.requireNonNull(status, "Location status is required");
        this.place = Objects.requireNonNull(place, "Location place is required");
        this.version = version;
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
                LocationPlace.unknown(),
                0);
    }

    /** @param version the row's stored version, which an {@code If-Match} is compared against (ADR 0031) */
    public static Location reconstitute(
            LocationId id,
            TenantId tenantId,
            BrandId brandId,
            String code,
            Slug slug,
            String displayName,
            ZoneId timezone,
            OperatingUnitStatus status,
            LocationPlace place,
            long version) {
        return new Location(id, tenantId, brandId, code, slug, displayName, timezone, status, place, version);
    }

    /**
     * Corrects the branch's name at any time, and its code, slug and timezone
     * while it is still a draft.
     *
     * <p>{@link Brand#revise} gives the reason for the code and slug. The
     * timezone joins them because schedules, business days and order numbering
     * are all computed in it: moving a live branch to another zone would shift
     * every opening hour it has by the difference, and re-date its history.
     */
    public void revise(String newCode, Slug newSlug, String newDisplayName, ZoneId newTimezone) {
        String revisedCode = Brand.normalizedCode(newCode);
        Slug revisedSlug = Objects.requireNonNull(newSlug, "Location slug is required");
        String revisedName = Brand.normalizedName(newDisplayName);
        ZoneId revisedTimezone = Objects.requireNonNull(newTimezone, "Location timezone is required");
        if (status != OperatingUnitStatus.DRAFT
                && (!revisedCode.equals(code) || !revisedSlug.equals(slug) || !revisedTimezone.equals(timezone))) {
            throw new IllegalStateException(
                    "A location's code, slug and timezone can only change while it is DRAFT, and this one is "
                            + status);
        }
        code = revisedCode;
        slug = revisedSlug;
        displayName = revisedName;
        timezone = revisedTimezone;
    }

    /** Whether the branch has never left {@code DRAFT}; {@link Brand#deletable} says why that is the rule. */
    public boolean deletable() {
        return status == OperatingUnitStatus.DRAFT;
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

    /** The version this branch was read at; a write that persists it moves the stored one on. */
    public long version() {
        return version;
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
