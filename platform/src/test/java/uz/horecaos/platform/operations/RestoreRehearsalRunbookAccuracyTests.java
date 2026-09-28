package uz.horecaos.platform.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code production-setup.md}'s "Monthly restore rehearsal" section used to claim that
 * running {@code rehearse-restore.sh} restores "a production backup object," "pointed at
 * a production backup object by bucket/prefix." Neither claim is true: {@code
 * rehearse-restore.sh} always dumps a fresh, freshly timestamped copy of whatever
 * database it is pointed at ({@code OBJECT} is generated from the current time, never
 * read from an argument, environment variable, bucket listing or prefix), uploads that
 * fresh dump, and restores it — the object it restores was never anything but the one it
 * just created.
 *
 * <p>An operator following the runbook literally would believe a monthly "REHEARSAL
 * PASSED" proves a real, previously-taken production backup is recoverable. It only
 * proves the dump/encrypt/upload/download/decrypt/restore mechanism works end to end on
 * a fresh snapshot — which is exactly what {@code infra/backup/README.md}'s own "What is
 * proven and what is not" section says, and what this runbook must say too.
 */
class RestoreRehearsalRunbookAccuracyTests {

    private static final Path RUNBOOK = Path.of("docs/runbooks/production-setup.md");
    private static final Path REHEARSAL_SCRIPT = Path.of("infra/backup/rehearse-restore.sh");

    @Test
    @DisplayName("rehearse-restore.sh has no way to select an existing backup object")
    void rehearsalScriptCannotTargetAnExistingBackupObject() throws IOException {
        String script = Files.readString(REHEARSAL_SCRIPT, StandardCharsets.UTF_8);

        // OBJECT is always a freshly generated, timestamped name — never read from an
        // argument, an environment variable, a bucket listing or a prefix. If this ever
        // changes, the runbook's claim would stop being false and this guard should be
        // revisited alongside it.
        assertThat(script).contains("OBJECT=\"horecaos-$(date -u +%Y%m%dT%H%M%SZ).dump.enc\"");
        assertThat(script).doesNotContain("${1");
        assertThat(script).doesNotContain("HORECAOS_REHEARSAL_OBJECT");
        assertThat(script).doesNotContain("list-objects");
    }

    @Test
    @DisplayName("the monthly restore rehearsal runbook does not claim to restore an existing production backup object")
    void runbookDoesNotOverclaimWhatTheRehearsalProves() throws IOException {
        String runbook = Files.readString(RUNBOOK, StandardCharsets.UTF_8);
        // Collapse whitespace so a claim spread across a wrapped markdown line is not
        // missed just because of where the line happens to break.
        String normalized = runbook.replaceAll("\\s+", " ");

        assertThat(normalized)
                .as("rehearse-restore.sh always dumps a fresh snapshot of whatever database it "
                        + "connects to; it cannot be pointed at an existing production backup object "
                        + "by bucket or prefix, so the runbook must not claim it does")
                .doesNotContain("restoring a **production** backup object")
                .doesNotContain("pointed at a production backup object by bucket/prefix")
                .doesNotContain("restores a **production** backup object onto staging");
    }
}
