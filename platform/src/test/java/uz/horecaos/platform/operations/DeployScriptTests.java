package uz.horecaos.platform.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The colo deploy script starts the object store compose.production.yaml
 * actually defines (ADR 0135).
 *
 * <p>compose.production.yaml renamed the MinIO service to {@code object-store}
 * when RustFS replaced it, but nothing re-pointed
 * {@code infra/production/deploy.sh} at the new name: Phase 6 still asked
 * Compose to start a service called {@code minio}, which no longer exists, and
 * Phase 3 still wrote the object store's startup credential under the old
 * secret filename {@code minio-root-password}, which
 * compose.production.yaml's {@code secrets:} block no longer reads by
 * default. Both defects are invisible to a syntax check or to
 * {@code docker compose config} on the compose file alone — the mismatch is
 * between two files, and only this script's own text shows it. They surface
 * for the first time on the colo host, mid-deploy, exactly where
 * docs/runbooks/deploy.md tells an operator to run this script verbatim.
 *
 * <p>Asserted against the actual script text, the same way
 * {@link BackupScriptTests} pins backup.sh: the defect was a name the script
 * wrote, not a code path an execution test could isolate without root, Docker
 * and a sealed OpenBao.
 */
class DeployScriptTests {

    private static final Path SCRIPT = Path.of("infra/production/deploy.sh");
    private static final Path COMPOSE_FILE = Path.of("compose.production.yaml");

    @Test
    @DisplayName("phase 6 starts the object-store service, not the retired minio one")
    void startsTheObjectStoreServiceByItsCurrentName() throws IOException {
        String dependenciesLine = phaseSixDependenciesLine();
        List<String> services = Arrays.asList(dependenciesLine.trim().split("\\s+"));

        assertThat(services)
                .as("compose.production.yaml has no service literally named `minio` any more; "
                        + "`docker compose up -d` on this line fails with \"no such service: minio\"")
                .doesNotContain("minio");
        assertThat(services)
                .as("the object store must actually be started before the migration and the "
                        + "seed job that platform-app depends on")
                .contains("object-store");
    }

    @Test
    @DisplayName("the object-store secret is written under the filename compose.production.yaml expects")
    void writesTheObjectStoreSecretUnderItsComposeFilename() throws IOException {
        String script = readFile(SCRIPT);
        String compose = readFile(COMPOSE_FILE);

        // compose.production.yaml's `secrets:` block resolves the object store's
        // credential file from HORECAOS_SECRET_DIR under exactly this name unless
        // an operator overrides HORECAOS_OBJECT_STORE_SECRET_KEY_FILE for a
        // one-release compatibility window. deploy.sh does not set that override,
        // so it must write the file compose falls back to.
        assertThat(compose)
                .as("compose.production.yaml's object-store secret must still resolve to "
                        + "`object-store-secret-key` under HORECAOS_SECRET_DIR by default")
                .contains("${HORECAOS_OBJECT_STORE_SECRET_KEY_FILE:-${HORECAOS_SECRET_DIR}/object-store-secret-key}");

        assertThat(script)
                .as("deploy.sh must materialise the object store credential onto the tmpfs "
                        + "under the name compose.production.yaml reads by default")
                .contains("write_secret object-store-secret-key")
                .doesNotContain("write_secret minio-root-password");
    }

    /**
     * The line in Phase 6 ("Starting dependencies") that starts the platform
     * database, Kafka, the object store and OpenBao before migrations run —
     * distinct from the earlier one-service {@code compose up -d openbao} line
     * and the later Phase 7 line that starts the application.
     */
    private static String phaseSixDependenciesLine() throws IOException {
        List<String> lines = Files.readAllLines(SCRIPT, StandardCharsets.UTF_8);
        Optional<String> line = lines.stream()
                .filter(candidate -> candidate.startsWith("compose up -d "))
                .filter(candidate -> candidate.contains("platform-db") && candidate.contains("kafka"))
                .findFirst();
        assertThat(line)
                .as("expected exactly one `compose up -d ...` line starting platform-db and kafka "
                        + "together in Phase 6 of " + SCRIPT)
                .isPresent();
        return line.orElseThrow();
    }

    private static String readFile(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
