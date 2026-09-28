package uz.horecaos.platform.loyalty.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.CustomerLedgerMovement;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@link JdbcLoyaltyStore#recentCustomerMovements}, the {@code CASHBACK_CHANGE}
 * candidate query {@code AutomationRulePreviewService} and {@code
 * LoyaltyBalanceChangeAutomationTrigger}'s own sibling read both depend on
 * (gap-map row X.25, ADR 0046).
 *
 * <p>Nothing exercised this query directly before this class: the join
 * against {@code loyalty.accounts} that recovers {@code customer_account_id}
 * and the tenant/brand predicates on it are exactly the kind of thing that
 * compiles, and passes every unrelated suite, while silently returning a
 * sibling brand's or a sibling tenant's customers to a marketer previewing a
 * rule.
 */
class JdbcLoyaltyStoreRecentCustomerMovementsTests {

    private static final UUID TENANT = UUID.fromString("018f9c40-a000-7000-8000-0000000000a1");
    private static final UUID OTHER_TENANT = UUID.fromString("018f9c40-a000-7000-8000-0000000000a2");
    private static final UUID BRAND = UUID.fromString("018f9c40-a000-7000-8000-0000000000b1");
    private static final UUID OTHER_BRAND = UUID.fromString("018f9c40-a000-7000-8000-0000000000b2");
    private static final UUID OTHER_TENANT_BRAND = UUID.fromString("018f9c40-a000-7000-8000-0000000000b3");

    private static final Instant NOW = Instant.parse("2026-09-20T09:00:00Z");
    private static final Instant SINCE = NOW.minus(Duration.ofDays(7));
    private static final long MINIMUM_ABS_MINOR = 1_000L;

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcLoyaltyStore store;

    private UUID inScopeCustomer;
    private UUID siblingBrandCustomer;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for loyalty store tests");
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
        store = new JdbcLoyaltyStore(jdbc);

        // loyalty.entries is append-only (V0042's own trigger refuses UPDATE
        // and DELETE, ADR 0046) -- TRUNCATE is a statement-level operation
        // that row-level trigger never sees, the same escape hatch
        // LoyaltyLedgerAndSplitTenderTests already uses between tests. It
        // takes the whole table rather than this class's own tenant slice,
        // which is safe because Surefire runs test classes serially.
        jdbc.sql("TRUNCATE TABLE loyalty.entries, loyalty.accounts CASCADE").update();
        jdbc.sql("DELETE FROM customer.customer_accounts WHERE tenant_id IN (:t, :o)")
                .param("t", TENANT)
                .param("o", OTHER_TENANT)
                .update();
        jdbc.sql("DELETE FROM tenant.brands WHERE tenant_id IN (:t, :o)")
                .param("t", TENANT)
                .param("o", OTHER_TENANT)
                .update();
        jdbc.sql("DELETE FROM tenant.tenants WHERE id IN (:t, :o)")
                .param("t", TENANT)
                .param("o", OTHER_TENANT)
                .update();

        insertTenant(TENANT, "recent-movements-tenant");
        insertTenant(OTHER_TENANT, "recent-movements-other-tenant");
        insertBrand(BRAND, TENANT, "MAIN");
        insertBrand(OTHER_BRAND, TENANT, "OTHER");
        insertBrand(OTHER_TENANT_BRAND, OTHER_TENANT, "MAIN");

        inScopeCustomer = insertCustomer(TENANT, "In scope");
        siblingBrandCustomer = insertCustomer(TENANT, "Sibling brand");
        UUID siblingTenantCustomer = insertCustomer(OTHER_TENANT, "Sibling tenant");
        UUID belowThresholdCustomer = insertCustomer(TENANT, "Below threshold");
        UUID staleCustomer = insertCustomer(TENANT, "Stale");
        UUID nonMovementCustomer = insertCustomer(TENANT, "Adjustment only");

        UUID inScopeAccount = insertAccount(TENANT, BRAND, inScopeCustomer);
        UUID siblingBrandAccount = insertAccount(TENANT, OTHER_BRAND, siblingBrandCustomer);
        UUID siblingTenantAccount = insertAccount(OTHER_TENANT, OTHER_TENANT_BRAND, siblingTenantCustomer);
        UUID belowThresholdAccount = insertAccount(TENANT, BRAND, belowThresholdCustomer);
        UUID staleAccount = insertAccount(TENANT, BRAND, staleCustomer);
        UUID nonMovementAccount = insertAccount(TENANT, BRAND, nonMovementCustomer);

        // The one movement that must come back: this tenant, this brand,
        // above threshold, inside the lookback window.
        insertEntry(TENANT, inScopeAccount, "ACCRUAL", 5_000L, NOW.minus(Duration.ofHours(1)));

        // A sibling brand under the SAME tenant -- must not leak across the
        // brand predicate even though it otherwise qualifies.
        insertEntry(TENANT, siblingBrandAccount, "ACCRUAL", 5_000L, NOW.minus(Duration.ofHours(1)));

