package uz.horecaos.platform.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The routing runbook's monthly refresh has to be an order the system can honour (ADR 0147).
 *
 * <p>It was one {@code up -d osrm-dataset osrm platform-app}. The application has no
 * dependency on the engine (a stopped engine must degrade a fee, never stop the application),
 * so Compose started it on the new tag whenever it liked relative to the engine, and the fees
 * it stamped and the routes it cached under the new tag, for a day, were measured by whichever
 * map the engine happened to hold. The engine now moves first, is proven to be on the new tag,
 * and only then does the application start stamping it.
 */
class RoutingDatasetRunbookTests {

    private static final Path RUNBOOK = Path.of("docs/runbooks/load-uzbekistan-routing-dataset.md");

    private static String runbook() throws IOException {
        return Files.readString(RUNBOOK, StandardCharsets.UTF_8);
    }

    /** The text from the heading that starts with {@code heading} to the next level-two heading. */
    private static String section(String heading) throws IOException {
        String text = runbook();
        int start = text.indexOf("\n## " + heading);
        assertThat(start).as("a section headed \"" + heading + "\"").isNotNegative();
        int end = text.indexOf("\n## ", start + 1);
        return end < 0 ? text.substring(start) : text.substring(start, end);
    }

    /** Every {@code docker compose … up …} command in the text, with continuation lines joined. */
    private static List<String> composeUps(String text) {
        List<String> commands = new ArrayList<>();
        for (String command : text.replace("\\\n", " ").split("\n")) {
            String trimmed = command.trim();
            if (trimmed.startsWith("docker compose") && trimmed.contains(" up ")) {
                commands.add(trimmed);
            }
        }
        return commands;
    }

    @Test
    @DisplayName("the monthly refresh recreates the engine in one command and the application in a later one")
    void theEngineMovesBeforeTheApplication() throws IOException {
        List<String> ups = composeUps(section("6. The monthly refresh"));

        assertThat(ups).as("the refresh's compose up commands").hasSize(2);
        assertThat(ups.get(0))
                .as("the first command moves the dataset and the engine")
                .contains(" osrm-dataset")
                .contains(" osrm")
                .doesNotContain("platform-app");
        assertThat(ups.get(1))
                .as("the second command moves the application, and only it")
                .endsWith("up -d platform-app");
    }

    @Test
    @DisplayName("the refresh proves the engine is on the new tag before the application is started on it")
    void theRefreshProvesTheEngineBeforeTheApplication() throws IOException {
        String refresh = section("6. The monthly refresh");

        int engine = refresh.indexOf("up -d osrm-dataset osrm");
        int proof = refresh.indexOf("grep '^HORECAOS_ROUTING_DATASET_TAG='");
        int application = refresh.indexOf("up -d platform-app");

        assertThat(engine).isNotNegative();
        assertThat(proof)
                .as("a command that prints the tag the running engine was started with")
                .isNotNegative();
        assertThat(application).isNotNegative();
        assertThat(engine).isLessThan(proof);
        assertThat(proof).isLessThan(application);
    }
}
