package uz.horecaos.platform.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * An environment variable a runbook tells the operator to set must reach {@code platform-app} (ADR 0145).
 *
 * <p>Compose forwards nothing from the operator's {@code --env-file} to a container except what the service lists (the
 * file feeds interpolation only), so a variable the delivery-maps runbook names and the compose file does not list
 * leaves the platform on its default: the operator follows the steps, restarts the stack, and
 * {@code GET .../map-config} still answers {@code configured: false} with nothing saying why. That was the state of
 * {@code HORECAOS_GEO_PROVIDER}, {@code HORECAOS_GEO_YANDEX_SECRET_REFERENCE} and
 * {@code HORECAOS_GEO_YANDEX_BROWSER_KEY} when the wave moved the map key off the storefront container and onto
 * platform-delivered configuration. {@link ProductionComposeSecretReferenceTests} guards the references
 * {@code application.yml} will not default; this guards the ones it defaults and a runbook asks an operator to set.
 *
 * <p>The compose file is read as parsed YAML, not as text, so a comment that mentions a variable cannot satisfy it.
 */
class ProductionComposeRunbookVariablesTests {

    private static final Path COMPOSE = Path.of("../deploy/compose.production.yml");

    private static final Path ENV_TEMPLATE = Path.of("../deploy/env.template");

    private static final Path ENV_STAGING_EXAMPLE = Path.of("../deploy/env.staging.example");

    private static final Path DELIVERY_MAPS_RUNBOOK = Path.of("docs/runbooks/external-connections/w3-delivery-maps.md");

    private static final Pattern VARIABLE = Pattern.compile("\\bHORECAOS_[A-Z0-9]+(?:_[A-Z0-9]+)*\\b");

    /** The three the operator sets to turn geocoding and tiles on; the runbook's sections 1 to 3. */
    private static final Set<String> GEO_VARIABLES =
            Set.of("HORECAOS_GEO_PROVIDER", "HORECAOS_GEO_YANDEX_SECRET_REFERENCE", "HORECAOS_GEO_YANDEX_BROWSER_KEY");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> platformAppEnvironment() throws IOException {
        try (Reader reader = Files.newBufferedReader(COMPOSE, StandardCharsets.UTF_8)) {
            Map<String, Object> file = Objects.requireNonNull(new Yaml().load(reader), "an empty compose file");
            Map<String, Object> services = (Map<String, Object>) file.get("services");
            assertThat(services).as("a services block").isNotNull();
            Object service = Objects.requireNonNull(services).get("platform-app");
            if (!(service instanceof Map<?, ?> serviceMap)) {
                throw new AssertionError("a platform-app service");
            }
            if (!(serviceMap.get("environment") instanceof Map<?, ?> environment)) {
                throw new AssertionError("platform-app's environment block, as a map");
            }
            return (Map<String, Object>) environment;
        }
    }

    private static Set<String> variablesNamedIn(Path file) throws IOException {
        Set<String> names = new TreeSet<>();
        Matcher matcher = VARIABLE.matcher(Files.readString(file, StandardCharsets.UTF_8));
        while (matcher.find()) {
            names.add(matcher.group());
        }
        return names;
    }

    private static boolean assignsOrSuggests(Path envFile, String variable) throws IOException {
        Pattern assignment = Pattern.compile("^#?\\s*" + Pattern.quote(variable) + "=", Pattern.MULTILINE);
        return assignment
                .matcher(Files.readString(envFile, StandardCharsets.UTF_8))
                .find();
    }

    @Test
    @DisplayName("the delivery-maps runbook names the three switches, so the premise of the other checks holds")
    void theRunbookNamesTheGeoSwitches() throws IOException {
        assertThat(variablesNamedIn(DELIVERY_MAPS_RUNBOOK)).containsAll(GEO_VARIABLES);
    }

    @Test
    @DisplayName(
            "every HORECAOS_ variable the delivery-maps runbook names is passed to platform-app by the production compose file")
    void everyVariableTheRunbookNamesReachesThePlatform() throws IOException {
        Map<String, Object> environment = platformAppEnvironment();

        for (String variable : variablesNamedIn(DELIVERY_MAPS_RUNBOOK)) {
            assertThat(environment)
                    .as(
                            variable + " is named by " + DELIVERY_MAPS_RUNBOOK
                                    + " as something to set in production.env;"
                                    + " compose forwards only what platform-app lists, so unlisted it never reaches the container")
                    .containsKey(variable);
        }
    }

    @Test
    @DisplayName(
            "the provider defaults to none and the two keys to empty, so an environment that sets nothing stays off")
    void theGeoSwitchesDefaultToOff() throws IOException {
        Map<String, Object> environment = platformAppEnvironment();

        assertThat(String.valueOf(environment.get("HORECAOS_GEO_PROVIDER")))
                .isEqualTo("${HORECAOS_GEO_PROVIDER:-none}");
        assertThat(String.valueOf(environment.get("HORECAOS_GEO_YANDEX_SECRET_REFERENCE")))
                .isEqualTo("${HORECAOS_GEO_YANDEX_SECRET_REFERENCE:-}");
        assertThat(String.valueOf(environment.get("HORECAOS_GEO_YANDEX_BROWSER_KEY")))
                .isEqualTo("${HORECAOS_GEO_YANDEX_BROWSER_KEY:-}");
    }

    @Test
    @DisplayName("the environment templates list the three switches, so an operator finds them where the others are")
    void theEnvironmentTemplatesListTheGeoSwitches() throws IOException {
        for (String variable : GEO_VARIABLES) {
            assertThat(assignsOrSuggests(ENV_TEMPLATE, variable))
                    .as(variable + " in deploy/env.template")
                    .isTrue();
            assertThat(assignsOrSuggests(ENV_STAGING_EXAMPLE, variable))
                    .as(variable + " in deploy/env.staging.example")
                    .isTrue();
        }
    }
}
