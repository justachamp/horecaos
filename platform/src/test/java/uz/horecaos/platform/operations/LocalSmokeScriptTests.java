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
    private static final Path COMPOSE_PRODUCTION_FILE = Path.of("..", "deploy", "compose.production.yml");

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

    @Test
    @DisplayName("the seed job's credential is minted as a create-bucket-only service account, not the root pair")
    void provisionsTheSeedCredentialAsAScopedServiceAccount() throws IOException {
        String script = readFile(SCRIPT);
        String compose = readFile(COMPOSE_PRODUCTION_FILE);

        assertThat(script)
                .as("local-smoke.sh must mint a seed-scoped account through RustFS's admin API, the "
                        + "same call shape already proved for the media account")
                .contains("Provisioning a create-bucket-only seed service account")
                .as("scoped to create-bucket/head-bucket/put-bucket-versioning on the three named "
                        + "buckets only -- never s3:* the way the root credential effectively grants")
                .contains("\"s3:CreateBucket\",\"s3:HeadBucket\",\"s3:PutBucketVersioning\"");

        assertThat(script)
                .as("the minted credential must be handed to object-store-seed under the exact names "
                        + "compose.production.yml reads, not left in a shell variable nothing consumes")
                .contains("export HORECAOS_OBJECT_STORE_SEED_ACCESS_KEY=")
                .contains("write_secret object-store-seed-secret-key");

        assertThat(compose)
                .as("compose.production.yml's object-store-seed must prefer the seed-scoped access key "
                        + "over the root one")
                .contains("HORECAOS_OBJECT_STORE_SEED_ACCESS_KEY")
                .contains("object-store-seed-secret-key");
    }

    @Test
    @DisplayName("the backup credential pair is minted and written to OpenBao, not left for an operator to do by hand")
    void provisionsTheBackupCredentialAsAScopedServiceAccount() throws IOException {
        String script = readFile(SCRIPT);

        assertThat(script)
                .as("production-setup.md's manual 'Then create the scoped service accounts' step for "
                        + "the backup bucket must now be scripted the same way the media account is")
                .contains("Provisioning the backup bucket's own service account")
                .as("scoped to the backup bucket alone, the same shape the media account's own policy "
                        + "already uses")
                .contains("Action:[\"s3:*\"],Resource:[(\"arn:aws:s3:::\"+$b),(\"arn:aws:s3:::\"+$b+\"/*\")]")
                .as("and the resulting pair must be written to the exact OpenBao paths bootstrap.sh's "
                        + "own printed instructions named")
                .contains("put object_storage/platform/backup-access-key \"${BACKUP_ACCESS_KEY}\"")
                .contains("put object_storage/platform/backup-secret-key \"${BACKUP_SECRET_KEY}\"");
    }

    @Test
    @DisplayName("every OpenBao value put() writes travels over stdin, never as a `docker compose exec` argument")
    void writesEveryPutValueOverStdinNotAsAComposeExecArgument() throws IOException {
        String script = readFile(SCRIPT);

        // infra/production/deploy.sh already carries this exact protection for
        // OPERATOR_TOKEN and its own backup-credential writes (see
        // DeployScriptTests#backupServiceAccountIsProvisionedAndStoredInOpenBao):
        // a value passed as a literal `docker compose exec` argument sits in
        // this host's own `ps` output for as long as the exec runs. Until this
        // fix, local-smoke.sh's put() forwarded every secret it writes --
        // including the BACKUP_ACCESS_KEY/BACKUP_SECRET_KEY this wave mints
        // from RustFS's admin API -- straight into `bao_run`'s "$@", which
        // lands as a literal argument the same way.
        assertThat(script)
                .as("local-smoke.sh must carry a stdin-based helper for writing KV values to "
                        + "OpenBao, mirroring infra/production/deploy.sh's own bao_put_value")
                .contains("bao_put_value() {")
                .as("the value must travel over stdin behind the root token, never as a `bao kv "
                        + "put ... value=...` argument")
                .contains("printf '%s\\n%s' \"${ROOT_TOKEN}\" \"${value}\"");

        assertThat(script)
                .as("put() -- and therefore every secret value it writes, including the backup "
                        + "service-account keys -- must route through the stdin-based helper, not "
                        + "embed the raw value as a literal argument on the compose exec command line")
                .contains("put() { bao_put_value \"horecaos/${ENVIRONMENT}/$1\" \"$2\"")
                .as("the old argv-based form (the value as a literal `bao kv put ... value=$2` "
                        + "argument to bao_run) must be gone, not left alongside the fix as a second "
                        + "path that still leaks the value")
                .doesNotContain("bao_run bao kv put \"horecaos/${ENVIRONMENT}/$1\" \"value=$2\"");
    }

    @Test
    @DisplayName(
            "the seed and backup accounts are provisioned after object-store is healthy and before the seed job (or the application) starts")
    void provisionsTheSeedAndBackupAccountsInOrder() throws IOException {
        String script = readFile(SCRIPT);

        int objectStoreHealthy = script.indexOf("wait_healthy object-store 60");
        int mediaAccountCreated = script.indexOf("check \"media service account provisioned");
        int seedAccountCreated = script.indexOf("check \"seed service account provisioned");
        int backupAccountCreated = script.indexOf("check \"backup service account provisioned");
        int platformAppStart = script.indexOf("compose up -d platform-app");

        assertThat(objectStoreHealthy).isNotEqualTo(-1);
        assertThat(mediaAccountCreated).isNotEqualTo(-1);
        assertThat(seedAccountCreated).isNotEqualTo(-1);
        assertThat(backupAccountCreated).isNotEqualTo(-1);
        assertThat(platformAppStart).isNotEqualTo(-1);

        assertThat(seedAccountCreated)
                .as("RustFS must be confirmed healthy before any of these admin-API calls run")
                .isGreaterThan(objectStoreHealthy);
        assertThat(backupAccountCreated)
                .as("the backup account follows the seed account, matching the order the script names " + "them in")
                .isGreaterThan(seedAccountCreated);
        assertThat(seedAccountCreated)
                .as("the seed-scoped credential must exist before object-store-seed's own implicit "
                        + "start (pulled in by platform-app's own depends_on), or it would fall back to "
                        + "the root credential the same way it did before this closed the gap")
                .isLessThan(platformAppStart);
        assertThat(backupAccountCreated).isLessThan(platformAppStart);
    }

    private static String readFile(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
