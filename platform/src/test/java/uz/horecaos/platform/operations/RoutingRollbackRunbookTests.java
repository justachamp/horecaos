package uz.horecaos.platform.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.PostMapping;
import uz.horecaos.platform.integration.web.OperationsProviderInstallationController;
import uz.horecaos.platform.integration.web.ProviderInstallationController;

/**
 * The routing runbook's one-tenant rollback has to be a switch that exists (ADR 0147).
 *
 * <p>It told the operator to suspend the tenant's routing installation "in Integrations".
 * Integrations suspends <em>bindings</em>; no installation of any category has a suspend
 * door, and a platform routing installation is not even created there (it is created by
 * choosing "use platform routing" on a draft tariff). An operator at 02:00 would have looked
 * for a button that does not exist, or edited {@code integration.installations} by hand and
 * left no audit record. The per-tenant choice of road or straight line lives on the tariff
 * version, so that is the rollback the runbook has to name.
 */
class RoutingRollbackRunbookTests {

    private static final Path RUNBOOK = Path.of("docs/runbooks/load-uzbekistan-routing-dataset.md");
    private static final Path DESCRIPTOR = Path.of("docs/routes/osrm-road-distance.md");

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

    @Test
    @DisplayName(
            "the rollback to the previous map uses the same order, and the one-tenant rollback is the tariff version")
    void theRollbackNamesMechanismsThatExist() throws IOException {
        String rollback = section("7. Roll back");
        String oneTenant = rollback.substring(
                        rollback.indexOf("1. **One tenant:**"), rollback.indexOf("2. **Everyone:**"))
                .replaceAll("\\s+", " ");

        assertThat(oneTenant)
                .as("the per-tenant switch is the distance mode of the tariff version")
                .contains("Straight line")
                .contains("Activate draft");
        assertThat(rollback.replaceAll("\\s+", " "))
                .doesNotContain("UPDATE integration.installations")
                .doesNotContain("(Integrations, the *Platform routing* row")
                .doesNotContain("suspend its routing installation");
        assertThat(runbook().replaceAll("\\s+", " "))
                .doesNotContain("suspend the tenant's routing installation")
                .doesNotContain("or a suspended installation");
        // The route descriptor is the other place an operator reads the rollback, and it made the
        // same promise.
        assertThat(Files.readString(DESCRIPTOR, StandardCharsets.UTF_8).replaceAll("\\s+", " "))
                .doesNotContain("or suspending the installation")
                .contains("activating a new version of its `ROAD` tariff");
    }

    @Test
    @DisplayName("there is still no door that suspends an installation, so the runbook may not name one")
    void noInstallationLevelSuspendDoorExists() {
        // The runbook says there is none. If one is added, this fails and the runbook's step
        // 7.1 has to be revisited: a real switch may well be the better one-tenant rollback.
        List<String> suspendPaths = new ArrayList<>();
        for (Class<?> controller :
                List.of(ProviderInstallationController.class, OperationsProviderInstallationController.class)) {
            for (Method method : controller.getDeclaredMethods()) {
                PostMapping mapping = method.getAnnotation(PostMapping.class);
                if (mapping == null) {
                    continue;
                }
                Arrays.stream(mapping.value())
                        .filter(path -> path.toLowerCase(java.util.Locale.ROOT).contains("suspen"))
                        .forEach(suspendPaths::add);
            }
        }

        assertThat(suspendPaths)
                .as("every suspend door on the installation controllers")
                .isNotEmpty();
        assertThat(suspendPaths)
                .as("a suspend door that is not under /bindings/ would suspend an installation")
                .allSatisfy(path -> assertThat(path).contains("/bindings/"));
    }
}
