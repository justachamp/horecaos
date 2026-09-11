package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
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
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.MixSliceRow;
import uz.horecaos.platform.reporting.application.BusinessDayService;
import uz.horecaos.platform.reporting.application.BusinessDayWindowsAdapter;
import uz.horecaos.platform.reporting.domain.BusinessDayBoundary;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * IA 0.1a, 0.1b and 0.1c against the migrated schema: the live board's counters
 * are cut to the tenant's own business day, its two mixes are exact, and its
 * branch leaderboard is one read.
 *
 * <p>The boundary under test is <strong>02:00</strong>, so every business day
 * here contains a midnight. That is the case the console could not get right and
 * the one a restaurant actually has: an order cancelled at 00:30 belongs to the
 * evening that is still being served, not to the date the wall calendar shows.
 * A cut at UTC midnight, or at local midnight, files it under the wrong day and
 * looks entirely healthy while doing so — every assertion below is written so
 * that either of those mistakes changes the number.
 *
 * <p>Nothing is back-dated relative to a clock the production code writes: the
 * counts read is a read of history, and its fixture is history. What moves is
 * {@link MovableClock}, which is the only thing that decides <em>which</em>
 * business day "now" falls in.
 */
class LiveBoardCountsTests {

    private static final UUID TENANT = UUID.fromString("018fa011-3000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fa011-3000-7000-8000-0000000000b1");
    private static final UUID CHILONZOR = UUID.fromString("018fa011-3000-7000-8000-0000000000c1");
    private static final UUID YUNUSOBOD = UUID.fromString("018fa011-3000-7000-8000-0000000000c2");
    private static final UUID QUIET_BRANCH = UUID.fromString("018fa011-3000-7000-8000-0000000000c3");
    private static final UUID CUSTOMER = UUID.fromString("018fa011-3000-7000-8000-0000000000d1");

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    /** 00:45 local on 12 September — past midnight, inside the 11 September trading day. */
    private static final Instant DURING_SERVICE = tashkent(12, 0, 45);

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @SuppressWarnings("NullAway")
    private JdbcClient jdbc;

    @SuppressWarnings("NullAway")
    private JdbcOrderStore orders;

    @SuppressWarnings("NullAway")
    private LiveBoardQueryService board;

    @SuppressWarnings("NullAway")
    private MovableClock clock;

    @SuppressWarnings("NullAway")
    private UUID telegramChannel;

    @SuppressWarnings("NullAway")
    private UUID websiteChannel;

    @SuppressWarnings("NullAway")
    private UUID publicationId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the live board counts tests");
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
        jdbc.sql("TRUNCATE TABLE reporting.business_day_policies").update();
        jdbc.sql("TRUNCATE TABLE ordering.orders CASCADE").update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.catalogs CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        orders = new JdbcOrderStore(jdbc);
        clock = new MovableClock(DURING_SERVICE);
        board = new LiveBoardQueryService(
                orders, new BusinessDayWindowsAdapter(new BusinessDayService(new JdbcReportingStore(jdbc))), clock);

