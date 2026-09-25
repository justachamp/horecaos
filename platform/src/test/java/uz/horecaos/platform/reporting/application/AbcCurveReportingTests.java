package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * X.19 (w6-reporting-facts, batch 11): the ABC cumulative-revenue-share curve
 * behind the product analytics page's own {@code q-abc-curve} chart.
 *
 * <p>Rows inserted straight into {@code reporting.fact_order}/{@code
 * fact_order_line} — the same footing {@link VariantSalesReportingTests}
 * already stands on for the source this read shares, and for the same
 * reason stated there: both facts are written together by {@code
 * DayAggregator}, so a read-level test does not need to re-prove the close
 * job that would have produced the same rows.
 */
class AbcCurveReportingTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-3000-7000-8000-00000000e001");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-3000-7000-8000-00000000e0ff");
    private static final UUID BRAND = UUID.fromString("018f6f4e-3000-7000-8000-00000000e002");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-3000-7000-8000-00000000e003");
    private static final UUID VARIANT_A = UUID.fromString("018f6f4e-3000-7000-8000-00000000e004");
    private static final UUID VARIANT_B = UUID.fromString("018f6f4e-3000-7000-8000-00000000e005");
    private static final UUID VARIANT_C = UUID.fromString("018f6f4e-3000-7000-8000-00000000e006");
    private static final UUID CATEGORY = UUID.fromString("018f6f4e-3000-7000-8000-00000000e007");

    private static final LocalDate DAY = LocalDate.of(2026, 8, 21);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
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

        var store = new JdbcReportingStore(jdbc);
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T04:00:00Z"), ZoneOffset.UTC);
        queries = new ReportQueryService(store, new BusinessDayService(store), clock);

        seedTenant(TENANT);
        seedTenant(OTHER_TENANT);
    }

    /**
     * 8000/1500/500 out of a 10000 total lands exactly on both published
     * boundaries — {@link uz.horecaos.platform.reporting.domain.ClassificationThresholds#DEFAULT}'s
     * 80%/95% — so this is also the test that would fail first were either
     * boundary read as a strict {@code <} instead of the record's own {@code
     * <=} ("up to and including").
     */
    @Test
    @DisplayName("rows are ranked by revenue, and cumulative share crosses A into B into C at the published boundaries")
    void cumulativeShareAndAbcClassFollowThePublishedBoundaries() {
        UUID order = insertOrder(TENANT, "ABC-1", LOCATION);
        insertLine(TENANT, order, VARIANT_A, 1, 8_000L);
        insertLine(TENANT, order, VARIANT_B, 1, 1_500L);
        insertLine(TENANT, order, VARIANT_C, 1, 500L);

        var result = queries.abcCurve(TENANT, DAY, DAY, List.of(), 100);

        assertThat(result.rows())
                .extracting(ReportQueryService.AbcCurveRow::variantId)
                .containsExactly(VARIANT_A, VARIANT_B, VARIANT_C);

        ReportQueryService.AbcCurveRow a = result.rows().get(0);
        assertThat(a.shareBasisPoints()).isEqualTo(8_000);
        assertThat(a.cumulativeShareBasisPoints()).isEqualTo(8_000);
        assertThat(a.abcClass()).isEqualTo('A');

        ReportQueryService.AbcCurveRow b = result.rows().get(1);
        assertThat(b.shareBasisPoints()).isEqualTo(1_500);
        assertThat(b.cumulativeShareBasisPoints()).isEqualTo(9_500);
        assertThat(b.abcClass()).isEqualTo('B');

        ReportQueryService.AbcCurveRow c = result.rows().get(2);
        assertThat(c.shareBasisPoints()).isEqualTo(500);
        assertThat(c.cumulativeShareBasisPoints()).isEqualTo(10_000);
        assertThat(c.abcClass()).isEqualTo('C');

        assertThat(result.maybeMore()).isFalse();
        assertThat(result.thresholds().abcThresholdA()).isEqualTo(8_000);
        assertThat(result.thresholds().abcThresholdB()).isEqualTo(9_500);
    }

    @Test
    @DisplayName("a bounded read reports maybeMore, the same caveat variantSales discloses for its own share")
    void aFullPageReportsMaybeMore() {
        UUID order = insertOrder(TENANT, "ABC-2", LOCATION);
        insertLine(TENANT, order, VARIANT_A, 1, 6_000L);
        insertLine(TENANT, order, VARIANT_B, 1, 4_000L);

        var capped = queries.abcCurve(TENANT, DAY, DAY, List.of(), 1);
        assertThat(capped.rows()).hasSize(1);
        assertThat(capped.maybeMore()).isTrue();

        var whole = queries.abcCurve(TENANT, DAY, DAY, List.of(), 100);
        assertThat(whole.rows()).hasSize(2);
        assertThat(whole.maybeMore()).isFalse();
    }

    /**
     * The denominator behind {@code shareBasisPoints}/{@code
     * cumulativeShareBasisPoints} has to be the tenant's true total revenue
     * in range, not just the sum of whatever page {@code limit} let through
     * — the same total {@link ProductClassificationService#run} sums over
     * every ranked variant, unbounded, before dividing. A tenant with more
     * variants than {@code limit} would otherwise see every visible
     * product's share inflated by the excluded tail's real revenue, and the
     * published 80/95 boundaries crossed too early.
     */
    @Test
    @DisplayName("cumulative share is against the tenant's true total revenue, not just the capped page's own sum")
    void cumulativeShareDividesByTheTrueTenantTotalNotJustTheVisiblePage() {
        UUID order = insertOrder(TENANT, "ABC-3", LOCATION);
        insertLine(TENANT, order, VARIANT_A, 1, 6_000L);
        insertLine(TENANT, order, VARIANT_B, 1, 3_000L);
        insertLine(TENANT, order, VARIANT_C, 1, 1_000L);

        // limit=2 excludes VARIANT_C (1,000) from the page, but its revenue
        // is still real tenant revenue and belongs in the denominator: true
        // total is 10,000, not 9,000 (the visible page's own sum).
        var result = queries.abcCurve(TENANT, DAY, DAY, List.of(), 2);

        assertThat(result.rows()).hasSize(2);
        assertThat(result.maybeMore()).isTrue();

        ReportQueryService.AbcCurveRow a = result.rows().get(0);
        assertThat(a.shareBasisPoints()).isEqualTo(6_000); // 6,000 / 10,000
        assertThat(a.cumulativeShareBasisPoints()).isEqualTo(6_000);
        assertThat(a.abcClass()).isEqualTo('A');

        ReportQueryService.AbcCurveRow b = result.rows().get(1);
        assertThat(b.shareBasisPoints()).isEqualTo(3_000); // 3,000 / 10,000
        // A buggy denominator of 9,000 (the capped page's own sum) would
        // read this as 10,000bp -- 100% -- and misclassify it 'C'.
        assertThat(b.cumulativeShareBasisPoints()).isEqualTo(9_000);
        assertThat(b.abcClass()).isEqualTo('B');
    }

    @Test
    @DisplayName("the curve never crosses tenants")
    void theCurveNeverCrossesTenants() {
        UUID mine = insertOrder(TENANT, "ABC-MINE", LOCATION);
        insertLine(TENANT, mine, VARIANT_A, 1, 10_000L);
        UUID theirs = insertOrder(OTHER_TENANT, "ABC-THEIRS", LOCATION);
        insertLine(OTHER_TENANT, theirs, VARIANT_B, 1, 50_000L);

        var result = queries.abcCurve(TENANT, DAY, DAY, List.of(), 100);

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst().variantId()).isEqualTo(VARIANT_A);
        // A lone row is always the whole visible total, whatever the other
        // tenant's revenue looks like -- the figure that would leak first if
        // the tenant predicate were ever dropped from the underlying read.
        assertThat(result.rows().getFirst().cumulativeShareBasisPoints()).isEqualTo(10_000);
    }

    // ----------------------------------------------------------------- fixtures

    private static UUID orderId(String seed) {
        return UUID.nameUUIDFromBytes(("abc-curve-order:" + seed).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static UUID lineId(UUID orderId, UUID variantId) {
        return UUID.nameUUIDFromBytes((orderId + ":" + variantId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private UUID insertOrder(UUID tenantId, String seed, UUID locationId) {
        UUID orderId = orderId(seed);
        OffsetDateTime occurredAt = DAY.atTime(9, 0).minusHours(5).atOffset(ZoneOffset.UTC);

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("orderId", orderId);
        params.put("businessDate", DAY);
        params.put("boundaryVersion", 1);
        params.put("occurredAt", occurredAt);
        params.put("brandId", BRAND);
        params.put("locationId", locationId);
        params.put("channelCode", "TELEGRAM");
        params.put("fulfilmentType", "DELIVERY");
        params.put("terminalStatus", "COMPLETED");
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
        return orderId;
    }

    private void insertLine(UUID tenantId, UUID orderId, UUID variantId, int quantity, long netSom) {
        jdbc.sql("""
                INSERT INTO reporting.fact_order_line (
                    tenant_id, business_date, order_id, line_id, location_id, variant_id, category_id,
                    product_name_snapshot, quantity, gross_som, discount_som, net_som, occurred_at)
                VALUES (
                    :tenantId, :businessDate, :orderId, :lineId, :locationId, :variantId, :categoryId,
                    :productName, :quantity, :gross, 0, :net, :occurredAt)
                """)
                .param("tenantId", tenantId)
                .param("businessDate", DAY)
                .param("orderId", orderId)
                .param("lineId", lineId(orderId, variantId))
                .param("locationId", LOCATION)
                .param("variantId", variantId)
                .param("categoryId", CATEGORY)
                .param("productName", "Variant " + variantId)
                .param("quantity", quantity)
                .param("gross", netSom)
                .param("net", netSom)
                .param("occurredAt", DAY.atTime(9, 0).minusHours(5).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void seedTenant(UUID tenantId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "abc-curve-" + tenantId)
                .update();
    }
}
