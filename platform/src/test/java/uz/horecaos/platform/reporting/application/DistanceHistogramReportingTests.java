package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.reporting.domain.DistanceBucketSet;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Row 7.10b (wave 10 w5-reports-exports): the geography page's distance histogram —
 * a live bucket count over {@code reporting.fact_delivery.distance_meters} against
 * {@link DistanceBucketSet}'s fixed boundaries.
 *
 * <p>Rows are inserted straight into {@code reporting.fact_delivery} rather than driven
 * through the courier-accrual/day-close pipeline, on the same footing {@code
 * OrderGrainReportingTests}' own doc argues for {@code fact_order}: the fact table is
 * derived and rebuildable by design, and this read only ever touches it.
 */
class DistanceHistogramReportingTests {

    private static final UUID TENANT = UUID.fromString("018f9c10-2000-7000-8000-00000000e001");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9c10-2000-7000-8000-00000000e0ff");
    private static final UUID LOCATION_A = UUID.fromString("018f9c10-2000-7000-8000-00000000e003");
    private static final UUID LOCATION_B = UUID.fromString("018f9c10-2000-7000-8000-00000000e004");

    private static final LocalDate DAY = LocalDate.of(2026, 8, 21);

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

        jdbc.sql("TRUNCATE TABLE reporting.fact_delivery, reporting.business_day_policies")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        store = new JdbcReportingStore(jdbc);
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T04:00:00Z"), ZoneOffset.UTC);
        queries = new ReportQueryService(store, new BusinessDayService(store), clock);

