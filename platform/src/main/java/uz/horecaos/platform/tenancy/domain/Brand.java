package uz.horecaos.platform.tenancy.domain;

import java.util.Objects;
import uz.horecaos.platform.tenancy.api.BrandId;
import uz.horecaos.platform.tenancy.api.TenantId;

public final class Brand {

    private final BrandId id;
    private final TenantId tenantId;
    private String code;
    private Slug slug;
    private String displayName;
    private OperatingUnitStatus status;
    private final long version;

    private Brand(
            BrandId id,
            TenantId tenantId,
            String code,
            Slug slug,
            String displayName,
            OperatingUnitStatus status,
            long version) {
        this.id = Objects.requireNonNull(id, "Brand ID is required");
        this.tenantId = Objects.requireNonNull(tenantId, "Tenant ID is required");
        this.code = normalizedCode(code);
        this.slug = Objects.requireNonNull(slug, "Brand slug is required");
        this.displayName = normalizedName(displayName);
        this.status = Objects.requireNonNull(status, "Brand status is required");
        this.version = version;
    }

    public static Brand draft(BrandId id, TenantId tenantId, String code, Slug slug, String displayName) {
        return new Brand(id, tenantId, code, slug, displayName, OperatingUnitStatus.DRAFT, 0);
    }

    /** @param version the row's stored version, which an {@code If-Match} is compared against (ADR 0031) */
    public static Brand reconstitute(
            BrandId id,
            TenantId tenantId,
            String code,
            Slug slug,
            String displayName,
            OperatingUnitStatus status,
            long version) {
        return new Brand(id, tenantId, code, slug, displayName, status, version);
    }

    /**
     * Corrects the brand's name at any time, and its code and slug while it is
     * still a draft.
     *
     * <p>The name is what people read and can always be fixed. The code and slug
     * are what things are built on once a brand has been live: the storefront is
     * addressed by the slug, and operators, exports and onboarding's own messages
     * name a brand by its code. Changing either after {@code DRAFT} would quietly
     * break whatever was built on the old one, so a live brand that needs a new
     * identity is a new brand.
     */
    public void revise(String newCode, Slug newSlug, String newDisplayName) {
        String revisedCode = normalizedCode(newCode);
        Slug revisedSlug = Objects.requireNonNull(newSlug, "Brand slug is required");
        String revisedName = normalizedName(newDisplayName);
        if (status != OperatingUnitStatus.DRAFT && (!revisedCode.equals(code) || !revisedSlug.equals(slug))) {
            throw new IllegalStateException(
                    "A brand's code and slug can only change while it is DRAFT, and this one is " + status);
        }
        code = revisedCode;
        slug = revisedSlug;
        displayName = revisedName;
    }

    /**
     * Whether the brand has never left {@code DRAFT}, the only state it can be
     * deleted from.
     *
     * <p>Deleting exists for setting a tenant up — a typo, a brand created twice.
     * A brand that has been active has orders, receipts and audit history that
     * name it, and those have to keep meaning something.
     */
    public boolean deletable() {
        return status == OperatingUnitStatus.DRAFT;
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

    /** The version this brand was read at; a write that persists it moves the stored one on. */
    public long version() {
        return version;
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
