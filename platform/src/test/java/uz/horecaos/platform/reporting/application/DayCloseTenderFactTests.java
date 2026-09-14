package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
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
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * P39 / ADR 0043 / ADR 0115: {@code reporting.fact_order_tender}, the
 * producer inside {@link DayCloseService#close}, and the {@code
 * payment_mix.amount.v1} read path over it.
 *
 * <p>A self-contained fixture on purpose, deliberately not added to {@code
 * DayCloseAndMetricLayerTests} — that file is 1000+ lines and shared by every
 * other reporting wave; this one owns its own tenant, brand, location, and
 * payment-method registry so a parallel wave editing that file never has to
 * reason about tenders.
 */
class DayCloseTenderFactTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-2000-7000-8000-0000000c9001");
    private static final UUID BRAND = UUID.fromString("018f6f4e-2000-7000-8000-0000000c9002");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-2000-7000-8000-0000000c9003");
    private static final UUID ENTITY = UUID.fromString("018f6f4e-2000-7000-8000-0000000c9004");
    private static final UUID CUSTOMER = UUID.fromString("018f6f4e-2000-7000-8000-0000000c9005");
    private static final UUID CASH_METHOD = UUID.fromString("018f6f4e-2000-7000-8000-0000000c9006");
    private static final UUID CARD_METHOD = UUID.fromString("018f6f4e-2000-7000-8000-0000000c9007");

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcReportingStore store;
    private DayCloseService close;
    private ReportQueryService queries;
    private UUID channelId;
    private UUID publicationId;

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

        jdbc.sql("TRUNCATE TABLE reporting.aggregate_divergences, reporting.close_runs")
                .update();
        jdbc.sql("""
                TRUNCATE TABLE reporting.fact_order, reporting.fact_order_line,
                    reporting.fact_order_tender, reporting.fact_refund, reporting.agg_branch_day,
                    reporting.agg_sla_bucket_day, reporting.business_day_policies,
                    reporting.metric_definitions, reporting.fact_call_hour
                """).update();
        jdbc.sql("TRUNCATE TABLE payments.tenders, payments.order_settlements CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE payments.payment_intents CASCADE").update();
        jdbc.sql("TRUNCATE TABLE payments.payment_methods CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.orders CASCADE").update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.catalogs CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(Instant.parse("2026-09-02T04:00:00Z"), ZoneOffset.UTC);
        store = new JdbcReportingStore(jdbc);
        BusinessDayService businessDays = new BusinessDayService(store);
        close = new DayCloseService(store, businessDays, new SubjectPseudonym(new StubProtection()), clock);
        queries = new ReportQueryService(store, businessDays, clock);

        new MetricDefinitionSynchronizer(store).synchronizeAll();
        seedTenancy();
        seedPaymentMethods();
    }

    // --------------------------------------------------- the tender producer

    @Test
    @DisplayName("a split-tender order writes one fact_order_tender row per tender, summing to the order total")
    void aSplitTenderOrderProducesTwoRowsSummingToTheOrderTotal() {
        UUID orderId = insertOrder("SPLIT", 100_000);
        insertSettlement(
                orderId,
                100_000,
                tender(1, CASH_METHOD, 60_000, 0, "SETTLED"),
                tender(2, CARD_METHOD, 40_000, 0, "SETTLED"));

        close.close(TENANT, DAY);

        List<Map<String, Object>> rows =
                jdbc.sql("""
                SELECT tender_sequence, payment_method_code, amount_som, tender_status
                  FROM reporting.fact_order_tender
                 WHERE tenant_id = :t AND order_id = :o
                 ORDER BY tender_sequence
                """).param("t", TENANT).param("o", orderId).query().listOfRows();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("payment_method_code", "CASH")
                .containsEntry("amount_som", 60_000L)
                .containsEntry("tender_status", "SETTLED");
        assertThat(rows.get(1))
                .containsEntry("payment_method_code", "CARD")
                .containsEntry("amount_som", 40_000L)
                .containsEntry("tender_status", "SETTLED");

        long total =
                rows.stream().mapToLong(row -> (Long) row.get("amount_som")).sum();
        assertThat(total)
                .as("split across two methods, the tender fact must still sum to the order total")
                .isEqualTo(100_000L);
    }

    @Test
    @DisplayName("a partially refunded tender nets out in place, never as a second row")
    void aPartiallyRefundedTenderNetsOut() {
        UUID orderId = insertOrder("PARTIAL-REFUND", 100_000);
        insertSettlement(orderId, 100_000, tender(1, CASH_METHOD, 100_000, 30_000, "SETTLED"));

        close.close(TENANT, DAY);

        List<Map<String, Object>> rows =
                jdbc.sql("""
                SELECT amount_som, tender_status FROM reporting.fact_order_tender
                 WHERE tenant_id = :t AND order_id = :o
                """).param("t", TENANT).param("o", orderId).query().listOfRows();

        assertThat(rows)
                .as("never a second row for the refunded portion — see TenderFact's own doc")
                .hasSize(1);
        assertThat(rows.getFirst()).containsEntry("amount_som", 70_000L).containsEntry("tender_status", "SETTLED");
    }

    @Test
    @DisplayName("a fully refunded tender reads REVERSED and zero, never negative")
    void aFullyRefundedTenderReadsZero() {
        UUID orderId = insertOrder("FULL-REFUND", 50_000);
        insertSettlement(orderId, 50_000, tender(1, CASH_METHOD, 50_000, 50_000, "REVERSED"));

        close.close(TENANT, DAY);

        Map<String, Object> row =
                jdbc.sql("""
                SELECT amount_som, tender_status FROM reporting.fact_order_tender
                 WHERE tenant_id = :t AND order_id = :o
                """).param("t", TENANT).param("o", orderId).query().singleRow();

        assertThat(row).containsEntry("amount_som", 0L).containsEntry("tender_status", "REVERSED");
    }

    // ------------------------------------------------ payment_mix.amount.v1

    @Test
    @DisplayName("payment_mix.amount.v1's inclusion rule excludes a tender that never collected money")
    void readPaymentMixExcludesTendersThatNeverMovedMoney() {
        UUID settled = insertOrder("COLLECTED", 40_000);
        insertSettlement(settled, 40_000, tender(1, CASH_METHOD, 40_000, 0, "SETTLED"));

        UUID failed = insertOrder("NEVER-COLLECTED", 20_000);
        insertSettlement(failed, 20_000, tender(1, CASH_METHOD, 20_000, 0, "FAILED"));

        close.close(TENANT, DAY);

        List<JdbcReportingStore.PaymentMixRow> mix = store.readPaymentMix(TENANT, DAY, DAY, List.of());

        assertThat(mix).hasSize(1);
        assertThat(mix.getFirst().amountSom())
                .as("a FAILED tender never collected money and must not inflate takings")
                .isEqualTo(40_000L);
        assertThat(mix.getFirst().tenderCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("ReportQueryService.paymentMix folds the overview and keeps the branch split")
    void paymentMixOverviewAndByLocationAgreeAtOneLocation() {
        UUID orderId = insertOrder("MIX", 100_000);
        insertSettlement(
                orderId,
                100_000,
                tender(1, CASH_METHOD, 70_000, 0, "SETTLED"),
                tender(2, CARD_METHOD, 30_000, 0, "SETTLED"));

        close.close(TENANT, DAY);

        var result = queries.paymentMix(TENANT, DAY, DAY, List.of());

        assertThat(result.overview()).hasSize(2);
        assertThat(result.byLocation()).hasSize(2);
        long overviewTotal = result.overview().stream()
                .mapToLong(ReportQueryService.PaymentMixRow::amountSom)
                .sum();
        long byLocationTotal = result.byLocation().stream()
                .mapToLong(ReportQueryService.PaymentMixRow::amountSom)
                .sum();
        assertThat(overviewTotal).isEqualTo(100_000L);
        assertThat(byLocationTotal).isEqualTo(100_000L);
        assertThat(result.overview())
                .allSatisfy(row -> assertThat(row.locationId())
                        .as("the overview folds every branch — never a per-branch row")
                        .isNull());
        assertThat(result.byLocation())
                .allSatisfy(row -> assertThat(row.locationId()).isEqualTo(LOCATION));
        assertThat(result.provenance().metricVersions()).contains("payment_mix.amount.v1");
    }

    // ---------------------------------------------------------------- setup

    private record TenderSpec(
            int sequence, UUID paymentMethodId, long amountMinor, long refundedMinor, String status) {}

    private static TenderSpec tender(
            int sequence, UUID paymentMethodId, long amountMinor, long refundedMinor, String status) {
        return new TenderSpec(sequence, paymentMethodId, amountMinor, refundedMinor, status);
    }

    private void insertSettlement(UUID orderId, long totalDueMinor, TenderSpec... tenders) {
        UUID settlementId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payments.order_settlements (
                    id, tenant_id, order_id, currency, total_due_minor, settled_minor, status, version)
                VALUES (:id, :t, :order, 'UZS', :total, :total, 'SETTLED', 1)
                """)
                .param("id", settlementId)
                .param("t", TENANT)
                .param("order", orderId)
                .param("total", totalDueMinor)
                .update();

        for (TenderSpec spec : tenders) {
            // ck_tender_settled_at_pairs_with_status: settled_at is set exactly
            // when the tender reached SETTLED or REVERSED, never otherwise.
            boolean terminalMoney = "SETTLED".equals(spec.status()) || "REVERSED".equals(spec.status());
            jdbc.sql("""
                    INSERT INTO payments.tenders (
                        id, tenant_id, settlement_id, sequence, payment_method_id,
                        settles_from_balance, amount_minor, refunded_minor, currency, status,
                        settled_at, idempotency_key, version)
                    VALUES (
                        gen_random_uuid(), :t, :settlement, :sequence, :method,
                        false, :amount, :refunded, 'UZS', :status,
                        :settledAt, :idempotency, 1)
                    """)
                    .param("t", TENANT)
                    .param("settlement", settlementId)
                    .param("sequence", spec.sequence())
                    .param("method", spec.paymentMethodId())
                    .param("amount", spec.amountMinor())
                    .param("refunded", spec.refundedMinor())
                    .param("status", spec.status())
                    .param("settledAt", terminalMoney ? tashkent(13, 0).atOffset(ZoneOffset.UTC) : null)
                    .param("idempotency", "tender-" + settlementId + "-" + spec.sequence())
                    .update();
        }
    }

    private UUID insertOrder(String seed, long totalMinor) {
        UUID orderId = orderId(seed);
        UUID cartId = UUID.nameUUIDFromBytes(("cart:" + seed).getBytes(StandardCharsets.UTF_8));
        UUID quoteId = UUID.nameUUIDFromBytes(("quote:" + seed).getBytes(StandardCharsets.UTF_8));
        Instant createdAt = tashkent(13, 0);

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    customer_account_id, fulfillment_mode, currency, status, expires_at,
                    converted_order_id)
                VALUES (:id, :t, :b, :loc, :ch, :cust, 'DELIVERY', 'UZS', 'CONVERTED', :expires, :orderId)
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
                    :total, 0, 0, 0, :total, :expires, :accepted)
                """)
                .param("id", quoteId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("cust", CUSTOMER)
                .param("pub", publicationId)
                .param("hash", "hash-" + seed)
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
                    cart_id, idempotency_key, promise_basis, version, created_at, confirmed_at, closed_at)
                VALUES (:id, :number, :t, :b, :loc, :ch, 'TELEGRAM', :cust,
                    'DELIVERY', 'AUTO_CONFIRM', 'NONE',
                    'COMPLETED', 'UZS', :total, 0, 0, 0,
                    :total, :quote, :hash, :pub,
                    :cart, :key, 'NOT_PROMISED',
                    1, :createdAt, :confirmedAt, :closedAt)
                """)
                .param("id", orderId)
                .param("number", seed)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channelId)
                .param("cust", CUSTOMER)
                .param("total", totalMinor)
                .param("quote", quoteId)
                .param("hash", "hash-" + seed)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + seed)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .param("confirmedAt", createdAt.plusSeconds(120).atOffset(ZoneOffset.UTC))
                .param("closedAt", createdAt.plusSeconds(1_800).atOffset(ZoneOffset.UTC))
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
                .param("entity", ENTITY)
                .param("amount", totalMinor)
                .param("key", "intent-" + seed)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .update();

        return orderId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'tender-fact-tenant', 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent',
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
                INSERT INTO tenant.legal_entities (id, tenant_id, code, legal_name, tin, status)
                VALUES (:id, :t, 'ENTITY', 'Birinchi MCHJ', '123456789', 'ACTIVE')
                """).param("id", ENTITY).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                    identity_policy_version, version)
                VALUES (:id, :t, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", CUSTOMER).param("t", TENANT).update();

        channelId = UUID.nameUUIDFromBytes("tender-fact-channel".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :t, 'TELEGRAM', 'TELEGRAM', 'Telegram bot', 'ACTIVE', false)
                """).param("id", channelId).param("t", TENANT).update();

        UUID catalogId = UUID.nameUUIDFromBytes("tender-fact-catalog".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();

        publicationId = UUID.nameUUIDFromBytes("tender-fact-publication".getBytes(StandardCharsets.UTF_8));
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

    /** Two ordinary money methods, both OPERATOR-responsibility so no provider binding is needed. */
    private void seedPaymentMethods() {
        jdbc.sql("""
                INSERT INTO payments.payment_methods (
                    id, tenant_id, code, display_name, responsibility, settles_from_balance,
                    status, version)
                VALUES (:id, :t, 'CASH', 'Cash', 'OPERATOR', false, 'ACTIVE', 1)
                """).param("id", CASH_METHOD).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO payments.payment_methods (
                    id, tenant_id, code, display_name, responsibility, settles_from_balance,
                    status, version)
                VALUES (:id, :t, 'CARD', 'Card', 'TERMINAL', false, 'ACTIVE', 1)
                """).param("id", CARD_METHOD).param("t", TENANT).update();
    }

    private static Instant tashkent(int hour, int minute) {
        return ZonedDateTime.of(DAY, LocalTime.of(hour, minute), TASHKENT).toInstant();
    }

    private static UUID orderId(String seed) {
        return UUID.nameUUIDFromBytes(("tender-fact-order:" + seed).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A deterministic stand-in for the ADR 0029 keyed hash — see {@code
     * DayCloseAndMetricLayerTests.StubProtection}'s own doc for why only
     * {@code lookupHash} needs a body.
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
}
