package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

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
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * 7.5's operator leaderboard and 7.5a's operator product mix, straight off
 * {@code reporting.fact_order}/{@code fact_order_line} — see {@link
 * OrderGrainReportingTests}'s own doc for why rows are inserted directly
 * rather than driven through {@code DayCloseService}: the fact table is
 * derived and rebuildable, and {@code DayCloseAndMetricLayerTests} already
 * proves the close job attributes correctly.
 */
class OperatorReportingTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-3000-7000-8000-00000000d001");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-3000-7000-8000-00000000d0ff");
    private static final UUID BRAND = UUID.fromString("018f6f4e-3000-7000-8000-00000000d002");
    private static final UUID LOCATION_A = UUID.fromString("018f6f4e-3000-7000-8000-00000000d003");

    private static final String STAFF_1 = "018f6f4e-3000-7000-8000-0000000e5731";
    private static final String STAFF_2 = "018f6f4e-3000-7000-8000-0000000e5732";

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

        jdbc.sql("TRUNCATE TABLE reporting.fact_order, reporting.fact_order_line, " + "reporting.business_day_policies")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        store = new JdbcReportingStore(jdbc);
        Clock clock = Clock.fixed(Instant.parse("2026-08-22T04:00:00Z"), ZoneOffset.UTC);
        queries = new ReportQueryService(store, new BusinessDayService(store), clock);

        seedTenant(TENANT);
        seedTenant(OTHER_TENANT);
    }

    // ----------------------------------------------------------- 7.5 leaderboard

    @Test
    void aStaffOperatorsOrdersAcrossTwoChannelsAreSummedIntoOneRow() {
        insertOrder("A", STAFF_1, "ADMIN", "DELIVERY", 100_000, 60, 3);
        insertOrder("B", STAFF_1, "TELEGRAM", "PICKUP", 50_000, 90, 1);

        var result = queries.operatorLeaderboard(TENANT, DAY, DAY, List.of());

        assertThat(result.rows()).hasSize(1);
        var row = result.rows().getFirst();
        assertThat(row.operatorPrincipalId()).isEqualTo(STAFF_1);
        assertThat(row.principalKind()).isEqualTo("STAFF");
        assertThat(row.subject()).isEqualTo(STAFF_1);
        assertThat(row.orderCount()).isEqualTo(2);
        assertThat(row.grossRevenueSom()).isEqualTo(150_000L);
        assertThat(row.deliveryCount()).isEqualTo(1);
        assertThat(row.pickupCount()).isEqualTo(1);
        assertThat(row.avgHandlingSeconds()).isEqualTo(75); // (60+90)/2
        assertThat(row.avgItemsPerOrder()).isEqualTo(2.0); // (3+1)/2
        assertThat(row.byChannel())
                .extracting(ReportQueryService.ChannelCount::channelCode)
                .containsExactlyInAnyOrder("ADMIN", "TELEGRAM");
    }

    @Test
    void aMachinePseudoOperatorAppearsBesideStaffOnTheSameBoard() {
        insertOrder("A", STAFF_1, "ADMIN", "DELIVERY", 100_000, 60, 2);
        insertOrder("B", "channel:BOT", "BOT", "DELIVERY", 40_000, null, 1);
        insertOrder("C", "channel:WEBSITE", "WEBSITE", "PICKUP", 30_000, null, 1);

        var result = queries.operatorLeaderboard(TENANT, DAY, DAY, List.of());

        assertThat(result.rows()).hasSize(3);
        Map<String, String> kindByOperator = new HashMap<>();
        Map<String, String> subjectByOperator = new HashMap<>();
        result.rows().forEach(row -> {
            kindByOperator.put(row.operatorPrincipalId(), row.principalKind());
            subjectByOperator.put(row.operatorPrincipalId(), row.subject());
        });

        assertThat(kindByOperator)
                .containsExactlyInAnyOrderEntriesOf(
                        Map.of(STAFF_1, "STAFF", "channel:BOT", "MACHINE", "channel:WEBSITE", "MACHINE"));
        assertThat(subjectByOperator).contains(entry("channel:BOT", "BOT"), entry("channel:WEBSITE", "WEBSITE"));
    }

    @Test
    void averageCheckAndHandlingTimeAreNullRatherThanZeroWhenNothingContributed() {
        insertOrder("A", STAFF_1, "ADMIN", "DELIVERY", 100_000, null, 1);

        var result = queries.operatorLeaderboard(TENANT, DAY, DAY, List.of());

        var row = result.rows().getFirst();
        assertThat(row.avgHandlingSeconds())
                .as("no order recorded a handling time, so the average is unknown, not zero")
                .isNull();
        assertThat(row.averageCheckSom()).isEqualTo(100_000L);
    }

    @Test
    void nonCompletedOrdersAreExcludedFromTheLeaderboard() {
        insertOrderWithStatus("A", STAFF_1, "ADMIN", "DELIVERY", "CANCELLED", 100_000, null, 1);

        var result = queries.operatorLeaderboard(TENANT, DAY, DAY, List.of());

        assertThat(result.rows()).isEmpty();
    }

    @Test
    void anOrderWithNoOperatorAttributionAtAllIsExcludedRatherThanCounted() {
        // A row closed before T12 and never recut: operator_principal_id is
        // null, and the leaderboard is silent about it rather than inventing
        // an "unknown operator" bucket that would double as a catch-all.
        insertRow("A", null, "ADMIN", "DELIVERY", "COMPLETED", 100_000, null, 1);

        var result = queries.operatorLeaderboard(TENANT, DAY, DAY, List.of());

        assertThat(result.rows()).isEmpty();
    }

    @Test
    void leaderboardRowsAreSortedByNetRevenueDescending() {
        insertOrder("LOW", STAFF_1, "ADMIN", "DELIVERY", 10_000, null, 1);
        insertOrder("HIGH", STAFF_2, "ADMIN", "DELIVERY", 500_000, null, 1);

        var result = queries.operatorLeaderboard(TENANT, DAY, DAY, List.of());

        assertThat(result.rows())
                .extracting(ReportQueryService.OperatorLeaderboardRow::operatorPrincipalId)
                .containsExactly(STAFF_2, STAFF_1);
    }

    @Test
    void leaderboardNeverCrossesTenants() {
        insertOrder("MINE", STAFF_1, "ADMIN", "DELIVERY", 100_000, null, 1);
        insertOrderForTenant(OTHER_TENANT, "THEIRS", STAFF_1, "ADMIN", "DELIVERY", 999_000, null, 1);

        var result = queries.operatorLeaderboard(TENANT, DAY, DAY, List.of());

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst().grossRevenueSom()).isEqualTo(100_000L);
    }

    @Test
    void theLeaderboardStatesItsReceiptDepthProvenance() {
        insertOrder("A", STAFF_1, "ADMIN", "DELIVERY", 100_000, null, 1);

        var result = queries.operatorLeaderboard(TENANT, DAY, DAY, List.of());

        assertThat(result.provenance().metricVersions()).contains("receipt_depth.v1");
    }

    // ----------------------------------------------------------- 7.5a operator products

    @Test
    void operatorProductsAreScopedToTheRequestedOperatorOnly() {
        insertOrderWithLine("A", STAFF_1, "DELIVERY", "Plov", 2, 60_000);
        insertOrderWithLine("B", STAFF_2, "DELIVERY", "Lagman", 1, 30_000);

        var result = queries.operatorProducts(TENANT, STAFF_1, DAY, DAY, List.of(), 100);

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst().productName()).isEqualTo("Plov");
    }

    /**
     * T12 second-pass adversarial review: {@link #leaderboardNeverCrossesTenants}
     * pins the leaderboard's own {@code l.tenant_id = :tenantId} filter with a
     * test; {@code readOperatorProductSales} carries the identical filter
     * (verified by reading {@code JdbcReportingStore}) but nothing pinned it —
     * a row under a different tenant, same {@code operatorPrincipalId}
     * ({@code STAFF_1}), must not leak into this tenant's product mix.
     */
    @Test
    void operatorProductsNeverCrossTenants() {
        insertOrderWithLine("MINE", STAFF_1, "DELIVERY", "Plov", 2, 60_000);
        insertOrderWithLineForTenant(OTHER_TENANT, "THEIRS", STAFF_1, "DELIVERY", "Manti", 5, 999_000);

        var result = queries.operatorProducts(TENANT, STAFF_1, DAY, DAY, List.of(), 100);

        // Both rows share a null variant_id (this fixture sets none) and would
        // GROUP BY together into one row regardless of tenant scoping, so
        // asserting the row count and product name alone would pass even with
        // the tenant filter dropped entirely -- total_quantity/total_net_som
        // are what actually change if OTHER_TENANT's line leaked in and merged
        // (2 -> 7 units, 60_000 -> 1_059_000 som), so those are what this pins.
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst().totalQuantity()).isEqualTo(2);
        assertThat(result.rows().getFirst().totalNetSom()).isEqualTo(60_000L);
    }

    // ----------------------------------------------------------------- fixtures

    private static UUID orderId(String seed) {
        return UUID.nameUUIDFromBytes(("operator-fact-order:" + seed).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID lineId(String seed) {
        return UUID.nameUUIDFromBytes(("operator-fact-line:" + seed).getBytes(StandardCharsets.UTF_8));
    }

    private void insertOrder(
            String seed,
            @Nullable String operatorPrincipalId,
            String channelCode,
            String fulfilmentType,
            long grossSom,
            @Nullable Integer secondsToConfirm,
            int itemCount) {
        insertOrderWithStatus(
                seed,
                operatorPrincipalId,
                channelCode,
                fulfilmentType,
                "COMPLETED",
                grossSom,
                secondsToConfirm,
                itemCount);
    }

    private void insertOrderWithStatus(
            String seed,
            @Nullable String operatorPrincipalId,
            String channelCode,
            String fulfilmentType,
            String status,
            long grossSom,
            @Nullable Integer secondsToConfirm,
            int itemCount) {
        insertRow(
                seed, operatorPrincipalId, channelCode, fulfilmentType, status, grossSom, secondsToConfirm, itemCount);
    }

    private void insertOrderForTenant(
            UUID tenantId,
            String seed,
            @Nullable String operatorPrincipalId,
            String channelCode,
            String fulfilmentType,
            long grossSom,
            @Nullable Integer secondsToConfirm,
            int itemCount) {
        insertRow(
                tenantId,
                seed,
                operatorPrincipalId,
                channelCode,
                fulfilmentType,
                "COMPLETED",
                grossSom,
                secondsToConfirm,
                itemCount);
    }

    private void insertRow(
            String seed,
            @Nullable String operatorPrincipalId,
            String channelCode,
            String fulfilmentType,
            String status,
            long grossSom,
            @Nullable Integer secondsToConfirm,
            int itemCount) {
        insertRow(
                TENANT,
                seed,
                operatorPrincipalId,
                channelCode,
                fulfilmentType,
                status,
                grossSom,
                secondsToConfirm,
                itemCount);
    }

    private void insertRow(
            UUID tenantId,
            String seed,
            @Nullable String operatorPrincipalId,
            String channelCode,
            String fulfilmentType,
            String status,
            long grossSom,
            @Nullable Integer secondsToConfirm,
            int itemCount) {

        OffsetDateTime created = DAY.atTime(9, 0).atOffset(ZoneOffset.of("+05:00"));

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("orderId", orderId(seed));
        params.put("businessDate", DAY);
        params.put("boundaryVersion", 1);
        params.put("occurredAt", created);
        params.put("brandId", BRAND);
        params.put("locationId", LOCATION_A);
        params.put("channelCode", channelCode);
        params.put("fulfilmentType", fulfilmentType);
        params.put("terminalStatus", status);
        params.put("operatorPrincipalId", operatorPrincipalId);
        params.put("gross", grossSom);
        params.put("discount", 0L);
        params.put("deliveryFee", 0L);
        params.put("tax", 0L);
        params.put("net", grossSom);
        params.put("lineCount", 1);
        params.put("itemCount", itemCount);
        params.put("secondsToConfirm", secondsToConfirm);
        params.put("metricCalculationVersion", 1);
        params.put("sourceOrderVersion", 1);

        jdbc.sql("""
                INSERT INTO reporting.fact_order (
                    tenant_id, order_id, business_date, boundary_version, occurred_at,
                    brand_id, location_id, channel_code, fulfilment_type, terminal_status,
                    operator_principal_id, gross_revenue_som, discount_som, delivery_fee_som,
                    tax_som, net_revenue_som, line_count, item_count, seconds_to_confirm,
                    metric_calculation_version, source_order_version)
                VALUES (
                    :tenantId, :orderId, :businessDate, :boundaryVersion, :occurredAt,
                    :brandId, :locationId, :channelCode, :fulfilmentType, :terminalStatus,
                    :operatorPrincipalId, :gross, :discount, :deliveryFee,
                    :tax, :net, :lineCount, :itemCount, :secondsToConfirm,
                    :metricCalculationVersion, :sourceOrderVersion)
                """).params(params).update();
    }

    private void insertOrderWithLine(
            String seed,
            String operatorPrincipalId,
            String fulfilmentType,
            String productName,
            int quantity,
            long netSom) {
        insertOrderWithLineForTenant(TENANT, seed, operatorPrincipalId, fulfilmentType, productName, quantity, netSom);
    }

    private void insertOrderWithLineForTenant(
            UUID tenantId,
            String seed,
            String operatorPrincipalId,
            String fulfilmentType,
            String productName,
            int quantity,
            long netSom) {
        insertOrderForTenant(tenantId, seed, operatorPrincipalId, "ADMIN", fulfilmentType, netSom, null, quantity);

        Map<String, Object> params = new HashMap<>();
        params.put("tenantId", tenantId);
        params.put("businessDate", DAY);
        params.put("orderId", orderId(seed));
        params.put("lineId", lineId(seed));
        params.put("locationId", LOCATION_A);
        params.put("productName", productName);
        params.put("quantity", quantity);
        params.put("gross", netSom);
        params.put("discount", 0L);
        params.put("net", netSom);
        // Matches insertRow's own occurred_at for the sibling fact_order row,
        // the same (business_date, order_id) pairing the wave W02 backfill
        // migration joins on.
        params.put("occurredAt", DAY.atTime(9, 0).atOffset(ZoneOffset.of("+05:00")));

        jdbc.sql("""
                INSERT INTO reporting.fact_order_line (
                    tenant_id, business_date, order_id, line_id, location_id,
                    product_name_snapshot, quantity, gross_som, discount_som, net_som, occurred_at)
                VALUES (
                    :tenantId, :businessDate, :orderId, :lineId, :locationId,
                    :productName, :quantity, :gross, :discount, :net, :occurredAt)
                """).params(params).update();
    }

    private void seedTenant(UUID tenantId) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", tenantId)
                .param("slug", "reporting-operators-" + tenantId)
                .update();
    }
}
