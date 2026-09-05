package uz.horecaos.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * ADR 0023's {@code app}/{@code worker} split, proven at the one place it actually
 * lives: whether the Spring scheduling infrastructure exists in the context at all.
 *
 * <p>{@link SchedulingConfiguration} carries the platform's only {@code
 * @EnableScheduling}, so this is a property test about one class rather than about
 * thirty-two of them: every {@code @Scheduled} method on every module rides on the same
 * {@link ScheduledAnnotationBeanPostProcessor}, and a role that removes it removes all
 * of them uniformly, including ones this test was never told about.
 *
 * <p>Deliberately not a test that waits for a tick. Whether a job's timer would ever
 * fire is answered by whether Spring registered the machinery that fires timers at
 * all — a fact available the instant the context refreshes, with no clock and no sleep
 * involved.
 */
class RuntimeRoleSchedulingTests {

    // SchedulingConfiguration.taskScheduler resolves an ISO-8601 @Value Duration, which
    // needs Boot's own conversion service — present in a real application, absent from a
    // bare ApplicationContextRunner's plain AnnotationConfigApplicationContext.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withInitializer(context ->
                    context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(SchedulingConfiguration.class, ProcessHealth.class);

    @Test
    @DisplayName("role unset runs every scheduled job, exactly as before this record existed")
    void unsetRoleRunsSchedulingLikeToday() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(context).hasSingleBean(ThreadPoolTaskScheduler.class);
        });
    }

    @Test
    @DisplayName("role \"both\" runs every scheduled job")
    void bothRoleRunsScheduling() {
        runner.withPropertyValues("horecaos.runtime.role=both").run(context -> {
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(context).hasSingleBean(ThreadPoolTaskScheduler.class);
        });
    }

    @Test
    @DisplayName("role \"worker\" runs every scheduled job")
    void workerRoleRunsScheduling() {
        runner.withPropertyValues("horecaos.runtime.role=worker").run(context -> {
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(context).hasSingleBean(ThreadPoolTaskScheduler.class);
        });
    }

    @Test
    @DisplayName("role \"app\" runs no scheduled work at all")
    void appRoleRunsNoScheduling() {
        runner.withPropertyValues("horecaos.runtime.role=app").run(context -> {
            assertThat(context)
                    .as("no post-processor means no @Scheduled method anywhere in the platform "
                            + "is ever invoked, regardless of that method's own switch")
                    .doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(context).doesNotHaveBean(ThreadPoolTaskScheduler.class);
        });
    }

    @Test
    @DisplayName("the role name is case-insensitive")
    void roleIsCaseInsensitive() {
        runner.withPropertyValues("horecaos.runtime.role=APP")
                .run(context -> assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
        runner.withPropertyValues("horecaos.runtime.role=Worker")
                .run(context -> assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    @DisplayName("a role that is none of app, worker, both fails the context rather than guessing")
    void anUnknownRoleFailsStartup() {
        runner.withPropertyValues("horecaos.runtime.role=scheduler").run(context -> {
            Throwable failure = context.getStartupFailure();
            assertThat(failure).isNotNull();
            assertThat(causeChain(failure))
                    .as("a typo must not silently run every job (matching \"both\") or none of them "
                            + "(matching \"app\"); RuntimeRole.fromProperty's own IllegalStateException "
                            + "must be somewhere in the chain Spring wraps it in")
                    .anyMatch(cause -> cause instanceof IllegalStateException
                            && cause.getMessage() != null
                            && cause.getMessage().contains("horecaos.runtime.role")
                            && cause.getMessage().contains("scheduler"));
        });
    }

    /** A throwable's own cause chain, bottoming out rather than looping on a self-reference. */
    private static List<Throwable> causeChain(@Nullable Throwable top) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable cursor = top; cursor != null && !chain.contains(cursor); cursor = cursor.getCause()) {
            chain.add(cursor);
        }
        return chain;
    }

    @Test
    @DisplayName("RuntimeRole.fromProperty draws the same line the condition does")
    void fromPropertyMatchesTheConditionsBehaviour() {
        assertThat(RuntimeRole.fromProperty(null)).isEqualTo(RuntimeRole.BOTH);
        assertThat(RuntimeRole.fromProperty("")).isEqualTo(RuntimeRole.BOTH);
        assertThat(RuntimeRole.fromProperty("  ")).isEqualTo(RuntimeRole.BOTH);
        assertThat(RuntimeRole.fromProperty("app")).isEqualTo(RuntimeRole.APP);
        assertThat(RuntimeRole.fromProperty("WORKER")).isEqualTo(RuntimeRole.WORKER);
        assertThat(RuntimeRole.fromProperty(" both ")).isEqualTo(RuntimeRole.BOTH);

        assertThat(RuntimeRole.BOTH.runsWorkerWork()).isTrue();
        assertThat(RuntimeRole.WORKER.runsWorkerWork()).isTrue();
        assertThat(RuntimeRole.APP.runsWorkerWork()).isFalse();

        assertThatThrownBy(() -> RuntimeRole.fromProperty("scheduler"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app")
                .hasMessageContaining("worker")
                .hasMessageContaining("both");
    }
}
