package uz.horecaos.platform.ordering.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.ordering.application.LiveBoardQueryService;
import uz.horecaos.platform.ordering.application.OrderCountsPeriod;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.reporting.application.BusinessDayService;
import uz.horecaos.platform.reporting.application.BusinessDayWindowsAdapter;
import uz.horecaos.platform.reporting.infrastructure.persistence.JdbcReportingStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.telemetry.api.ScopeKey;
import uz.horecaos.platform.telemetry.api.StreamChannel;

/**
 * The ADR 0045 {@code COUNTERS} snapshot, proved against a real schema (wave
 * P08, row {@code 0.1f}).
 *
 * <p>The property under test is exactly the one the brief names: {@code
 * OrderCountsSnapshotSource.snapshot} for a scope must equal what {@code
 * GET .../orders/counts} (LOCATION) and {@code GET .../brands/{id}/orders/counts}
 * (BRAND) answer for that same scope — a stream and a poll that could disagree
 * about the same nine counters is worse than either alone. It is not enough
 * for the two to call the same store method; the snapshot source's own
 * location-only query path ({@link JdbcOrderStore#locationCounts}) has to
 * produce the identical row a brand-scoped caller with a real {@code brandId}
 * would, which is why this seeds a sibling location under the same brand and
 * asserts it is excluded rather than merely trusting the query looks right.
 */
class OrderCountsSnapshotSourceTests {

