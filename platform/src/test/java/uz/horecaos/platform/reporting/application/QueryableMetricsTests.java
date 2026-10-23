package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
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
import uz.horecaos.platform.reporting.domain.Grain;
import uz.horecaos.platform.reporting.domain.MetricDefinition;
import uz.horecaos.platform.reporting.domain.MetricRegistry;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The registry and {@code GET /queries} agree on what each metric is (ADR 0043).
 *
 * <p>A metric registered with a built source has to end one of two ways when it is asked of {@link
 * ReportQueryService#run}: a figure, or a refusal that names where the figure is answered. The third ending is a
 * crash. {@code Bucket#valueOf} fell through to an {@link IllegalStateException} for every metric that is read
 * from a different fact than {@code agg_branch_day}, which the HTTP layer answered 500 when the tenant had
 * aggregate rows and answered 200 with an empty list when it had none -- a quiet day, which is the one answer
 * the registry exists to forbid.
 */
class QueryableMetricsTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-3000-7000-8000-00000000e001");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-3000-7000-8000-00000000e002");
    private static final UUID ENTITY = UUID.fromString("018f6f4e-3000-7000-8000-00000000e003");

    private static final LocalDate DAY = LocalDate.of(2026, 10, 20);

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

        jdbc.sql("TRUNCATE TABLE reporting.agg_branch_day, reporting.business_day_policies")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        JdbcReportingStore store = new JdbcReportingStore(jdbc);
        Clock clock = Clock.fixed(Instant.parse("2026-10-22T04:00:00Z"), ZoneOffset.UTC);
        queries = new ReportQueryService(store, new BusinessDayService(store), clock);

        seedTenant();
        insertAggregate();
    }

    /**
     * Every built metric, one at a time, over a tenant that has aggregate rows: a figure or an {@link
     * IllegalArgumentException} refusal, never anything else.
     */
    @Test
    void everyBuiltMetricIsEitherAnsweredOrRefusedByName() {
        List<String> crashed = new ArrayList<>();
        for (MetricDefinition metric : MetricRegistry.all()) {
            if (!metric.sourceAvailable()) {
                continue;
            }
            try {
                queries.run(queryFor(metric.id().code()));
            } catch (IllegalArgumentException refusal) {
                // A refusal names its metric: the registry's own rule, "refused by name".
                if (!String.valueOf(refusal.getMessage()).contains(metric.id().code())) {
                    crashed.add(metric.id().code() + " refused without naming itself: " + refusal.getMessage());
                }
            } catch (RuntimeException failure) {
                crashed.add(metric.id().code() + " -> " + failure);
            }
        }
        assertThat(crashed)
                .as("built metrics that /queries neither answers nor refuses")
                .isEmpty();
    }

    /**
     * The promotion metrics are answered by {@code GET .../reporting/promotions/summary}: a distinct-customer
     * count and a mean cannot be composed from per-slice figures. {@code /queries} says so, by name, instead of
     * answering for some tenants and crashing for others.
     */
    @Test
    void thePromotionMetricsAreRefusedTowardTheirOwnEndpointWhateverTheTenantHolds() {
        for (String code : List.of(
                "promo.redemptions.v1",
                "promo.unique_customers.v1",
                "promo.discount_som.v1",
                "promo.markup_som.v1",
                "promo.revenue_with.v1",
                "promo.avg_check_with.v1",
                "promo.avg_check_without.v1")) {
            assertThatThrownBy(() -> queries.run(queryFor(code)))
                    .as(code)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(code)
                    .hasMessageContaining("reporting/promotions/summary");
        }
    }

    /** The refusal is for the metrics agg_branch_day cannot answer; the ones it holds still come back as figures. */
    @Test
    void theFiguresAggBranchDayHoldAreStillAnswered() {
        for (String code : List.of(
                "revenue.gross.v1",
                "revenue.net.v1",
                "average_check.v1",
                "orders.count.v1",
                "channel_mix.count.v1",
                "orders.cancelled.v1",
                "orders.late.v1",
                "orders.promised.v1",
                "delivery_fee.v1")) {
            var result = queries.run(queryFor(code));

            assertThat(result.rows()).as(code).hasSize(1);
            assertThat(result.rows().getFirst().values()).as(code).containsKey(code);
        }
    }

    /** The same answer when the tenant has no aggregate rows at all, not a 200 that reads as a quiet day. */
    @Test
    void aTenantWithNoAggregateRowsIsRefusedTheSameWay() {
        jdbc.sql("TRUNCATE TABLE reporting.agg_branch_day").update();

        assertThatThrownBy(() -> queries.run(queryFor("promo.discount_som.v1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reporting/promotions/summary");
    }

    private static ReportQuery queryFor(String metricCode) {
        return new ReportQuery(
                TENANT,
                DAY,
                DAY,
                List.of(metricCode),
                List.of(Grain.Dimension.LEGAL_ENTITY),
                List.of(),
                List.of(),
                List.of(ENTITY));
    }

    private void insertAggregate() {
        jdbc.sql("""
                INSERT INTO reporting.agg_branch_day (
                    tenant_id, business_date, location_id, legal_entity_id, channel_code,
                    fulfilment_type, boundary_version, metric_calculation_version, order_count,
                    cancelled_count, gross_som, discount_som, net_som, refunded_som,
                    promised_count, late_count, distinct_customers, new_customers,
                    delivery_fee_som)
                VALUES (:tenantId, :businessDate, :locationId, :legalEntityId, 'WEBSITE',
                    'DELIVERY', 1, 1, 3, 0, 150000, 0, 150000, 0, 0, 0, 0, 0, 0)
                """)
                .param("tenantId", TENANT)
                .param("businessDate", DAY)
                .param("locationId", LOCATION)
                .param("legalEntityId", ENTITY)
                .update();
    }

    private void seedTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", TENANT)
                .param("slug", "reporting-queryable-" + TENANT)
                .update();
    }
}
