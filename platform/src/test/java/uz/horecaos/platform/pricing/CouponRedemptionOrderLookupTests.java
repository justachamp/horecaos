package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.support.TestDatabase;

/**
 * The "which redemption does this order hold?" lookup an order amendment makes
 * on every propose and apply ({@code findRedemptionHeldByOrder},
 * {@code restateHeldRedemptionAmount}), and the one-live-redemption-per-order
 * invariant that lookup is written around.
 *
 * <p>Against the real schema: an index and a uniqueness rule are claims only a
 * database settles. The foreign keys are switched off for the connection
 * ({@code session_replication_role = replica}) because a promotion, a coupon and
 * a quote are the parents of a redemption and have nothing to do with what is
 * asserted here; the check constraints and every index still apply.
 */
class CouponRedemptionOrderLookupTests {

    private static final UUID TENANT = UUID.fromString("018f9b20-9100-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9b20-9100-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018f9b20-9100-7000-8000-0000000000b1");
    private static final UUID COUPON = UUID.fromString("018f9b20-9100-7000-8000-0000000000c1");
    private static final UUID PROMOTION = UUID.fromString("018f9b20-9100-7000-8000-0000000000d1");

    @SuppressWarnings("NullAway")
    private static TestDatabase.Handle db;

    @SuppressWarnings("NullAway")
    private static SingleConnectionDataSource dataSource;

    private JdbcClient jdbc;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
        db = TestDatabase.migrated();
        dataSource = new SingleConnectionDataSource(db.jdbcUrl(), db.username(), db.password(), true);
    }

    @AfterAll
    static void stopDatabase() {
        if (dataSource != null) {
            dataSource.destroy();
        }
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("SET session_replication_role = replica").update();
        jdbc.sql("TRUNCATE TABLE pricing.coupon_redemptions").update();
    }

    @Test
    @DisplayName("a second live redemption for one order is refused, not hidden behind LIMIT 1")
    void anOrderHoldsAtMostOneLiveRedemption() {
        UUID orderId = UUID.randomUUID();
        insert(TENANT, orderId, UUID.randomUUID(), "REDEEMED");

        assertThatThrownBy(() -> insert(TENANT, orderId, UUID.randomUUID(), "REDEEMED"))
                .as("findRedemptionHeldByOrder is LIMIT 1, so a duplicate would otherwise be silently ignored")
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    @DisplayName("a released redemption does not count, and another tenant's order id is a different order")
    void onlyALiveRedemptionOfThisTenantCounts() {
        UUID orderId = UUID.randomUUID();
        insert(TENANT, orderId, UUID.randomUUID(), "RELEASED");
        insert(TENANT, orderId, UUID.randomUUID(), "REDEEMED");
        insert(OTHER_TENANT, orderId, UUID.randomUUID(), "REDEEMED");

        assertThat(jdbc.sql("SELECT count(*) FROM pricing.coupon_redemptions WHERE order_id = :orderId")
                        .param("orderId", orderId)
                        .query(Long.class)
                        .single())
                .isEqualTo(3L);
    }

    @Test
    @DisplayName("the held-redemption lookup has an index to use")
    void theLookupIsServedByAnIndex() {
        for (int i = 0; i < 50; i++) {
            insert(TENANT, UUID.randomUUID(), UUID.randomUUID(), "REDEEMED");
        }
        jdbc.sql("ANALYZE pricing.coupon_redemptions").update();
        // With sequential scans off, the planner scans the table only when it has
        // no index that fits; so an index scan in the plan means one does.
        jdbc.sql("SET enable_seqscan = off").update();
        try {
            List<String> plan = jdbc.sql("""
                    EXPLAIN SELECT brand_id, coupon_id, promotion_id
                    FROM pricing.coupon_redemptions
                    WHERE tenant_id = :tenantId AND order_id = :orderId AND status = 'REDEEMED'
                    LIMIT 1
                    """)
                    .param("tenantId", TENANT)
                    .param("orderId", UUID.randomUUID())
                    .query(String.class)
                    .list();

            assertThat(String.join("\n", plan))
                    .as("the amendment path must not scan the coupon audit trail")
                    .contains("ux_redemption_order_live")
                    .doesNotContain("Seq Scan");
        } finally {
            jdbc.sql("RESET enable_seqscan").update();
        }
    }

    private void insert(UUID tenantId, UUID orderId, UUID quoteId, String status) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO pricing.coupon_redemptions (
                    id, tenant_id, brand_id, coupon_id, promotion_id, customer_account_id,
                    quote_id, order_id, status, amount_minor, currency,
                    reserved_at, redeemed_at, released_at)
                VALUES (:id, :tenantId, :brandId, :couponId, :promotionId, NULL,
                    :quoteId, :orderId, :status, 1000, 'UZS',
                    :now, :redeemedAt, :releasedAt)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", BRAND)
                .param("couponId", COUPON)
                .param("promotionId", PROMOTION)
                .param("quoteId", quoteId)
                .param("orderId", orderId)
                .param("status", status)
                .param("now", now)
                .param("redeemedAt", status.equals("REDEEMED") ? now : null)
                .param("releasedAt", status.equals("RELEASED") ? now : null)
                .update();
    }
}
