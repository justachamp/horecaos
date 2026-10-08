package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import uz.horecaos.platform.iam.api.ResourceScope;

/**
 * Drops what {@link JdbcConfigurationResolver#resolve} has cached for a key once a value of it is
 * written (ADR 0033's {@code tenant.configuration}).
 *
 * <p><strong>The scope and everything beneath it, not the one scope.</strong> The same reasoning as
 * {@link PolicyCurrentCacheEvictor}, and the same defect it fixed for policies: a resolution is cached
 * under the scope that <em>asked</em>, and a location that inherits the tenant's value holds it under
 * its own key. Writing at the tenant changes the answer for every brand and location under it, so
 * evicting only the tenant's own key left each of them answering the value just replaced until the
 * sixty-second TTL ran out. For a setting a board reads (the late-order threshold, ADR 0150, and the
 * at-risk window and late colour beside it) that is a screen drawing the old line for a minute after an
 * operator saved the new one. A cached key is recognised by the scope it encodes ({@link
 * JdbcConfigurationResolver#cacheKey}); one that cannot be read is dropped too, because a wasted miss
 * costs one query and a kept stale answer costs a wrong decision.
 *
 * <p>Other tenants' and other keys' entries are untouched. Not a Spring bean: {@link
 * JdbcConfigurationResolver} owns one, so there is a single {@code ConfigurationValueCache} to inject.
 */
final class ConfigurationValueCacheEvictor {

    private final CacheManager cacheManager;

    ConfigurationValueCacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    void evict(String keyCode, ResourceScope scope) {
        Cache cache = cacheManager.getCache(JdbcConfigurationResolver.CACHE_NAME);
        if (cache == null) {
            return;
        }
        if (!(cache instanceof CaffeineCache caffeine)) {
            // No view of the keys to pick from: dropping all of them is always safe.
            cache.clear();
            return;
        }
        String prefix = keyCode + JdbcConfigurationResolver.KEY_SEPARATOR;
        List<Object> reached = new ArrayList<>();
        for (Object cachedKey : caffeine.getNativeCache().asMap().keySet()) {
            if (cachedKey instanceof String text
                    && text.startsWith(prefix)
                    && PolicyCurrentCacheEvictor.reaches(scope, text.substring(prefix.length()))) {
                reached.add(cachedKey);
            }
        }
        caffeine.getNativeCache().invalidateAll(reached);
    }
}
