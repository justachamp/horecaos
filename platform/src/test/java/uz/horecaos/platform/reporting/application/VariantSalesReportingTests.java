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
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The per-variant sales read behind Reports 7.7's «Продажи» tab (ADR 0043).
 *
 * <p>Rows are inserted straight into {@code reporting.fact_order} and {@code
 * reporting.fact_order_line} — both facts {@code DayAggregator} writes together
 * for exactly this join, so a store-level test proving the read is correct does
 * not need to re-prove the close job that would have produced the same rows.
 */
class VariantSalesReportingTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-2000-7000-8000-00000000d001");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-2000-7000-8000-00000000d0ff");
    private static final UUID BRAND = UUID.fromString("018f6f4e-2000-7000-8000-00000000d002");
    private static final UUID LOCATION_A = UUID.fromString("018f6f4e-2000-7000-8000-00000000d003");
    private static final UUID LOCATION_B = UUID.fromString("018f6f4e-2000-7000-8000-00000000d004");
    private static final UUID VARIANT_PIZZA = UUID.fromString("018f6f4e-2000-7000-8000-00000000d005");
    private static final UUID VARIANT_SALAD = UUID.fromString("018f6f4e-2000-7000-8000-00000000d006");
    private static final UUID CATEGORY = UUID.fromString("018f6f4e-2000-7000-8000-00000000d007");

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

        jdbc.sql("TRUNCATE TABLE reporting.fact_order_line, reporting.fact_order, " + "reporting.business_day_policies")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        store = new JdbcReportingStore(jdbc);
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T04:00:00Z"), ZoneOffset.UTC);
        queries = new ReportQueryService(store, new BusinessDayService(store), clock);

        seedTenant(TENANT);
        seedTenant(OTHER_TENANT);
    }

    @Test
    void salesAreSummedPerVariantAcrossOrdersAndSortedByNetRevenueDescending() {
        UUID orderOne = insertOrder(TENANT, "O1", LOCATION_A, "DELIVERY");
        insertLine(TENANT, orderOne, LOCATION_A, VARIANT_PIZZA, 2, 80_000L, 80_000L);
        UUID orderTwo = insertOrder(TENANT, "O2", LOCATION_A, "PICKUP");
        insertLine(TENANT, orderTwo, LOCATION_A, VARIANT_PIZZA, 1, 40_000L, 40_000L);
        insertLine(TENANT, orderTwo, LOCATION_A, VARIANT_SALAD, 3, 60_000L, 54_000L);

        List<JdbcReportingStore.VariantSalesRow> rows = store.readVariantSales(TENANT, DAY, DAY, List.of(), 100);

        assertThat(rows)
                .extracting(JdbcReportingStore.VariantSalesRow::variantId)
                .containsExactly(VARIANT_PIZZA, VARIANT_SALAD);

        JdbcReportingStore.VariantSalesRow pizza = rows.get(0);
        assertThat(pizza.totalQuantity()).isEqualTo(3);
        assertThat(pizza.totalNetSom()).isEqualTo(120_000L);
        assertThat(pizza.deliveryQuantity()).isEqualTo(2);
        assertThat(pizza.deliveryNetSom()).isEqualTo(80_000L);
        assertThat(pizza.pickupQuantity()).isEqualTo(1);
        assertThat(pizza.pickupNetSom()).isEqualTo(40_000L);

        JdbcReportingStore.VariantSalesRow salad = rows.get(1);
        assertThat(salad.totalQuantity()).isEqualTo(3);
        assertThat(salad.deliveryQuantity()).isNull();
        assertThat(salad.pickupQuantity()).isEqualTo(3);
    }

    @Test
    void locationFilterNarrowsTheLinesConsidered() {
        UUID orderA = insertOrder(TENANT, "LOC-A", LOCATION_A, "DELIVERY");
        insertLine(TENANT, orderA, LOCATION_A, VARIANT_PIZZA, 1, 40_000L, 40_000L);
        UUID orderB = insertOrder(TENANT, "LOC-B", LOCATION_B, "DELIVERY");
        insertLine(TENANT, orderB, LOCATION_B, VARIANT_SALAD, 1, 20_000L, 20_000L);

        List<JdbcReportingStore.VariantSalesRow> rows =
                store.readVariantSales(TENANT, DAY, DAY, List.of(LOCATION_A), 100);

        assertThat(rows)
                .extracting(JdbcReportingStore.VariantSalesRow::variantId)
                .containsExactly(VARIANT_PIZZA);
    }

    @Test
    void linesNeverCrossTenants() {
        UUID mine = insertOrder(TENANT, "MINE", LOCATION_A, "DELIVERY");
        insertLine(TENANT, mine, LOCATION_A, VARIANT_PIZZA, 1, 40_000L, 40_000L);
        UUID theirs = insertOrder(OTHER_TENANT, "THEIRS", LOCATION_A, "DELIVERY");
        insertLine(OTHER_TENANT, theirs, LOCATION_A, VARIANT_PIZZA, 5, 200_000L, 200_000L);

        List<JdbcReportingStore.VariantSalesRow> rows = store.readVariantSales(TENANT, DAY, DAY, List.of(), 100);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).totalQuantity()).isEqualTo(1);
    }

    @Test
    void theServiceReportsMaybeMoreAndProvenanceLikeTheOtherOrderGrainReads() {
        UUID order = insertOrder(TENANT, "P1", LOCATION_A, "DELIVERY");
        insertLine(TENANT, order, LOCATION_A, VARIANT_PIZZA, 1, 40_000L, 40_000L);
        insertLine(TENANT, order, LOCATION_A, VARIANT_SALAD, 1, 20_000L, 20_000L);

        var full = queries.variantSales(TENANT, DAY, DAY, List.of(), 1);
        assertThat(full.rows()).hasSize(1);
        assertThat(full.maybeMore()).isTrue();

        var all = queries.variantSales(TENANT, DAY, DAY, List.of(), 100);
        assertThat(all.rows()).hasSize(2);
        assertThat(all.maybeMore()).isFalse();
        assertThat(all.provenance().timezone()).isEqualTo("Asia/Tashkent");
    }

    // ----------------------------------------------------------------- fixtures

    private static UUID orderId(String seed) {
        return UUID.nameUUIDFromBytes(
                ("variant-sales-order:" + seed).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static UUID lineId(UUID orderId, UUID variantId) {
        return UUID.nameUUIDFromBytes((orderId + ":" + variantId).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private UUID insertOrder(UUID tenantId, String seed, UUID locationId, String fulfilmentType) {
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
        params.put("fulfilmentType", fulfilmentType);
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

    private void insertLine(
            UUID tenantId, UUID orderId, UUID locationId, UUID variantId, int quantity, long grossSom, long netSom) {
        jdbc.sql("""
                INSERT INTO reporting.fact_order_line (
                    tenant_id, business_date, order_id, line_id, location_id, variant_id, category_id,
                    product_name_snapshot, quantity, gross_som, discount_som, net_som)
                VALUES (
                    :tenantId, :businessDate, :orderId, :lineId, :locationId, :variantId, :categoryId,
                    :productName, :quantity, :gross, :discount, :net)
                """)
                .param("tenantId", tenantId)
                .param("businessDate", DAY)
                .param("orderId", orderId)
                .param("lineId", lineId(orderId, variantId))
                .param("locationId", locationId)
                .param("variantId", variantId)
                .param("categoryId", CATEGORY)
                .param("productName", variantId.equals(VARIANT_PIZZA) ? "Пицца Маргарита" : "Салат Цезарь")
                .param("quantity", quantity)
                .param("gross", grossSom)
                .param("discount", grossSom - netSom)
                .param("net", netSom)
                .update();
    }

    private void seedTenant(UUID tenantId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "variant-sales-" + tenantId)
                .update();
    }
}
