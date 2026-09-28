package uz.horecaos.platform.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code deploy.md}'s "Create the buckets" step authenticates once, as the
 * backup-scoped credential (`production/object_storage/platform/backup-access-key` /
 * `backup-secret-key`), and uses that single credential to head-bucket/create-bucket
 * BOTH {@code horecaos-backups} and {@code horecaos-media}. The paragraph this repo
 * added just above it says to mint the scoped service accounts — one pair per bucket,
 * neither able to reach the other's bucket — before this step runs. By the time an
 * operator reaches "Create the buckets," the backup credential is scoped to {@code
 * horecaos-backups} only, so every {@code horecaos-media} call in this block gets
 * {@code AccessDenied} and the media bucket, and therefore the application (which
 * refuses to boot without it), never comes up.
 *
 * <p>This walks the "Create the buckets" script and checks that whichever credential
 * was most recently exported at each {@code --bucket} reference is the one scoped to
 * that bucket.
 */
class DeployRunbookBucketCredentialTests {

    private static final Path RUNBOOK = Path.of("docs/runbooks/deploy.md");

    private static final Pattern ACCESS_KEY_EXPORT =
            Pattern.compile("object_storage/platform/(media|backup)-access-key");

    private static final Pattern BUCKET_REFERENCE = Pattern.compile("--bucket (horecaos-media|horecaos-backups)");

    @Test
    @DisplayName("the 'Create the buckets' step uses each bucket's own scoped credential, never the other bucket's")
    void createTheBucketsStepUsesTheMatchingScopedCredentialPerBucket() throws IOException {
        String script = extractCreateTheBucketsScript();

        String currentCredential = null;
        for (String line : script.lines().toList()) {
            Matcher credential = ACCESS_KEY_EXPORT.matcher(line);
            if (credential.find()) {
                currentCredential = credential.group(1);
            }
            Matcher bucket = BUCKET_REFERENCE.matcher(line);
            while (bucket.find()) {
                String expected = bucket.group(1).equals("horecaos-media") ? "media" : "backup";
                assertThat(currentCredential)
                        .as(
                                "operating on %s must use the %s-scoped credential (the other pair gets"
                                        + " AccessDenied on this bucket), line: %s",
                                bucket.group(1), expected, line)
                        .isEqualTo(expected);
            }
        }
    }

    private static String extractCreateTheBucketsScript() throws IOException {
        String runbook = Files.readString(RUNBOOK, StandardCharsets.UTF_8);
        int heading = runbook.indexOf("### Create the buckets");
        assertThat(heading).as("the 'Create the buckets' section must exist").isPositive();
        int fenceStart = runbook.indexOf("```bash", heading);
        int codeStart = runbook.indexOf('\n', fenceStart) + 1;
        int fenceEnd = runbook.indexOf("```", codeStart);
        return runbook.substring(codeStart, fenceEnd);
    }

    @Test
    @DisplayName("both scoped credential pairs are read from OpenBao by the time buckets are created")
    void bothScopedPairsAreReferenced() throws IOException {
        String script = extractCreateTheBucketsScript();
        List<String> lines = script.lines().toList();

        assertThat(lines).anyMatch(line -> line.contains("media-access-key"));
        assertThat(lines).anyMatch(line -> line.contains("media-secret-key"));
        assertThat(lines).anyMatch(line -> line.contains("backup-access-key"));
        assertThat(lines).anyMatch(line -> line.contains("backup-secret-key"));
    }
}
