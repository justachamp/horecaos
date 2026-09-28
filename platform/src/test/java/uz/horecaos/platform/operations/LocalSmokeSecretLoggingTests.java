package uz.horecaos.platform.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code deploy/local-smoke.sh}'s "Provision a bucket-scoped media service account" step
 * used to fall back, on an extraction failure, to a {@code die} call that printed the
 * entire raw RustFS admin-API response body — which {@code warn()}/{@code die()} write to
 * both stderr and {@code LOG_FILE} via {@code tee -a}. If a future RustFS point release
 * returns the credential pair at a different JSON path (say, top-level {@code accessKey}/
 * {@code secretKey} instead of nested under {@code .credentials}), the request would still
 * succeed and the response would still hold a real, usable secret — this script would then
 * log that live secret to a file and to the terminal, which is exactly what "secrets are
 * references, never values in logs" (ADR 0028/0029) exists to prevent.
 *
 * <p>Sibling to {@link BackupScriptTests}: read the script's own source, since the point is
 * what the wording of a failure message is, not what a successful run does.
 */
class LocalSmokeSecretLoggingTests {

    private static final Path SCRIPT = Path.of("../deploy/local-smoke.sh");

    @Test
    @DisplayName("a media service-account extraction failure does not log the raw RustFS response body")
    void extractionFailureDoesNotLogTheRawResponseBody() throws IOException {
        String script = Files.readString(SCRIPT, StandardCharsets.UTF_8);

        assertThat(script)
                .as("die()/warn() write to both stderr and LOG_FILE (see their definitions near the"
                        + " top of the script); passing the raw admin-API response through them risks"
                        + " logging a real secretKey if RustFS's response shape ever drifts from the"
                        + " .credentials.accessKey/.credentials.secretKey path this script parses")
                .doesNotContain("Response: ${MEDIA_SVC_JSON}");
    }
}
