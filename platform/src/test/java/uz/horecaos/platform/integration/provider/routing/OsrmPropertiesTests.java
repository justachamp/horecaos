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
                                        "HORECAOS_ROUTING_OSRM_TIMEOUT", "250ms"))))
                .run(run -> {
                    OsrmProperties properties = run.getBean(OsrmProperties.class);

                    assertThat(properties.enabled()).isTrue();
                    assertThat(properties.datasetVersion()).isEqualTo("2026-10-01");
                    assertThat(properties.timeout()).isEqualTo(Duration.ofMillis(250));
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
}
