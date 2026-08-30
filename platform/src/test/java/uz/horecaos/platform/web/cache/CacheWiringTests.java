package uz.horecaos.platform.web.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;

/**
 * ADR 0033: every cache in the platform is registered, and no correctness path
 * reads from one.
 */
class CacheWiringTests {

    private static final String BASE_PACKAGE = "uz.horecaos.platform";

    /**
     * Classes whose correctness depends on the database rather than on a cached
     * answer. ADRs 0005, 0017, and 0031 all reject cache-based correctness by
     * name; this makes the rejection enforceable rather than a convention.
     */
    private static final List<String> CORRECTNESS_PATHS = List.of(
            "IdempotencyService",
            "JdbcInboxStore",
            "InboxExecutor",
            "FailureOperationsService",
            "JdbcApprovalService",
            "JdbcAuditRecorder");

    @Test
    void everyCacheableAnnotationNamesARegisteredCache() {
        List<String> unregistered = new java.util.ArrayList<>();

        for (Class<?> type : platformClasses()) {
            for (var method : type.getDeclaredMethods()) {
                Cacheable cacheable = method.getAnnotation(Cacheable.class);
                if (cacheable == null) {
                    continue;
                }
                for (String name : cacheable.cacheNames()) {
                    if (CacheRegistry.find(name).isEmpty()) {
                        unregistered.add(type.getSimpleName() + "#" + method.getName() + " -> " + name);
                    }
                }
            }
        }

        assertThat(unregistered).as("""
                        A cache with no registry entry has no declared TTL, size bound, or
                        invalidation source, which is how a stale answer outlives its cause.""").isEmpty();
    }

    @Test
    void noCorrectnessPathReadsFromACache() {
        List<String> violations = new java.util.ArrayList<>();

        for (Class<?> type : platformClasses()) {
            if (!CORRECTNESS_PATHS.contains(type.getSimpleName())) {
                continue;
            }
            for (var method : type.getDeclaredMethods()) {
                if (method.isAnnotationPresent(Cacheable.class)) {
                    violations.add(type.getSimpleName() + "#" + method.getName());
                }
            }
        }

        assertThat(violations).as("""
                        Deduplication, idempotency, and audit decide whether an effect happens.
                        An eviction or a restart must never be able to reopen that window.""").isEmpty();
    }

    @Test
    void theCacheManagerExposesExactlyTheRegisteredCaches() {
        CacheManager manager = new CacheConfiguration().cacheManager();

        assertThat(manager.getCacheNames())
                .containsExactlyInAnyOrderElementsOf(Arrays.stream(CacheRegistry.values())
                        .map(CacheRegistry::cacheName)
                        .toList());
        assertThat(manager.getCache("someone.invented.this"))
                .as("a fixed cache list is what stops an unregistered cache appearing implicitly")
                .isNull();
    }

    @Test
    void theScanFindsTheClassesItClaimsToCheck() {
        assertThat(platformClasses()).hasSizeGreaterThan(20);
    }

    private static List<Class<?>> platformClasses() {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(Object.class));

        List<Class<?>> classes = new java.util.ArrayList<>();
        for (BeanDefinition definition : scanner.findCandidateComponents(BASE_PACKAGE)) {
            try {
                classes.add(Class.forName(definition.getBeanClassName()));
            } catch (Throwable unloadable) {
                // A class that cannot load cannot hold an annotation either.
            }
        }
        return classes;
    }
}
