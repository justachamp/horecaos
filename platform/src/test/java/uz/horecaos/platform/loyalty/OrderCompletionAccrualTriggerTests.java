package uz.horecaos.platform.loyalty;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.loyalty.application.LoyaltyAccrualService;
import uz.horecaos.platform.loyalty.application.LoyaltyPolicyService;
import uz.horecaos.platform.loyalty.application.OrderCompletionAccrualTrigger;
import uz.horecaos.platform.loyalty.application.PointsRedemptionService;
import uz.horecaos.platform.loyalty.domain.EntryType;
import uz.horecaos.platform.loyalty.domain.LotStatus;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.AccountRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.EntryRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.LedgerDrift;
import uz.horecaos.platform.ordering.api.OrderCancelled;
import uz.horecaos.platform.ordering.api.OrderCompleted;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore;
import uz.horecaos.platform.payments.settlement.OrderSettlementService;
import uz.horecaos.platform.payments.settlement.OrderSettlementService.PlannedTender;
import uz.horecaos.platform.payments.settlement.OrderSettlementService.SettlementPlan;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.TenantId;

/**
 * The production caller {@code LoyaltyAccrualService.accrue(CompletedOrder)}
 * was missing (ADR 0046): {@link OrderCompletionAccrualTrigger} listening for
 * a real {@code OrderCompleted} fact and turning it into an accrual attempt.
 *
 * <p>Against a real PostgreSQL, for the same reason {@code
 * LoyaltyLedgerAndSplitTenderTests} is: whether a replayed event accrues twice
 * is a property of {@code appendEntry}'s {@code ON CONFLICT DO NOTHING}, and
 * whether the redeemed portion is actually excluded is a property of a real
 * settlement plan and a real points reservation, neither of which a mock can
 * stand in for.
 */
class OrderCompletionAccrualTriggerTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();

    private static final Instant NOW = Instant.parse("2026-09-07T07:00:00Z");
    private static final java.time.OffsetDateTime VALID_FROM =
            NOW.minus(Duration.ofDays(1)).atOffset(ZoneOffset.UTC);

    private static TestDatabase.Handle db;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private JdbcLoyaltyStore store;
    private JdbcSettlementStore settlementStore;
    private TransactionTemplate transactions;
    private Clock clock;

    private PointsRedemptionService redemption;
    private OrderSettlementService settlements;
    private OrderCompletionAccrualTrigger trigger;

    private UUID locationId;
    private UUID channelId;
    private UUID publicationId;
    private UUID customerId;
    private UUID pointsMethod;
    private UUID clickMethod;

    /** What one tenant's fixture seeding produced. */
    private record TenantFixture(
            UUID tenantId, UUID brandId, UUID locationId, UUID channelId, UUID publicationId, UUID customerId) {}

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for loyalty tests");
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
        dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);

        jdbc.sql("TRUNCATE TABLE loyalty.reservation_lots, loyalty.reservations, loyalty.lots, "
                        + "loyalty.entries, loyalty.clawbacks, loyalty.accrual_rules, "
                        + "loyalty.redemption_policies, loyalty.accounts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE payments.tenders, payments.order_settlements, payments.payment_methods CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE ordering.order_lines, ordering.orders, ordering.carts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = Clock.fixed(NOW, ZoneOffset.UTC);
        store = new JdbcLoyaltyStore(jdbc);
        settlementStore = new JdbcSettlementStore(jdbc);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        LoyaltyPolicyService policies = new LoyaltyPolicyService(store);
        redemption = new PointsRedemptionService(store, policies, clock);
        LoyaltyAccrualService accrual = new LoyaltyAccrualService(store, policies, clock);
        settlements = new OrderSettlementService(settlementStore, redemption, clock);
        trigger = new OrderCompletionAccrualTrigger(accrual, store);

        TenantFixture fixture = seedTenancy(TENANT, "trigger-tenant", BRAND, "MAIN");
        locationId = fixture.locationId();
        channelId = fixture.channelId();
        publicationId = fixture.publicationId();
        customerId = fixture.customerId();

        seedAccrualRule(TENANT, BRAND);
        seedRedemptionPolicy(TENANT, BRAND);
        seedPaymentMethods();
    }

    @AfterEach
    void everyBalanceIsExactlyItsOwnLedger() {
        if (store == null) {
            return;
        }
        List<LedgerDrift> drifting = store.driftingAccounts(500);
        assertThat(drifting)
                .as("every points balance is the sum of its own movements")
                .isEmpty();
    }

    @Test
    @DisplayName("a completed order accrues the right points, net of the delivery fee")
    void completedOrderAccruesTheRightPoints() {
        UUID orderId = orderFor(TENANT, BRAND, locationId, channelId, publicationId, customerId, 100_000L, 10_000L);

        transactions.executeWithoutResult(
                status -> trigger.onOrderingEvent(completedEvent(TENANT, orderId, BRAND, locationId, 100_000L)));

        // 3% of (100 000 - 10 000 fee) = 2 700.
        AccountRow account = store.findAccount(TENANT, BRAND, customerId).orElseThrow();
        assertThat(account.balanceMinor()).isEqualTo(2_700L);

        List<EntryRow> entries = store.entries(TENANT, account.id(), 10);
        assertThat(entries).hasSize(1);
        assertThat(entries.get(0).entryType()).isEqualTo(EntryType.ACCRUAL);
        assertThat(entries.get(0).orderId()).isEqualTo(orderId);
    }

    @Test
    @DisplayName("the same completion, replayed, accrues nothing further")
    void theSameCompletionReplayedAccruesNothingFurther() {
        UUID orderId = orderFor(TENANT, BRAND, locationId, channelId, publicationId, customerId, 100_000L, 0L);

        transactions.executeWithoutResult(
                status -> trigger.onOrderingEvent(completedEvent(TENANT, orderId, BRAND, locationId, 100_000L)));
        // A redelivery of the identical fact -- a fresh event id, same order,
        // same completion instant, exactly what an at-least-once delivery looks
        // like on the wire.
        transactions.executeWithoutResult(
                status -> trigger.onOrderingEvent(completedEvent(TENANT, orderId, BRAND, locationId, 100_000L)));
        // And a third, for good measure -- the same shape ADR 0067's own
        // three-delivery test uses.
        transactions.executeWithoutResult(
                status -> trigger.onOrderingEvent(completedEvent(TENANT, orderId, BRAND, locationId, 100_000L)));

        AccountRow account = store.findAccount(TENANT, BRAND, customerId).orElseThrow();
        assertThat(account.balanceMinor()).as("3% of 100 000, exactly once").isEqualTo(3_000L);
        assertThat(store.entries(TENANT, account.id(), 10))
                .as("one accrual entry, not three")
                .hasSize(1);
    }

    @Test
    @DisplayName("an order that ends any other way accrues nothing")
    void anOrderThatEndsAnyOtherWayAccruesNothing() {
        UUID orderId = orderFor(TENANT, BRAND, locationId, channelId, publicationId, customerId, 100_000L, 0L);

        transactions.executeWithoutResult(status -> trigger.onOrderingEvent(new OrderCancelled(
                UUID.randomUUID(),
                new TenantId(TENANT),
                orderId,
                clock.instant(),
                BRAND,
                locationId,
                "SYSTEM",
                "OUT_OF_STOCK",
                "CONFIRMED",
                "CANCELLED",
                2)));

        assertThat(store.findAccount(TENANT, BRAND, customerId))
                .as("a cancelled order opens no account and earns nothing")
                .isEmpty();
    }

    @Test
    @DisplayName("accrual excludes the redeemed portion: it is computed on money, not on points")
    void accrualExcludesTheRedeemedPortion() {
        seedSpendableBalance(customerId, 20_000L);

        UUID orderId = orderFor(TENANT, BRAND, locationId, channelId, publicationId, customerId, 94_000L, 10_000L);
        UUID tenderId = redeemAndSettle(orderId, 94_000L, 12_000L);
        assertThat(tenderId).isNotNull();

        transactions.executeWithoutResult(
                status -> trigger.onOrderingEvent(completedEvent(TENANT, orderId, BRAND, locationId, 94_000L)));

        // Money settled = 94 000 - 12 000 redeemed = 82 000; base excludes the
        // 10 000 fee too, so the accrual base is 72 000 and 3% of it is 2 160.
        // Starting balance 20 000, minus the 12 000 redemption, plus 2 160
        // earned.
        AccountRow account = store.findAccount(TENANT, BRAND, customerId).orElseThrow();
        assertThat(account.balanceMinor()).isEqualTo(20_000L - 12_000L + 2_160L);
    }

    @Test
    @DisplayName("tenant isolation: a completion accrues only into its own tenant's ledger")
    void tenantIsolation() {
        UUID otherBrandId = UUID.randomUUID();
        TenantFixture other = seedTenancy(OTHER_TENANT, "trigger-tenant-b", otherBrandId, "MAIN");
        seedAccrualRule(OTHER_TENANT, otherBrandId);

        UUID orderInA = orderFor(TENANT, BRAND, locationId, channelId, publicationId, customerId, 100_000L, 0L);
        UUID orderInB = orderFor(
                OTHER_TENANT,
                otherBrandId,
                other.locationId(),
                other.channelId(),
                other.publicationId(),
                other.customerId(),
                200_000L,
                0L);

        transactions.executeWithoutResult(
                status -> trigger.onOrderingEvent(completedEvent(TENANT, orderInA, BRAND, locationId, 100_000L)));
        transactions.executeWithoutResult(status -> trigger.onOrderingEvent(
                completedEvent(OTHER_TENANT, orderInB, otherBrandId, other.locationId(), 200_000L)));

        assertThat(store.findAccount(TENANT, BRAND, customerId).orElseThrow().balanceMinor())
                .as("tenant A earned only on its own order")
                .isEqualTo(3_000L);
        assertThat(store.findAccount(OTHER_TENANT, otherBrandId, other.customerId())
                        .orElseThrow()
                        .balanceMinor())
                .as("tenant B earned only on its own order")
                .isEqualTo(6_000L);

        // The attack case: an event claiming to be tenant B's, naming an order
        // that actually belongs to tenant A. The tenant predicate inside
        // orderFacts must refuse to find it, rather than crediting tenant B for
        // an order tenant A's customer placed.
        transactions.executeWithoutResult(
                status -> trigger.onOrderingEvent(completedEvent(OTHER_TENANT, orderInA, BRAND, locationId, 100_000L)));

        assertThat(store.findAccount(TENANT, BRAND, customerId).orElseThrow().balanceMinor())
                .as("tenant A's balance is untouched by a mismatched-tenant delivery")
                .isEqualTo(3_000L);
        assertThat(store.accountsOfCustomer(OTHER_TENANT, customerId))
                .as("no account was ever opened for tenant A's customer under tenant B")
                .isEmpty();
    }

    // -------------------------------------------------------------- helpers

    private OrderCompleted completedEvent(UUID tenantId, UUID orderId, UUID brandId, UUID location, long totalMinor) {
        return new OrderCompleted(
                UUID.randomUUID(),
                new TenantId(tenantId),
                orderId,
                clock.instant(),
                brandId,
                location,
                clock.instant(),
                "UZS",
                totalMinor,
                2);
    }

    /** Reserves points against the order and settles the balance tender, the way checkout would. */
    private UUID redeemAndSettle(UUID orderId, long totalMinor, long pointsMinor) {
        transactions.execute(status -> settlements.plan(new SettlementPlan(
                TENANT,
                BRAND,
                orderId,
                customerId,
                "UZS",
                totalMinor,
                List.of(
                        new PlannedTender(pointsMethod, pointsMinor),
                        new PlannedTender(clickMethod, totalMinor - pointsMinor)),
                "k-" + orderId,
                "test")));

        UUID settlementId =
                settlementStore.findSettlement(TENANT, orderId).orElseThrow().id();
        UUID tenderId = settlementStore.tendersOf(TENANT, settlementId).stream()
                .filter(JdbcSettlementStore.TenderRow::settlesFromBalance)
                .findFirst()
                .orElseThrow()
                .id();

        transactions.executeWithoutResult(status -> settlements.recordTenderSettled(TENANT, orderId, tenderId, "test"));
        return tenderId;
    }

    private void seedSpendableBalance(UUID customer, long amountMinor) {
        transactions.executeWithoutResult(status -> {
            Instant now = clock.instant();
            AccountRow account = store.openAccount(UUID.randomUUID(), TENANT, BRAND, customer, "UZS", now);
            UUID entryId = UUID.randomUUID();
            store.appendEntry(
                    new JdbcLoyaltyStore.NewEntry(
                            entryId,
                            TENANT,
                            account.id(),
                            EntryType.ADJUSTMENT,
                            amountMinor,
                            amountMinor,
                            null,
                            null,
                            null,
                            null,
                            null,
                            "SEED_FIXTURE",
                            "test-fixture",
                            null,
                            "seed:" + entryId,
                            now),
                    now);
            store.insertLot(
                    UUID.randomUUID(),
                    TENANT,
                    account.id(),
                    entryId,
                    amountMinor,
                    now.minus(Duration.ofHours(1)),
                    now.plus(Duration.ofDays(180)),
                    LotStatus.ACTIVE,
                    now);
            store.creditBalance(TENANT, account.id(), amountMinor, 0L, now);
        });
    }

    private UUID orderFor(
            UUID tenantId,
            UUID brandId,
            UUID location,
            UUID channel,
            UUID publication,
            UUID customer,
            long totalMinor,
            long feeMinor) {
        UUID orderId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID cartId = UUID.randomUUID();

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
                .param("customer", customer)
                .update();

        Map<String, Object> order = new HashMap<>();
        order.put("id", orderId);
        order.put("number", "T-" + orderId.toString().substring(0, 8));
        order.put("tenantId", tenantId);
        order.put("brandId", brandId);
        order.put("locationId", location);
        order.put("channelId", channel);
        order.put("quoteId", quoteId);
        order.put("cartId", cartId);
        order.put("publicationId", publication);
        order.put("customer", customer);
        order.put("total", totalMinor);
        order.put("fee", feeMinor);
        order.put("subtotal", totalMinor - feeMinor);
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
                    'UZS', :subtotal, 0, :fee, :total, :quoteId, 'hash', :publicationId, :cartId,
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

        UUID customer = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status,
                    identity_policy_version, version)
                VALUES (:id, :tenantId, 'ACTIVE', 1, 1)
                """).param("id", customer).param("tenantId", tenantId).update();

        return new TenantFixture(tenantId, brandId, location, channel, publication, customer);
    }

    private void seedAccrualRule(UUID tenantId, UUID brandId) {
        jdbc.sql("""
                INSERT INTO loyalty.accrual_rules (id, tenant_id, brand_id, scope_type,
                    rate_basis_points, max_accrual_minor, earn_delay_hours, lot_lifetime_days,
                    expiry_warning_days, status, version, valid_from)
                VALUES (:id, :tenantId, :brandId, 'BRAND', 300, 30000, 24, 180, 14, 'ACTIVE', 1,
                        :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("validFrom", VALID_FROM)
                .update();
    }

    private void seedRedemptionPolicy(UUID tenantId, UUID brandId) {
        jdbc.sql("""
                INSERT INTO loyalty.redemption_policies (id, tenant_id, brand_id,
                    max_share_basis_points, min_order_minor, excludes_delivery_fee,
                    allowed_channels, status, version, valid_from)
                VALUES (:id, :tenantId, :brandId, 5000, 50000, true, '{}', 'ACTIVE', 1,
                        :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("validFrom", VALID_FROM)
                .update();
    }

    private void seedPaymentMethods() {
        Instant now = clock.instant();
        clickMethod = settlementStore.registerMethod(TENANT, "CLICK", "Click", "PARTNER", false, now);
        pointsMethod = settlementStore.registerMethod(TENANT, "LOYALTY_POINTS", "Баллы", "OPERATOR", true, now);
    }
}