        seedTenancy();
        // A branch that closes at 02:00 — the whole point of ADR 0043's boundary.
        new BusinessDayService(new JdbcReportingStore(jdbc))
                .setBoundary(
                        TENANT,
                        new BusinessDayBoundary(TASHKENT, LocalTime.of(2, 0), 1),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 9, 10));
    }

    // ------------------------------------------------- the period predicate

    @Test
    @DisplayName("«Отменено» counts the trading day that is still running, across midnight")
    void cancelledIsCutAtTheBusinessDayBoundaryNotAtMidnight() {
        // 01:00 on the 11th is before that day's 02:00 start, so it belongs to
        // the 10th's trading day. A cut at local midnight would keep it.
        insertOrder("YESTERDAY_EVENING", CHILONZOR, "CANCELLED", tashkent(10, 22, 0), tashkent(11, 1, 0));
        // 00:30 on the 12th is after midnight but before 02:00, so it is still
        // the 11th's trading day. A cut at midnight — local or UTC — loses it.
        insertOrder("TONIGHT_LATE", CHILONZOR, "CANCELLED", tashkent(11, 23, 0), tashkent(12, 0, 30));
        // 02:30 on the 12th is the next trading day, which has not started for
        // the supervisor reading this board.
        insertOrder("TOMORROW", CHILONZOR, "REJECTED", tashkent(11, 23, 30), tashkent(12, 2, 30));

        var today = board.forLocation(TENANT, BRAND, CHILONZOR, OrderCountsPeriod.BUSINESS_DAY);
        var everything = board.forLocation(TENANT, BRAND, CHILONZOR, OrderCountsPeriod.ALL_TIME);

        assertThat(today.counts().cancelled())
                .as("only the cancellation inside [11 Sep 02:00, 12 Sep 02:00) Tashkent")
                .isEqualTo(1);
        assertThat(everything.counts().cancelled())
                .as("ALL_TIME is the unchanged lifetime figure this endpoint used to be")
                .isEqualTo(3);
        assertThat(today.window().from()).isEqualTo(tashkent(11, 2, 0));
        assertThat(today.window().to()).isEqualTo(tashkent(12, 2, 0));
    }

    @Test
    @DisplayName("the window follows the clock: the same rows read differently after the boundary passes")
    void theWindowMovesWithTheClock() {
        insertOrder("TONIGHT_LATE", CHILONZOR, "CANCELLED", tashkent(11, 23, 0), tashkent(12, 0, 30));

        assertThat(board.forLocation(TENANT, BRAND, CHILONZOR, OrderCountsPeriod.BUSINESS_DAY)
                        .counts()
                        .cancelled())
                .isEqualTo(1);

        // 00:45 → 03:45, past the 02:00 boundary: a new trading day has begun and
        // last night's cancellation is no longer today's.
        clock.advance(Duration.ofHours(3));

        var afterRollover = board.forLocation(TENANT, BRAND, CHILONZOR, OrderCountsPeriod.BUSINESS_DAY);
        assertThat(afterRollover.counts().cancelled())
                .as("a counter that did not reset at the boundary is the lifetime total again")
                .isZero();
        assertThat(afterRollover.window().from()).isEqualTo(tashkent(12, 2, 0));
    }

    @Test
    @DisplayName("completed is cut on when the order closed, total on when it arrived")
    void theTwoHistoricalCountersAreCutOnTheirOwnTimestamps() {
        // Placed on the previous trading day, finished on this one.
        insertOrder("SPANNING", CHILONZOR, "COMPLETED", tashkent(11, 1, 30), tashkent(11, 20, 0));

        var today = board.forLocation(TENANT, BRAND, CHILONZOR, OrderCountsPeriod.BUSINESS_DAY);

        assertThat(today.counts().completed())
                .as("it was completed today, which is what «завершено сегодня» asks")
                .isEqualTo(1);
        assertThat(today.counts().total())
                .as("but it arrived yesterday, so it is not one of today's orders")
                .isZero();
    }

    @Test
    @DisplayName("the six live counters are never cut by the period")
    void anOrderStillInTheKitchenIsCountedHoweverLongAgoItArrived() {
        insertOrder("STILL_COOKING", CHILONZOR, "PREPARING", tashkent(9, 20, 0), null);

        var today = board.forLocation(TENANT, BRAND, CHILONZOR, OrderCountsPeriod.BUSINESS_DAY);

        assertThat(today.counts().inKitchen())
                .as("an order on the pass is on the pass; a period cannot make it disappear")
                .isEqualTo(1);
        assertThat(today.counts().totalNonTerminal()).isEqualTo(1);
        assertThat(today.counts().total())
                .as("total is a period figure, and this order arrived two days ago")
                .isZero();
    }

    // ----------------------------------------------------------- the mixes

    @Test
    @DisplayName("both mixes are exact aggregates over the in-progress orders, largest first")
    void theMixesAreServerSideAndSumToTheInProgressCounter() {
        insertOrder("A", CHILONZOR, "PREPARING", tashkent(11, 18, 0), null, telegramChannel, "DELIVERY");
        insertOrder("B", CHILONZOR, "READY", tashkent(11, 18, 5), null, telegramChannel, "PICKUP");
        insertOrder("C", CHILONZOR, "CONFIRMED", tashkent(11, 18, 10), null, websiteChannel, "DELIVERY");
        // Terminal, and therefore in neither mix — the mix answers "what is on
        // the pass right now", not "what happened".
        insertOrder("D", CHILONZOR, "COMPLETED", tashkent(11, 18, 20), tashkent(11, 18, 50), websiteChannel, "PICKUP");

        var today = board.forLocation(TENANT, BRAND, CHILONZOR, OrderCountsPeriod.BUSINESS_DAY);

        assertThat(slices(today.mix(), MixSliceRow.CHANNEL))
                .containsExactly(
                        new MixSliceRow(MixSliceRow.CHANNEL, "TELEGRAM", 2),
                        new MixSliceRow(MixSliceRow.CHANNEL, "WEBSITE", 1));
        assertThat(slices(today.mix(), MixSliceRow.FULFILLMENT_MODE))
                .containsExactly(
                        new MixSliceRow(MixSliceRow.FULFILLMENT_MODE, "DELIVERY", 2),
                        new MixSliceRow(MixSliceRow.FULFILLMENT_MODE, "PICKUP", 1));

        long channelTotal = slices(today.mix(), MixSliceRow.CHANNEL).stream()
                .mapToLong(MixSliceRow::orders)
                .sum();
        assertThat(channelTotal)
                .as("bars that do not add up to the counter beside them are the truncation this replaces")
                .isEqualTo(today.counts().totalNonTerminal());
    }

    @Test
    @DisplayName("the mix does not stop at the 200-order page the console used to fetch")
    void theMixCountsEveryInProgressOrderNotJustTheFirstPage() {
        for (int i = 0; i < 205; i++) {
            insertOrder("BULK-" + i, CHILONZOR, "PREPARING", tashkent(11, 18, 0).plusSeconds(i), null);
        }

        var today = board.forLocation(TENANT, BRAND, CHILONZOR, OrderCountsPeriod.BUSINESS_DAY);

        assertThat(slices(today.mix(), MixSliceRow.CHANNEL))
                .as("MIX_FETCH_LIMIT = 200 would have answered 200 and said nothing about it")
                .containsExactly(new MixSliceRow(MixSliceRow.CHANNEL, "TELEGRAM", 205));
    }

    // ---------------------------------------------------- the branch band

    @Test
    @DisplayName("the brand's leaderboard is one read, busiest branch first")
    void theBrandReadAnswersEveryBranchAtOnce() {
        insertOrder("CH-1", CHILONZOR, "PREPARING", tashkent(11, 18, 0), null);
        insertOrder("YU-1", YUNUSOBOD, "PREPARING", tashkent(11, 18, 1), null);
        insertOrder("YU-2", YUNUSOBOD, "READY", tashkent(11, 18, 2), null);
        insertOrder("YU-3", YUNUSOBOD, "CANCELLED", tashkent(11, 18, 3), tashkent(11, 18, 30));

        var brand = board.forBrand(TENANT, BRAND, OrderCountsPeriod.BUSINESS_DAY);

        assertThat(brand.totals().totalNonTerminal())
                .as("the brand total is its own aggregate over every branch")
                .isEqualTo(3);
        assertThat(brand.totals().cancelled()).isEqualTo(1);
        assertThat(brand.locations())
                .as("a branch with no order at all is absent, not a fabricated zero row")
                .extracting(JdbcOrderStore.LocationCountsRow::locationId)
                .containsExactlyInAnyOrder(CHILONZOR, YUNUSOBOD)
                .doesNotContain(QUIET_BRANCH);

        var yunusobod = brand.locations().stream()
                .filter(row -> YUNUSOBOD.equals(row.locationId()))
                .findFirst()
                .orElseThrow();
        assertThat(yunusobod.counts().totalNonTerminal()).isEqualTo(2);
        assertThat(yunusobod.counts().cancelled()).isEqualTo(1);
    }

    @Test
    @DisplayName("a brand read never reaches another tenant's or another brand's orders")
    void theBrandReadIsScopedToItsOwnBrand() {
        UUID otherBrand = UUID.fromString("018fa011-3000-7000-8000-0000000000b2");
        UUID otherLocation = UUID.fromString("018fa011-3000-7000-8000-0000000000c9");
        insertBrand(otherBrand, "SECOND", "second");
        insertLocation(otherLocation, otherBrand, "OTH", "other");

        insertOrder("MINE", CHILONZOR, "PREPARING", tashkent(11, 18, 0), null);
        insertOrder(
                "THEIRS",
                otherLocation,
                "PREPARING",
                tashkent(11, 18, 1),
                null,
                telegramChannel,
                "DELIVERY",
                otherBrand);

        var brand = board.forBrand(TENANT, BRAND, OrderCountsPeriod.BUSINESS_DAY);

        assertThat(brand.totals().totalNonTerminal()).isEqualTo(1);
        assertThat(brand.locations())
                .extracting(JdbcOrderStore.LocationCountsRow::locationId)
                .containsExactly(CHILONZOR);
        assertThat(slices(brand.mix(), MixSliceRow.CHANNEL))
                .containsExactly(new MixSliceRow(MixSliceRow.CHANNEL, "TELEGRAM", 1));
    }

    // ------------------------------------------------------------ fixtures

    private static List<MixSliceRow> slices(List<MixSliceRow> mix, String dimension) {
        return mix.stream().filter(row -> dimension.equals(row.dimension())).toList();
    }

    /** An instant in September 2026, stated in Tashkent wall-clock time. */
    private static Instant tashkent(int day, int hour, int minute) {
        return LocalDate.of(2026, 9, day).atTime(hour, minute).atZone(TASHKENT).toInstant();
    }

    private void insertOrder(
            String seed, UUID locationId, String status, Instant createdAt, @Nullable Instant closedAt) {
        insertOrder(seed, locationId, status, createdAt, closedAt, telegramChannel, "DELIVERY");
    }

    private void insertOrder(
            String seed,
            UUID locationId,
            String status,
            Instant createdAt,
            @Nullable Instant closedAt,
            UUID channelId,
            String fulfillmentMode) {
        insertOrder(seed, locationId, status, createdAt, closedAt, channelId, fulfillmentMode, BRAND);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void insertOrder(
            String seed,
            UUID locationId,
            String status,
            Instant createdAt,
            @Nullable Instant closedAt,
            UUID channelId,
            String fulfillmentMode,
            UUID brandId) {

        UUID orderId = derived("order:" + seed);
        UUID cartId = derived("cart:" + seed);
        UUID quoteId = derived("quote:" + seed);
        boolean confirmed = List.of("CONFIRMED", "PREPARING", "READY", "FULFILLING", "COMPLETED")
                .contains(status);

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    customer_account_id, fulfillment_mode, currency, status, expires_at, converted_order_id)
                VALUES (:id, :t, :b, :loc, :ch, :cust, :mode, 'UZS', 'CONVERTED', :expires, :orderId)
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", brandId)
                .param("loc", locationId)
                .param("ch", channelId)
                .param("cust", CUSTOMER)
                .param("mode", fulfillmentMode)
                .param("expires", createdAt.atOffset(ZoneOffset.UTC))
                .param("orderId", orderId)
                .update();

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, customer_account_id,
                    currency, status, catalog_publication_id, calculation_version, context_hash,
                    subtotal_minor, tax_minor, fee_minor, discount_minor, total_minor, expires_at, accepted_at)
                VALUES (:id, :t, :b, :loc, :cust, 'UZS', 'ACCEPTED', :pub, 1, :hash,
                    50000, 0, 0, 0, 50000, :expires, :accepted)
                """)
                .param("id", quoteId)
                .param("t", TENANT)
                .param("b", brandId)
                .param("loc", locationId)
                .param("cust", CUSTOMER)
                .param("pub", publicationId)
                .param("hash", "hash-" + seed)
                .param("expires", createdAt.atOffset(ZoneOffset.UTC))
                .param("accepted", createdAt.atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id, location_id,
                    channel_id, channel_code_snapshot, customer_account_id, fulfillment_mode,
                    acceptance_mode_snapshot, approval_channel_snapshot, status, currency,
                    subtotal_minor, tax_minor, discount_minor, fee_minor, total_minor,
                    pricing_quote_id, pricing_context_hash, catalog_publication_id, cart_id,
                    idempotency_key, version, created_at, confirmed_at, closed_at)
                VALUES (:id, :number, :t, :b, :loc, :ch,
                    (SELECT code FROM tenant.sales_channels WHERE id = :ch), :cust, :mode,
                    'AUTO_CONFIRM', 'NONE', :status, 'UZS',
                    50000, 0, 0, 0, 50000,
                    :quote, :hash, :pub, :cart,
                    :key, 1, :createdAt, :confirmedAt, :closedAt)
                """)
                .param("id", orderId)
                .param("number", seed)
                .param("t", TENANT)
                .param("b", brandId)
                .param("loc", locationId)
                .param("ch", channelId)
                .param("cust", CUSTOMER)
                .param("mode", fulfillmentMode)
                .param("status", status)
                .param("quote", quoteId)
                .param("hash", "hash-" + seed)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + seed)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .param("confirmedAt", confirmed ? createdAt.plusSeconds(60).atOffset(ZoneOffset.UTC) : null)
                .param("closedAt", closedAt == null ? null : closedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'live-board-tenant', 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        insertBrand(BRAND, "MAIN", "main");
        insertLocation(CHILONZOR, BRAND, "CHI", "chilonzor");
        insertLocation(YUNUSOBOD, BRAND, "YUN", "yunusobod");
        insertLocation(QUIET_BRANCH, BRAND, "QUI", "quiet");

        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                    identity_policy_version, version)
                VALUES (:id, :t, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", CUSTOMER).param("t", TENANT).update();

        telegramChannel = insertChannel("TELEGRAM", "TELEGRAM");
        websiteChannel = insertChannel("WEBSITE", "WEB");

        UUID catalogId = derived("catalog");
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("t", TENANT)
                .param("b", BRAND)
                .update();

        publicationId = derived("publication");
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

    private void insertBrand(UUID brandId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, :code, :slug, :code, 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("t", TENANT)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private void insertLocation(UUID locationId, UUID brandId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, :code, :slug, :slug, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("t", TENANT)
                .param("b", brandId)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private UUID insertChannel(String code, String systemType) {
        UUID id = derived("channel:" + code);
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :t, :code, :type, :code, 'ACTIVE', false)
                """)
                .param("id", id)
                .param("t", TENANT)
                .param("code", code)
                .param("type", systemType)
                .update();
        return id;
    }

    private static UUID derived(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
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
