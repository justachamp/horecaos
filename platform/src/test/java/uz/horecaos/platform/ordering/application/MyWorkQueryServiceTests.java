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
 * IA 0.2a against the migrated schema: one operator's own orders, today, by
 * sales channel — over {@code created_by_actor_id} and {@code
 * ix_orders_created_by} (V0029), which existed for two waves with nothing
 * aggregating them (wave T01).
 *
 * <p>Mirrors {@code LiveBoardCountsTests}'s fixture style deliberately: same
 * business-day boundary (02:00 Tashkent), same {@link MovableClock}, so a
 * reader who already knows that suite recognises this one. What is new here
 * is the actor dimension — every test below seeds at least two operators (or
 * an operator and an unattributed order) so that "cut to the caller" is
 * proven by something actually being excluded, not merely by an empty result
 * that a dropped predicate would also produce.
 */
class MyWorkQueryServiceTests {

    private static final UUID TENANT = UUID.fromString("018fc300-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fc300-4000-7000-8000-0000000000b1");
    private static final UUID CHILONZOR = UUID.fromString("018fc300-4000-7000-8000-0000000000c1");
    private static final UUID YUNUSOBOD = UUID.fromString("018fc300-4000-7000-8000-0000000000c2");
    private static final UUID CUSTOMER = UUID.fromString("018fc300-4000-7000-8000-0000000000d1");

    private static final ZoneId TASHKENT = ZoneId.of("Asia/Tashkent");

    /** One Keycloak subject — the caller every test but the isolation ones reads as. */
    private static final String OPERATOR_A = "018fc300-op-a";

    /** A second operator at the same branch, whose orders must never appear in {@link #OPERATOR_A}'s read. */
    private static final String OPERATOR_B = "018fc300-op-b";

    /** 00:45 local on 12 September — past midnight, inside the 11 September trading day. */
    private static final Instant DURING_SERVICE = tashkent(12, 0, 45);

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @SuppressWarnings("NullAway")
    private JdbcClient jdbc;

    @SuppressWarnings("NullAway")
    private JdbcOrderStore orders;

    @SuppressWarnings("NullAway")
    private MyWorkQueryService myWork;

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
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the my-work tests");
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
        myWork = new MyWorkQueryService(
                orders, new BusinessDayWindowsAdapter(new BusinessDayService(new JdbcReportingStore(jdbc))), clock);

