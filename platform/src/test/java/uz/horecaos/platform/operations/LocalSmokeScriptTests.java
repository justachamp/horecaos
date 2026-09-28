package uz.horecaos.platform.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The local production proof provisions the media credential as a
 * bucket-scoped RustFS service account, not the object store's own root
 * credential (ADR 0135, checklist item 3).
 *
 * <p>Until 2026-09-25 {@code deploy/local-smoke.sh} seeded
 * {@code object_storage/platform/media-access-key} /
 * {@code media-secret-key} in OpenBao directly from
 * {@code HORECAOS_OBJECT_STORE_ACCESS_KEY} and the object store's own root
 * password — the same root pair {@code RUSTFS_ACCESS_KEY}/{@code
 * RUSTFS_SECRET_KEY} start the object store itself with. The script's own
 * comment at the time called this out as a corner production-setup.md did
 * not cut: with the root credential, the application can reach every
 * bucket, including the backups the media code has no legitimate reason to
 * touch. RustFS 1.0.0 turns out to expose a long-lived, policy-scoped
 * service account through its own admin API — {@code PUT
 * /rustfs/admin/v3/add-service-account}, SigV4-signed — verified 2026-09-25
 * against a running instance. This closes the gap the smoke script's
 * comment used to name: the media credential the application actually
 * receives is now minted by that call, scoped to
 * {@code horecaos-media} only, not read back from the root pair.
 *
 * <p>Asserted against the script text, the same way {@link BackupScriptTests}
 * pins backup.sh and {@link DeployScriptTests} pins deploy.sh: local-smoke.sh
 * is a full Docker/OpenBao integration script with no unit seam around this
 * substitution, so the defect (and its fix) is only visible in what the
 * script itself says to do. The admin-API call shape itself was verified
 * directly against a running RustFS 1.0.0 container and the real {@code ops}
 * image (curl 8.x, which has carried {@code --aws-sigv4} since 7.75) before
 * this test was written — see ADR 0135's checklist item 3 and
 * docs/runbooks/production-setup.md, "Then create the scoped service
 * accounts", for the same call run by hand against a real host.
 */
class LocalSmokeScriptTests {

    private static final Path SCRIPT = Path.of("..", "deploy", "local-smoke.sh");
    private static final Path ENV_FILE = Path.of("..", "deploy", "env.local-test");

    @Test
    @DisplayName("the object-store access key env.local-test sets is still what the root admin call authenticates with")
    void readsTheRootAccessKeyFromTheConfiguredVariable() throws IOException {
        String env = readFile(ENV_FILE);
        String script = readFile(SCRIPT);

        assertThat(env)
                .as("env.local-test must still be the file that sets the object-store access "
                        + "key for the smoke stack")
                .contains("HORECAOS_OBJECT_STORE_ACCESS_KEY=horecaos-smoke-root")
                .as("and must not have re-introduced the retired variable name")
                .doesNotContain("HORECAOS_MINIO_ROOT_USER=");

        assertThat(script)
                .as("the root access key still has to reach the container that calls RustFS's "
                        + "admin API to mint the scoped service account")
                .contains("OBJECT_STORE_ROOT_ACCESS_KEY=\"${HORECAOS_OBJECT_STORE_ACCESS_KEY:-horecaos-smoke-root}\"");
    }

    @Test
    @DisplayName(
            "the media credential is minted as a bucket-scoped RustFS service account, not read from the root pair")
    void provisionsTheMediaCredentialAsAScopedServiceAccount() throws IOException {
        String script = readFile(SCRIPT);

        assertThat(script)
                .as("local-smoke.sh must call RustFS's own admin API to create a service account "
                        + "-- there is no mc-shaped `admin user add`/`policy attach` on this store "
                        + "(ADR 0135) -- rather than assume the media credential is the root pair")
                .contains("/rustfs/admin/v3/add-service-account")
                .as("the call must be SigV4-signed the same way every other request against this "
                        + "store is, not an unauthenticated or differently-authenticated request")
                .contains("--aws-sigv4")
                .as("the service account's embedded policy must name the media bucket specifically, "
                        + "not grant access to every bucket the way the root credential does")
                .contains("Resource:[(\"arn:aws:s3:::\"+$b),(\"arn:aws:s3:::\"+$b+\"/*\")]");

        assertThat(script)
                .as("the media-access-key/media-secret-key OpenBao values must come from the "
                        + "service account this script just created, not from the object store's "
                        + "own root pair -- the exact defect ADR 0135's checklist item 3 tracked")
                .contains("put object_storage/platform/media-access-key \"${MEDIA_ACCESS_KEY}\"")
                .contains("put object_storage/platform/media-secret-key \"${MEDIA_SECRET_KEY}\"")
                .as("and the old direct root-credential seed must be gone, not left alongside the "
                        + "new call as a second, later write that would silently win")
                .doesNotContain(
                        "put object_storage/platform/media-access-key  \"${HORECAOS_OBJECT_STORE_ACCESS_KEY:-horecaos-smoke-root}\"")
                .doesNotContain("put object_storage/platform/media-secret-key  \"${OBJECT_STORE_ROOT_PW}\"");
    }

    @Test
    @DisplayName("the service account is provisioned after object-store is healthy and before the application needs it")
    void provisionsTheServiceAccountAfterObjectStoreIsHealthyAndBeforeTheApplicationStarts() throws IOException {
        String script = readFile(SCRIPT);

        int objectStoreHealthy = script.indexOf("wait_healthy object-store 60");
        int serviceAccountCall = script.indexOf("/rustfs/admin/v3/add-service-account");
        int platformAppStart = script.indexOf("compose up -d platform-app");

        assertThat(objectStoreHealthy)
                .as("wait_healthy object-store 60 must exist in the script")
                .isNotEqualTo(-1);
        assertThat(serviceAccountCall)
                .as("the add-service-account call must exist in the script")
                .isNotEqualTo(-1);
        assertThat(platformAppStart)
                .as("compose up -d platform-app must exist in the script")
                .isNotEqualTo(-1);

        assertThat(serviceAccountCall)
                .as("RustFS must be confirmed healthy before this script asks its admin API for "
                        + "anything -- calling it earlier would race the container's own startup")
                .isGreaterThan(objectStoreHealthy);
        assertThat(serviceAccountCall)
                .as("the scoped credential must exist in OpenBao before platform-app starts and "
                        + "resolves its HORECAOS_MEDIA_ACCESS_KEY_REF, or the application would "
                        + "start with no media credential at all")
                .isLessThan(platformAppStart);
    }

    private static String readFile(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
