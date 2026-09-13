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

    /*
     * There is deliberately no COMMERCIAL_ENTITLEMENTS entry here.
     *
     * ADR 0033's own Decision text names "entitlement snapshots" among the
     * in-process caches it expected, but ADR 0021 -- deciding independently,
     * and already built -- went the other way and says so in its own
     * "What was built, and where it departs from the text above": "There
     * is no entitlement cache and therefore no invalidation event.
     * Resolution is one indexed read per tenant... until a measured request
     * path needs it, a plan change that is not yet visible is a support
     * ticket bought for nothing." EntitlementQueryService reads PostgreSQL on
     * every call for exactly that reason. Registering a cache neither ADR's
     * implementation wires is how a registry entry outlives the decision
     * that justified it; removing it is what keeps this one honest instead
     * of aspirational.
     */

    /*
     * There is deliberately no INTEGRATION_ENVIRONMENTS entry, for the same
     * reason there is no COMMERCIAL_ENTITLEMENTS one: it described a cache that
     * should not exist. Its invalidation source was "deployment", which the
     * application cannot perform — nothing here can notice a provider's base URL
     * change and evict — and a base URL held for an hour with no way to drop it
     * turned a secret rotation into a 422 the first time it was wired. See
     * JdbcProviderEnvironmentLookup's own doc.
     */

    /**
     * ADR 0025 scope hierarchy: whether a brand belongs to a tenant, and a
     * location to a brand. Consulted on every capability check, so it is cached;
     * a brand's parent never changes, and a new brand simply misses.
     *
     * <p>Only positive answers are cached. A negative is a request naming a
     * hierarchy that does not exist, which is either a bug or an attempt, and
     * neither should be able to fill this map.
     */
    TENANT_HIERARCHY("tenant.hierarchy", Duration.ofMinutes(10), 50_000, "BrandCreated, LocationCreated"),

    /**
     * Whether a tenant is suspended, consulted on every capability check
     * alongside {@link #IAM_GRANTS} and given the same thirty seconds for the
     * same reason: a suspension that is recorded but not yet in force is a
     * window in which whatever prompted it is still going on. The writer evicts,
     * so the TTL is the backstop rather than the mechanism.
     */
    TENANT_STATUS("tenant.status", Duration.ofSeconds(30), 10_000, "TenantSuspended, TenantReactivated"),

    /**
     * Staff 9.3b's actor-display resolution: a name for a Keycloak subject id,
     * read live from the admin API on a miss. Ten minutes because a name
     * changes rarely and every miss is a network round trip to Keycloak, not a
     * database read — this is the one cache in the registry with no domain
     * event to invalidate it, so the TTL is the whole mechanism rather than a
     * backstop on top of one.
     */
    STAFF_DISPLAY_NAMES("staff.display_names", Duration.ofMinutes(10), 20_000, "TTL only (no name-change event yet)");

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
        return find(cacheName).orElseThrow(() -> new UnregisteredCacheException("""
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
