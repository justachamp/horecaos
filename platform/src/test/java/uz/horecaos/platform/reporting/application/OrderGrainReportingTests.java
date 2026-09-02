package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The two order-grain reads behind 7.2's per-order tables — «Этапы», «Заказы»,
 * «Опоздания» — and the funnel/cancellation-panel breakdown behind 7.1's Band D
 * (ADR 0043; {@code frontend-information-architecture.md} §7.1–7.2).
 *
 * <p>Rows are inserted straight into {@code reporting.fact_order} rather than
 * driven through {@code DayCloseService} the way {@code
 * DayCloseAndMetricLayerTests} does: the fact table is derived and rebuildable
 * by design, these two reads only ever touch it, and a store-level test proving
 * the read is correct does not need to re-prove the close job that would have
 * produced the same rows — that is already {@code DayCloseAndMetricLayerTests}'s
 * job.
 */
class OrderGrainReportingTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-2000-7000-8000-00000000c001");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-2000-7000-8000-00000000c0ff");
    private static final UUID BRAND = UUID.fromString("018f6f4e-2000-7000-8000-00000000c002");
    private static final UUID LOCATION_A = UUID.fromString("018f6f4e-2000-7000-8000-00000000c003");
    private static final UUID LOCATION_B = UUID.fromString("018f6f4e-2000-7000-8000-00000000c004");

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

        jdbc.sql("""
                TRUNCATE TABLE reporting.fact_order, reporting.business_day_policies
                """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        store = new JdbcReportingStore(jdbc);
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T04:00:00Z"), ZoneOffset.UTC);
        queries = new ReportQueryService(store, new BusinessDayService(store), clock);

        seedTenant(TENANT);
        seedTenant(OTHER_TENANT);
    }

    // ----------------------------------------------------------- «Этапы»/DURATION_DESC

    @Test
    void durationSortOrdersByTotalElapsedAndExcludesStillOpenOrders() {
        insertOrder("A", LOCATION_A, "COMPLETED", 12 * 60, null);
        insertOrder("B", LOCATION_A, "COMPLETED", 40 * 60, null);
        insertOrder("C", LOCATION_A, "COMPLETED", null, null); // never closed: no seconds_total

        List<JdbcReportingStore.OrderRow> rows = store.readOrders(
                TENANT, DAY, DAY, List.of(), List.of(), JdbcReportingStore.OrderSort.DURATION_DESC, 100);

        assertThat(rows).extracting(JdbcReportingStore.OrderRow::orderId).containsExactly(orderId("B"), orderId("A"));
    }

    // ----------------------------------------------------------- «Опоздания»/LATENESS_DESC

    @Test
    void latenessSortIncludesOnlyOrdersLateByAPositiveMargin() {
        insertLateOrder("ON_TIME", LOCATION_A, -300); // early: not late
        insertLateOrder("EXACT", LOCATION_A, 0); // exactly on time: not late
        insertLateOrder("WORST", LOCATION_A, 4_260); // 71 minutes late
        insertLateOrder("MILD", LOCATION_A, 840); // 14 minutes late
        insertOrder("NO_PROMISE", LOCATION_A, "COMPLETED", 600, null); // no promise at all

        List<JdbcReportingStore.OrderRow> rows = store.readOrders(
                TENANT, DAY, DAY, List.of(), List.of(), JdbcReportingStore.OrderSort.LATENESS_DESC, 100);

        assertThat(rows)
                .extracting(JdbcReportingStore.OrderRow::orderId)
                .containsExactly(orderId("WORST"), orderId("MILD"));
        assertThat(rows.get(0).secondsLate()).isEqualTo(4_260);
    }

    // ----------------------------------------------------------- «Заказы»/DATE_DESC + filters

    @Test
    void dateSortIncludesEveryTerminalStatusNewestFirst() {
        insertOrder("EARLY", LOCATION_A, "COMPLETED", 600, tashkent(10, 0));
        insertOrder("LATE_CREATED", LOCATION_A, "CANCELLED", null, tashkent(18, 0));

        List<JdbcReportingStore.OrderRow> rows =
                store.readOrders(TENANT, DAY, DAY, List.of(), List.of(), JdbcReportingStore.OrderSort.DATE_DESC, 100);

        assertThat(rows)
                .extracting(JdbcReportingStore.OrderRow::orderId)
                .containsExactly(orderId("LATE_CREATED"), orderId("EARLY"));
    }

    @Test
    void locationAndChannelFiltersNarrowTheOrderList() {
        insertOrderAt("A1", LOCATION_A, "AGGREGATOR", "COMPLETED", 600, tashkent(10, 0));
        insertOrderAt("B1", LOCATION_B, "TELEGRAM", "COMPLETED", 600, tashkent(11, 0));

        List<JdbcReportingStore.OrderRow> byLocation = store.readOrders(
                TENANT, DAY, DAY, List.of(LOCATION_A), List.of(), JdbcReportingStore.OrderSort.DATE_DESC, 100);
        assertThat(byLocation).extracting(JdbcReportingStore.OrderRow::orderId).containsExactly(orderId("A1"));

        List<JdbcReportingStore.OrderRow> byChannel = store.readOrders(
                TENANT, DAY, DAY, List.of(), List.of("TELEGRAM"), JdbcReportingStore.OrderSort.DATE_DESC, 100);
        assertThat(byChannel).extracting(JdbcReportingStore.OrderRow::orderId).containsExactly(orderId("B1"));
    }

    @Test
    void orderRowsNeverCrossTenants() {
        insertOrder("MINE", LOCATION_A, "COMPLETED", 600, null);
        insertOrderForTenant(OTHER_TENANT, "THEIRS", LOCATION_A, "COMPLETED", 600, null);

        List<JdbcReportingStore.OrderRow> rows =
                store.readOrders(TENANT, DAY, DAY, List.of(), List.of(), JdbcReportingStore.OrderSort.DATE_DESC, 100);

        assertThat(rows).extracting(JdbcReportingStore.OrderRow::orderId).containsExactly(orderId("MINE"));
    }

    // ----------------------------------------------------------- the bounded read via the service

    @Test
    void aFullPageReportsMaybeMoreAndAShortOneDoesNot() {
        insertOrder("A", LOCATION_A, "COMPLETED", 600, null);
        insertOrder("B", LOCATION_A, "COMPLETED", 600, null);
        insertOrder("C", LOCATION_A, "COMPLETED", 600, null);

        var full = queries.orders(TENANT, DAY, DAY, List.of(), List.of(), JdbcReportingStore.OrderSort.DATE_DESC, 2);
        assertThat(full.rows()).hasSize(2);
        assertThat(full.maybeMore())
                .as("a full 2-row page out of 3 rows may have more")
                .isTrue();

        var short_ = queries.orders(TENANT, DAY, DAY, List.of(), List.of(), JdbcReportingStore.OrderSort.DATE_DESC, 5);
        assertThat(short_.rows()).hasSize(3);
        assertThat(short_.maybeMore())
                .as("fewer rows than the limit is the whole answer")
                .isFalse();
    }

    @Test
    void theOrderListStatesItsProvenanceEvenWithNoMetricInvolved() {
        insertOrder("A", LOCATION_A, "COMPLETED", 600, null);

        var result =
                queries.orders(TENANT, DAY, DAY, List.of(), List.of(), JdbcReportingStore.OrderSort.DATE_DESC, 100);

        assertThat(result.provenance().timezone()).isEqualTo("Asia/Tashkent");
        assertThat(result.provenance().businessDayStart()).isEqualTo("00:00");
        assertThat(result.provenance().asOf()).isEqualTo(Instant.parse("2026-08-22T04:00:00Z"));
    }

    @Test
    void aRangeThatEndsBeforeItStartsIsRefused() {
        assertThatThrownBy(() -> queries.orders(
                        TENANT,
                        DAY,
                        DAY.minusDays(1),
                        List.of(),
                        List.of(),
                        JdbcReportingStore.OrderSort.DATE_DESC,
                        10))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aRangeCrossingAnOutstandingBoundaryRecutIsRefused() {
        BusinessDayService businessDays = new BusinessDayService(store);
        businessDays.setBoundary(
                TENANT,
                new BusinessDayBoundary(java.time.ZoneId.of("Asia/Tashkent"), java.time.LocalTime.MIDNIGHT, 2),
                DAY,
                DAY.minusDays(1));

        assertThatThrownBy(() -> queries.orders(
                        TENANT,
                        DAY.minusDays(2),
                        DAY,
                        List.of(),
                        List.of(),
                        JdbcReportingStore.OrderSort.DATE_DESC,
                        10))
                .isInstanceOf(ReportingRefusals.MixedBoundaryRegimeException.class);
    }

    // ----------------------------------------------------------- order outcomes (funnel + cancellation panel)

    @Test
    void outcomesGroupByStatusAndReasonWithCompletedCarryingNoReason() {
        insertOrder("OK-1", LOCATION_A, "COMPLETED", 600, null);
        insertOrder("OK-2", LOCATION_A, "COMPLETED", 600, null);
        insertCancelled("NO_COURIER-1", "NO_COURIER_AVAILABLE");
        insertCancelled("NO_COURIER-2", "NO_COURIER_AVAILABLE");
        insertCancelled("OUT_OF_STOCK-1", "OUT_OF_STOCK");
        insertRejected("R-1", "KITCHEN_CLOSED");

        List<JdbcReportingStore.OutcomeRow> rows = store.readOrderOutcomes(TENANT, DAY, DAY, List.of(), List.of());

        Map<String, Integer> byKey = new HashMap<>();
        for (JdbcReportingStore.OutcomeRow row : rows) {
            byKey.put(row.terminalStatus() + "|" + row.cancellationReasonCode(), row.count());
        }

        assertThat(byKey.get("COMPLETED|null")).isEqualTo(2);
        assertThat(byKey.get("CANCELLED|NO_COURIER_AVAILABLE")).isEqualTo(2);
        assertThat(byKey.get("CANCELLED|OUT_OF_STOCK")).isEqualTo(1);
        assertThat(byKey.get("REJECTED|KITCHEN_CLOSED")).isEqualTo(1);

        // Highest count first — the panel's "most common reason" line reads row 0.
        assertThat(rows.get(0).count())
                .isGreaterThanOrEqualTo(rows.get(rows.size() - 1).count());
    }

    @Test
    void outcomesNeverCrossTenants() {
        insertOrder("MINE", LOCATION_A, "COMPLETED", 600, null);
        insertOrderForTenant(OTHER_TENANT, "THEIRS", LOCATION_A, "COMPLETED", 600, null);

        List<JdbcReportingStore.OutcomeRow> rows = store.readOrderOutcomes(TENANT, DAY, DAY, List.of(), List.of());

        int total = rows.stream().mapToInt(JdbcReportingStore.OutcomeRow::count).sum();
        assertThat(total).isEqualTo(1);
    }

    // ----------------------------------------------------------------- fixtures

    private static UUID orderId(String seed) {
        return UUID.nameUUIDFromBytes(("fact-order:" + seed).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static OffsetDateTime tashkent(int hour, int minute) {
        // Asia/Tashkent is UTC+5, no daylight saving (see BusinessDayBoundary's own doc).
        return DAY.atTime(hour, minute).minusHours(5).atOffset(ZoneOffset.UTC);
    }

    private void insertOrder(
            String seed,
            UUID locationId,
            String status,
            @Nullable Integer secondsTotal,
            @Nullable OffsetDateTime occurredAt) {
        OffsetDateTime created = occurredAt != null ? occurredAt : tashkent(9, 0);
        OffsetDateTime closedAt = secondsTotal == null ? null : created.plusSeconds(secondsTotal);
        insertRow(TENANT, seed, locationId, "TELEGRAM", status, created, closedAt, secondsTotal, null, null, null);
    }

    private void insertOrderAt(
            String seed,
            UUID locationId,
            String channelCode,
            String status,
            int secondsTotal,
            OffsetDateTime occurredAt) {
        insertRow(
                TENANT,
                seed,
                locationId,
                channelCode,
                status,
                occurredAt,
                occurredAt.plusSeconds(secondsTotal),
                secondsTotal,
                null,
                null,
                null);
    }

    private void insertOrderForTenant(
            UUID tenantId,
            String seed,
            UUID locationId,
            String status,
            @Nullable Integer secondsTotal,
            @Nullable OffsetDateTime occurredAt) {
        OffsetDateTime created = occurredAt != null ? occurredAt : tashkent(9, 0);
        OffsetDateTime closedAt = secondsTotal == null ? null : created.plusSeconds(secondsTotal);
        insertRow(tenantId, seed, locationId, "TELEGRAM", status, created, closedAt, secondsTotal, null, null, null);
    }

    /** A closed order carrying a promise, late (or early) by {@code secondsLate}. */
    private void insertLateOrder(String seed, UUID locationId, int secondsLate) {
        OffsetDateTime created = tashkent(9, 0);
        OffsetDateTime promisedAt = tashkent(12, 0);
        OffsetDateTime closedAt = promisedAt.plusSeconds(secondsLate);
        insertRow(
                TENANT,
                seed,
                locationId,
                "TELEGRAM",
                "COMPLETED",
                created,
                closedAt,
                900,
                promisedAt,
                secondsLate,
                null);
    }

    private void insertCancelled(String seed, String reasonCode) {
        OffsetDateTime created = tashkent(9, 0);
        insertRow(TENANT, seed, LOCATION_A, "TELEGRAM", "CANCELLED", created, null, null, null, null, reasonCode);
    }

    private void insertRejected(String seed, String reasonCode) {
        OffsetDateTime created = tashkent(9, 0);
        insertRow(TENANT, seed, LOCATION_A, "TELEGRAM", "REJECTED", created, null, null, null, null, reasonCode);
    }

    private void insertRow(
            UUID tenantId,
            String seed,
            UUID locationId,
            String channelCode,
            String status,
            OffsetDateTime occurredAt,
            @Nullable OffsetDateTime closedAt,
            @Nullable Integer secondsTotal,
            @Nullable OffsetDateTime promisedAt,
            @Nullable Integer secondsLate,
            @Nullable String cancellationReasonCode) {

        OffsetDateTime created = occurredAt;

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("orderId", orderId(seed));
        params.put("businessDate", DAY);
        params.put("boundaryVersion", 1);
        params.put("occurredAt", created);
        params.put("closedAt", closedAt);
        params.put("brandId", BRAND);
        params.put("locationId", locationId);
        params.put("channelCode", channelCode);
        params.put("fulfilmentType", "DELIVERY");
        params.put("terminalStatus", status);
        params.put("cancellationReasonCode", cancellationReasonCode);
        params.put("gross", 100_000L);
        params.put("discount", 0L);
        params.put("deliveryFee", 10_000L);
        params.put("tax", 0L);
        params.put("net", 100_000L);
        params.put("lineCount", 1);
        params.put("itemCount", 1);
        params.put("secondsToConfirm", secondsTotal == null ? null : 60);
        params.put("secondsToReady", secondsTotal == null ? null : Math.max(0, secondsTotal - 120));
        params.put("secondsTotal", secondsTotal);
        params.put("promisedAt", promisedAt);
        params.put("secondsLate", secondsLate);
        params.put("metricCalculationVersion", 1);
        params.put("sourceOrderVersion", 1);

        jdbc.sql("""
                INSERT INTO reporting.fact_order (
                    tenant_id, order_id, business_date, boundary_version, occurred_at, closed_at,
                    brand_id, location_id, channel_code, fulfilment_type, terminal_status,
                    cancellation_reason_code, gross_revenue_som, discount_som, delivery_fee_som,
                    tax_som, net_revenue_som, line_count, item_count, seconds_to_confirm,
                    seconds_to_ready, seconds_total, promised_at, seconds_late,
                    metric_calculation_version, source_order_version)
                VALUES (
                    :tenantId, :orderId, :businessDate, :boundaryVersion, :occurredAt, :closedAt,
                    :brandId, :locationId, :channelCode, :fulfilmentType, :terminalStatus,
                    :cancellationReasonCode, :gross, :discount, :deliveryFee,
                    :tax, :net, :lineCount, :itemCount, :secondsToConfirm,
                    :secondsToReady, :secondsTotal, :promisedAt, :secondsLate,
                    :metricCalculationVersion, :sourceOrderVersion)
                """).params(params).update();
    }

    private void seedTenant(UUID tenantId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "reporting-orders-" + tenantId)
                .update();
    }
}
