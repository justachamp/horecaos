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
import java.util.ArrayList;
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
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.pricing.api.PromotionRedemptionSource;
import uz.horecaos.platform.pricing.api.PromotionRedemptionSource.Redemption;
import uz.horecaos.platform.pricing.api.PromotionRedemptionSource.Redemption.Kind;
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0140 / ADR 0043 (report 7.9): {@code reporting.fact_promotion_redemption}, the
 * producer inside {@link DayCloseService#close}, and the summary and log reads over it.
 *
 * <p>A self-contained fixture, the way {@code DayCloseTenderFactTests} is: its own tenant,
 * brand, location and customers, so a parallel reporting wave never has to reason about
 * promotions. The {@link PromotionRedemptionSource} is a stand-in for pricing's port
 * implementation (which {@code PromotionLedgerTests} covers against the real ledger); what
 * this class proves is what the reporting side does with what the port hands it.
 */
class PromotionRedemptionFactTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-2000-7000-8000-0000000d9001");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-2000-7000-8000-0000000d9101");
    private static final UUID BRAND = UUID.fromString("018f6f4e-2000-7000-8000-0000000d9002");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-2000-7000-8000-0000000d9003");
    private static final UUID ENTITY = UUID.fromString("018f6f4e-2000-7000-8000-0000000d9004");
    private static final UUID CUSTOMER_A = UUID.fromString("018f6f4e-2000-7000-8000-0000000d9005");
    private static final UUID CUSTOMER_B = UUID.fromString("018f6f4e-2000-7000-8000-0000000d9006");
    private static final UUID PROMOTION = UUID.fromString("018f6f4e-2000-7000-8000-0000000d9007");
    private static final UUID COUPON = UUID.fromString("018f6f4e-2000-7000-8000-0000000d9008");

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcReportingStore store;
    private DayCloseService close;
    private ReportQueryService queries;
    private FakeSource source;
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
                    reporting.metric_definitions, reporting.fact_call_hour,
                    reporting.fact_promotion_redemption
                """).update();
        jdbc.sql("TRUNCATE TABLE ordering.orders CASCADE").update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.catalogs CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        Clock clock = Clock.fixed(Instant.parse("2026-09-02T04:00:00Z"), ZoneOffset.UTC);
        store = new JdbcReportingStore(jdbc);
        BusinessDayService businessDays = new BusinessDayService(store);
        source = new FakeSource();
        close = new DayCloseService(store, businessDays, new SubjectPseudonym(new StubProtection()), clock, source);
        queries = new ReportQueryService(store, businessDays, clock);

        new MetricDefinitionSynchronizer(store).synchronizeAll();
        seedTenancy();
    }

    @Test
    @DisplayName("day close writes one fact row per redemption the port hands it, with the customer as a"
            + " pseudonym and the coupon's id but never its word")
    void closeWritesOneFactRowPerRedemptionWithAPseudonymousCustomer() {
        UUID automaticOrder = insertOrder("AUTO", 100_000, "COMPLETED", CUSTOMER_A);
        UUID couponOrder = insertOrder("COUPON", 80_000, "COMPLETED", CUSTOMER_B);
        source.add(redemption(automaticOrder, Kind.AUTOMATIC, null, CUSTOMER_A, 10_000, 0));
        source.add(redemption(couponOrder, Kind.COUPON, COUPON, CUSTOMER_B, 5_000, 0));

        close.close(TENANT, DAY);

        List<Map<String, Object>> rows =
                jdbc.sql("""
                SELECT source_kind, coupon_id, customer_subject_hash, discount_minor, order_id,
                       promotion_code, definition_version, business_date
                  FROM reporting.fact_promotion_redemption
                 WHERE tenant_id = :t
                 ORDER BY source_kind
                """).param("t", TENANT).query().listOfRows();

        assertThat(rows).hasSize(2);
        Map<String, Object> automatic = rows.get(0);
        assertThat(automatic.get("source_kind")).isEqualTo("AUTOMATIC");
        assertThat(automatic.get("coupon_id")).isNull();
        assertThat(automatic.get("order_id")).isEqualTo(automaticOrder);
        assertThat(String.valueOf(automatic.get("discount_minor"))).isEqualTo("10000");
        assertThat(String.valueOf(automatic.get("business_date"))).isEqualTo(DAY.toString());
        assertThat(automatic.get("definition_version")).isEqualTo(3);

        Map<String, Object> coupon = rows.get(1);
        assertThat(coupon.get("source_kind")).isEqualTo("COUPON");
        assertThat(coupon.get("coupon_id")).isEqualTo(COUPON);

        assertThat(rows)
                .as("the customer is the keyed ADR 0029 pseudonym, never the account id")
                .allSatisfy(row -> {
                    assertThat(String.valueOf(row.get("customer_subject_hash")))
                            .doesNotContain(CUSTOMER_A.toString())
                            .doesNotContain(CUSTOMER_B.toString());
                    assertThat(row.get("customer_subject_hash")).isNotNull();
                });
        assertThat(automatic.get("customer_subject_hash"))
                .as("the pseudonym is the same function reporting applies to every other fact")
                .isEqualTo(new SubjectPseudonym(new StubProtection()).of(TENANT, CUSTOMER_A));
    }

    @Test
    @DisplayName("closing the same day again replaces the rows instead of doubling them")
    void closingTheSameDayTwiceIsIdempotent() {
        UUID order = insertOrder("TWICE", 100_000, "COMPLETED", CUSTOMER_A);
        source.add(redemption(order, Kind.AUTOMATIC, null, CUSTOMER_A, 10_000, 0));

        close.close(TENANT, DAY);
        close.close(TENANT, DAY);

        Long count = jdbc.sql("SELECT count(*) FROM reporting.fact_promotion_redemption WHERE tenant_id = :t")
                .param("t", TENANT)
                .query(Long.class)
                .single();
        assertThat(count).isEqualTo(1L);
    }

    @Test
    @DisplayName(
            "a boundary change that moves a redemption to the neighbouring day moves its fact, and the close does not abort")
    void aBoundaryChangeMovesTheFactInsteadOfViolatingItsKey() {
        UUID order = insertOrder("SEAM", 100_000, "COMPLETED", CUSTOMER_A);
        source.add(redemption(order, Kind.AUTOMATIC, null, CUSTOMER_A, 10_000, 0));
        close.close(TENANT, DAY);
        assertThat(factDates()).containsExactly(DAY);

        // The tenant moves its business day to start at 14:00: the redemption at 13:01 now belongs to
        // the business day that began at 14:00 the day before.
        new BusinessDayService(store)
                .setBoundary(TENANT, new BusinessDayBoundary(TASHKENT, LocalTime.of(14, 0), 2), DAY, null);

        org.assertj.core.api.Assertions.assertThatCode(() -> close.close(TENANT, DAY.minusDays(1)))
                .as("the same redemption id re-inserted under the previous day used to violate its primary key")
                .doesNotThrowAnyException();

        assertThat(factDates())
                .as("one row for the redemption, on the day it now belongs to")
                .containsExactly(DAY.minusDays(1));
    }

    @Test
    @DisplayName("a recut reports a promotion fact that changed after the day closed, and leaves the stored rows alone")
    void aRecutReportsAPromotionFactThatChangedAfterTheClose() {
        UUID first = insertOrder("RC1", 100_000, "COMPLETED", CUSTOMER_A);
        UUID second = insertOrder("RC2", 80_000, "COMPLETED", CUSTOMER_B);
        source.add(redemption(first, Kind.AUTOMATIC, null, CUSTOMER_A, 10_000, 0));
        close.close(TENANT, DAY);

        // After the close an amendment restated the first redemption, and the second order's
        // promotion was recorded late.
        source.clear();
        source.add(redemption(first, Kind.AUTOMATIC, null, CUSTOMER_A, 7_000, 0));
        source.add(redemption(second, Kind.AUTOMATIC, null, CUSTOMER_B, 2_000, 0));

        var result = close.recut(TENANT, DAY);

        assertThat(result.divergences())
                .extracting(DayCloseService.Divergence::metricName)
                .containsExactlyInAnyOrder("promotion.redemptions", "promotion.discount");
        assertThat(result.divergences())
                .filteredOn(d -> d.metricName().equals("promotion.redemptions"))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.storedValue()).isEqualTo(1L);
                    assertThat(d.recutValue()).isEqualTo(2L);
                });
        assertThat(result.divergences())
                .filteredOn(d -> d.metricName().equals("promotion.discount"))
                .singleElement()
                .satisfies(d -> {
                    assertThat(d.storedValue()).isEqualTo(10_000L);
                    assertThat(d.recutValue()).isEqualTo(9_000L);
                });
        assertThat(jdbc.sql("SELECT count(*) FROM reporting.aggregate_divergences WHERE tenant_id = :t")
                        .param("t", TENANT)
                        .query(Long.class)
                        .single())
                .as("recorded for a person to decide, not applied")
                .isEqualTo(2L);
        assertThat(jdbc.sql("SELECT sum(discount_minor) FROM reporting.fact_promotion_redemption WHERE tenant_id = :t")
                        .param("t", TENANT)
                        .query(Long.class)
                        .single())
                .as("the stored fact is still what the day was closed with")
                .isEqualTo(10_000L);
    }

    @Test
    @DisplayName("a recut of a day whose promotion facts have not moved reports nothing")
    void aRecutOfUnchangedPromotionFactsIsClean() {
        UUID order = insertOrder("RC3", 100_000, "COMPLETED", CUSTOMER_A);
        source.add(redemption(order, Kind.AUTOMATIC, null, CUSTOMER_A, 10_000, 0));
        close.close(TENANT, DAY);

        assertThat(close.recut(TENANT, DAY).divergences()).isEmpty();
    }

    @Test
    @DisplayName("the summary counts redemptions, unique customers and discount, and leaves a cancelled order"
            + " out while the log keeps it with its status")
    void theSummaryExcludesCancelledOrdersAndTheLogKeepsThem() {
        UUID first = insertOrder("S1", 100_000, "COMPLETED", CUSTOMER_A);
        UUID second = insertOrder("S2", 60_000, "COMPLETED", CUSTOMER_A);
        UUID cancelled = insertOrder("S3", 500_000, "CANCELLED", CUSTOMER_B);
        source.add(redemption(first, Kind.AUTOMATIC, null, CUSTOMER_A, 10_000, 0));
        source.add(redemption(second, Kind.AUTOMATIC, null, CUSTOMER_A, 6_000, 0));
        source.add(redemption(cancelled, Kind.AUTOMATIC, null, CUSTOMER_B, 50_000, 0));

        close.close(TENANT, DAY);

        var summary = queries.promotionSummary(TENANT, DAY, DAY, null);
        assertThat(summary.rows()).hasSize(1);
        var row = summary.rows().getFirst();
        assertThat(row.promotionId()).isEqualTo(PROMOTION);
        assertThat(row.redemptions())
                .as("the cancelled order's redemption is not spend anyone incurred")
                .isEqualTo(2L);
        assertThat(row.uniqueCustomers()).isEqualTo(1L);
        assertThat(row.discountMinor()).isEqualTo(16_000L);
        assertThat(row.averageCheckWith()).isEqualTo(80_000L);

        var log = queries.promotionRedemptions(TENANT, DAY, DAY, null, 100);
        assertThat(log.rows())
                .as("the log keeps every redemption so a count that excludes cancelled orders can be reproduced")
                .hasSize(3);
        assertThat(log.rows())
                .filteredOn(r -> r.orderId().equals(cancelled))
                .singleElement()
                .satisfies(r -> {
                    assertThat(r.orderStatus()).isEqualTo("CANCELLED");
                    assertThat(r.channelCode())
                            .as("report 7.9 names the channel of each redemption")
                            .isEqualTo("TELEGRAM");
                });
    }

    @Test
    @DisplayName("the average check without the promotion is the brand's other completed orders, and null when"
            + " there are none to average")
    void theAverageCheckWithoutIsTheBrandsOtherCompletedOrders() {
        UUID carrying = insertOrder("W1", 90_000, "COMPLETED", CUSTOMER_A);
        insertOrder("W2", 200_000, "COMPLETED", CUSTOMER_B);
        insertOrder("W3", 100_000, "COMPLETED", CUSTOMER_B);
        source.add(redemption(carrying, Kind.AUTOMATIC, null, CUSTOMER_A, 10_000, 0));

        close.close(TENANT, DAY);

        var row = queries.promotionSummary(TENANT, DAY, DAY, null).rows().getFirst();
        assertThat(row.averageCheckWith()).isEqualTo(90_000L);
        assertThat(row.averageCheckWithout())
                .as("(200000 + 100000) / 2 -- the order that carried the promotion is not in its own comparison")
                .isEqualTo(150_000L);
    }

    @Test
    @DisplayName("a day with only promotion orders has no 'without' average to report")
    void withoutIsNullWhenEveryCompletedOrderCarriedThePromotion() {
        UUID only = insertOrder("N1", 90_000, "COMPLETED", CUSTOMER_A);
        source.add(redemption(only, Kind.AUTOMATIC, null, CUSTOMER_A, 10_000, 0));

        close.close(TENANT, DAY);

        var row = queries.promotionSummary(TENANT, DAY, DAY, null).rows().getFirst();
        assertThat(row.averageCheckWithout()).isNull();
    }

    @Test
    @DisplayName("another tenant's redemption facts never appear in this tenant's summary or log")
    void anotherTenantsFactsAreInvisible() {
        UUID order = insertOrder("T1", 100_000, "COMPLETED", CUSTOMER_A);
        source.add(redemption(order, Kind.AUTOMATIC, null, CUSTOMER_A, 10_000, 0));
        close.close(TENANT, DAY);

        store.insertPromotionRedemptionFact(new ReportingFacts.PromotionRedemptionFact(
                OTHER_TENANT,
                UUID.randomUUID(),
                DAY,
                1,
                1,
                BRAND,
                PROMOTION,
                "OTHER",
                1,
                "AUTOMATIC",
                null,
                UUID.randomUUID(),
                "pseudonym",
                999_999,
                0,
                "UZS",
                Instant.parse("2026-09-01T08:00:00Z")));

        assertThat(queries.promotionRedemptions(TENANT, DAY, DAY, null, 100).rows())
                .hasSize(1)
                .allSatisfy(r -> assertThat(r.promotionCode()).isNotEqualTo("OTHER"));
        assertThat(queries.promotionRedemptions(OTHER_TENANT, DAY, DAY, null, 100)
                        .rows())
                .hasSize(1)
                .allSatisfy(r -> assertThat(r.discountMinor()).isEqualTo(999_999L));
    }

    @Test
    @DisplayName("a close with no pricing source wired builds no promotion facts and still closes the day")
    void aCloseWithoutASourceStillCloses() {
        insertOrder("NOSRC", 100_000, "COMPLETED", CUSTOMER_A);
        var bare = new DayCloseService(
                store,
                new BusinessDayService(store),
                new SubjectPseudonym(new StubProtection()),
                Clock.fixed(Instant.parse("2026-09-02T04:00:00Z"), ZoneOffset.UTC));

        bare.close(TENANT, DAY);

        Long count = jdbc.sql("SELECT count(*) FROM reporting.fact_promotion_redemption")
                .query(Long.class)
                .single();
        assertThat(count).isZero();
        Long orders = jdbc.sql("SELECT count(*) FROM reporting.fact_order WHERE tenant_id = :t")
                .param("t", TENANT)
                .query(Long.class)
                .single();
        assertThat(orders).isEqualTo(1L);
    }

    private List<LocalDate> factDates() {
        return jdbc.sql("SELECT business_date FROM reporting.fact_promotion_redemption WHERE tenant_id = :t"
                        + " ORDER BY business_date")
                .param("t", TENANT)
                .query(LocalDate.class)
                .list();
    }

    // ---------------------------------------------------------------- setup

    private Redemption redemption(
            UUID orderId, Kind kind, @Nullable UUID couponId, UUID customer, long discountMinor, long markupMinor) {
        return new Redemption(
                UUID.nameUUIDFromBytes(("redemption:" + orderId + ":" + kind).getBytes(StandardCharsets.UTF_8)),
                BRAND,
                PROMOTION,
                "LUNCH10",
                3,
                kind,
                couponId,
                orderId,
                customer,
                discountMinor,
                markupMinor,
                "UZS",
                tashkent(13, 1));
    }

    private static final class FakeSource implements PromotionRedemptionSource {

        private final List<Redemption> all = new ArrayList<>();

        void add(Redemption redemption) {
            all.add(redemption);
        }

        void clear() {
            all.clear();
        }

        @Override
        public List<Redemption> redeemedBetween(UUID tenantId, Instant fromInclusive, Instant toExclusive) {
            return all.stream()
                    .filter(r -> !r.redeemedAt().isBefore(fromInclusive)
                            && r.redeemedAt().isBefore(toExclusive))
                    .toList();
        }
    }

    private UUID insertOrder(String seed, long totalMinor, String status, UUID customer) {
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
                .param("cust", customer)
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
                .param("cust", customer)
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
                    :status, 'UZS', :total, 0, 0, 0,
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
                .param("cust", customer)
                .param("status", status)
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

        return orderId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'promo-fact-tenant', 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent',
                    'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'promo-fact-other', 'Legal', 'Other', 'UZS', 'Asia/Tashkent',
                    'ACTIVE', 0)
                """).param("id", OTHER_TENANT).update();
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
        for (UUID customer : List.of(CUSTOMER_A, CUSTOMER_B)) {
            jdbc.sql("""
                    INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                        identity_policy_version, version)
                    VALUES (:id, :t, 'ACTIVE', 'Customer', 1, 1)
                    """).param("id", customer).param("t", TENANT).update();
        }

        channelId = UUID.nameUUIDFromBytes("promo-fact-channel".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :t, 'TELEGRAM', 'TELEGRAM', 'Telegram bot', 'ACTIVE', false)
                """).param("id", channelId).param("t", TENANT).update();

        UUID catalogId = UUID.nameUUIDFromBytes("promo-fact-catalog".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();

        publicationId = UUID.nameUUIDFromBytes("promo-fact-publication".getBytes(StandardCharsets.UTF_8));
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

    private static Instant tashkent(int hour, int minute) {
        return ZonedDateTime.of(DAY, LocalTime.of(hour, minute), TASHKENT).toInstant();
    }

    private static UUID orderId(String seed) {
        return UUID.nameUUIDFromBytes(("promo-fact-order:" + seed).getBytes(StandardCharsets.UTF_8));
    }

    /** The same deterministic stand-in for the ADR 0029 keyed hash the sibling fact tests use. */
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
