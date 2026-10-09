package uz.horecaos.platform.reporting.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcCustomerRegistrationSource;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.ProtectedValue;
import uz.horecaos.platform.ordering.application.CartRetentionSweeper;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartFunnelSource;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcFunnelStore;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0167 (row 7.6c): the customer funnel's daily counts, captured by the day close from the live
 * carts, checkout attempts, brand profiles and orders.
 *
 * <p>Every case goes through {@link DayCloseService#close}, the production path, with the real
 * ordering and customers ports over a real database and carts written the way checkout writes them.
 * The clock is a duration the test lives through: the retention cliff is asserted by moving it past
 * ninety days, not by typing a date that happens to be old.
 */
class CustomerFunnelCaptureTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-2000-7000-8000-0000000f6701");
    private static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-2000-7000-8000-0000000f6702");
    private static final UUID BRAND = UUID.fromString("018f6f4e-2000-7000-8000-0000000f6703");
    private static final UUID OTHER_BRAND = UUID.fromString("018f6f4e-2000-7000-8000-0000000f6704");
    private static final UUID LOCATION = UUID.fromString("018f6f4e-2000-7000-8000-0000000f6705");
    private static final UUID ACCOUNT_SELF = UUID.fromString("018f6f4e-2000-7000-8000-0000000f6706");
    private static final UUID ACCOUNT_OPERATOR = UUID.fromString("018f6f4e-2000-7000-8000-0000000f6707");
    private static final UUID ACCOUNT_IMPORT = UUID.fromString("018f6f4e-2000-7000-8000-0000000f6708");
    private static final UUID ACCOUNT_YESTERDAY = UUID.fromString("018f6f4e-2000-7000-8000-0000000f6709");

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");
    private static final LocalDate DAY = LocalDate.of(2026, 9, 1);
    private static final Instant FIRST_CLOSE_INSTANT = Instant.parse("2026-09-02T04:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcReportingStore store;
    private JdbcFunnelStore funnelStore;
    private BusinessDayService businessDays;
    private MovableClock clock;
    private FunnelCaptureService capture;
    private DayCloseService close;
    private UUID webChannel;
    private UUID telegramChannel;
    private UUID publicationId;
    private UUID variantId;
    private UUID secondLocation;
    private int seeds;

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
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE reporting.aggregate_divergences, reporting.close_runs")
                .update();
        jdbc.sql("""
                TRUNCATE TABLE reporting.fact_order, reporting.fact_order_line,
                    reporting.fact_order_tender, reporting.fact_refund, reporting.agg_branch_day,
                    reporting.agg_sla_bucket_day, reporting.business_day_policies,
                    reporting.metric_definitions, reporting.fact_call_hour,
                    reporting.fact_promotion_redemption, reporting.fact_funnel_day,
                    reporting.fact_registration_day, reporting.funnel_backfill_state
                """).update();
        jdbc.sql("TRUNCATE TABLE ordering.orders CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.checkout_attempts").update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.catalogs CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.products CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = new MovableClock(FIRST_CLOSE_INSTANT);
        store = new JdbcReportingStore(jdbc);
        funnelStore = new JdbcFunnelStore(jdbc);
        businessDays = new BusinessDayService(store);
        capture = new FunnelCaptureService(
                funnelStore,
                new JdbcCartFunnelSource(
                        jdbc, new CartRetentionSweeper(new JdbcCartStore(jdbc), jdbc, clock, 90, 500), clock),
                new JdbcCustomerRegistrationSource(jdbc),
                clock);
        close = new DayCloseService(
                store, businessDays, new SubjectPseudonym(new StubProtection()), clock, null, capture);

        new MetricDefinitionSynchronizer(store).synchronizeAll();
        seedTenancy();
    }

    // ------------------------------------------------------------------ baskets and checkouts

    @Test
    @DisplayName("a cart is one basket however many lines it has, a guest and an account cart count alike,"
            + " and a retried checkout is one cart reaching checkout")
    void aCartCountsOnceHoweverManyLinesAndAttempts() {
        UUID accountCart = insertCart(
                webChannel,
                ACCOUNT_SELF,
                null,
                "ACTIVE",
                tashkent(DAY, 12, 0),
                tashkent(DAY, 12, 5),
                tashkent(DAY, 12, 9));
        UUID guestCart = insertCart(webChannel, null, "guest-hash-a", "ACTIVE", tashkent(DAY, 13, 0));
        insertCart(webChannel, ACCOUNT_SELF, null, "ACTIVE", tashkent(DAY, 14, 0));
        insertCheckoutAttempt(accountCart, "FAILED_PAYMENT", tashkent(DAY, 12, 20));
        insertCheckoutAttempt(accountCart, "OK", tashkent(DAY, 12, 25));
        insertCheckoutAttempt(guestCart, "OK", tashkent(DAY, 13, 5));

        close.close(TENANT, DAY);

        Map<String, Object> row = funnelRow(DAY, BRAND, "WEB");
        assertThat(row.get("basket_started"))
                .as("three carts, one of them with three lines: three baskets, not five")
                .isEqualTo(3);
        assertThat(row.get("checkout_started"))
                .as("two carts reached checkout, one of them twice")
                .isEqualTo(2);
        assertThat(row.get("ordered")).as("no order was placed").isEqualTo(0);
    }

    @Test
    @DisplayName("a cart is a basket on the day of its first line and an order on the day it became one:"
            + " converted the next morning it is counted in both stages, on two different days")
    void aCartConvertedTheNextMorningIsABasketOnDayOneAndAnOrderOnDayTwo() {
        UUID cart = insertCart(webChannel, ACCOUNT_SELF, null, "CONVERTED", tashkent(DAY, 22, 0));
        insertCheckoutAttempt(cart, "OK", tashkent(DAY.plusDays(1), 9, 0));
        insertOrder("NEXT-MORNING", cart, webChannel, "WEB", ACCOUNT_SELF, tashkent(DAY.plusDays(1), 9, 0));

        close.close(TENANT, DAY);
        close.close(TENANT, DAY.plusDays(1));

        Map<String, Object> dayOne = funnelRow(DAY, BRAND, "WEB");
        assertThat(dayOne.get("basket_started")).isEqualTo(1);
        assertThat(dayOne.get("checkout_started")).isEqualTo(0);
        assertThat(dayOne.get("ordered")).isEqualTo(0);

        Map<String, Object> dayTwo = funnelRow(DAY.plusDays(1), BRAND, "WEB");
        assertThat(dayTwo.get("basket_started"))
                .as("its first line was added on day one, so day two does not count it again")
                .isEqualTo(0);
        assertThat(dayTwo.get("checkout_started")).isEqualTo(1);
        assertThat(dayTwo.get("ordered")).isEqualTo(1);
    }

    @Test
    @DisplayName("the counts are per brand and per channel, and another tenant's carts are never counted")
    void countsAreSplitByBrandAndChannelAndStayInsideTheTenant() {
        insertCart(webChannel, ACCOUNT_SELF, null, "ACTIVE", tashkent(DAY, 10, 0));
        insertCart(telegramChannel, null, "guest-hash-b", "ACTIVE", tashkent(DAY, 10, 30));
        insertCartForBrand(OTHER_BRAND, webChannel, ACCOUNT_SELF, null, "ACTIVE", tashkent(DAY, 11, 0));
        insertOtherTenantCart(tashkent(DAY, 10, 0));

        close.close(TENANT, DAY);

        assertThat(funnelRow(DAY, BRAND, "WEB").get("basket_started")).isEqualTo(1);
        assertThat(funnelRow(DAY, BRAND, "TELEGRAM").get("basket_started")).isEqualTo(1);
        assertThat(funnelRow(DAY, OTHER_BRAND, "WEB").get("basket_started")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM reporting.fact_funnel_day WHERE tenant_id = :t")
                        .param("t", OTHER_TENANT)
                        .query(Long.class)
                        .single())
                .as("closing this tenant's day wrote nothing for the other tenant")
                .isZero();
    }

    // ------------------------------------------------------------------ the retention cliff

    @Test
    @DisplayName("a day whose carts the sweeper has already deleted is 'not recorded' (NULL), never zero,"
            + " while the orders it still holds are counted")
    void aDayPastRetentionIsNullAndNotZero() {
        UUID cart = insertCart(webChannel, ACCOUNT_SELF, null, "CONVERTED", tashkent(DAY, 12, 0));
        insertCheckoutAttempt(cart, "OK", tashkent(DAY, 12, 10));
        insertOrder("SURVIVOR", cart, webChannel, "WEB", ACCOUNT_SELF, tashkent(DAY, 12, 10));
        insertCart(webChannel, null, "swept-guest", "ABANDONED", tashkent(DAY, 15, 0));

        clock.advance(Duration.ofDays(100));
        close.close(TENANT, DAY);

        Map<String, Object> row = funnelRow(DAY, BRAND, "WEB");
        assertThat(row.get("basket_started"))
                .as("the abandoned cart is past retention, so a basket count would understate: not recorded")
                .isNull();
        assertThat(row.get("checkout_started")).isNull();
        assertThat(row.get("ordered"))
                .as("orders keep their own facts and are still counted")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("a count already recorded is left alone when the day is closed again after the carts are swept")
    void aRecordedCountSurvivesAReCloseAfterTheSweep() {
        insertCart(webChannel, ACCOUNT_SELF, null, "ACTIVE", tashkent(DAY, 12, 0));
        insertCart(webChannel, null, "guest-hash-c", "ACTIVE", tashkent(DAY, 13, 0));
        // One basket became an order. The order keeps the day's row alive after the sweep, which is what
        // makes this a test of the count and not of whether anything is written at all: a day whose
        // carts are all swept and which has no order writes no row, and so could never overwrite one.
        UUID converted = insertCart(webChannel, ACCOUNT_SELF, null, "CONVERTED", tashkent(DAY, 14, 0));
        insertOrder("SURVIVES", converted, webChannel, "WEB", ACCOUNT_SELF, tashkent(DAY, 14, 10));
        close.close(TENANT, DAY);
        assertThat(funnelRow(DAY, BRAND, "WEB").get("basket_started")).isEqualTo(3);

        clock.advance(Duration.ofDays(100));
        new CartRetentionSweeper(new JdbcCartStore(jdbc), jdbc, clock, 90, 500).runOnce();
        assertThat(jdbc.sql("SELECT count(*) FROM ordering.carts WHERE status <> 'CONVERTED'")
                        .query(Long.class)
                        .single())
                .as("the sweep really deleted the abandoned carts this test is about")
                .isZero();
        close.close(TENANT, DAY);

        assertThat(funnelRow(DAY, BRAND, "WEB").get("basket_started"))
                .as("recomputing a day whose carts are gone must not turn yesterday's good number into 'not"
                        + " recorded' or into zero")
                .isEqualTo(3);
    }

    @Test
    @DisplayName("a close with no cart source wired records 'not recorded', because an unknown is not a zero")
    void aCloseWithNoCartSourceRecordsNothingAboutCarts() {
        UUID cart = insertCart(webChannel, ACCOUNT_SELF, null, "CONVERTED", tashkent(DAY, 12, 0));
        insertOrder("NOSOURCE", cart, webChannel, "WEB", ACCOUNT_SELF, tashkent(DAY, 12, 10));
        FunnelCaptureService bare = new FunnelCaptureService(funnelStore, null, null, clock);
        DayCloseService bareClose =
                new DayCloseService(store, businessDays, new SubjectPseudonym(new StubProtection()), clock, null, bare);

        bareClose.close(TENANT, DAY);

        Map<String, Object> row = funnelRow(DAY, BRAND, "WEB");
        assertThat(row.get("basket_started")).isNull();
        assertThat(row.get("checkout_started")).isNull();
        assertThat(row.get("ordered")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ registrations

    @Test
    @DisplayName(
            "registrations are counted per brand profile and by the origin of the account, never as a list of people")
    void registrationsAreCountedByBrandAndOrigin() {
        insertProfile(BRAND, ACCOUNT_SELF, tashkent(DAY, 9, 0));
        insertProfile(BRAND, ACCOUNT_OPERATOR, tashkent(DAY, 9, 30));
        insertProfile(BRAND, ACCOUNT_IMPORT, tashkent(DAY, 10, 0));
        insertProfile(OTHER_BRAND, ACCOUNT_SELF, tashkent(DAY, 11, 0));
        insertProfile(BRAND, ACCOUNT_YESTERDAY, tashkent(DAY.minusDays(1), 23, 59)); // yesterday: another day's count

        close.close(TENANT, DAY);

        List<Map<String, Object>> rows =
                jdbc.sql("""
                SELECT brand_id, origin, registered FROM reporting.fact_registration_day
                 WHERE tenant_id = :t AND business_date = :d ORDER BY brand_id, origin
                """).param("t", TENANT).param("d", DAY).query().listOfRows();

        assertThat(rows).hasSize(4);
        assertThat(registered(rows, BRAND, "SELF_SERVICE")).isEqualTo(1);
        assertThat(registered(rows, BRAND, "OPERATOR")).isEqualTo(1);
        assertThat(registered(rows, BRAND, "IMPORT")).isEqualTo(1);
        assertThat(registered(rows, OTHER_BRAND, "SELF_SERVICE"))
                .as("one person joining a second brand is a registration for that brand")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("closing the same day twice replaces the rows instead of doubling them")
    void closingTwiceIsIdempotent() {
        insertCart(webChannel, ACCOUNT_SELF, null, "ACTIVE", tashkent(DAY, 12, 0));
        insertProfile(BRAND, ACCOUNT_SELF, tashkent(DAY, 9, 0));

        close.close(TENANT, DAY);
        close.close(TENANT, DAY);

        assertThat(funnelRow(DAY, BRAND, "WEB").get("basket_started")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT sum(registered) FROM reporting.fact_registration_day WHERE tenant_id = :t")
                        .param("t", TENANT)
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    // ------------------------------------------------------------------ backfill

    @Test
    @DisplayName("the backfill walks days closed before the capture existed once, and marks the tenant done")
    void theBackfillWalksClosedDaysOnce() {
        UUID cart = insertCart(webChannel, ACCOUNT_SELF, null, "CONVERTED", tashkent(DAY, 12, 0));
        insertCheckoutAttempt(cart, "OK", tashkent(DAY, 12, 10));
        insertOrder("OLD-DAY", cart, webChannel, "WEB", ACCOUNT_SELF, tashkent(DAY, 12, 10));
        insertProfile(BRAND, ACCOUNT_SELF, tashkent(DAY, 9, 0));
        // A close from before the capture existed: no funnel capture wired.
        DayCloseService oldClose =
                new DayCloseService(store, businessDays, new SubjectPseudonym(new StubProtection()), clock);
        oldClose.close(TENANT, DAY);
        assertThat(jdbc.sql("SELECT count(*) FROM reporting.fact_funnel_day")
                        .query(Long.class)
                        .single())
                .isZero();

        FunnelBackfillService backfill = new FunnelBackfillService(funnelStore, capture, businessDays, clock);

        assertThat(backfill.backfill(TENANT)).isEqualTo(1);
        Map<String, Object> row = funnelRow(DAY, BRAND, "WEB");
        assertThat(row.get("basket_started")).isEqualTo(1);
        assertThat(row.get("checkout_started")).isEqualTo(1);
        assertThat(row.get("ordered")).isEqualTo(1);
        assertThat(registered(
                        jdbc.sql("SELECT brand_id, origin, registered FROM reporting.fact_registration_day")
                                .query()
                                .listOfRows(),
                        BRAND,
                        "SELF_SERVICE"))
                .isEqualTo(1);
        assertThat(backfill.backfill(TENANT)).as("a tenant is walked once").isZero();
    }

    @Test
    @DisplayName(
            "a day older than the cart retention window backfills with NULL baskets and checkouts and the orders it still holds")
    void theBackfillMarksDaysPastRetentionAsNotRecorded() {
        UUID cart = insertCart(webChannel, ACCOUNT_SELF, null, "CONVERTED", tashkent(DAY, 12, 0));
        insertOrder("ANCIENT", cart, webChannel, "WEB", ACCOUNT_SELF, tashkent(DAY, 12, 10));
        new DayCloseService(store, businessDays, new SubjectPseudonym(new StubProtection()), clock).close(TENANT, DAY);

        clock.advance(Duration.ofDays(200));
        new FunnelBackfillService(funnelStore, capture, businessDays, clock).backfill(TENANT);

        Map<String, Object> row = funnelRow(DAY, BRAND, "WEB");
        assertThat(row.get("basket_started")).isNull();
        assertThat(row.get("checkout_started")).isNull();
        assertThat(row.get("ordered")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ health

    @Test
    @DisplayName(
            "the health probe flags a closed day that holds orders and has no funnel row, and the backfill clears it")
    void theHealthProbeFlagsAClosedDayWithOrdersAndNoFunnelRow() {
        UUID cart = insertCart(webChannel, ACCOUNT_SELF, null, "CONVERTED", tashkent(DAY, 12, 0));
        insertOrder("PROBE", cart, webChannel, "WEB", ACCOUNT_SELF, tashkent(DAY, 12, 10));
        new DayCloseService(store, businessDays, new SubjectPseudonym(new StubProtection()), clock).close(TENANT, DAY);
        clock.advance(Duration.ofDays(10));
        FunnelHealthProbe probe = new FunnelHealthProbe(
                funnelStore, clock, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        assertThat(probe.probe().daysWithoutRow())
                .as("a closed day with an order and no funnel row is the loss that cannot be repaired")
                .isEqualTo(1);

        new FunnelBackfillService(funnelStore, capture, businessDays, clock).backfill(TENANT);

        assertThat(probe.probe().daysWithoutRow()).isZero();
        assertThat(probe.probe().unrecordedDays())
                .as("the day is within cart retention, so its baskets are recorded and nothing is a gap")
                .isZero();
    }

    @Test
    @DisplayName(
            "the health probe counts a row older than a week whose baskets were never recorded, and ignores a fresh one")
    void theHealthProbeCountsOldGapsOnly() {
        UUID cart = insertCart(webChannel, ACCOUNT_SELF, null, "CONVERTED", tashkent(DAY, 12, 0));
        insertOrder("GAP", cart, webChannel, "WEB", ACCOUNT_SELF, tashkent(DAY, 12, 10));
        clock.advance(Duration.ofDays(100));
        close.close(TENANT, DAY); // past retention: baskets NULL
        FunnelHealthProbe probe = new FunnelHealthProbe(
                funnelStore, clock, new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        // The probe looks back sixty days; bring "today" to within that of the gap.
        clock.set(tashkent(DAY.plusDays(40), 12, 0));

        assertThat(probe.probe().unrecordedDays()).isEqualTo(1);

        clock.set(tashkent(DAY.plusDays(3), 12, 0));
        assertThat(probe.probe().unrecordedDays())
                .as("three days after the day is not yet a lost day: a late close may still catch up")
                .isZero();
    }

    // ------------------------------------------------------------------ helpers

    private Map<String, Object> funnelRow(LocalDate date, UUID brand, String channel) {
        List<Map<String, Object>> rows = jdbc.sql("""
                SELECT basket_started, checkout_started, ordered FROM reporting.fact_funnel_day
                 WHERE tenant_id = :t AND business_date = :d AND brand_id = :b AND channel_code = :c
                """)
                .param("t", TENANT)
                .param("d", date)
                .param("b", brand)
                .param("c", channel)
                .query()
                .listOfRows();
        assertThat(rows).as("a funnel row for %s %s %s", date, brand, channel).hasSize(1);
        return rows.getFirst();
    }

    private static int registered(List<Map<String, Object>> rows, UUID brand, String origin) {
        int total = 0;
        for (Map<String, Object> row : rows) {
            if (brand.equals(row.get("brand_id")) && origin.equals(row.get("origin"))) {
                total += ((Number) java.util.Objects.requireNonNull(row.get("registered"))).intValue();
            }
        }
        return total;
    }

    private static Instant tashkent(LocalDate day, int hour, int minute) {
        return ZonedDateTime.of(day, LocalTime.of(hour, minute), TASHKENT).toInstant();
    }

    private UUID insertCart(
            UUID channel, @Nullable UUID account, @Nullable String guestHash, String status, Instant... lineTimes) {
        return insertCartForBrand(BRAND, channel, account, guestHash, status, lineTimes);
    }

    private UUID insertCartForBrand(
            UUID brand,
            UUID channel,
            @Nullable UUID account,
            @Nullable String guestHash,
            String status,
            Instant... lineTimes) {
        UUID cartId = UUID.nameUUIDFromBytes(("funnel-cart:" + (seeds++)).getBytes(StandardCharsets.UTF_8));
        Instant first = lineTimes[0];
        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    customer_account_id, guest_reference_hash, fulfillment_mode, currency, status,
                    expires_at, converted_order_id, created_at, updated_at)
                VALUES (:id, :t, :b, :loc, :ch, :acct, :guest, 'PICKUP', 'UZS', :status,
                    :expires, :orderId, :at, :at)
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", brand)
                .param("loc", brand.equals(BRAND) ? LOCATION : secondLocation)
                .param("ch", channel)
                .param("acct", account)
                .param("guest", guestHash)
                .param("status", status)
                .param("expires", first.plus(Duration.ofHours(4)).atOffset(ZoneOffset.UTC))
                .param(
                        "orderId",
                        "CONVERTED".equals(status)
                                ? UUID.nameUUIDFromBytes(("o:" + cartId).getBytes(StandardCharsets.UTF_8))
                                : null)
                .param("at", first.atOffset(ZoneOffset.UTC))
                .update();
        int line = 0;
        for (Instant at : lineTimes) {
            jdbc.sql("""
                    INSERT INTO ordering.cart_lines (id, tenant_id, cart_id, line_key, variant_id, quantity,
                        created_at, updated_at)
                    VALUES (:id, :t, :cart, :key, :variant, 1, :at, :at)
                    """)
                    .param(
                            "id",
                            UUID.nameUUIDFromBytes(("line:" + cartId + ":" + line).getBytes(StandardCharsets.UTF_8)))
                    .param("t", TENANT)
                    .param("cart", cartId)
                    .param("key", "L" + (line++))
                    .param("variant", variantId)
                    .param("at", at.atOffset(ZoneOffset.UTC))
                    .update();
        }
        return cartId;
    }

    private void insertOtherTenantCart(Instant at) {
        UUID channel = UUID.nameUUIDFromBytes("other-tenant-channel".getBytes(StandardCharsets.UTF_8));
        UUID location = UUID.nameUUIDFromBytes("other-tenant-location".getBytes(StandardCharsets.UTF_8));
        UUID otherBrand = UUID.nameUUIDFromBytes("other-tenant-brand".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", otherBrand).param("t", OTHER_TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CHI', 'chilonzor', 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", location)
                .param("t", OTHER_TENANT)
                .param("b", otherBrand)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :t, 'WEB', 'WEB', 'Storefront', 'ACTIVE', true)
                """).param("id", channel).param("t", OTHER_TENANT).update();
        UUID cart = UUID.nameUUIDFromBytes("other-tenant-cart".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    guest_reference_hash, fulfillment_mode, currency, status, expires_at,
                    created_at, updated_at)
                VALUES (:id, :t, :b, :loc, :ch, 'other-guest', 'PICKUP', 'UZS', 'ACTIVE', :expires, :at, :at)
                """)
                .param("id", cart)
                .param("t", OTHER_TENANT)
                .param("b", otherBrand)
                .param("loc", location)
                .param("ch", channel)
                .param("expires", at.plus(Duration.ofHours(4)).atOffset(ZoneOffset.UTC))
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
        UUID product = UUID.nameUUIDFromBytes("other-tenant-product".getBytes(StandardCharsets.UTF_8));
        UUID variant = UUID.nameUUIDFromBytes("other-tenant-variant".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("INSERT INTO catalog.products (id, tenant_id, brand_id, code) VALUES (:id, :t, :b, 'SKU')")
                .param("id", product)
                .param("t", OTHER_TENANT)
                .param("b", otherBrand)
                .update();
        jdbc.sql("INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id) VALUES (:id, :t, :b, :p)")
                .param("id", variant)
                .param("t", OTHER_TENANT)
                .param("b", otherBrand)
                .param("p", product)
                .update();
        jdbc.sql("""
                INSERT INTO ordering.cart_lines (id, tenant_id, cart_id, line_key, variant_id, quantity,
                    created_at, updated_at)
                VALUES (:id, :t, :cart, 'L0', :variant, 1, :at, :at)
                """)
                .param("id", UUID.nameUUIDFromBytes("other-tenant-line".getBytes(StandardCharsets.UTF_8)))
                .param("t", OTHER_TENANT)
                .param("cart", cart)
                .param("variant", variant)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertCheckoutAttempt(UUID cart, String outcome, Instant at) {
        jdbc.sql("""
                INSERT INTO ordering.checkout_attempts (id, tenant_id, idempotency_key, cart_id, quote_id,
                    request_fingerprint, status, outcome_code, created_at, completed_at)
                VALUES (:id, :t, :key, :cart, :quote, 'fp', 'COMPLETED', :outcome, :at, :at)
                """)
                .param("id", UUID.nameUUIDFromBytes(("attempt:" + (seeds++)).getBytes(StandardCharsets.UTF_8)))
                .param("t", TENANT)
                .param("key", "attempt-" + (seeds++))
                .param("cart", cart)
                .param("quote", UUID.nameUUIDFromBytes(("attempt-quote:" + seeds).getBytes(StandardCharsets.UTF_8)))
                .param("outcome", outcome)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void insertProfile(UUID brand, UUID account, Instant at) {
        jdbc.sql("""
                INSERT INTO customer.brand_profiles (id, tenant_id, brand_id, customer_account_id, status,
                    created_at, updated_at)
                VALUES (:id, :t, :b, :acct, 'ACTIVE', :at, :at)
                """)
                .param("id", UUID.nameUUIDFromBytes(("profile:" + (seeds++)).getBytes(StandardCharsets.UTF_8)))
                .param("t", TENANT)
                .param("b", brand)
                .param("acct", account)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
    }

    /** An order placed from {@code cart}: the cart's quote and the order's own row, as checkout writes them. */
    private void insertOrder(
            String seed, UUID cart, UUID channel, String channelCode, @Nullable UUID customer, Instant createdAt) {
        UUID orderId = UUID.nameUUIDFromBytes(("funnel-order:" + seed).getBytes(StandardCharsets.UTF_8));
        UUID quoteId = UUID.nameUUIDFromBytes(("funnel-quote:" + seed).getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id,
                    customer_account_id, currency, status, catalog_publication_id,
                    calculation_version, context_hash, subtotal_minor, tax_minor, fee_minor,
                    discount_minor, total_minor, expires_at, accepted_at)
                VALUES (:id, :t, :b, :loc, :cust, 'UZS', 'ACCEPTED', :pub, 1, :hash,
                    50000, 0, 0, 0, 50000, :expires, :accepted)
                """)
                .param("id", quoteId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("cust", customer)
                .param("pub", publicationId)
                .param("hash", "hash-" + seed)
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
                VALUES (:id, :number, :t, :b, :loc, :ch, :code, :cust,
                    'PICKUP', 'AUTO_CONFIRM', 'NONE',
                    'COMPLETED', 'UZS', 50000, 0, 0, 0,
                    50000, :quote, :hash, :pub,
                    :cart, :key, 'NOT_PROMISED',
                    1, :createdAt, :confirmedAt, :closedAt)
                """)
                .param("id", orderId)
                .param("number", seed)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", LOCATION)
                .param("ch", channel)
                .param("code", channelCode)
                .param("cust", customer)
                .param("quote", quoteId)
                .param("hash", "hash-" + seed)
                .param("pub", publicationId)
                .param("cart", cart)
                .param("key", "idem-" + seed)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .param("confirmedAt", createdAt.plusSeconds(120).atOffset(ZoneOffset.UTC))
                .param("closedAt", createdAt.plusSeconds(1_800).atOffset(ZoneOffset.UTC))
                .update();
    }

    private void seedTenancy() {
        for (UUID tenant : List.of(TENANT, OTHER_TENANT)) {
            jdbc.sql("""
                    INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                        default_timezone, status, version)
                    VALUES (:id, :slug, 'Legal', 'Funnel', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                    """).param("id", tenant).param("slug", "funnel-" + tenant).update();
        }
        for (UUID brand : List.of(BRAND, OTHER_BRAND)) {
            jdbc.sql("""
                    INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                    VALUES (:id, :t, :code, :slug, 'Brand', 'ACTIVE', 0)
                    """)
                    .param("id", brand)
                    .param("t", TENANT)
                    .param("code", brand.equals(BRAND) ? "MAIN" : "SECOND")
                    .param("slug", brand.equals(BRAND) ? "main" : "second")
                    .update();
        }
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CHI', 'chilonzor', 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", LOCATION).param("t", TENANT).param("b", BRAND).update();
        secondLocation = UUID.nameUUIDFromBytes("funnel-second-location".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'SEC', 'second', 'Second', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", secondLocation)
                .param("t", TENANT)
                .param("b", OTHER_BRAND)
                .update();
        for (Object[] account : new Object[][] {
            {ACCOUNT_SELF, "SELF_SERVICE", null},
            {ACCOUNT_OPERATOR, "OPERATOR", "staff-subject"},
            {ACCOUNT_IMPORT, "IMPORT", null},
            {ACCOUNT_YESTERDAY, "SELF_SERVICE", null},
        }) {
            jdbc.sql("""
                    INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                        identity_policy_version, version, origin, created_by_actor_id)
                    VALUES (:id, :t, 'ACTIVE', 'Customer', 1, 1, :origin, :actor)
                    """)
                    .param("id", account[0])
                    .param("t", TENANT)
                    .param("origin", account[1])
                    .param("actor", account[2])
                    .update();
        }
        webChannel = UUID.nameUUIDFromBytes("funnel-web".getBytes(StandardCharsets.UTF_8));
        telegramChannel = UUID.nameUUIDFromBytes("funnel-telegram".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :t, 'WEB', 'WEB', 'Storefront', 'ACTIVE', true)
                """).param("id", webChannel).param("t", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :t, 'TELEGRAM', 'TELEGRAM', 'Telegram bot', 'ACTIVE', true)
                """).param("id", telegramChannel).param("t", TENANT).update();

        UUID catalogId = UUID.nameUUIDFromBytes("funnel-catalog".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();
        publicationId = UUID.nameUUIDFromBytes("funnel-publication".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("cat", catalogId)
                .update();

        UUID product = UUID.nameUUIDFromBytes("funnel-product".getBytes(StandardCharsets.UTF_8));
        variantId = UUID.nameUUIDFromBytes("funnel-variant".getBytes(StandardCharsets.UTF_8));
        jdbc.sql("INSERT INTO catalog.products (id, tenant_id, brand_id, code) VALUES (:id, :t, :b, 'SKU')")
                .param("id", product)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();
        jdbc.sql("INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id) VALUES (:id, :t, :b, :p)")
                .param("id", variantId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("p", product)
                .update();
    }

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

    private static final class MovableClock extends Clock {
        private Instant now;

        private MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
