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
 * Writing a setting changes the answer under every scope beneath the one written, and a resolution is
 * cached under the scope that <em>asked</em> (ADR 0150: the late-order threshold a board reads at
 * location scope was written at the tenant). The real cache is filled with the keys {@link
 * JdbcConfigurationResolver#cacheKey} produces and the tests check which of them an eviction removes --
 * no database, no Spring context, the same shape as {@link PolicyCurrentCacheEvictorTests}.
 */
class ConfigurationValueCacheEvictorTests {

    private static final String THRESHOLD = "ordering.late_order_threshold_minutes";
    private static final String AT_RISK = "ordering.at_risk_before_minutes";

    private static final UUID TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac133001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac133002");
    private static final UUID OTHER_BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac133003");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac133004");
    private static final UUID SIBLING = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac133005");
    private static final UUID OTHER_BRANDS_LOCATION = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac133006");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac133101");
    private static final UUID OTHER_TENANTS_BRAND = UUID.fromString("018f6f4e-899d-7b1c-a8cf-0242ac133102");

    private static final ResourceScope TENANT_SCOPE = ResourceScope.tenant(TENANT);
    private static final ResourceScope BRAND_SCOPE = ResourceScope.brand(TENANT, BRAND);
    private static final ResourceScope OTHER_BRAND_SCOPE = ResourceScope.brand(TENANT, OTHER_BRAND);
    private static final ResourceScope LOCATION_SCOPE = ResourceScope.location(TENANT, BRAND, LOCATION);
    private static final ResourceScope SIBLING_SCOPE = ResourceScope.location(TENANT, BRAND, SIBLING);
    private static final ResourceScope OTHER_BRANDS_LOCATION_SCOPE =
            ResourceScope.location(TENANT, OTHER_BRAND, OTHER_BRANDS_LOCATION);
    private static final ResourceScope OTHER_TENANTS_SCOPE = ResourceScope.brand(OTHER_TENANT, OTHER_TENANTS_BRAND);

    // A fresh instance per test method, so each test starts with its own cache.
    private final CaffeineCacheManager manager = new CaffeineCacheManager(JdbcConfigurationResolver.CACHE_NAME);
    private final Cache cache = Objects.requireNonNull(manager.getCache(JdbcConfigurationResolver.CACHE_NAME));
    private final ConfigurationValueCacheEvictor evictor = new ConfigurationValueCacheEvictor(manager);

    @BeforeEach
    void fillTheCacheTheWayTheResolverDoes() {
        for (String code : List.of(THRESHOLD, AT_RISK)) {
            for (ResourceScope scope : List.of(
                    TENANT_SCOPE,
                    BRAND_SCOPE,
                    OTHER_BRAND_SCOPE,
                    LOCATION_SCOPE,
                    SIBLING_SCOPE,
                    OTHER_BRANDS_LOCATION_SCOPE,
                    OTHER_TENANTS_SCOPE,
                    ResourceScope.platform())) {
                cache.put(JdbcConfigurationResolver.cacheKey(code, scope), "cached");
            }
        }
    }

    @Test
    void writingAtTheTenantDropsTheTenantsOwnEntryAndEveryBrandAndLocationBeneathIt() {
        evictor.evict(THRESHOLD, TENANT_SCOPE);

        assertThat(cached(THRESHOLD, TENANT_SCOPE)).isFalse();
        assertThat(cached(THRESHOLD, BRAND_SCOPE)).isFalse();
        assertThat(cached(THRESHOLD, OTHER_BRAND_SCOPE)).isFalse();
        assertThat(cached(THRESHOLD, LOCATION_SCOPE))
                .as("a location resolved through the tenant holds the replaced value under its own key")
                .isFalse();
        assertThat(cached(THRESHOLD, SIBLING_SCOPE)).isFalse();
        assertThat(cached(THRESHOLD, OTHER_BRANDS_LOCATION_SCOPE)).isFalse();
    }

    @Test
    void anEvictionNeverReachesAnotherTenantAnotherKeyOrTheBroaderScopes() {
        evictor.evict(THRESHOLD, TENANT_SCOPE);

        assertThat(cached(THRESHOLD, OTHER_TENANTS_SCOPE))
                .as("another tenant's entry")
                .isTrue();
        assertThat(cached(THRESHOLD, ResourceScope.platform()))
                .as("the platform's own resolution does not depend on a tenant's value")
                .isTrue();
        for (ResourceScope scope : List.of(TENANT_SCOPE, BRAND_SCOPE, LOCATION_SCOPE, OTHER_TENANTS_SCOPE)) {
            assertThat(cached(AT_RISK, scope))
                    .as("another key's entry at " + scope.type())
                    .isTrue();
        }
    }

    @Test
    void writingAtABrandDropsThatBrandAndItsLocationsOnly() {
        evictor.evict(THRESHOLD, BRAND_SCOPE);

        assertThat(cached(THRESHOLD, BRAND_SCOPE)).isFalse();
        assertThat(cached(THRESHOLD, LOCATION_SCOPE)).isFalse();
        assertThat(cached(THRESHOLD, SIBLING_SCOPE)).isFalse();
        assertThat(cached(THRESHOLD, TENANT_SCOPE)).isTrue();
        assertThat(cached(THRESHOLD, OTHER_BRAND_SCOPE)).isTrue();
        assertThat(cached(THRESHOLD, OTHER_BRANDS_LOCATION_SCOPE)).isTrue();
    }

    @Test
    void writingAtALocationDropsThatLocationAlone() {
        evictor.evict(THRESHOLD, LOCATION_SCOPE);

        assertThat(cached(THRESHOLD, LOCATION_SCOPE)).isFalse();
        assertThat(cached(THRESHOLD, SIBLING_SCOPE)).isTrue();
        assertThat(cached(THRESHOLD, BRAND_SCOPE)).isTrue();
        assertThat(cached(THRESHOLD, TENANT_SCOPE)).isTrue();
    }

    @Test
    void writingAtThePlatformDropsEveryResolutionOfThatKey() {
        evictor.evict(THRESHOLD, ResourceScope.platform());

        for (ResourceScope scope :
                List.of(TENANT_SCOPE, BRAND_SCOPE, LOCATION_SCOPE, OTHER_TENANTS_SCOPE, ResourceScope.platform())) {
            assertThat(cached(THRESHOLD, scope)).as(scope.type().name()).isFalse();
            assertThat(cached(AT_RISK, scope)).as("at risk " + scope.type()).isTrue();
        }
    }

    @Test
    void anEntryWhoseKeyCannotBeReadIsDroppedRatherThanKept() {
        cache.put(THRESHOLD + "|garbage", "cached");
        cache.put(THRESHOLD + "|LOCATION:not-a-uuid:null:null", "cached");

        evictor.evict(THRESHOLD, LOCATION_SCOPE);

        assertThat(cache.get(THRESHOLD + "|garbage")).isNull();
        assertThat(cache.get(THRESHOLD + "|LOCATION:not-a-uuid:null:null")).isNull();
    }

    @Test
    void aMissingCacheIsNotAnError() {
        new ConfigurationValueCacheEvictor(new CaffeineCacheManager("some.other.cache")).evict(THRESHOLD, TENANT_SCOPE);
    }

    private boolean cached(String code, ResourceScope scope) {
        return cache.get(JdbcConfigurationResolver.cacheKey(code, scope)) != null;
    }
}
