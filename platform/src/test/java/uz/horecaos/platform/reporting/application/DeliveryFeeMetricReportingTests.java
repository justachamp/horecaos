package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.reporting.domain.Grain;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Wave 8 w7-reports (7.2c, V0383): {@code delivery_fee.v1} — the daily
 * summary's («Сводка») fee-exclusive column needs the fee's own total, at the
 * same {@code agg_branch_day} grain every other money metric on this page
 * reads. Rows go straight into {@code reporting.agg_branch_day}, the way
 * {@code ReportingControllerCapabilityHttpTests#insertFactOrder} already
 * does for the same table: {@code ReportQueryService#run} only ever reads
 * this table for a typed query, never {@code fact_order} itself, so a test
 * of the read does not need to re-prove the close job that would have
 * produced the same row (that is {@code DayCloseAndMetricLayerTests}'s job).
 */
class DeliveryFeeMetricReportingTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-3000-7000-8000-00000000d001");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-3000-7000-8000-00000000d002");
    private static final UUID ENTITY_A = UUID.fromString("018f6f4e-3000-7000-8000-00000000d003");
    private static final UUID ENTITY_B = UUID.fromString("018f6f4e-3000-7000-8000-00000000d004");

    private static final LocalDate DAY = LocalDate.of(2026, 9, 21);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcReportingStore store;
    private ReportQueryService queries;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);

        jdbc.sql("TRUNCATE TABLE reporting.agg_branch_day, reporting.business_day_policies")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        store = new JdbcReportingStore(jdbc);
        Clock clock = Clock.fixed(Instant.parse("2026-09-22T04:00:00Z"), ZoneOffset.UTC);
        queries = new ReportQueryService(store, new BusinessDayService(store), clock);

        seedTenant(TENANT);
    }

    @Test
    void sumsTheFeeSeparatelyFromGrossOverOneLegalEntity() {
        insertAggregate(ENTITY_A, "TELEGRAM", "DELIVERY", 3, 150_000L, 20_000L);
        insertAggregate(ENTITY_A, "WEBSITE", "PICKUP", 2, 60_000L, 0L);

        var result = queries.run(new ReportQuery(
                TENANT,
                DAY,
                DAY,
                List.of("revenue.gross.v1", "delivery_fee.v1"),
                List.of(),
                List.of(),
                List.of(),
                List.of()));

        assertThat(result.rows()).hasSize(1);
        var row = result.rows().getFirst();
        // The fee is a component already inside gross (ADR 0019), never a
        // second figure to add on top: proving it is smaller than gross and
        // that gross-minus-fee reproduces the fee-exclusive total is the
        // property that would break if the metric were wired to double count.
        long gross = Objects.requireNonNull(row.values().get("revenue.gross.v1"));
        long fee = Objects.requireNonNull(row.values().get("delivery_fee.v1"));
        assertThat(gross).isEqualTo(210_000L);
        assertThat(fee).isEqualTo(20_000L);
        assertThat(gross - fee)
                .as("the fee-exclusive figure a report derives by subtraction")
                .isEqualTo(190_000L);
    }

    @Test
    void aFeeTotalAcrossTwoLegalEntitiesIsRefused() {
        insertAggregate(ENTITY_A, "TELEGRAM", "DELIVERY", 1, 100_000L, 10_000L);
        insertAggregate(ENTITY_B, "TELEGRAM", "DELIVERY", 1, 80_000L, 15_000L);

        assertThatThrownBy(() -> queries.run(new ReportQuery(
                        TENANT, DAY, DAY, List.of("delivery_fee.v1"), List.of(), List.of(), List.of(), List.of())))
                .isInstanceOf(ReportingRefusals.CombinedEntityTotalException.class)
                .hasMessageContaining("neither tax filing");
    }

    @Test
    void thePerEntityCutOfTheFeeIsAllowedAndReconciles() {
        insertAggregate(ENTITY_A, "TELEGRAM", "DELIVERY", 1, 100_000L, 10_000L);
        insertAggregate(ENTITY_B, "TELEGRAM", "DELIVERY", 1, 80_000L, 15_000L);

        var result = queries.run(new ReportQuery(
                TENANT,
                DAY,
                DAY,
                List.of("delivery_fee.v1"),
                List.of(Grain.Dimension.LEGAL_ENTITY),
                List.of(),
                List.of(),
                List.of()));

        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().stream()
                        .mapToLong(row -> row.values().get("delivery_fee.v1"))
                        .sum())
                .isEqualTo(25_000L);
    }

    /**
     * A row closed before V0383 has no real delivery-fee total — the column
     * defaults to zero. Money-safety still fires: a two-entity tenant whose
     * fee happens to be entirely unrecorded pre-migration reconciles to zero
     * either way, so this asserts the honest reading (zero, not a refusal
     * bypass) rather than pretending a real figure was recovered.
     */
    @Test
    void aRowWithNoRecordedFeeReadsZeroRatherThanNull() {
        insertAggregate(ENTITY_A, "TELEGRAM", "DINE_IN", 4, 200_000L, 0L);

        var result = queries.run(new ReportQuery(
                TENANT,
                DAY,
                DAY,
                List.of("delivery_fee.v1"),
                List.of(Grain.Dimension.LEGAL_ENTITY),
                List.of(),
                List.of(),
                List.of()));

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst().values().get("delivery_fee.v1")).isZero();
    }

    /** One {@code agg_branch_day} row — money fields only, matching what the grid/roll-up tabs read. */
    private void insertAggregate(
            UUID legalEntityId, String channelCode, String fulfilmentType, int orderCount, long grossSom, long feeSom) {
        jdbc.sql("""
                INSERT INTO reporting.agg_branch_day (
                    tenant_id, business_date, location_id, legal_entity_id, channel_code,
                    fulfilment_type, boundary_version, metric_calculation_version, order_count,
                    cancelled_count, gross_som, discount_som, net_som, refunded_som,
                    promised_count, late_count, distinct_customers, new_customers,
                    delivery_fee_som)
                VALUES (:tenantId, :businessDate, :locationId, :legalEntityId, :channelCode,
                    :fulfilmentType, 1, 1, :orderCount, 0, :gross, 0, :gross, 0, 0, 0, 0, 0,
                    :fee)
                """)
                .param("tenantId", TENANT)
                .param("businessDate", DAY)
                .param("locationId", LOCATION)
                .param("legalEntityId", legalEntityId)
                .param("channelCode", channelCode)
                .param("fulfilmentType", fulfilmentType)
                .param("orderCount", orderCount)
                .param("gross", grossSom)
                .param("fee", feeSom)
                .update();
    }

    private void seedTenant(UUID tenantId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "reporting-delivery-fee-" + tenantId)
                .update();
    }
}
