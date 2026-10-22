package uz.horecaos.platform.ordering.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.configuration.RetentionWindow;
import uz.horecaos.platform.configuration.RetentionWindow.Scope;
import uz.horecaos.platform.ordering.api.OrderingConfigurationKeys;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0092 as decided on 2026-09-11: a cart that never became an order is deleted 90 days after it was last touched; and,
 * since batch 21, on its own tenant's window when that tenant chose one (ADR 0109).
 *
 * <p>Every cart is written at the real present and the sweep's clock is moved past it, so the days are a duration the test
 * lived through rather than a date it typed. Two tenants are swept by the same pass in the window tests, because the property
 * is that one tenant's choice neither deletes nor keeps another's.
 */
class CartRetentionSweeperTests {

    private static final String KEY = OrderingConfigurationKeys.CART_RETENTION_DAYS_CODE;

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private MovableClock clock;
    private CartRetentionSweeper sweeper;
    private Tenancy tenancy;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
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
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        clock = new MovableClock(Instant.now());
        sweeper = sweeperWith(false);
        tenancy = seedTenancy("cart-retention");
    }

    private CartRetentionSweeper sweeperWith(boolean reportOnly) {
        return new CartRetentionSweeper(new JdbcCartStore(jdbc), jdbc, clock, 90, 500, reportOnly);
    }

    @Test
    @DisplayName("an expired or abandoned cart untouched for ninety days is deleted, and a fresher one is not")
    void aCartUntouchedForNinetyDaysIsDeleted() {
        UUID expired = insertCart(tenancy, "EXPIRED");
        UUID abandoned = insertCart(tenancy, "ABANDONED");
        clock.advance(Duration.ofDays(60));
        UUID fresher = insertCartAt(tenancy, "EXPIRED", clock.instant());

        clock.advance(Duration.ofDays(31));
        assertThat(sweeper.runOnce()).isEqualTo(2);

        assertThat(exists(expired) || exists(abandoned)).isFalse();
        assertThat(exists(fresher))
                .as("touched sixty days after the others, it has thirty days to go")
                .isTrue();
    }

    @Test
    @DisplayName("a cart is not deleted before ninety days, and a converted cart never is")
    void aConvertedCartIsAnOrdersHistory() {
        UUID converted = insertCart(tenancy, "CONVERTED");
        UUID recent = insertCart(tenancy, "ACTIVE");

        clock.advance(Duration.ofDays(89));
        assertThat(sweeper.runOnce())
                .as("eighty-nine days is inside the window")
                .isZero();

        clock.advance(Duration.ofDays(400));
        assertThat(sweeper.runOnce()).isEqualTo(1);
        assertThat(exists(recent)).isFalse();
        assertThat(exists(converted)).as("an order's cart is its history").isTrue();
    }

    @Test
    @DisplayName(
            "a tenant that sets a shorter window has its carts deleted at that window, and its neighbour keeps ninety")
    void aShorterTenantWindowIsHonoured() {
        Tenancy neighbour = seedTenancy("cart-retention-neighbour");
        storeTenantRetention(tenancy.tenantId(), 30);
        UUID shortWindow = insertCart(tenancy, "EXPIRED");
        UUID defaultWindow = insertCart(neighbour, "EXPIRED");

        clock.advance(Duration.ofDays(31));
        assertThat(sweeper.runOnce()).isEqualTo(1);

        assertThat(exists(shortWindow))
                .as("this tenant asked for thirty days; before batch 21 the sweep took the longer of ninety and "
                        + "the largest stored value, and kept this cart for sixty days more without saying so")
                .isFalse();
        assertThat(exists(defaultWindow))
                .as("the neighbour asked for nothing and keeps the platform's ninety")
                .isTrue();

        clock.advance(Duration.ofDays(60));
        assertThat(sweeper.runOnce()).isEqualTo(1);
        assertThat(exists(defaultWindow)).isFalse();
    }

    @Test
    @DisplayName("a longer tenant window is honoured too, and does not hold back the tenants beside it")
    void aLongerTenantWindowIsHonoured() {
        Tenancy neighbour = seedTenancy("cart-retention-neighbour");
        storeTenantRetention(tenancy.tenantId(), 180);
        UUID longWindow = insertCart(tenancy, "EXPIRED");
        UUID defaultWindow = insertCart(neighbour, "EXPIRED");

        clock.advance(Duration.ofDays(91));
        assertThat(sweeper.runOnce()).isEqualTo(1);
        assertThat(exists(defaultWindow)).isFalse();
        assertThat(exists(longWindow)).as("180 days were asked for").isTrue();

        clock.advance(Duration.ofDays(90));
        assertThat(sweeper.runOnce()).isEqualTo(1);
        assertThat(exists(longWindow)).isFalse();
    }

    @Test
    @DisplayName("a value below the platform floor is raised to it, so a stored zero cannot reach a cart still in play")
    void aValueBelowTheFloorIsRaisedToIt() {
        storeTenantRetention(tenancy.tenantId(), 0);
        UUID cart = insertCart(tenancy, "EXPIRED");

        clock.advance(Duration.ofHours(12));
        assertThat(sweeper.runOnce())
                .as("half a day old is inside the one-day floor")
                .isZero();
        assertThat(exists(cart)).isTrue();

        clock.advance(Duration.ofDays(2));
        assertThat(sweeper.runOnce()).isEqualTo(1);
    }

    @Test
    @DisplayName("a platform-wide value governs every tenant that set none of its own")
    void aPlatformValueGovernsTenantsWithoutOne() {
        jdbc.sql("""
                INSERT INTO tenant.configuration_values (id, key_code, scope_type, value_type, integer_value, set_by)
                VALUES (:id, :keyCode, 'PLATFORM', 'INTEGER', 45, 'a-test')
                """).param("id", UUID.randomUUID()).param("keyCode", KEY).update();
        UUID cart = insertCart(tenancy, "EXPIRED");

        clock.advance(Duration.ofDays(46));
        assertThat(sweeper.runOnce()).isEqualTo(1);
        assertThat(exists(cart)).isFalse();
    }

    @Test
    @DisplayName(
            "the window the card shows is the window the sweep uses: stored value, effective value, floor, and who it applies to")
    void theWindowForATenantIsWhatTheSweepUses() {
        RetentionWindow inherited = sweeper.windowFor(tenancy.tenantId());
        assertThat(inherited.code()).isEqualTo(KEY);
        assertThat(inherited.configured()).as("nothing stored").isNull();
        assertThat(inherited.effective()).isEqualTo(90);
        assertThat(inherited.platformDefault()).isEqualTo(90);
        assertThat(inherited.floor()).isEqualTo(OrderingConfigurationKeys.CART_RETENTION_FLOOR_DAYS);
        assertThat(inherited.scope()).isEqualTo(Scope.PER_TENANT);

        storeTenantRetention(tenancy.tenantId(), 30);
        RetentionWindow own = sweeper.windowFor(tenancy.tenantId());
        assertThat(own.configured()).isEqualTo(30);
        assertThat(own.effective())
                .as("a shorter value is the effective one now, not ignored")
                .isEqualTo(30);

        storeTenantRetention(seedTenancy("cart-retention-other").tenantId(), 7);
        assertThat(sweeper.windowFor(tenancy.tenantId()).effective())
                .as("another tenant's value changes nothing here")
                .isEqualTo(30);
    }

    @Test
    @DisplayName("a customer under a legal hold keeps her carts, and loses them once the hold is lifted")
    void aLegalHoldSuspendsTheExpiry() {
        UUID account = insertAccount(tenancy.tenantId());
        UUID held = insertCartFor(tenancy, "EXPIRED", clock.instant(), account);
        UUID unheld = insertCart(tenancy, "EXPIRED");
        insertHold(tenancy.tenantId(), account);

        clock.advance(Duration.ofDays(91));
        assertThat(sweeper.runOnce()).isEqualTo(1);
        assertThat(exists(unheld)).isFalse();
        assertThat(exists(held)).as("expiry is suspended for a held customer").isTrue();

        jdbc.sql("""
                UPDATE customer.legal_holds
                   SET status = 'LIFTED', lifted_at = now(), lifted_by_actor_type = 'USER', lifted_by_actor_id = 'owner-2'
                 WHERE customer_account_id = :id
                """).param("id", account).update();
        assertThat(sweeper.runOnce()).isEqualTo(1);
        assertThat(exists(held)).isFalse();
    }

    @Test
    @DisplayName("report-only mode counts what it would delete and deletes nothing")
    void reportOnlyDeletesNothing() {
        UUID cart = insertCart(tenancy, "EXPIRED");
        clock.advance(Duration.ofDays(91));

        assertThat(sweeperWith(true).runOnce()).isZero();
        assertThat(exists(cart))
                .as("report-only is the mode ADR 0029 asks destructive jobs to start in")
                .isTrue();
        assertThat(new JdbcCartStore(jdbc).countAbandoned(clock.instant(), 90, 1))
                .isEqualTo(1);
        assertThat(sweeper.runOnce()).isEqualTo(1);
    }

    // ------------------------------------------------------------- fixtures

    private void storeTenantRetention(UUID tenantId, int days) {
        jdbc.sql("""
                INSERT INTO tenant.configuration_values (id, key_code, scope_type, tenant_id,
                    value_type, integer_value, set_by)
                VALUES (:id, :keyCode, 'TENANT', :tenantId, 'INTEGER', :days, 'a-test')
                """)
                .param("id", UUID.randomUUID())
                .param("keyCode", KEY)
                .param("tenantId", tenantId)
                .param("days", (long) days)
                .update();
    }

    private UUID insertCart(Tenancy in, String status) {
        return insertCartAt(in, status, clock.instant());
    }

    private UUID insertCartAt(Tenancy in, String status, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    guest_reference_hash, fulfillment_mode, currency, status, expires_at,
                    converted_order_id, created_at, updated_at)
                VALUES (:id, :t, :b, :loc, :ch, 'guest-hash', 'PICKUP', 'UZS', :status, :expires,
                    :orderId, :at, :at)
                """)
                .param("id", id)
                .param("t", in.tenantId())
                .param("b", in.brandId())
                .param("loc", in.locationId())
                .param("ch", in.channelId())
                .param("status", status)
                .param("expires", at.plus(Duration.ofHours(2)).atOffset(ZoneOffset.UTC))
                .param("orderId", "CONVERTED".equals(status) ? UUID.randomUUID() : null)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    private UUID insertCartFor(Tenancy in, String status, Instant at, UUID account) {
        UUID id = insertCartAt(in, status, at);
        jdbc.sql("UPDATE ordering.carts SET customer_account_id = :a, guest_reference_hash = NULL WHERE id = :id")
                .param("a", account)
                .param("id", id)
                .update();
        return id;
    }

    private UUID insertAccount(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status, display_name, identity_policy_version, version)
                VALUES (:id, :t, 'ACTIVE', 'Customer', 1, 1)
                """).param("id", id).param("t", tenantId).update();
        return id;
    }

    private void insertHold(UUID tenantId, UUID account) {
        jdbc.sql("""
                INSERT INTO customer.legal_holds (id, tenant_id, customer_account_id, reason_code,
                    placed_by_actor_type, placed_by_actor_id)
                VALUES (:id, :t, :a, 'LITIGATION', 'USER', 'owner-1')
                """)
                .param("id", UUID.randomUUID())
                .param("t", tenantId)
                .param("a", account)
                .update();
    }

    private boolean exists(UUID cartId) {
        return jdbc.sql("SELECT count(*) FROM ordering.carts WHERE id = :id")
                        .param("id", cartId)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    private record Tenancy(UUID tenantId, UUID brandId, UUID locationId, UUID channelId) {}

    private Tenancy seedTenancy(String slug) {
        UUID tenantId = UUID.randomUUID();
        UUID brandId = UUID.randomUUID();
        UUID locationId = UUID.randomUUID();
        UUID channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", brandId).param("t", tenantId).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :t, :b, 'CHI', 'chilonzor', 'Chilonzor', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("t", tenantId)
                .param("b", brandId)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name,
                    status, guest_orders_allowed)
                VALUES (:id, :t, 'WEB', 'WEB', 'Storefront', 'ACTIVE', true)
                """).param("id", channelId).param("t", tenantId).update();
        return new Tenancy(tenantId, brandId, locationId, channelId);
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
