package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
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
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;
import uz.horecaos.platform.reporting.domain.HolidayMode;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Wave W02: {@code forecast_run}/{@code fact_forecast} (V0367) and {@link
 * ForecastService} — the seasonal-naive model's confidence interval on a
 * known series, the honesty gate below the minimum sample, the
 * operating-day-relative hour axis, forecast-vs-actual after {@link
 * ForecastService#backfillActuals}, and the 7.8a department/product
 * breakdown.
 *
 * <p>Every date below is a real, hand-verified Tuesday (ISO-8601: Tuesday =
 * 2), the same discipline {@code DemandHistoryReportingTests} uses and for
 * the same reason.
 */
class ForecastServiceTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-4000-7000-8000-00000000f001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-4000-7000-8000-00000000f002");
    private static final UUID LOCATION_A = UUID.fromString("018f6f4e-4000-7000-8000-00000000f003");

    private static final UUID CATEGORY = UUID.fromString("018f6f4e-4000-7000-8000-00000000f010");
    private static final UUID VARIANT = UUID.fromString("018f6f4e-4000-7000-8000-00000000f011");

    private static final int TUESDAY = 2;
    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    /** Four consecutive Tuesdays, oldest first — verified with the platform's own `date` binary before writing this file. */
    private static final LocalDate TUE1 = LocalDate.of(2026, 7, 28);

    private static final LocalDate TUE2 = LocalDate.of(2026, 8, 4);
    private static final LocalDate TUE3 = LocalDate.of(2026, 8, 11);
    private static final LocalDate TUE4 = LocalDate.of(2026, 8, 18);

    /** The Wednesday after TUE4 — "today", so history runs through TUE4 and the next Tuesday is forecast. */
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 19);

    /** {@code TODAY.plusDays(daysUntil(WEDNESDAY, TUESDAY))} — the run's own target date. */
    private static final LocalDate TARGET_TUESDAY = LocalDate.of(2026, 8, 25);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcReportingStore store;
    private ForecastService forecasts;

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
                        TRUNCATE TABLE reporting.fact_forecast, reporting.forecast_run,
                            reporting.fact_order_line, reporting.fact_order, reporting.business_day_policies
                        """).update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        store = new JdbcReportingStore(jdbc);
        Clock clock = Clock.fixed(TODAY.atStartOfDay(TASHKENT).toInstant(), ZoneOffset.UTC);
        forecasts = new ForecastService(store, new BusinessDayService(store), clock);

        seedTenant(TENANT);
    }

    // ------------------------------------------------------------ generation

    @Test
    void generateForecastWritesTheMeanAndConfidenceIntervalFromAKnownSeries() {
        // Hour 18, four Tuesdays: 8, 10, 12, 10 -> mean 10, sample stdev
        // sqrt(8/3) ~ 1.633, 80% margin (z=1.2816) ~ 2.093.
        insertOrders(TUE1, 18, 8);
        insertOrders(TUE2, 18, 10);
        insertOrders(TUE3, 18, 12);
        insertOrders(TUE4, 18, 10);

        var result = forecasts.generateForecast(TENANT, LOCATION_A, TUESDAY, 4, HolidayMode.INCLUDE);

        assertThat(result.targetDate()).isEqualTo(TARGET_TUESDAY);
        assertThat(result.hoursGenerated()).isEqualTo(24);

        var run = store.findForecastRun(TENANT, result.runId()).orElseThrow();
        assertThat(run.weekday()).isEqualTo(TUESDAY);
        assertThat(run.modelVersion()).isEqualTo(ForecastService.MODEL_VERSION);
        assertThat(run.confidenceLevel()).isEqualTo(ForecastService.CONFIDENCE_LEVEL);
        assertThat(run.holidayMode()).isEqualTo(HolidayMode.INCLUDE);

        var hour18 = hourRow(store.readForecastHours(TENANT, result.runId()), 18);
        assertThat(hour18.businessDate()).isEqualTo(TARGET_TUESDAY);
        assertThat(hour18.sampleSize()).isEqualTo(4);
        assertThat(hour18.forecastQuantity()).isEqualTo(10.0);
        assertThat(hour18.confidenceLow()).isCloseTo(7.907, within(0.01));
        assertThat(hour18.confidenceHigh()).isCloseTo(12.093, within(0.01));
        assertThat(hour18.actualQuantity()).isNull();
        assertThat(hour18.absolutePercentageError()).isNull();

        // A quiet hour still gets a row, forecasting zero, never omitted.
        var hour3 = hourRow(store.readForecastHours(TENANT, result.runId()), 3);
        assertThat(hour3.forecastQuantity()).isZero();
        assertThat(hour3.confidenceLow()).isZero();
        assertThat(hour3.confidenceHigh()).isZero();
    }

    @Test
    void belowMinimumSampleWritesTheRunButNoHourRowAtAll() {
        // Two qualifying Tuesdays — below DEMAND_HISTORY_MINIMUM_SAMPLE (3).
        insertOrders(TUE3, 18, 5);
        insertOrders(TUE4, 18, 7);

        var result = forecasts.generateForecast(TENANT, LOCATION_A, TUESDAY, 4, HolidayMode.INCLUDE);

        assertThat(result.hoursGenerated()).isZero();
        assertThat(result.breakdownRowsWritten()).isZero();
        assertThat(store.readForecastHours(TENANT, result.runId())).isEmpty();
        // The attempt itself is still auditable.
        assertThat(store.findForecastRun(TENANT, result.runId())).isPresent();
    }

    @Test
    void theHourAxisIsOperatingDayRelativeNotWallClock() {
        BusinessDayBoundary nineAm = new BusinessDayBoundary(TASHKENT, LocalTime.of(9, 0), 1);
        store.upsertBoundary(TENANT, nineAm, TUE1.minusYears(1), null);

        // One order at 08:45 wall clock on each of the four sample Tuesdays —
        // operating hour 23 (the hour before the next 09:00 start), never
        // wall-clock hour 8.
        for (LocalDate date : List.of(TUE1, TUE2, TUE3, TUE4)) {
            insertOrderAt(date, date.atTime(8, 45).atZone(TASHKENT).toInstant());
        }

        var result = forecasts.generateForecast(TENANT, LOCATION_A, TUESDAY, 4, HolidayMode.INCLUDE);

        var hours = store.readForecastHours(TENANT, result.runId());
        assertThat(hourRow(hours, 23).forecastQuantity()).isEqualTo(1.0);
        assertThat(hourRow(hours, 8).forecastQuantity()).isZero();
        assertThat(hourRow(hours, 9).forecastQuantity()).isZero();
    }

    // -------------------------------------------------------------- backfill

    @Test
    void backfillActualsFillsInTheActualAndErrorOnceTheForecastedDayHasClosed() {
        insertOrders(TUE1, 18, 8);
        insertOrders(TUE2, 18, 10);
        insertOrders(TUE3, 18, 12);
        insertOrders(TUE4, 18, 10);
        var result = forecasts.generateForecast(TENANT, LOCATION_A, TUESDAY, 4, HolidayMode.INCLUDE);

        // The target Tuesday itself trades: 11 orders at hour 18.
        insertOrders(TARGET_TUESDAY, 18, 11);

        int updated = forecasts.backfillActuals(TENANT, TARGET_TUESDAY);

        assertThat(updated).isEqualTo(24); // every hour of the branch-level run
        assertThat(store.findPendingForecastRows(TENANT, TARGET_TUESDAY)).isEmpty();

        var hour18 = hourRow(store.readForecastHours(TENANT, result.runId()), 18);
        assertThat(hour18.actualQuantity()).isEqualTo(11.0);
        assertThat(hour18.absolutePercentageError()).isCloseTo(1.0 / 11.0, within(0.0001));

        var comparisons = store.readForecastComparisons(TENANT, LOCATION_A, TUESDAY, 1);
        var comparisonHour18 = comparisons.stream()
                .filter(row -> row.operatingHour() == 18)
                .findFirst()
                .orElseThrow();
        assertThat(comparisonHour18.businessDate()).isEqualTo(TARGET_TUESDAY);
        assertThat(comparisonHour18.forecastQuantity()).isEqualTo(10.0);
        assertThat(comparisonHour18.actualQuantity()).isEqualTo(11.0);
    }

    @Test
    void backfillActualsIsANoOpWhenNothingIsPending() {
        assertThat(forecasts.backfillActuals(TENANT, TARGET_TUESDAY)).isZero();
    }

    // ------------------------------------------------------------- breakdown

    @Test
    void generateForecastWritesDepartmentAndProductBreakdownRowsFromTheSameSample() {
        // insertLine writes its own fact_order row alongside the line, so
        // each date already qualifies for the branch-level sample without a
        // separate insertOrders call.
        for (LocalDate date : List.of(TUE1, TUE2, TUE3, TUE4)) {
            insertLine(date, 18, 4);
        }

        var result = forecasts.generateForecast(TENANT, LOCATION_A, TUESDAY, 4, HolidayMode.INCLUDE);

        var byCategory = store.readForecastBreakdown(TENANT, result.runId(), false);
        var categoryHour18 = byCategory.stream()
                .filter(row -> row.operatingHour() == 18)
                .findFirst()
                .orElseThrow();
        assertThat(categoryHour18.categoryId()).isEqualTo(CATEGORY);
        assertThat(categoryHour18.variantId()).isNull();
        assertThat(categoryHour18.forecastQuantity()).isEqualTo(4.0);
        assertThat(categoryHour18.confidenceLow()).isEqualTo(4.0);
        assertThat(categoryHour18.confidenceHigh()).isEqualTo(4.0);

        var byProduct = store.readForecastBreakdown(TENANT, result.runId(), true);
        var variantHour18 = byProduct.stream()
                .filter(row -> row.operatingHour() == 18)
                .findFirst()
                .orElseThrow();
        assertThat(variantHour18.variantId()).isEqualTo(VARIANT);
        assertThat(variantHour18.productName()).isEqualTo("Пицца Маргарита");
        assertThat(variantHour18.forecastQuantity()).isEqualTo(4.0);
    }

    // ----------------------------------------------------------------- fixtures

    private static JdbcReportingStore.ForecastRow hourRow(List<JdbcReportingStore.ForecastRow> rows, int hour) {
        return rows.stream()
                .filter(row -> row.operatingHour() == hour)
                .findFirst()
                .orElseThrow();
    }

    private static int sequence = 0;

    private static UUID orderId(String seed) {
        return UUID.nameUUIDFromBytes(("forecast-order:" + seed).getBytes(StandardCharsets.UTF_8));
    }

    private void insertOrders(LocalDate businessDate, int localHour, int count) {
        for (int i = 0; i < count; i++) {
            insertOrderAt(
                    businessDate,
                    businessDate.atTime(localHour, 0).atZone(TASHKENT).toInstant());
        }
    }

    private void insertOrderAt(LocalDate businessDate, Instant occurredAt) {
        UUID orderId = orderId(TENANT + ":" + sequence++);
        OffsetDateTime occurredAtOffset = occurredAt.atOffset(ZoneOffset.UTC);

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", TENANT);
        params.put("orderId", orderId);
        params.put("businessDate", businessDate);
        params.put("boundaryVersion", 1);
        params.put("occurredAt", occurredAtOffset);
        params.put("brandId", BRAND);
        params.put("locationId", LOCATION_A);
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
    }

    /** One order-with-one-line at the given business date and hour, quantity {@code qty}, for the breakdown tests. */
    private void insertLine(LocalDate businessDate, int localHour, int qty) {
        Instant occurredAt = businessDate.atTime(localHour, 0).atZone(TASHKENT).toInstant();
        UUID orderId = orderId(TENANT + ":line:" + sequence++);
        UUID lineId = UUID.nameUUIDFromBytes(("forecast-line:" + orderId).getBytes(StandardCharsets.UTF_8));

        jdbc.sql("""
                INSERT INTO reporting.fact_order (
                    tenant_id, order_id, business_date, boundary_version, occurred_at,
                    brand_id, location_id, channel_code, fulfilment_type, terminal_status,
                    gross_revenue_som, discount_som, delivery_fee_som, tax_som, net_revenue_som,
                    line_count, item_count, metric_calculation_version, source_order_version)
                VALUES (
                    :tenantId, :orderId, :businessDate, 1, :occurredAt,
                    :brandId, :locationId, 'TELEGRAM', 'DELIVERY', 'COMPLETED',
                    0, 0, 0, 0, 0, 1, :qty, 1, 1)
                """)
                .param("tenantId", TENANT)
                .param("orderId", orderId)
                .param("businessDate", businessDate)
                .param("occurredAt", occurredAt.atOffset(ZoneOffset.UTC))
                .param("brandId", BRAND)
                .param("locationId", LOCATION_A)
                .param("qty", qty)
                .update();

        jdbc.sql("""
                INSERT INTO reporting.fact_order_line (
                    tenant_id, business_date, order_id, line_id, location_id, variant_id, category_id,
                    product_name_snapshot, quantity, gross_som, discount_som, net_som, occurred_at)
                VALUES (
                    :tenantId, :businessDate, :orderId, :lineId, :locationId, :variantId, :categoryId,
                    :productName, :quantity, 0, 0, 0, :occurredAt)
                """)
                .param("tenantId", TENANT)
                .param("businessDate", businessDate)
                .param("orderId", orderId)
                .param("lineId", lineId)
                .param("locationId", LOCATION_A)
                .param("variantId", VARIANT)
                .param("categoryId", CATEGORY)
                .param("productName", "Пицца Маргарита")
                .param("quantity", qty)
                .param("occurredAt", occurredAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void seedTenant(UUID tenantId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "forecast-" + tenantId)
                .update();
    }
}
