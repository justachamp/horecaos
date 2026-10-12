package uz.horecaos.platform.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
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
 * A secret reference {@code application.yml} refuses to default must reach the container (ADR 0028, ADR 0155).
 *
 * <p>{@code application.yml} leaves {@code ${HORECAOS_HANDOVER_PEPPER_REF}} and {@code ${HORECAOS_TERMINAL_PIN_PEPPER_REF}}
 * without a fallback on purpose: the reference carries the environment segment the secret path is built from, and a
 * default would resolve against whichever environment it names. The other half of that decision is that the shipped
 * production compose file passes the variable to {@code platform-app}. Compose forwards nothing from the operator's
 * {@code --env-file} to a container except what the service lists (the file feeds interpolation only), so a reference
 * the service does not list is never set, the placeholder does not resolve and the container restarts in a loop, with
 * the pre-production stack down. {@code TerminalPinPepperReferenceDefaultTests} proves the application refuses an unset
 * reference; this proves the compose file does not leave it unset.
 *
 * <p>Checked on the parsed files, not on their text, so a comment that mentions a variable cannot satisfy it.
 */
class ProductionComposeSecretReferenceTests {

    private static final Path COMPOSE = Path.of("../deploy/compose.production.yml");

    private static final Path APPLICATION_YML = Path.of("src/main/resources/application.yml");

    /** {@code ${HORECAOS_X_REF}} and nothing with a {@code :default} inside the braces. */
    private static final Pattern REQUIRED_REFERENCE = Pattern.compile("\\$\\{(HORECAOS_[A-Z0-9_]+_REF)}");

    private static void collect(Object node, Set<String> into) {
        if (node instanceof String text) {
            Matcher matcher = REQUIRED_REFERENCE.matcher(text);
            while (matcher.find()) {
                into.add(matcher.group(1));
            }
        } else if (node instanceof Map<?, ?> map) {
            map.values().forEach(value -> collect(value, into));
        } else if (node instanceof Collection<?> items) {
            items.forEach(item -> collect(item, into));
        }
    }

    private static Set<String> referencesApplicationYmlRequires() throws IOException {
        Set<String> required = new TreeSet<>();
        try (Reader reader = Files.newBufferedReader(APPLICATION_YML, StandardCharsets.UTF_8)) {
            new Yaml().loadAll(reader).forEach(document -> collect(document, required));
        }
        return required;
    }

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

    @Test
    @DisplayName("the references application.yml leaves without a default include both peppers, so the premise holds")
    void theApplicationRequiresBothPepperReferences() throws IOException {
        assertThat(referencesApplicationYmlRequires())
                .contains("HORECAOS_HANDOVER_PEPPER_REF", "HORECAOS_TERMINAL_PIN_PEPPER_REF");
    }

    @Test
    @DisplayName(
            "every secret reference application.yml will not default is passed to platform-app by the production compose file")
    void everyRequiredReferenceIsWired() throws IOException {
        Map<String, Object> environment = platformAppEnvironment();

        for (String variable : referencesApplicationYmlRequires()) {
            assertThat(environment)
                    .as(variable + " has no default in application.yml, so platform-app fails to start"
                            + " (PlaceholderResolutionException) unless deploy/compose.production.yml sets it")
                    .containsKey(variable);
            assertThat(String.valueOf(environment.get(variable)))
                    .as(variable + " must carry a value, not an empty string")
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName(
            "the terminal PIN pepper's reference follows HORECAOS_ENVIRONMENT and can be rotated from the env file")
    void theTerminalPepperFollowsTheEnvironmentAndRotates() throws IOException {
        Map<String, Object> environment = platformAppEnvironment();

        assertThat(String.valueOf(environment.get("HORECAOS_TERMINAL_PIN_PEPPER_REF")))
                .as("overridable by the rotation runbook, and otherwise built from the environment segment like every"
                        + " other reference in this file")
                .startsWith("${HORECAOS_TERMINAL_PIN_PEPPER_REF:-horecaos:${HORECAOS_ENVIRONMENT:-production}:")
                .endsWith(":data_encryption:platform:terminal-pin-pepper}");
        assertThat(environment)
                .as("the rotation runbook sets these two in the env file; the container must receive them")
                .containsKeys("HORECAOS_TERMINAL_PIN_PEPPER_VERSION", "HORECAOS_TERMINAL_PIN_PEPPER_PREVIOUS_REF");
    }
}
