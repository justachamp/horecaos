package uz.horecaos.platform.reviews.application;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uz.horecaos.platform.web.cache.CacheRegistry;

/**
 * The two registered caches behind the anonymous public review endpoints (ADR 0159, ADR 0033), and the
 * one rule that makes the list safe to cache: a hide, a withdrawal and an erasure evict it in the same
 * request, after the commit, so the next read cannot return the comment.
 *
 * <p>The list entry holds <em>decrypted</em> personal text, so eviction is not a nicety. Two things keep
 * it honest:
 *
 * <ul>
 *   <li>Eviction runs <em>after the commit</em> ({@link #evictAfterCommit}), never before it. Evicting
 *       first would let a concurrent reader repopulate the cache from the not-yet-committed state.
 *   <li>A reader that began loading <em>before</em> an eviction may not store what it loaded
 *       <em>after</em> it. Each brand has a generation that every eviction bumps; a loader remembers the
 *       generation it started under and stores only if it is unchanged. Without this a slow reader that
 *       read the comment while it was still published would put it back into the cache the moment after
 *       it was hidden.
 * </ul>
 *
 * <p>The cache is an accelerator and never a decision (ADR 0033): visibility, the minimum count and the
 * publication state are read from their sources on every request, and a miss simply asks the database.
 */
@Component
public class PublicReviewCache {

    /** The most distinct pages one brand's list entry will hold: an anonymous caller cannot grow it without bound. */
    static final int MAXIMUM_PAGES_PER_BRAND = 50;

    private final CacheManager caches;
    private final Map<String, AtomicLong> generations = new ConcurrentHashMap<>();

    public PublicReviewCache(CacheManager caches) {
        this.caches = caches;
    }

    /** The brand's summary, loaded at most once per TTL. */
    @SuppressWarnings("unchecked")
    public <T> T summary(UUID tenantId, UUID brandId, Supplier<T> loader) {
        Cache cache = cache(CacheRegistry.REVIEWS_PUBLIC_SUMMARY);
        String key = key(tenantId, brandId);
        Cache.ValueWrapper hit = cache.get(key);
        Object cached = hit == null ? null : hit.get();
        if (cached != null) {
            return (T) cached;
        }
        T loaded = loader.get();
        cache.put(key, loaded);
        return loaded;
    }

    /**
     * One page of the brand's published comments. The loader's answer is stored only if no eviction
     * happened while it ran, and only while the brand's entry holds fewer than {@value
     * #MAXIMUM_PAGES_PER_BRAND} pages.
     */
    @SuppressWarnings("unchecked")
    public <T> T listPage(UUID tenantId, UUID brandId, String pageKey, Supplier<T> loader) {
        Cache cache = cache(CacheRegistry.REVIEWS_PUBLIC_LIST);
        String key = key(tenantId, brandId);
        Map<String, Object> pages = java.util.Objects.requireNonNull(
                cache.get(key, ConcurrentHashMap<String, Object>::new), "the cache loads its own entry");
        Object hit = pages.get(pageKey);
        if (hit != null) {
            return (T) hit;
        }
        long generation = generation(key).get();
        T loaded = loader.get();
        if (generation(key).get() == generation && pages.size() < MAXIMUM_PAGES_PER_BRAND) {
            pages.put(pageKey, loaded);
        }
        return loaded;
    }

    /** Evicts the brand's published comments from the cache once the current transaction has committed. */
    public void evictAfterCommit(UUID tenantId, UUID brandId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    evictNow(tenantId, brandId);
                }
            });
            return;
        }
        evictNow(tenantId, brandId);
    }

    /** Evicts at once. For code that is already past its commit, and for the tests that prove it. */
    public void evictNow(UUID tenantId, UUID brandId) {
        String key = key(tenantId, brandId);
        generation(key).incrementAndGet();
        cache(CacheRegistry.REVIEWS_PUBLIC_LIST).evict(key);
    }

    /** Whether the list entry for this brand exists, for a test to prove an eviction happened. */
    public boolean holdsList(UUID tenantId, UUID brandId) {
        return cache(CacheRegistry.REVIEWS_PUBLIC_LIST).get(key(tenantId, brandId)) != null;
    }

    private AtomicLong generation(String key) {
        return generations.computeIfAbsent(key, ignored -> new AtomicLong());
    }

    private Cache cache(CacheRegistry registered) {
        Cache cache = caches.getCache(registered.cacheName());
        if (cache == null) {
            throw new IllegalStateException(registered.cacheName() + " is registered but was not built");
        }
        return cache;
    }

    private static String key(UUID tenantId, UUID brandId) {
        return tenantId + ":" + brandId;
    }
}
