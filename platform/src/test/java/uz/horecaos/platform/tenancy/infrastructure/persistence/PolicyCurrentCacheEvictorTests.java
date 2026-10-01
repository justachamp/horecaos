package uz.horecaos.platform.tenancy.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * Publishing a policy changes the answer under every scope beneath the one published, and a resolution
 * is cached under the scope that <em>asked</em>. These tests fill the real cache with the keys {@link
 * JdbcPolicyResolver#cacheKey} produces and check which of them an eviction removes -- no database, no
 * Spring context: the property is in which keys go, not in how they were filled (the cache-integration
 * test proves the keys {@code @Cacheable} writes are these).
 */
class PolicyCurrentCacheEvictorTests {

    private static final String LATENESS = "ordering.lateness";
    private static final String ACCEPTANCE = "ordering.acceptance";

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac132001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac132002");
    private static final UUID OTHER_BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac132003");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac132004");
    private static final UUID SIBLING = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac132005");
    private static final UUID OTHER_BRANDS_LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac132006");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac132101");
    private static final UUID OTHER_TENANTS_BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac132102");

    private static final ResourceScope TENANT_SCOPE = ResourceScope.tenant(TENANT);
    private static final ResourceScope BRAND_SCOPE = ResourceScope.brand(TENANT, BRAND);
    private static final ResourceScope OTHER_BRAND_SCOPE = ResourceScope.brand(TENANT, OTHER_BRAND);
    private static final ResourceScope LOCATION_SCOPE = ResourceScope.location(TENANT, BRAND, LOCATION);
    private static final ResourceScope SIBLING_SCOPE = ResourceScope.location(TENANT, BRAND, SIBLING);
    private static final ResourceScope OTHER_BRANDS_LOCATION_SCOPE =
            ResourceScope.location(TENANT, OTHER_BRAND, OTHER_BRANDS_LOCATION);
    private static final ResourceScope OTHER_TENANTS_SCOPE = ResourceScope.brand(OTHER_TENANT, OTHER_TENANTS_BRAND);

    // A fresh instance per test method, so each test starts with its own cache.
    private final CaffeineCacheManager manager = new CaffeineCacheManager(JdbcPolicyResolver.CACHE_NAME);
    private final Cache cache = Objects.requireNonNull(manager.getCache(JdbcPolicyResolver.CACHE_NAME));
    private final PolicyCurrentCacheEvictor evictor = new PolicyCurrentCacheEvictor(manager);

    @BeforeEach
    void fillTheCacheTheWayTheResolverDoes() {
        for (String code : List.of(LATENESS, ACCEPTANCE)) {
            for (ResourceScope scope : List.of(
                    TENANT_SCOPE,
                    BRAND_SCOPE,
                    OTHER_BRAND_SCOPE,
                    LOCATION_SCOPE,
                    SIBLING_SCOPE,
                    OTHER_BRANDS_LOCATION_SCOPE,
                    OTHER_TENANTS_SCOPE,
                    ResourceScope.platform())) {
                cache.put(JdbcPolicyResolver.cacheKey(code, scope), "cached");
            }
        }
    }

    @Test
    void publishingAtTheTenantDropsTheTenantsOwnEntryAndEveryBrandAndLocationBeneathIt() {
        evictor.evict(LATENESS, TENANT_SCOPE);

        assertThat(cached(LATENESS, TENANT_SCOPE)).isFalse();
        assertThat(cached(LATENESS, BRAND_SCOPE)).isFalse();
        assertThat(cached(LATENESS, OTHER_BRAND_SCOPE)).isFalse();
        assertThat(cached(LATENESS, LOCATION_SCOPE))
                .as("a location resolved through the tenant holds the replaced document under its own key")
                .isFalse();
        assertThat(cached(LATENESS, SIBLING_SCOPE)).isFalse();
        assertThat(cached(LATENESS, OTHER_BRANDS_LOCATION_SCOPE)).isFalse();
    }

    @Test
    void anEvictionNeverReachesAnotherTenantAnotherPolicyOrTheBroaderScopes() {
        evictor.evict(LATENESS, TENANT_SCOPE);

        assertThat(cached(LATENESS, OTHER_TENANTS_SCOPE))
                .as("another tenant's entry")
                .isTrue();
        assertThat(cached(LATENESS, ResourceScope.platform()))
                .as("the platform's own resolution does not depend on a tenant's document")
                .isTrue();
        for (ResourceScope scope : List.of(TENANT_SCOPE, BRAND_SCOPE, LOCATION_SCOPE, OTHER_TENANTS_SCOPE)) {
            assertThat(cached(ACCEPTANCE, scope))
                    .as("another policy's entry at " + scope.type())
                    .isTrue();
        }
    }

    @Test
    void publishingAtABrandDropsThatBrandAndItsLocationsOnly() {
        evictor.evict(LATENESS, BRAND_SCOPE);

        assertThat(cached(LATENESS, BRAND_SCOPE)).isFalse();
        assertThat(cached(LATENESS, LOCATION_SCOPE)).isFalse();
        assertThat(cached(LATENESS, SIBLING_SCOPE)).isFalse();
        assertThat(cached(LATENESS, TENANT_SCOPE)).isTrue();
        assertThat(cached(LATENESS, OTHER_BRAND_SCOPE)).isTrue();
        assertThat(cached(LATENESS, OTHER_BRANDS_LOCATION_SCOPE)).isTrue();
    }

    @Test
    void publishingAtALocationDropsThatLocationAlone() {
        evictor.evict(LATENESS, LOCATION_SCOPE);

        assertThat(cached(LATENESS, LOCATION_SCOPE)).isFalse();
        assertThat(cached(LATENESS, SIBLING_SCOPE)).isTrue();
        assertThat(cached(LATENESS, BRAND_SCOPE)).isTrue();
        assertThat(cached(LATENESS, TENANT_SCOPE)).isTrue();
    }

    @Test
    void publishingAtThePlatformDropsEveryResolutionOfThatPolicy() {
        evictor.evict(LATENESS, ResourceScope.platform());

        for (ResourceScope scope :
                List.of(TENANT_SCOPE, BRAND_SCOPE, LOCATION_SCOPE, OTHER_TENANTS_SCOPE, ResourceScope.platform())) {
            assertThat(cached(LATENESS, scope)).as(scope.type().name()).isFalse();
            assertThat(cached(ACCEPTANCE, scope))
                    .as("acceptance " + scope.type())
                    .isTrue();
        }
    }

    @Test
    void anEntryWhoseKeyCannotBeReadIsDroppedRatherThanKept() {
        cache.put(LATENESS + "|garbage", "cached");
        cache.put(LATENESS + "|LOCATION:not-a-uuid:null:null", "cached");

        evictor.evict(LATENESS, LOCATION_SCOPE);

        assertThat(cache.get(LATENESS + "|garbage")).isNull();
        assertThat(cache.get(LATENESS + "|LOCATION:not-a-uuid:null:null")).isNull();
    }

    @Test
    void aMissingCacheIsNotAnError() {
        new PolicyCurrentCacheEvictor(new CaffeineCacheManager("some.other.cache")).evict(LATENESS, TENANT_SCOPE);
    }

    private boolean cached(String code, ResourceScope scope) {
        return cache.get(JdbcPolicyResolver.cacheKey(code, scope)) != null;
    }
}
