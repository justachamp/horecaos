package uz.horecaos.platform.operations;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @Test
    @DisplayName("the media service account is minted bucket-scoped and stored in OpenBao, not left to bootstrap")
    void mediaServiceAccountIsProvisionedAndStoredInOpenBao() throws IOException {
        String script = readFile(SCRIPT);
        String media = mediaBlock(script);

        assertThat(media)
                .as("the media account's policy names only the media bucket and its objects -- the "
                        + "application must not be able to reach the backup bucket")
                .contains("--arg b \"${HORECAOS_MEDIA_BUCKET}\"")
                .contains("Action:[\"s3:*\"],Resource:[(\"arn:aws:s3:::\"+$b),(\"arn:aws:s3:::\"+$b+\"/*\")]")
                .doesNotContain("HORECAOS_BACKUP_BUCKET")
                .doesNotContain("HORECAOS_AUDIT_ARCHIVE_BUCKET")
                .as("minted through RustFS's admin API, through the ops container that already carries "
                        + "the root credential, so this script's own shell never holds it")
                .contains("/rustfs/admin/v3/add-service-account")
                .contains("compose run --rm --no-TTY ops bash -c \"${MEDIA_SVC_ACCOUNT_SCRIPT}\"")
                .as("written to OpenBao over stdin, both halves")
                .contains("bao_put_value \"${OBJECT_STORE_MEDIA_ACCESS_PATH}\" \"${MEDIA_ACCESS_KEY}\"")
                .contains("bao_put_value \"${OBJECT_STORE_MEDIA_SECRET_PATH}\" \"${MEDIA_SECRET_KEY}\"");

        assertThat(script)
                .as("the paths must be the exact references compose.production.yaml gives platform-app")
                .contains(
                        "OBJECT_STORE_MEDIA_ACCESS_PATH=\"horecaos/production/object_storage/platform/media-access-key\"")
                .contains(
                        "OBJECT_STORE_MEDIA_SECRET_PATH=\"horecaos/production/object_storage/platform/media-secret-key\"");
        assertThat(readFile(COMPOSE_FILE))
                .contains("HORECAOS_MEDIA_ACCESS_KEY_REF: horecaos:production:object_storage:platform:media-access-key")
                .contains("HORECAOS_MEDIA_SECRET_KEY_REF: horecaos:production:object_storage:platform:media-secret-key")
                .as("the ops container must know the media bucket name the policy is built from")
                .contains("HORECAOS_MEDIA_BUCKET: ${HORECAOS_MEDIA_BUCKET:-horecaos-media}");
    }

    @Test
    @DisplayName("the media pair exists in OpenBao before the application that resolves it starts")
    void mediaAccountIsMintedAfterTheObjectStoreIsHealthyAndBeforeTheApplicationStarts() throws IOException {
        String script = readFile(SCRIPT);

        int objectStoreHealthy = script.indexOf("Waiting for the object store to become healthy");
        int backupMinted = script.indexOf("Provisioning the backup bucket's own service account");
        int mediaMinted = script.indexOf("Provisioning the media bucket's own service account");
        int applicationStarted = script.indexOf("compose up -d keycloak platform-app");

        assertThat(mediaMinted).as("the media minting step must exist").isNotEqualTo(-1);
        assertThat(mediaMinted)
                .as("RustFS must be healthy before this script asks its admin API for anything")
                .isGreaterThan(objectStoreHealthy);
        assertThat(mediaMinted)
                .as("it follows the backup pair it mirrors, inside the same phase")
                .isGreaterThan(backupMinted);
        assertThat(applicationStarted).isNotEqualTo(-1);
        assertThat(mediaMinted)
                .as("platform-app fails to build its S3 client without these two values "
                        + "(\"No secret is configured for ...media-access-key\"), so they come first")
                .isLessThan(applicationStarted);
    }

    @Test
    @DisplayName("minting is once-only unless the operator asks for a remint, for the media pair and the backup pair")
    void mintingIsIdempotentUnlessTheOperatorAsksForARemint() throws IOException {
        String script = readFile(SCRIPT);
        String media = mediaBlock(script);

        assertThat(script)
                .as("the switch is read from the environment with a safe default of off")
                .contains("REMINT_OBJECT_STORE_CREDENTIALS=\"${HORECAOS_REMINT_OBJECT_STORE_CREDENTIALS:-0}\"");
        assertThat(media)
                .as("both halves of the media pair are checked, not only the access key -- a lone half "
                        + "is a failed earlier run")
                .contains("bao_field \"${OBJECT_STORE_MEDIA_ACCESS_PATH}\"")
                .contains("bao_field \"${OBJECT_STORE_MEDIA_SECRET_PATH}\"")
                .contains("\"${REMINT_OBJECT_STORE_CREDENTIALS}\" != \"1\"");
        assertThat(script)
                .as("the backup pair honours the same switch (migration step 6 re-mints both)")
                .contains("if [ \"${REMINT_OBJECT_STORE_CREDENTIALS}\" != \"1\" ] \\\n"
                        + "    && bao_field \"${OBJECT_STORE_BACKUP_ACCESS_PATH}\"");
    }

    @Test
    @DisplayName("no line of the media block can write a minted key or the admin response to the terminal")
    void noMediaSecretIsEverPrintedByTheScript() throws IOException {
        String script = readFile(SCRIPT);
        String media = mediaBlock(script);

        for (String line : media.lines().toList()) {
            // `printf '%s' "${MEDIA_SVC_JSON}" | jq ...` feeds a pipe, not the terminal; every other
            // printf, and any say/warn/die/echo, writes where an operator (or a log shipper) can read.
            boolean feedsJq = line.contains("printf '%s'") && line.contains("| jq ");
            boolean prints = !feedsJq
                    && (line.contains("say ")
                            || line.contains("warn ")
                            || line.contains("die ")
                            || line.contains("echo ")
                            || line.contains("printf "));
            if (!prints) {
                continue;
            }
            assertThat(line)
                    .as("a line that prints must not interpolate a credential or the raw response: %s", line)
                    .doesNotContain("${MEDIA_SECRET_KEY}")
                    .doesNotContain("${MEDIA_ACCESS_KEY}")
                    .doesNotContain("${MEDIA_SVC_JSON}");
        }
        assertThat(script)
                .as("xtrace would print every expansion")
                .doesNotContain("set -x")
                .doesNotContain("set -o xtrace");
        assertThat(media)
                .as("the credentials are dropped from the shell as soon as they are stored")
                .contains("unset MEDIA_SVC_ACCOUNT_SCRIPT MEDIA_SVC_JSON MEDIA_ACCESS_KEY MEDIA_SECRET_KEY");
    }

    @Test
    @DisplayName("bootstrap.sh no longer tells the operator to create the media pair by hand")
    void bootstrapNoLongerAsksForAHandMadeMediaPair() throws IOException {
        String bootstrap = readFile(Path.of("infra/production/bootstrap.sh"));

        int byHandStart = bootstrap.indexOf("have to be created or copied by hand:");
        int scriptedStart = bootstrap.indexOf("The object store's own credentials are not on that list on purpose");
        assertThat(byHandStart).isNotEqualTo(-1);
        assertThat(scriptedStart).as("the note explaining who mints them").isGreaterThan(byHandStart);

        assertThat(bootstrap.substring(byHandStart, scriptedStart))
                .as("the hand-made list is the KEK and the Keycloak client secrets only")
                .doesNotContain("media-access-key")
                .doesNotContain("media-secret-key");
        assertThat(bootstrap.substring(scriptedStart))
                .contains("infra/production/deploy.sh")
                .contains("media-access-key")
                .contains("media-secret-key");
    }

    @Test
    @DisplayName("run for real, the media block mints a scoped account once and keeps every secret off the terminal")
    void theMediaBlockBehavesWhenExecuted(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(commandExists("jq") && commandExists("bash"), "jq and bash are required");
        String freshResponse =
                "{\"credentials\":{\"accessKey\":\"MEDIAACCESSCANARY\",\"secretKey\":\"media-secret-canary-0123456789\"}}";

        // 1. First deploy: OpenBao holds nothing.
        Fixture first = new Fixture(dir.resolve("first"), freshResponse, false);
        Run minted = first.run();
        assertThat(minted.exitCode()).as(minted.output()).isZero();
        assertThat(first.trace().stream().map(line -> line.startsWith("CURL") ? "CURL" : line))
                .as("one admin call, then the access key, then the secret key")
                .containsExactly(
                        "CURL",
                        "PUT horecaos/production/object_storage/platform/media-access-key",
                        "PUT horecaos/production/object_storage/platform/media-secret-key");
        assertThat(first.stored("media-access-key")).isEqualTo("MEDIAACCESSCANARY");
        assertThat(first.stored("media-secret-key")).isEqualTo("media-secret-canary-0123456789");
        assertThat(minted.output())
                .as("neither the minted keys nor the root secret may reach the terminal")
                .doesNotContain("MEDIAACCESSCANARY")
                .doesNotContain("media-secret-canary")
                .doesNotContain(Fixture.ROOT_SECRET);

        JsonNode request = first.requestBody();
        assertThat(request.path("name").asText()).isEqualTo("media-production");
        JsonNode statement = request.path("policy").path("Statement").get(0);
        assertThat(statement.path("Effect").asText()).isEqualTo("Allow");
        assertThat(statement.path("Action")).hasSize(1);
        assertThat(statement.path("Action").get(0).asText()).isEqualTo("s3:*");
        assertThat(List.of(
                        statement.path("Resource").get(0).asText(),
                        statement.path("Resource").get(1).asText()))
                .containsExactly("arn:aws:s3:::horecaos-media", "arn:aws:s3:::horecaos-media/*");
        assertThat(statement.path("Resource")).hasSize(2);
        assertThat(first.curlLine())
                .contains("/rustfs/admin/v3/add-service-account")
                .contains("--aws-sigv4");

        // 2. Second deploy: the pair is there, so nothing is minted and nothing is overwritten.
        Fixture second = new Fixture(dir.resolve("second"), freshResponse, false);
        second.store("media-access-key", "EXISTINGACCESS");
        second.store("media-secret-key", "existing-secret");
        Run kept = second.run();
        assertThat(kept.exitCode()).as(kept.output()).isZero();
        assertThat(second.trace()).noneMatch(line -> line.startsWith("CURL") || line.startsWith("PUT"));
        assertThat(second.stored("media-access-key")).isEqualTo("EXISTINGACCESS");
        assertThat(kept.output()).contains("already provisioned");

        // 3. The operator asks for a remint: a new pair replaces the old one.
        Fixture remint = new Fixture(dir.resolve("remint"), freshResponse, true);
        remint.store("media-access-key", "MINIO-ERA-ACCESS");
        remint.store("media-secret-key", "minio-era-secret");
        Run reminted = remint.run();
        assertThat(reminted.exitCode()).as(reminted.output()).isZero();
        assertThat(remint.stored("media-access-key")).isEqualTo("MEDIAACCESSCANARY");
        assertThat(remint.stored("media-secret-key")).isEqualTo("media-secret-canary-0123456789");
        assertThat(reminted.output()).doesNotContain("MINIO-ERA").doesNotContain("media-secret-canary");

        // 4. Half a pair is a failed earlier run: warn, then replace both.
        Fixture half = new Fixture(dir.resolve("half"), freshResponse, false);
        half.store("media-access-key", "LONELYACCESS");
        Run repaired = half.run();
        assertThat(repaired.exitCode()).as(repaired.output()).isZero();
        assertThat(repaired.output()).contains("only one half");
        assertThat(half.stored("media-secret-key")).isEqualTo("media-secret-canary-0123456789");
        assertThat(repaired.output()).doesNotContain("LONELYACCESS");
    }

    @Test
    @DisplayName("a media response in an unexpected shape stops the deploy without echoing the body")
    void anUnexpectedAdminResponseIsNeverEchoed(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(commandExists("jq") && commandExists("bash"), "jq and bash are required");
        // The drift LocalSmokeSecretLoggingTests guards against: credentials one level up. The request
        // still succeeded, so the body holds a live secret.
        Fixture drifted = new Fixture(
                dir.resolve("drifted"),
                "{\"accessKey\":\"DRIFTACCESSCANARY\",\"secretKey\":\"drift-secret-canary-987654321\"}",
                false);

        Run run = drifted.run();

        assertThat(run.exitCode()).isNotZero();
        assertThat(run.output()).contains("did not return a service-account access key/secret for the media account");
        assertThat(run.output()).doesNotContain("DRIFTACCESSCANARY").doesNotContain("drift-secret-canary");
        assertThat(drifted.trace()).as("nothing may be written to OpenBao").noneMatch(line -> line.startsWith("PUT"));

        Fixture refused = new Fixture(dir.resolve("refused"), "{}", false);
        refused.curlExitCode(22);
        Run failed = refused.run();
        assertThat(failed.exitCode()).isNotZero();
        assertThat(failed.output()).contains("Could not create the media service account");
        assertThat(failed.output()).doesNotContain(Fixture.ROOT_SECRET);
        assertThat(refused.trace()).noneMatch(line -> line.startsWith("PUT"));
    }

    @Test
    @DisplayName("the operator token is proven able to store the keys before any service account is minted")
    void theTokenIsCheckedBeforeAnythingIsMinted() throws IOException {
        String script = readFile(SCRIPT);

        int preflightCall = script.indexOf("\nrequire_write_access_before_minting\n");
        int seedMinted = script.indexOf("say \"Provisioning a create-bucket-only service account");
        int backupMinted = script.indexOf("say \"Provisioning the backup bucket's own service account");
        int mediaMinted = script.indexOf("say \"Provisioning the media bucket's own service account");

        assertThat(script)
                .as("the check asks OpenBao what this very token may do -- a read-only deploy token "
                        + "must be found out here, not after RustFS has minted an account nobody can record")
                .contains("bao_run bao token capabilities");
        assertThat(preflightCall)
                .as("the preflight must be called by name on a line of its own")
                .isNotEqualTo(-1);
        assertThat(preflightCall)
                .as("no add-service-account call may precede it, the seed account's included")
                .isLessThan(seedMinted);
        assertThat(seedMinted).isLessThan(backupMinted);
        assertThat(backupMinted).isLessThan(mediaMinted);
        assertThat(script.substring(0, preflightCall))
                .as("the first mint is what the preflight guards against")
                .doesNotContain("/rustfs/admin/v3/add-service-account\" \\");
        assertThat(script)
                .as("KV v2 authorises the data/ path, not the logical one bao kv put is given")
                .contains("horecaos/data/${");
    }

    @Test
    @DisplayName("a token that cannot store the keys stops the deploy before RustFS is asked for anything")
    void aReadOnlyTokenIsRefusedBeforeAnAccountIsMinted(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(commandExists("jq") && commandExists("bash"), "jq and bash are required");
        String response =
                "{\"credentials\":{\"accessKey\":\"PREFLIGHTACCESSCANARY\",\"secretKey\":\"preflight-secret-canary\"}}";

        // The horecaos-deploy policy as shipped: read and list, nothing more.
        Fixture readOnly = new Fixture(dir.resolve("read-only"), response, false);
        readOnly.capabilities("list, read");
        Run refused = readOnly.runWithPreflight();
        assertThat(refused.exitCode()).as(refused.output()).isNotZero();
        assertThat(refused.output())
                .contains("cannot store")
                .contains("horecaos/production/object_storage/platform/media-access-key")
                .contains("horecaos/production/object_storage/platform/backup-secret-key")
                .doesNotContain("PREFLIGHTACCESSCANARY")
                .doesNotContain(Fixture.ROOT_SECRET);
        assertThat(readOnly.trace())
                .as("nothing was minted and nothing was written")
                .noneMatch(line -> line.startsWith("CURL") || line.startsWith("PUT"));
        assertThat(readOnly.trace())
                .as("the KV v2 data/ paths are the ones asked about")
                .contains(
                        "CAPS horecaos/data/production/object_storage/platform/media-access-key",
                        "CAPS horecaos/data/production/object_storage/platform/backup-access-key");

        // A token that can create but not update fails a remint, which overwrites.
        Fixture createOnly = new Fixture(dir.resolve("create-only"), response, true);
        createOnly.capabilities("create, read");
        createOnly.store("media-access-key", "EXISTING");
        createOnly.store("media-secret-key", "existing");
        createOnly.store("backup-access-key", "EXISTING");
        Run half = createOnly.runWithPreflight();
        assertThat(half.exitCode()).as(half.output()).isNotZero();
        assertThat(createOnly.trace()).noneMatch(line -> line.startsWith("CURL"));

        // If OpenBao cannot even say what the token may do, the answer is no.
        Fixture unknown = new Fixture(dir.resolve("unknown"), response, false);
        unknown.capabilitiesCallFails();
        Run unanswered = unknown.runWithPreflight();
        assertThat(unanswered.exitCode()).as(unanswered.output()).isNotZero();
        assertThat(unknown.trace()).noneMatch(line -> line.startsWith("CURL"));
    }

    @Test
    @DisplayName("a token that can store the keys, or a deploy with nothing to mint, goes through")
    void aTokenThatCanStoreTheKeysOrHasNothingToMintIsNotStopped(@TempDir Path dir) throws Exception {
        Assumptions.assumeTrue(commandExists("jq") && commandExists("bash"), "jq and bash are required");
        String response = "{\"credentials\":{\"accessKey\":\"OKACCESS\",\"secretKey\":\"ok-secret\"}}";

        Fixture writer = new Fixture(dir.resolve("writer"), response, false);
        writer.capabilities("create, read, update");
        Run minted = writer.runWithPreflight();
        assertThat(minted.exitCode()).as(minted.output()).isZero();
        assertThat(writer.stored("media-access-key")).isEqualTo("OKACCESS");

        Fixture root = new Fixture(dir.resolve("root"), response, false);
        root.capabilities("root");
        Run rooted = root.runWithPreflight();
        assertThat(rooted.exitCode()).as(rooted.output()).isZero();

        // Routine deploy with a read-only deploy token: both pairs are on file, so no write is needed,
        // and the operator who carries only horecaos-deploy must not be locked out of deploying.
        Fixture routine = new Fixture(dir.resolve("routine"), response, false);
        routine.capabilities("list, read");
        for (String name : List.of("media-access-key", "media-secret-key", "backup-access-key", "backup-secret-key")) {
            routine.store(name, "existing-" + name);
        }
        Run kept = routine.runWithPreflight();
        assertThat(kept.exitCode()).as(kept.output()).isZero();
        assertThat(routine.trace()).noneMatch(line -> line.startsWith("CURL") || line.startsWith("PUT"));

        // Only the media pair is missing: only the media paths are held to the rule.
        Fixture mediaOnly = new Fixture(dir.resolve("media-only"), response, false);
        mediaOnly.capabilities("list, read");
        mediaOnly.store("backup-access-key", "existing");
        mediaOnly.store("backup-secret-key", "existing");
        Run refusedMedia = mediaOnly.runWithPreflight();
        assertThat(refusedMedia.exitCode()).as(refusedMedia.output()).isNotZero();
        assertThat(refusedMedia.output())
                .contains("media-access-key")
                .doesNotContain("backup-access-key")
                .doesNotContain("backup-secret-key");
    }

    /** Everything from the media step's own `say` up to the seed job's, the text this test class pins. */
    private static String mediaBlock(String script) {
        int start = script.indexOf("say \"Provisioning the media bucket's own service account\"");
        int end = script.indexOf("say \"Starting the bucket-creation seed job");
        assertThat(start).as("the media minting step must exist").isNotEqualTo(-1);
        assertThat(end).as("the seed job start follows it").isGreaterThan(start);
        return script.substring(start, end);
    }

    private static boolean commandExists(String command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder("bash", "-c", "command -v " + command)
                .redirectErrorStream(true)
                .start();
        process.getInputStream().readAllBytes();
        return process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0;
    }

    private record Run(int exitCode, String output) {}

    /**
     * Runs deploy.sh's real media block under stubs for the three things it cannot have on a
     * developer machine: {@code compose} (which would start a container -- the stub runs the SAME inner
     * script the container would, with a fake {@code curl} standing in for RustFS), and the two OpenBao
     * helpers (backed by files).
     */
    private static final class Fixture {
        static final String ROOT_SECRET = "root-secret-canary-abcdef";
        private static final ObjectMapper JSON = new ObjectMapper();
        private static final Pattern DATA = Pattern.compile("--data (\\{.*})\\s*$");

        private final Path root;
        private final boolean remint;

        Fixture(Path root, String adminResponse, boolean remint) throws IOException {
            this.root = root;
            this.remint = remint;
            Files.createDirectories(root.resolve("bao"));
            Files.createDirectories(root.resolve("bin"));
            Files.writeString(root.resolve("response.json"), adminResponse, StandardCharsets.UTF_8);
            Files.writeString(root.resolve("root-secret"), ROOT_SECRET, StandardCharsets.UTF_8);
            Files.writeString(root.resolve("trace"), "", StandardCharsets.UTF_8);
            Path curl = root.resolve("bin/curl");
            Files.writeString(curl, """
                    #!/usr/bin/env bash
                    printf 'CURL %s\\n' "$*" >> "${FAKE_ROOT}/trace"
                    if [ -f "${FAKE_ROOT}/curl-exit" ]; then exit "$(cat "${FAKE_ROOT}/curl-exit")"; fi
                    cat "${FAKE_ROOT}/response.json"
                    """, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(curl, PosixFilePermissions.fromString("rwxr-xr-x"));
        }

        void store(String name, String value) throws IOException {
            Files.writeString(
                    root.resolve("bao/horecaos_production_object_storage_platform_" + name),
                    value,
                    StandardCharsets.UTF_8);
        }

        String stored(String name) throws IOException {
            return Files.readString(
                    root.resolve("bao/horecaos_production_object_storage_platform_" + name), StandardCharsets.UTF_8);
        }

        void curlExitCode(int code) throws IOException {
            Files.writeString(root.resolve("curl-exit"), Integer.toString(code), StandardCharsets.UTF_8);
        }

        List<String> trace() throws IOException {
            return Files.readAllLines(root.resolve("trace"), StandardCharsets.UTF_8);
        }

        String curlLine() throws IOException {
            return trace().stream()
                    .filter(line -> line.startsWith("CURL"))
                    .findFirst()
                    .orElseThrow();
        }

        JsonNode requestBody() throws IOException {
            Matcher matcher = DATA.matcher(curlLine());
            assertThat(matcher.find())
                    .as("the admin call carries a JSON body: %s", curlLine())
                    .isTrue();
            return JSON.readTree(matcher.group(1));
        }

        /** What {@code bao token capabilities} answers for every path, as OpenBao prints it. */
        void capabilities(String listing) throws IOException {
            Files.writeString(root.resolve("caps-default"), listing, StandardCharsets.UTF_8);
        }

        void capabilitiesCallFails() throws IOException {
            Files.writeString(root.resolve("caps-fail"), "1", StandardCharsets.UTF_8);
        }

        Run run() throws IOException, InterruptedException {
            return execute(false);
        }

        /** The write-access preflight first, then the media block, as deploy.sh orders them. */
        Run runWithPreflight() throws IOException, InterruptedException {
            return execute(true);
        }

        private Run execute(boolean withPreflight) throws IOException, InterruptedException {
            String script = readFile(SCRIPT);
            StringBuilder definitions = new StringBuilder();
            for (String line : script.lines().toList()) {
                if (line.startsWith("OBJECT_STORE_") || line.startsWith("REMINT_OBJECT_STORE_CREDENTIALS=")) {
                    definitions.append(line).append('\n');
                }
            }
            String preflight = "";
            if (withPreflight) {
                int start = script.indexOf("token_can_store() {");
                int end = script.indexOf("# end of the write-access preflight helpers");
                assertThat(start).as("the preflight helpers must exist").isNotEqualTo(-1);
                assertThat(end).as("and end at their marker").isGreaterThan(start);
                preflight = """
                        bao_run() {
                            [ "$1 $2 $3" = "bao token capabilities" ] || { echo "unexpected bao_run: $*" >&2; return 99; }
                            printf 'CAPS %s\\n' "$4" >> "${FAKE_ROOT}/trace"
                            [ ! -f "${FAKE_ROOT}/caps-fail" ] || return 2
                            if [ -f "${FAKE_ROOT}/caps-default" ]; then cat "${FAKE_ROOT}/caps-default"; else echo deny; fi
                        }
                        """ + script.substring(start, end) + "\nrequire_write_access_before_minting\n";
            }
            String harness = """
                    set -euo pipefail
                    export FAKE_ROOT PATH="${FAKE_ROOT}/bin:${PATH}"
                    say()  { printf '==> %s\\n' "$*"; }
                    warn() { printf '\\n!!  %s\\n' "$*" >&2; }
                    die()  { printf '\\n!!  %s\\n' "$*" >&2; exit 1; }
                    bao_key() { printf '%s' "$1" | tr '/' '_'; }
                    bao_field() { local f="${FAKE_ROOT}/bao/$(bao_key "$1")"; [ -f "$f" ] && cat "$f"; }
                    bao_put_value() { printf '%s' "$2" > "${FAKE_ROOT}/bao/$(bao_key "$1")"; printf 'PUT %s\\n' "$1" >> "${FAKE_ROOT}/trace"; }
                    compose() {
                        local inner="${@: -1}"
                        inner="${inner//\\/run\\/secrets\\/object-store-secret-key/${FAKE_ROOT}/root-secret}"
                        OBJECT_STORE_ROOT_ACCESS_KEY=root-user HORECAOS_MEDIA_BUCKET=horecaos-media bash -c "${inner}"
                    }
                    """ + definitions + preflight + mediaBlock(script);
            Path harnessFile = root.resolve("harness.sh");
            Files.writeString(harnessFile, harness, StandardCharsets.UTF_8);
            ProcessBuilder builder = new ProcessBuilder("bash", harnessFile.toString()).redirectErrorStream(true);
            builder.environment().put("FAKE_ROOT", root.toString());
            builder.environment().put("HORECAOS_REMINT_OBJECT_STORE_CREDENTIALS", remint ? "1" : "0");
            builder.environment().remove("BASH_ENV");
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(process.waitFor(60, TimeUnit.SECONDS))
                    .as("the media block never blocks")
                    .isTrue();
            return new Run(process.exitValue(), output);
        }
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
