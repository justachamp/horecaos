package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore.ZoneDensityRow;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Row 7.10 (ADR 0145 decision 8): the order-density view's data -- deliveries per delivery zone.
 *
 * <p>ADR 0037 kept coordinates out of {@code DeliveryFeeResolved} on purpose: "the heat-map
 * consumers need the zone, not the doorstep". So the density view reads the zone that
 * {@code reporting.fact_delivery_fee_resolution} already carries, and these tests pin the three
 * ways that read can lie: a delivery counted in the wrong zone, the deliveries <em>no</em> zone
 * covered dropped (the number that exposes a badly cut zone), and one tenant's deliveries counted
 * in another's map.
 *
 * <p>Rows go straight into the fact table, on the same footing as
 * {@code DistanceHistogramReportingTests}: the fact is derived and rebuildable, and this read
 * touches nothing else.
 */
class ZoneDensityReportingTests {

    private static final UUID TENANT = UUID.fromString("018f9c10-2000-7000-8000-00000000f001");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9c10-2000-7000-8000-00000000f0ff");
    private static final UUID LOCATION_A = UUID.fromString("018f9c10-2000-7000-8000-00000000f003");
    private static final UUID LOCATION_B = UUID.fromString("018f9c10-2000-7000-8000-00000000f004");
    private static final UUID ZONE_CENTRE = UUID.fromString("018f9c10-2000-7000-8000-00000000f0a1");
    private static final UUID ZONE_RING = UUID.fromString("018f9c10-2000-7000-8000-00000000f0a2");

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

        jdbc.sql("TRUNCATE TABLE reporting.fact_delivery_fee_resolution, reporting.business_day_policies")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        store = new JdbcReportingStore(jdbc);
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T04:00:00Z"), ZoneOffset.UTC);
        queries = new ReportQueryService(store, new BusinessDayService(store), clock);

