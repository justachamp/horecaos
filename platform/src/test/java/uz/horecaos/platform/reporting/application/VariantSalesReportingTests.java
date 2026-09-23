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
    private static final UUID VARIANT_BURGER = UUID.fromString("018f6f4e-2000-7000-8000-00000000d008");
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

        List<JdbcReportingStore.VariantSalesRow> rows =
                store.readVariantSales(TENANT, DAY, DAY, List.of(), List.of(), 100);

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
                store.readVariantSales(TENANT, DAY, DAY, List.of(LOCATION_A), List.of(), 100);

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

        List<JdbcReportingStore.VariantSalesRow> rows =
                store.readVariantSales(TENANT, DAY, DAY, List.of(), List.of(), 100);

        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).totalQuantity()).isEqualTo(1);
    }

    /**
     * Wave T14 (7.7): the filter bar's fulfilment control used to be accepted
     * and ignored. A DINE_IN order has no delivery or pickup column of its
     * own (statistics.md's disclosed gap), so proving the filter actually
     * narrows the query is the only way to see it take effect at all.
     */
    @Test
    void fulfilmentTypeFilterNarrowsTheLinesConsideredIncludingTheTotals() {
        UUID delivery = insertOrder(TENANT, "FUL-DELIVERY", LOCATION_A, "DELIVERY");
        insertLine(TENANT, delivery, LOCATION_A, VARIANT_PIZZA, 2, 80_000L, 80_000L);
        UUID dineIn = insertOrder(TENANT, "FUL-DINEIN", LOCATION_A, "DINE_IN");
        insertLine(TENANT, dineIn, LOCATION_A, VARIANT_PIZZA, 5, 200_000L, 200_000L);

        List<JdbcReportingStore.VariantSalesRow> unfiltered =
                store.readVariantSales(TENANT, DAY, DAY, List.of(), List.of(), 100);
        assertThat(unfiltered.get(0).totalQuantity())
                .as("DINE_IN sums into the total though it has no split column of its own")
                .isEqualTo(7);

        List<JdbcReportingStore.VariantSalesRow> deliveryOnly =
                store.readVariantSales(TENANT, DAY, DAY, List.of(), List.of("DELIVERY"), 100);
        assertThat(deliveryOnly).hasSize(1);
        assertThat(deliveryOnly.get(0).totalQuantity()).isEqualTo(2);

        List<JdbcReportingStore.VariantSalesRow> dineInOnly =
                store.readVariantSales(TENANT, DAY, DAY, List.of(), List.of("DINE_IN"), 100);
        assertThat(dineInOnly).hasSize(1);
        assertThat(dineInOnly.get(0).totalQuantity()).isEqualTo(5);
    }

    @Test
    void theServiceReportsMaybeMoreAndProvenanceLikeTheOtherOrderGrainReads() {
        UUID order = insertOrder(TENANT, "P1", LOCATION_A, "DELIVERY");
        insertLine(TENANT, order, LOCATION_A, VARIANT_PIZZA, 1, 40_000L, 40_000L);
        insertLine(TENANT, order, LOCATION_A, VARIANT_SALAD, 1, 20_000L, 20_000L);

        var full = queries.variantSales(TENANT, DAY, DAY, List.of(), List.of(), 1);
        assertThat(full.rows()).hasSize(1);
        assertThat(full.maybeMore()).isTrue();

        var all = queries.variantSales(TENANT, DAY, DAY, List.of(), List.of(), 100);
        assertThat(all.rows()).hasSize(2);
        assertThat(all.maybeMore()).isFalse();
        assertThat(all.provenance().timezone()).isEqualTo("Asia/Tashkent");
    }

    // ------------------------------------------------------- wave 10 w5-reports-exports (7.7): sort + cursor

    /**
     * Burger sells more units at a lower price than pizza (fewer, pricier
     * orders) -- proving QUANTITY_DESC is a real second axis, not revenue
     * order relabelled.
     */
    @Test
    void quantityDescSortsByTotalQuantityRatherThanRevenue() {
        UUID pizzaOrder = insertOrder(TENANT, "Q-PIZZA", LOCATION_A, "DELIVERY");
        insertLine(TENANT, pizzaOrder, LOCATION_A, VARIANT_PIZZA, 2, 200_000L, 200_000L);
        UUID burgerOrder = insertOrder(TENANT, "Q-BURGER", LOCATION_A, "DELIVERY");
        insertLine(TENANT, burgerOrder, LOCATION_A, VARIANT_BURGER, 10, 100_000L, 100_000L);

        List<JdbcReportingStore.VariantSalesRow> byRevenue = store.readVariantSales(
                TENANT, DAY, DAY, List.of(), List.of(), JdbcReportingStore.VariantSalesSort.REVENUE_DESC, 100, null);
        assertThat(byRevenue)
                .extracting(JdbcReportingStore.VariantSalesRow::variantId)
                .containsExactly(VARIANT_PIZZA, VARIANT_BURGER);

        List<JdbcReportingStore.VariantSalesRow> byQuantity = store.readVariantSales(
                TENANT, DAY, DAY, List.of(), List.of(), JdbcReportingStore.VariantSalesSort.QUANTITY_DESC, 100, null);
        assertThat(byQuantity)
                .as("burger outsells pizza in units though it earns less -- the two sorts must disagree")
                .extracting(JdbcReportingStore.VariantSalesRow::variantId)
                .containsExactly(VARIANT_BURGER, VARIANT_PIZZA);
    }

    @Test
    void nameAscSortsAlphabeticallyByProductName() {
        UUID pizzaOrder = insertOrder(TENANT, "N-PIZZA", LOCATION_A, "DELIVERY");
        insertLine(TENANT, pizzaOrder, LOCATION_A, VARIANT_PIZZA, 1, 40_000L, 40_000L);
        UUID burgerOrder = insertOrder(TENANT, "N-BURGER", LOCATION_A, "DELIVERY");
        insertLine(TENANT, burgerOrder, LOCATION_A, VARIANT_BURGER, 1, 20_000L, 20_000L);
        UUID saladOrder = insertOrder(TENANT, "N-SALAD", LOCATION_A, "DELIVERY");
        insertLine(TENANT, saladOrder, LOCATION_A, VARIANT_SALAD, 1, 10_000L, 10_000L);

        List<JdbcReportingStore.VariantSalesRow> rows = store.readVariantSales(
                TENANT, DAY, DAY, List.of(), List.of(), JdbcReportingStore.VariantSalesSort.NAME_ASC, 100, null);

        assertThat(rows)
                .extracting(JdbcReportingStore.VariantSalesRow::productName)
                // Cyrillic alphabetical: Бургер, Пицца, Салат.
                .containsExactly("Бургер Классик", "Пицца Маргарита", "Салат Цезарь");
    }

    @Test
    void cursorPagingUnderRevenueDescCoversEveryRowExactlyOnceAndMatchesTheUnpagedOrder() {
        UUID pizzaOrder = insertOrder(TENANT, "C-PIZZA", LOCATION_A, "DELIVERY");
        insertLine(TENANT, pizzaOrder, LOCATION_A, VARIANT_PIZZA, 1, 120_000L, 120_000L);
        UUID burgerOrder = insertOrder(TENANT, "C-BURGER", LOCATION_A, "DELIVERY");
        insertLine(TENANT, burgerOrder, LOCATION_A, VARIANT_BURGER, 1, 80_000L, 80_000L);
        UUID saladOrder = insertOrder(TENANT, "C-SALAD", LOCATION_A, "DELIVERY");
        insertLine(TENANT, saladOrder, LOCATION_A, VARIANT_SALAD, 1, 40_000L, 40_000L);

        List<JdbcReportingStore.VariantSalesRow> whole = store.readVariantSales(
                TENANT, DAY, DAY, List.of(), List.of(), JdbcReportingStore.VariantSalesSort.REVENUE_DESC, 100, null);
        assertThat(whole)
                .extracting(JdbcReportingStore.VariantSalesRow::variantId)
                .containsExactly(VARIANT_PIZZA, VARIANT_BURGER, VARIANT_SALAD);

        List<JdbcReportingStore.VariantSalesRow> firstPage = store.readVariantSales(
                TENANT, DAY, DAY, List.of(), List.of(), JdbcReportingStore.VariantSalesSort.REVENUE_DESC, 2, null);
        assertThat(firstPage)
                .extracting(JdbcReportingStore.VariantSalesRow::variantId)
                .containsExactly(VARIANT_PIZZA, VARIANT_BURGER);

        JdbcReportingStore.VariantSalesRow last = firstPage.get(firstPage.size() - 1);
        List<JdbcReportingStore.VariantSalesRow> secondPage = store.readVariantSales(
                TENANT,
                DAY,
                DAY,
                List.of(),
                List.of(),
                JdbcReportingStore.VariantSalesSort.REVENUE_DESC,
                2,
                new JdbcReportingStore.VariantSalesCursor(
                        last.totalQuantity(),
                        last.totalNetSom(),
                        null,
                        java.util.Objects.requireNonNull(last.variantId())));

        assertThat(secondPage)
                .as("the cursor page picks up exactly where the first page stopped, no overlap and no gap")
                .extracting(JdbcReportingStore.VariantSalesRow::variantId)
                .containsExactly(VARIANT_SALAD);
    }

    @Test
    void theServiceOverloadWithSortAndCursorRoutesThroughToTheStore() {
        UUID order = insertOrder(TENANT, "SVC-1", LOCATION_A, "DELIVERY");
        insertLine(TENANT, order, LOCATION_A, VARIANT_PIZZA, 1, 40_000L, 40_000L);
        insertLine(TENANT, order, LOCATION_A, VARIANT_BURGER, 5, 20_000L, 20_000L);

        var byQuantity = queries.variantSales(
                TENANT, DAY, DAY, List.of(), List.of(), JdbcReportingStore.VariantSalesSort.QUANTITY_DESC, 100, null);
        assertThat(byQuantity.rows())
                .extracting(JdbcReportingStore.VariantSalesRow::variantId)
                .containsExactly(VARIANT_BURGER, VARIANT_PIZZA);
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
                    product_name_snapshot, quantity, gross_som, discount_som, net_som, occurred_at)
                VALUES (
                    :tenantId, :businessDate, :orderId, :lineId, :locationId, :variantId, :categoryId,
                    :productName, :quantity, :gross, :discount, :net, :occurredAt)
                """)
                .param("tenantId", tenantId)
                .param("businessDate", DAY)
                .param("orderId", orderId)
                .param("lineId", lineId(orderId, variantId))
                .param("locationId", locationId)
                .param("variantId", variantId)
                .param("categoryId", CATEGORY)
                .param("productName", productNameFor(variantId))
                .param("quantity", quantity)
                .param("gross", grossSom)
                .param("discount", grossSom - netSom)
                .param("net", netSom)
                // Matches insertOrder's own occurred_at for the sibling
                // fact_order row (the sample here is a fixed order-of-day, not
                // order-specific), the (business_date, order_id) pairing the
                // wave W02 backfill migration joins on.
                .param("occurredAt", DAY.atTime(9, 0).minusHours(5).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static String productNameFor(UUID variantId) {
        if (variantId.equals(VARIANT_PIZZA)) {
            return "Пицца Маргарита";
        }
        if (variantId.equals(VARIANT_BURGER)) {
            return "Бургер Классик";
        }
        return "Салат Цезарь";
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
