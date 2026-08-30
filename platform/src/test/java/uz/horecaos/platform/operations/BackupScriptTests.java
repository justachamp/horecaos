package uz.horecaos.platform.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The nightly backup refuses to produce a copy that never leaves the building
 * (ADR 0034).
 *
 * <p>This is asserted by running the script rather than by reading it, because
 * the defect it guards against was invisible in a reading: every line of
 * {@code backup.sh} said "upload", the README said "off-site", and the only
 * thing that was actually off-site was the local rehearsal. What matters is the
 * exit status a cron entry sees, so that is what is checked.
 *
 * <p>The guard runs before {@code pg_dump}, so these cases need no PostgreSQL,
 * no {@code mc} and no object store. The cases that do need all three are the
 * rehearsal's, and it is the rehearsal that proves the happy path.
 */
class BackupScriptTests {

    private static final Path SCRIPT = Path.of("infra/backup/backup.sh");

    /**
     * Enough to get past every other precondition, so that a failure here can
     * only be the off-site check. None of these is a real credential and none
     * reaches a network: the script exits before it opens a connection.
     */
    private static Map<String, String> configuredExceptOffsite() {
        Map<String, String> environment = new LinkedHashMap<>();
        environment.put("HORECAOS_BACKUP_DB_URL", "postgresql://unused@127.0.0.1:1/unused");
        environment.put("HORECAOS_BACKUP_PASSPHRASE", "unused-in-this-test");
        environment.put("HORECAOS_BACKUP_ACCESS_KEY", "unused-in-this-test");
        environment.put("HORECAOS_BACKUP_SECRET_KEY", "unused-in-this-test");
        environment.put("HORECAOS_BACKUP_S3_ENDPOINT", "http://minio:9000");
        return environment;
    }

    @Test
    @DisplayName("a backup with no off-site destination fails instead of keeping a local copy")
    void refusesToRunWithoutAnOffsiteDestination() throws Exception {
        Result result = run(configuredExceptOffsite());

        assertThat(result.exitCode())
                .as("cron records only the exit status, so a local-only backup must be a failure")
                .isEqualTo(1);
        assertThat(result.stderr())
                .contains("No off-site destination is configured")
                .contains("HORECAOS_BACKUP_OFFSITE_ENDPOINT")
                .contains("HORECAOS_BACKUP_OFFSITE_ACCESS_KEY")
                .contains("HORECAOS_BACKUP_OFFSITE_SECRET_KEY");
        assertThat(result.stdout())
                .as("nothing was dumped, so nothing was left on disk to clean up")
                .doesNotContain("==> Dumping");
    }

    @Test
    @DisplayName("a partly configured off-site destination is named, not silently ignored")
    void namesTheMissingHalfOfTheConfiguration() throws Exception {
        Map<String, String> environment = configuredExceptOffsite();
        environment.put("HORECAOS_BACKUP_OFFSITE_ENDPOINT", "https://s3.example.invalid");
        environment.put("HORECAOS_BACKUP_OFFSITE_ACCESS_KEY", "unused-in-this-test");

        Result result = run(environment);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr())
                .contains("HORECAOS_BACKUP_OFFSITE_SECRET_KEY")
                .doesNotContain("HORECAOS_BACKUP_OFFSITE_ENDPOINT");
    }

    @Test
    @DisplayName("a second bucket on the primary store is not an off-site destination")
    void refusesAnOffsiteEndpointThatIsThePrimary() throws Exception {
        Map<String, String> environment = configuredExceptOffsite();
        environment.put("HORECAOS_BACKUP_OFFSITE_ENDPOINT", environment.get("HORECAOS_BACKUP_S3_ENDPOINT"));
        environment.put("HORECAOS_BACKUP_OFFSITE_ACCESS_KEY", "unused-in-this-test");
        environment.put("HORECAOS_BACKUP_OFFSITE_SECRET_KEY", "unused-in-this-test");

        Result result = run(environment);

        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.stderr()).contains("shares the failure domain");
    }

    @Test
    @DisplayName("no destination, bucket or credential is defaulted in the script itself")
    void carriesNoOffsiteDefaults() throws IOException {
        String source = Files.readString(SCRIPT, StandardCharsets.UTF_8);

        // A default endpoint is how the previous version ended up shipping the
        // backup to the machine it was backing up, and a default credential is a
        // credential in a repository.
        assertThat(source)
                .doesNotContain("HORECAOS_BACKUP_OFFSITE_ENDPOINT:=")
                .doesNotContain("HORECAOS_BACKUP_OFFSITE_ENDPOINT:-")
                .doesNotContain("HORECAOS_BACKUP_OFFSITE_ACCESS_KEY:=")
                .doesNotContain("HORECAOS_BACKUP_OFFSITE_ACCESS_KEY:-")
                .doesNotContain("HORECAOS_BACKUP_OFFSITE_SECRET_KEY:=")
                .doesNotContain("HORECAOS_BACKUP_OFFSITE_SECRET_KEY:-");
    }

    private record Result(int exitCode, String stdout, String stderr) {}

    private static Result run(Map<String, String> environment) throws IOException, InterruptedException {
        Path out = Files.createTempFile("backup-stdout", ".log");
        Path err = Files.createTempFile("backup-stderr", ".log");
        try {
            ProcessBuilder builder = new ProcessBuilder("bash", SCRIPT.toString());
            builder.environment().keySet().removeIf(key -> key.startsWith("HORECAOS_BACKUP_"));
            builder.environment().putAll(environment);
            builder.redirectOutput(out.toFile());
            builder.redirectError(err.toFile());

            Process process = builder.start();
            assertThat(process.waitFor(60, TimeUnit.SECONDS))
                    .as("the off-site guard runs before anything that can block on a network")
                    .isTrue();

            return new Result(process.exitValue(),
                    Files.readString(out, StandardCharsets.UTF_8),
                    Files.readString(err, StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(out);
            Files.deleteIfExists(err);
        }
    }
}
