package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Reports 7.8's honest historical-average read (ADR 0043's implementation
 * status, owner decision 2026-09-05): "how many orders actually happened, hour
 * by hour, on the location's most recent occurrences of this weekday" — never
 * a prediction, never a smoothed number over a sample too thin to mean
 * anything.
 *
 * <p>Every date below is a real, hand-verified weekday (ISO-8601: Tuesday = 2,
 * Wednesday = 3): 2026-07-07 through 2026-08-25 are eight consecutive
 * Tuesdays, and 2026-07-08 is the Wednesday in the same week as the first one
 * — seeded specifically to prove the weekday filter, not merely assumed
 * correct.
 *
 * <p>Rows are inserted straight into {@code reporting.fact_order}, the same
 * choice {@code VariantSalesReportingTests} and {@code
 * OrderGrainReportingTests} make and explain: the fact table is derived and
 * rebuildable by design, so a store-level test proving the read is correct
 * does not need to re-prove the close job that would have produced the same
 * rows.
 */
class DemandHistoryReportingTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-3000-7000-8000-00000000e001");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-3000-7000-8000-00000000e0ff");
    private static final UUID BRAND = UUID.fromString("018f6f4e-3000-7000-8000-00000000e002");
    private static final UUID LOCATION_A = UUID.fromString("018f6f4e-3000-7000-8000-00000000e003");
    private static final UUID LOCATION_B = UUID.fromString("018f6f4e-3000-7000-8000-00000000e004");

    /** ISO-8601: Tuesday = 2, matching `java.time.DayOfWeek#getValue()` and Postgres `isodow`. */
    private static final int TUESDAY = 2;

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    /** Eight consecutive Tuesdays, oldest first. Verified with the platform's own `date` binary before writing this file. */
    private static final LocalDate TUE1 = LocalDate.of(2026, 7, 7);

    private static final LocalDate TUE2 = LocalDate.of(2026, 7, 14);
    private static final LocalDate TUE3 = LocalDate.of(2026, 7, 21);
    private static final LocalDate TUE4 = LocalDate.of(2026, 7, 28);
    private static final LocalDate TUE5 = LocalDate.of(2026, 8, 4);
    private static final LocalDate TUE6 = LocalDate.of(2026, 8, 11);
    private static final LocalDate TUE7 = LocalDate.of(2026, 8, 18);
    private static final LocalDate TUE8 = LocalDate.of(2026, 8, 25);

    /**
     * The Wednesday immediately after TUE8, the newest seeded Tuesday. Seeded
     * with more orders than any Tuesday and dated more recently than all of
     * them, so a broken or missing weekday filter would let it displace a real
     * Tuesday out of the top-4 sample — an older, off-schedule Wednesday
     * could not prove that, since it would already rank behind every Tuesday
     * on recency alone.
     */
    private static final LocalDate WED_RECENT = LocalDate.of(2026, 8, 26);

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

        jdbc.sql("TRUNCATE TABLE reporting.fact_order_line, reporting.fact_order, " + "reporting.business_day_policies")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        store = new JdbcReportingStore(jdbc);
        // A Wednesday, one day after the newest seeded Tuesday (2026-08-25) —
        // both dates sit safely inside the lookback window regardless of it.
        Clock clock = Clock.fixed(Instant.parse("2026-08-26T04:00:00Z"), ZoneOffset.UTC);
        queries = new ReportQueryService(store, new BusinessDayService(store), clock);

        seedTenant(TENANT);
        seedTenant(OTHER_TENANT);
    }

    // --------------------------------------------------------- store level

    @Test
    void theMostRecentSampleSizeMatchingWeekdaysAreReturnedNewestFirstAndOtherWeekdaysAreExcluded() {
        for (LocalDate tuesday : List.of(TUE1, TUE2, TUE3, TUE4, TUE5, TUE6, TUE7, TUE8)) {
            insertOrder(TENANT, LOCATION_A, tuesday, 18, "COMPLETED");
        }
        // Newer than every seeded Tuesday. Only the weekday filter keeps this
        // out of the top-4 by recency — see WED_RECENT's own doc.
        insertOrders(TENANT, LOCATION_A, WED_RECENT, 18, 50, "COMPLETED");

        JdbcReportingStore.DemandSample sample = readAllHistory(TENANT, LOCATION_A, TUESDAY, 4);

        assertThat(sample.sampleDates()).containsExactly(TUE8, TUE7, TUE6, TUE5);
    }

    @Test
    void hourCountsAreGroupedByLocalHourInTheTenantTimezoneNotByUtcHour() {
        // 23:30 Tashkent (UTC+5) is 18:30 UTC the same calendar day — if the
        // read used the raw UTC hour instead of converting first, this would
        // land in hour 18, not 23.
        insertOrderAt(
                TENANT, LOCATION_A, TUE8, TUE8.atTime(23, 30).atZone(TASHKENT).toInstant(), "COMPLETED");
        insertOrder(TENANT, LOCATION_A, TUE8, 9, "COMPLETED");

        JdbcReportingStore.DemandSample sample = readAllHistory(TENANT, LOCATION_A, TUESDAY, 4);

        Map<Integer, Integer> hours = hourCountsFor(sample, TUE8);
        assertThat(hours).containsEntry(23, 1).containsEntry(9, 1).doesNotContainKey(18);
    }

    @Test
    void cancelledOrdersOtherTenantsAndOtherLocationsAreAllExcluded() {
        insertOrder(TENANT, LOCATION_A, TUE8, 12, "COMPLETED");
        insertOrder(TENANT, LOCATION_A, TUE8, 12, "CANCELLED");
        insertOrder(OTHER_TENANT, LOCATION_A, TUE8, 12, "COMPLETED");
        insertOrder(TENANT, LOCATION_B, TUE8, 12, "COMPLETED");

        JdbcReportingStore.DemandSample sample = readAllHistory(TENANT, LOCATION_A, TUESDAY, 4);

        assertThat(hourCountsFor(sample, TUE8)).containsEntry(12, 1);
    }

    @Test
    void noQualifyingOrderAtAllReturnsAnEmptySample() {
        JdbcReportingStore.DemandSample sample = readAllHistory(TENANT, LOCATION_A, TUESDAY, 4);

        assertThat(sample.sampleDates()).isEmpty();
        assertThat(sample.hourCounts()).isEmpty();
    }

    // ------------------------------------------------------- service level

    @Test
    void averageIsTheMeanOverEveryQualifyingDateIncludingTheOnesWithNoOrderInThatHour() {
        // Hour 20: two of the four sample Tuesdays have orders, two have
        // none. A denominator of "dates with at least one order in this hour"
        // would report 8/2 = 4.0; the honest denominator is the sample size,
        // 8/4 = 2.0.
        insertOrders(TENANT, LOCATION_A, TUE6, 20, 3, "COMPLETED");
        insertOrders(TENANT, LOCATION_A, TUE8, 20, 5, "COMPLETED");
        // TUE5 and TUE7 trade at other hours, so they still qualify as active
        // Tuesdays, just with a genuine zero at hour 20.
        insertOrder(TENANT, LOCATION_A, TUE5, 9, "COMPLETED");
        insertOrder(TENANT, LOCATION_A, TUE7, 9, "COMPLETED");

        var result = queries.demandHistory(TENANT, LOCATION_A, TUESDAY, 4);

        assertThat(result.sampleDates()).containsExactly(TUE8, TUE7, TUE6, TUE5);
        var hour20 = result.hours().get(20);
        assertThat(hour20.totalOrders()).isEqualTo(8);
        assertThat(hour20.averageOrders()).isEqualTo(2.0);
        assertThat(hour20.ordersByDate()).containsEntry(TUE5, 0).containsEntry(TUE7, 0);
    }

    @Test
    void olderOccurrencesBeyondTheRequestedSampleSizeDoNotPullTheAverage() {
        insertOrders(TENANT, LOCATION_A, TUE1, 18, 50, "COMPLETED");
        insertOrders(TENANT, LOCATION_A, TUE2, 18, 50, "COMPLETED");
        insertOrders(TENANT, LOCATION_A, TUE5, 18, 2, "COMPLETED");
        insertOrders(TENANT, LOCATION_A, TUE6, 18, 4, "COMPLETED");
        insertOrders(TENANT, LOCATION_A, TUE7, 18, 6, "COMPLETED");
        insertOrders(TENANT, LOCATION_A, TUE8, 18, 8, "COMPLETED");

        var result = queries.demandHistory(TENANT, LOCATION_A, TUESDAY, 4);

        assertThat(result.sampleDates()).doesNotContain(TUE1, TUE2);
        assertThat(result.hours().get(18).totalOrders()).isEqualTo(20);
        assertThat(result.hours().get(18).averageOrders()).isEqualTo(5.0);
    }

    @Test
    void belowMinimumSampleTheAverageIsNullButTheRawPerDateCountsAreStillReturned() {
        insertOrders(TENANT, LOCATION_A, TUE7, 12, 3, "COMPLETED");
        insertOrders(TENANT, LOCATION_A, TUE8, 12, 7, "COMPLETED");

        var result = queries.demandHistory(TENANT, LOCATION_A, TUESDAY, 4);

        assertThat(result.sampleDates()).hasSize(2);
        assertThat(result.minimumSampleSize()).isEqualTo(3);
        var hour12 = result.hours().get(12);
        assertThat(hour12.averageOrders()).isNull();
        assertThat(hour12.totalOrders()).isEqualTo(10);
        assertThat(hour12.ordersByDate()).containsEntry(TUE7, 3).containsEntry(TUE8, 7);
    }

    @Test
    void aLocationWithNoHistoryOnThisWeekdayGetsAnEmptySampleAndNoHourLooksConfident() {
        var result = queries.demandHistory(TENANT, LOCATION_B, TUESDAY, 4);

        assertThat(result.sampleDates()).isEmpty();
        assertThat(result.hours()).hasSize(24);
        assertThat(result.hours()).allSatisfy(hour -> {
            assertThat(hour.averageOrders()).isNull();
            assertThat(hour.totalOrders()).isZero();
            assertThat(hour.ordersByDate()).isEmpty();
        });
    }

    @Test
    void theResponseCarriesBothWhatWasRequestedAndWhatWasActuallyFound() {
        insertOrder(TENANT, LOCATION_A, TUE8, 9, "COMPLETED");
        insertOrder(TENANT, LOCATION_A, TUE7, 9, "COMPLETED");

        var result = queries.demandHistory(TENANT, LOCATION_A, TUESDAY, 4);

        assertThat(result.requestedSampleSize()).isEqualTo(4);
        assertThat(result.sampleDates()).hasSize(2);
        assertThat(result.provenance().timezone()).isEqualTo("Asia/Tashkent");
    }

    // ----------------------------------------------------------------- fixtures

    private JdbcReportingStore.DemandSample readAllHistory(
            UUID tenantId, UUID locationId, int weekday, int sampleSize) {
        return store.readDemandHistory(
                tenantId,
                locationId,
                weekday,
                LocalDate.of(2020, 1, 1),
                LocalDate.of(2030, 1, 1),
                "Asia/Tashkent",
                sampleSize);
    }

    private static Map<Integer, Integer> hourCountsFor(JdbcReportingStore.DemandSample sample, LocalDate date) {
        Map<Integer, Integer> hours = new HashMap<>();
        for (JdbcReportingStore.HourCount count : sample.hourCounts()) {
            if (count.businessDate().equals(date)) {
                hours.put(count.hourOfDay(), count.orderCount());
            }
        }
        return hours;
    }

    private static int sequence = 0;

    private static UUID orderId(String seed) {
        return UUID.nameUUIDFromBytes(("demand-history-order:" + seed).getBytes(StandardCharsets.UTF_8));
    }

    private void insertOrders(
            UUID tenantId, UUID locationId, LocalDate businessDate, int localHour, int count, String terminalStatus) {
        for (int i = 0; i < count; i++) {
            insertOrder(tenantId, locationId, businessDate, localHour, terminalStatus);
        }
    }

    private void insertOrder(
            UUID tenantId, UUID locationId, LocalDate businessDate, int localHour, String terminalStatus) {
        insertOrderAt(
                tenantId,
                locationId,
                businessDate,
                businessDate.atTime(localHour, 0).atZone(TASHKENT).toInstant(),
                terminalStatus);
    }

    private void insertOrderAt(
            UUID tenantId, UUID locationId, LocalDate businessDate, Instant occurredAt, String terminalStatus) {
        UUID orderId = orderId(tenantId + ":" + sequence++);
        OffsetDateTime occurredAtOffset = occurredAt.atOffset(ZoneOffset.UTC);

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("orderId", orderId);
        params.put("businessDate", businessDate);
        params.put("boundaryVersion", 1);
        params.put("occurredAt", occurredAtOffset);
        params.put("brandId", BRAND);
        params.put("locationId", locationId);
        params.put("channelCode", "TELEGRAM");
        params.put("fulfilmentType", "DELIVERY");
        params.put("terminalStatus", terminalStatus);
        params.put("lineCount", 1);
        params.put("itemCount", 1);
        params.put("metricCalculationVersion", 1);
        params.put("sourceOrderVersion", 1);

        jdbc.sql("""
                INSERT INTO reporting.fact_order (
                    tenant_id, order_id, business_date, boundary_version, occurred_at,
                    brand_id, location_id, channel_code, fulfilment_type, terminal_status,
                    gross_revenue_som, discount_som, delivery_fee_som, tax_som, net_revenue_som,
                    line_count, item_count, metric_calculation_version, source_order_version)
                VALUES (
                    :tenantId, :orderId, :businessDate, :boundaryVersion, :occurredAt,
                    :brandId, :locationId, :channelCode, :fulfilmentType, :terminalStatus,
                    0, 0, 0, 0, 0,
                    :lineCount, :itemCount, :metricCalculationVersion, :sourceOrderVersion)
                """).params(params).update();
    }

    private void seedTenant(UUID tenantId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "demand-history-" + tenantId)
                .update();
    }
}
