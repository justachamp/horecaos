package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;
import uz.horecaos.platform.reporting.domain.Grain;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The day-grain slice of ADR 0043, end to end against PostgreSQL.
 *
 * <p>The cases are the ones the ADR's testing section names, plus the three the
 * operations prototype surfaced: provenance per number, no combined total across
 * legal entities, and a recut that alerts rather than overwrites.
 */
class DayCloseAndMetricLayerTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-1000-7000-8000-00000000b001");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-1000-7000-8000-00000000b0ff");
    private static final UUID BRAND = UUID.fromString("018f6f4e-1000-7000-8000-00000000b002");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-1000-7000-8000-00000000b003");
    private static final UUID ENTITY_A = UUID.fromString("018f6f4e-1000-7000-8000-00000000b004");
    private static final UUID ENTITY_B = UUID.fromString("018f6f4e-1000-7000-8000-00000000b005");
    private static final UUID CUSTOMER = UUID.fromString("018f6f4e-1000-7000-8000-00000000b006");

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final LocalDate DAY = LocalDate.of(2026, 8, 21);

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;
    private final AtomicInteger statements = new AtomicInteger();
    private JdbcReportingStore store;
    private DayCloseService close;
    private ReportQueryService queries;
    private MetricSigningService signing;
    private RecordingAuditRecorder auditRecorder;
    private UUID channelId;
    private UUID publicationId;
    private UUID merchantBindingId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
        jdbcUrl = db.jdbcUrl();
        username = db.username();
        password = db.password();
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
        jdbc = JdbcClient.create(counting(dataSource, statements));

        jdbc.sql("TRUNCATE TABLE reporting.aggregate_divergences, reporting.close_runs")
                .update();
        jdbc.sql("""
                TRUNCATE TABLE reporting.fact_order, reporting.fact_order_line,
                    reporting.fact_refund, reporting.agg_branch_day, reporting.agg_sla_bucket_day,
                    reporting.business_day_policies, reporting.metric_definitions, reporting.fact_call_hour
                """).update();
        jdbc.sql("TRUNCATE TABLE voice.call_events CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.orders CASCADE").update();
        jdbc.sql("TRUNCATE TABLE payments.payment_intents CASCADE").update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.catalogs CASCADE").update();
        jdbc.sql("TRUNCATE TABLE integration.installations CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(Instant.parse("2026-08-22T04:00:00Z"), ZoneOffset.UTC);
        store = new JdbcReportingStore(jdbc);
        auditRecorder = new RecordingAuditRecorder();
        BusinessDayService businessDays = new BusinessDayService(store);
        close = new DayCloseService(store, businessDays, new SubjectPseudonym(new StubProtection()), clock);
        queries = new ReportQueryService(store, businessDays, clock);
        signing = new MetricSigningService(store, auditRecorder, clock);

        new MetricDefinitionSynchronizer(store).synchronizeAll();
        seedTenancy();
        seedPaymentConfiguration();
    }

    // ------------------------------------------------------------- the close

    @Test
    void aClosedDayReproducesByteIdenticallyWhenRecomputed() {
        insertOrder("A-1", ENTITY_A, "COMPLETED", tashkent(13, 0), tashkent(13, 40), 120_000, 0);
        insertOrder("A-2", ENTITY_A, "COMPLETED", tashkent(19, 0), tashkent(19, 35), 80_000, 10_000);

        close.close(TENANT, DAY);
        List<Map<String, Object>> first = aggregateRows();

        close.close(TENANT, DAY);
        List<Map<String, Object>> second = aggregateRows();

        assertThat(second)
                .as("a close that does not reproduce itself makes every divergence alert noise")
                .isEqualTo(first);
    }

    @Test
    void grossIsThePreDiscountFigureAndNetIsWhatWasPaid() {
        // The order row stores the total net of discount, so reading it as gross
        // would subtract the discount twice and put revenue.net below takings.
        insertOrder("A-1", ENTITY_A, "COMPLETED", tashkent(13, 0), tashkent(13, 40), 90_000, 10_000);

        close.close(TENANT, DAY);

        Map<String, Object> fact = jdbc.sql("""
                SELECT gross_revenue_som, discount_som, net_revenue_som
                  FROM reporting.fact_order WHERE tenant_id = :t
                """).param("t", TENANT).query().singleRow();

        assertThat(fact)
                .containsEntry("gross_revenue_som", 100_000L)
                .containsEntry("discount_som", 10_000L)
                .containsEntry("net_revenue_som", 90_000L);
    }

    @Test
    void anOrderPastItsPromiseIsLateAndOneWithNoPromiseIsNeither() {
        insertOrder("LATE", ENTITY_A, "COMPLETED", tashkent(18, 0), tashkent(19, 10), 50_000, 0, tashkent(18, 40));
        insertOrder("UNPROMISED", ENTITY_A, "COMPLETED", tashkent(18, 5), tashkent(19, 30), 50_000, 0, null);

        close.close(TENANT, DAY);

        Map<String, Object> late = jdbc.sql("""
                SELECT seconds_late FROM reporting.fact_order
                 WHERE tenant_id = :t AND order_id = :id
                """)
                .param("t", TENANT)
                .param("id", orderId("LATE"))
                .query()
                .singleRow();
        Map<String, Object> unpromised = jdbc.sql("""
                SELECT seconds_late FROM reporting.fact_order
                 WHERE tenant_id = :t AND order_id = :id
                """)
                .param("t", TENANT)
                .param("id", orderId("UNPROMISED"))
                .query()
                .singleRow();

        assertThat(late).containsEntry("seconds_late", 1800);
        assertThat(unpromised.get("seconds_late"))
                .as("an order with no promise is a third state, not an on-time order")
                .isNull();

        var aggregate = store.readAggregates(TENANT, DAY, DAY).getFirst();
        assertThat(aggregate.promisedCount()).isEqualTo(1);
        assertThat(aggregate.lateCount()).isEqualTo(1);
    }

    // ---------------------------------------------------- ADR 0064 call facts

    @Test
    @DisplayName("call events close through the same pipeline as orders, bucketed by hour and operator")
    void callFactsAreWrittenThroughTheSameClosePipeline() {
        UUID installation = UUID.randomUUID();
        insertCallEvent(installation, "call-1", "OFFERED", null, null, tashkent(10, 0));
        insertCallEvent(installation, "call-1", "ANSWERED", "alice", null, tashkent(10, 0, 5));
        insertCallEvent(installation, "call-1", "ENDED", "alice", 90, tashkent(10, 1, 35));
        insertCallEvent(installation, "call-2", "OFFERED", null, null, tashkent(10, 30));
        insertCallEvent(installation, "call-2", "MISSED", null, null, tashkent(10, 30, 20));

        var result = close.close(TENANT, DAY);
        assertThat(result.callsWritten()).isEqualTo(2);

        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT hour_of_day, operator_principal_id, offered_count, answered_count, missed_count,
                       talk_duration_seconds
                FROM reporting.fact_call_hour
                WHERE tenant_id = :t AND location_id = :l AND business_date = :day
                ORDER BY operator_principal_id
                """)
                .param("t", TENANT)
                .param("l", LOCATION)
                .param("day", DAY)
                .query()
                .listOfRows();

        assertThat(rows).hasSize(2);
        Map<String, Object> unassigned = rows.stream()
                .filter(row -> "(unassigned)".equals(row.get("operator_principal_id")))
                .findFirst()
                .orElseThrow();
        assertThat(unassigned.get("offered_count")).isEqualTo(2);
        assertThat(unassigned.get("missed_count")).isEqualTo(1);

        Map<String, Object> alice = rows.stream()
                .filter(row -> "alice".equals(row.get("operator_principal_id")))
                .findFirst()
                .orElseThrow();
        assertThat(alice.get("answered_count")).isEqualTo(1);
        Number talkDurationSeconds = (Number) Objects.requireNonNull(alice.get("talk_duration_seconds"));
        assertThat(talkDurationSeconds.longValue()).isEqualTo(90L);
    }

    @Test
    @DisplayName("closing the same day twice reproduces the same call-hour figures")
    void callFactsAreIdempotentOnARepeatedClose() {
        UUID installation = UUID.randomUUID();
        insertCallEvent(installation, "call-1", "OFFERED", null, null, tashkent(9, 0));

        close.close(TENANT, DAY);
        long firstCount = jdbc.sql("SELECT count(*) FROM reporting.fact_call_hour WHERE tenant_id = :t")
                .param("t", TENANT)
                .query(Long.class)
                .single();

        close.close(TENANT, DAY);
        long secondCount = jdbc.sql("SELECT count(*) FROM reporting.fact_call_hour WHERE tenant_id = :t")
                .param("t", TENANT)
                .query(Long.class)
                .single();

        assertThat(secondCount).isEqualTo(firstCount);
    }

    private void insertCallEvent(
            UUID installationId,
            String providerCallId,
            String eventType,
            @Nullable String operatorPrincipalId,
            @Nullable Integer durationSeconds,
            Instant occurredAt) {
        jdbc.sql("""
                INSERT INTO voice.call_events
                    (id, tenant_id, brand_id, location_id, installation_id, provider_call_id, event_type,
                     direction, operator_principal_id, duration_seconds, occurred_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :installationId, :providerCallId, :eventType,
                        'INBOUND', :operatorPrincipalId, :durationSeconds, :occurredAt)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("locationId", LOCATION)
                .param("installationId", installationId)
                .param("providerCallId", providerCallId)
                .param("eventType", eventType)
                .param("operatorPrincipalId", operatorPrincipalId)
                .param("durationSeconds", durationSeconds)
                .param("occurredAt", occurredAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private static Instant tashkent(int hour, int minute, int second) {
        return ZonedDateTime.of(DAY, LocalTime.of(hour, minute, second), TASHKENT)
                .toInstant();
    }

    // ------------------------------------------------------------- refunds

    @Test
    void aRefundThreeDaysLaterMovesNetOnItsOwnDateAndLeavesGrossAlone() {
        insertOrder("A-1", ENTITY_A, "COMPLETED", tashkent(13, 0), tashkent(13, 40), 200_000, 0);
        close.close(TENANT, DAY);

        long grossBefore = metric(DAY, DAY, "revenue.gross.v1");
        long netBefore = metric(DAY, DAY, "revenue.net.v1");

        LocalDate refundDay = DAY.plusDays(3);
        insertRefund(
                orderId("A-1"),
                50_000,
                ZonedDateTime.of(refundDay, LocalTime.of(11, 0), TASHKENT).toInstant());
        close.close(TENANT, refundDay);

        assertThat(metric(DAY, DAY, "revenue.gross.v1"))
                .as("a closed day does not change because a refund arrived later")
                .isEqualTo(grossBefore);
        assertThat(metric(DAY, DAY, "revenue.net.v1")).isEqualTo(netBefore);
        assertThat(metric(refundDay, refundDay, "revenue.net.v1"))
                .as("the refund lands on the day it was issued")
                .isEqualTo(-50_000L);
    }

    @Test
    @DisplayName("a day's refunds are resolved in one read, and a second refund on one order still files")
    void refundsAreResolvedAsASetRatherThanOneQueryEach() {
        insertOrder("A-1", ENTITY_A, "COMPLETED", tashkent(13, 0), tashkent(13, 40), 200_000, 0);
        insertOrder("A-2", ENTITY_B, "COMPLETED", tashkent(14, 0), tashkent(14, 40), 300_000, 0);
        insertOrder("A-3", ENTITY_A, "COMPLETED", tashkent(15, 0), tashkent(15, 40), 400_000, 0);
        close.close(TENANT, DAY);

        LocalDate refundDay = DAY.plusDays(1);
        Instant midMorning =
                ZonedDateTime.of(refundDay, LocalTime.of(11, 0), TASHKENT).toInstant();
        // Two refunds against one order: a partial, then the rest of it. The order
        // is looked up once and both refunds have to be filed against it, which is
        // what a set-based read gets wrong if it walks the orders it found instead
        // of the refunds it was given.
        insertRefund(orderId("A-1"), 10_000, midMorning);
        for (int extra = 1; extra <= 29; extra++) {
            insertAdditionalRefund(orderId("A-1"), 1_000, midMorning.plusSeconds(extra));
        }
        insertRefund(orderId("A-2"), 20_000, midMorning.plusSeconds(120));

        statements.set(0);
        var result = close.close(TENANT, refundDay);
        int used = statements.get();

        assertThat(result.refundsWritten()).isEqualTo(31);
        assertThat(metric(refundDay, refundDay, "revenue.net.v1")).isEqualTo(-59_000L);
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM reporting.fact_refund
                         WHERE tenant_id = :t AND business_date = :day
                        """)
                        .param("t", TENANT)
                        .param("day", refundDay)
                        .query(Integer.class)
                        .single())
                .isEqualTo(31);

        // Each refund also keeps the order's own business date, which is the whole
        // reason the order has to be read at all.
        assertThat(jdbc.sql("""
                        SELECT DISTINCT order_business_date FROM reporting.fact_refund
                         WHERE tenant_id = :t AND business_date = :day
                        """)
                        .param("t", TENANT)
                        .param("day", refundDay)
                        .query(LocalDate.class)
                        .list())
                .containsExactly(DAY);

        // Thirty-one refunds cost one read of the orders behind them, not
        // thirty-one. The write per refund is unavoidable; the lookup per refund
        // was a round-trip inside the close transaction for an answer the previous
        // refund had already fetched.
        assertThat(used)
                .as("a busy Saturday's close should not spend a round-trip per refund")
                .isLessThanOrEqualTo(55);
    }

    // ----------------------------------------------------- the settle recut

    @Test
    void aRecutThatDisagreesAlertsAndDoesNotOverwrite() {
        insertOrder("A-1", ENTITY_A, "COMPLETED", tashkent(13, 0), tashkent(13, 40), 120_000, 0);
        close.close(TENANT, DAY);

        // Something moved between the close and the settle window. Whether it is a
        // late correction or a projection bug is exactly what a person has to
        // decide, which is why the stored figure stays put.
        jdbc.sql("UPDATE reporting.agg_branch_day SET gross_som = 999_000 WHERE tenant_id = :t")
                .param("t", TENANT)
                .update();

        var result = close.recut(TENANT, DAY);

        assertThat(result.divergences()).hasSize(1);
        assertThat(result.divergences().getFirst().metricName()).isEqualTo("revenue.gross");
        assertThat(result.divergences().getFirst().difference()).isEqualTo(120_000L - 999_000L);

        assertThat(jdbc.sql("SELECT gross_som FROM reporting.agg_branch_day WHERE tenant_id = :t")
                        .param("t", TENANT)
                        .query(Long.class)
                        .single())
                .as("somebody may already have acted on the earlier figure, so it is left alone")
                .isEqualTo(999_000L);

        assertThat(store.readOpenDivergences(TENANT)).hasSize(1);
    }

    @Test
    void aRecutThatAgreesRecordsNothing() {
        insertOrder("A-1", ENTITY_A, "COMPLETED", tashkent(13, 0), tashkent(13, 40), 120_000, 0);
        close.close(TENANT, DAY);

        assertThat(close.recut(TENANT, DAY).divergences()).isEmpty();
        assertThat(store.readOpenDivergences(TENANT)).isEmpty();
    }

    // --------------------------------------------------------- legal entity

    @Test
    void aMoneyTotalAcrossTwoLegalEntitiesIsRefused() {
        insertOrder("A-1", ENTITY_A, "COMPLETED", tashkent(13, 0), tashkent(13, 40), 120_000, 0);
        insertOrder("B-1", ENTITY_B, "COMPLETED", tashkent(14, 0), tashkent(14, 40), 80_000, 0);
        close.close(TENANT, DAY);

        assertThatThrownBy(() -> queries.run(new ReportQuery(
                        TENANT, DAY, DAY, List.of("revenue.gross.v1"), List.of(), List.of(), List.of())))
                .isInstanceOf(ReportingRefusals.CombinedEntityTotalException.class)
                .hasMessageContaining("neither tax filing");
    }

    @Test
    void thePerEntityCutIsAllowedAndSumsToWhatTheCombinedTotalWouldHaveBeen() {
        insertOrder("A-1", ENTITY_A, "COMPLETED", tashkent(13, 0), tashkent(13, 40), 120_000, 0);
        insertOrder("B-1", ENTITY_B, "COMPLETED", tashkent(14, 0), tashkent(14, 40), 80_000, 0);
        close.close(TENANT, DAY);

        var result = queries.run(new ReportQuery(
                TENANT,
                DAY,
                DAY,
                List.of("revenue.gross.v1"),
                List.of(Grain.Dimension.LEGAL_ENTITY),
                List.of(),
                List.of()));

        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().stream()
                        .mapToLong(row -> row.values().get("revenue.gross.v1"))
                        .sum())
                .as("the parts exist and reconcile; it is only the platform printing the sum "
                        + "as one figure that ADR 0038 forbids")
                .isEqualTo(200_000L);
    }

    @Test
    void anOperationalCutAcrossTwoEntitiesIsNotRefused() {
        insertOrder("A-1", ENTITY_A, "COMPLETED", tashkent(13, 0), tashkent(13, 40), 120_000, 0);
        insertOrder("B-1", ENTITY_B, "COMPLETED", tashkent(14, 0), tashkent(14, 40), 80_000, 0);
        close.close(TENANT, DAY);

        var result = queries.run(
                new ReportQuery(TENANT, DAY, DAY, List.of("orders.count.v1"), List.of(), List.of(), List.of()));

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst().values()).containsEntry("orders.count.v1", 2L);
    }

    // ----------------------------------------------------------- the registry

    @Test
    void anUnknownMetricIsRejectedRatherThanIgnored() {
        assertThatThrownBy(() -> queries.run(new ReportQuery(
                        TENANT,
                        DAY,
                        DAY,
                        List.of("revenue.gross.v1", "revenue.imaginary.v1"),
                        List.of(Grain.Dimension.LEGAL_ENTITY),
                        List.of(),
                        List.of())))
                .hasMessageContaining("revenue.imaginary.v1");
    }

    @Test
    void anUnbuiltMetricIsRefusedRatherThanAnsweredWithZero() {
        assertThatThrownBy(() -> queries.run(new ReportQuery(
                        TENANT,
                        DAY,
                        DAY,
                        List.of("delivery_cost_variance.v1"),
                        List.of(Grain.Dimension.LEGAL_ENTITY),
                        List.of(),
                        List.of())))
                .isInstanceOf(ReportingRefusals.MetricNotBuiltException.class);
    }

    @Test
    void everyAnswerStatesItsProvenance() {
        insertOrder("A-1", ENTITY_A, "COMPLETED", tashkent(13, 0), tashkent(13, 40), 120_000, 0);
        close.close(TENANT, DAY);

        var provenance = queries.run(new ReportQuery(
                        TENANT,
                        DAY,
                        DAY,
                        List.of("revenue.gross.v1"),
                        List.of(Grain.Dimension.LEGAL_ENTITY),
                        List.of(),
                        List.of()))
                .provenance();

        assertThat(provenance.metricVersions()).containsExactly("revenue.gross.v1");
        assertThat(provenance.timezone()).isEqualTo("Asia/Tashkent");
        assertThat(provenance.businessDayStart()).isEqualTo("00:00");
        assertThat(provenance.closedThrough()).isEqualTo(DAY);
        assertThat(provenance.provisionalMetricCodes())
                .as("version 1 ships provisional and the API has to say so")
                .containsExactly("revenue.gross.v1");
    }

    @Test
    void aSignatureIsRecordedOnceAndAudited() {
        signing.sign(
                "revenue.gross.v1", ActorRef.user("finance-1", "Finance"), "Signed at the 21 August finance review");

        assertThat(auditRecorder.facts).hasSize(1);
        assertThat(auditRecorder.facts.getFirst().actionCode()).isEqualTo("reporting.metric.signed");
        assertThat(auditRecorder.facts.getFirst().changeDocument()).containsKey("definitionDigest");

        assertThatThrownBy(
                        () -> signing.sign("revenue.gross.v1", ActorRef.user("finance-2", "Finance"), "Signing again"))
                .isInstanceOf(MetricSigningService.AlreadySignedException.class);
    }

    @Test
    void aDefinitionEditedInPlaceStopsTheApplication() {
        jdbc.sql("""
                UPDATE reporting.metric_definitions SET definition_digest = repeat('0', 64)
                 WHERE metric_id = 'revenue.gross'
                """).update();

        assertThatThrownBy(() -> new MetricDefinitionSynchronizer(store).synchronizeAll())
                .isInstanceOf(MetricDefinitionSynchronizer.MetricDefinitionDriftException.class)
                .hasMessageContaining("a definition change is a new version");
    }

    // ---------------------------------------------------------- the boundary

    @Test
    void aRangeSpanningAnUnfinishedBoundaryRecutIsRefused() {
        new BusinessDayService(store)
                .setBoundary(TENANT, new BusinessDayBoundary(TASHKENT, LocalTime.of(9, 0), 2), DAY, DAY);

        assertThatThrownBy(() -> queries.run(new ReportQuery(
                        TENANT,
                        DAY.minusDays(2),
                        DAY.plusDays(2),
                        List.of("orders.count.v1"),
                        List.of(),
                        List.of(),
                        List.of())))
                .isInstanceOf(ReportingRefusals.MixedBoundaryRegimeException.class);

        // Either side of the frontier on its own is answerable, so the refusal is
        // exactly as wide as the problem.
        queries.run(new ReportQuery(
                TENANT, DAY.plusDays(1), DAY.plusDays(2), List.of("orders.count.v1"), List.of(), List.of(), List.of()));
    }

    // ---------------------------------------------------------- isolation

    @Test
    void oneTenantsQueryCannotReturnAnothersRow() {
        insertOrder("A-1", ENTITY_A, "COMPLETED", tashkent(13, 0), tashkent(13, 40), 120_000, 0);
        close.close(TENANT, DAY);

        seedOtherTenant();
        jdbc.sql("""
                INSERT INTO reporting.agg_branch_day (
                    tenant_id, business_date, location_id, legal_entity_id, channel_code,
                    fulfilment_type, boundary_version, metric_calculation_version, order_count,
                    cancelled_count, gross_som, discount_som, net_som, refunded_som,
                    promised_count, late_count, distinct_customers, new_customers)
                VALUES (:t, :d, :loc, :e, 'TELEGRAM', 'DELIVERY', 1, 1, 99, 0, 999_999, 0,
                    999_999, 0, 0, 0, 0, 0)
                """)
                .param("t", OTHER_TENANT)
                .param("d", DAY)
                .param("loc", UUID.randomUUID())
                .param("e", ENTITY_A)
                .update();

        var result = queries.run(
                new ReportQuery(TENANT, DAY, DAY, List.of("orders.count.v1"), List.of(), List.of(), List.of()));

        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().getFirst().values()).containsEntry("orders.count.v1", 1L);
    }

    @Test
    void theReadOnlyRoleCannotWriteAndCannotReachAModuleSchema() throws Exception {
        // ADR 0023's rule as a grant rather than a convention. Driven on one raw
        // connection on purpose: SET ROLE is session state, and the pooled client
        // hands out a fresh connection per statement, so a test written through it
        // would silently keep running as the owner and pass forever.
        try (var connection = java.sql.DriverManager.getConnection(jdbcUrl, username, password);
                var statement = connection.createStatement()) {

            statement.execute("SET ROLE horecaos_reporting_read");

            try (var reading = statement.executeQuery("SELECT count(*) FROM reporting.agg_branch_day")) {
                assertThat(reading.next())
                        .as("the role can read what it exists to read")
                        .isTrue();
            }

            assertThatThrownBy(() -> statement.execute("""
                    INSERT INTO reporting.close_runs (id, tenant_id, business_date, run_kind,
                        status, boundary_version, metric_calculation_version, started_at)
                    VALUES (gen_random_uuid(), gen_random_uuid(), current_date, 'CLOSE',
                        'RUNNING', 1, 1, now())
                    """)).hasMessageContaining("permission denied");

            assertThatThrownBy(() -> statement.executeQuery("SELECT count(*) FROM ordering.orders"))
                    .as("a reporting query that reaches a module table fails at the database "
                            + "rather than in review, or not at all")
                    .hasMessageContaining("permission denied");
        }
    }

    // ------------------------------------------------------------- fixtures

    private long metric(LocalDate from, LocalDate to, String code) {
        var result = queries.run(new ReportQuery(
                TENANT, from, to, List.of(code), List.of(Grain.Dimension.LEGAL_ENTITY), List.of(), List.of()));
        return result.rows().stream()
                .map(row -> row.values().get(code))
                .filter(java.util.Objects::nonNull)
                .mapToLong(Long::longValue)
                .sum();
    }

    private List<Map<String, Object>> aggregateRows() {
        return jdbc.sql("""
                SELECT location_id, legal_entity_id, channel_code, fulfilment_type, order_count,
                       cancelled_count, gross_som, discount_som, net_som, refunded_som,
                       avg_seconds_total, promised_count, late_count, distinct_customers,
                       new_customers
                  FROM reporting.agg_branch_day
                 WHERE tenant_id = :t ORDER BY location_id, channel_code, fulfilment_type
                """).param("t", TENANT).query().listOfRows();
    }

    /**
     * A data source that counts the statements prepared through it.
     *
     * <p>Kept private to this class rather than shared: a helper in
     * {@code uz.horecaos.platform.support} would be a second suite's dependency on
     * this one's idea of what is worth counting.
     */
    private static DataSource counting(DataSource delegate, AtomicInteger prepared) {
        ClassLoader loader = DayCloseAndMetricLayerTests.class.getClassLoader();
        return (DataSource) Proxy.newProxyInstance(
                loader, new Class<?>[] {DataSource.class}, (proxy, method, arguments) -> {
                    Object result = invoke(method, delegate, arguments);
                    if (!(result instanceof Connection connection)) {
                        return result;
                    }
                    return Proxy.newProxyInstance(
                            loader,
                            new Class<?>[] {Connection.class},
                            (connectionProxy, connectionMethod, connectionArguments) -> {
                                if (connectionMethod.getName().startsWith("prepare")) {
                                    prepared.incrementAndGet();
                                }
                                return invoke(connectionMethod, connection, connectionArguments);
                            });
                });
    }

    private static Object invoke(Method method, Object target, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException wrapped) {
            // Unwrapped, or a SQLException a caller expects to catch arrives as an
            // UndeclaredThrowableException instead.
            throw wrapped.getCause();
        }
    }

    private static Instant tashkent(int hour, int minute) {
        return ZonedDateTime.of(DAY, LocalTime.of(hour, minute), TASHKENT).toInstant();
    }

    private static UUID orderId(String seed) {
        return UUID.nameUUIDFromBytes(("order:" + seed).getBytes(StandardCharsets.UTF_8));
    }

    private void insertOrder(
            String seed,
            UUID legalEntityId,
            String status,
            Instant createdAt,
            Instant closedAt,
            long totalMinor,
            long discountMinor) {
        insertOrder(seed, legalEntityId, status, createdAt, closedAt, totalMinor, discountMinor, null);
    }

    private void insertOrder(
            String seed,
            UUID legalEntityId,
            String status,
            Instant createdAt,
            Instant closedAt,
            long totalMinor,
            long discountMinor,
            @Nullable Instant promisedAt) {

        UUID orderId = orderId(seed);
        UUID cartId = UUID.nameUUIDFromBytes(("cart:" + seed).getBytes(StandardCharsets.UTF_8));
        UUID quoteId = UUID.nameUUIDFromBytes(("quote:" + seed).getBytes(StandardCharsets.UTF_8));
        long subtotal = totalMinor + discountMinor;

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    customer_account_id, fulfillment_mode, currency, status, expires_at,
                    converted_order_id)
                VALUES (:id, :t, :b, :loc, :ch, :cust, 'DELIVERY', 'UZS', 'CONVERTED',
                    :expires, :orderId)
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("cust", CUSTOMER)
                .param("expires", createdAt.atOffset(ZoneOffset.UTC))
                .param("orderId", orderId)
                .update();

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id,
                    customer_account_id, currency, status, catalog_publication_id,
                    calculation_version, context_hash, subtotal_minor, tax_minor, fee_minor,
                    discount_minor, total_minor, expires_at, accepted_at)
                VALUES (:id, :t, :b, :loc, :cust, 'UZS', 'ACCEPTED', :pub, 1, :hash,
                    :subtotal, 0, 0, :discount, :total, :expires, :accepted)
                """)
                .param("id", quoteId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("cust", CUSTOMER)
                .param("pub", publicationId)
                .param("hash", "hash-" + seed)
                .param("subtotal", subtotal)
                .param("discount", discountMinor)
                .param("total", totalMinor)
                .param("expires", createdAt.atOffset(ZoneOffset.UTC))
                .param("accepted", createdAt.atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, customer_account_id,
                    fulfillment_mode, acceptance_mode_snapshot, approval_channel_snapshot,
                    status, currency, subtotal_minor, tax_minor, discount_minor, fee_minor,
                    total_minor, pricing_quote_id, pricing_context_hash, catalog_publication_id,
                    cart_id, idempotency_key, promised_at, promise_basis, promise_prep_minutes,
                    version, created_at, confirmed_at, closed_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'TELEGRAM', :cust,
                    'DELIVERY', 'AUTO_CONFIRM', 'NONE',
                    :status, 'UZS', :subtotal, 0, :discount, 0,
                    :total, :quote, :hash, :pub,
                    :cart, :key, :promisedAt, :basis, :prep,
                    1, :createdAt, :confirmedAt, :closedAt)
                """)
                .param("id", orderId)
                .param("number", seed)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("cust", CUSTOMER)
                .param("status", status)
                .param("subtotal", subtotal)
                .param("discount", discountMinor)
                .param("total", totalMinor)
                .param("quote", quoteId)
                .param("hash", "hash-" + seed)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + seed)
                .param("promisedAt", promisedAt == null ? null : promisedAt.atOffset(ZoneOffset.UTC))
                .param("basis", promisedAt == null ? "NOT_PROMISED" : "PREPARATION_BAND")
                .param("prep", promisedAt == null ? null : 40)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .param("confirmedAt", createdAt.plusSeconds(120).atOffset(ZoneOffset.UTC))
                .param("closedAt", closedAt == null ? null : closedAt.atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                INSERT INTO payments.payment_intents (id, tenant_id, order_id, brand_id,
                    location_id, legal_entity_id, tender, payment_method_code, requested_amount_minor,
                    currency, status, capture_timing, idempotency_key, created_at, settled_at)
                VALUES (gen_random_uuid(), :t, :orderId, :b, :loc, :entity, 'CASH', 'CASH',
                    :amount, 'UZS', 'PAID', 'ON_HANDOVER', :key, :createdAt, :createdAt)
                """)
                .param("t", TENANT)
                .param("orderId", orderId)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("entity", legalEntityId)
                .param("amount", totalMinor)
                .param("key", "intent-" + seed)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertRefund(UUID orderId, long amountMinor, Instant occurredAt) {
        UUID intentId = jdbc.sql("""
                SELECT id FROM payments.payment_intents
                 WHERE tenant_id = :t AND order_id = :o
                """)
                .param("t", TENANT)
                .param("o", orderId)
                .query(UUID.class)
                .single();

        UUID attemptId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payments.payment_attempts (id, tenant_id, intent_id, provider_type,
                    merchant_binding_id, merchant_trans_id, business_date,
                    requested_amount_minor, currency, status, created_at, settled_at)
                VALUES (:id, :t, :intent, 'CLICK', :binding, :trans, :day, :amount, 'UZS',
                    'CAPTURED', :at, :at)
                """)
                .param("id", attemptId)
                .param("t", TENANT)
                .param("intent", intentId)
                .param("binding", merchantBindingId)
                .param("trans", "trans-" + attemptId)
                .param("day", DAY)
                .param("amount", amountMinor)
                .param("at", occurredAt.atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                INSERT INTO payments.payment_transactions (id, tenant_id, intent_id, attempt_id,
                    transaction_type, amount_minor, currency, provider_reference, occurred_at)
                VALUES (gen_random_uuid(), :t, :intent, :attempt, 'REFUND', :amount, 'UZS',
                    :reference, :at)
                """)
                .param("t", TENANT)
                .param("intent", intentId)
                .param("attempt", attemptId)
                .param("amount", amountMinor)
                .param("reference", "LOCAL:" + UUID.randomUUID())
                .param("at", occurredAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    /** A second refund on an order that already has a captured attempt. */
    private void insertAdditionalRefund(UUID orderId, long amountMinor, Instant occurredAt) {
        jdbc.sql("""
                INSERT INTO payments.payment_transactions (id, tenant_id, intent_id, attempt_id,
                    transaction_type, amount_minor, currency, provider_reference, occurred_at)
                SELECT gen_random_uuid(), a.tenant_id, a.intent_id, a.id, 'REFUND', :amount, 'UZS',
                       :reference, :at
                  FROM payments.payment_attempts a
                  JOIN payments.payment_intents i
                    ON i.id = a.intent_id AND i.tenant_id = a.tenant_id
                 WHERE a.tenant_id = :t AND i.order_id = :o
                """)
                .param("t", TENANT)
                .param("o", orderId)
                .param("amount", amountMinor)
                .param("reference", "LOCAL:" + UUID.randomUUID())
                .param("at", occurredAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'reporting-tenant', 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent',
                    'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CHI', 'chilonzor', 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                    identity_policy_version, version)
                VALUES (:id, :t, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", CUSTOMER).param("t", TENANT).update();

        channelId = UUID.nameUUIDFromBytes("channel".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :t, 'TELEGRAM', 'TELEGRAM', 'Telegram bot', 'ACTIVE', false)
                """).param("id", channelId).param("t", TENANT).update();

        UUID catalogId = UUID.nameUUIDFromBytes("catalog".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();

        publicationId = UUID.nameUUIDFromBytes("publication".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'TELEGRAM', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("cat", catalogId)
                .update();
    }

    /**
     * The payment configuration a refund transaction hangs off.
     *
     * <p>Present only because {@code payments.payment_transactions} is anchored to
     * an attempt, which is anchored to a merchant binding. Reporting reads none of
     * it beyond the intent's legal entity.
     */
    private void seedPaymentConfiguration() {
        UUID installationId = UUID.nameUUIDFromBytes("installation".getBytes(StandardCharsets.UTF_8));
        UUID bindingId = UUID.nameUUIDFromBytes("binding".getBytes(StandardCharsets.UTF_8));
        merchantBindingId = UUID.nameUUIDFromBytes("merchant-binding".getBytes(StandardCharsets.UTF_8));

        jdbc.sql("""
                INSERT INTO integration.provider_environments (code, provider_category,
                    provider_type, base_url, is_production, egress_allowlist)
                VALUES ('CLICK_SANDBOX', 'PAYMENT', 'CLICK', 'https://example.test', false, 'example.test')
                ON CONFLICT (code) DO NOTHING
                """).update();
        jdbc.sql("""
                INSERT INTO integration.installations (id, tenant_id, provider_category,
                    provider_type, environment_code, display_name, status)
                VALUES (:id, :t, 'PAYMENT', 'CLICK', 'CLICK_SANDBOX', 'Click', 'ACTIVE')
                """).param("id", installationId).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO integration.bindings (id, tenant_id, installation_id, brand_id, status)
                VALUES (:id, :t, :installation, :b, 'ACTIVE')
                """)
                .param("id", bindingId)
                .param("t", TENANT)
                .param("installation", installationId)
                .param("b", BRAND)
                .update();

        // V0053 made merchant_bindings.legal_entity_id a real foreign key. The
        // column existed before it and pointed at nothing, which is exactly why a
        // restaurant had no legal identity to issue a receipt under. Both entities
        // are seeded because the fixture books orders against each, and the day
        // close reports them apart.
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, status)
                VALUES (:id, :t, 'ENTITY-A', 'Birinchi MCHJ', '123456789', 'ACTIVE')
                """).param("id", ENTITY_A).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, status)
                VALUES (:id, :t, 'ENTITY-B', 'Ikkinchi MCHJ', '223456789', 'ACTIVE')
                """).param("id", ENTITY_B).param("t", TENANT).update();

        jdbc.sql("""
                INSERT INTO payments.merchant_bindings (id, tenant_id, legal_entity_id,
                    provider_type, installation_id, binding_id, merchant_account_reference,
                    secret_reference, callback_path_segment, supports_reversal,
                    supports_partner_fiscalization, status, effective_from)
                VALUES (:id, :t, :entity, 'CLICK', :installation, :binding, 'service-1',
                    'horecaos:test:provider_payment:tenant:click-1', 'reporting-click-1', true, false, 'ACTIVE', :from)
                """)
                .param("id", merchantBindingId)
                .param("t", TENANT)
                .param("entity", ENTITY_A)
                .param("installation", installationId)
                .param("binding", bindingId)
                .param("from", DAY.minusDays(30))
                .update();
    }

    private void seedOtherTenant() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'other-tenant', 'Legal', 'Other', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", OTHER_TENANT).update();
    }

    /**
     * A deterministic stand-in for the ADR 0029 keyed hash.
     *
     * <p>Only {@code lookupHash} is exercised: reporting never encrypts or reveals
     * anything, because there is no personal data anywhere in the schema to
     * encrypt.
     */
    private static final class StubProtection implements FieldProtection {

        @Override
        public ProtectedValue protect(UUID tenantId, DataClass dataClass, RecordRef record, String plaintext) {
            throw new UnsupportedOperationException("Reporting stores no protected values");
        }

        @Override
        public String reveal(UUID tenantId, ProtectedValue value, RecordRef record, String purpose) {
            throw new UnsupportedOperationException("Reporting reveals nothing");
        }

        @Override
        public String lookupHash(UUID tenantId, String lookupDomain, String normalizedValue) {
            return Integer.toHexString((tenantId + "|" + lookupDomain + "|" + normalizedValue).hashCode());
        }
    }

    private static final class RecordingAuditRecorder implements AuditRecorder {

        private final List<AuditFact> facts = new java.util.ArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
        }
    }
}
