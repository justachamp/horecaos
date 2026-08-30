package uz.horecaos.platform.web.cache;

import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Every cache in the platform, with its TTL, invalidation source, and size
 * bound (ADR 0033).
 *
 * <p>An unregistered cache fails a startup check. Registration is what makes
 * "which caches exist and how do they go stale" answerable without reading the
 * whole codebase, and it forces each staleness budget to be a decision rather
 * than whatever the first annotation happened to say.
 *
 * <p>PostgreSQL is always the authority. Every cache here is a disposable
 * accelerator: a miss, eviction, or total outage degrades to a database read.
 */
public enum CacheRegistry {

    /** ADR 0025 grants. Short, because a revoked grant must stop working quickly. */
    IAM_GRANTS("iam.grants", Duration.ofSeconds(30), 10_000, "TenantGrantsChanged"),

    /** ADR 0030 configuration values. */
    TENANT_CONFIGURATION("tenant.configuration", Duration.ofSeconds(60), 20_000, "ConfigurationChanged"),

    /** ADR 0030 active policy versions. */
    TENANT_POLICY_CURRENT("tenant.policy_current", Duration.ofSeconds(60), 20_000, "PolicyActivated"),

    /** ADR 0021 entitlement snapshots. */
    COMMERCIAL_ENTITLEMENTS("commercial.entitlements", Duration.ofSeconds(60), 10_000,
            "TenantEntitlementsChanged"),

    /** ADR 0026 provider environments; reference data that changes on deployment. */
    INTEGRATION_ENVIRONMENTS("integration.environments", Duration.ofHours(1), 1_000, "deployment"),

    /**
     * ADR 0025 scope hierarchy: whether a brand belongs to a tenant, and a
     * location to a brand. Consulted on every capability check, so it is cached;
     * a brand's parent never changes, and a new brand simply misses.
     *
     * <p>Only positive answers are cached. A negative is a request naming a
     * hierarchy that does not exist, which is either a bug or an attempt, and
     * neither should be able to fill this map.
     */
    TENANT_HIERARCHY("tenant.hierarchy", Duration.ofMinutes(10), 50_000, "BrandCreated, LocationCreated");

    private static final Map<String, CacheRegistry> BY_NAME = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(CacheRegistry::cacheName, Function.identity()));

    private final String cacheName;
    private final Duration ttl;
    private final long maximumSize;
    private final String invalidationSource;

    CacheRegistry(String cacheName, Duration ttl, long maximumSize, String invalidationSource) {
        this.cacheName = cacheName;
        this.ttl = ttl;
        this.maximumSize = maximumSize;
        this.invalidationSource = invalidationSource;
    }

    public String cacheName() {
        return cacheName;
    }

    public Duration ttl() {
        return ttl;
    }

    public long maximumSize() {
        return maximumSize;
    }

    /**
     * The event that invalidates this cache. TTL is the backstop, so a missed
     * invalidation heals instead of persisting indefinitely.
     */
    public String invalidationSource() {
        return invalidationSource;
    }

    public static Optional<CacheRegistry> find(String cacheName) {
        return Optional.ofNullable(BY_NAME.get(cacheName.toLowerCase(Locale.ROOT)));
    }

    public static CacheRegistry require(String cacheName) {
        return find(cacheName).orElseThrow(() -> new UnregisteredCacheException(
                """
                Cache "%s" is not registered. Declare it in CacheRegistry with a TTL \
                and an invalidation source (ADR 0033).""".formatted(cacheName)));
    }

    /** Thrown when a cache is used without a registered TTL and invalidation source. */
    public static final class UnregisteredCacheException extends IllegalStateException {
        public UnregisteredCacheException(String message) {
            super(message);
        }
    }
}
