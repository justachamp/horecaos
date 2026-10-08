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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The routing engine moves with the dataset tag (ADR 0147).
 *
 * <p>{@code platform-app} stamps every ROAD fee with {@code HORECAOS_ROUTING_DATASET_TAG}, and
 * the runbook's monthly refresh is {@code up -d osrm-dataset osrm platform-app}. Compose
 * recreates a service only when its own resolved configuration changed. The {@code osrm}
 * service named nothing that varies with the tag, so the refresh replaced the files in the
 * volume and restarted the application with the new tag while the engine kept answering from
 * the old graph in memory: every new fee said it was measured on the new map, and the metres
 * came from the old one. The same held for rolling the tag back.
 *
 * <p>Checked on the parsed file, not on its text, so a comment that mentions the variable
 * cannot satisfy it.
 */
class RoutingComposeDatasetTagTests {

    private static final Path COMPOSE = Path.of("../deploy/compose.production.yml");

    private static final String TAG_REFERENCE = "${HORECAOS_ROUTING_DATASET_TAG";

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) throws IOException {
        try (Reader reader = Files.newBufferedReader(COMPOSE, StandardCharsets.UTF_8)) {
            Map<String, Object> file = Objects.requireNonNull(new Yaml().load(reader), "an empty compose file");
            Map<String, Object> services = (Map<String, Object>) file.get("services");
            assertThat(services).as("a services block").isNotNull();
            Map<String, Object> service = Objects.requireNonNull(services).get(name) instanceof Map<?, ?> found
                    ? (Map<String, Object>) found
                    : null;
            assertThat(service).as("a service named " + name).isNotNull();
            return Objects.requireNonNull(service);
        }
    }

    private static boolean mentionsTag(Object node) {
        if (node instanceof String text) {
            return text.contains(TAG_REFERENCE);
        }
        if (node instanceof Map<?, ?> map) {
            return map.entrySet().stream().anyMatch(e -> mentionsTag(e.getKey()) || mentionsTag(e.getValue()));
        }
        if (node instanceof Collection<?> items) {
            return items.stream().anyMatch(RoutingComposeDatasetTagTests::mentionsTag);
        }
        return false;
    }

    @Test
    @DisplayName("the dataset job and the application both read the tag, so the premise of this test holds")
    void theDatasetJobAndTheApplicationReadTheTag() throws IOException {
        assertThat(mentionsTag(service("osrm-dataset"))).isTrue();
        assertThat(mentionsTag(service("platform-app"))).isTrue();
    }

    @Test
    @DisplayName("the osrm service's configuration changes with the dataset tag, so a refresh recreates the engine")
    void theEngineIsRecreatedWhenTheTagChanges() throws IOException {
        Map<String, Object> osrm = service("osrm");

        assertThat(mentionsTag(osrm))
                .as("osrm must interpolate HORECAOS_ROUTING_DATASET_TAG (for example as an environment"
                        + " variable) so `up -d osrm-dataset osrm platform-app` recreates it after the dataset"
                        + " volume is replaced; otherwise the running engine keeps the old graph in memory while"
                        + " every new fee is stamped with the new tag")
                .isTrue();
    }
}
