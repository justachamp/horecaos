package uz.horecaos.platform.tenancy.domain;

import java.util.Objects;
import uz.horecaos.platform.tenancy.api.BrandId;
import uz.horecaos.platform.tenancy.api.TenantId;

public final class Brand {

    private final BrandId id;
    private final TenantId tenantId;
    private final String code;
    private final Slug slug;
    private String displayName;
    private OperatingUnitStatus status;

    private Brand(
            BrandId id, TenantId tenantId, String code, Slug slug, String displayName, OperatingUnitStatus status) {
        this.id = Objects.requireNonNull(id, "Brand ID is required");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant ID is required");
        this.code = normalizedCode(code);
        this.slug = Objects.requireNonNull(slug, "Brand slug is required");
        this.displayName = normalizedName(displayName);
        this.status = Objects.requireNonNull(status, "Brand status is required");
    }

    public static Brand draft(BrandId id, TenantId tenantId, String code, Slug slug, String displayName) {
        return new Brand(id, tenantId, code, slug, displayName, OperatingUnitStatus.DRAFT);
    }

    public static Brand reconstitute(
            BrandId id, TenantId tenantId, String code, Slug slug, String displayName, OperatingUnitStatus status) {
        return new Brand(id, tenantId, code, slug, displayName, status);
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

    public BrandId id() {
        return id;
    }

    public TenantId tenantId() {
        return tenantId;
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

    public OperatingUnitStatus status() {
        return status;
    }

    private void requireStatus(OperatingUnitStatus... allowed) {
        for (OperatingUnitStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException("Brand cannot transition from " + status);
    }

    static String normalizedCode(String value) {
        Objects.requireNonNull(value, "Code is required");
        String normalized = value.strip().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("[A-Z0-9][A-Z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("Code must contain 1-32 letters, digits, underscores, or hyphens");
        }
        return normalized;
    }

    static String normalizedName(String value) {
        Objects.requireNonNull(value, "Display name is required");
        String normalized = value.strip();
        if (normalized.isEmpty() || normalized.length() > 200) {
            throw new IllegalArgumentException("Display name must contain 1-200 characters");
        }
        return normalized;
    }
}
