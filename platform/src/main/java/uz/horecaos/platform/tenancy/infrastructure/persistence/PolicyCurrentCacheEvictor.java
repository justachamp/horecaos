package uz.horecaos.platform.tenancy.infrastructure.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.tenancy.application.port.PolicyCurrentCache;

/**
 * Drops what {@link JdbcPolicyResolver#resolve} has cached for a policy once a new version of it is
 * published (ADR 0033's {@code tenant.policy_current}).
 *
 * <p><strong>The scope and everything beneath it, not the one scope.</strong> A resolution is cached
 * under the scope that <em>asked</em>, and a location that inherits the tenant's document holds it
 * under its own key. Publishing at the tenant changes the answer for every brand and location under
 * it, so evicting only the tenant's own key (all that {@code @CacheEvict} on the resolver could do)
 * left each of them answering the version just replaced until the sixty-second TTL ran out: the boards
 * and the late-only filter drew the old numbers, and a policy editor opened at such a location showed
 * the old document beside the table's current version. A cached key is recognised by the scope it
 * encodes ({@link JdbcPolicyResolver#cacheKey}); one that cannot be read is dropped too, because a
 * wasted miss costs one query and a kept stale answer costs a wrong decision.
 *
 * <p>Other tenants' and other policies' entries are untouched.
 */
@Component
public class PolicyCurrentCacheEvictor implements PolicyCurrentCache {

    private final CacheManager cacheManager;

    public PolicyCurrentCacheEvictor(CacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public void evict(String keyCode, ResourceScope scope) {
        Cache cache = cacheManager.getCache(JdbcPolicyResolver.CACHE_NAME);
        if (cache == null) {
            return;
        }
        if (!(cache instanceof CaffeineCache caffeine)) {
            // No view of the keys to pick from: dropping all of them is always safe.
            cache.clear();
            return;
        }
        String prefix = keyCode + JdbcPolicyResolver.KEY_SEPARATOR;
        List<Object> reached = new ArrayList<>();
        for (Object cachedKey : caffeine.getNativeCache().asMap().keySet()) {
            if (cachedKey instanceof String text
                    && text.startsWith(prefix)
                    && reaches(scope, text.substring(prefix.length()))) {
                reached.add(cachedKey);
            }
        }
        caffeine.getNativeCache().invalidateAll(reached);
    }

    /** Whether a publication at {@code published} changes what the scope encoded in {@code scopeKey} resolves. */
    static boolean reaches(ResourceScope published, String scopeKey) {
        String[] parts = scopeKey.split(":", -1);
        if (parts.length != 4) {
            return true;
        }
        try {
            ResourceScope asked =
                    new ResourceScope(ScopeType.valueOf(parts[0]), idOf(parts[1]), idOf(parts[2]), idOf(parts[3]));
            return published.covers(asked);
        } catch (RuntimeException unreadable) {
            return true;
        }
    }

    private static @Nullable UUID idOf(String text) {
        return "null".equals(text) ? null : UUID.fromString(text);
    }
}
