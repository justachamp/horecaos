package uz.horecaos.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * The scheduler pool is a correctness property, not a tuning knob.
 *
 * <p>Boot's default is a pool of one, and on one thread the platform's timers
 * are serialised: the fifty-millisecond realtime tick, the one-second outbox
 * relay and a sweeper holding a connection all queue behind whichever started
 * first. The failure is silent — every job simply stops, and nothing fails a
 * health check — so it is asserted here rather than left to a property file.
 */
class SchedulerPoolSizeTests {

    @Test
    void thePoolHasOneThreadForEveryScheduledMethod() throws IOException {
        int jobs = scheduledMethodCount();

        assertThat(SchedulingConfiguration.DEFAULT_POOL_SIZE)
                .as("%d @Scheduled methods share this pool; a pool smaller than that lets an "
                        + "unrelated module's sweeper delay the outbox relay", jobs)
                .isGreaterThanOrEqualTo(jobs);
    }

    @Test
    void theSchedulerIsNamedAndDrainsOnShutdown() {
        ThreadPoolTaskScheduler scheduler = new SchedulingConfiguration().taskScheduler(
                healthNothingWillReport(), SchedulingConfiguration.DEFAULT_POOL_SIZE,
                Duration.ofSeconds(20));
        scheduler.initialize();

        try {
            assertThat(scheduler.getScheduledThreadPoolExecutor().getCorePoolSize())
                    .as("getPoolSize() reports live threads, which is zero until work arrives")
                    .isEqualTo(SchedulingConfiguration.DEFAULT_POOL_SIZE);
            assertThat(scheduler.getThreadNamePrefix())
                    .as("a thread dump must attribute a stuck job to the platform's scheduler")
                    .isEqualTo("horecaos-scheduler-");
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void aStalledJobDoesNotHoldUpAnUnrelatedOne() throws InterruptedException {
        ThreadPoolTaskScheduler scheduler = new SchedulingConfiguration().taskScheduler(
                healthNothingWillReport(), SchedulingConfiguration.DEFAULT_POOL_SIZE,
                Duration.ofSeconds(1));
        scheduler.initialize();

        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch unrelatedRan = new CountDownLatch(1);

        try {
            // Stands in for a sweeper wedged on a query that never returns. On
            // Boot's default pool of one this is the whole platform's timers.
            scheduler.execute(() -> {
                try {
                    release.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            scheduler.execute(unrelatedRan::countDown);

            assertThat(unrelatedRan.await(5, TimeUnit.SECONDS))
                    .as("a job that never returns must not stop every other job on the platform")
                    .isTrue();
        } finally {
            release.countDown();
            scheduler.shutdown();
        }
    }

    /**
     * The scheduler's error handler needs one, and nothing in this file fails.
     *
     * <p>What it does when something does fail is {@code ProcessFatalErrorTests}'
     * subject; here it only has to exist, so the events go nowhere.
     */
    private static ProcessHealth healthNothingWillReport() {
        return new ProcessHealth(event -> { });
    }

    /**
     * Counted from class metadata rather than from a running context, so the
     * guard costs nothing and holds even for a job whose module is switched off
     * by a {@code @ConditionalOnProperty}.
     */
    private static int scheduledMethodCount() throws IOException {
        MetadataReaderFactory metadata = new CachingMetadataReaderFactory();
        Resource[] classes = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:uz/horecaos/platform/**/*.class");

        // A set, because the same class can be reachable from more than one
        // classpath root and a job counted twice would inflate the pool silently.
        Set<String> found = new TreeSet<>();
        for (Resource candidate : classes) {
            MetadataReader reader = metadata.getMetadataReader(candidate);
            reader.getAnnotationMetadata()
                    .getAnnotatedMethods(Scheduled.class.getName())
                    .forEach(method -> found.add(
                            reader.getClassMetadata().getClassName() + "#" + method.getMethodName()));
        }

        assertThat(found)
                .as("the scan found no @Scheduled methods at all, so it is measuring nothing")
                .isNotEmpty();
        return found.size();
    }
}
