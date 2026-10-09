package uz.horecaos.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The schema half of ADR 0158 (V0538): what the database itself refuses, so that a bug in the cart, the
 * authoring service or a future importer cannot write an incoherent rule or a line with no provenance.
 *
 * <p>Proved on the migrated schema and against the group role the application connects through. The rest of the
 * suite connects as the migration role, a superuser, and so cannot notice a missing or an extra GRANT.
 */
class AutoAddSchemaTests {

    @Test
    @DisplayName("the bands of one rule may touch but never overlap, and another rule's bands are not compared")
    void bandsOfOneRuleMayTouchButNeverOverlap() throws SQLException {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required to migrate a database");
        try (TestDatabase.Handle db = TestDatabase.migrated();
                Connection connection = db.dataSource().getConnection()) {
            JdbcClient jdbc = withoutForeignKeys(connection);
            UUID rule = UUID.randomUUID();
            UUID other = UUID.randomUUID();

            insert(jdbc, "catalog.auto_add_rule_bands", band(rule, "1", "4"));
            assertThatCode(() -> insert(jdbc, "catalog.auto_add_rule_bands", band(rule, "4", null)))
                    .as("[1,4) and [4,) share a boundary and no point")
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> insert(jdbc, "catalog.auto_add_rule_bands", band(rule, "3", "5")))
                    .as("[3,5) shares the point 3.5 with [1,4)")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ex_auto_add_band_overlap");
            assertThatThrownBy(() -> insert(jdbc, "catalog.auto_add_rule_bands", band(rule, "10", "20")))
                    .as("[10,20) lies inside the open-ended band that starts at 4")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ex_auto_add_band_overlap");
            assertThatCode(() -> insert(jdbc, "catalog.auto_add_rule_bands", band(other, "1", "4")))
                    .as("the exclusion is per rule: another rule may use the same range")
                    .doesNotThrowAnyException();
            assertThatThrownBy(() -> insert(jdbc, "catalog.auto_add_rule_bands", band(UUID.randomUUID(), "5", "5")))
                    .as("a band that ends where it starts holds nothing")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_auto_add_band_range");
        }
    }

    @Test
    @DisplayName("a cart line has a rule exactly when a rule added it, and carries the version that did")
    void aCartLineHasARuleExactlyWhenARuleAddedIt() throws SQLException {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required to migrate a database");
        try (TestDatabase.Handle db = TestDatabase.migrated();
                Connection connection = db.dataSource().getConnection()) {
            JdbcClient jdbc = withoutForeignKeys(connection);
            UUID rule = UUID.randomUUID();

            assertThatCode(() -> insert(jdbc, "ordering.cart_lines", Map.of()))
                    .as("a customer's line has no rule")
                    .doesNotThrowAnyException();
            assertThatCode(() -> insert(
                            jdbc,
                            "ordering.cart_lines",
                            Map.of("origin", "AUTO_ADD", "auto_add_rule_id", rule, "auto_add_rule_version", 2)))
                    .as("a line a rule added names the rule and its version")
                    .doesNotThrowAnyException();
            assertThatCode(() -> insert(
                            jdbc,
                            "ordering.cart_lines",
                            Map.of("origin", "OFFER_ACCEPTED", "auto_add_rule_id", rule, "auto_add_rule_version", 2)))
                    .as("so does a line the customer accepted from an offer")
                    .doesNotThrowAnyException();

            assertThatThrownBy(() -> insert(
                            jdbc, "ordering.cart_lines", Map.of("auto_add_rule_id", rule, "auto_add_rule_version", 1)))
                    .as("a customer's line cannot name a rule")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_cart_line_origin_rule");
            assertThatThrownBy(() -> insert(jdbc, "ordering.cart_lines", Map.of("origin", "AUTO_ADD")))
                    .as("an injected line without its rule is the evidence the order would lack")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_cart_line_origin_rule");
            assertThatThrownBy(() ->
                            insert(jdbc, "ordering.cart_lines", Map.of("origin", "AUTO_ADD", "auto_add_rule_id", rule)))
                    .as("a rule without the version of it that fired")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_cart_line_rule_version");
            assertThatThrownBy(() -> insert(
                            jdbc,
                            "ordering.cart_lines",
                            Map.of("origin", "WAITER", "auto_add_rule_id", rule, "auto_add_rule_version", 1)))
                    .as("the origins are a closed set")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("\"ck_cart_line_origin\"");
        }
    }

    @Test
    @DisplayName("an order line may be AUTO_ADD with no rule: a packaging line imported from the legacy platform")
    void anImportedPackagingLineIsAnOrderLineWithAnOriginAndNoRule() throws SQLException {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required to migrate a database");
        try (TestDatabase.Handle db = TestDatabase.migrated();
                Connection connection = db.dataSource().getConnection()) {
            JdbcClient jdbc = withoutForeignKeys(connection);

            assertThatCode(() -> insert(jdbc, "ordering.order_lines", Map.of()))
                    .as("an ordinary line defaults to the customer's")
                    .doesNotThrowAnyException();
            assertThatCode(() -> insert(jdbc, "ordering.order_lines", Map.of("origin", "AUTO_ADD")))
                    .as("ADR 0158: a legacy is_package line imports with origin AUTO_ADD and a null rule id; only "
                            + "the cart forbids that pairing")
                    .doesNotThrowAnyException();
            assertThat(jdbc.sql("SELECT count(*) FROM ordering.order_lines WHERE origin = 'AUTO_ADD' "
                                    + "AND auto_add_rule_id IS NULL AND auto_add_mandatory = false")
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);
            assertThatThrownBy(() -> insert(jdbc, "ordering.order_lines", Map.of("origin", "WAITER")))
                    .as("the origins are a closed set here too")
                    .isInstanceOf(DataIntegrityViolationException.class)
                    .hasMessageContaining("ck_order_line_origin");
        }
    }

    @Test
    @DisplayName("a rule is archived and never deleted, its children are replaced as a set, and a decline is forgotten "
            + "with its cart")
    void theApplicationRoleHoldsExactlyTheGrantsTheRecordNames() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required to migrate a database");
        try (TestDatabase.Handle db = TestDatabase.migrated()) {
            JdbcClient jdbc = JdbcClient.create(db.dataSource());
            String role = TestDatabase.APPLICATION_ROLE;

            assertThat(privileges(jdbc, role, "catalog.auto_add_rules"))
                    .as("a published snapshot must always be traceable to a row")
                    .containsExactlyInAnyOrder("SELECT", "INSERT", "UPDATE");
            for (String child : List.of(
                    "catalog.auto_add_rule_triggers",
                    "catalog.auto_add_rule_bands",
                    "catalog.auto_add_rule_locations")) {
                assertThat(privileges(jdbc, role, child))
                        .as("%s: a PUT removes a node, a band or a location, so DELETE is needed", child)
                        .containsExactlyInAnyOrder("SELECT", "INSERT", "UPDATE", "DELETE");
            }
            assertThat(privileges(jdbc, role, "ordering.cart_auto_add_declines"))
                    .as("a decline is written once and deleted with its cart; it is never edited")
                    .containsExactlyInAnyOrder("SELECT", "INSERT", "DELETE");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static Map<String, Object> band(UUID rule, String from, @Nullable String to) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("rule_id", rule);
        values.put("portions_from", new BigDecimal(from));
        if (to != null) {
            values.put("portions_to", new BigDecimal(to));
        }
        return values;
    }

    private static List<String> privileges(JdbcClient jdbc, String role, String table) {
        List<String> held = new ArrayList<>();
        for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
            if (Boolean.TRUE.equals(jdbc.sql("SELECT has_table_privilege(:role, :table, :privilege)")
                    .param("role", role)
                    .param("table", table)
                    .param("privilege", privilege)
                    .query(Boolean.class)
                    .single())) {
                held.add(privilege);
            }
        }
        return held;
    }

    /** A session that does not enforce foreign keys, so a row can be written without its whole graph of parents. */
    private static JdbcClient withoutForeignKeys(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
        }
        return JdbcClient.create(new SingleConnectionDataSource(connection, true));
    }

    /**
     * Writes one row, filling every required column the caller did not name from its type.
     *
     * <p>Introspected rather than spelled out because the point is the constraint under test and a table's worth of
     * unrelated NOT NULL columns would bury it. A CHECK that rejects a generic value fails the test loudly, which is
     * the right answer to "this helper no longer fits". A column left out is left to its default or to null.
     */
    private static void insert(JdbcClient jdbc, String table, Map<String, Object> named) {
        String[] schemaAndTable = table.split("\\.");
        List<Map<String, Object>> required = jdbc.sql("""
                        SELECT column_name, data_type FROM information_schema.columns
                        WHERE table_schema = :schema AND table_name = :table
                          AND is_nullable = 'NO' AND column_default IS NULL AND is_generated = 'NEVER'
                        ORDER BY ordinal_position
                        """)
                .param("schema", schemaAndTable[0])
                .param("table", schemaAndTable[1])
                .query()
                .listOfRows();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map<String, Object> column : required) {
            String name = String.valueOf(column.get("column_name"));
            values.put(name, genericValue(String.valueOf(column.get("data_type")), name));
        }
        values.putAll(constraintValues(table));
        values.putAll(named);

        List<String> columns = new ArrayList<>(values.keySet());
        String sql = "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", columns.stream().map(c -> ":" + c).toList()) + ")";
        jdbc.sql(sql).params(new HashMap<>(values)).update();
    }

    /** The values a table's own CHECKs insist on, which no type-driven default would guess. */
    private static Map<String, Object> constraintValues(String table) {
        return switch (table) {
            // A MAPPED line names its variant (ck_order_line_unmapped_has_no_variant).
            case "ordering.order_lines" -> Map.of("source_variant_id", UUID.randomUUID(), "line_number", 1);
            default -> Map.of();
        };
    }

    private static Object genericValue(String dataType, String column) {
        return switch (dataType) {
            case "uuid" -> UUID.randomUUID();
            case "integer", "smallint", "bigint" -> 1;
            case "numeric" -> BigDecimal.ONE;
            case "boolean" -> Boolean.FALSE;
            case "character varying", "text", "character" -> "x";
            case "timestamp with time zone" -> java.sql.Timestamp.from(java.time.Instant.now());
            case "date" -> java.sql.Date.valueOf(java.time.LocalDate.now());
            default -> throw new IllegalStateException("No generic value for " + column + " of type " + dataType);
        };
    }
}
