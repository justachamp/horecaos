package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Wave T06 (7.3/7.3a): the branch leaderboard's three missing columns, plus
 * the SLA table's own median.
 *
 * <p>Two of the leaderboard columns need no new SQL at all — {@code
 * groupBy=['LOCATION', 'FULFILMENT_TYPE']} (plus {@code CHANNEL} for the
 * aggregator slice) over {@code reporting.agg_branch_day}, which has carried
 * both columns since V0031 — and {@code orders.promised.v1}, the on-time
 * percentage's own denominator. The other two are real new grouped queries:
 * {@link JdbcReportingStore#medianSecondsToReadyByLocation} (7.3's
 * preparation-time fan-out, collapsed to one call) and {@link
 * JdbcReportingStore#medianSecondsTotalByLocation} (7.3a's «Медиана» column).
 *
 * <p>Rows go straight into {@code reporting.fact_order} and {@code
 * reporting.agg_branch_day} rather than through {@code DayCloseService}, on
 * the same footing {@code OrderGrainReportingTests} already establishes: both
 * are derived, rebuildable fact tables with no foreign key back to the
 * modules that produced them, and a store-level test does not need to re-run
 * the close job that would have written the same rows.
 */
class BranchLeaderboardReportingTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-3000-7000-8000-00000000d001");
    private static final UUID LOCATION_A = UUID.fromString("018f6f4e-3000-7000-8000-00000000d002");
    private static final UUID LOCATION_B = UUID.fromString("018f6f4e-3000-7000-8000-00000000d003");
    private static final UUID LOCATION_C = UUID.fromString("018f6f4e-3000-7000-8000-00000000d004");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-3000-7000-8000-00000000d0ff");

    private static final LocalDate DAY = LocalDate.of(2026, 8, 21);
    private static final LocalDate DAY_2 = DAY.plusDays(1);

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

        jdbc.sql("""
                TRUNCATE TABLE reporting.fact_order, reporting.agg_branch_day,
                    reporting.business_day_policies
                """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        store = new JdbcReportingStore(jdbc);
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T04:00:00Z"), ZoneOffset.UTC);
        queries = new ReportQueryService(store, new BusinessDayService(store), clock);

        seedTenant(TENANT, "branch-leaderboard-tenant");
        seedTenant(OTHER_TENANT, "branch-leaderboard-other-tenant");
    }

    private void seedTenant(UUID tenantId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Osh Markazi', 'UZS',
                    'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
    }

    // ------------------------------------------- groupBy=['LOCATION', 'FULFILMENT_TYPE']

    @Test
    void groupingByLocationAndFulfilmentTypeSplitsOrdersCountPerBranch() {
        // LOCATION_A: 5 delivery orders on the tenant's own channel, plus 2 more
        // delivery orders through an aggregator channel — folded into the same
        // FULFILMENT_TYPE slice, exactly what "an aggregate already keyed by
        // fulfilment type" means. 3 pickup orders, one channel.
        insertAggregate(LOCATION_A, "TELEGRAM", "DELIVERY", 5);
        insertAggregate(LOCATION_A, "AGGREGATOR_X", "DELIVERY", 2);
        insertAggregate(LOCATION_A, "TELEGRAM", "PICKUP", 3);
        // LOCATION_B: 1 delivery, 4 pickup.
        insertAggregate(LOCATION_B, "TELEGRAM", "DELIVERY", 1);
        insertAggregate(LOCATION_B, "TELEGRAM", "PICKUP", 4);

        var result = queries.run(new ReportQuery(
                TENANT,
                DAY,
                DAY,
                List.of("orders.count.v1"),
                List.of(Grain.Dimension.LOCATION, Grain.Dimension.FULFILMENT_TYPE),
                List.of(),
                List.of(),
                List.of()));

        Map<String, Long> byLocationAndType = new HashMap<>();
        for (var row : result.rows()) {
            byLocationAndType.put(
                    row.slice().locationId() + "|" + row.slice().fulfilmentType(),
                    row.values().get("orders.count.v1"));
        }

        assertThat(byLocationAndType)
                .as("two channels of the same fulfilment type fold into one slice")
                .containsEntry(LOCATION_A + "|DELIVERY", 7L)
                .containsEntry(LOCATION_A + "|PICKUP", 3L)
                .containsEntry(LOCATION_B + "|DELIVERY", 1L)
                .containsEntry(LOCATION_B + "|PICKUP", 4L);
    }

    @Test
    void groupingByLocationAndChannelIsolatesTheAggregatorSlice() {
        // The leaderboard's "Агрегаторы" count is the CHANNEL axis, not
        // FULFILMENT_TYPE: an aggregator order is still a DELIVERY order, so
        // only grouping by channel can tell it apart from the tenant's own
        // delivery channel.
        insertAggregate(LOCATION_A, "TELEGRAM", "DELIVERY", 5);
        insertAggregate(LOCATION_A, "AGGREGATOR_X", "DELIVERY", 2);

        var result = queries.run(new ReportQuery(
                TENANT,
                DAY,
                DAY,
                List.of("orders.count.v1"),
                List.of(Grain.Dimension.LOCATION, Grain.Dimension.CHANNEL),
                List.of(),
                List.of(),
                List.of()));

        Map<String, Long> byChannel = new HashMap<>();
        for (var row : result.rows()) {
            byChannel.put(row.slice().channelCode(), row.values().get("orders.count.v1"));
        }

        assertThat(byChannel).containsEntry("TELEGRAM", 5L).containsEntry("AGGREGATOR_X", 2L);
    }

    @Test
    void oneQueryNamingAllThreeAxesAndAllThreeMetricsAnswersEveryLeaderboardColumnTheFrontendAsksFor() {
        // The exact shape branch-sla-report-page.ts's own `load()` sends:
        // metric=[orders.count.v1, orders.promised.v1, orders.late.v1],
        // groupBy=[LOCATION, FULFILMENT_TYPE, CHANNEL] — one request answering
        // the delivery/pickup split, the aggregator count, and the on-time
        // denominator/numerator together, not three separate calls.
        insertFullAggregate(LOCATION_A, "TELEGRAM", "DELIVERY", 5, 5, 1);
        insertFullAggregate(LOCATION_A, "AGGREGATOR_X", "DELIVERY", 2, 2, 0);
        insertFullAggregate(LOCATION_A, "TELEGRAM", "PICKUP", 3, 0, 0);

        var result = queries.run(new ReportQuery(
                TENANT,
                DAY,
                DAY,
                List.of("orders.count.v1", "orders.promised.v1", "orders.late.v1"),
                List.of(Grain.Dimension.LOCATION, Grain.Dimension.FULFILMENT_TYPE, Grain.Dimension.CHANNEL),
                List.of(),
                List.of(),
                List.of()));

        // Folding FULFILMENT_TYPE and CHANNEL away (as the frontend's
        // sumAcrossDays keyed by locationId alone does) must recover the true
        // branch totals: 10 orders, 7 promised, 1 late.
        long orders = 0;
        long promised = 0;
        long late = 0;
        long deliveryCount = 0;
        long aggregatorCount = 0;
        for (var row : result.rows()) {
            long count = Objects.requireNonNull(row.values().get("orders.count.v1"));
            orders += count;
            promised += Objects.requireNonNull(row.values().get("orders.promised.v1"));
            late += Objects.requireNonNull(row.values().get("orders.late.v1"));
            if ("DELIVERY".equals(row.slice().fulfilmentType())) {
                deliveryCount += count;
            }
            if ("AGGREGATOR_X".equals(row.slice().channelCode())) {
                aggregatorCount += count;
            }
        }

        assertThat(orders).isEqualTo(10);
        assertThat(promised).isEqualTo(7);
        assertThat(late).isEqualTo(1);
        assertThat(deliveryCount).isEqualTo(7);
        assertThat(aggregatorCount).isEqualTo(2);
    }

    // ------------------------------------------------- orders.promised.v1

    @Test
    void ordersPromisedSumsAcrossDaysAlongsideOrdersLate() {
        // Day 1: 10 promised, 2 late. Day 2: 6 promised, 1 late. A branch
        // manager's "В норме %" reads (promised − late) / promised over the
        // whole range, not one day's share printed beside the range total —
        // the same defect 7.3a's buildSlaRows fix corrects for the SLA table.
        insertPromiseAggregate(LOCATION_A, DAY, 10, 2);
        insertPromiseAggregate(LOCATION_A, DAY_2, 6, 1);
        // A second branch with a worse on-time record, proving the denominator
        // is per-slice and not folded across branches by accident.
        insertPromiseAggregate(LOCATION_B, DAY, 4, 4);

        var result = queries.run(new ReportQuery(
                TENANT,
                DAY,
                DAY_2,
                List.of("orders.promised.v1", "orders.late.v1"),
                List.of(Grain.Dimension.LOCATION),
                List.of(),
                List.of(),
                List.of()));

        long promisedA = 0;
        long lateA = 0;
        long promisedB = 0;
        long lateB = 0;
        for (var row : result.rows()) {
            if (LOCATION_A.equals(row.slice().locationId())) {
                promisedA += Objects.requireNonNull(row.values().get("orders.promised.v1"));
                lateA += Objects.requireNonNull(row.values().get("orders.late.v1"));
            } else if (LOCATION_B.equals(row.slice().locationId())) {
                promisedB += Objects.requireNonNull(row.values().get("orders.promised.v1"));
                lateB += Objects.requireNonNull(row.values().get("orders.late.v1"));
            }
        }

        assertThat(promisedA).isEqualTo(16);
        assertThat(lateA).isEqualTo(3);
        assertThat(promisedB).isEqualTo(4);
        assertThat(lateB).isEqualTo(4);

        long onTimeSharePercentA = Math.round(100.0 * (promisedA - lateA) / promisedA);
        long onTimeSharePercentB = Math.round(100.0 * (promisedB - lateB) / promisedB);
        assertThat(onTimeSharePercentA).isEqualTo(81);
        assertThat(onTimeSharePercentB)
                .as("every promised order at LOCATION_B was late")
                .isEqualTo(0);
    }

    // ----------------------------------- preparation-time fan-out collapsing

    @Test
    void medianPreparationTimeForEveryBranchComesBackFromOneCall() {
        // Three branches, three different medians — the previous shape needed
        // three separate `medianSecondsToReady` calls, one per branch. This is
        // the one call that replaces all three.
        insertReadyOrder("A-1", LOCATION_A, 600);
        insertReadyOrder("A-2", LOCATION_A, 1_200);
        insertReadyOrder("B-1", LOCATION_B, 300);
        // LOCATION_C never reached READY — must be absent, not a null row.

        List<JdbcReportingStore.LocationMedianRow> rows =
                store.medianSecondsToReadyByLocation(TENANT, DAY, DAY, List.of());

        Map<UUID, Integer> byLocation = new HashMap<>();
        for (JdbcReportingStore.LocationMedianRow row : rows) {
            byLocation.put(row.locationId(), row.medianSeconds());
        }

        // Median of {480 (600-120), 1080 (1200-120)} is 780.
        assertThat(byLocation).containsEntry(LOCATION_A, 780);
        // A single order's own value: 300-120 = 180.
        assertThat(byLocation).containsEntry(LOCATION_B, 180);
        assertThat(byLocation)
                .as("no order reached READY at LOCATION_C: absent, never a zero-second kitchen")
                .doesNotContainKey(LOCATION_C);
        assertThat(rows).hasSize(2);
    }

    @Test
    void medianPreparationTimeByLocationNarrowsToTheRequestedBranches() {
        insertReadyOrder("A-1", LOCATION_A, 600);
        insertReadyOrder("B-1", LOCATION_B, 300);

        List<JdbcReportingStore.LocationMedianRow> rows =
                store.medianSecondsToReadyByLocation(TENANT, DAY, DAY, List.of(LOCATION_A));

        assertThat(rows)
                .extracting(JdbcReportingStore.LocationMedianRow::locationId)
                .containsExactly(LOCATION_A);
    }

    /**
     * 2026-09-14 review: no test in this file seeded a second tenant, so a regression dropping
     * {@code tenant_id} from {@link JdbcReportingStore#medianSecondsToReadyByLocation} would pass
     * every other test here unchanged.
     */
    @Test
    void medianSecondsToReadyByLocationNeverCrossesTenants() {
        insertReadyOrder("A-1", LOCATION_A, 600);
        // OTHER_TENANT: the same location and business date, a wildly different duration --
        // would move the median below if it ever leaked into TENANT's own read.
        insertReadyOrder(OTHER_TENANT, "other-A-1", LOCATION_A, 60_000);

        List<JdbcReportingStore.LocationMedianRow> rows =
                store.medianSecondsToReadyByLocation(TENANT, DAY, DAY, List.of());

        assertThat(rows)
                .extracting(JdbcReportingStore.LocationMedianRow::locationId)
                .containsExactly(LOCATION_A);
        // A single order's own value: 600-120 = 480, unmoved by OTHER_TENANT's row.
        assertThat(rows.getFirst().medianSeconds()).isEqualTo(480);
    }

    @Test
    void theServiceMethodStatesItsProvenanceLikeEveryOtherReport() {
        insertReadyOrder("A-1", LOCATION_A, 600);

        var result = queries.preparationTimeByLocation(TENANT, DAY, DAY, List.of());

        assertThat(result.rows())
                .extracting(JdbcReportingStore.LocationMedianRow::locationId)
                .containsExactly(LOCATION_A);
        assertThat(result.provenance().timezone()).isEqualTo("Asia/Tashkent");
        assertThat(result.provenance().metricVersions()).contains("prep_time.median.v1");
    }

    // ------------------------------------------------- handover-time median (7.3a)

    @Test
    void medianHandoverTimeReadsSecondsTotalNotSecondsToReady() {
        // secondsTotal 600 -> secondsToReady 480 (600-120). The SLA table's own
        // median must read the former, not the leaderboard's prep-time column.
        insertReadyOrder("A-1", LOCATION_A, 600);

        List<JdbcReportingStore.LocationMedianRow> rows =
                store.medianSecondsTotalByLocation(TENANT, DAY, DAY, List.of());

        assertThat(rows)
                .extracting(JdbcReportingStore.LocationMedianRow::medianSeconds)
                .as("seconds_total itself, not seconds_total minus the prep-time offset")
                .containsExactly(600);
    }

    /** Same gap as {@link #medianSecondsToReadyByLocationNeverCrossesTenants}, for {@link
     * JdbcReportingStore#medianSecondsTotalByLocation}. */
    @Test
    void medianSecondsTotalByLocationNeverCrossesTenants() {
        insertReadyOrder("A-1", LOCATION_A, 600);
        insertReadyOrder(OTHER_TENANT, "other-A-1", LOCATION_A, 60_000);

        List<JdbcReportingStore.LocationMedianRow> rows =
                store.medianSecondsTotalByLocation(TENANT, DAY, DAY, List.of());

        assertThat(rows)
                .extracting(JdbcReportingStore.LocationMedianRow::medianSeconds)
                .containsExactly(600);
    }

    @Test
    void slaBucketsCarriesEachBranchsMedianAlongsideTheBucketCounts() {
        // The bucket counts come from agg_sla_bucket_day (the close job's own
        // aggregate); the median is a live percentile over fact_order. Both
        // answer from the one /sla-buckets request.
        store.insertSlaBucket(
                new ReportingFacts.SlaBucketAggregate(TENANT, DAY, "LOCATION", LOCATION_A, 1, "UNDER_30", 1, 10_000));
        insertReadyOrder("A-1", LOCATION_A, 600);
        insertReadyOrder("A-2", LOCATION_A, 1_200);

        var result = queries.slaBuckets(TENANT, DAY, DAY, List.of());

        assertThat(result.buckets()).hasSize(1);
        assertThat(result.medians())
                .extracting(
                        JdbcReportingStore.LocationMedianRow::locationId,
                        JdbcReportingStore.LocationMedianRow::medianSeconds)
                // Median of {600, 1200} is 900.
                .containsExactly(tuple(LOCATION_A, 900));
        assertThat(result.provenance().metricVersions()).contains("handover_time.median.v1");
    }

    // ----------------------------------------------------------------- fixtures

    private static UUID orderId(String seed) {
        return UUID.nameUUIDFromBytes(
                ("branch-leaderboard-order:" + seed).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static OffsetDateTime tashkent(int hour, int minute) {
        // Asia/Tashkent is UTC+5, no daylight saving.
        return DAY.atTime(hour, minute).minusHours(5).atOffset(ZoneOffset.UTC);
    }

    /** One {@code agg_branch_day} row for the grouping tests — money fields are irrelevant to them. */
    private void insertAggregate(UUID locationId, String channelCode, String fulfilmentType, int orderCount) {
        insertFullAggregate(locationId, channelCode, fulfilmentType, orderCount, 0, 0);
    }

    /** {@link #insertAggregate}, naming a promised/late count too — the combined-query test's own fixture. */
    private void insertFullAggregate(
            UUID locationId,
            String channelCode,
            String fulfilmentType,
            int orderCount,
            int promisedCount,
            int lateCount) {
        store.insertAggregate(new ReportingFacts.BranchDayAggregate(
                new ReportingFacts.BranchDayKey(TENANT, DAY, locationId, null, channelCode, fulfilmentType),
                1,
                1,
                orderCount,
                0,
                0L,
                0L,
                0L,
                0L,
                null,
                promisedCount,
                lateCount,
                0,
                0));
    }

    /** One {@code agg_branch_day} row for the on-time-denominator test — the fulfilment/channel axes are irrelevant to it. */
    private void insertPromiseAggregate(UUID locationId, LocalDate day, int promisedCount, int lateCount) {
        store.insertAggregate(new ReportingFacts.BranchDayAggregate(
                new ReportingFacts.BranchDayKey(TENANT, day, locationId, null, "TELEGRAM", "DELIVERY"),
                1,
                1,
                promisedCount,
                0,
                0L,
                0L,
                0L,
                0L,
                null,
                promisedCount,
                lateCount,
                0,
                0));
    }

    /** One {@code fact_order} row that reached READY {@code secondsTotal} seconds after creation. */
    private void insertReadyOrder(String seed, UUID locationId, int secondsTotal) {
        insertReadyOrder(TENANT, seed, locationId, secondsTotal);
    }

    /** {@link #insertReadyOrder}, naming a tenant — the cross-tenant isolation tests' own fixture. */
    private void insertReadyOrder(UUID tenantId, String seed, UUID locationId, int secondsTotal) {
        OffsetDateTime created = tashkent(9, 0);
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("orderId", orderId(tenantId + ":" + seed));
        params.put("businessDate", DAY);
        params.put("boundaryVersion", 1);
        params.put("occurredAt", created);
        params.put("closedAt", created.plusSeconds(secondsTotal));
        params.put("brandId", UUID.randomUUID());
        params.put("locationId", locationId);
        params.put("legalEntityId", (Object) null);
        params.put("channelCode", "TELEGRAM");
        params.put("fulfilmentType", "DELIVERY");
        params.put("terminalStatus", "COMPLETED");
        params.put("cancellationReasonCode", (Object) null);
        params.put("gross", 100_000L);
        params.put("discount", 0L);
        params.put("deliveryFee", 10_000L);
        params.put("tax", 0L);
        params.put("net", 100_000L);
        params.put("lineCount", 1);
        params.put("itemCount", 1);
        params.put("secondsToConfirm", 60);
        // The fan-out this wave collapses reads seconds_to_ready specifically —
        // 120 seconds narrower than secondsTotal, same convention
        // OrderGrainReportingTests#insertRow uses.
        params.put("secondsToReady", Math.max(0, secondsTotal - 120));
        params.put("secondsTotal", secondsTotal);
        params.put("promisedAt", (Object) null);
        params.put("secondsLate", (Object) null);
        params.put("metricCalculationVersion", 1);
        params.put("sourceOrderVersion", 1);

        jdbc.sql("""
                INSERT INTO reporting.fact_order (
                    tenant_id, order_id, business_date, boundary_version, occurred_at, closed_at,
                    brand_id, location_id, legal_entity_id, channel_code, fulfilment_type, terminal_status,
                    cancellation_reason_code, gross_revenue_som, discount_som, delivery_fee_som,
                    tax_som, net_revenue_som, line_count, item_count, seconds_to_confirm,
                    seconds_to_ready, seconds_total, promised_at, seconds_late,
                    metric_calculation_version, source_order_version)
                VALUES (
                    :tenantId, :orderId, :businessDate, :boundaryVersion, :occurredAt, :closedAt,
                    :brandId, :locationId, :legalEntityId, :channelCode, :fulfilmentType, :terminalStatus,
                    :cancellationReasonCode, :gross, :discount, :deliveryFee,
                    :tax, :net, :lineCount, :itemCount, :secondsToConfirm,
                    :secondsToReady, :secondsTotal, :promisedAt, :secondsLate,
                    :metricCalculationVersion, :sourceOrderVersion)
                """).params(params).update();
    }
}