    private static final UUID TENANT = UUID.fromString("018fb022-4000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018fb022-4000-7000-8000-0000000000b1");
    private static final UUID LOCATION_A = UUID.fromString("018fb022-4000-7000-8000-0000000000c1");
    private static final UUID LOCATION_B = UUID.fromString("018fb022-4000-7000-8000-0000000000c2");
    private static final UUID CUSTOMER = UUID.fromString("018fb022-4000-7000-8000-0000000000d1");
    private static final Instant NOW = Instant.parse("2026-09-14T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcOrderStore orders;
    private LiveBoardQueryService liveBoard;
    private OrderCountsSnapshotSource source;
    private UUID channelId;
    private UUID publicationId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the COUNTERS snapshot tests");
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
        jdbc.sql("TRUNCATE TABLE ordering.orders CASCADE").update();
        jdbc.sql("TRUNCATE TABLE ordering.carts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.catalogs CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        orders = new JdbcOrderStore(jdbc);
        liveBoard = new LiveBoardQueryService(
                orders, new BusinessDayWindowsAdapter(new BusinessDayService(new JdbcReportingStore(jdbc))), CLOCK);
        source = new OrderCountsSnapshotSource(liveBoard);

        seedTenancy();
    }

    @Test
    @DisplayName("channel() is COUNTERS")
    void declaresTheCountersChannel() {
        assertThat(source.channel()).isEqualTo(StreamChannel.COUNTERS);
    }

    @Test
    @DisplayName("a LOCATION snapshot equals OrderCountsResponse for the same location")
    void locationSnapshotEqualsOrderCountsResponseForTheSameScope() {
        insertOrder("A-NEW", LOCATION_A, "RECEIVED");
        insertOrder("A-KITCHEN", LOCATION_A, "PREPARING");
        insertOrder("A-DONE", LOCATION_A, "COMPLETED");
        // A sibling location on the same brand: proves the snapshot's
        // location-only query does not silently answer for the whole brand.
        insertOrder("B-NEW", LOCATION_B, "RECEIVED");

        Optional<Object> snapshot = source.snapshot(TENANT, ScopeKey.location(LOCATION_A));

        LiveBoardQueryService.LocationLiveBoard expectedBoard =
                liveBoard.forLocation(TENANT, BRAND, LOCATION_A, OrderCountsPeriod.ALL_TIME, null, null);
        OperationsOrderController.OrderCountsResponse expected =
                OperationsOrderController.OrderCountsResponse.of(expectedBoard, OrderCountsPeriod.ALL_TIME);

        assertThat(snapshot).contains(expected);
        // And the count itself proves LOCATION_B's order was actually excluded,
        // not merely that the two equal computations share the same (wrong) bug.
        assertThat(expected.total()).isEqualTo(3);
    }

    @Test
    @DisplayName("a BRAND snapshot equals BrandOrderCountsResponse for the same brand")
    void brandSnapshotEqualsBrandOrderCountsResponseForTheSameScope() {
        insertOrder("A-NEW", LOCATION_A, "RECEIVED");
        insertOrder("B-KITCHEN", LOCATION_B, "PREPARING");

        Optional<Object> snapshot = source.snapshot(TENANT, ScopeKey.brand(BRAND));

        LiveBoardQueryService.BrandLiveBoard expectedBoard =
                liveBoard.forBrand(TENANT, BRAND, OrderCountsPeriod.ALL_TIME);
        OperationsBrandOrderController.BrandOrderCountsResponse expected =
                OperationsBrandOrderController.BrandOrderCountsResponse.of(expectedBoard, OrderCountsPeriod.ALL_TIME);

        assertThat(snapshot).contains(expected);
        assertThat(expected.totals().total()).isEqualTo(2);
        assertThat(expected.locations()).hasSize(2);
    }

    @Test
    @DisplayName("a scope type COUNTERS does not declare answers nothing, defensively")
    void anUndeclaredScopeTypeAnswersEmpty() {
        assertThat(source.snapshot(TENANT, ScopeKey.tenant(TENANT))).isEmpty();
    }

    // ------------------------------------------------------------ fixtures

    private void insertOrder(String seed, UUID locationId, String status) {
        UUID orderId = derived("order:" + seed);
        UUID cartId = derived("cart:" + seed);
        UUID quoteId = derived("quote:" + seed);
        boolean confirmed = !"RECEIVED".equals(status);
        Instant closedAt = "COMPLETED".equals(status) || "CANCELLED".equals(status) ? NOW : null;

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    customer_account_id, fulfillment_mode, currency, status, expires_at, converted_order_id)
                VALUES (:id, :t, :b, :loc, :ch, :cust, 'DELIVERY', 'UZS', 'CONVERTED', :expires, :orderId)
                """)
                .param("id", cartId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", locationId)
                .param("ch", channelId)
                .param("cust", CUSTOMER)
                .param("expires", NOW.atOffset(ZoneOffset.UTC))
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
                .param("b", BRAND)
                .param("loc", locationId)
                .param("cust", CUSTOMER)
                .param("pub", publicationId)
                .param("hash", "hash-" + seed)
                .param("expires", NOW.atOffset(ZoneOffset.UTC))
                .param("accepted", NOW.atOffset(ZoneOffset.UTC))
                .update();

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id, location_id,
                    channel_id, channel_code_snapshot, customer_account_id, fulfillment_mode,
                    acceptance_mode_snapshot, approval_channel_snapshot, status, currency,
                    subtotal_minor, tax_minor, discount_minor, fee_minor, total_minor,
                    pricing_quote_id, pricing_context_hash, catalog_publication_id, cart_id,
                    idempotency_key, version, created_at, confirmed_at, closed_at)
                VALUES (:id, :number, :t, :b, :loc, :ch,
                    (SELECT code FROM tenant.sales_channels WHERE id = :ch), :cust, 'DELIVERY',
                    'AUTO_CONFIRM', 'NONE', :status, 'UZS',
                    50000, 0, 0, 0, 50000,
                    :quote, :hash, :pub, :cart,
                    :key, 1, :createdAt, :confirmedAt, :closedAt)
                """)
                .param("id", orderId)
                .param("number", seed)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("loc", locationId)
                .param("ch", channelId)
                .param("cust", CUSTOMER)
                .param("status", status)
                .param("quote", quoteId)
                .param("hash", "hash-" + seed)
                .param("pub", publicationId)
                .param("cart", cartId)
                .param("key", "idem-" + seed)
                .param("createdAt", NOW.atOffset(ZoneOffset.UTC))
                .param("confirmedAt", confirmed ? NOW.atOffset(ZoneOffset.UTC) : null)
                .param("closedAt", closedAt == null ? null : closedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'counters-snapshot-tenant', 'Legal', 'Osh Markazi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Main', 'ACTIVE', 0)
                """).param("id", BRAND).param("t", TENANT).update();
        insertLocation(LOCATION_A, "CHI", "chilonzor");
        insertLocation(LOCATION_B, "YUN", "yunusobod");

        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name,
                    identity_policy_version, version)
                VALUES (:id, :t, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", CUSTOMER).param("t", TENANT).update();

        channelId = derived("channel");
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :t, 'TELEGRAM', 'TELEGRAM', 'Telegram', 'ACTIVE', false)
                """).param("id", channelId).param("t", TENANT).update();

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

    private void insertLocation(UUID locationId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, :code, :slug, :slug, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("t", TENANT)
                .param("b", BRAND)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private static UUID derived(String seed) {
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }
}
