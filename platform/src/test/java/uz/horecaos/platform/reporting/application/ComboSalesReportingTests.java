package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Sales by combo container (ADR 0136, row 4.2a), the read over {@code reporting.fact_order_line}.
 *
 * <p>Facts are inserted straight into {@code fact_order} and {@code fact_order_line}, the two tables
 * {@code DayCloseService} writes together, so what is under test is the question the read asks of
 * them: a combo is several ordinary lines sharing a selection, the container is never a line, and
 * "how many Lunch boxes" is one quantity per purchase and not one per component. The producer (the
 * day close that copies the grouping off the order line, and skips the lines an amendment closed) is
 * proved against real ordering rows in {@code ComboOrderFlowEndToEndTests}.
 */
class ComboSalesReportingTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-2000-7000-8000-00000000c001");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-2000-7000-8000-00000000c0ff");
    private static final UUID BRAND = UUID.fromString("018f6f4e-2000-7000-8000-00000000c002");
    private static final UUID LOCATION_A = UUID.fromString("018f6f4e-2000-7000-8000-00000000c003");
    private static final UUID LOCATION_B = UUID.fromString("018f6f4e-2000-7000-8000-00000000c004");
    private static final UUID LUNCH = UUID.fromString("018f6f4e-2000-7000-8000-00000000c005");
    private static final UUID FAMILY = UUID.fromString("018f6f4e-2000-7000-8000-00000000c006");
    private static final UUID BURGER = UUID.fromString("018f6f4e-2000-7000-8000-00000000c007");
    private static final UUID COLA = UUID.fromString("018f6f4e-2000-7000-8000-00000000c008");

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

        jdbc.sql("TRUNCATE TABLE reporting.fact_order_line, reporting.fact_order, reporting.business_day_policies")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        store = new JdbcReportingStore(jdbc);
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T04:00:00Z"), ZoneOffset.UTC);
        queries = new ReportQueryService(store, new BusinessDayService(store), clock);

        seedTenant(TENANT);
        seedTenant(OTHER_TENANT);
    }

    @Test
    @DisplayName(
            "a combo is counted once per purchase, not once per component line, and its money is the sum of its lines")
    void aComboIsCountedOncePerPurchase() {
        // Two Lunch boxes bought as one purchase: each component line carries combo_quantity 2, and
        // the component quantities are 2 x what each pick asked for. A count over the lines would say
        // 2 (two components); a sum of the quantities would say 4; the answer is 2 combos, 1 purchase.
        UUID orderOne = insertOrder(TENANT, "O1", LOCATION_A, "DELIVERY", "COMPLETED");
        UUID twoBoxes = selection("two-boxes");
        insertLine(TENANT, orderOne, LOCATION_A, BURGER, 2, 50_000L, 50_000L, comboOf(LUNCH, twoBoxes, 2, "Lunch box"));
        insertLine(TENANT, orderOne, LOCATION_A, COLA, 2, 6_000L, 6_000L, comboOf(LUNCH, twoBoxes, 2, "Lunch box"));
        // One more box, on another order, with a discount spread over its lines.
        UUID orderTwo = insertOrder(TENANT, "O2", LOCATION_A, "PICKUP", "COMPLETED");
        UUID oneBox = selection("one-box");
        insertLine(TENANT, orderTwo, LOCATION_A, BURGER, 1, 25_000L, 22_500L, comboOf(LUNCH, oneBox, 1, "Lunch box"));
        insertLine(TENANT, orderTwo, LOCATION_A, COLA, 1, 3_000L, 2_700L, comboOf(LUNCH, oneBox, 1, "Lunch box"));

        List<JdbcReportingStore.ComboSalesRow> rows = store.readComboSales(TENANT, DAY, DAY, List.of(), List.of(), 100);

        assertThat(rows).singleElement().satisfies(lunch -> {
            assertThat(lunch.comboContainerVariantId()).isEqualTo(LUNCH);
            assertThat(lunch.comboName()).isEqualTo("Lunch box");
            assertThat(lunch.combosSold())
                    .as("2 + 1 combos, however many lines they were")
                    .isEqualTo(3L);
            assertThat(lunch.purchases()).as("two selections").isEqualTo(2L);
            assertThat(lunch.orders()).isEqualTo(2L);
            assertThat(lunch.totalGrossSom()).isEqualTo(50_000L + 6_000L + 25_000L + 3_000L);
            assertThat(lunch.totalNetSom()).isEqualTo(50_000L + 6_000L + 22_500L + 2_700L);
            assertThat(lunch.totalDiscountSom()).isEqualTo(2_800L);
            assertThat(lunch.deliveryCombos()).isEqualTo(2L);
            assertThat(lunch.pickupCombos()).isEqualTo(1L);
        });
    }

    @Test
    @DisplayName("combos are grouped by container and ordered by the revenue they earned")
    void combosAreOrderedByNetRevenue() {
        UUID order = insertOrder(TENANT, "O1", LOCATION_A, "DELIVERY", "COMPLETED");
        UUID lunchSelection = selection("lunch");
        insertLine(
                TENANT, order, LOCATION_A, BURGER, 1, 25_000L, 25_000L, comboOf(LUNCH, lunchSelection, 1, "Lunch box"));
        UUID familySelection = selection("family");
        insertLine(
                TENANT,
                order,
                LOCATION_A,
                BURGER,
                3,
                90_000L,
                90_000L,
                comboOf(FAMILY, familySelection, 1, "Family box"));

        List<JdbcReportingStore.ComboSalesRow> rows = store.readComboSales(TENANT, DAY, DAY, List.of(), List.of(), 100);

        assertThat(rows)
                .extracting(JdbcReportingStore.ComboSalesRow::comboContainerVariantId)
                .containsExactly(FAMILY, LUNCH);
    }

    @Test
    @DisplayName("a combo on an order that did not complete was not sold; a dish that is no combo's is no combo's sale")
    void onlyCompletedOrdersAndOnlyComboLinesCount() {
        UUID cancelled = insertOrder(TENANT, "CANCELLED", LOCATION_A, "DELIVERY", "CANCELLED");
        insertLine(
                TENANT,
                cancelled,
                LOCATION_A,
                BURGER,
                1,
                25_000L,
                25_000L,
                comboOf(LUNCH, selection("c"), 1, "Lunch box"));
        UUID completed = insertOrder(TENANT, "COMPLETED", LOCATION_A, "DELIVERY", "COMPLETED");
        insertLine(TENANT, completed, LOCATION_A, BURGER, 4, 100_000L, 100_000L, null);

        assertThat(store.readComboSales(TENANT, DAY, DAY, List.of(), List.of(), 100))
                .as("no combo was sold: the one on the cancelled order does not count and the burger is a plain dish")
                .isEmpty();
    }

    @Test
    @DisplayName("the location, the fulfilment type and the date range narrow what is counted")
    void theFiltersNarrowTheRead() {
        UUID atA = insertOrder(TENANT, "A", LOCATION_A, "DELIVERY", "COMPLETED");
        insertLine(
                TENANT, atA, LOCATION_A, BURGER, 1, 25_000L, 25_000L, comboOf(LUNCH, selection("a"), 1, "Lunch box"));
        UUID atB = insertOrder(TENANT, "B", LOCATION_B, "PICKUP", "COMPLETED");
        insertLine(
                TENANT, atB, LOCATION_B, BURGER, 5, 125_000L, 125_000L, comboOf(LUNCH, selection("b"), 5, "Lunch box"));

        assertThat(store.readComboSales(TENANT, DAY, DAY, List.of(LOCATION_A), List.of(), 100))
                .singleElement()
                .satisfies(row -> assertThat(row.combosSold()).isEqualTo(1L));
        assertThat(store.readComboSales(TENANT, DAY, DAY, List.of(), List.of("PICKUP"), 100))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.combosSold()).isEqualTo(5L);
                    assertThat(row.deliveryCombos())
                            .as("no delivery order is in the narrowed set")
                            .isNull();
                });
        assertThat(store.readComboSales(TENANT, DAY.plusDays(1), DAY.plusDays(2), List.of(), List.of(), 100))
                .isEmpty();
    }

    @Test
    @DisplayName("another tenant's combos are never read")
    void combosNeverCrossTenants() {
        UUID mine = insertOrder(TENANT, "MINE", LOCATION_A, "DELIVERY", "COMPLETED");
        insertLine(
                TENANT,
                mine,
                LOCATION_A,
                BURGER,
                1,
                25_000L,
                25_000L,
                comboOf(LUNCH, selection("mine"), 1, "Lunch box"));
        UUID theirs = insertOrder(OTHER_TENANT, "THEIRS", LOCATION_A, "DELIVERY", "COMPLETED");
        insertLine(
                OTHER_TENANT,
                theirs,
                LOCATION_A,
                BURGER,
                9,
                225_000L,
                225_000L,
                comboOf(LUNCH, selection("theirs"), 9, "Lunch box"));

        assertThat(store.readComboSales(TENANT, DAY, DAY, List.of(), List.of(), 100))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.combosSold()).isEqualTo(1L);
                    assertThat(row.totalNetSom()).isEqualTo(25_000L);
                });
    }

    @Test
    @DisplayName("the service says a full page may have more behind it, and carries the provenance of every other read")
    void theServiceReportsMaybeMoreAndProvenance() {
        UUID order = insertOrder(TENANT, "O", LOCATION_A, "DELIVERY", "COMPLETED");
        insertLine(
                TENANT, order, LOCATION_A, BURGER, 1, 25_000L, 25_000L, comboOf(LUNCH, selection("l"), 1, "Lunch box"));
        insertLine(
                TENANT,
                order,
                LOCATION_A,
                BURGER,
                1,
                30_000L,
                30_000L,
                comboOf(FAMILY, selection("f"), 1, "Family box"));

        var full = queries.comboSales(TENANT, DAY, DAY, List.of(), List.of(), 1);
        assertThat(full.rows()).hasSize(1);
        assertThat(full.maybeMore()).isTrue();

        var all = queries.comboSales(TENANT, DAY, DAY, List.of(), List.of(), 100);
        assertThat(all.rows()).hasSize(2);
        assertThat(all.maybeMore()).isFalse();
        assertThat(all.provenance().timezone()).isEqualTo("Asia/Tashkent");
    }

    @Test
    @DisplayName("the schema keeps the four combo columns of a fact line together")
    void theSchemaKeepsTheComboColumnsTogether() {
        UUID order = insertOrder(TENANT, "O", LOCATION_A, "DELIVERY", "COMPLETED");

        Throwable half = catchThrowable(() -> jdbc.sql("""
                        INSERT INTO reporting.fact_order_line (
                            tenant_id, business_date, order_id, line_id, location_id, variant_id,
                            product_name_snapshot, quantity, gross_som, discount_som, net_som, occurred_at,
                            combo_selection_id)
                        VALUES (:t, :d, :o, :l, :loc, :v, 'Burger', 1, 1, 0, 1, now(), :selection)
                        """)
                .param("t", TENANT)
                .param("d", DAY)
                .param("o", order)
                .param("l", UUID.randomUUID())
                .param("loc", LOCATION_A)
                .param("v", BURGER)
                .param("selection", UUID.randomUUID())
                .update());

        assertThat(half)
                .as("a line that names a selection but no container, quantity or name cannot be reported")
                .hasMessageContaining("ck_fact_order_line_combo");
    }

    // ------------------------------------------------------------------ fixtures

    private record Combo(UUID containerVariantId, UUID selectionId, int quantity, String name) {}

    private static Combo comboOf(UUID container, UUID selection, int quantity, String name) {
        return new Combo(container, selection, quantity, name);
    }

    private static UUID selection(String seed) {
        return UUID.nameUUIDFromBytes(("combo-selection:" + seed).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID orderId(UUID tenant, String seed) {
        return UUID.nameUUIDFromBytes(("combo-sales-order:" + tenant + ":" + seed).getBytes(StandardCharsets.UTF_8));
    }

    private UUID insertOrder(
            UUID tenantId, String seed, UUID locationId, String fulfilmentType, String terminalStatus) {
        UUID orderId = orderId(tenantId, seed);
        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("orderId", orderId);
        params.put("businessDate", DAY);
        params.put("occurredAt", DAY.atTime(9, 0).minusHours(5).atOffset(ZoneOffset.UTC));
        params.put("brandId", BRAND);
        params.put("locationId", locationId);
        params.put("fulfilmentType", fulfilmentType);
        params.put("terminalStatus", terminalStatus);

        jdbc.sql("""
                INSERT INTO reporting.fact_order (
                    tenant_id, order_id, business_date, boundary_version, occurred_at,
                    brand_id, location_id, channel_code, fulfilment_type, terminal_status,
                    gross_revenue_som, discount_som, delivery_fee_som, tax_som, net_revenue_som,
                    line_count, item_count, metric_calculation_version, source_order_version)
                VALUES (
                    :tenantId, :orderId, :businessDate, 1, :occurredAt,
                    :brandId, :locationId, 'TELEGRAM', :fulfilmentType, :terminalStatus,
                    0, 0, 0, 0, 0,
                    1, 1, 1, 1)
                """).params(params).update();
        return orderId;
    }

    private void insertLine(
            UUID tenantId,
            UUID orderId,
            UUID locationId,
            UUID variantId,
            int quantity,
            long grossSom,
            long netSom,
            @Nullable Combo combo) {
        jdbc.sql("""
                INSERT INTO reporting.fact_order_line (
                    tenant_id, business_date, order_id, line_id, location_id, variant_id,
                    product_name_snapshot, quantity, gross_som, discount_som, net_som, occurred_at,
                    combo_selection_id, combo_container_variant_id, combo_quantity, combo_name_snapshot)
                VALUES (
                    :tenantId, :businessDate, :orderId, :lineId, :locationId, :variantId,
                    :productName, :quantity, :gross, :discount, :net, :occurredAt,
                    :selectionId, :containerId, :comboQuantity, :comboName)
                """)
                .param("tenantId", tenantId)
                .param("businessDate", DAY)
                .param("orderId", orderId)
                .param("lineId", UUID.randomUUID())
                .param("locationId", locationId)
                .param("variantId", variantId)
                .param("productName", variantId.equals(BURGER) ? "Burger" : "Cola")
                .param("quantity", quantity)
                .param("gross", grossSom)
                .param("discount", grossSom - netSom)
                .param("net", netSom)
                .param("occurredAt", OffsetDateTime.of(DAY.atTime(4, 0), ZoneOffset.UTC))
                .param("selectionId", combo == null ? null : combo.selectionId())
                .param("containerId", combo == null ? null : combo.containerVariantId())
                .param("comboQuantity", combo == null ? null : combo.quantity())
                .param("comboName", combo == null ? null : combo.name())
                .update();
    }

    private void seedTenant(UUID tenantId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "combo-sales-" + tenantId)
                .update();
    }
}
