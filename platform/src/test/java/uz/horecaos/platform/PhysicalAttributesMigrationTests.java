package uz.horecaos.platform;

import static org.assertj.core.api.Assertions.assertThat;
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
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The schema half of ADR 0137: V0449 widens every column that carries an ordered quantity,
 * and V0448/V0450 hand the application role exactly the privileges the new behaviour needs.
 *
 * <p>The widening is proved over rows that already exist, which is the only state in which
 * a type change can be wrong: a fixture migrated from an empty schema has nothing for it to
 * rewrite. The rows are written under the schema as it was before V0449 and read back after.
 *
 * <p>The privileges are proved against the group role the application connects through. The
 * rest of the suite connects as the migration role, a superuser, and so cannot notice a
 * missing GRANT; a table the application cannot write is a table whose first request fails in
 * production and in nothing before it.
 */
class PhysicalAttributesMigrationTests {

    /** The last migration before V0449. */
    private static final MigrationVersion BEFORE = MigrationVersion.fromVersion("0448");

    /** Every table V0449 widens, and the column that carries the quantity. */
    private static final Map<String, String> WIDENED = Map.of(
            "ordering.cart_lines", "quantity",
            "pricing.quote_lines", "quantity",
            "ordering.order_lines", "quantity",
            "kitchen.ticket_items", "quantity",
            "reporting.fact_order_line", "quantity",
            "reporting.classification_result", "quantity_total");

    /** The tables whose rows can be written without a partition or a deep graph of parents. */
    private static final List<String> WRITTEN =
            List.of("ordering.cart_lines", "pricing.quote_lines", "ordering.order_lines", "kitchen.ticket_items");

    @Test
    @DisplayName("every whole quantity written before V0449 reads back unchanged, and a fraction is now admitted")
    void existingWholeQuantitiesSurviveTheWidening() throws SQLException {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required to migrate a database");
        try (TestDatabase.Handle db = TestDatabase.empty()) {
            DataSource dataSource = db.dataSource();
            Flyway.configure().dataSource(dataSource).target(BEFORE).load().migrate();

            Map<String, UUID> ids = new LinkedHashMap<>();
            try (Connection connection = dataSource.getConnection()) {
                JdbcClient jdbc = withoutForeignKeys(connection);
                int quantity = 3;
                for (String table : WRITTEN) {
                    UUID id = UUID.randomUUID();
                    ids.put(table, id);
                    insert(jdbc, table, id, BigDecimal.valueOf(quantity++));
                }
            }

            Flyway.configure().dataSource(dataSource).load().migrate();

            JdbcClient jdbc = JdbcClient.create(dataSource);
            int expected = 3;
            for (String table : WRITTEN) {
                assertThat(jdbc.sql("SELECT quantity FROM " + table + " WHERE " + idColumn(table) + "::text = :id")
                                .param("id", String.valueOf(ids.get(table)))
                                .query(BigDecimal.class)
                                .single())
                        .as("%s: a whole quantity written as an integer reads back as the same number", table)
                        .isEqualByComparingTo(BigDecimal.valueOf(expected++));
            }

            for (Map.Entry<String, String> widened : WIDENED.entrySet()) {
                String[] schemaAndTable = widened.getKey().split("\\.");
                Map<String, Object> column = jdbc.sql("""
                                SELECT data_type, numeric_precision, numeric_scale FROM information_schema.columns
                                WHERE table_schema = :schema AND table_name = :table AND column_name = :column
                                """)
                        .param("schema", schemaAndTable[0])
                        .param("table", schemaAndTable[1])
                        .param("column", widened.getValue())
                        .query()
                        .singleRow();
                assertThat(column.get("data_type")).as(widened.getKey()).isEqualTo("numeric");
                assertThat(column.get("numeric_scale")).as(widened.getKey()).isEqualTo(3);
                assertThat(column.get("numeric_precision")).as(widened.getKey()).isIn(10, 14);
            }

            try (Connection connection = dataSource.getConnection()) {
                JdbcClient writer = withoutForeignKeys(connection);
                for (String table : WRITTEN) {
                    insert(writer, table, UUID.randomUUID(), new BigDecimal("0.5"));
                    assertThat(writer.sql("SELECT count(*) FROM " + table + " WHERE quantity = 0.5")
                                    .query(Integer.class)
                                    .single())
                            .as("%s admits half a portion", table)
                            .isEqualTo(1);
                    for (String refused : List.of("0", "-0.5")) {
                        assertThatThrownBy(() -> insert(writer, table, UUID.randomUUID(), new BigDecimal(refused)))
                                .as("%s still refuses %s", table, refused)
                                .isInstanceOf(DataIntegrityViolationException.class)
                                .hasMessageContaining("quantity");
                    }
                }
                assertThatThrownBy(() -> insert(writer, "ordering.cart_lines", UUID.randomUUID(), new BigDecimal(1000)))
                        .as("the basket keeps its upper bound")
                        .isInstanceOf(DataIntegrityViolationException.class);
            }
        }
    }

