package uz.horecaos.platform.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
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
import uz.horecaos.platform.loyalty.application.LoyaltyPolicyAuthoringService;
import uz.horecaos.platform.loyalty.application.LoyaltyPolicyAuthoringService.AccrualRuleDraft;
import uz.horecaos.platform.loyalty.application.LoyaltyPolicyAuthoringService.RedemptionPolicyDraft;
import uz.horecaos.platform.loyalty.application.LoyaltyPolicyService;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.AccrualRuleAuthoringRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.AccrualRuleRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.RedemptionPolicyAuthoringRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.RedemptionPolicyRow;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/**
 * Authoring a brand's accrual rate and redemption cap (operations §6.3
 * Loyalty, ADR 0046).
 *
 * <p>Against a real PostgreSQL, for the same reason {@code
 * LoyaltyLedgerAndSplitTenderTests} gives: the property that matters —
 * whether a brand's live set ever holds two {@code ACTIVE} rows resolving one
 * scope — is a property of the retire-then-promote transaction, and the
 * transaction is only real against a database that enforces it.
 *
 * <p>Every test here reads back through {@link LoyaltyPolicyService}, the
 * class a checkout actually asks, rather than stopping at the authoring
 * table's own {@code status} column. A status flip that never reaches the
 * resolver is exactly the shape of defect this suite exists to catch — the
 * ledger's own history is that a check on the adjacent quantity (the row
 * exists and says ACTIVE) can stay green while the one that matters (does the
 * resolver see it) is broken.
 */
class LoyaltyPolicyAuthoringTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID CHANNEL = UUID.randomUUID();

    private static final Instant NOW = Instant.parse("2026-08-24T07:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private LoyaltyPolicyAuthoringService authoring;
    private LoyaltyPolicyService resolver;

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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE loyalty.accrual_rules, loyalty.redemption_policies CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        JdbcLoyaltyStore store = new JdbcLoyaltyStore(jdbc);
        authoring = new LoyaltyPolicyAuthoringService(store, CLOCK);
        resolver = new LoyaltyPolicyService(store);

        seedTenancy();
    }

    // ------------------------------------------------------------- accrual

    @Test
    @DisplayName("a drafted rule does not accrue until it is activated")
    void draftedRuleDoesNotResolveUntilActivated() {
        UUID ruleId = authoring.draftAccrualRule(TENANT, BRAND, brandRule(500)).id();

        assertThat(resolver.accrualRule(TENANT, BRAND, LOCATION, CHANNEL, NOW))
                .as("a DRAFT is not a decision — accrual reads only ACTIVE rows")
                .isEmpty();

        authoring.activateAccrualRule(TENANT, BRAND, ruleId);

        assertThat(resolver.accrualRule(TENANT, BRAND, LOCATION, CHANNEL, NOW))
                .get()
                .extracting(AccrualRuleRow::rateBasisPoints)
                .isEqualTo(500);
    }

    @Test
    @DisplayName("activating a new BRAND rule retires the old one and the resolver sees the new rate")
    void activatingANewRuleAtTheSameScopeRetiresTheOld() {
        UUID first = authoring.draftAccrualRule(TENANT, BRAND, brandRule(300)).id();
        authoring.activateAccrualRule(TENANT, BRAND, first);

        UUID second = authoring.draftAccrualRule(TENANT, BRAND, brandRule(500)).id();
        authoring.activateAccrualRule(TENANT, BRAND, second);

        List<AccrualRuleAuthoringRow> rules = authoring.listAccrualRules(TENANT, BRAND);
        assertThat(statusOf(rules, first)).isEqualTo("RETIRED");
        assertThat(statusOf(rules, second)).isEqualTo("ACTIVE");
        assertThat(rules.stream().filter(r -> "ACTIVE".equals(r.status())).count())
                .as("a brand's live set holds exactly one ACTIVE rule per scope, never two")
                .isEqualTo(1);
        assertThat(resolver.accrualRule(TENANT, BRAND, LOCATION, CHANNEL, NOW))
                .get()
                .extracting(AccrualRuleRow::rateBasisPoints)
                .isEqualTo(500);
    }

    @Test
    @DisplayName("retiring the live rule stops accrual outright, it does not fall back to a default")
    void retiringTheLiveRuleStopsAccrual() {
        UUID ruleId = authoring.draftAccrualRule(TENANT, BRAND, brandRule(300)).id();
        authoring.activateAccrualRule(TENANT, BRAND, ruleId);
        assertThat(resolver.accrualRule(TENANT, BRAND, LOCATION, CHANNEL, NOW)).isPresent();

        authoring.retireAccrualRule(TENANT, BRAND, ruleId);

        assertThat(resolver.accrualRule(TENANT, BRAND, LOCATION, CHANNEL, NOW))
                .as("a brand with no active rule does not accrue (ADR 0046) — retiring the only "
                        + "rule must not resolve to some other row")
                .isEmpty();
    }

    @Test
    @DisplayName("promoting a second BRAND rule never disturbs an unrelated LOCATION rule")
    void activatingABrandScopeRuleDoesNotDisturbAnUnrelatedLocationScopeRule() {
        UUID locationRuleId = authoring
                .draftAccrualRule(
                        TENANT, BRAND, new AccrualRuleDraft("LOCATION", LOCATION, 700, null, 24, 180, 14, null, null))
                .id();
        authoring.activateAccrualRule(TENANT, BRAND, locationRuleId);

        UUID brandRuleA =
                authoring.draftAccrualRule(TENANT, BRAND, brandRule(300)).id();
        authoring.activateAccrualRule(TENANT, BRAND, brandRuleA);

        UUID brandRuleB =
                authoring.draftAccrualRule(TENANT, BRAND, brandRule(500)).id();
        authoring.activateAccrualRule(TENANT, BRAND, brandRuleB);

        List<AccrualRuleAuthoringRow> rules = authoring.listAccrualRules(TENANT, BRAND);
        assertThat(statusOf(rules, locationRuleId))
                .as("activating a second BRAND-scope rule must retire only its BRAND-scope "
                        + "sibling — a scope match that used brandId alone, without scope_type "
                        + "and scope_id, would retire this LOCATION rule too")
                .isEqualTo("ACTIVE");
        assertThat(statusOf(rules, brandRuleA)).isEqualTo("RETIRED");
        assertThat(statusOf(rules, brandRuleB)).isEqualTo("ACTIVE");

        // Narrowest-first resolution still prefers the untouched LOCATION rule.
        assertThat(resolver.accrualRule(TENANT, BRAND, LOCATION, CHANNEL, NOW))
                .get()
                .extracting(AccrualRuleRow::rateBasisPoints)
                .isEqualTo(700);
    }

    @Test
    @DisplayName("activating a rule at one brand never touches another brand's rule")
    void activatingARuleAtOneBrandNeverTouchesAnotherBrandsRule() {
        UUID otherBrandRule =
                authoring.draftAccrualRule(TENANT, OTHER_BRAND, brandRule(900)).id();
        authoring.activateAccrualRule(TENANT, OTHER_BRAND, otherBrandRule);

        UUID id = authoring.draftAccrualRule(TENANT, BRAND, brandRule(300)).id();
        authoring.activateAccrualRule(TENANT, BRAND, id);

        assertThat(statusOf(authoring.listAccrualRules(TENANT, OTHER_BRAND), otherBrandRule))
                .isEqualTo("ACTIVE");
        assertThat(resolver.accrualRule(TENANT, OTHER_BRAND, LOCATION, CHANNEL, NOW))
                .get()
                .extracting(AccrualRuleRow::rateBasisPoints)
                .isEqualTo(900);
    }

    @Test
    @DisplayName("only a DRAFT can be activated")
    void onlyADraftCanBeActivated() {
        UUID id = authoring.draftAccrualRule(TENANT, BRAND, brandRule(300)).id();
        authoring.activateAccrualRule(TENANT, BRAND, id);

        assertThatThrownBy(() -> authoring.activateAccrualRule(TENANT, BRAND, id))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("DRAFT");
    }

    @Test
    @DisplayName("activating or retiring an unknown rule is refused, not silently ignored")
    void unknownRuleIsRefused() {
        UUID unknown = UUID.randomUUID();
        assertThatThrownBy(() -> authoring.activateAccrualRule(TENANT, BRAND, unknown))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> authoring.retireAccrualRule(TENANT, BRAND, unknown))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("the expiry warning must fall strictly inside the lot's own lifetime")
    void expiryWarningMustBeShorterThanLotLifetime() {
        assertThatThrownBy(() -> authoring.draftAccrualRule(
                        TENANT, BRAND, new AccrualRuleDraft("BRAND", null, 300, null, 24, 180, 180, null, null)))
                .isInstanceOf(ApiException.class);

        assertThat(authoring.listAccrualRules(TENANT, BRAND))
                .as("a refused draft must not land a row — nothing to clean up, nothing to retire later")
                .isEmpty();
    }

    @Test
    @DisplayName("a BRAND-scope rule cannot also name a scopeId")
    void aBrandScopeRuleCannotNameAScopeId() {
        assertThatThrownBy(() -> authoring.draftAccrualRule(
                        TENANT, BRAND, new AccrualRuleDraft("BRAND", LOCATION, 300, null, 24, 180, 14, null, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a LOCATION-scope rule must name a scopeId")
    void aLocationScopeRuleMustNameAScopeId() {
        assertThatThrownBy(() -> authoring.draftAccrualRule(
                        TENANT, BRAND, new AccrualRuleDraft("LOCATION", null, 300, null, 24, 180, 14, null, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName(
            "scopeType is one of BRAND, LOCATION or CHANNEL — nothing else, even bypassing the controller's own pattern check")
    void scopeTypeMustBeOneOfTheThreeKnownValues() {
        assertThatThrownBy(() -> authoring.draftAccrualRule(
                        TENANT, BRAND, new AccrualRuleDraft("TENANT", null, 300, null, 24, 180, 14, null, null)))
                .isInstanceOf(ApiException.class);
        assertThat(authoring.listAccrualRules(TENANT, BRAND)).isEmpty();
    }

    @Test
    @DisplayName("the rate is a basis-point share of the order, capped at 100%")
    void rateBasisPointsIsBounded() {
        assertThatThrownBy(() -> authoring.draftAccrualRule(
                        TENANT, BRAND, new AccrualRuleDraft("BRAND", null, -1, null, 24, 180, 14, null, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> authoring.draftAccrualRule(
                        TENANT, BRAND, new AccrualRuleDraft("BRAND", null, 10_001, null, 24, 180, 14, null, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a stated cap must be a positive amount — null is the way to say uncapped")
    void maxAccrualMustBePositiveWhenStated() {
        assertThatThrownBy(() -> authoring.draftAccrualRule(
                        TENANT, BRAND, new AccrualRuleDraft("BRAND", null, 300, 0L, 24, 180, 14, null, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> authoring.draftAccrualRule(
                        TENANT, BRAND, new AccrualRuleDraft("BRAND", null, 300, -1L, 24, 180, 14, null, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("the earn delay cannot be negative")
    void earnDelayCannotBeNegative() {
        assertThatThrownBy(() -> authoring.draftAccrualRule(
                        TENANT, BRAND, new AccrualRuleDraft("BRAND", null, 300, null, -1, 180, 14, null, null)))
                .isInstanceOf(ApiException.class);
    }

    // --------------------------------------------------------- redemption

    @Test
    @DisplayName("redemption policy lifecycle: draft is inert, activation resolves, retirement stops it")
    void redemptionPolicyLifecycle() {
        UUID first = authoring
                .draftRedemptionPolicy(TENANT, BRAND, redemptionDraft(5_000))
                .id();
        assertThat(resolver.redemptionPolicy(TENANT, BRAND, NOW)).isEmpty();

        authoring.activateRedemptionPolicy(TENANT, BRAND, first);
        assertThat(resolver.redemptionPolicy(TENANT, BRAND, NOW))
                .get()
                .extracting(RedemptionPolicyRow::maxShareBasisPoints)
                .isEqualTo(5_000);

        UUID second = authoring
                .draftRedemptionPolicy(TENANT, BRAND, redemptionDraft(3_000))
                .id();
        authoring.activateRedemptionPolicy(TENANT, BRAND, second);

        List<RedemptionPolicyAuthoringRow> policies = authoring.listRedemptionPolicies(TENANT, BRAND);
        assertThat(policyStatusOf(policies, first)).isEqualTo("RETIRED");
        assertThat(resolver.redemptionPolicy(TENANT, BRAND, NOW))
                .get()
                .extracting(RedemptionPolicyRow::maxShareBasisPoints)
                .isEqualTo(3_000);

        authoring.retireRedemptionPolicy(TENANT, BRAND, second);
        assertThat(resolver.redemptionPolicy(TENANT, BRAND, NOW))
                .as("a brand with no active policy does not redeem")
                .isEmpty();
    }

    @Test
    @DisplayName("points may never be offered to cover a whole order")
    void redemptionShareIsCappedBelowTheWholeOrder() {
        assertThatThrownBy(() -> authoring.draftRedemptionPolicy(
                        TENANT, BRAND, new RedemptionPolicyDraft(10_000, 0, true, List.of(), null, null)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> authoring.draftRedemptionPolicy(
                        TENANT, BRAND, new RedemptionPolicyDraft(0, 0, true, List.of(), null, null)))
                .as("zero is not a share; a brand that redeems nothing simply has no active policy")
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("the minimum order to redeem against cannot be negative")
    void minOrderCannotBeNegative() {
        assertThatThrownBy(() -> authoring.draftRedemptionPolicy(
                        TENANT, BRAND, new RedemptionPolicyDraft(5_000, -1, true, List.of(), null, null)))
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a redemption policy authored for one brand never resolves for another")
    void redemptionPolicyIsBrandScoped() {
        UUID id = authoring
                .draftRedemptionPolicy(TENANT, BRAND, redemptionDraft(5_000))
                .id();
        authoring.activateRedemptionPolicy(TENANT, BRAND, id);

        assertThat(resolver.redemptionPolicy(TENANT, OTHER_BRAND, NOW))
                .as("a brand's redemption cap must never leak to a brand nobody authored it for")
                .isEmpty();
    }

    // ---------------------------------------------------------------- fixtures

    private static AccrualRuleDraft brandRule(int rateBasisPoints) {
        return new AccrualRuleDraft("BRAND", null, rateBasisPoints, 30_000L, 24, 180, 14, null, null);
    }

    private static RedemptionPolicyDraft redemptionDraft(int maxShareBasisPoints) {
        return new RedemptionPolicyDraft(maxShareBasisPoints, 50_000L, true, List.of(), null, null);
    }

    private static String statusOf(List<AccrualRuleAuthoringRow> rows, UUID id) {
        return rows.stream()
                .filter(row -> row.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No such rule: " + id))
                .status();
    }

    private static String policyStatusOf(List<RedemptionPolicyAuthoringRow> rows, UUID id) {
        return rows.stream()
                .filter(row -> row.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No such policy: " + id))
                .status();
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'loyalty-policy-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", TENANT).update();

        insertBrand(BRAND, "MAIN", "main");
        insertBrand(OTHER_BRAND, "SECOND", "second");
    }

    private void insertBrand(UUID id, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, :code, 'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("code", code)
                .param("slug", slug)
                .update();
    }
}