        seedTenancy();
        new BusinessDayService(new JdbcReportingStore(jdbc))
                .setBoundary(
                        TENANT,
                        new BusinessDayBoundary(TASHKENT, LocalTime.of(2, 0), 1),
                        LocalDate.of(2026, 1, 1),
                        LocalDate.of(2026, 9, 10));
    }

    // ----------------------------------------------------- self-scoping

    @Test
    @DisplayName("a colleague's order at the same branch never appears in my own read")
    void anotherOperatorsOrderIsExcluded() {
        insertOrder("MINE", OPERATOR_A, telegramChannel, tashkent(11, 18, 0));
        insertOrder("THEIRS", OPERATOR_B, telegramChannel, tashkent(11, 18, 5));

        var mix = myWork.channelMixForCaller(TENANT, CHILONZOR, OPERATOR_A);

        assertThat(mix.channels())
                .as("dropping the created_by_actor_id predicate would return 2, not 1")
                .containsExactly(new MixSliceRow(MixSliceRow.CHANNEL, "TELEGRAM", 1));
    }

    @Test
    @DisplayName("an order nobody is attributed to (created_by_actor_id null) is nobody's")
    void anUnattributedOrderBelongsToNoOnesRead() {
        insertUnattributedOrder("SYSTEM", telegramChannel, tashkent(11, 18, 0));

        var mix = myWork.channelMixForCaller(TENANT, CHILONZOR, OPERATOR_A);

        assertThat(mix.channels()).isEmpty();
    }

    // --------------------------------------------------------- grouping

    @Test
    @DisplayName("my own orders are grouped by channel, largest first")
    void groupsByChannelLargestFirst() {
        insertOrder("A", OPERATOR_A, telegramChannel, tashkent(11, 18, 0));
        insertOrder("B", OPERATOR_A, telegramChannel, tashkent(11, 18, 5));
        insertOrder("C", OPERATOR_A, websiteChannel, tashkent(11, 18, 10));
        // A colleague's order in the same window must not inflate either bar.
        insertOrder("D", OPERATOR_B, telegramChannel, tashkent(11, 18, 15));

        var mix = myWork.channelMixForCaller(TENANT, CHILONZOR, OPERATOR_A);

        assertThat(mix.channels())
                .containsExactly(
                        new MixSliceRow(MixSliceRow.CHANNEL, "TELEGRAM", 2),
                        new MixSliceRow(MixSliceRow.CHANNEL, "WEBSITE", 1));
    }

    @Test
    @DisplayName("a terminal order still counts — this is a day's tally, not a live queue")
    void terminalOrdersStillCount() {
        insertOrder("COMPLETED_ONE", OPERATOR_A, telegramChannel, tashkent(11, 18, 0), "COMPLETED");
        insertOrder("CANCELLED_ONE", OPERATOR_A, telegramChannel, tashkent(11, 18, 5), "CANCELLED");

        var mix = myWork.channelMixForCaller(TENANT, CHILONZOR, OPERATOR_A);

        assertThat(mix.channels()).containsExactly(new MixSliceRow(MixSliceRow.CHANNEL, "TELEGRAM", 2));
    }

    // ------------------------------------------------ the business-day window

    @Test
    @DisplayName("my tally is cut to the trading day that is still running, across midnight")
    void cutAtTheBusinessDayBoundaryNotAtMidnight() {
        // 01:00 on the 11th is before that day's 02:00 start: yesterday's trading day.
        insertOrder("YESTERDAY_EVENING", OPERATOR_A, telegramChannel, tashkent(10, 22, 0));
        // 00:30 on the 12th is after midnight but before 02:00: still the 11th's trading day.
        insertOrder("TONIGHT_LATE", OPERATOR_A, telegramChannel, tashkent(11, 23, 30));
        // 02:30 on the 12th is the next trading day, which has not started yet.
        insertOrder("TOMORROW", OPERATOR_A, telegramChannel, tashkent(12, 2, 30));

        var mix = myWork.channelMixForCaller(TENANT, CHILONZOR, OPERATOR_A);

        assertThat(mix.channels())
                .as("only TONIGHT_LATE falls inside [11 Sep 02:00, 12 Sep 02:00) Tashkent")
                .containsExactly(new MixSliceRow(MixSliceRow.CHANNEL, "TELEGRAM", 1));
        assertThat(mix.window().from()).isEqualTo(tashkent(11, 2, 0));
        assertThat(mix.window().to()).isEqualTo(tashkent(12, 2, 0));
    }

    @Test
    @DisplayName("the window follows the clock: yesterday's orders roll off after the boundary passes")
    void theWindowMovesWithTheClock() {
        insertOrder("TONIGHT_LATE", OPERATOR_A, telegramChannel, tashkent(11, 23, 0));

        assertThat(myWork.channelMixForCaller(TENANT, CHILONZOR, OPERATOR_A).channels())
                .hasSize(1);

        // 00:45 -> 03:45, past the 02:00 boundary: a new trading day has begun.
        clock.advance(Duration.ofHours(3));

        assertThat(myWork.channelMixForCaller(TENANT, CHILONZOR, OPERATOR_A).channels())
                .as("last night's order is no longer today's")
                .isEmpty();
    }

    // -------------------------------------------------------- isolation

    @Test
    @DisplayName("my read never reaches another branch's orders, even one of my own")
    void neverReachesAnotherLocation() {
        insertOrderAt(CHILONZOR, "HERE", OPERATOR_A, telegramChannel, tashkent(11, 18, 0));
        insertOrderAt(YUNUSOBOD, "THERE", OPERATOR_A, telegramChannel, tashkent(11, 18, 5));

        var mix = myWork.channelMixForCaller(TENANT, CHILONZOR, OPERATOR_A);

        assertThat(mix.channels()).containsExactly(new MixSliceRow(MixSliceRow.CHANNEL, "TELEGRAM", 1));
    }

    @Test
    @DisplayName("my read never reaches another tenant's orders, even under the same subject string")
    void neverReachesAnotherTenant() {
        OtherTenant other = seedOtherTenant();
        insertOrderAt(CHILONZOR, "MINE", OPERATOR_A, telegramChannel, tashkent(11, 18, 0));
        insertOrderForOtherTenant(other, "THEIRS", OPERATOR_A, tashkent(11, 18, 1));

        var mix = myWork.channelMixForCaller(TENANT, CHILONZOR, OPERATOR_A);

        assertThat(mix.channels())
                .as("dropping tenant_id from the WHERE clause would pull the other tenant's order in too")
                .containsExactly(new MixSliceRow(MixSliceRow.CHANNEL, "TELEGRAM", 1));
    }

    // ------------------------------------------------------------ fixtures

    /** An instant in September 2026, stated in Tashkent wall-clock time. */
    private static Instant tashkent(int day, int hour, int minute) {
        return LocalDate.of(2026, 9, day).atTime(hour, minute).atZone(TASHKENT).toInstant();
    }

    private void insertOrder(String seed, String createdByActorId, UUID channelId, Instant createdAt) {
        insertOrder(seed, createdByActorId, channelId, createdAt, "PREPARING");
    }

    private void insertOrder(String seed, String createdByActorId, UUID channelId, Instant createdAt, String status) {
        insertOrderAt(CHILONZOR, seed, createdByActorId, channelId, createdAt, status, TENANT, BRAND, CUSTOMER);
    }

    private void insertOrderAt(
            UUID locationId, String seed, String createdByActorId, UUID channelId, Instant createdAt) {
        insertOrderAt(locationId, seed, createdByActorId, channelId, createdAt, "PREPARING", TENANT, BRAND, CUSTOMER);
    }

    private void insertUnattributedOrder(String seed, UUID channelId, Instant createdAt) {
        insertOrderRow(seed, null, channelId, createdAt, "PREPARING", CHILONZOR, TENANT, BRAND, CUSTOMER);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void insertOrderAt(
            UUID locationId,
            String seed,
            String createdByActorId,
            UUID channelId,
            Instant createdAt,
            String status,
            UUID tenantId,
            UUID brandId,
            UUID customerId) {
        insertOrderRow(seed, createdByActorId, channelId, createdAt, status, locationId, tenantId, brandId, customerId);
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private void insertOrderRow(
            String seed,
            @Nullable String createdByActorId,
            UUID channelId,
            Instant createdAt,
            String status,
            UUID locationId,
            UUID tenantId,
            UUID brandId,
            UUID customerId) {

        UUID orderId = derived("order:" + seed);
        UUID cartId = derived("cart:" + seed);
        UUID quoteId = derived("quote:" + seed);

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    customer_account_id, fulfillment_mode, currency, status, expires_at, converted_order_id)
                VALUES (:id, :t, :b, :loc, :ch, :cust, 'DELIVERY', 'UZS', 'CONVERTED', :expires, :orderId)
                """)
                .param("id", cartId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("ch", channelId)
                .param("cust", customerId)
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
                .param("t", tenantId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("cust", customerId)
                .param("pub", tenantId.equals(TENANT) ? publicationId : derived("publication:other-tenant"))
                .param("hash", "hash-" + seed)
                .param("expires", createdAt.atOffset(ZoneOffset.UTC))
                .param("accepted", createdAt.atOffset(ZoneOffset.UTC))
                .update();

        boolean confirmed = List.of("CONFIRMED", "PREPARING", "READY", "FULFILLING", "COMPLETED")
                .contains(status);

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id, location_id,
                    channel_id, channel_code_snapshot, customer_account_id, fulfillment_mode,
                    acceptance_mode_snapshot, approval_channel_snapshot, status, currency,
                    subtotal_minor, tax_minor, discount_minor, fee_minor, total_minor,
                    pricing_quote_id, pricing_context_hash, catalog_publication_id, cart_id,
                    idempotency_key, version, created_at, confirmed_at,
                    created_by_actor_type, created_by_actor_id)
                VALUES (:id, :number, :t, :b, :loc, :ch,
                    (SELECT code FROM tenant.sales_channels WHERE id = :ch), :cust, 'DELIVERY',
                    'AUTO_CONFIRM', 'NONE', :status, 'UZS',
                    50000, 0, 0, 0, 50000,
                    :quote, :hash, :pub, :cart,
                    :key, 1, :createdAt, :confirmedAt,
                    :actorType, :actorId)
                """)
                .param("id", orderId)
                .param("number", seed)
                .param("t", tenantId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("ch", channelId)
                .param("cust", customerId)
                .param("status", status)
                .param("quote", quoteId)
                .param("hash", "hash-" + seed)
                .param("pub", tenantId.equals(TENANT) ? publicationId : derived("publication:other-tenant"))
                .param("cart", cartId)
                .param("key", "idem-" + seed)
                .param("createdAt", createdAt.atOffset(ZoneOffset.UTC))
                .param("confirmedAt", confirmed ? createdAt.plusSeconds(60).atOffset(ZoneOffset.UTC) : null)
                .param("actorType", createdByActorId == null ? null : "USER")
                .param("actorId", createdByActorId)
                .update();
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'my-work-tenant', 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        insertBrand(TENANT, BRAND, "MAIN", "main");
        insertLocation(TENANT, CHILONZOR, BRAND, "CHI", "chilonzor");
        insertLocation(TENANT, YUNUSOBOD, BRAND, "YUN", "yunusobod");

        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                    identity_policy_version, version)
                VALUES (:id, :t, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", CUSTOMER).param("t", TENANT).update();

        telegramChannel = insertChannel(TENANT, "TELEGRAM", "TELEGRAM");
        websiteChannel = insertChannel(TENANT, "WEBSITE", "WEB");

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

    /** A second tenant's ids, wired independently of {@link #seedTenancy()}. */
    private record OtherTenant(UUID tenantId, UUID brandId, UUID locationId, UUID customerId, UUID channelId) {}

    private OtherTenant seedOtherTenant() {
        UUID otherTenant = UUID.fromString("018fc300-4000-7000-8000-0000000000e0");
        UUID otherBrand = UUID.fromString("018fc300-4000-7000-8000-0000000000e1");
        UUID otherLocation = UUID.fromString("018fc300-4000-7000-8000-0000000000e2");
        UUID otherCustomer = UUID.fromString("018fc300-4000-7000-8000-0000000000e3");

        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'my-work-other-tenant', 'Legal', 'Other Tenant', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", otherTenant).update();
        insertBrand(otherTenant, otherBrand, "OTH", "oth");
        insertLocation(otherTenant, otherLocation, otherBrand, "OTH", "oth-branch");
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                    identity_policy_version, version)
                VALUES (:id, :t, 'ACTIVE', 'Other customer', 1, 1)
                """).param("id", otherCustomer).param("t", otherTenant).update();

        UUID otherChannel = insertChannel(otherTenant, "TELEGRAM", "TELEGRAM");

        UUID otherCatalog = derived("catalog:other-tenant");
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :t, :b, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", otherCatalog)
                .param("t", otherTenant)
                .param("b", otherBrand)
                .update();

        UUID otherPublication = derived("publication:other-tenant");
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :t, :b, :cat, 'TELEGRAM', 'PUBLISHED', 'hash', now())
                """)
                .param("id", otherPublication)
                .param("t", otherTenant)
                .param("b", otherBrand)
                .param("cat", otherCatalog)
                .update();

        return new OtherTenant(otherTenant, otherBrand, otherLocation, otherCustomer, otherChannel);
    }

    private void insertOrderForOtherTenant(OtherTenant other, String seed, String createdByActorId, Instant createdAt) {
        insertOrderAt(
                other.locationId(),
                seed,
                createdByActorId,
                other.channelId(),
                createdAt,
                "PREPARING",
                other.tenantId(),
                other.brandId(),
                other.customerId());
    }

    private void insertBrand(UUID tenantId, UUID brandId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, :code, :slug, :code, 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("t", tenantId)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private void insertLocation(UUID tenantId, UUID locationId, UUID brandId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, :code, :slug, :slug, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("t", tenantId)
                .param("b", brandId)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private UUID insertChannel(UUID tenantId, String code, String systemType) {
        UUID id = derived("channel:" + tenantId + ":" + code);
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :t, :code, :type, :code, 'ACTIVE', false)
                """)
                .param("id", id)
                .param("t", tenantId)
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
