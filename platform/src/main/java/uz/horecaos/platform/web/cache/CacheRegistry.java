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
     * The display name of a staff member, keyed by {@code (tenant, subject)}
     * (ADR 0139; Staff 9.3b).
     *
     * <p>Ten minutes, with a real invalidation source since the tenant's own
     * staff member record replaced the Keycloak read it used to cache: every
     * profile write evicts the entry once its transaction commits, so a name
     * edited on the self-service screen is the name on the audit log and the
     * order detail at the next request, not ten minutes later. The TTL is the
     * backstop, as it is for every other cache here. The key carries the tenant,
     * which the interim subject-only key did not.
     */
    STAFF_DISPLAY_NAMES(
            "staff.display_names",
            Duration.ofMinutes(10),
            20_000,
            "StaffMemberChanged (evicted on every profile write)"),

    /**
     * Geocoder answers (ADR 0145, ADR 0033), keyed by tenant, region and version, locale,
     * operation and a keyed hash of the normalized query.
     *
     * <p>Thirty days, which is the narrowest licence limit in force: Yandex's standard terms
     * let results be cached for up to thirty days, and a longer life would be a storage
     * decision the record deliberately did not make (ADR 0145 decision 5). There is no
     * invalidation event, and none is needed: a stale answer is a suggestion a person looks
     * at on a map before it becomes a pin, and editing a region changes its version, which is
     * part of the key. Only non-empty answers are held.
     *
     * <p>An entry is personal data (ADR 0029): it holds an address. It lives in process
     * memory only, under a key that carries a hash and never the query text, and is held to
     * the rules of the address it came from.
     */
    GEO_RESPONSES(
            "geo.responses",
            Duration.ofDays(30),
            20_000,
            "none: the TTL is the provider licence limit, and a region edit changes the key"),

    /**
     * The road routes the OSRM adapter has measured (ADR 0147, decision 4), keyed by
     * tenant, installation, the branch's exact point, the destination rounded to four
     * decimals (about 11 m) and the routing dataset version.
     *
     * <p>Twenty-four hours, because a road network does not change between two
     * checkouts and a repeated destination is the common case, and no event ever
     * evicts: the dataset version is part of the key, so a new image tag simply
     * misses every entry and the old ones age out. The installation is re-read from
     * the database on every call before this is consulted, so suspending it (the
     * rollback) takes effect at once rather than a TTL later.
     *
     * <p>A performance aid and never an authority. The fee row stores the metres it
     * used, not a cache key, and an entry lost to eviction or a restart costs one
     * engine call.
     */
    ROUTING_ROAD_ROUTES(
            "routing.road_routes",
            Duration.ofHours(24),
            50_000,
            "none: the dataset version is part of the key, so a new dataset misses every entry");

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
