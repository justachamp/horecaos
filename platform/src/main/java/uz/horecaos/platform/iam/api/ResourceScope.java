package uz.horecaos.platform.iam.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A point in the ownership hierarchy at which a configuration value or policy
 * may be set or resolved (ADR 0030).
 *
 * <p>The chain is strictly hierarchical: a location belongs to exactly one
 * brand, and a brand to exactly one tenant (ADR 0002). {@link #chain()} returns
 * the resolution order, most specific first, and is the only definition of
 * precedence in the platform: ADR 0030 uses it for configuration and policy
 * resolution, ADR 0025 uses {@link #covers} for capability scope checks.
 *
 * <p>Lives in {@code iam.api} and is expressed in raw identifiers rather than
 * tenancy value types. Tenancy already depends on {@code iam.api}, so taking a
 * dependency the other way would make the two modules cyclic.
 *
 * @param tenantId absent only for {@link ScopeType#PLATFORM}
 * @param brandId present only for {@link ScopeType#BRAND} and {@link ScopeType#LOCATION}
 * @param locationId present only for {@link ScopeType#LOCATION}
 */
public record ResourceScope(
        ScopeType type,
        @Nullable UUID tenantId,
        @Nullable UUID brandId,
        @Nullable UUID locationId) {

    public enum ScopeType {
        PLATFORM,
        TENANT,
        BRAND,
        LOCATION
    }

    public ResourceScope {
        Objects.requireNonNull(type, "Scope type is required");
        switch (type) {
            case PLATFORM -> requireAllNull(tenantId, brandId, locationId);
            case TENANT -> {
                Objects.requireNonNull(tenantId, "A tenant scope requires a tenant ID");
                requireAllNull(brandId, locationId);
            }
            case BRAND -> {
                Objects.requireNonNull(tenantId, "A brand scope requires a tenant ID");
                Objects.requireNonNull(brandId, "A brand scope requires a brand ID");
                requireAllNull(locationId);
            }
            case LOCATION -> {
                Objects.requireNonNull(tenantId, "A location scope requires a tenant ID");
                Objects.requireNonNull(brandId, "A location scope requires a brand ID");
                Objects.requireNonNull(locationId, "A location scope requires a location ID");
            }
        }
    }

    public static ResourceScope platform() {
        return new ResourceScope(ScopeType.PLATFORM, null, null, null);
    }

    public static ResourceScope tenant(UUID tenantId) {
        return new ResourceScope(ScopeType.TENANT, tenantId, null, null);
    }

    public static ResourceScope brand(UUID tenantId, UUID brandId) {
        return new ResourceScope(ScopeType.BRAND, tenantId, brandId, null);
    }

    public static ResourceScope location(UUID tenantId, UUID brandId, UUID locationId) {
        return new ResourceScope(ScopeType.LOCATION, tenantId, brandId, locationId);
    }

    /**
     * The resolution chain from this scope up to the platform, most specific
     * first. Resolution stops at the first level holding an explicit value.
     */
    public List<ResourceScope> chain() {
        List<ResourceScope> chain = new ArrayList<>(4);
        chain.add(this);
        switch (type) {
            case LOCATION -> {
                chain.add(new ResourceScope(ScopeType.BRAND, tenantId, brandId, null));
                chain.add(new ResourceScope(ScopeType.TENANT, tenantId, null, null));
                chain.add(platform());
            }
            case BRAND -> {
                chain.add(new ResourceScope(ScopeType.TENANT, tenantId, null, null));
                chain.add(platform());
            }
            case TENANT -> chain.add(platform());
            case PLATFORM -> {}
        }
        return List.copyOf(chain);
    }

    /** The identifier of this scope level, or {@code null} for the platform. */
    public @Nullable UUID scopeId() {
        return switch (type) {
            case PLATFORM -> null;
            case TENANT -> tenantId;
            case BRAND -> brandId;
            case LOCATION -> locationId;
        };
    }

    /** Whether this scope covers {@code other}: a broader scope covers narrower ones. */
    public boolean covers(ResourceScope other) {
        return other.chain().contains(this);
    }

    private static void requireAllNull(@Nullable UUID... values) {
        for (UUID value : values) {
            if (value != null) {
                throw new IllegalArgumentException("Scope identifiers below the scope type must be absent");
            }
        }
    }
}
