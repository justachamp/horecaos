package uz.horecaos.platform.reporting.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.PlatformRole;
import uz.horecaos.platform.iam.infrastructure.authorization.RoleRegistrySynchronizer;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Wave T12, second-pass adversarial review: no HTTP-level test existed for
 * {@code ReportingController} at all — {@code OperatorReportingTests} and its
 * siblings exercise {@code ReportQueryService}/{@code JdbcReportingStore}
 * directly, and {@code EndpointCapabilityDeclarationTests} only checks by
 * reflection that {@code @RequiresCapability} is present and well-shaped,
 * never that a real request lacking {@code REPORTING_READ} is actually
 * refused. Every route on this controller shares the identical {@code
 * REPORTING_READ}/{@code TENANT} declaration, so this suite covers the
 * controller's capability wiring as a whole through the two endpoints T12
 * added, following {@code CommercialOperationsControllerEndpointTests}' own
 * shape.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ReportingControllerCapabilityHttpTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-9000-7000-8000-0000000000a1");

    // LOCATION_MANAGER holds REPORTING_READ (PlatformRole.java); COURIER_DISPATCHER
    // holds plenty of other tenant/brand authority but never REPORTING_READ, so
    // it doubles as the "authenticated, but not this capability" negative case.
    private static final String MANAGER = "reporting-manager";
    private static final String DISPATCHER = "reporting-dispatcher";

    private static final String REPORTING = "/api/v1/tenants/" + TENANT + "/reporting";

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @BeforeAll
    static void requireDocker() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this endpoint test");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        db = TestDatabase.migrated();
        registry.add("spring.datasource.url", db::jdbcUrl);
        registry.add("spring.datasource.username", db::username);
        registry.add("spring.datasource.password", db::password);
        registry.add("horecaos.messaging.outbox.enabled", () -> "false");
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:59092");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private RoleRegistrySynchronizer roleRegistry;

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        // Row 7.7's own fixtures (insertVariantSalesLine) carry fixed variant
        // ids across tests -- never FK-cascaded from tenant.tenants (fact
        // tables are derived and rebuildable, ADR 0043), so a stale row from
        // an earlier test would collide on this one's primary key.
        jdbc.sql("TRUNCATE TABLE reporting.fact_order_line, reporting.fact_order")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        roleRegistry.synchronize();
        insertTenant();
        grant(MANAGER, PlatformRole.LOCATION_MANAGER);
        grant(DISPATCHER, PlatformRole.COURIER_DISPATCHER);
    }

    @Test
    void operatorLeaderboardRefusesWithoutReportingRead() throws Exception {
        MvcResult refused = mvc.perform(get(REPORTING + "/operator-leaderboard")
                        .with(tokenFor(DISPATCHER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_READ.code());
    }

    @Test
    void operatorLeaderboardSucceedsWithReportingRead() throws Exception {
        MvcResult ok = mvc.perform(get(REPORTING + "/operator-leaderboard")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("\"rows\":[]");
    }

    @Test
    void operatorProductsRefusesWithoutReportingRead() throws Exception {
        MvcResult refused = mvc.perform(get(REPORTING + "/operator-products")
                        .with(tokenFor(DISPATCHER))
                        .queryParam("operatorPrincipalId", "staff-1")
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_READ.code());
    }

    @Test
    void operatorProductsSucceedsWithReportingRead() throws Exception {
        MvcResult ok = mvc.perform(get(REPORTING + "/operator-products")
                        .with(tokenFor(MANAGER))
                        .queryParam("operatorPrincipalId", "staff-1")
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("\"rows\":[]");
    }

    /**
     * 2026-09-14 review: {@code /fulfilment-time} and {@code
     * /cancellation-reasons} (P27) were two more {@code REPORTING_READ}
     * endpoints on this controller with no HTTP-level test — this suite's
     * own stated purpose is exactly to cover the controller's capability
     * wiring as a whole, and these two were simply missed.
     */
    @Test
    void fulfilmentTimeRefusesWithoutReportingRead() throws Exception {
        MvcResult refused = mvc.perform(get(REPORTING + "/fulfilment-time")
                        .with(tokenFor(DISPATCHER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07")
                        .queryParam("fulfilmentType", "DELIVERY"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_READ.code());
    }

    @Test
    void fulfilmentTimeSucceedsWithReportingRead() throws Exception {
        MvcResult ok = mvc.perform(get(REPORTING + "/fulfilment-time")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07")
                        .queryParam("fulfilmentType", "DELIVERY"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("\"medianSeconds\":null");
    }

    @Test
    void cancellationReasonsRefusesWithoutReportingRead() throws Exception {
        MvcResult refused = mvc.perform(get(REPORTING + "/cancellation-reasons").with(tokenFor(DISPATCHER)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_READ.code());
    }

    @Test
    void cancellationReasonsSucceedsWithReportingRead() throws Exception {
        MvcResult ok = mvc.perform(get(REPORTING + "/cancellation-reasons").with(tokenFor(MANAGER)))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).isEqualTo("[]");
    }

    // ------------------------------------------------------ T13 (7.6/7.6a/7.6b)

    @Test
    void customerKpisRefusesWithoutReportingRead() throws Exception {
        MvcResult refused = mvc.perform(get(REPORTING + "/customer-kpis")
                        .with(tokenFor(DISPATCHER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_READ.code());
    }

    @Test
    void customerKpisSucceedsWithReportingRead() throws Exception {
        MvcResult ok = mvc.perform(get(REPORTING + "/customer-kpis")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString())
                .contains("\"newCustomers\":0")
                .contains("\"distinctCustomers\":0");
    }

    @Test
    void customerCohortsRefusesWithoutReportingRead() throws Exception {
        MvcResult refused = mvc.perform(get(REPORTING + "/customer-cohorts")
                        .with(tokenFor(DISPATCHER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_READ.code());
    }

    @Test
    void customerCohortsSucceedsWithReportingRead() throws Exception {
        MvcResult ok = mvc.perform(get(REPORTING + "/customer-cohorts")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("\"cohorts\":[]");
    }

    @Test
    void customerCohortsRefusesARangeWiderThanTheRetentionWindow() throws Exception {
        MvcResult refused = mvc.perform(get(REPORTING + "/customer-cohorts")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2025-08-01")
                        .queryParam("to", "2026-08-15"))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("a refusal, not a crash")
                .isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("COHORT_RANGE_TOO_WIDE");
    }

    @Test
    void customerRfmRefusesWithoutReportingRead() throws Exception {
        MvcResult refused = mvc.perform(get(REPORTING + "/customer-rfm")
                        .with(tokenFor(DISPATCHER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_READ.code());
    }

    @Test
    void customerRfmSucceedsWithReportingRead() throws Exception {
        MvcResult ok = mvc.perform(get(REPORTING + "/customer-rfm")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-07"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("\"totalCustomers\":0");
    }

    // ------------------------------------------------------------------------- W02 demand-forecast
    // 2026-09-14 review: the two demand-forecast endpoints new this wave were
    // never covered here despite this suite's own stated purpose being the
    // controller's capability wiring as a whole.

    @Test
    void demandForecastRefusesWithoutReportingRead() throws Exception {
        MvcResult refused = mvc.perform(get(REPORTING + "/demand-forecast")
                        .with(tokenFor(DISPATCHER))
                        .queryParam("locationId", UUID.randomUUID().toString())
                        .queryParam("weekday", "2"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_READ.code());
    }

    @Test
    void demandForecastSucceedsWithReportingRead() throws Exception {
        MvcResult ok = mvc.perform(get(REPORTING + "/demand-forecast")
                        .with(tokenFor(MANAGER))
                        .queryParam("locationId", UUID.randomUUID().toString())
                        .queryParam("weekday", "2"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("\"hours\":[]");
    }

    @Test
    void demandForecastBreakdownRefusesWithoutReportingRead() throws Exception {
        MvcResult refused = mvc.perform(get(REPORTING + "/demand-forecast/breakdown")
                        .with(tokenFor(DISPATCHER))
                        .queryParam("locationId", UUID.randomUUID().toString())
                        .queryParam("weekday", "2")
                        .queryParam("dimension", "CATEGORY"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_READ.code());
    }

    @Test
    void demandForecastBreakdownSucceedsWithReportingRead() throws Exception {
        MvcResult ok = mvc.perform(get(REPORTING + "/demand-forecast/breakdown")
                        .with(tokenFor(MANAGER))
                        .queryParam("locationId", UUID.randomUUID().toString())
                        .queryParam("weekday", "2")
                        .queryParam("dimension", "CATEGORY"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("\"rows\":[]");
    }

    @Test
    void metricsDictionaryPublishesTheCustomerAnalyticsFormulas() throws Exception {
        MvcResult ok =
                mvc.perform(get(REPORTING + "/metrics").with(tokenFor(MANAGER))).andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString())
                .contains("customers.new.v1")
                .contains("customers.distinct.v1")
                .contains("customers.repeat_share.v1")
                .contains("customers.order_frequency.v1")
                .contains("customers.value.v1")
                .contains("customers.basket_depth.v1")
                .contains("customers.ltv.v1")
                .contains("revenue.new_vs_returning.v1");
    }

    /**
     * Wave P27: the trap the brief names by name — a money metric queried
     * without {@code groupBy=LEGAL_ENTITY} on a two-entity tenant must come
     * back as a handled {@code ProblemDetail} (ADR 0031/0038), never a 500 or
     * an unhandled exception that renders as an errored page.
     */
    @Test
    void aMoneyMetricWithoutLegalEntityGroupingIsAHandledErrorOnATwoEntityTenant() throws Exception {
        UUID entityA = UUID.randomUUID();
        UUID entityB = UUID.randomUUID();
        insertFactOrder(entityA);
        insertFactOrder(entityB);

        MvcResult refused = mvc.perform(get(REPORTING + "/queries")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-01")
                        .queryParam("metric", "revenue.gross.v1"))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("a refusal, not a crash — ADR 0031's ProblemDetail, not a 500")
                .isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString())
                .contains("LEGAL_ENTITY_GROUPING_REQUIRED")
                .contains("revenue.gross.v1");
    }

    @Test
    void thePerEntityCutOfTheSameMoneyMetricSucceeds() throws Exception {
        UUID entityA = UUID.randomUUID();
        UUID entityB = UUID.randomUUID();
        insertFactOrder(entityA);
        insertFactOrder(entityB);

        MvcResult ok = mvc.perform(get(REPORTING + "/queries")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-01")
                        .queryParam("metric", "revenue.gross.v1")
                        .queryParam("groupBy", "LEGAL_ENTITY"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(ok.getResponse().getContentAsString()).contains("\"rows\":[");
    }

    // ------------------------------------------------------------ wave 10 w5-reports-exports (7.7)

    @Test
    void variantSalesRefusesWithoutReportingRead() throws Exception {
        MvcResult refused = mvc.perform(get(REPORTING + "/variant-sales")
                        .with(tokenFor(DISPATCHER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-01"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_READ.code());
    }

    @Test
    void variantSalesDefaultsToRevenueDescendingOrder() throws Exception {
        insertVariantSalesLine(PIZZA, "Пицца Маргарита", 1, 40_000L);
        insertVariantSalesLine(SALAD, "Салат Цезарь", 5, 10_000L);

        MvcResult ok = mvc.perform(get(REPORTING + "/variant-sales")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-01"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String body = ok.getResponse().getContentAsString();
        assertThat(body.indexOf(PIZZA.toString()))
                .as("pizza earns more (40000 vs 10000) and must lead the default order")
                .isLessThan(body.indexOf(SALAD.toString()));
    }

    /** Row 7.7: the sort control the page previously had none of. */
    @Test
    void variantSalesSortsByQuantityWhenRequested() throws Exception {
        insertVariantSalesLine(PIZZA, "Пицца Маргарита", 1, 40_000L);
        insertVariantSalesLine(SALAD, "Салат Цезарь", 5, 10_000L);

        MvcResult ok = mvc.perform(get(REPORTING + "/variant-sales")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-01")
                        .queryParam("sort", "QUANTITY_DESC"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String body = ok.getResponse().getContentAsString();
        assertThat(body.indexOf(SALAD.toString()))
                .as("salad sells more units (5 vs 1) and must lead under QUANTITY_DESC, the opposite of revenue order")
                .isLessThan(body.indexOf(PIZZA.toString()));
    }

    /**
     * A cursor field that does not match the active {@code sort} used to be silently accepted:
     * {@code afterQuantity} stayed {@code null} while {@code sort=QUANTITY_DESC}, the generated
     * {@code HAVING (sum(l.quantity), variant_id) < (NULL, :afterVariantId)} tuple compared
     * against SQL NULL for every row, and the store returned an empty page that looked like "no
     * more results" instead of a rejected malformed request.
     */
    @Test
    void variantSalesRefusesACursorFieldThatDoesNotMatchTheActiveSort() throws Exception {
        insertVariantSalesLine(PIZZA, "Пицца Маргарита", 5, 40_000L);
        insertVariantSalesLine(SALAD, "Салат Цезарь", 3, 10_000L);

        MvcResult refused = mvc.perform(get(REPORTING + "/variant-sales")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-01")
                        .queryParam("sort", "QUANTITY_DESC")
                        // afterRevenueSom/afterVariantId without the afterQuantity QUANTITY_DESC needs.
                        .queryParam("afterRevenueSom", "40000")
                        .queryParam("afterVariantId", PIZZA.toString()))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("a rejected malformed request, not a silent empty page")
                .isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
    }

    @Test
    void variantSalesRefusesAnUnknownSort() throws Exception {
        MvcResult refused = mvc.perform(get(REPORTING + "/variant-sales")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-01")
                        .queryParam("sort", "PRICE_DESC"))
                .andReturn();

        assertThat(refused.getResponse().getStatus())
                .as("a refusal, not a crash")
                .isEqualTo(400);
        assertThat(refused.getResponse().getContentAsString()).contains("VALIDATION_FAILED");
    }

    /** Row 7.7: cursor paging past the page's previous 200-row cap. */
    @Test
    void variantSalesCursorPagesPastTheFirstPageUnderQuantityDesc() throws Exception {
        insertVariantSalesLine(PIZZA, "Пицца Маргарита", 5, 40_000L);
        insertVariantSalesLine(SALAD, "Салат Цезарь", 3, 10_000L);

        MvcResult firstPage = mvc.perform(get(REPORTING + "/variant-sales")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-01")
                        .queryParam("sort", "QUANTITY_DESC")
                        .queryParam("limit", "1"))
                .andReturn();
        assertThat(firstPage.getResponse().getStatus()).isEqualTo(200);
        String firstBody = firstPage.getResponse().getContentAsString();
        assertThat(firstBody).contains(PIZZA.toString()).doesNotContain(SALAD.toString());
        assertThat(firstBody).contains("\"maybeMore\":true");

        MvcResult secondPage = mvc.perform(get(REPORTING + "/variant-sales")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-01")
                        .queryParam("sort", "QUANTITY_DESC")
                        .queryParam("limit", "1")
                        .queryParam("afterQuantity", "5")
                        .queryParam("afterVariantId", PIZZA.toString()))
                .andReturn();
        assertThat(secondPage.getResponse().getStatus()).isEqualTo(200);
        String secondBody = secondPage.getResponse().getContentAsString();
        assertThat(secondBody)
                .as("the cursor page picks up exactly where the first page stopped, no overlap and no gap")
                .contains(SALAD.toString())
                .doesNotContain(PIZZA.toString());
        // A full page (1 row for a limit of 1) cannot prove there is no next
        // row -- the same "maybeMore" convention readOrders/variantSales
        // already document -- so this is not a false positive, just the
        // known shape of a full-width final page.
        assertThat(secondBody).contains("\"maybeMore\":true");
    }

    // ------------------------------------------------------------ wave 10 w5-reports-exports (7.10b)

    @Test
    void distanceBucketsRefusesWithoutReportingRead() throws Exception {
        MvcResult refused = mvc.perform(get(REPORTING + "/distance-buckets")
                        .with(tokenFor(DISPATCHER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-01"))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_READ.code());
    }

    @Test
    void distanceBucketsZeroFillsEveryBucketWithNoDeliveriesInRange() throws Exception {
        MvcResult ok = mvc.perform(get(REPORTING + "/distance-buckets")
                        .with(tokenFor(MANAGER))
                        .queryParam("from", "2026-09-01")
                        .queryParam("to", "2026-09-01"))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String body = ok.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"bucketCode\":\"UNDER_1KM\",\"deliveryCount\":0")
                .contains("\"bucketCode\":\"OVER_8KM\",\"deliveryCount\":0");
    }

    @Test
    void distanceBucketSetRefusesWithoutReportingRead() throws Exception {
        MvcResult refused = mvc.perform(get(REPORTING + "/distance-bucket-set").with(tokenFor(DISPATCHER)))
                .andReturn();

        assertThat(refused.getResponse().getStatus()).isEqualTo(403);
        assertThat(refused.getResponse().getContentAsString())
                .contains("INSUFFICIENT_CAPABILITY")
                .contains(Capability.REPORTING_READ.code());
    }

    @Test
    void distanceBucketSetPublishesTheFixedBoundariesAndVersion() throws Exception {
        MvcResult ok = mvc.perform(get(REPORTING + "/distance-bucket-set").with(tokenFor(MANAGER)))
                .andReturn();

        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        String body = ok.getResponse().getContentAsString();
        assertThat(body)
                .contains("\"version\":1")
                .contains("\"code\":\"UNDER_1KM\",\"fromMeters\":0,\"toMetersExclusive\":1000")
                .contains("\"code\":\"OVER_8KM\",\"fromMeters\":8000,\"toMetersExclusive\":null");
    }

    // ------------------------------------------------------------------ fixtures

    private static final UUID PIZZA = UUID.fromString("018f9b20-9100-7000-8000-0000000000b1");
    private static final UUID SALAD = UUID.fromString("018f9b20-9100-7000-8000-0000000000b2");

    private static final java.time.LocalDate FACT_DAY = java.time.LocalDate.of(2026, 9, 1);

    /** {@code GET .../reporting/variant-sales}'s own source — {@code reporting.fact_order}/{@code fact_order_line}, joined on (tenant, business_date, order_id). */
    private void insertVariantSalesLine(UUID variantId, String productName, int quantity, long netSom) {
        UUID orderId = UUID.nameUUIDFromBytes(("variant-sales-http:" + variantId).getBytes(UTF_8));
        UUID locationId = UUID.randomUUID();
        var occurredAt = FACT_DAY.atTime(9, 0).atOffset(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO reporting.fact_order (
                    tenant_id, order_id, business_date, boundary_version, occurred_at,
                    brand_id, location_id, channel_code, fulfilment_type, terminal_status,
                    gross_revenue_som, discount_som, delivery_fee_som, tax_som, net_revenue_som,
                    line_count, item_count, metric_calculation_version, source_order_version)
                VALUES (:tenantId, :orderId, :businessDate, 1, :occurredAt,
                    :brandId, :locationId, 'TELEGRAM', 'DELIVERY', 'COMPLETED',
                    0, 0, 0, 0, 0, 1, 1, 1, 1)
                """)
                .param("tenantId", TENANT)
                .param("orderId", orderId)
                .param("businessDate", FACT_DAY)
                .param("occurredAt", occurredAt)
                .param("brandId", UUID.randomUUID())
                .param("locationId", locationId)
                .update();
        jdbc.sql("""
                INSERT INTO reporting.fact_order_line (
                    tenant_id, business_date, order_id, line_id, location_id, variant_id, category_id,
                    product_name_snapshot, quantity, gross_som, discount_som, net_som, occurred_at)
                VALUES (:tenantId, :businessDate, :orderId, :lineId, :locationId, :variantId, :categoryId,
                    :productName, :quantity, :gross, 0, :net, :occurredAt)
                """)
                .param("tenantId", TENANT)
                .param("businessDate", FACT_DAY)
                .param("orderId", orderId)
                .param("lineId", UUID.nameUUIDFromBytes(("line:" + variantId).getBytes(UTF_8)))
                .param("locationId", locationId)
                .param("variantId", variantId)
                .param("categoryId", UUID.randomUUID())
                .param("productName", productName)
                .param("quantity", quantity)
                .param("gross", netSom)
                .param("net", netSom)
                .param("occurredAt", occurredAt)
                .update();
    }

    /**
     * {@code GET .../reporting/queries} reads {@code reporting.agg_branch_day}
     * (the typed pipeline's own pre-aggregated table), not {@code fact_order}
     * directly — see {@code JdbcReportingStore#readAggregates}.
     */
    private void insertFactOrder(UUID legalEntityId) {
        jdbc.sql("""
                INSERT INTO reporting.agg_branch_day (
                    tenant_id, business_date, location_id, legal_entity_id, channel_code,
                    fulfilment_type, boundary_version, metric_calculation_version, order_count,
                    cancelled_count, gross_som, discount_som, net_som, refunded_som,
                    promised_count, late_count, distinct_customers, new_customers)
                VALUES (:tenantId, :businessDate, :locationId, :legalEntityId, 'TELEGRAM', 'DELIVERY',
                    1, 1, 1, 0, 100000, 0, 100000, 0, 0, 0, 0, 0)
                """)
                .param("tenantId", TENANT)
                .param("businessDate", FACT_DAY)
                .param("locationId", UUID.randomUUID())
                .param("legalEntityId", legalEntityId)
                .update();
    }

    private void insertTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'reporting-capability-endpoint', 'Reporting', 'Reporting',
                    'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
    }

    private void grant(String subject, PlatformRole role) {
        jdbc.sql("""
                INSERT INTO iam.grants
                    (id, tenant_id, principal_subject, role_id, role_is_platform, scope_type, scope_id,
                     status, granted_by, reason, valid_from)
                VALUES (:id, :tenantId, :subject, :roleId, true, 'TENANT', :tenantId,
                        'ACTIVE', 'test-fixture', 'reporting capability endpoint test', :validFrom)
                ON CONFLICT DO NOTHING
                """)
                .param("id", UUID.nameUUIDFromBytes((subject + role.code()).getBytes(UTF_8)))
                .param("tenantId", TENANT)
                .param("subject", subject)
                .param("roleId", RoleRegistrySynchronizer.platformRoleId(role))
                .param("validFrom", Instant.now().minus(Duration.ofHours(1)).atOffset(ZoneOffset.UTC))
                .update();
    }

    private static RequestPostProcessor tokenFor(String subject) {
        return jwt().jwt(builder ->
                builder.subject(subject).claim("resource_access", Map.of("horecaos-api", Map.of("roles", List.of()))));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class StubIssuer {

        @Bean
        JwtDecoder jwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "unused")
                    .build();
        }
    }
}
