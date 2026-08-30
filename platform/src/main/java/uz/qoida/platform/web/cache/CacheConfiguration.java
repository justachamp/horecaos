package uz.qoida.platform.web.cache;

import java.util.Arrays;
import java.util.List;

import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

import com.github.benmanes.caffeine.cache.Caffeine;

/**
 * Builds one in-process cache per {@link CacheRegistry} entry (ADR 0033).
 *
 * <p>{@code setAllowNullValues(false)} and a fixed cache list together mean a
 * cache name that is not registered cannot be created implicitly, which is how
 * an unregistered cache with no declared TTL would otherwise appear.
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
public class CacheConfiguration {

    @Bean
    CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setAllowNullValues(false);

        List<String> names = Arrays.stream(CacheRegistry.values())
                .map(CacheRegistry::cacheName)
                .toList();
        manager.setCacheNames(names);

        Arrays.stream(CacheRegistry.values()).forEach(registered ->
                manager.registerCustomCache(registered.cacheName(), Caffeine.newBuilder()
                        .expireAfterWrite(registered.ttl())
                        .maximumSize(registered.maximumSize())
                        .recordStats()
                        .build()));
        return manager;
    }
}
