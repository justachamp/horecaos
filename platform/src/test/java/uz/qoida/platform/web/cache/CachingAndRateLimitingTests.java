package uz.qoida.platform.web.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** ADR 0033: registered caches, and rate limits the edge cannot express. */
class CachingAndRateLimitingTests {

    @ParameterizedTest
    @EnumSource(CacheRegistry.class)
    void everyCacheDeclaresATtlSizeBoundAndInvalidationSource(CacheRegistry cache) {
        assertThat(cache.ttl())
                .as("a cache without a TTL cannot heal from a missed invalidation")
                .isPositive();
        assertThat(cache.maximumSize()).isPositive();
        assertThat(cache.invalidationSource()).isNotBlank();
    }

    @Test
    void authorizationCachesStaleLessThanConfigurationCaches() {
        assertThat(CacheRegistry.IAM_GRANTS.ttl())
                .as("a revoked grant must stop working faster than a changed setting propagates")
                .isLessThanOrEqualTo(CacheRegistry.TENANT_CONFIGURATION.ttl());
    }

    @Test
    void anUnregisteredCacheIsRejected() {
        assertThatThrownBy(() -> CacheRegistry.require("someone.invented.this"))
                .isInstanceOf(CacheRegistry.UnregisteredCacheException.class)
                .hasMessageContaining("ADR 0033");
    }

    @Test
    void cacheNamesAreUnique() {
        assertThat(Arrays.stream(CacheRegistry.values()).map(CacheRegistry::cacheName).toList())
                .doesNotHaveDuplicates();
    }

    @Test
    void aRequestWithinBudgetIsAllowed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-20T10:00:00Z"));
        RateLimiter limiter = new InProcessRateLimiter(clock);

        assertThat(limiter.check(key(), RateLimiter.Policy.perMinute(5)).allowed()).isTrue();
    }

    @Test
    void exceedingTheBudgetIsDeniedWithARetryAfter() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-20T10:00:00Z"));
        RateLimiter limiter = new InProcessRateLimiter(clock);
        RateLimiter.Policy policy = RateLimiter.Policy.perMinute(3);

        for (int attempt = 0; attempt < 3; attempt++) {
            assertThat(limiter.check(key(), policy).allowed()).isTrue();
        }

        RateLimiter.Decision denied = limiter.check(key(), policy);
        assertThat(denied.allowed()).isFalse();
        assertThat(denied.retryAfter())
                .as("ADR 0031 requires Retry-After on a 429")
                .isPositive();
    }

    @Test
    void theBudgetRefillsOverTime() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-20T10:00:00Z"));
        RateLimiter limiter = new InProcessRateLimiter(clock);
        RateLimiter.Policy policy = RateLimiter.Policy.perMinute(2);

        limiter.check(key(), policy);
        limiter.check(key(), policy);
        assertThat(limiter.check(key(), policy).allowed()).isFalse();

        clock.advance(Duration.ofMinutes(1));

        assertThat(limiter.check(key(), policy).allowed()).isTrue();
    }

    @Test
    void oneTenantCannotConsumeAnothersBudget() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-20T10:00:00Z"));
        RateLimiter limiter = new InProcessRateLimiter(clock);
        RateLimiter.Policy policy = RateLimiter.Policy.perMinute(1);

        RateLimiter.Key tenantA = new RateLimiter.Key("onboarding.create", "tenant-a", "user-1");
        RateLimiter.Key tenantB = new RateLimiter.Key("onboarding.create", "tenant-b", "user-2");

        assertThat(limiter.check(tenantA, policy).allowed()).isTrue();
        assertThat(limiter.check(tenantA, policy).allowed()).isFalse();
        assertThat(limiter.check(tenantB, policy).allowed())
                .as("the edge cannot express a per-tenant budget, which is why this limiter exists")
                .isTrue();
    }

    @Test
    void separateOperationsHaveSeparateBudgets() {
        MutableClock clock = new MutableClock(Instant.parse("2026-08-20T10:00:00Z"));
        RateLimiter limiter = new InProcessRateLimiter(clock);
        RateLimiter.Policy policy = RateLimiter.Policy.perMinute(1);

        limiter.check(new RateLimiter.Key("onboarding.create", "tenant-a", "user-1"), policy);

        assertThat(limiter.check(new RateLimiter.Key("checkout", "tenant-a", "user-1"), policy).allowed())
                .isTrue();
    }

    @Test
    void expensiveWritesCanFailClosed() {
        assertThat(RateLimiter.Policy.strictPerMinute(5).failOpen())
                .as("the cost of being wrong differs between a read and a refund, so the choice is per limit")
                .isFalse();
        assertThat(RateLimiter.Policy.perMinute(5).failOpen()).isTrue();
    }

    private static RateLimiter.Key key() {
        return new RateLimiter.Key("onboarding.create", "tenant-a", "user-1");
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
