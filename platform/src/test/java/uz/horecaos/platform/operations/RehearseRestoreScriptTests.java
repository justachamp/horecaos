package uz.horecaos.platform.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The restore rehearsal connects as a role the privilege-tightened schema
 * actually has.
 *
 * <p>{@code rehearse-restore.sh} hardcoded {@code postgresql://horecaos:horecaos@
 * platform-db:5432/horecaos}. That role does not exist: compose.yaml's own
 * platform-db comment explains why — the local stack used to run everything
 * as a single {@code horecaos} superuser that also owned the database, so
 * every GRANT and REVOKE sixty-one migrations wrote was bypassed on every
 * laptop and in every test, and that superuser was retired for exactly that
 * reason. What replaced it, in both compose.yaml (as {@code POSTGRES_USER}/
 * {@code POSTGRES_PASSWORD}) and compose.production.yaml, is {@code
 * horecaos_migrator}. A rehearsal that still asked for {@code horecaos}
 * could not authenticate against either environment — confirmed by running
 * it against a real, freshly migrated database with the old role name before
 * this fix: {@code FATAL: password authentication failed for user
 * "horecaos"}, before the script dumped a single table.
 *
 * <p>Asserted against the script text and cross-checked against compose.yaml,
 * the same way {@link DeployScriptTests} catches a name that drifted between
 * two files rather than being wrong in either one alone: a defect here is
 * invisible to a syntax check on either file, only to a comparison between
 * them. The end-to-end round trip itself — dump, encrypt, upload, replicate
 * off-site, read back, decrypt, restore, verify row counts — was run by hand
 * against a throwaway compose project (platform-db, object-store,
 * object-store-offsite, freshly migrated to V0416) before this test was
 * written, and passed: {@code baseline : 0,0,246} / {@code restored :
 * 0,0,246}, off-site checksum matched. That full round trip needs Docker, a
 * compose network and a migrated database, which is exactly the shape
 * {@link BackupScriptTests}'s own class comment gives for why its happy path
 * is proven by running the rehearsal rather than by a JUnit test.
 */
class RehearseRestoreScriptTests {

    private static final Path SCRIPT = Path.of("infra/backup/rehearse-restore.sh");
    private static final Path COMPOSE_FILE = Path.of("compose.yaml");

    @Test
    @DisplayName("the database role is not the retired `horecaos` superuser")
    void doesNotConnectAsTheRetiredSuperuser() throws IOException {
        String script = readFile(SCRIPT);

        assertThat(script)
                .as("the old hardcoded URL authenticated as a role the privilege-tightened "
                        + "schema no longer creates -- FATAL: password authentication failed, "
                        + "confirmed by running this script against a real migrated database "
                        + "before this fix")
                .doesNotContain("horecaos:horecaos@platform-db");
    }

    @Test
    @DisplayName(
            "the database credential is configurable, with a default that matches what compose.yaml actually creates")
    void defaultsToTheRoleComposeYamlActuallyCreates() throws IOException {
        String script = readFile(SCRIPT);
        String compose = readFile(COMPOSE_FILE);

        assertThat(script)
                .as("the connection URL must be built from configurable variables, not a "
                        + "literal -- so this script keeps working if compose.yaml's local "
                        + "credentials ever change, and can be pointed at a real host by "
                        + "environment alone")
                .contains("HORECAOS_REHEARSAL_DB_HOST")
                .contains("HORECAOS_REHEARSAL_DB_USER")
                .contains("HORECAOS_REHEARSAL_DB_PASSWORD")
                .doesNotContain("PGPASSWORD=horecaos ");

        // compose.yaml is the source of truth for what role actually exists locally
        // (platform-db's own POSTGRES_USER/POSTGRES_PASSWORD) -- pinned here so a
        // rename in one file without the other fails this test instead of failing
        // silently at 2am during a scheduled rehearsal.
        assertThat(compose)
                .as("compose.yaml must still create horecaos_migrator as platform-db's own user")
                .contains("POSTGRES_USER: horecaos_migrator")
                .contains("POSTGRES_PASSWORD: horecaos_migrator");
        assertThat(script)
                .as("this script's default role must match the one compose.yaml actually creates")
                .contains("HORECAOS_REHEARSAL_DB_USER:=horecaos_migrator")
                .contains("HORECAOS_REHEARSAL_DB_PASSWORD:=horecaos_migrator");
    }

    private static String readFile(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
