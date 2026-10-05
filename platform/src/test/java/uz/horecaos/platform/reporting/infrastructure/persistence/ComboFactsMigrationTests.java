package uz.horecaos.platform.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * V0478 over facts that already exist (ADR 0136, row 4.2a): the cleanup that follows the day-close
 * change from "every line an order ever held" to "the lines it holds now".
 *
 * <p>The cleanup is only ever wrong over rows built before it: a fixture migrated from an empty
 * schema has nothing for it to delete or recount. So the rows are written under the schema as it was
 * before V0478 -- an amended order whose day closed with both revisions of a rewritten line -- and
 * read back after.
 */
class ComboFactsMigrationTests {

    /** The last migration before V0478 that touches the facts. */
    private static final MigrationVersion BEFORE = MigrationVersion.fromVersion("0477");

    private static final LocalDate DAY = LocalDate.of(2026, 8, 21);

    private static final UUID TENANT = UUID.fromString("018f6f4e-2000-7000-8000-00000000d001");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-2000-7000-8000-00000000d002");
    private static final UUID BRAND = UUID.fromString("018f6f4e-2000-7000-8000-00000000d003");

    @Test
    @DisplayName(
            "an order amended before V0478 keeps only its live line facts, and its line and item counts say what it holds now")
    void anAmendedOrdersCountsAgreeWithTheLineFactsThatAreLeft() throws SQLException {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required to migrate a database");
        try (TestDatabase.Handle db = TestDatabase.empty()) {
            DataSource dataSource = db.dataSource();
            Flyway.configure().dataSource(dataSource).target(BEFORE).load().migrate();

            // Order A: one burger became two (the burger line was closed and rewritten), and a salad was
            // added. Day close read all three lines, so it counted three lines and four plates.
            UUID amended = UUID.randomUUID();
            UUID burgerClosed = UUID.randomUUID();
            UUID burgerLive = UUID.randomUUID();
            UUID saladLive = UUID.randomUUID();
            // Order B was never amended: one line, one plate.
            UUID plain = UUID.randomUUID();
            UUID plainLine = UUID.randomUUID();
            // Order C was amended and its day rebuilt after the reader changed: already right.
            UUID rebuilt = UUID.randomUUID();
            UUID rebuiltClosed = UUID.randomUUID();
            UUID rebuiltLive = UUID.randomUUID();
            try (Connection connection = dataSource.getConnection()) {
                JdbcClient jdbc = withoutForeignKeys(connection);
                insertFactOrder(jdbc, amended, 3, 4);
                insertFactLine(jdbc, amended, burgerClosed, new BigDecimal("1"));
                insertFactLine(jdbc, amended, burgerLive, new BigDecimal("2"));
                insertFactLine(jdbc, amended, saladLive, new BigDecimal("1"));
                insertOrderLine(jdbc, amended, burgerClosed, 1, true);
                insertOrderLine(jdbc, amended, burgerLive, 2, false);
                insertOrderLine(jdbc, amended, saladLive, 3, false);

                insertFactOrder(jdbc, plain, 1, 1);
                insertFactLine(jdbc, plain, plainLine, new BigDecimal("1"));
                insertOrderLine(jdbc, plain, plainLine, 1, false);

                insertFactOrder(jdbc, rebuilt, 1, 3);
                insertFactLine(jdbc, rebuilt, rebuiltLive, new BigDecimal("3"));
                insertOrderLine(jdbc, rebuilt, rebuiltClosed, 1, true);
                insertOrderLine(jdbc, rebuilt, rebuiltLive, 2, false);
            }

            Flyway.configure().dataSource(dataSource).load().migrate();

            JdbcClient jdbc = JdbcClient.create(dataSource);
            assertThat(factLines(jdbc, amended))
                    .as("the closed burger line is history no live order has")
                    .containsExactlyInAnyOrder(burgerLive, saladLive);
            assertThat(counts(jdbc, amended))
                    .as("two lines and three plates now, not the three lines and four plates both revisions made")
                    .containsExactly(2, 3);
            assertThat(counts(jdbc, plain))
                    .as("an order never amended is left as it was")
                    .containsExactly(1, 1);
            assertThat(counts(jdbc, rebuilt))
                    .as("an amended order whose counts were already right is not changed")
                    .containsExactly(1, 3);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static List<UUID> factLines(JdbcClient jdbc, UUID orderId) {
        return jdbc.sql("SELECT line_id FROM reporting.fact_order_line WHERE order_id = :id")
                .param("id", orderId)
                .query(UUID.class)
                .list();
    }

    private static List<Integer> counts(JdbcClient jdbc, UUID orderId) {
        return jdbc.sql("SELECT line_count, item_count FROM reporting.fact_order WHERE order_id = :id")
                .param("id", orderId)
                .query((row, number) -> List.of(row.getInt("line_count"), row.getInt("item_count")))
                .single();
    }

    /** A session that does not enforce foreign keys, so a row can be written without its whole graph of parents. */
    private static JdbcClient withoutForeignKeys(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
        }
        return JdbcClient.create(new SingleConnectionDataSource(connection, true));
    }

    private static void insertFactOrder(JdbcClient jdbc, UUID orderId, int lineCount, int itemCount) {
        jdbc.sql("""
                INSERT INTO reporting.fact_order (
                    tenant_id, order_id, business_date, boundary_version, occurred_at,
                    brand_id, location_id, channel_code, fulfilment_type, terminal_status,
                    gross_revenue_som, discount_som, delivery_fee_som, tax_som, net_revenue_som,
                    line_count, item_count, metric_calculation_version, source_order_version)
                VALUES (
                    :tenantId, :orderId, :businessDate, 1, :occurredAt,
                    :brandId, :locationId, 'TELEGRAM', 'DELIVERY', 'COMPLETED',
                    0, 0, 0, 0, 0,
                    :lineCount, :itemCount, 1, 1)
                """)
                .param("tenantId", TENANT)
                .param("orderId", orderId)
                .param("businessDate", DAY)
                .param("occurredAt", OffsetDateTime.of(DAY.atTime(4, 0), ZoneOffset.UTC))
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("lineCount", lineCount)
                .param("itemCount", itemCount)
                .update();
    }

    private static void insertFactLine(JdbcClient jdbc, UUID orderId, UUID lineId, BigDecimal quantity) {
        jdbc.sql("""
                INSERT INTO reporting.fact_order_line (
                    tenant_id, business_date, order_id, line_id, location_id, variant_id,
                    product_name_snapshot, quantity, gross_som, discount_som, net_som, occurred_at)
                VALUES (
                    :tenantId, :businessDate, :orderId, :lineId, :locationId, :variantId,
                    'Burger', :quantity, 1000, 0, 1000, :occurredAt)
                """)
                .param("tenantId", TENANT)
                .param("businessDate", DAY)
                .param("orderId", orderId)
                .param("lineId", lineId)
                .param("locationId", LOCATION)
                .param("variantId", UUID.randomUUID())
                .param("quantity", quantity)
                .param("occurredAt", OffsetDateTime.of(DAY.atTime(4, 0), ZoneOffset.UTC))
                .update();
    }

    /**
     * Writes one ordering line, filling every other required column from its type: the cleanup reads
     * only whether the line is closed, and four dozen unrelated NOT NULL columns would bury that.
     */
    private static void insertOrderLine(JdbcClient jdbc, UUID orderId, UUID lineId, int lineNumber, boolean closed) {
        List<Map<String, Object>> required = jdbc.sql("""
                        SELECT column_name, data_type FROM information_schema.columns
                        WHERE table_schema = 'ordering' AND table_name = 'order_lines'
                          AND is_nullable = 'NO' AND column_default IS NULL AND is_generated = 'NEVER'
                        ORDER BY ordinal_position
                        """).query().listOfRows();
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map<String, Object> column : required) {
            String name = String.valueOf(column.get("column_name"));
            values.put(name, genericValue(String.valueOf(column.get("data_type")), name));
        }
        // A MAPPED line names its variant (ck_order_line_unmapped_has_no_variant).
        values.put("source_variant_id", UUID.randomUUID());
        values.put("tenant_id", TENANT);
        values.put("order_id", orderId);
        values.put("id", lineId);
        values.put("line_number", lineNumber);
        values.put("quantity", BigDecimal.ONE);
        if (closed) {
            values.put("revision_to", 2);
        }

        List<String> columns = new ArrayList<>(values.keySet());
        String sql = "INSERT INTO ordering.order_lines (" + String.join(", ", columns) + ") VALUES ("
                + String.join(", ", columns.stream().map(c -> ":" + c).toList()) + ")";
        jdbc.sql(sql).params(new HashMap<>(values)).update();
    }

    private static Object genericValue(String dataType, String column) {
        return switch (dataType) {
            case "uuid" -> UUID.randomUUID();
            case "integer", "smallint", "bigint" -> 1;
            case "numeric" -> BigDecimal.ONE;
            case "boolean" -> Boolean.FALSE;
            case "character varying", "text", "character" -> "x";
            case "timestamp with time zone" -> java.sql.Timestamp.from(java.time.Instant.now());
            case "date" -> java.sql.Date.valueOf(LocalDate.now());
            default -> throw new IllegalStateException("No generic value for " + column + " of type " + dataType);
        };
    }
}