        seedTenant(TENANT);
        seedTenant(OTHER_TENANT);
    }

    @Test
    void countsEachZonesDeliveriesAndSumsItsFeesInMinorUnits() {
        resolution("A1", TENANT, LOCATION_A, ZONE_CENTRE, 12_000);
        resolution("A2", TENANT, LOCATION_A, ZONE_CENTRE, 15_000);
        resolution("A3", TENANT, LOCATION_A, ZONE_RING, 20_000);

        List<ZoneDensityRow> rows = store.readZoneDensity(TENANT, DAY, DAY, List.of());

        assertThat(rows).hasSize(2);
        ZoneDensityRow centre = rowOf(rows, ZONE_CENTRE);
        assertThat(centre.deliveryCount()).isEqualTo(2);
        assertThat(centre.totalFeeMinor()).isEqualTo(27_000L);
        assertThat(centre.currency()).isEqualTo("UZS");
        assertThat(rowOf(rows, ZONE_RING).deliveryCount()).isEqualTo(1);
        assertThat(rows.get(0).zoneId())
                .as("the busiest zone is listed first, so a truncated list loses the quiet ones")
                .isEqualTo(ZONE_CENTRE);
    }

    @Test
    void deliveriesNoDrawnZoneCoveredComeBackAsTheGroupWithNoZone() {
        // A resolution that fell through to the branch's own tariff names no zone. Dropping it
        // would hide exactly what this view is for: orders that a badly cut zone leaves outside.
        resolution("IN-ZONE", TENANT, LOCATION_A, ZONE_CENTRE, 12_000);
        resolution("UNCOVERED-1", TENANT, LOCATION_A, null, 18_000);
        resolution("UNCOVERED-2", TENANT, LOCATION_A, null, 18_000);
        resolution("UNCOVERED-3", TENANT, LOCATION_A, null, 18_000);

        List<ZoneDensityRow> rows = store.readZoneDensity(TENANT, DAY, DAY, List.of());

        ZoneDensityRow uncovered = rowOf(rows, null);
        assertThat(uncovered.deliveryCount()).isEqualTo(3);
        assertThat(uncovered.totalFeeMinor()).isEqualTo(54_000L);
        assertThat(rows).extracting(ZoneDensityRow::deliveryCount).containsExactly(3, 1);
    }

    @Test
    void theBranchFilterNarrowsTheDeliveriesCounted() {
        resolution("A", TENANT, LOCATION_A, ZONE_CENTRE, 12_000);
        resolution("B", TENANT, LOCATION_B, ZONE_CENTRE, 12_000);

        List<ZoneDensityRow> rows = store.readZoneDensity(TENANT, DAY, DAY, List.of(LOCATION_A));

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).deliveryCount()).isEqualTo(1);
    }

    @Test
    void theRangeIsInclusiveOfBothDaysAndNoMore() {
        resolutionOn("BEFORE", TENANT, DAY.minusDays(1), ZONE_CENTRE);
        resolutionOn("FIRST", TENANT, DAY, ZONE_CENTRE);
        resolutionOn("LAST", TENANT, DAY.plusDays(1), ZONE_CENTRE);
        resolutionOn("AFTER", TENANT, DAY.plusDays(2), ZONE_CENTRE);

        List<ZoneDensityRow> rows = store.readZoneDensity(TENANT, DAY, DAY.plusDays(1), List.of());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).deliveryCount()).isEqualTo(2);
    }

    @Test
    void anotherTenantsDeliveriesAreNeverInThisTenantsMap() {
        resolution("MINE", TENANT, LOCATION_A, ZONE_CENTRE, 12_000);
        resolution("THEIRS-1", OTHER_TENANT, LOCATION_A, ZONE_CENTRE, 12_000);
        resolution("THEIRS-2", OTHER_TENANT, LOCATION_A, null, 12_000);

        List<ZoneDensityRow> rows = store.readZoneDensity(TENANT, DAY, DAY, List.of());

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).deliveryCount()).isEqualTo(1);
        assertThat(rows.get(0).zoneId()).isEqualTo(ZONE_CENTRE);
    }

    @Test
    void feesInDifferentCurrenciesAreNeverAddedTogether() {
        resolutionIn("UZS-ONE", TENANT, ZONE_CENTRE, "UZS", 12_000);
        resolutionIn("USD-ONE", TENANT, ZONE_CENTRE, "USD", 300);

        List<ZoneDensityRow> rows = store.readZoneDensity(TENANT, DAY, DAY, List.of());

        assertThat(rows).hasSize(2);
        assertThat(rows)
                .extracting(ZoneDensityRow::currency, ZoneDensityRow::totalFeeMinor)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("UZS", 12_000L),
                        org.assertj.core.groups.Tuple.tuple("USD", 300L));
    }

    @Test
    void theServiceAnswersWithProvenanceAndAnEmptyListForARangeWithNoDeliveries() {
        var result = queries.zoneDensity(TENANT, DAY, DAY, List.of());

        assertThat(result.rows())
                .as("no deliveries is an empty map, not an error and not a zone with a made-up zero")
                .isEmpty();
        assertThat(result.provenance().timezone()).isEqualTo("Asia/Tashkent");
    }

    @Test
    void theServiceRefusesARangeThatEndsBeforeItStarts() {
        assertThatThrownBy(() -> queries.zoneDensity(TENANT, DAY, DAY.minusDays(1), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ----------------------------------------------------------------- fixtures

    private static ZoneDensityRow rowOf(List<ZoneDensityRow> rows, @Nullable UUID zoneId) {
        return rows.stream()
                .filter(row -> java.util.Objects.equals(row.zoneId(), zoneId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no row for zone " + zoneId));
    }

    private void resolution(String seed, UUID tenantId, UUID locationId, @Nullable UUID zoneId, long feeMinor) {
        insert(seed, tenantId, DAY, locationId, zoneId, "UZS", feeMinor);
    }

    private void resolutionOn(String seed, UUID tenantId, LocalDate day, UUID zoneId) {
        insert(seed, tenantId, day, LOCATION_A, zoneId, "UZS", 10_000);
    }

    private void resolutionIn(String seed, UUID tenantId, UUID zoneId, String currency, long feeMinor) {
        insert(seed, tenantId, DAY, LOCATION_A, zoneId, currency, feeMinor);
    }

    private void insert(
            String seed,
            UUID tenantId,
            LocalDate day,
            UUID locationId,
            @Nullable UUID zoneId,
            String currency,
            long feeMinor) {
        UUID resolutionId = UUID.nameUUIDFromBytes(
                ("zone-density:" + tenantId + ":" + seed).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        OffsetDateTime resolvedAt = day.atTime(12, 0).atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                        INSERT INTO reporting.fact_delivery_fee_resolution (
                            tenant_id, resolution_id, business_date, boundary_version,
                            metric_calculation_version, location_id, tariff_id, tariff_version,
                            zone_id, band_sequence, courier_id, order_id, shipment_id,
                            final_fee_minor, currency, resolved_at)
                        VALUES (
                            :tenantId, :resolutionId, :businessDate, 1,
                            1, :locationId, :tariffId, 1,
                            :zoneId, 1, :courierId, :orderId, :shipmentId,
                            :feeMinor, :currency, :resolvedAt)
                        """)
                .param("tenantId", tenantId)
                .param("resolutionId", resolutionId)
                .param("businessDate", day)
                .param("locationId", locationId)
                .param("tariffId", UUID.randomUUID())
                .param("zoneId", zoneId)
                .param("courierId", UUID.randomUUID())
                .param("orderId", UUID.randomUUID())
                .param("shipmentId", UUID.randomUUID())
                .param("feeMinor", feeMinor)
                .param("currency", currency)
                .param("resolvedAt", resolvedAt)
                .update();
    }

    private void seedTenant(UUID tenantId) {
        jdbc.sql("""
                        INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                            default_timezone, status, version)
                        VALUES (:id, :slug, 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                        """)
                .param("id", tenantId)
                .param("slug", "zone-density-" + tenantId)
                .update();
    }
}
