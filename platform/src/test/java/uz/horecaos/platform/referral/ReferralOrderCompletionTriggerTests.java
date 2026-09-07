package uz.horecaos.platform.referral;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
import uz.horecaos.platform.loyalty.application.ReferralGrantService;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.ordering.api.OrderCancelled;
import uz.horecaos.platform.ordering.api.OrderCompleted;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.api.OrderDirectory.OrderSummary;
import uz.horecaos.platform.referral.application.ReferralCodeService;
import uz.horecaos.platform.referral.application.ReferralOrderCompletionTrigger;
import uz.horecaos.platform.referral.application.ReferralProgramAuthoringService;
import uz.horecaos.platform.referral.application.ReferralProgramAuthoringService.ProgramDraft;
import uz.horecaos.platform.referral.application.ReferralQualificationService;
import uz.horecaos.platform.referral.application.ReferralRedemptionService;
import uz.horecaos.platform.referral.application.ReferralRedemptionService.RedeemCommand;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore;
import uz.horecaos.platform.referral.infrastructure.persistence.JdbcReferralStore.RedemptionRow;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * The production caller {@code ReferralQualificationService.onOrderOutcome}
 * was missing (ADR 0067): {@link ReferralOrderCompletionTrigger} listening for
 * a real {@code OrderCompleted} fact and turning it into a qualifying event.
 *
 * <p>{@code ReferralProgramTests} already covers {@code onOrderOutcome}
 * itself in depth, direct call by direct call. This suite is about the one
 * thing that class cannot exercise: that a real {@link OrderCompleted} fact,
 * published the way {@code OrderStateService} actually publishes it, reaches
 * that method at all -- and that a fact shaped any other way does not.
 */
class ReferralOrderCompletionTriggerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();

    private static final Instant NOW = Instant.parse("2026-09-07T07:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcReferralStore referralStore;
    private JdbcLoyaltyStore loyaltyStore;

    private ReferralProgramAuthoringService authoring;
    private ReferralCodeService codes;
    private ReferralRedemptionService redemptions;
    private ReferralOrderCompletionTrigger trigger;

    private UUID locationId;
    private UUID channelId;
    private UUID publicationId;

    /** What one tenant's fixture seeding produced. */
    private record TenantFixture(UUID tenantId, UUID brandId, UUID locationId, UUID channelId, UUID publicationId) {}

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for referral tests");
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

        jdbc.sql("TRUNCATE TABLE referral.redemptions, referral.codes, referral.programs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE loyalty.reservation_lots, loyalty.reservations, loyalty.lots, loyalty.entries, "
                        + "loyalty.accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE ordering.order_lines, ordering.orders, ordering.carts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        referralStore = new JdbcReferralStore(jdbc);
        loyaltyStore = new JdbcLoyaltyStore(jdbc);

        OrderDirectory orders = this::findOrderSummary;
        authoring = new ReferralProgramAuthoringService(referralStore, CLOCK);
        codes = new ReferralCodeService(referralStore, CLOCK);
        redemptions = new ReferralRedemptionService(referralStore, CLOCK, orders);
        ReferralQualificationService qualification =
                new ReferralQualificationService(referralStore, new ReferralGrantService(loyaltyStore));
        trigger = new ReferralOrderCompletionTrigger(qualification, orders);

        TenantFixture fixture = seedTenancy(TENANT, "referral-trigger-tenant", BRAND, "MAIN");
        locationId = fixture.locationId();
        channelId = fixture.channelId();
        publicationId = fixture.publicationId();
    }

    @Test
    @DisplayName("BOTH_SIDES: a real OrderCompleted fact credits both the referrer and the referee, once")
    void completedOrderPaysTheReferralRewardOnce() {
        activateBothSides(TENANT, BRAND, 10_000, 5_000);
        UUID referrer = newCustomer(TENANT);
        UUID referee = newCustomer(TENANT);
        String code = codes.myCode(TENANT, BRAND, referrer).code();
        redemptions.redeem(new RedeemCommand(TENANT, BRAND, referee, code));

        UUID orderId = completedOrder(TENANT, BRAND, locationId, publicationId, channelId, referee);
        trigger.onOrderingEvent(completedEvent(TENANT, orderId, BRAND, locationId));

        RedemptionRow redemption =
                referralStore.findRedemptionByReferee(TENANT, BRAND, referee).orElseThrow();
        assertThat(redemption.status()).isEqualTo("REWARDED");
        assertThat(redemption.qualifyingOrderId()).isEqualTo(orderId);
        assertThat(balanceOf(TENANT, BRAND, referrer)).isEqualTo(10_000L);
        assertThat(balanceOf(TENANT, BRAND, referee)).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("the same completion, delivered three times, pays exactly once")
    void theSameCompletionReplayedPaysOnce() {
        activateBothSides(TENANT, BRAND, 10_000, 5_000);
        UUID referrer = newCustomer(TENANT);
        UUID referee = newCustomer(TENANT);
        String code = codes.myCode(TENANT, BRAND, referrer).code();
        redemptions.redeem(new RedeemCommand(TENANT, BRAND, referee, code));

        UUID orderId = completedOrder(TENANT, BRAND, locationId, publicationId, channelId, referee);

        trigger.onOrderingEvent(completedEvent(TENANT, orderId, BRAND, locationId));
        trigger.onOrderingEvent(completedEvent(TENANT, orderId, BRAND, locationId));
        trigger.onOrderingEvent(completedEvent(TENANT, orderId, BRAND, locationId));

        assertThat(balanceOf(TENANT, BRAND, referrer))
                .as("the referrer's reward, exactly once across three deliveries")
                .isEqualTo(10_000L);
        assertThat(balanceOf(TENANT, BRAND, referee))
                .as("the referee's reward, exactly once across three deliveries")
                .isEqualTo(5_000L);
    }

    @Test
    @DisplayName("an order that ends any other way pays nothing, and the redemption stays open")
    void anOrderThatEndsAnyOtherWayPaysNothing() {
        activateBothSides(TENANT, BRAND, 10_000, 5_000);
        UUID referrer = newCustomer(TENANT);
        UUID referee = newCustomer(TENANT);
        String code = codes.myCode(TENANT, BRAND, referrer).code();
        redemptions.redeem(new RedeemCommand(TENANT, BRAND, referee, code));

        UUID cancelledOrder = completedOrder(TENANT, BRAND, locationId, publicationId, channelId, referee);
        trigger.onOrderingEvent(new OrderCancelled(
                UUID.randomUUID(),
                new TenantId(TENANT),
                cancelledOrder,
                CLOCK.instant(),
                BRAND,
                locationId,
                "SYSTEM",
                "OUT_OF_STOCK",
                "CONFIRMED",
                "CANCELLED",
                2));

        assertThat(balanceOf(TENANT, BRAND, referrer)).isZero();
        assertThat(balanceOf(TENANT, BRAND, referee)).isZero();
        assertThat(referralStore
                        .findRedemptionByReferee(TENANT, BRAND, referee)
                        .orElseThrow()
                        .status())
                .as("the redemption is still open for a real completion later")
                .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("tenant isolation: a completion pays only into its own tenant's program")
    void tenantIsolation() {
        UUID otherBrandId = UUID.randomUUID();
        TenantFixture other = seedTenancy(OTHER_TENANT, "referral-trigger-tenant-b", otherBrandId, "MAIN");

        activateBothSides(TENANT, BRAND, 10_000, 5_000);
        activateBothSides(OTHER_TENANT, otherBrandId, 20_000, 8_000);

        UUID referrerA = newCustomer(TENANT);
        UUID refereeA = newCustomer(TENANT);
        String codeA = codes.myCode(TENANT, BRAND, referrerA).code();
        redemptions.redeem(new RedeemCommand(TENANT, BRAND, refereeA, codeA));

        UUID referrerB = newCustomer(OTHER_TENANT);
        UUID refereeB = newCustomer(OTHER_TENANT);
        String codeB = codes.myCode(OTHER_TENANT, otherBrandId, referrerB).code();
        redemptions.redeem(new RedeemCommand(OTHER_TENANT, otherBrandId, refereeB, codeB));

        UUID orderA = completedOrder(TENANT, BRAND, locationId, publicationId, channelId, refereeA);
        UUID orderB = completedOrder(
                OTHER_TENANT, otherBrandId, other.locationId(), other.publicationId(), other.channelId(), refereeB);

        trigger.onOrderingEvent(completedEvent(TENANT, orderA, BRAND, locationId));
        trigger.onOrderingEvent(completedEvent(OTHER_TENANT, orderB, otherBrandId, other.locationId()));

        assertThat(balanceOf(TENANT, BRAND, referrerA)).isEqualTo(10_000L);
        assertThat(balanceOf(OTHER_TENANT, otherBrandId, referrerB)).isEqualTo(20_000L);

        // The attack case: an event claiming to be tenant B's, naming an order
        // that actually belongs to tenant A. OrderDirectory's own tenant
        // predicate must find nothing, so this must pay nothing.
        trigger.onOrderingEvent(completedEvent(OTHER_TENANT, orderA, BRAND, locationId));

        assertThat(balanceOf(TENANT, BRAND, referrerA))
                .as("tenant A's referrer is untouched by a mismatched-tenant delivery")
                .isEqualTo(10_000L);
        assertThat(referralStore.findRedemptionByReferee(OTHER_TENANT, otherBrandId, refereeA))
                .as("tenant A's referee has no redemption row under tenant B")
                .isEmpty();
    }

    // -------------------------------------------------------------- helpers

    private Optional<OrderSummary> findOrderSummary(UUID tenantId, UUID orderId) {
        return jdbc.sql("""
                SELECT id, tenant_id, brand_id, location_id, public_order_number,
                       customer_account_id, status, currency, total_minor, version
                  FROM ordering.orders
                 WHERE tenant_id = :tenantId AND id = :orderId
                """)
                .param("tenantId", tenantId)
                .param("orderId", orderId)
                .query((row, number) -> new OrderSummary(
                        row.getObject("id", UUID.class),
                        row.getObject("tenant_id", UUID.class),
                        row.getObject("brand_id", UUID.class),
                        row.getObject("location_id", UUID.class),
                        row.getString("public_order_number"),
                        row.getObject("customer_account_id", UUID.class),
                        null,
                        row.getString("status"),
                        row.getString("currency"),
                        row.getLong("total_minor"),
                        row.getInt("version")))
                .optional();
    }

    private OrderCompleted completedEvent(UUID tenantId, UUID orderId, UUID brandId, UUID location) {
        return new OrderCompleted(
                UUID.randomUUID(),
                new TenantId(tenantId),
                orderId,
                CLOCK.instant(),
                brandId,
                location,
                CLOCK.instant(),
                "UZS",
                84_000L,
                2);
    }

    private long balanceOf(UUID tenantId, UUID brandId, UUID customerAccountId) {
        return loyaltyStore
                .findAccount(tenantId, brandId, customerAccountId)
                .map(JdbcLoyaltyStore.AccountRow::balanceMinor)
                .orElse(0L);
    }

    private void activateBothSides(UUID tenantId, UUID brandId, long referrerRewardMinor, long refereeRewardMinor) {
        UUID id = authoring
                .draftProgram(
                        tenantId,
                        brandId,
                        new ProgramDraft(
                                "BOTH_SIDES", referrerRewardMinor, refereeRewardMinor, "UZS", null, 14, 90, null, null))
                .id();
        authoring.activateProgram(tenantId, brandId, id);
    }

    private UUID newCustomer(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status,
                    identity_policy_version, version)
                VALUES (:id, :tenantId, 'ACTIVE', 1, 1)
                """).param("id", id).param("tenantId", tenantId).update();
        return id;
    }

    private UUID completedOrder(
            UUID tenantId, UUID brandId, UUID location, UUID publication, UUID channel, UUID customerAccountId) {
        UUID orderId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();
        long totalMinor = 84_000;

        jdbc.sql("""
                INSERT INTO pricing.quotes (id, tenant_id, brand_id, location_id, currency,
                    catalog_publication_id, calculation_version, context_hash, subtotal_minor,
                    tax_minor, total_minor, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, 'UZS', :publicationId, 1, 'hash',
                        :total, 0, :total, now() + interval '1 hour')
                """)
                .param("id", quoteId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", location)
                .param("publicationId", publication)
                .param("total", totalMinor)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, customer_account_id, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'DELIVERY', 'UZS',
                        'ACTIVE', :customer, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("locationId", location)
                .param("channelId", channel)
                .param("customer", customerAccountId)
                .update();

        Map<String, Object> order = new HashMap<>();
        order.put("id", orderId);
        order.put("number", "R-" + orderId.toString().substring(0, 8));
        order.put("tenantId", tenantId);
        order.put("brandId", brandId);
        order.put("locationId", location);
        order.put("channelId", channel);
        order.put("quoteId", quoteId);
        order.put("cartId", cartId);
        order.put("publicationId", publication);
        order.put("customer", customerAccountId);
        order.put("total", totalMinor);
        order.put("key", "idem-" + orderId);

        jdbc.sql("""
                INSERT INTO ordering.orders (id, public_order_number, tenant_id, brand_id,
                    location_id, channel_id, channel_code_snapshot, customer_account_id,
                    fulfillment_mode, acceptance_mode_snapshot, acceptance_policy_id,
                    acceptance_policy_version, approval_channel_snapshot,
                    approval_timeout_action_snapshot, status, currency, subtotal_minor, tax_minor,
                    fee_minor, total_minor, pricing_quote_id, pricing_context_hash,
                    catalog_publication_id, cart_id, idempotency_key, version, confirmed_at)
                VALUES (:id, :number, :tenantId, :brandId, :locationId, :channelId, 'WEB',
                    :customer, 'DELIVERY', 'AUTO_CONFIRM', NULL, 0, 'NONE', NULL, 'COMPLETED',
                    'UZS', :total, 0, 0, :total, :quoteId, 'hash', :publicationId, :cartId,
                    :key, 2, now())
                """).params(order).update();

        return orderId;
    }

    private TenantFixture seedTenancy(UUID tenantId, String slug, UUID brandId, String brandCode) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, :code, 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("code", brandCode)
                .param("slug", brandCode.toLowerCase(Locale.ROOT))
                .update();

        UUID location = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'CENTRE', 'centre', 'Centre', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """)
                .param("id", location)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();

        UUID channel = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type,
                    display_name, status)
                VALUES (:id, :tenantId, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """).param("id", channel).param("tenantId", tenantId).update();

        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .update();

        UUID publication = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publication)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("catalogId", catalogId)
                .update();

        return new TenantFixture(tenantId, brandId, location, channel, publication);
    }
}