        seedTenant(TENANT);
        seedTenant(OTHER_TENANT);
    }

    @Test
    void bucketsDeliveriesByDistanceIntoTheFixedRanges() {
        insertDelivery("D-500M", LOCATION_A, 500);
        insertDelivery("D-1500M", LOCATION_A, 1_500);
        insertDelivery("D-2500M", LOCATION_A, 2_500);
        insertDelivery("D-4000M", LOCATION_A, 4_000);
        insertDelivery("D-6000M", LOCATION_A, 6_000);
        insertDelivery("D-9000M", LOCATION_A, 9_000);

        List<JdbcReportingStore.DistanceBucketRow> rows = store.readDistanceBuckets(TENANT, DAY, DAY, List.of());

        assertThat(rows)
                .extracting(JdbcReportingStore.DistanceBucketRow::bucketCode)
                .containsExactlyInAnyOrder("UNDER_1KM", "KM1_2", "KM2_3", "KM3_5", "KM5_8", "OVER_8KM");
        assertThat(bucketCount(rows, "UNDER_1KM")).isEqualTo(1);
        assertThat(bucketCount(rows, "OVER_8KM")).isEqualTo(1);
    }

    @Test
    void aBucketBoundaryFallsIntoTheHigherBucketNeverBoth() {
        // Half-open: exactly 1000m belongs to KM1_2, not UNDER_1KM — the same
        // exhaustive-and-non-overlapping property SlaBucketSet's own doc argues for.
        insertDelivery("D-EXACT-1000", LOCATION_A, 1_000);

        List<JdbcReportingStore.DistanceBucketRow> rows = store.readDistanceBuckets(TENANT, DAY, DAY, List.of());

        assertThat(bucketCount(rows, "KM1_2")).isEqualTo(1);
        assertThat(bucketCount(rows, "UNDER_1KM")).isEqualTo(0);
    }

    @Test
    void locationFilterNarrowsTheDeliveriesConsidered() {
        insertDelivery("D-A", LOCATION_A, 500);
        insertDelivery("D-B", LOCATION_B, 500);

        List<JdbcReportingStore.DistanceBucketRow> rows =
                store.readDistanceBuckets(TENANT, DAY, DAY, List.of(LOCATION_A));

        assertThat(rows).hasSize(1);
        assertThat(bucketCount(rows, "UNDER_1KM")).isEqualTo(1);
    }

    @Test
    void deliveriesNeverCrossTenants() {
        insertDeliveryForTenant(TENANT, "MINE", LOCATION_A, 500);
        insertDeliveryForTenant(OTHER_TENANT, "THEIRS", LOCATION_A, 500);

        List<JdbcReportingStore.DistanceBucketRow> rows = store.readDistanceBuckets(TENANT, DAY, DAY, List.of());

        int total = rows.stream()
                .mapToInt(JdbcReportingStore.DistanceBucketRow::deliveryCount)
                .sum();
        assertThat(total).isEqualTo(1);
    }

    @Test
    void theServiceZeroFillsEveryBucketDistanceBucketSetDefinesEvenWithNoDeliveriesAtAll() {
        var result = queries.distanceBuckets(TENANT, DAY, DAY, List.of());

        assertThat(result.buckets())
                .extracting(JdbcReportingStore.DistanceBucketRow::bucketCode)
                .containsExactlyElementsOf(DistanceBucketSet.codes());
        assertThat(result.buckets())
                .as("no deliveries in range means every bucket is a real zero, not a missing row")
                .allSatisfy(row -> assertThat(row.deliveryCount()).isZero());
        assertThat(result.provenance().timezone()).isEqualTo("Asia/Tashkent");
    }

    @Test
    void theServiceZeroFillsAnUnpopulatedBucketAlongsideRealCounts() {
        insertDelivery("D-ONLY", LOCATION_A, 500);

        var result = queries.distanceBuckets(TENANT, DAY, DAY, List.of());

        assertThat(bucketCount(result.buckets(), "UNDER_1KM")).isEqualTo(1);
        assertThat(bucketCount(result.buckets(), "OVER_8KM"))
                .as("a bucket with no deliveries is still a row, holding zero")
                .isEqualTo(0);
        assertThat(result.buckets()).hasSize(DistanceBucketSet.codes().size());
    }

    @Test
    void theServiceRefusesARangeThatEndsBeforeItStarts() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> queries.distanceBuckets(TENANT, DAY, DAY.minusDays(1), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ----------------------------------------------------------------- fixtures

    private static int bucketCount(List<JdbcReportingStore.DistanceBucketRow> rows, String code) {
        return rows.stream()
                .filter(row -> row.bucketCode().equals(code))
                .mapToInt(JdbcReportingStore.DistanceBucketRow::deliveryCount)
                .findFirst()
                .orElse(0);
    }

    private void insertDelivery(String seed, UUID locationId, int distanceMeters) {
        insertDeliveryForTenant(TENANT, seed, locationId, distanceMeters);
    }

    private void insertDeliveryForTenant(UUID tenantId, String seed, UUID locationId, int distanceMeters) {
        UUID earningId = UUID.nameUUIDFromBytes(
                ("distance-histogram:" + tenantId + ":" + seed).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        OffsetDateTime acceptedAt = DAY.atTime(9, 0).atOffset(ZoneOffset.UTC);
        OffsetDateTime deliveredAt = acceptedAt.plusMinutes(20);

        jdbc.sql("""
                        INSERT INTO reporting.fact_delivery (
                            tenant_id, courier_assignment_earning_id, business_date, boundary_version,
                            metric_calculation_version, courier_id, location_id, shipment_id,
                            assignment_attempt_id, distance_meters, distance_source, on_time_outcome,
                            accepted_at, delivered_at, transit_seconds)
                        VALUES (
                            :tenantId, :earningId, :businessDate, 1,
                            1, :courierId, :locationId, :shipmentId,
                            :assignmentAttemptId, :distanceMeters, 'ROUTING', 'ON_TIME',
                            :acceptedAt, :deliveredAt, :transitSeconds)
                        """)
                .param("tenantId", tenantId)
                .param("earningId", earningId)
                .param("businessDate", DAY)
                .param("courierId", UUID.randomUUID())
                .param("locationId", locationId)
                .param("shipmentId", UUID.randomUUID())
                .param("assignmentAttemptId", UUID.randomUUID())
                .param("distanceMeters", distanceMeters)
                .param("acceptedAt", acceptedAt)
                .param("deliveredAt", deliveredAt)
                .param("transitSeconds", 1_200)
                .update();
    }

    private void seedTenant(UUID tenantId) {
        jdbc.sql("""
                        INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                            default_timezone, status, version)
                        VALUES (:id, :slug, 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                        """)
                .param("id", tenantId)
                .param("slug", "distance-histogram-" + tenantId)
                .update();
    }
}
