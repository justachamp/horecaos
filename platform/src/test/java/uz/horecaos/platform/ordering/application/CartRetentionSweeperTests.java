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
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcCartStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0092 as decided on 2026-09-11: a cart that never became an order is
 * deleted 90 days after it was last touched.
 *
 * <p>Every cart is written at the real present and the sweep's clock is moved
 * past it, so the ninety days are a duration the test lived through rather
 * than a date it typed.
 */
class CartRetentionSweeperTests {

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private MovableClock clock;
    private CartRetentionSweeper sweeper;
    private UUID tenantId;
    private UUID brandId;
    private UUID locationId;
    private UUID channelId;

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
        clock = new MovableClock(Instant.now());
        sweeper = new CartRetentionSweeper(new JdbcCartStore(jdbc), clock, 90, 500);
        seedTenancy();
    }

    @Test
    @DisplayName("an expired or abandoned cart untouched for ninety days is deleted, and a fresher one is not")
    void aCartUntouchedForNinetyDaysIsDeleted() {
        UUID expired = insertCart("EXPIRED");
        UUID abandoned = insertCart("ABANDONED");
        clock.advance(Duration.ofDays(60));
        UUID fresher = insertCartAt("EXPIRED", clock.instant());

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
        UUID converted = insertCart("CONVERTED");
        UUID recent = insertCart("ACTIVE");

        clock.advance(Duration.ofDays(89));
        assertThat(sweeper.runOnce())
                .as("eighty-nine days is inside the window")
                .isZero();

        clock.advance(Duration.ofDays(400));
        assertThat(sweeper.runOnce()).isEqualTo(1);
        assertThat(exists(recent)).isFalse();
        assertThat(exists(converted)).as("an order's cart is its history").isTrue();
    }

    // ------------------------------------------------------------- fixtures

    private UUID insertCart(String status) {
        return insertCartAt(status, clock.instant());
    }

    private UUID insertCartAt(String status, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    guest_reference_hash, fulfillment_mode, currency, status, expires_at,
                    converted_order_id, created_at, updated_at)
                VALUES (:id, :t, :b, :loc, :ch, 'guest-hash', 'PICKUP', 'UZS', :status, :expires,
                    :orderId, :at, :at)
                """)
                .param("id", id)
                .param("t", tenantId)
                .param("b", brandId)
                .param("loc", locationId)
                .param("ch", channelId)
                .param("status", status)
                .param("expires", at.plus(Duration.ofHours(2)).atOffset(ZoneOffset.UTC))
                .param("orderId", "CONVERTED".equals(status) ? UUID.randomUUID() : null)
                .param("at", at.atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    private boolean exists(UUID cartId) {
        return jdbc.sql("SELECT count(*) FROM ordering.carts WHERE id = :id")
                        .param("id", cartId)
                        .query(Integer.class)
                        .single()
                > 0;
    }

    private void seedTenancy() {
        tenantId = UUID.randomUUID();
        brandId = UUID.randomUUID();
        locationId = UUID.randomUUID();
        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'cart-retention', 'Legal', 'Pilot', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).update();
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
