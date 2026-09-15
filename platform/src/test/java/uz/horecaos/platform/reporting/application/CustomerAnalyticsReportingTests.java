package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
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
import uz.horecaos.platform.reporting.domain.Grain;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * T13 (7.6/7.6a/7.6b): customer analytics — {@code GET .../customer-kpis},
 * the {@code revenue.new_vs_returning.v1} branch {@link ReportQueryService#run}
 * takes for the customer-type grain, {@code GET .../customer-cohorts}, and
 * {@code GET .../customer-rfm}.
 *
 * <p>Rows are inserted directly into {@code reporting.fact_order} rather than
 * driven through {@code DayCloseService}, following {@code
 * OperatorReportingTests}' own reasoning: the fact table is derived and
 * rebuildable, and {@code DayCloseAndMetricLayerTests} already proves the
 * close job attributes {@code is_first_order}/{@code customer_subject_hash}
 * correctly.
 */
class CustomerAnalyticsReportingTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-3000-7000-8000-00000000e001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-3000-7000-8000-00000000e002");
    private static final UUID LOCATION_A = UUID.fromString("018f6f4e-3000-7000-8000-00000000e003");
    private static final UUID ENTITY_A = UUID.fromString("018f6f4e-3000-7000-8000-00000000e004");
    private static final UUID ENTITY_B = UUID.fromString("018f6f4e-3000-7000-8000-00000000e005");

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

        jdbc.sql("TRUNCATE TABLE reporting.fact_order, reporting.business_day_policies")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        store = new JdbcReportingStore(jdbc);
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T04:00:00Z"), ZoneOffset.UTC);
        queries = new ReportQueryService(store, new BusinessDayService(store), clock);

        seedTenant(TENANT);
    }

    // ----------------------------------------------------------- 7.6 customer-kpis

    @Test
    void kpisFoldNewDistinctRepeatFrequencyValueAndBasketDepthOverTheWholeRange() {
        // C1: two orders the same day — a first order, and a same-day repeat.
        insertOrder("c1-first", "C1", true, ENTITY_A, "COMPLETED", 50_000, 2);
        insertOrder("c1-repeat", "C1", false, ENTITY_A, "COMPLETED", 70_000, 3);
        // C2: a first order only.
        insertOrder("c2-first", "C2", true, ENTITY_A, "COMPLETED", 30_000, 1);
        // C3: a genuinely returning customer — their first order was outside this range.
        insertOrder("c3-return", "C3", false, ENTITY_A, "COMPLETED", 40_000, 4);
        // Excluded: a guest order (no customer account) and a cancelled order.
        insertGuestOrder("guest", ENTITY_A, "COMPLETED", 999_000, 9);
        insertOrder("c1-cancelled", "C1", false, ENTITY_A, "CANCELLED", 500_000, 5);

        var result = queries.customerKpis(TENANT, DAY, DAY, List.of(), List.of());

        assertThat(result.distinctCustomers()).isEqualTo(3);
        assertThat(result.newCustomers()).isEqualTo(2);
        // (3 - 2) / 3 = 33.33...% = 3333 basis points, largest-fraction rounding.
        assertThat(result.repeatShareBasisPoints()).isEqualTo(3333L);
        // 4 completed orders (2 + 1 + 1) over 3 distinct customers.
        assertThat(result.orderFrequency()).isEqualTo(4.0 / 3.0);
        // (50000 + 70000 + 30000 + 40000) / 3 = 63333.33.. -> 63333
        assertThat(result.customerValueSom()).isEqualTo(63_333L);
        // (2 + 3 + 1 + 4) items / 4 orders = 2.5
        assertThat(result.basketDepth()).isEqualTo(2.5);
    }

    @Test
    void kpisAreNullRatherThanZeroWhenNoCustomerOrderedAtAll() {
        var result = queries.customerKpis(TENANT, DAY, DAY, List.of(), List.of());

        assertThat(result.distinctCustomers()).isZero();
        assertThat(result.newCustomers()).isZero();
        assertThat(result.repeatShareBasisPoints())
                .as("no customers, not a zero repeat share")
                .isNull();
        assertThat(result.orderFrequency()).isNull();
        assertThat(result.customerValueSom()).isNull();
        assertThat(result.basketDepth()).isNull();
    }

    @Test
    void kpisRefuseACombinedTotalAcrossTwoLegalEntitiesUnlessNarrowed() {
        insertOrder("a-1", "C1", true, ENTITY_A, "COMPLETED", 50_000, 1);
        insertOrder("b-1", "C2", true, ENTITY_B, "COMPLETED", 60_000, 1);

        assertThatThrownBy(() -> queries.customerKpis(TENANT, DAY, DAY, List.of(), List.of()))
                .isInstanceOf(ReportingRefusals.CombinedEntityTotalException.class)
                .hasMessageContaining("customers.value.v1");

        // Narrowed to one entity: answered rather than refused.
        var result = queries.customerKpis(TENANT, DAY, DAY, List.of(), List.of(ENTITY_A));
        assertThat(result.distinctCustomers()).isEqualTo(1);
        assertThat(result.customerValueSom()).isEqualTo(50_000L);
    }

    @Test
    void kpisCatalogueNamesEveryPublishedMetricInProvenance() {
        insertOrder("c1", "C1", true, ENTITY_A, "COMPLETED", 50_000, 1);

        var result = queries.customerKpis(TENANT, DAY, DAY, List.of(), List.of());

        assertThat(result.provenance().metricVersions())
                .contains(
                        "customers.new.v1",
                        "customers.distinct.v1",
                        "customers.repeat_share.v1",
                        "customers.order_frequency.v1",
                        "customers.value.v1",
                        "customers.basket_depth.v1");
    }

    // ------------------------------------------------ 7.6a revenue.new_vs_returning.v1 via /queries

    @Test
    void revenueByCustomerTypeIsRequestableThroughQueries() {
        insertOrder("new-1", "C1", true, ENTITY_A, "COMPLETED", 50_000, 1);
        insertOrder("returning-1", "C2", false, ENTITY_A, "COMPLETED", 40_000, 1);
        // Excluded: no customer account at all.
        insertGuestOrder("guest", ENTITY_A, "COMPLETED", 999_000, 1);
        // Excluded: not COMPLETED.
        insertOrder("cancelled-1", "C3", true, ENTITY_A, "CANCELLED", 777_000, 1);

        var result = queries.run(new ReportQuery(
                TENANT,
                DAY,
                DAY,
                List.of("revenue.new_vs_returning.v1"),
                List.of(Grain.Dimension.CUSTOMER_TYPE, Grain.Dimension.LEGAL_ENTITY),
                List.of(),
                List.of(),
                List.of()));

        assertThat(result.rows()).hasSize(2);
        Map<String, Long> byCustomerType = new HashMap<>();
        result.rows()
                .forEach(row -> byCustomerType.put(
                        row.slice().customerType(), row.values().get("revenue.new_vs_returning.v1")));
        assertThat(byCustomerType).containsExactlyInAnyOrderEntriesOf(Map.of("NEW", 50_000L, "RETURNING", 40_000L));
    }

    @Test
    void mixingTheCustomerTypeGrainWithAnAggBranchDayMetricIsRefused() {
        assertThatThrownBy(() -> queries.run(new ReportQuery(
                        TENANT,
                        DAY,
                        DAY,
                        List.of("revenue.new_vs_returning.v1", "revenue.gross.v1"),
                        List.of(Grain.Dimension.CUSTOMER_TYPE, Grain.Dimension.LEGAL_ENTITY),
                        List.of(),
                        List.of(),
                        List.of())))
                .isInstanceOf(ReportingRefusals.MixedCustomerTypeGrainException.class);
    }

    @Test
    void revenueByCustomerTypeRefusesACombinedTotalAcrossTwoLegalEntities() {
        insertOrder("a-1", "C1", true, ENTITY_A, "COMPLETED", 50_000, 1);
        insertOrder("b-1", "C2", true, ENTITY_B, "COMPLETED", 60_000, 1);

        assertThatThrownBy(() -> queries.run(new ReportQuery(
                        TENANT,
                        DAY,
                        DAY,
                        List.of("revenue.new_vs_returning.v1"),
                        List.of(Grain.Dimension.CUSTOMER_TYPE),
                        List.of(),
                        List.of(),
                        List.of())))
                .isInstanceOf(ReportingRefusals.CombinedEntityTotalException.class);
    }

    // ----------------------------------------------------------------- 7.6a cohorts

    @Test
    void aCohortsRetentionCurveCountsMembersWhoOrderedAgainInASubsequentMonth() {
        LocalDate julyFirst = LocalDate.of(2026, 7, 5);
        LocalDate augustRepeat = LocalDate.of(2026, 8, 10);

        insertOrderOn(julyFirst, "cust-a-first", "CUST_A", true, ENTITY_A, "COMPLETED", 10_000, 1);
        insertOrderOn(augustRepeat, "cust-a-repeat", "CUST_A", false, ENTITY_A, "COMPLETED", 15_000, 1);
        insertOrderOn(julyFirst, "cust-b-first", "CUST_B", true, ENTITY_A, "COMPLETED", 20_000, 1);
        // CUST_B never reorders.

        var result = queries.customerCohorts(TENANT, LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 31), List.of());

        assertThat(result.cohorts()).hasSize(1);
        var cohort = result.cohorts().getFirst();
        assertThat(cohort.cohortMonth()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(cohort.size()).isEqualTo(2);

        var offset0 = cohort.points().stream()
                .filter(point -> point.monthOffset() == 0)
                .findFirst()
                .orElseThrow();
        assertThat(offset0.customerCount()).isEqualTo(2);
        assertThat(offset0.retainedBasisPoints()).isEqualTo(10_000L);

        var offset1 = cohort.points().stream()
                .filter(point -> point.monthOffset() == 1)
                .findFirst()
                .orElseThrow();
        assertThat(offset1.customerCount()).isEqualTo(1);
        // 1 of 2 members ordered again in month +1 = 50%.
        assertThat(offset1.retainedBasisPoints()).isEqualTo(5_000L);
    }

    @Test
    void aCohortRangeWiderThanTheRetentionWindowIsRefused() {
        // 379 days — inside ReportQuery.MAX_DAYS (400), so validateRange lets it
        // through, but 13 calendar months, one past COHORT_WINDOW_MONTHS (12).
        LocalDate from = LocalDate.of(2025, 8, 1);
        LocalDate to = LocalDate.of(2026, 8, 15);

        assertThatThrownBy(() -> queries.customerCohorts(TENANT, from, to, List.of()))
                .isInstanceOf(ReportingRefusals.CohortRangeTooWideException.class)
                .hasMessageContaining("months");
    }

    @Test
    void aCohortRangeAtExactlyTheRetentionWindowIsAnswered() {
        LocalDate from = LocalDate.of(2026, 1, 1);
        LocalDate to = LocalDate.of(2026, 12, 31);

        var result = queries.customerCohorts(TENANT, from, to, List.of());

        assertThat(result.windowMonths()).isEqualTo(ReportQueryService.COHORT_WINDOW_MONTHS);
        assertThat(result.cohorts()).isEmpty();
    }

    // --------------------------------------------------------------------- 7.6b RFM

    @Test
    void rfmBucketsMembersByRecencyAndFrequencyAndSumsRevenuePerCell() {
        LocalDate to = LocalDate.of(2026, 8, 31);
        // Recent (0 days before "to"), single order -> R1/F1.
        insertOrderOn(to, "recent-single", "R1F1", false, ENTITY_A, "COMPLETED", 25_000, 1);
        // Lapsing (10 days before "to"), 2 orders -> needs a second order to reach F2.
        LocalDate lapsing = to.minusDays(10);
        insertOrderOn(lapsing, "lapsing-a", "R2F2", false, ENTITY_A, "COMPLETED", 10_000, 1);
        insertOrderOn(lapsing, "lapsing-b", "R2F2", false, ENTITY_A, "COMPLETED", 12_000, 1);
        // At risk (40 days before "to"), 5 orders -> F3.
        LocalDate atRisk = to.minusDays(40);
        for (int i = 0; i < 5; i++) {
            insertOrderOn(atRisk, "atrisk-" + i, "R3F3", false, ENTITY_A, "COMPLETED", 1_000, 1);
        }

        var result = queries.customerRfm(TENANT, LocalDate.of(2026, 7, 1), to, List.of(), List.of());

        assertThat(result.totalCustomers()).isEqualTo(3);
        assertThat(result.cells())
                .filteredOn(cell -> cell.recency() == ReportQueryService.RecencyBand.R1_RECENT
                        && cell.frequency() == ReportQueryService.FrequencyBand.F1_SINGLE)
                .singleElement()
                .satisfies(cell -> {
                    assertThat(cell.memberCount()).isEqualTo(1);
                    assertThat(cell.revenueSom()).isEqualTo(25_000L);
                });
        assertThat(result.cells())
                .filteredOn(cell -> cell.recency() == ReportQueryService.RecencyBand.R2_LAPSING
                        && cell.frequency() == ReportQueryService.FrequencyBand.F2_FEW)
                .singleElement()
                .satisfies(cell -> {
                    assertThat(cell.memberCount()).isEqualTo(1);
                    assertThat(cell.revenueSom()).isEqualTo(22_000L);
                });
        assertThat(result.cells())
                .filteredOn(cell -> cell.recency() == ReportQueryService.RecencyBand.R3_AT_RISK
                        && cell.frequency() == ReportQueryService.FrequencyBand.F3_FREQUENT)
                .singleElement()
                .satisfies(cell -> {
                    assertThat(cell.memberCount()).isEqualTo(1);
                    assertThat(cell.revenueSom()).isEqualTo(5_000L);
                });
    }

    @Test
    void rfmGridAlwaysListsAllNineCellsEvenWhenEmpty() {
        var result = queries.customerRfm(TENANT, DAY, DAY, List.of(), List.of());

        assertThat(result.cells()).hasSize(9);
        assertThat(result.cells()).allSatisfy(cell -> {
            assertThat(cell.memberCount()).isZero();
            assertThat(cell.revenueSom()).isZero();
        });
    }

    // ----------------------------------------------------------------------- fixtures

    private static UUID orderId(String seed) {
        return UUID.nameUUIDFromBytes(("customer-analytics-fact-order:" + seed).getBytes(StandardCharsets.UTF_8));
    }

    private void insertOrder(
            String seed,
            String customerKey,
            boolean isFirstOrder,
            UUID legalEntityId,
            String status,
            long grossSom,
            int itemCount) {
        insertOrderOn(DAY, seed, customerKey, isFirstOrder, legalEntityId, status, grossSom, itemCount);
    }

    private void insertOrderOn(
            LocalDate businessDate,
            String seed,
            String customerKey,
            boolean isFirstOrder,
            UUID legalEntityId,
            String status,
            long grossSom,
            int itemCount) {
        insertRow(
                businessDate,
                seed,
                customerHash(customerKey),
                isFirstOrder,
                legalEntityId,
                status,
                grossSom,
                itemCount);
    }

    private void insertGuestOrder(String seed, UUID legalEntityId, String status, long grossSom, int itemCount) {
        insertRow(DAY, seed, null, false, legalEntityId, status, grossSom, itemCount);
    }

    /** A stand-in for the ADR 0029 keyed hash — this test never carries a real customer account. */
    private static String customerHash(String customerKey) {
        return "hash:" + customerKey;
    }

    private void insertRow(
            LocalDate businessDate,
            String seed,
            @Nullable String customerSubjectHash,
            boolean isFirstOrder,
            UUID legalEntityId,
            String status,
            long grossSom,
            int itemCount) {

        OffsetDateTime created = businessDate.atTime(9, 0).atOffset(ZoneOffset.of("+05:00"));

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", TENANT);
        params.put("orderId", orderId(seed));
        params.put("businessDate", businessDate);
        params.put("boundaryVersion", 1);
        params.put("occurredAt", created);
        params.put("brandId", BRAND);
        params.put("locationId", LOCATION_A);
        params.put("legalEntityId", legalEntityId);
        params.put("channelCode", "ADMIN");
        params.put("fulfilmentType", "PICKUP");
        params.put("terminalStatus", status);
        params.put("customerSubjectHash", customerSubjectHash);
        params.put("isFirstOrder", customerSubjectHash == null ? null : isFirstOrder);
        params.put("gross", grossSom);
        params.put("discount", 0L);
        params.put("deliveryFee", 0L);
        params.put("tax", 0L);
        params.put("net", grossSom);
        params.put("lineCount", 1);
        params.put("itemCount", itemCount);
        params.put("metricCalculationVersion", 1);
        params.put("sourceOrderVersion", 1);

        jdbc.sql("""
                INSERT INTO reporting.fact_order (
                    tenant_id, order_id, business_date, boundary_version, occurred_at,
                    brand_id, location_id, legal_entity_id, channel_code, fulfilment_type,
                    terminal_status, customer_subject_hash, is_first_order,
                    gross_revenue_som, discount_som, delivery_fee_som, tax_som, net_revenue_som,
                    line_count, item_count, metric_calculation_version, source_order_version)
                VALUES (
                    :tenantId, :orderId, :businessDate, :boundaryVersion, :occurredAt,
                    :brandId, :locationId, :legalEntityId, :channelCode, :fulfilmentType,
                    :terminalStatus, :customerSubjectHash, :isFirstOrder,
                    :gross, :discount, :deliveryFee, :tax, :net,
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
                .param("slug", "reporting-cust-" + tenantId)
                .update();
    }
}
