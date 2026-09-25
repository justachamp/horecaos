package uz.horecaos.platform.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The local production proof seeds OpenBao with the object-store access key
 * an operator actually configured, not a name deploy/env.local-test stopped
 * setting (ADR 0135).
 *
 * <p>deploy/env.local-test was migrated from {@code HORECAOS_MINIO_ROOT_USER}
 * to {@code HORECAOS_OBJECT_STORE_ACCESS_KEY}, but deploy/local-smoke.sh kept
 * reading the retired name when seeding
 * {@code object_storage/platform/media-access-key}. Because
 * {@code HORECAOS_MINIO_ROOT_USER} is never set any more, that read always
 * fell through to the script's own hardcoded fallback literal, silently
 * ignoring whatever access key {@code deploy/env.local-test} actually names.
 * RustFS itself starts with the real (possibly different) access key — {@code
 * compose.production.yml} prefers {@code HORECAOS_OBJECT_STORE_ACCESS_KEY} —
 * so the application, which reads its media credential back from OpenBao,
 * would authenticate with the wrong key and every media S3 call in the smoke
 * run would fail with 403, looking unrelated to the credential that caused
 * it.
 *
 * <p>Asserted against the script text, the same way {@link BackupScriptTests}
 * pins backup.sh and {@link DeployScriptTests} pins deploy.sh: local-smoke.sh
 * is a full Docker/OpenBao integration script with no unit seam around this
 * one substitution, so the defect is only visible in what the script itself
 * says to read.
 */
class LocalSmokeScriptTests {

    private static final Path SCRIPT = Path.of("..", "deploy", "local-smoke.sh");
    private static final Path ENV_FILE = Path.of("..", "deploy", "env.local-test");

    @Test
    @DisplayName("the media-access-key seed reads the access key env.local-test actually sets")
    void seedsTheMediaAccessKeyFromTheConfiguredVariable() throws IOException {
        String env = readFile(ENV_FILE);
        String script = readFile(SCRIPT);

        assertThat(env)
                .as("env.local-test must still be the file that sets the object-store access "
                        + "key for the smoke stack")
                .contains("HORECAOS_OBJECT_STORE_ACCESS_KEY=horecaos-smoke-root")
                .as("and must not have re-introduced the retired variable name")
                .doesNotContain("HORECAOS_MINIO_ROOT_USER=");

        assertThat(script)
                .as("local-smoke.sh must seed OpenBao's media-access-key from "
                        + "HORECAOS_OBJECT_STORE_ACCESS_KEY -- the variable env.local-test "
                        + "actually sets -- not from HORECAOS_MINIO_ROOT_USER, which is never "
                        + "set and so always falls through to the hardcoded fallback")
                .contains("put object_storage/platform/media-access-key  "
                        + "\"${HORECAOS_OBJECT_STORE_ACCESS_KEY:-horecaos-smoke-root}\"")
                .doesNotContain("HORECAOS_MINIO_ROOT_USER");
    }

    private static String readFile(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