    @Test
    @DisplayName("the application role can use what ADR 0137 added, and edits no more of an order line than it must")
    void theApplicationRoleHoldsExactlyTheGrantsTheRecordNeeds() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required to migrate a database");
        try (TestDatabase.Handle db = TestDatabase.migrated()) {
            JdbcClient jdbc = JdbcClient.create(db.dataSource());
            String role = TestDatabase.APPLICATION_ROLE;

            for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
                assertThat(tablePrivilege(jdbc, role, "catalog.variant_physical_attributes", privilege))
                        .as("the authoring write needs %s on the attributes table (V0448)", privilege)
                        .isTrue();
            }

            // V0450: the reconciliation corrects one weighed line's amounts, and nothing else of it.
            for (String column :
                    List.of("actual_weight_grams", "base_amount_minor", "final_amount_minor", "tax_amount_minor")) {
                assertThat(columnPrivilege(jdbc, role, "ordering.order_lines", column, "UPDATE"))
                        .as("the reconciliation writes %s", column)
                        .isTrue();
            }
            List<String> stillImmutable = new ArrayList<>(List.of(
                    "quantity",
                    "unit_amount_minor",
                    "product_name_snapshot",
                    "source_variant_id",
                    "catchweight_quantum_grams",
                    "catchweight_nominal_grams",
                    "catchweight_price_per_quantum_minor"));
            for (String column : stillImmutable) {
                assertThat(columnPrivilege(jdbc, role, "ordering.order_lines", column, "UPDATE"))
                        .as("%s is a snapshot of what the customer agreed to and stays read-only", column)
                        .isFalse();
            }
            assertThat(tablePrivilege(jdbc, role, "ordering.order_lines", "UPDATE"))
                    .as("no table-wide UPDATE on order lines")
                    .isFalse();
            assertThat(tablePrivilege(jdbc, role, "ordering.order_lines", "DELETE"))
                    .as("an order line is never deleted")
                    .isFalse();

            // The new quote and order columns are written by INSERT, which the tables already grant.
            for (String table : List.of("pricing.quote_lines", "ordering.order_lines")) {
                for (String column : List.of(
                        "catchweight_quantum_grams",
                        "catchweight_nominal_grams",
                        "catchweight_price_per_quantum_minor",
                        "actual_weight_grams")) {
                    assertThat(columnPrivilege(jdbc, role, table, column, "INSERT"))
                            .as("%s.%s is written at checkout", table, column)
                            .isTrue();
                }
            }
            assertThat(tablePrivilege(jdbc, role, "ordering.order_revisions", "INSERT"))
                    .as("a reconciliation appends an order revision")
                    .isTrue();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A session that does not enforce foreign keys, so a line can be written without its whole graph of parents. */
    private static JdbcClient withoutForeignKeys(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
        }
        return JdbcClient.create(new SingleConnectionDataSource(connection, true));
    }

    private static boolean tablePrivilege(JdbcClient jdbc, String role, String table, String privilege) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT has_table_privilege(:role, :table, :privilege)")
                .param("role", role)
                .param("table", table)
                .param("privilege", privilege)
                .query(Boolean.class)
                .single());
    }

    private static boolean columnPrivilege(
            JdbcClient jdbc, String role, String table, String column, String privilege) {
        return Boolean.TRUE.equals(jdbc.sql("SELECT has_column_privilege(:role, :table, :column, :privilege)")
                .param("role", role)
                .param("table", table)
                .param("column", column)
                .param("privilege", privilege)
                .query(Boolean.class)
                .single());
    }

    /**
     * Writes one row with the given quantity, filling every other required column from its type.
     *
     * <p>Introspected rather than spelled out because the point is the quantity column and four
     * tables' worth of unrelated NOT NULL columns would bury it. A CHECK that rejects a generic
     * value fails the test loudly, which is the right answer to "this helper no longer fits".
     */
    private static void insert(JdbcClient jdbc, String table, UUID id, BigDecimal quantity) {
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
        values.putAll(CONSTRAINT_VALUES.getOrDefault(table, Map.of()));
        values.put(idColumn(table), id);
        values.put("quantity", quantity);
        if ("ordering.order_lines".equals(table)) {
            values.put("line_number", 1);
        }

        List<String> columns = new ArrayList<>(values.keySet());
        String sql = "INSERT INTO " + table + " (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", columns.stream().map(c -> ":" + c).toList()) + ")";
        jdbc.sql(sql).params(new HashMap<>(values)).update();
    }

    /** The values a table's own CHECKs insist on, which no type-driven default would guess. */
    private static final Map<String, Map<String, Object>> CONSTRAINT_VALUES = Map.of(
            // An ITEM line names its variant (ck_quote_line_variant_agrees).
            "pricing.quote_lines", Map.of("source_variant_id", UUID.randomUUID()),
            // A MAPPED line names its variant (ck_order_line_unmapped_has_no_variant).
            "ordering.order_lines", Map.of("source_variant_id", UUID.randomUUID()),
            "kitchen.ticket_items", Map.of("routed_by", "FALLBACK"));

    /** A quote line is identified by the line it quotes, not by a row id of its own. */
    private static String idColumn(String table) {
        return "pricing.quote_lines".equals(table) ? "line_id" : "id";
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