        // A sibling tenant -- must not leak across the tenant predicate.
        insertEntry(OTHER_TENANT, siblingTenantAccount, "ACCRUAL", 5_000L, NOW.minus(Duration.ofHours(1)));

        // Below the rule's own minimumChangeMinor -- not a firing candidate,
        // so not a preview candidate either.
        insertEntry(TENANT, belowThresholdAccount, "ACCRUAL", 500L, NOW.minus(Duration.ofHours(1)));

        // Outside the lookback window.
        insertEntry(TENANT, staleAccount, "ACCRUAL", 5_000L, NOW.minus(Duration.ofDays(30)));

        // A real ledger movement, but not one of the two kinds this query
        // means by "a balance changed" for preview purposes.
        insertEntry(TENANT, nonMovementAccount, "ADJUSTMENT", 5_000L, NOW.minus(Duration.ofHours(1)));
    }

    @Test
    @DisplayName(
            "recentCustomerMovements answers only this tenant's, this brand's, in-window, over-threshold accrual/redemption movements")
    void answersOnlyForItsOwnTenantAndBrandWithinWindowAndThreshold() {
        List<CustomerLedgerMovement> movements =
                store.recentCustomerMovements(TENANT, BRAND, MINIMUM_ABS_MINOR, SINCE, 20);

        assertThat(movements)
                .as("only the in-scope customer's movement should come back -- never a sibling brand's or "
                        + "tenant's customer, never a below-threshold or stale or non-movement entry")
                .extracting(CustomerLedgerMovement::customerAccountId)
                .containsExactly(inScopeCustomer);
        assertThat(movements.get(0).amountMinor()).isEqualTo(5_000L);
    }

    @Test
    @DisplayName("recentCustomerMovements scoped to the sibling brand finds that brand's own customer -- "
            + "proving the brand predicate selects, not merely excludes")
    void theSameQueryScopedToTheSiblingBrandFindsItsOwnCustomer() {
        List<CustomerLedgerMovement> movements =
                store.recentCustomerMovements(TENANT, OTHER_BRAND, MINIMUM_ABS_MINOR, SINCE, 20);

        assertThat(movements)
                .extracting(CustomerLedgerMovement::customerAccountId)
                .containsExactly(siblingBrandCustomer);
    }

    private void insertTenant(UUID tenantId, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.tenants
                    (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, :slug, 'Legal', 'Recent movements', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", slug).update();
    }

    private void insertBrand(UUID brandId, UUID tenantId, String code) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :t, :code, :slug, :code, 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("t", tenantId)
                .param("code", code)
                .param(
                        "slug",
                        code.toLowerCase(Locale.ROOT) + "-" + brandId.toString().substring(0, 8))
                .update();
    }

    private UUID insertCustomer(UUID tenantId, String displayName) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, display_name, status, version)
                VALUES (:id, :t, :name, 'ACTIVE', 1)
                """)
                .param("id", id)
                .param("t", tenantId)
                .param("name", displayName)
                .update();
        return id;
    }

    private UUID insertAccount(UUID tenantId, UUID brandId, UUID customerAccountId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO loyalty.accounts (id, tenant_id, brand_id, customer_account_id, currency, status, version)
                VALUES (:id, :t, :b, :c, 'UZS', 'ACTIVE', 1)
                """)
                .param("id", id)
                .param("t", tenantId)
                .param("b", brandId)
                .param("c", customerAccountId)
                .update();
        return id;
    }

    private void insertEntry(UUID tenantId, UUID accountId, String entryType, long amountMinor, Instant occurredAt) {
        OffsetDateTime occurred = OffsetDateTime.ofInstant(occurredAt.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
        // Every row here carries a rule snapshot, valid for ACCRUAL (which
        // requires one) and harmless for ADJUSTMENT (which does not forbid
        // one) -- sidesteps having to bind a typed SQL NULL through JdbcClient.
        jdbc.sql("""
                INSERT INTO loyalty.entries
                    (id, tenant_id, account_id, entry_type, amount_minor, balance_after_minor,
                     rule_id, rule_version, reason_code, actor, idempotency_key, occurred_at)
                VALUES (:id, :t, :a, :type, :amount, :balanceAfter, :ruleId, :ruleVersion,
                        'TEST_MOVEMENT', 'test-fixture', :idempotencyKey, :occurredAt)
                """)
                .param("id", UUID.randomUUID())
                .param("t", tenantId)
                .param("a", accountId)
                .param("type", entryType)
                .param("amount", amountMinor)
                .param("balanceAfter", Math.max(amountMinor, 0))
                .param("ruleId", UUID.randomUUID())
                .param("ruleVersion", 1)
                .param("idempotencyKey", "seed-" + UUID.randomUUID())
                .param("occurredAt", occurred)
                .update();
    }
}
