package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
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
import uz.horecaos.platform.reporting.domain.ClassificationRun;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcClassificationStore;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * T14 (7.7a/7.7b, ADR 0134): the persisted ABC/XYZ run, against the migrated
 * schema. {@code reporting.fact_order_line} rows are inserted directly, the
 * same footing {@code VariantSalesReportingTests} already establishes for
 * 7.7's own read — a store-level test proving the classification is correct
 * does not need to re-prove the close job that would have produced the same
 * rows.
 */
class ProductClassificationServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-3000-7000-8000-00000000e001");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-3000-7000-8000-00000000e0ff");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-3000-7000-8000-00000000e002");
    private static final UUID VARIANT_STEADY = UUID.fromString("018f6f4e-3000-7000-8000-00000000e003");
    private static final UUID VARIANT_ERRATIC = UUID.fromString("018f6f4e-3000-7000-8000-00000000e004");
    private static final UUID VARIANT_SMALL = UUID.fromString("018f6f4e-3000-7000-8000-00000000e005");
    private static final UUID CATEGORY = UUID.fromString("018f6f4e-3000-7000-8000-00000000e006");

    /** Exactly 28 days -- the floor -- so four 7-day buckets fit with none left over. */
    private static final LocalDate FROM = LocalDate.of(2026, 7, 1);

    private static final LocalDate TO = LocalDate.of(2026, 7, 28);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcReportingStore reportingStore;
    private JdbcClassificationStore classificationStore;
    private ProductClassificationService service;

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

        jdbc.sql("TRUNCATE TABLE reporting.classification_result, reporting.classification_run, "
                        + "reporting.fact_order_line, reporting.fact_order, reporting.business_day_policies")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        reportingStore = new JdbcReportingStore(jdbc);
        classificationStore = new JdbcClassificationStore(jdbc);
        Clock clock = Clock.fixed(Instant.parse("2026-07-29T04:00:00Z"), ZoneOffset.UTC);
        service = new ProductClassificationService(
                classificationStore, reportingStore, new BusinessDayService(reportingStore), clock);

        seedTenant(TENANT);
        seedTenant(OTHER_TENANT);
    }

    @Test
    void aRunPersistsItsWindowThresholdsAndMetric() {
        insertLine(TENANT, FROM, VARIANT_STEADY, 10, 100_000L);

        ClassificationRun run = service.run(TENANT, FROM, TO, List.of(), "staff-subject-1");

        assertThat(run.from()).isEqualTo(FROM);
        assertThat(run.to()).isEqualTo(TO);
        assertThat(run.metricCode()).isEqualTo("revenue.gross.v1");
        assertThat(run.thresholds().abcThresholdA()).isEqualTo(8_000);
        assertThat(run.thresholds().abcThresholdB()).isEqualTo(9_500);
        assertThat(run.bucketDays()).isEqualTo(7);
        assertThat(run.bucketCount()).isEqualTo(4);
        assertThat(run.requestedBy()).isEqualTo("staff-subject-1");
        assertThat(run.computedAt()).isEqualTo(Instant.parse("2026-07-29T04:00:00Z"));

        // And it is actually on the shelf, not just returned in memory: a
        // fresh read off the store, through its own findLatestRun -- not the
        // in-memory object this method already returned -- proves the row a
        // dispute would be checked against is really there.
        ClassificationRun reloaded =
                classificationStore.findLatestRun(TENANT, FROM, TO, List.of()).orElseThrow();
        assertThat(reloaded.id()).isEqualTo(run.id());
        assertThat(reloaded.from()).isEqualTo(FROM);
        assertThat(reloaded.to()).isEqualTo(TO);
        assertThat(reloaded.thresholds()).isEqualTo(run.thresholds());
        assertThat(reloaded.metricCode()).isEqualTo("revenue.gross.v1");
        assertThat(reloaded.rows()).hasSize(1);
    }

    /**
     * The trap named in the brief: the month preset is month-to-date, so on
     * (say) the 10th of the month the closest available pill resolves to a
     * ten-day window. This is exactly that shape of range, named for what it
     * is rather than as an arbitrary short span.
     */
    @Test
    void aMonthToDateRangeShorterThanTheFloorIsRefusedRatherThanSilentlyAccepted() {
        LocalDate monthToDateFrom = LocalDate.of(2026, 7, 1);
        LocalDate monthToDateTo = LocalDate.of(2026, 7, 10);
        insertLine(TENANT, monthToDateFrom, VARIANT_STEADY, 10, 100_000L);

        assertThatThrownBy(() -> service.run(TENANT, monthToDateFrom, monthToDateTo, List.of(), "staff-subject-1"))
                .isInstanceOf(ReportingRefusals.RangeTooShortException.class)
                .satisfies(exception -> {
                    var refusal = (ReportingRefusals.RangeTooShortException) exception;
                    assertThat(refusal.minimumDays()).isEqualTo(28);
                    assertThat(refusal.actualDays()).isEqualTo(10);
                });

        assertThat(jdbc.sql("SELECT count(*) FROM reporting.classification_run WHERE tenant_id = :t")
                        .param("t", TENANT)
                        .query(Integer.class)
                        .single())
                .as("a refused run writes nothing")
                .isZero();
    }

    @Test
    void exactlyTwentyEightDaysIsAccepted() {
        insertLine(TENANT, FROM, VARIANT_STEADY, 10, 100_000L);

        ClassificationRun run = service.run(TENANT, FROM, TO, List.of(), "staff-subject-1");

        assertThat(run.rows()).hasSize(1);
    }

    @Test
    void abcClassesFollowCumulativeRevenueShareDescending() {
        // 100,000 total, split 75/15/10 so cumulative share lands exactly on
        // each side of both thresholds with no rounding: STEADY 75,000
        // (cumulative 7500bp <= 8000 -> A), ERRATIC 15,000 (cumulative
        // 9000bp, between 8000 and 9500 -> B), SMALL 10,000 (cumulative
        // 10000bp > 9500 -> C). Spread evenly so the XYZ side does not also
        // make any of them erratic.
        insertLineEvenlyAcrossBuckets(TENANT, VARIANT_STEADY, 75_000L, 80);
        insertLineEvenlyAcrossBuckets(TENANT, VARIANT_ERRATIC, 15_000L, 16);
        insertLineEvenlyAcrossBuckets(TENANT, VARIANT_SMALL, 10_000L, 8);

        ClassificationRun run = service.run(TENANT, FROM, TO, List.of(), "staff-subject-1");

        assertThat(run.rows()).hasSize(3);
        ClassificationRun.Row steady = rowFor(run, VARIANT_STEADY);
        ClassificationRun.Row erratic = rowFor(run, VARIANT_ERRATIC);
        ClassificationRun.Row small = rowFor(run, VARIANT_SMALL);

        assertThat(steady.cumulativeShareBasisPoints()).isEqualTo(7_500);
        assertThat(steady.abcClass()).isEqualTo('A');
        assertThat(erratic.cumulativeShareBasisPoints()).isEqualTo(9_000);
        assertThat(erratic.abcClass()).isEqualTo('B');
        assertThat(small.cumulativeShareBasisPoints()).isEqualTo(10_000);
        assertThat(small.abcClass()).isEqualTo('C');
    }

    @Test
    void xyzClassifiesSteadyDemandAsXAndAOneWeekSpikeAsZ() {
        insertLineEvenlyAcrossBuckets(TENANT, VARIANT_STEADY, 40_000L, 40);
        // All 40 units in the first week alone -- the same total quantity as
        // the steady product, spread with maximum unevenness instead.
        insertLine(TENANT, FROM, VARIANT_ERRATIC, 40, 40_000L);

        ClassificationRun run = service.run(TENANT, FROM, TO, List.of(), "staff-subject-1");

        ClassificationRun.Row steady = rowFor(run, VARIANT_STEADY);
        ClassificationRun.Row erratic = rowFor(run, VARIANT_ERRATIC);

        assertThat(steady.xyzClass()).isEqualTo('X');
        assertThat(steady.coefficientOfVariationBasisPoints()).isZero();
        assertThat(erratic.xyzClass()).isEqualTo('Z');
        assertThat(erratic.coefficientOfVariationBasisPoints())
                .isGreaterThan(steady.coefficientOfVariationBasisPoints());
    }

    @Test
    void resultsNeverCrossTenants() {
        insertLine(TENANT, FROM, VARIANT_STEADY, 10, 100_000L);
        insertLine(OTHER_TENANT, FROM, VARIANT_STEADY, 999, 999_000_000L);

        ClassificationRun run = service.run(TENANT, FROM, TO, List.of(), "staff-subject-1");

        assertThat(run.rows()).hasSize(1);
        assertThat(run.rows().get(0).quantityTotal()).isEqualTo(10);
    }

    @Test
    void latestReturnsTheMostRecentlyComputedRunOverTheExactSameWindow() {
        insertLine(TENANT, FROM, VARIANT_STEADY, 10, 100_000L);

        assertThat(service.latest(TENANT, FROM, TO, List.of())).isEmpty();

        ClassificationRun first = service.run(TENANT, FROM, TO, List.of(), "staff-subject-1");
        ClassificationRun found = service.latest(TENANT, FROM, TO, List.of()).orElseThrow();
        assertThat(found.id()).isEqualTo(first.id());
        assertThat(found.requestedBy()).isEqualTo(first.requestedBy());
        assertThat(found.rows()).hasSize(1);
        assertThat(found.rows().get(0).variantId()).isEqualTo(VARIANT_STEADY);

        // A different window entirely does not answer for this one.
        assertThat(service.latest(TENANT, FROM.plusDays(1), TO.plusDays(1), List.of()))
                .isEmpty();
    }

    // ----------------------------------------------------------------- fixtures

    private static ClassificationRun.Row rowFor(ClassificationRun run, UUID variantId) {
        return run.rows().stream()
                .filter(row -> row.variantId().equals(variantId))
                .findFirst()
                .orElseThrow();
    }

    private static UUID orderId(String seed) {
        return UUID.nameUUIDFromBytes(
                ("classification-order:" + seed).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static UUID lineId(UUID orderId, LocalDate date, UUID variantId) {
        return UUID.nameUUIDFromBytes(
                (orderId + ":" + date + ":" + variantId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** One line on one day, straight into {@code fact_order_line} -- no {@code fact_order} row needed (no FK). */
    private void insertLine(UUID tenantId, LocalDate date, UUID variantId, int quantity, long grossSom) {
        UUID orderId = orderId(tenantId + ":" + date + ":" + variantId + ":" + quantity);
        jdbc.sql("""
                INSERT INTO reporting.fact_order_line (
                    tenant_id, business_date, order_id, line_id, location_id, variant_id, category_id,
                    product_name_snapshot, quantity, gross_som, discount_som, net_som, occurred_at)
                VALUES (
                    :tenantId, :businessDate, :orderId, :lineId, :locationId, :variantId, :categoryId,
                    :productName, :quantity, :gross, 0, :gross, :occurredAt)
                """)
                .param("tenantId", tenantId)
                .param("businessDate", date)
                .param("orderId", orderId)
                .param("lineId", lineId(orderId, date, variantId))
                .param("locationId", LOCATION)
                .param("variantId", variantId)
                .param("categoryId", CATEGORY)
                .param("productName", nameOf(variantId))
                .param("quantity", quantity)
                .param("gross", grossSom)
                // Wave W02's V0368 made this NOT NULL after this test was written; no
                // sibling fact_order row exists to match (see this method's own doc),
                // so a fixed time-of-day on the line's own business date is enough --
                // the same footing VariantSalesReportingTests#insertLine already uses.
                .param("occurredAt", date.atTime(9, 0).atOffset(ZoneOffset.UTC))
                .update();
    }

    /** Spreads a total evenly across the window's four 7-day buckets (one insert per bucket's first day). */
    private void insertLineEvenlyAcrossBuckets(UUID tenantId, UUID variantId, long totalGrossSom, int totalQuantity) {
        int perBucketQuantity = totalQuantity / 4;
        long perBucketGross = totalGrossSom / 4;
        for (int bucket = 0; bucket < 4; bucket++) {
            insertLine(tenantId, FROM.plusDays((long) bucket * 7), variantId, perBucketQuantity, perBucketGross);
        }
    }

    private static String nameOf(UUID variantId) {
        if (variantId.equals(VARIANT_STEADY)) {
            return "Плов (стабильный)";
        }
        if (variantId.equals(VARIANT_ERRATIC)) {
            return "Шашлык (нестабильный)";
        }
        return "Компот";
    }

    private void seedTenant(UUID tenantId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "classification-" + tenantId)
                .update();
    }
}
