package uz.horecaos.platform.iam.infrastructure.keycloak;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The checked-in realm has to fit the database Keycloak imports it into.
 *
 * <p>Keycloak keeps a client's description, name, client id and URLs, and
 * each redirect URI and web origin, in {@code varchar(255)} columns. A longer
 * value is not truncated: the whole realm import fails with {@code value too
 * long for type character varying(255)}, and no realm exists.
 *
 * <p>This went unnoticed for nine days because nothing ever did a fresh import.
 * Local development runs {@code start-dev --import-realm}, which skips a realm
 * that already exists, and every developer's Keycloak volume predates the first
 * over-long description. The first fresh import into PostgreSQL — the pre-prod
 * deployment of 2026-09-10 — was the first thing to try, and it failed. A
 * production first boot would have failed the same way. So this reads the file
 * rather than trusting that an import somewhere would have complained.
 */
class RealmFileColumnLimitTests {

    private static final Path REALM = Path.of("infra/keycloak/realm/horecaos-realm.json");
    private static final int KEYCLOAK_VARCHAR = 255;
    private static final List<String> SCALAR_COLUMNS =
            List.of("clientId", "name", "description", "rootUrl", "baseUrl", "adminUrl");
    private static final List<String> LIST_COLUMNS = List.of("redirectUris", "webOrigins");

    @Test
    @DisplayName("every client value Keycloak stores in a varchar(255) column fits it")
    void everyClientValueFitsItsColumn() throws Exception {
        JsonNode realm = JsonMapper.builder().build().readTree(Files.readString(REALM));
        List<String> tooLong = new ArrayList<>();

        for (JsonNode client : realm.path("clients")) {
            String id = client.path("clientId").asString("<no clientId>");
            for (String column : SCALAR_COLUMNS) {
                String value = client.path(column).asString("");
                if (value.length() > KEYCLOAK_VARCHAR) {
                    tooLong.add("%s.%s is %d characters".formatted(id, column, value.length()));
                }
            }
            for (String column : LIST_COLUMNS) {
                for (JsonNode entry : client.path(column)) {
                    if (entry.asString("").length() > KEYCLOAK_VARCHAR) {
                        tooLong.add("%s.%s has an entry of %d characters"
                                .formatted(id, column, entry.asString("").length()));
                    }
                }
            }
        }

        assertThat(tooLong)
                .as(
                        "a value over %d characters fails the entire realm import, not just this field; "
                                + "put the rationale in the ADR the description cites and keep the label short",
                        KEYCLOAK_VARCHAR)
                .isEmpty();
    }
}
