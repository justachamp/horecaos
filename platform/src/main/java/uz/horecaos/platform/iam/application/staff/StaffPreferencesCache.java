package uz.horecaos.platform.iam.application.staff;

import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uz.horecaos.platform.iam.infrastructure.persistence.JdbcStaffPreferencesStore.PreferenceRow;
import uz.horecaos.platform.web.cache.CacheRegistry;

/**
 * The in-process {@code staff.preferences} cache, keyed by {@code (tenant, subject)} (ADR 0160, ADR
 * 0033): a person's sealed preference rows, so the console's once-per-session read costs a database
 * round trip once a few seconds and not once per tab.
 *
 * <p>It holds ciphertext. The documents are opened on every read, so the one plaintext that is ever in
 * process memory is the one a request is answering with, for as long as it takes to answer. A miss, an
 * eviction or a total loss degrades to a database read; no write consults it. Eviction waits for the
 * commit when there is a transaction, for the reason {@link StaffNameCache} gives: evicting inside it lets
 * a concurrent reader repopulate the cache from the row as it was before the write committed.
 */
@Component
class StaffPreferencesCache {

    private final CacheManager caches;

    StaffPreferencesCache(CacheManager caches) {
        this.caches = caches;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    List<PreferenceRow> get(UUID tenantId, String subject) {
        Cache cache = cache();
        if (cache == null) {
            return null;
        }
        Cache.ValueWrapper hit = cache.get(key(tenantId, subject));
        return hit == null ? null : (List<PreferenceRow>) hit.get();
    }

    void put(UUID tenantId, String subject, List<PreferenceRow> rows) {
        Cache cache = cache();
        if (cache != null) {
            cache.put(key(tenantId, subject), List.copyOf(rows));
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
        return caches.getCache(CacheRegistry.STAFF_PREFERENCES.cacheName());
    }

    static String key(UUID tenantId, String subject) {
        return tenantId + "|" + subject;
    }
}
