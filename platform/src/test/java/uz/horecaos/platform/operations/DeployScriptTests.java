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

    @Test
    @DisplayName("object-store-seed runs with a create-bucket-only credential, not the object store's root one")
    void objectStoreSeedRunsWithAScopedCredential() throws IOException {
        String compose = readFile(COMPOSE_FILE);
        String script = readFile(SCRIPT);

        assertThat(compose)
                .as("object-store-seed's AWS_ACCESS_KEY_ID must prefer the seed-scoped access key this "
                        + "script mints, falling back to the root one only for an ad hoc `docker compose "
                        + "up` outside this script")
                .contains(
                        "AWS_ACCESS_KEY_ID: ${HORECAOS_OBJECT_STORE_SEED_ACCESS_KEY:-${HORECAOS_OBJECT_STORE_ACCESS_KEY:-${HORECAOS_MINIO_ROOT_USER:?set a non-obvious root user name}}}")
                .as("and its secret must come from its own docker secret, not object-store-secret-key "
                        + "(the root one)")
                .contains("export AWS_SECRET_ACCESS_KEY=\"$$(cat /run/secrets/object-store-seed-secret-key)\"")
                .as("the seed-scoped secret file must be declared under HORECAOS_SECRET_DIR the same way "
                        + "every other secret in this file is")
                .contains("object-store-seed-secret-key:\n" + "    # The seed job's own create-bucket-only credential");

        assertThat(script)
                .as("deploy.sh must mint the seed-scoped service account against RustFS's admin API, "
                        + "the same call shape deploy/local-smoke.sh already proved against a real "
                        + "RustFS 1.0.0 container")
                .contains("/rustfs/admin/v3/add-service-account")
                .as("scoped to create-bucket/head-bucket/put-bucket-versioning only -- never s3:* the "
                        + "way the root credential effectively grants")
                .contains("\"s3:CreateBucket\",\"s3:HeadBucket\",\"s3:PutBucketVersioning\"")
                .as("and must export the access key and write the secret under the exact names "
                        + "compose.production.yaml reads")
                .contains("export HORECAOS_OBJECT_STORE_SEED_ACCESS_KEY=")
                .contains("${SECRET_DIR}/object-store-seed-secret-key")
                .as("object-store-seed itself must start only after the scoped credential exists, not "
                        + "alongside the other dependencies where it would still race the root fallback")
                .contains("compose up -d object-store-seed");

        int objectStoreHealthy = script.indexOf("Waiting for the object store to become healthy");
        int seedAccountMinted = script.indexOf("Provisioning a create-bucket-only service account");
        int seedJobStarted = script.lastIndexOf("compose up -d object-store-seed");
        int migrationsApplied = script.indexOf("Applying migrations");

        assertThat(objectStoreHealthy).as("the health wait must exist").isNotEqualTo(-1);
        assertThat(seedAccountMinted)
                .as("the seed account minting step must exist")
                .isNotEqualTo(-1);
        assertThat(seedJobStarted).as("the explicit seed job start must exist").isNotEqualTo(-1);

        assertThat(seedAccountMinted)
                .as("RustFS must be confirmed healthy before this script asks its admin API for anything")
                .isGreaterThan(objectStoreHealthy);
        assertThat(seedJobStarted)
                .as("the seed job must not start before it has a scoped credential to run with")
                .isGreaterThan(seedAccountMinted);
        assertThat(seedJobStarted)
                .as("bucket creation must finish, one way or another, before migrations run against a "
                        + "database whose application role depends on those buckets existing")
                .isLessThan(migrationsApplied);
    }

    @Test
    @DisplayName(
            "the backup service account is minted and its keys are written to OpenBao, not left for an operator to do by hand")
    void backupServiceAccountIsProvisionedAndStoredInOpenBao() throws IOException {
        String script = readFile(SCRIPT);

        assertThat(script)
                .as("the backup account's policy must name only the backup bucket, the same shape "
                        + "bootstrap.sh's own comment describes for the media account")
                .contains("Action:[\"s3:*\"],Resource:[(\"arn:aws:s3:::\"+$b),(\"arn:aws:s3:::\"+$b+\"/*\")]");

        assertThat(script)
                .contains("bao_put_value \"${OBJECT_STORE_BACKUP_ACCESS_PATH}\"")
                .contains("bao_put_value \"${OBJECT_STORE_BACKUP_SECRET_PATH}\"")
                .as("the paths must be the exact ones bootstrap.sh's own printed instructions named")
                .contains(
                        "OBJECT_STORE_BACKUP_ACCESS_PATH=\"horecaos/production/object_storage/platform/backup-access-key\"")
                .contains(
                        "OBJECT_STORE_BACKUP_SECRET_PATH=\"horecaos/production/object_storage/platform/backup-secret-key\"");

        assertThat(script)
                .as("the secret value must travel to OpenBao over stdin, never as a `docker compose "
                        + "exec` argument -- the same protection this file already gives OPERATOR_TOKEN, "
                        + "for the same reason (Phase 2's own comment: an argument sits in `ps` for as "
                        + "long as the exec runs)")
                .contains("bao_put_value() {")
                .contains("printf '%s\\n%s' \"${OPERATOR_TOKEN}\" \"${value}\"")
                .as("neither call site may pass the raw key value on the compose exec command line")
                .doesNotContain(
                        "compose exec -T openbao sh -c 'BAO_TOKEN=\"$(cat)\"; export BAO_TOKEN; \"$@\"' _ bao kv put "
                                + "\"${OBJECT_STORE_BACKUP_ACCESS_PATH}\"");
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
