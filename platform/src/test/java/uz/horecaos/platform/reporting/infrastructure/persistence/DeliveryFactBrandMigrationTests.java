package uz.horecaos.platform.reporting.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * V0510 over delivery facts that already exist (ADR 0125, open input closed 2026-10-07): {@code
 * reporting.fact_delivery} gains {@code brand_id}, and the days closed before it must not be left
 * with a column that says nothing.
 *
 * <p>The backfill is only ever wrong over rows built before it, and a fixture migrated from an
 * empty schema has none, so the rows are written under the schema as it stood before V0510 and
 * read back after -- the shape {@code ComboFactsMigrationTests} already uses for V0478.
 */
class DeliveryFactBrandMigrationTests {

    /**
     * The newest migration that existed when V0510 was written. Flyway refuses a target that names
     * no migration, so this is a real one; whatever other work numbers between it and V0510 runs in
     * the second step, together with V0510 itself, and none of it touches this fact.
     */
    private static final MigrationVersion BEFORE = MigrationVersion.fromVersion("0489");

    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);

    private static final UUID TENANT = UUID.fromString("018f6f4e-2000-7000-8000-00000000e001");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-2000-7000-8000-00000000e002");
    private static final UUID SHIPMENT_BRAND = UUID.fromString("018f6f4e-2000-7000-8000-00000000e003");
    private static final UUID LOCATION_BRAND = UUID.fromString("018f6f4e-2000-7000-8000-00000000e004");
    private static final UUID LOCATION_OF_ORPHAN = UUID.fromString("018f6f4e-2000-7000-8000-00000000e005");

    @Test
    @DisplayName("a delivery fact closed before V0510 takes its shipment's brand, and the column is then required")
    void aFactClosedBeforeTheMigrationTakesItsShipmentsBrand() throws SQLException {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required to migrate a database");
        try (TestDatabase.Handle db = TestDatabase.empty()) {
            DataSource dataSource = db.dataSource();
            Flyway.configure().dataSource(dataSource).target(BEFORE).load().migrate();

            UUID shipment = UUID.randomUUID();
            UUID factWithShipment = UUID.randomUUID();
            UUID factWithoutShipment = UUID.randomUUID();
            try (Connection connection = dataSource.getConnection()) {
                JdbcClient jdbc = withoutForeignKeys(connection);
                insertShipment(jdbc, shipment, SHIPMENT_BRAND);
                insertLocation(jdbc, LOCATION_OF_ORPHAN, LOCATION_BRAND);
                insertFact(jdbc, factWithShipment, shipment, LOCATION);
                // The impossible row (fk_earning_shipment forbids an earning with no shipment), kept
                // only to show the fallback is the location's brand and never a guess.
                insertFact(jdbc, factWithoutShipment, UUID.randomUUID(), LOCATION_OF_ORPHAN);
            }

            Flyway.configure().dataSource(dataSource).load().migrate();

            JdbcClient jdbc = JdbcClient.create(dataSource);
            assertThat(brandOf(jdbc, factWithShipment))
                    .as("the shipment is where the close job now reads the brand from")
                    .isEqualTo(SHIPMENT_BRAND);
            assertThat(brandOf(jdbc, factWithoutShipment))
                    .as("with no shipment to read, the location's brand")
                    .isEqualTo(LOCATION_BRAND);
            assertThat(jdbc.sql("""
                            SELECT is_nullable FROM information_schema.columns
                             WHERE table_schema = 'reporting' AND table_name = 'fact_delivery'
                               AND column_name = 'brand_id'
                            """).query(String.class).single())
                    .as("every sibling fact names its brand, and so does this one")
                    .isEqualTo("NO");
        }
    }

    // ------------------------------------------------------------------ helpers

    private static UUID brandOf(JdbcClient jdbc, UUID earningId) {
        return jdbc.sql("SELECT brand_id FROM reporting.fact_delivery WHERE courier_assignment_earning_id = :id")
                .param("id", earningId)
                .query(UUID.class)
                .single();
    }

    /** A session that does not enforce foreign keys, so a row can be written without its whole graph of parents. */
    private static JdbcClient withoutForeignKeys(Connection connection) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.execute("SET session_replication_role = replica");
        }
        return JdbcClient.create(new SingleConnectionDataSource(connection, true));
    }

    private static void insertShipment(JdbcClient jdbc, UUID shipmentId, UUID brandId) {
        OffsetDateTime assigned = OffsetDateTime.of(DAY.atTime(10, 0), ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO fulfillment.shipments (
                    id, tenant_id, brand_id, location_id, order_id, delivery_plan_id, status,
                    source_type, courier_id, assigned_at, delivered_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :orderId, :planId, 'DELIVERED',
                        'INTERNAL', :courierId, :assigned, :delivered)
                """)
                .param("id", shipmentId)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("locationId", LOCATION)
                .param("orderId", UUID.randomUUID())
                .param("planId", UUID.randomUUID())
                .param("courierId", UUID.randomUUID())
                .param("assigned", assigned)
                .param("delivered", assigned.plusMinutes(30))
                .update();
    }

    private static void insertLocation(JdbcClient jdbc, UUID locationId, UUID brandId) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'ORPHAN', 'orphan', 'Orphan', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .update();
    }

    /** One {@code reporting.fact_delivery} row as V0337 left the table, before it had a brand. */
    private static void insertFact(JdbcClient jdbc, UUID earningId, UUID shipmentId, UUID locationId) {
        OffsetDateTime accepted = OffsetDateTime.of(DAY.atTime(10, 0), ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO reporting.fact_delivery (
                    tenant_id, courier_assignment_earning_id, business_date, boundary_version,
                    metric_calculation_version, courier_id, location_id, shipment_id,
                    assignment_attempt_id, distance_meters, distance_source, on_time_outcome,
                    accepted_at, delivered_at, transit_seconds)
                VALUES (
                    :tenantId, :earningId, :businessDate, 1,
                    1, :courierId, :locationId, :shipmentId,
                    :attemptId, 1000, 'ROUTING', 'ON_TIME',
                    :accepted, :delivered, 1800)
                """)
                .param("tenantId", TENANT)
                .param("earningId", earningId)
                .param("businessDate", DAY)
                .param("courierId", UUID.randomUUID())
                .param("locationId", locationId)
                .param("shipmentId", shipmentId)
                .param("attemptId", UUID.randomUUID())
                .param("accepted", accepted)
                .param("delivered", accepted.plusMinutes(30))
                .update();
    }
}
