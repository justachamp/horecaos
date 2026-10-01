package uz.horecaos.platform.iam.application.staff;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uz.horecaos.platform.web.cache.CacheRegistry;

/**
 * The in-process {@code staff.display_names} cache, keyed by {@code (tenant,
 * subject)} (ADR 0139, ADR 0033).
 *
 * <p>The interim cache was keyed by subject alone and had no tenant in its key,
 * which is the structural reason a name could be handed to the wrong tenant's
 * screen. The key here carries both, so a subject in two tenants has two
 * entries that never see each other.
 *
 * <p>What it holds is a person's name in process memory, for at most ten
 * minutes, and it is evicted on every profile write. That is the accepted
 * trade-off the ADR records: names are decrypted for a list on every read and
 * the one thing cached is the single display string, in a cache that never
 * leaves the process. A miss, an eviction or a total loss degrades to a
 * database read; correctness never reads cache state.
 *
 * <p>Eviction waits for the commit when there is a transaction: evicting inside
 * it would let a concurrent reader repopulate the cache from the row as it was
 * before the write committed, and the old name would then stay for ten minutes.
 */
@Component
class StaffNameCache {

    private final CacheManager caches;

    StaffNameCache(CacheManager caches) {
        this.caches = caches;
    }

    @Nullable
    String get(UUID tenantId, String subject) {
        Cache cache = cache();
        if (cache == null) {
            return null;
        }
        Cache.ValueWrapper hit = cache.get(key(tenantId, subject));
        return hit == null ? null : (String) hit.get();
    }

    void put(UUID tenantId, String subject, String name) {
        Cache cache = cache();
        if (cache != null) {
            cache.put(key(tenantId, subject), name);
        }
    }

    /** Drops the entry once the surrounding transaction commits, or now when there is none. */
    void evictAfterCommit(UUID tenantId, String subject) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evict(tenantId, subject);
                }
            });
            return;
        }
        evict(tenantId, subject);
    }

    void evict(UUID tenantId, String subject) {
        Cache cache = cache();
        if (cache != null) {
            cache.evict(key(tenantId, subject));
        }
    }

    private @Nullable Cache cache() {
        return caches.getCache(CacheRegistry.STAFF_DISPLAY_NAMES.cacheName());
    }

    static String key(UUID tenantId, String subject) {
        return tenantId + "|" + subject;
    }
}
