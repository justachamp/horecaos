package uz.horecaos.platform.integration.provider.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/**
 * The deployment's side of the OSRM adapter (ADR 0147): what an unset environment means, and
 * what the compose file's variable names bind to.
 */
class OsrmPropertiesTests {

    private final ApplicationContextRunner context =
            new ApplicationContextRunner().withUserConfiguration(Binding.class);

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(OsrmProperties.class)
    static class Binding {}

    @Test
    @DisplayName("with nothing set the engine is off, and a fee is never measured by it")
    void offByDefault() {
        context.run(run -> {
            OsrmProperties properties = run.getBean(OsrmProperties.class);

            assertThat(properties.enabled()).isFalse();
            assertThat(properties.answering()).isFalse();
            assertThat(properties.datasetVersion()).isNull();
            assertThat(properties.timeout()).isEqualTo(Duration.ofMillis(500));
            assertThat(properties.snapRadiusMeters()).isEqualTo(1_000);
            assertThat(properties.slowCallThreshold()).isEqualTo(Duration.ofMillis(250));
            assertThat(properties.maxConcurrentCalls()).isEqualTo(16);
        });
    }

    @Test
    @DisplayName("the variables deploy/compose.production.yml sets bind to the properties the adapter reads")
    void theComposeVariablesBind() {
        // The container's environment, as Spring sees it: a system-environment property source,
        // whose uppercase-and-underscore names are what compose passes to platform-app.
        context.withInitializer(initializer -> initializer
                        .getEnvironment()
                        .getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource(
                                "compose-environment",
                                Map.of(
                                        "HORECAOS_ROUTING_OSRM_ENABLED", "true",
                                        "HORECAOS_ROUTING_OSRM_DATASET_VERSION", "2026-10-01",
                                        "HORECAOS_ROUTING_OSRM_TIMEOUT", "250ms",
                                        "HORECAOS_ROUTING_OSRM_SLOW_CALL_THRESHOLD", "120ms",
                                        "HORECAOS_ROUTING_OSRM_MAX_CONCURRENT_CALLS", "4"))))
                .run(run -> {
                    OsrmProperties properties = run.getBean(OsrmProperties.class);

                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.datasetVersion()).isEqualTo("2026-10-01");
                    assertThat(properties.timeout()).isEqualTo(Duration.ofMillis(250));
                    assertThat(properties.slowCallThreshold()).isEqualTo(Duration.ofMillis(120));
                    assertThat(properties.maxConcurrentCalls()).isEqualTo(4);
                    assertThat(properties.answering()).isTrue();
                });
    }

    @Test
    @DisplayName("an enabled engine with a blank dataset version is not answering")
    void aBlankDatasetIsNotAnswering() {
        context.withPropertyValues("horecaos.routing.osrm.enabled=true", "horecaos.routing.osrm.dataset-version=  ")
                .run(run -> {
                    OsrmProperties properties = run.getBean(OsrmProperties.class);
                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.datasetVersion()).isNull();
                    assertThat(properties.answering()).isFalse();
                });
    }

    @Test
    @DisplayName("a non-positive timeout or snap radius is refused at start rather than meaning 'unbounded'")
    void nonsenseLimitsAreRefused() {
        assertThatThrownBy(
                        () -> new OsrmProperties(true, "2026-10-01", Duration.ZERO, 1_000, 10, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        new OsrmProperties(true, "2026-10-01", Duration.ofMillis(500), 0, 10, Duration.ofSeconds(30)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a slow-call threshold or a concurrency cap that is not positive is refused at start")
    void nonsenseBoundsAreRefused() {
        assertThatThrownBy(() -> new OsrmProperties(
                        true,
                        "2026-10-01",
                        Duration.ofMillis(500),
                        1_000,
                        10,
                        Duration.ofSeconds(30),
                        Duration.ZERO,
                        16))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OsrmProperties(
                        true,
                        "2026-10-01",
                        Duration.ofMillis(500),
                        1_000,
                        10,
                        Duration.ofSeconds(30),
                        Duration.ofMillis(250),
                        0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("a timeout past the ceiling is held to it, a shorter one is kept, and nothing is refused at start")
    void theTimeoutIsHeldToTheCeiling() {
        context.withPropertyValues("horecaos.routing.osrm.timeout=30s").run(run -> {
            OsrmProperties properties = run.getBean(OsrmProperties.class);

            assertThat(properties.timeout()).as("what was configured").isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.effectiveTimeout())
                    .as("what the quote thread waits")
                    .isEqualTo(Duration.ofSeconds(1));
        });
        assertThat(new OsrmProperties(true, "2026-10-01", Duration.ofMillis(300), 1_000, 10, Duration.ofSeconds(30))
                        .effectiveTimeout())
                .isEqualTo(Duration.ofMillis(300));
        assertThat(OsrmProperties.MAX_TIMEOUT).isEqualTo(Duration.ofSeconds(1));
    }
}
