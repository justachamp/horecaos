package uz.horecaos.platform.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.config.ScheduledTaskHolder;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * A {@code @SpringBootTest} context runs no {@code @Scheduled} job.
 *
 * <p>About 170 {@code @SpringBootTest} classes clean their private database with
 * {@code TRUNCATE ... CASCADE} before each test, and a scheduler polling the same database
 * from the same context deadlocks with it by construction: the TRUNCATE takes ACCESS
 * EXCLUSIVE locks on a cascade of tables one after another, a sweeper's SELECT takes ACCESS
 * SHARE locks on the tables it joins one after another, and when the two orders disagree
 * PostgreSQL kills one of the statements. Three of four consecutive CI runs failed a
 * different class that way, each of which passed on re-run.
 * {@code src/test/resources/application.properties} therefore sets ADR 0023's role to {@code
 * app} and switches off Spring Modulith's moments.
 *
 * <p>This is asserted against a real context on purpose. {@code RuntimeRoleSchedulingTests}
 * proves that role {@code app} removes {@link SchedulingConfiguration}, but it builds a bare
 * {@code ApplicationContextRunner} that loads neither the test profile nor any
 * auto-configuration, so it says nothing about the full application. There, {@code
 * spring-modulith-moments} carries its own {@code @EnableScheduling} and arrives with the
 * Modulith starters, so with the role alone set to {@code app} every sweeper still ran, on
 * Spring's fallback single-thread executor. What stops a sweeper from running is the absence of the
 * post-processor in the context the tests actually boot, and that is what this checks.
 *
 * <p>If this fails, something has put scheduling back: a dependency whose auto-configuration
 * enables it, a new {@code @EnableScheduling}, or a deleted line in the test properties. A
 * test that needs real timers asks for them itself with {@code horecaos.runtime.role=both};
 * the fix is never to relax this test, because every other class would then be back to racing
 * its own {@code TRUNCATE}.
 */
@SpringBootTest
class TestProfileSchedulingTests {

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the test profile scheduling test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
    }

    @Autowired
    private ApplicationContext context;

    @Test
    @DisplayName("the test profile boots with no scheduling post-processor, so no @Scheduled method can fire")
    void noScheduledJobRunsUnderTest() {
        assertThat(context.getEnvironment().getProperty(RuntimeRole.PROPERTY))
                .as("src/test/resources/application.properties must keep the role at app")
                .isEqualTo("app");
        assertThat(context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                .as("the post-processor that turns @Scheduled into a timer is registered by @EnableScheduling; "
                        + "something in this context declares one")
                .isFalse();
        assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class))
                .as("no @Scheduled method may fire in a test: its statements deadlock with TRUNCATE ... CASCADE")
                .isEmpty();
        assertThat(context.getBeansOfType(ScheduledTaskHolder.class))
                .as("nothing holds a scheduled task, so there is nothing to run")
                .isEmpty();
    }
}
