package uz.horecaos.platform.commercial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcApprovalService;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.commercial.application.WalletService.WalletChangeOutcome;
import uz.horecaos.platform.commercial.domain.PaymentMethod;
import uz.horecaos.platform.commercial.domain.PlanTerms;
import uz.horecaos.platform.commercial.domain.Statement;
import uz.horecaos.platform.commercial.domain.StatementLine;
import uz.horecaos.platform.commercial.domain.StatementPayment;
import uz.horecaos.platform.commercial.domain.Subscription;
import uz.horecaos.platform.commercial.domain.SubscriptionStatus;
import uz.horecaos.platform.commercial.domain.WalletBalances;
import uz.horecaos.platform.commercial.domain.WalletEntry;
import uz.horecaos.platform.commercial.infrastructure.NotConfiguredCardCharger;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcCardChargeAttemptStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcModuleStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcPlanStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcStatementStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcSubscriptionStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcUsageStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcWalletStore;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationResolver;
import uz.horecaos.platform.web.api.ApiException;

/**
 * ADR 0095 against PostgreSQL: a tenant's wallet keeps money it paid apart
 * from money HorecaOS granted it, a statement is paid from it bonus first,
 * later money pays the oldest open statement, and nothing a person types
 * moves until a second person signs it.
 *
 * <p>Balances here are checked against the ledger's own SUM rather than
 * against the number the service last reported, because a balance that
 * agrees with itself is exactly the defect CLAUDE.md records the loyalty
 * ledger dying of.
 */
class WalletTests {

    private static final UUID PILOT = UUID.fromString("018f6f4e-2100-7000-8000-0000000000d1");

    /**
     * A second tenant, funded and granted in its own right, so every query's
     * {@code tenant_id} predicate is under test rather than merely present.
     *
     * <p>A suite with one tenant cannot tell a balance scoped to its owner from
     * one summed over the estate: drop the predicate from {@code paidBalance}
     * and a single-tenant fixture agrees with itself all the way down. RIVAL is
     * deliberately the richer of the two, so a lost predicate makes PILOT spend
     * money it never had rather than merely reading a larger number.
     */
    private static final UUID RIVAL = UUID.fromString("018f6f4e-2100-7000-8000-0000000000d2");

    /**
     * Every fixture's clock starts here, and not a day earlier: V0211 seeds the
     * three wallet approval policies valid from 2026-09-11, and a maker-checker
     * action before a policy is valid is refused as unconfigured rather than
     * sent for a signature. A suite dated before its own migration proves
     * nothing about the change it is testing.
     */
    private static final Instant START = Instant.parse("2026-09-15T09:00:00Z");

    /** After both September and October have ended, so either month can be closed. */
    private static final Instant CLOSE = Instant.parse("2026-11-05T09:00:00Z");

    private static final ActorRef MAKER = ActorRef.user("finance-1", null);
    private static final ActorRef CHECKER = ActorRef.user("finance-2", null);

    /** What the plan these fixtures sell costs a month, in UZS minor units. */
    private static final long MONTHLY = 1_200_000L;

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private TransactionTemplate transactions;
    private MovableClock clock;
    private JdbcApprovalService approvals;
    private PlanCatalogService plans;
    private SubscriptionService subscriptions;
    private StatementService statements;
    private WalletService wallet;
    private WalletBonusExpirySweeper sweeper;
    private SimpleMeterRegistry meters;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration tests");
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
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        // Not TRUNCATE tenant.tenants CASCADE: that reaches audit.approval_policies
        // and would take the three wallet policies V0211 seeds with it — and
        // whether a correction needs a second person at all is what those decide.
        jdbc.sql("TRUNCATE TABLE audit.approval_requests CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("""
                TRUNCATE TABLE commercial.wallet_entries, commercial.tenant_billing,
                    commercial.card_charge_attempts,
                    commercial.statement_lines, commercial.statements, commercial.subscriptions,
                    commercial.usage_events, commercial.usage_aggregates, commercial.usage_adjustments,
                    commercial.entitlement_overrides, commercial.tenant_modules, commercial.modules,
                    commercial.plan_entitlements, commercial.plan_versions, commercial.plans CASCADE
                """).update();
        jdbc.sql("DELETE FROM tenant.tenants WHERE id IN (:pilot, :rival)")
                .param("pilot", PILOT)
                .param("rival", RIVAL)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'pilot', 'Non uyi', 'Non uyi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", PILOT).update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'rival', 'Choyxona', 'Choyxona', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", RIVAL).update();

        clock = new MovableClock(START);
        JdbcAuditRecorder audit =
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build());
        JdbcPlanStore planStore = new JdbcPlanStore(jdbc);
        JdbcSubscriptionStore subscriptionStore = new JdbcSubscriptionStore(jdbc);
        JdbcUsageStore usageStore =
                new JdbcUsageStore(jdbc, JsonMapper.builder().build());
        JdbcModuleStore moduleStore = new JdbcModuleStore(jdbc);
        JdbcStatementStore statementStore = new JdbcStatementStore(jdbc);
        JdbcWalletStore walletStore = new JdbcWalletStore(jdbc);

        approvals = new JdbcApprovalService(
                jdbc,
                audit,
                clock,
                new SimpleMeterRegistry(),
                JsonMapper.builder().build());
        meters = new SimpleMeterRegistry();
        wallet = new WalletService(
                walletStore,
                subscriptionStore,
                new JdbcCardChargeAttemptStore(jdbc),
                approvals,
                new NotConfiguredCardCharger(),
                audit,
                meters,
                transactions,
                clock);
        EntitlementQueryService entitlements = new EntitlementQueryService(
                subscriptionStore,
                planStore,
                usageStore,
                moduleStore,
                new EnforcementCeiling(new JdbcConfigurationResolver(jdbc)),
                clock);
        plans = new PlanCatalogService(planStore, audit, clock);
        subscriptions = new SubscriptionService(subscriptionStore, planStore, entitlements, audit, clock);
        statements = new StatementService(
                subscriptionStore, planStore, moduleStore, statementStore, usageStore, wallet, audit, clock);
        sweeper = new WalletBonusExpirySweeper(wallet, clock, 200);
    }

    // ------------------------------------------------------------- balances

    @Test
    void eachBalanceIsTheSumOfItsOwnEntriesAndNoColumnHoldsOne() {
        recordTransfer(1_000_000, "MT103-1");
        grantBonus(200_000, START.plus(Duration.ofDays(60)));

        WalletBalances balances = wallet.balances(PILOT);

        assertThat(balances.paidMinor())
                .as("the balance minus the SUM of the entries is the difference that broke the loyalty ledger")
                .isEqualTo(ledgerSum(WalletEntry.PAID));
        assertThat(balances.bonusMinor()).isEqualTo(ledgerSum(WalletEntry.BONUS));
        assertThat(balances.paidMinor()).isEqualTo(1_000_000);
        assertThat(balances.bonusMinor()).isEqualTo(200_000);
        assertThat(balances.currency()).isEqualTo("UZS");

        assertThat(jdbc.sql("""
                        SELECT column_name FROM information_schema.columns
                         WHERE table_schema = 'commercial' AND table_name IN ('wallet_entries', 'tenant_billing')
                           AND column_name LIKE '%balance%'
                        """).query(String.class).list())
                .as("a balance stored beside the entries is the one thing ADR 0095 forbids outright")
                .isEmpty();
    }

    @Test
    void theBonusBalanceSaysWhatIsSpendableAsWellAsWhatTheLedgerHolds() {
        UUID expiring = grantBonus(200_000, Instant.parse("2026-12-01T00:00:00Z"));
        grantBonus(150_000, Instant.parse("2027-03-01T00:00:00Z"));

        assertThat(wallet.spendableBonusMinor(PILOT))
                .as("while both grants live, everything in the ledger is spendable")
                .isEqualTo(350_000)
                .isEqualTo(wallet.balances(PILOT).bonusMinor());

        // Past the first grant's expiry and before the hourly sweep reaches it.
        clock.set(Instant.parse("2026-12-01T00:00:01Z"));

        assertThat(wallet.balances(PILOT).bonusMinor())
                .as("the ledger balance has no clock in it, and must not grow one")
                .isEqualTo(350_000)
                .isEqualTo(ledgerSum(WalletEntry.BONUS));
        assertThat(wallet.spendableBonusMinor(PILOT))
                .as("but a statement issued in this window can draw on the living grant only")
                .isEqualTo(150_000);
        assertThat(grantRemaining(expiring))
                .as("the lapsed grant still holds its remainder until the sweep writes the lapse")
                .isEqualTo(200_000);
    }

    @Test
    void aTransferIsRecordedOnceHoweverOftenItIsTyped() {
        recordTransfer(1_000_000, "MT103-1");

        assertThatThrownBy(() -> recordTransfer(1_000_000, "MT103-1"))
                .as("the same bank reference twice is one transfer recorded twice, not two transfers")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already recorded");
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(1_000_000);

        recordTransfer(1_000_000, "MT103-2");

        assertThat(wallet.balances(PILOT).paidMinor())
                .as("a different transfer of the same size is a different transfer")
                .isEqualTo(2_000_000);
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(ledgerSum(WalletEntry.PAID));
        assertThat(auditedActions())
                .as("two transfers were recorded and audited, and the refused one left nothing behind")
                .containsExactly("commercial.wallet.transfer_recorded", "commercial.wallet.transfer_recorded");
    }

    @Test
    void oneTransferTypedTwoWaysIsStillOneTransfer() {
        recordTransfer(1_000_000, "MT103-7");

        assertThatThrownBy(() -> recordTransfer(1_000_000, " mt103 7"))
                .as("two people reconciling the same statement type the same reference differently; "
                        + "an index on what they typed would credit the tenant twice")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already recorded");
        assertThatThrownBy(() -> recordTransfer(1_000_000, "#MT1037"))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already recorded");

        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(1_000_000);
        assertThat(wallet.ledger(PILOT, null, 10))
                .singleElement()
                .satisfies(entry -> assertThat(entry.externalReference())
                        .as("and the ledger keeps verbatim what the recorder actually typed")
                        .isEqualTo("MT103-7"));

        assertThatThrownBy(() -> recordTransfer(1_000_000, " - "))
                .as("a reference that normalises to nothing would collide with every other such record")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("bank's reference");

        recordTransfer(1_000_000, "MT1037-A");
        assertThat(wallet.balances(PILOT).paidMinor())
                .as("normalising narrows the hole; a genuinely different reference is a different transfer")
                .isEqualTo(2_000_000);
    }

    // ------------------------------------------------------------ settlement

    @Test
    void aStatementIsPaidFromBonusBeforePaidMoneyAndFromTheGrantExpiringSoonestFirst() {
        startOnPlan(START, MONTHLY);
        UUID later = grantBonus(900_000, Instant.parse("2027-02-01T00:00:00Z"));
        UUID sooner = grantBonus(800_000, Instant.parse("2027-01-01T00:00:00Z"));
        recordTransfer(1_000_000, "MT103-2");
        clock.set(CLOSE);

        inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September close", "corr"));

        assertThat(statementPayment("2026-09").dueMinor()).isZero();
        assertThat(grantRemaining(sooner))
                .as("the grant expiring soonest is emptied before the other is touched")
                .isZero();
        assertThat(grantRemaining(later))
                .as("which leaves the later grant short by exactly what the first could not cover")
                .isEqualTo(900_000 - (MONTHLY - 800_000));
        assertThat(wallet.balances(PILOT).paidMinor())
                .as("paid money is not touched while any bonus is left")
                .isEqualTo(1_000_000);

        assertThat(spendsOn("2026-09"))
                .as("one spend entry per grant drawn from, each naming its grant")
                .containsExactlyInAnyOrder(
                        new Spend(WalletEntry.BONUS, sooner, -800_000L),
                        new Spend(WalletEntry.BONUS, later, -(MONTHLY - 800_000)));
    }

    /** ADR 0095's own exit criterion, in its own numbers. */
    @Test
    void aTenantGranted200000AndPaying1000000HasA1200000StatementPaidInFull() {
        startOnPlan(START, MONTHLY);
        grantBonus(200_000, Instant.parse("2027-01-01T00:00:00Z"));
        recordTransfer(1_000_000, "MT103-EXIT");
        clock.set(CLOSE);

        inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September close", "corr"));

        StatementPayment september = statementPayment("2026-09");
        assertThat(september.totalMinor()).isEqualTo(MONTHLY);
        assertThat(september.paidMinor()).isEqualTo(MONTHLY);
        assertThat(september.dueMinor()).isZero();
        assertThat(wallet.balances(PILOT).bonusMinor())
                .as("the bonus went first and all of it went")
                .isZero();
        assertThat(wallet.balances(PILOT).paidMinor()).isZero();
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(ledgerSum(WalletEntry.PAID));
        assertThat(wallet.balances(PILOT).bonusMinor()).isEqualTo(ledgerSum(WalletEntry.BONUS));
    }

    @Test
    void moneyArrivingLaterPaysTheOldestOpenStatementFirst() {
        startOnPlan(START, MONTHLY);
        clock.set(CLOSE);
        inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September close", "corr"));
        inTx(() -> statements.issue(PILOT, "2026-10", MAKER, "October close", "corr"));
        assertThat(statementPayment("2026-09").dueMinor()).isEqualTo(MONTHLY);

        recordTransfer(1_500_000, "MT103-3");

        assertThat(statementPayment("2026-09").paidMinor())
                .as("the oldest open statement is filled before the next one is touched")
                .isEqualTo(MONTHLY);
        assertThat(statementPayment("2026-09").dueMinor()).isZero();
        assertThat(statementPayment("2026-10").paidMinor()).isEqualTo(300_000);
        assertThat(statementPayment("2026-10").dueMinor()).isEqualTo(900_000);
        assertThat(wallet.balances(PILOT).paidMinor())
                .as("the wallet keeps nothing back while a statement is open")
                .isZero();
    }

    @Test
    void voidingAStatementGivesBackWhatItDrewAndThatMoneyPaysWhatIsStillOpen() {
        startOnPlan(START, MONTHLY);
        UUID grant = grantBonus(400_000, Instant.parse("2027-01-01T00:00:00Z"));
        clock.set(CLOSE);
        UUID september = inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September close", "corr"))
                .statementId();
        inTx(() -> statements.issue(PILOT, "2026-10", MAKER, "October close", "corr"));
        assertThat(grantRemaining(grant)).as("September drew the whole grant").isZero();
        assertThat(statementPayment("2026-10").paidMinor()).isZero();

        inTxDo(() -> statements.voidStatement(PILOT, september, MAKER, "the plan was wrong", "corr"));

        assertThat(reversalsFor(grant))
                .as("what a voided statement drew goes back to the grant it came from")
                .isEqualTo(1L);
        assertThat(wallet.balances(PILOT).bonusMinor()).isEqualTo(ledgerSum(WalletEntry.BONUS));
        assertThat(statementPayment("2026-10").paidMinor())
                .as("and is then free to pay what is still open")
                .isEqualTo(400_000);
        assertThat(grantRemaining(grant)).isZero();
        assertThat(reversalRowsOn(september))
                .as("what came back is the amount the statement drew, not merely a row that it happened")
                .containsExactly(new Spend(WalletEntry.BONUS, grant, 400_000L));
    }

    @Test
    void voidingAStatementPaidFromPaidMoneyGivesThatMoneyBack() {
        startOnPlan(START, MONTHLY);
        recordTransfer(MONTHLY, "MT103-VOID");
        clock.set(CLOSE);
        UUID september = inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September close", "corr"))
                .statementId();
        assertThat(statementPayment("2026-09").dueMinor()).isZero();
        assertThat(wallet.balances(PILOT).paidMinor())
                .as("the whole transfer went to September")
                .isZero();

        inTxDo(() -> statements.voidStatement(PILOT, september, MAKER, "the plan was wrong", "corr"));

        assertThat(reversalRowsOn(september))
                .as("a reversal of paid money names no grant: grant_id is bonus money's column alone")
                .containsExactly(new Spend(WalletEntry.PAID, null, MONTHLY));
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(MONTHLY);
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(ledgerSum(WalletEntry.PAID));

        inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September, corrected", "corr"));

        assertThat(statementPayment("2026-09").dueMinor())
                .as("the month the tenant already paid for is not asked for twice")
                .isZero();
    }

    @Test
    void aWalletPaysOnlyStatementsInItsOwnCurrency() {
        // PILOT's wallet is UZS and its plan version is priced in USD, which
        // ADR 0095 permits: such a version's statements are invoiced, because
        // there is no rate at which som could settle a dollar statement. RIVAL
        // is the mirror -- a UZS wallet and a UZS plan -- so this test shows the
        // currency filter selecting, not settlement being switched off.
        clock.set(START);
        UUID dollars = activePlan("FOREIGN", "USD", new PlanTerms(null, 0, Map.of()), MONTHLY);
        UUID som = activePlan("BASIC", "UZS", new PlanTerms(null, 0, Map.of()), MONTHLY);
        inTx(() -> subscriptions.start(PILOT, dollars, null, MAKER, "the pilot", "corr"));
        inTx(() -> subscriptions.start(RIVAL, som, null, MAKER, "the other one", "corr"));
        clock.set(CLOSE);
        inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September close", "corr"));
        inTx(() -> statements.issue(RIVAL, "2026-09", MAKER, "September close", "corr"));

        recordTransfer(PILOT, MONTHLY, "MT103-FX");
        recordTransfer(RIVAL, MONTHLY, "MT103-SOM");

        assertThat(statementPayment(PILOT, "2026-09").currency()).isEqualTo("USD");
        assertThat(statementPayment(PILOT, "2026-09").paidMinor())
                .as("a UZS wallet cannot settle a USD statement at a rate nobody set")
                .isZero();
        assertThat(statementPayment(PILOT, "2026-09").dueMinor()).isEqualTo(MONTHLY);
        assertThat(spendsOn(PILOT, "2026-09"))
                .as("and no entry of any kind names that statement")
                .isEmpty();
        assertThat(wallet.balances(PILOT).paidMinor())
                .as("the money stays in the wallet, to be refunded or spent on a statement it can pay")
                .isEqualTo(MONTHLY)
                .isEqualTo(ledgerSum(PILOT, WalletEntry.PAID));

        assertThat(statementPayment(RIVAL, "2026-09").dueMinor())
                .as("the same pass pays the statement that is in the wallet's own currency")
                .isZero();
        assertThat(wallet.balances(RIVAL).paidMinor()).isZero();
    }

    @Test
    void oneTenantsMoneyNeverPaysAnothersStatement() {
        clock.set(START);
        UUID som = activePlan("BASIC", "UZS", new PlanTerms(null, 0, Map.of()), MONTHLY);
        inTx(() -> subscriptions.start(PILOT, som, null, MAKER, "the pilot", "corr"));
        UUID rivalGrant = grantBonus(RIVAL, 900_000, Instant.parse("2027-01-01T00:00:00Z"));
        recordTransfer(RIVAL, 5_000_000, "MT103-RIVAL");
        recordTransfer(PILOT, 100_000, "MT103-PILOT");
        clock.set(CLOSE);

        inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September close", "corr"));

        assertThat(statementPayment(PILOT, "2026-09").paidMinor())
                .as("PILOT pays what PILOT has, and RIVAL's five million is not PILOT's")
                .isEqualTo(100_000);
        assertThat(statementPayment(PILOT, "2026-09").dueMinor()).isEqualTo(MONTHLY - 100_000);
        assertThat(wallet.balances(PILOT).paidMinor())
                .as("a balance that went negative would mean the wallet spent money it never held")
                .isZero();
        assertThat(spendsOn(PILOT, "2026-09"))
                .as("nor does PILOT draw on a grant of RIVAL's")
                .containsExactly(new Spend(WalletEntry.PAID, null, -100_000L));

        assertThat(wallet.balances(RIVAL).paidMinor())
                .as("and RIVAL's own wallet is untouched by any of it")
                .isEqualTo(5_000_000)
                .isEqualTo(ledgerSum(RIVAL, WalletEntry.PAID));
        assertThat(grantRemaining(RIVAL, rivalGrant)).isEqualTo(900_000);

        assertThatThrownBy(() -> inTx(() ->
                        wallet.proposeAdjustment(PILOT, WalletEntry.BONUS, rivalGrant, -10_000, MAKER, "x", "corr")))
                .as("another tenant's grant id is an unknown grant, not a correctable one")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no such bonus grant");

        assertThatThrownBy(() -> applyChange(
                        () -> wallet.proposeRefund(PILOT, 3_000_000, "PAYOUT-X", MAKER, "the tenant left", "corr")))
                .as("and a refund is measured against this tenant's paid balance, not the estate's")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("below zero");
    }

    // ------------------------------------------------------------ two people

    @Test
    void aCorrectionMovesNothingUntilADifferentPersonApprovesIt() {
        WalletChangeOutcome first = inTx(() ->
                wallet.proposeAdjustment(PILOT, WalletEntry.PAID, null, 50_000, MAKER, "a misposted transfer", "corr"));

        assertThat(first.status()).isEqualTo(WalletChangeOutcome.AWAITING_APPROVAL);
        assertThat(wallet.balances(PILOT).paidMinor())
                .as("nothing moves on one signature")
                .isZero();
        assertThat(ledgerSize()).isZero();

        UUID requestId = Objects.requireNonNull(first.approvalRequestId());
        assertThatThrownBy(() ->
                        inTxDo(() -> approvals.decide(requestId, ApprovalService.Decision.APPROVE, MAKER, "mine")))
                .as("the person who proposed it is not the second person")
                .isInstanceOf(ApprovalService.SelfApprovalException.class);
        assertThat(wallet.balances(PILOT).paidMinor()).isZero();

        inTxDo(() -> approvals.decide(requestId, ApprovalService.Decision.APPROVE, CHECKER, "checked the bank"));
        WalletChangeOutcome second = inTx(() ->
                wallet.proposeAdjustment(PILOT, WalletEntry.PAID, null, 50_000, MAKER, "a misposted transfer", "corr"));

        assertThat(second.status()).isEqualTo(WalletChangeOutcome.CHANGED);
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(50_000);
        assertThat(wallet.ledger(PILOT, null, 10)).singleElement().satisfies(entry -> {
            assertThat(entry.entryType()).isEqualTo(WalletEntry.ADJUSTMENT);
            assertThat(entry.recordedBy()).isEqualTo("finance-1");
            assertThat(entry.approvedBy()).isEqualTo("finance-2");
            assertThat(entry.approvalRequestId()).isEqualTo(requestId);
        });
        assertThat(requestStatus(requestId))
                .as("the signature is spent by the entry it authorised")
                .isEqualTo("CONSUMED");
    }

    @Test
    void aCheckerWhoExecutesTheChangeThemselvesStillLeavesTwoNamesOnTheRow() {
        recordTransfer(100_000, "MT103-CHK");
        WalletChangeOutcome first =
                inTx(() -> wallet.proposeRefund(PILOT, 100_000, "PAYOUT-CHK", MAKER, "the tenant left", "corr"));
        UUID requestId = Objects.requireNonNull(first.approvalRequestId());
        inTxDo(() -> approvals.decide(requestId, ApprovalService.Decision.APPROVE, CHECKER, "checked the bank"));

        // The checker, not the maker, makes the identical second call. V0071
        // permits that -- four eyes governs who decides, not who executes -- and
        // what it must not produce is a ledger row a finance reviewer reading it
        // alone would have to take as one person recording and approving a payout.
        WalletChangeOutcome second =
                inTx(() -> wallet.proposeRefund(PILOT, 100_000, "PAYOUT-CHK", CHECKER, "the tenant left", "corr"));

        assertThat(second.status()).isEqualTo(WalletChangeOutcome.CHANGED);
        assertThat(wallet.ledger(PILOT, null, 10))
                .filteredOn(entry -> WalletEntry.REFUND.equals(entry.entryType()))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.recordedBy())
                            .as("the row names the person who proposed it, whoever finally pressed the button")
                            .isEqualTo("finance-1");
                    assertThat(entry.approvedBy()).isEqualTo("finance-2");
                });
        assertThat(jdbc.sql("SELECT consumed_by FROM audit.approval_requests WHERE id = :id")
                        .param("id", requestId)
                        .query(String.class)
                        .single())
                .as("who executed is not lost; the approval records it")
                .isEqualTo("finance-2");
    }

    @Test
    void aCorrectionCannotBeMadeAgainstAGrantThatHasAlreadyLapsed() {
        UUID grant = grantBonus(100_000, Instant.parse("2026-12-01T00:00:00Z"));
        clock.set(Instant.parse("2026-12-02T00:00:00Z"));
        assertThat(sweeper.runOnce()).isEqualTo(1);
        long entries = ledgerSize();

        assertThatThrownBy(() -> inTx(() ->
                        wallet.proposeAdjustment(PILOT, WalletEntry.BONUS, grant, 100_000, MAKER, "goodwill", "corr")))
                .as("credit added to a dead grant is a balance no statement could ever draw on, "
                        + "and the next sweep would take it away again with nobody proposing that")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("has lapsed");
        assertThatThrownBy(() -> inTx(() ->
                        wallet.proposeAdjustment(PILOT, WalletEntry.BONUS, grant, -10_000, MAKER, "typo", "corr")))
                .as("and the refusal is about the grant being dead, not about the arithmetic")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("has lapsed");

        assertThat(ledgerSize())
                .as("a refused correction leaves nothing behind")
                .isEqualTo(entries);
        assertThat(auditedActions())
                .as("nor does it cost a signature: the refusal happens before the request is raised")
                .doesNotContain("commercial.wallet.adjusted");
        assertThat(grantRemaining(grant)).isZero();
        assertThat(wallet.balances(PILOT).bonusMinor()).isZero();
    }

    @Test
    void aCorrectionOfBonusMoneyNamesItsGrantAndNeitherKindGoesBelowZero() {
        UUID grant = grantBonus(100_000, START.plus(Duration.ofDays(30)));

        assertThatThrownBy(() -> inTx(
                        () -> wallet.proposeAdjustment(PILOT, WalletEntry.BONUS, null, -10_000, MAKER, "typo", "corr")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("names the grant");
        assertThatThrownBy(() -> inTx(
                        () -> wallet.proposeAdjustment(PILOT, WalletEntry.PAID, grant, -10_000, MAKER, "typo", "corr")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("names no grant");

        assertThatThrownBy(() -> applyChange(() ->
                        wallet.proposeAdjustment(PILOT, WalletEntry.BONUS, grant, -150_000, MAKER, "too far", "corr")))
                .as("a correction cannot take a grant's remainder below zero")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("below zero");
        assertThat(grantRemaining(grant)).isEqualTo(100_000);

        applyChange(() ->
                wallet.proposeAdjustment(PILOT, WalletEntry.BONUS, grant, -40_000, MAKER, "granted too much", "corr"));

        assertThat(grantRemaining(grant)).isEqualTo(60_000);
        assertThat(wallet.balances(PILOT).bonusMinor())
                .as("the bonus balance is the sum of its grants' remainders, corrections included")
                .isEqualTo(60_000);
        assertThat(wallet.balances(PILOT).bonusMinor()).isEqualTo(ledgerSum(WalletEntry.BONUS));

        // The other half of "neither kind": the paid branch of the same guard,
        // which picks a different operand and is the one a mis-booked transfer
        // actually goes through.
        recordTransfer(50_000, "MT103-9");
        long entries = ledgerSize();
        WalletChangeOutcome proposed = inTx(() -> wallet.proposeAdjustment(
                PILOT, WalletEntry.PAID, null, -200_000, MAKER, "a mis-booked transfer", "corr"));
        UUID requestId = Objects.requireNonNull(proposed.approvalRequestId());
        inTxDo(() -> approvals.decide(requestId, ApprovalService.Decision.APPROVE, CHECKER, "checked"));

        assertThatThrownBy(() -> inTx(() -> wallet.proposeAdjustment(
                        PILOT, WalletEntry.PAID, null, -200_000, MAKER, "a mis-booked transfer", "corr")))
                .as("a correction cannot take the paid balance below zero either")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("Only 50000 is there to correct");
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(50_000);
        assertThat(ledgerSize()).as("a refused correction writes nothing").isEqualTo(entries);
        assertThat(requestStatus(requestId))
                .as("and it is refused before the signature is spent, so a retry needs no second one")
                .isEqualTo("APPROVED");

        applyChange(() -> wallet.proposeAdjustment(
                PILOT, WalletEntry.PAID, null, -50_000, MAKER, "the mis-booked transfer, in full", "corr"));

        assertThat(wallet.balances(PILOT).paidMinor()).isZero();
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(ledgerSum(WalletEntry.PAID));
    }

    @Test
    void aWalletChangeIsRaisedAboveTheTenantWhoseAccountItConcerns() {
        WalletChangeOutcome first =
                inTx(() -> wallet.proposeRefund(PILOT, 1, "PAYOUT-Q", MAKER, "the tenant left", "corr"));
        UUID requestId = Objects.requireNonNull(first.approvalRequestId());

        assertThat(jdbc.sql("SELECT tenant_id FROM audit.approval_requests WHERE id = :id")
                        .param("id", requestId)
                        .query(UUID.class)
                        .optional())
                .as("the tenant's own approvals worklist is keyed on its tenant id, so a request "
                        + "carrying none can never appear in it -- which is the point: HorecaOS "
                        + "proposing a refund of a tenant's money is not that tenant's to read or sign")
                .isEmpty();
        assertThat(jdbc.sql("SELECT scope_type FROM audit.approval_requests WHERE id = :id")
                        .param("id", requestId)
                        .query(String.class)
                        .single())
                .isEqualTo("PLATFORM");
        assertThat(jdbc.sql("""
                        SELECT p.scope_type || ':' || coalesce(p.tenant_id::text, 'none')
                          FROM audit.approval_requests r
                          JOIN audit.approval_policies p ON p.id = r.policy_id
                         WHERE r.id = :id
                        """).param("id", requestId).query(String.class).single())
                .as("and it resolved to the PLATFORM policy V0211 seeds, not to one of the tenant's")
                .isEqualTo("PLATFORM:none");
    }

    @Test
    void aPlatformWalletRequestSaysWhoseAccountItMovesAndWhatItProposes() {
        recordTransfer(50_000_000, "MT103-BIG");
        WalletChangeOutcome first =
                inTx(() -> wallet.proposeRefund(PILOT, 50_000_000, "PAYOUT-BIG", MAKER, "the tenant left", "corr"));
        UUID requestId = Objects.requireNonNull(first.approvalRequestId());

        assertThat(jdbc.sql("SELECT subject_tenant_id FROM audit.approval_requests WHERE id = :id")
                        .param("id", requestId)
                        .query(UUID.class)
                        .optional())
                .as("the row's own tenant_id stays null so the tenant can neither read nor sign it; this is "
                        + "the only field that says whose fifty million is leaving")
                .contains(PILOT);
        assertThat(jdbc.sql("SELECT subject_json::text FROM audit.approval_requests WHERE id = :id")
                        .param("id", requestId)
                        .query(String.class)
                        .single())
                .as("and what is proposed, as the components the parameters hash covers")
                .contains("\"amountMinor\": \"-50000000\"")
                .contains("\"currency\": \"UZS\"")
                .contains("\"payoutReference\": \"PAYOUT-BIG\"")
                .contains("\"entryType\": \"REFUND\"")
                .contains("\"moneyKind\": \"PAID\"");
        assertThat(jdbc.sql("SELECT subject_json::text FROM audit.approval_requests WHERE id = :id")
                        .param("id", requestId)
                        .query(String.class)
                        .single())
                .as("never the maker's prose, which ADR 0029 keeps off every console")
                .doesNotContain("the tenant left");

        // A second proposal, of a very different size, against the other tenant:
        // the two rows an approver could not tell apart before this.
        recordTransfer(RIVAL, 5_000_000, "MT103-SMALL");
        WalletChangeOutcome other =
                inTx(() -> wallet.proposeRefund(RIVAL, 5_000_000, "PAYOUT-SMALL", MAKER, "the tenant left", "corr"));
        UUID otherId = Objects.requireNonNull(other.approvalRequestId());

        assertThat(subjectOf(otherId))
                .as("whose money, and how much, on each row rather than on neither")
                .containsEntry("tenantId", RIVAL.toString())
                .containsEntry("amountMinor", "-5000000");
        assertThat(subjectOf(requestId)).containsEntry("tenantId", PILOT.toString());
    }

    @Test
    void everyApprovalLifecycleFactNamesTheTenantWhoseAccountItConcerns() {
        recordTransfer(100_000, "MT103-LIFE");
        WalletChangeOutcome first =
                inTx(() -> wallet.proposeRefund(PILOT, 100_000, "PAYOUT-LIFE", MAKER, "the tenant left", "corr"));
        UUID requestId = Objects.requireNonNull(first.approvalRequestId());
        inTxDo(() -> approvals.decide(requestId, ApprovalService.Decision.APPROVE, CHECKER, "checked"));
        inTx(() -> wallet.proposeRefund(PILOT, 100_000, "PAYOUT-LIFE", MAKER, "the tenant left", "corr"));

        assertThat(lifecycleFactsNaming(PILOT))
                .as("the facts are filed at PLATFORM scope, so audit_events.tenant_id is null on every one "
                        + "of them; a proposal that is declined, lapses, or is refused for a missing "
                        + "capability writes no wallet entry either, and was attributable to no tenant at all")
                .containsExactlyInAnyOrder("approval.requested", "approval.approve", "approval.consumed");
        assertThat(jdbc.sql("""
                        SELECT count(*) FROM audit.audit_events
                         WHERE action_code LIKE 'approval.%' AND tenant_id IS NOT NULL
                        """).query(Long.class).single())
                .as("and the routing is unchanged: none of them acquired a tenant_id, which would put the "
                        + "request back in the tenant's own worklist")
                .isZero();
    }

    @Test
    void aRefundCannotTakeThePaidBalanceBelowZero() {
        recordTransfer(100_000, "MT103-4");

        assertThatThrownBy(() -> applyChange(
                        () -> wallet.proposeRefund(PILOT, 150_000, "PAYOUT-1", MAKER, "the tenant left", "corr")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("below zero");
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(100_000);

        applyChange(() -> wallet.proposeRefund(PILOT, 100_000, "PAYOUT-2", MAKER, "the tenant left", "corr"));

        assertThat(wallet.balances(PILOT).paidMinor()).isZero();
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(ledgerSum(WalletEntry.PAID));
        assertThat(wallet.ledger(PILOT, null, 10))
                .filteredOn(entry -> WalletEntry.REFUND.equals(entry.entryType()))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.amountMinor()).isEqualTo(-100_000);
                    assertThat(entry.externalReference())
                            .as("a refund names the payout it left on")
                            .isEqualTo("PAYOUT-2");
                    assertThat(entry.approvedBy()).isEqualTo("finance-2");
                });
    }

    // --------------------------------------------------------- bonus expiry

    @Test
    void theSweepLapsesOnlyAnExpiredGrantsUnspentRemainderAndIsIdempotent() {
        startOnPlan(START, 100_000);
        UUID expiring = grantBonus(300_000, Instant.parse("2026-12-01T00:00:00Z"));
        UUID living = grantBonus(150_000, Instant.parse("2027-03-01T00:00:00Z"));
        clock.set(Instant.parse("2026-10-20T09:00:00Z"));
        inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September close", "corr"));
        assertThat(grantRemaining(expiring))
                .as("September's 100 000 came off the grant expiring soonest")
                .isEqualTo(200_000);

        clock.set(Instant.parse("2026-12-02T00:00:00Z"));
        assertThat(sweeper.runOnce()).isEqualTo(1);

        assertThat(grantRemaining(expiring)).isZero();
        assertThat(lapsedAmount(expiring))
                .as("what lapses is the unspent remainder, not the grant")
                .isEqualTo(-200_000);
        assertThat(grantRemaining(living))
                .as("a grant whose day has not come is not touched")
                .isEqualTo(150_000);
        assertThat(wallet.balances(PILOT).bonusMinor()).isEqualTo(150_000);
        assertThat(wallet.balances(PILOT).paidMinor())
                .as("paid money never lapses")
                .isEqualTo(ledgerSum(WalletEntry.PAID));

        long entries = ledgerSize();
        assertThat(sweeper.runOnce())
                .as("a second pass over the same expiry has nothing left to lapse")
                .isZero();
        assertThat(ledgerSize()).isEqualTo(entries);

        clock.set(Instant.parse("2027-03-02T00:00:00Z"));
        assertThat(sweeper.runOnce()).isEqualTo(1);
        assertThat(wallet.balances(PILOT).bonusMinor()).isZero();
        assertThat(wallet.balances(PILOT).bonusMinor()).isEqualTo(ledgerSum(WalletEntry.BONUS));
    }

    @Test
    void aGrantWhoseRemainderCameBackAfterItExpiredIsSweptAgain() {
        startOnPlan(START, 100_000);
        UUID expiring = grantBonus(300_000, Instant.parse("2026-12-01T00:00:00Z"));
        UUID living = grantBonus(150_000, Instant.parse("2027-03-01T00:00:00Z"));
        clock.set(Instant.parse("2026-10-20T09:00:00Z"));
        UUID september = inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September close", "corr"))
                .statementId();

        clock.set(Instant.parse("2026-12-02T00:00:00Z"));
        assertThat(sweeper.runOnce()).isEqualTo(1);
        assertThat(lapsedAmount(expiring)).isEqualTo(-200_000);

        clock.set(Instant.parse("2026-12-03T00:00:00Z"));
        inTxDo(() -> statements.voidStatement(PILOT, september, MAKER, "the plan was wrong", "corr"));

        assertThat(grantRemaining(expiring))
                .as("a voided statement gives its draw back even to a grant that has already lapsed")
                .isEqualTo(100_000);
        assertThat(sweeper.runOnce())
                .as("the candidate query asks for a remainder, not for the absence of a lapse -- ask "
                        + "the other question and this money is invisible to every sweep forever, "
                        + "unspendable because no live grant holds it and unlapsable because one "
                        + "lapse row already exists")
                .isEqualTo(1);
        assertThat(grantRemaining(expiring)).isZero();
        assertThat(lapsedAmount(expiring))
                .as("two lapse entries, together the whole grant")
                .isEqualTo(-300_000);
        assertThat(wallet.balances(PILOT).bonusMinor())
                .as("only the living grant is left; nothing unspendable lingers in the balance")
                .isEqualTo(150_000)
                .isEqualTo(ledgerSum(WalletEntry.BONUS));
        assertThat(grantRemaining(living)).isEqualTo(150_000);
    }

    @Test
    void oneGrantThatCannotLapseDoesNotHoldBackEveryGrantBehindIt() {
        UUID poisoned = grantBonus(300_000, Instant.parse("2026-12-01T00:00:00Z"));
        UUID behindIt = grantBonus(150_000, Instant.parse("2026-12-05T00:00:00Z"));
        UUID rivalGrant = grantBonus(RIVAL, 90_000, Instant.parse("2026-12-06T00:00:00Z"));
        clock.set(Instant.parse("2026-12-10T00:00:00Z"));

        WalletBonusExpirySweeper stubborn = new WalletBonusExpirySweeper(refusing(poisoned), clock, 200);

        assertThat(stubborn.runOnce())
                .as("the candidate query orders by expiry, so a grant that keeps throwing sits at the "
                        + "head of every later batch; without a per-candidate catch nothing behind it "
                        + "ever lapses again, in any tenant")
                .isEqualTo(2);
        assertThat(grantRemaining(poisoned)).isEqualTo(300_000);
        assertThat(grantRemaining(behindIt)).isZero();
        assertThat(grantRemaining(RIVAL, rivalGrant)).isZero();
    }

    // ---------------------------------------------------------- the ledger

    @Test
    void theLedgerRefusesAnUpdateAndADeleteAtTheDatabase() {
        recordTransfer(500_000, "MT103-5");
        UUID entryId = wallet.ledger(PILOT, null, 1).getFirst().id();

        assertThatThrownBy(() -> jdbc.sql("UPDATE commercial.wallet_entries SET amount_minor = 1 WHERE id = :id")
                        .param("id", entryId)
                        .update())
                .hasMessageContaining("never changed or deleted");
        assertThatThrownBy(() -> jdbc.sql("DELETE FROM commercial.wallet_entries WHERE id = :id")
                        .param("id", entryId)
                        .update())
                .hasMessageContaining("never changed or deleted");
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(500_000);

        assertThat(jdbc.sql("""
                        SELECT has_table_privilege('horecaos_application', 'commercial.wallet_entries', 'UPDATE')
                            OR has_table_privilege('horecaos_application', 'commercial.wallet_entries', 'DELETE')
                        """).query(Boolean.class).single())
                .as("the trigger is the second stop; the application is granted neither in the first place")
                .isFalse();
    }

    // -------------------------------------------------------------- deposit

    @Test
    void theDepositIsAPaidTopUpAndNoStatementBillsADepositLine() {
        UUID versionId = activePlan(new PlanTerms(null, 500_000, Map.of()), MONTHLY);
        UUID subscriptionId = inTx(() -> subscriptions.start(PILOT, versionId, null, MAKER, "the pilot", "corr"));
        assertThat(depositDue(subscriptionId))
                .as("the deposit becomes due the moment the subscription starts")
                .isEqualTo(500_000);

        inTx(() -> wallet.recordDeposit(PILOT, "MT103-DEP", MAKER, "the activation deposit", "corr"));

        assertThat(depositDue(subscriptionId)).isZero();
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(500_000);
        assertThat(wallet.ledger(PILOT, null, 10)).singleElement().satisfies(entry -> {
            assertThat(entry.entryType()).isEqualTo(WalletEntry.DEPOSIT);
            assertThat(entry.moneyKind()).isEqualTo(WalletEntry.PAID);
            assertThat(entry.externalReference()).isEqualTo("MT103-DEP");
            assertThat(entry.subscriptionId())
                    .as("the row that clears an obligation names the obligation it cleared, so the "
                            + "reversal has something to aim at other than whatever is live later")
                    .isEqualTo(subscriptionId);
        });
        assertThatThrownBy(() -> inTx(() -> wallet.recordDeposit(PILOT, "MT103-DEP", MAKER, "again", "corr")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no activation deposit due");

        clock.set(CLOSE);
        Statement september = statements.draft(PILOT, "2026-09");
        assertThat(september.lines())
                .as("the statement stops billing a deposit line; the wallet holds the deposit instead")
                .extracting(StatementLine::kind)
                .containsExactly("PLAN");

        inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September close", "corr"));

        assertThat(statementPayment("2026-09").paidMinor())
                .as("and the first statement is paid from it")
                .isEqualTo(500_000);
        assertThat(statementPayment("2026-09").dueMinor()).isEqualTo(MONTHLY - 500_000);
    }

    @Test
    void aPlanVersionInASecondCurrencyIsNotSoldWithADepositNothingCouldCollect() {
        clock.set(START);
        UUID dollars = activePlan("FOREIGN", "USD", new PlanTerms(null, 50_000, Map.of()), MONTHLY);

        assertThatThrownBy(() -> inTx(() -> subscriptions.start(PILOT, dollars, null, MAKER, "the pilot", "corr")))
                .as("the wallet takes money in in its own currency only, and no statement carries a deposit "
                        + "line any more, so this obligation could be collected by nothing at all: it "
                        + "stood in the control plane for the life of the subscription with no path back "
                        + "to zero and no remedy but hand-written SQL")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("priced in USD and the tenant is billed in UZS");

        // The configuration itself stays legitimate: ADR 0095 keeps a version
        // sold in a second currency sellable, invoiced in that currency. It is
        // the deposit alone that has no collection route.
        UUID invoiced = activePlan("FOREIGN_INVOICED", "USD", new PlanTerms(null, 0, Map.of()), MONTHLY);
        UUID subscriptionId = inTx(() -> subscriptions.start(PILOT, invoiced, null, MAKER, "the pilot", "corr"));

        assertThat(depositDue(subscriptionId)).isZero();
    }

    @Test
    void aDepositPricedInAnotherCurrencyIsNotCreditedAtItsFaceValue() {
        clock.set(START);
        UUID som = activePlan("BASIC", "UZS", new PlanTerms(null, 500_000, Map.of()), MONTHLY);
        UUID subscriptionId = inTx(() -> subscriptions.start(PILOT, som, null, MAKER, "the pilot", "corr"));
        UUID dollars = activePlan("FOREIGN", "USD", new PlanTerms(null, 50_000, Map.of()), MONTHLY);
        // Starting on a second-currency version that sells a deposit is refused
        // at the door now, so this SQL -- standing in for a plan-version
        // migration that does not exist -- is what is left to reach the guard
        // that keeps a foreign face value out of the ledger.
        jdbc.sql("UPDATE commercial.subscriptions SET plan_version_id = :version WHERE id = :id")
                .param("version", dollars)
                .param("id", subscriptionId)
                .update();

        assertThatThrownBy(() -> inTx(() -> wallet.recordDeposit(PILOT, "MT103-USD", MAKER, "the deposit", "corr")))
                .as("50 000 minor USD is five hundred dollars and 50 000 minor UZS is about four cents; "
                        + "a wallet takes money in in its own currency only, as it pays out in it only")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("priced in USD and the wallet holds UZS");

        assertThat(ledgerSize()).isZero();
        assertThat(depositDue(subscriptionId))
                .as("and the obligation is left standing rather than marked paid at a rate nobody set")
                .isEqualTo(500_000);
    }

    @Test
    void aDepositRecordedAgainstTheWrongTenantIsTakenBackAndBecomesDueAgain() {
        clock.set(START);
        UUID versionId = activePlan("BASIC", "UZS", new PlanTerms(null, 500_000, Map.of()), MONTHLY);
        UUID pilotSubscription = inTx(() -> subscriptions.start(PILOT, versionId, null, MAKER, "the pilot", "corr"));
        inTx(() -> subscriptions.start(RIVAL, versionId, null, MAKER, "the other one", "corr"));

        // RIVAL's wire, typed against PILOT's identifier in the path.
        UUID misposted = inTx(() -> wallet.recordDeposit(PILOT, "MT103-RIVALS", MAKER, "the deposit", "corr"));
        assertThat(depositDue(pilotSubscription)).isZero();
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(500_000);

        applyChange(() ->
                wallet.proposeDepositReversal(PILOT, misposted, MAKER, "recorded against the wrong tenant", "corr"));

        assertThat(wallet.balances(PILOT).paidMinor()).isZero();
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(ledgerSum(WalletEntry.PAID));
        assertThat(depositDue(pilotSubscription))
                .as("the money came back and the obligation came back with it, in one locked transaction")
                .isEqualTo(500_000);
        assertThat(wallet.ledger(PILOT, null, 10))
                .filteredOn(entry -> WalletEntry.DEPOSIT_REVERSAL.equals(entry.entryType()))
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.moneyKind()).isEqualTo(WalletEntry.PAID);
                    assertThat(entry.amountMinor()).isEqualTo(-500_000);
                    assertThat(entry.externalReference())
                            .as("naming the deposit it takes back, so the two rows read as one act")
                            .isEqualTo("MT103-RIVALS");
                    assertThat(entry.approvedBy()).isEqualTo("finance-2");
                });

        recordTransfer(PILOT, 500_000, "MT103-TOPUP");
        assertThatThrownBy(() -> applyChange(() -> wallet.proposeDepositReversal(
                        PILOT, misposted, MAKER, "recorded against the wrong tenant, again", "corr")))
                .as("one deposit is taken back once; a second reversal would re-arm the obligation twice over")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("already recorded");

        // The money was RIVAL's, and PILOT still owes its own.
        inTx(() -> wallet.recordDeposit(RIVAL, "MT103-RIVALS", MAKER, "the deposit", "corr"));
        inTx(() -> wallet.recordDeposit(PILOT, "MT103-PILOTS", MAKER, "the deposit", "corr"));

        assertThat(wallet.balances(RIVAL).paidMinor()).isEqualTo(500_000);
        assertThat(depositDue(pilotSubscription)).isZero();
    }

    @Test
    void aReversalReArmsTheSubscriptionTheDepositClearedAndRefusesWhenThatOneIsGone() {
        clock.set(START);
        UUID planA = activePlan("BASIC", "UZS", new PlanTerms(null, 500_000, Map.of()), MONTHLY);
        UUID first = inTx(() -> subscriptions.start(PILOT, planA, null, MAKER, "the pilot", "corr"));
        UUID misposted = inTx(() -> wallet.recordDeposit(PILOT, "MT103-RIVALS", MAKER, "the deposit", "corr"));
        assertThat(depositDue(first)).isZero();

        // The tenant changes plans. SubscriptionStatus.allowedNext documents this
        // as the way back from TERMINATED: a new subscription, with its own, much
        // larger activation deposit.
        terminate(PILOT);
        UUID planB = activePlan("LARGE", "UZS", new PlanTerms(null, 5_000_000, Map.of()), MONTHLY);
        UUID second = inTx(() -> subscriptions.start(PILOT, planB, null, MAKER, "the bigger plan", "corr"));
        assertThat(depositDue(second)).isEqualTo(5_000_000);
        long entries = ledgerSize();

        assertThatThrownBy(() -> applyChange(() ->
                        wallet.proposeDepositReversal(PILOT, misposted, MAKER, "recorded for the wrong tenant", "c")))
                .as("re-arming whichever subscription is live now would write plan A's 500 000 over plan B's "
                        + "5 000 000 -- and nothing would show it, because no statement carries a deposit "
                        + "line and the ledger records money rather than obligations")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("is TERMINATED, so there is no obligation left to re-arm");

        assertThat(depositDue(second))
                .as("plan B's own obligation stands untouched, in full")
                .isEqualTo(5_000_000);
        assertThat(depositDue(first)).isZero();
        assertThat(ledgerSize()).as("and a refused reversal writes nothing").isEqualTo(entries);
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(500_000).isEqualTo(ledgerSum(WalletEntry.PAID));
    }

    @Test
    void aReversalNeverMakesASecondDepositCollectableOnASubscriptionThatPaidItsOwn() {
        clock.set(START);
        UUID planA = activePlan("BASIC", "UZS", new PlanTerms(null, 500_000, Map.of()), MONTHLY);
        UUID first = inTx(() -> subscriptions.start(PILOT, planA, null, MAKER, "the pilot", "corr"));
        UUID paid = inTx(() -> wallet.recordDeposit(PILOT, "MT103-FIRST", MAKER, "the deposit", "corr"));

        terminate(PILOT);
        UUID planB = activePlan("LARGE", "UZS", new PlanTerms(null, 5_000_000, Map.of()), MONTHLY);
        UUID second = inTx(() -> subscriptions.start(PILOT, planB, null, MAKER, "the bigger plan", "corr"));
        recordTransfer(PILOT, 5_000_000, "MT103-FUNDS");
        inTx(() -> wallet.recordDeposit(PILOT, "MT103-SECOND", MAKER, "the new deposit", "corr"));
        assertThat(depositDue(second))
                .as("plan B's own deposit is genuinely paid")
                .isZero();

        // The bank recalls the first wire: a legitimate reversal of a deposit
        // that really was paid, against a subscription that has since ended.
        assertThatThrownBy(() ->
                        applyChange(() -> wallet.proposeDepositReversal(PILOT, paid, MAKER, "the bank recalled", "c")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no obligation left to re-arm");

        assertThat(depositDue(second))
                .as("a tenant that has already paid plan B's deposit must not be asked for it a second time")
                .isZero();
        assertThat(depositDue(first)).isZero();
        assertThatThrownBy(() -> inTx(() -> wallet.recordDeposit(PILOT, "MT103-THIRD", MAKER, "again?", "corr")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no activation deposit due");
    }

    @Test
    void aDepositThatHasAlreadyPaidAStatementIsNotReversibleUntilTheStatementIsVoided() {
        clock.set(START);
        UUID versionId = activePlan("BASIC", "UZS", new PlanTerms(null, 500_000, Map.of()), MONTHLY);
        UUID subscriptionId = inTx(() -> subscriptions.start(PILOT, versionId, null, MAKER, "the pilot", "corr"));
        UUID deposit = inTx(() -> wallet.recordDeposit(PILOT, "MT103-DEP", MAKER, "the deposit", "corr"));

        clock.set(CLOSE);
        UUID september = inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September close", "corr"))
                .statementId();
        assertThat(wallet.balances(PILOT).paidMinor())
                .as("the deposit paid the statement, so there is nothing left to take back")
                .isZero();
        long entries = ledgerSize();

        WalletChangeOutcome proposed =
                inTx(() -> wallet.proposeDepositReversal(PILOT, deposit, MAKER, "misposted", "corr"));
        UUID requestId = Objects.requireNonNull(proposed.approvalRequestId());
        inTxDo(() -> approvals.decide(requestId, ApprovalService.Decision.APPROVE, CHECKER, "checked"));

        assertThatThrownBy(() -> inTx(() -> wallet.proposeDepositReversal(PILOT, deposit, MAKER, "misposted", "corr")))
                .as("without this guard the ledger commits a paid balance of -500 000 that no UPDATE and no "
                        + "DELETE can mend, and the refund guard compares against a negative balance forever")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("a reversal cannot take it below zero")
                .hasMessageContaining("Void the statements it paid first");

        assertThat(ledgerSize()).as("a refused reversal writes nothing").isEqualTo(entries);
        assertThat(wallet.balances(PILOT).paidMinor()).isZero().isEqualTo(ledgerSum(WalletEntry.PAID));
        assertThat(depositDue(subscriptionId))
                .as("and the obligation is not re-armed on a deposit still spent")
                .isZero();
        assertThat(requestStatus(requestId))
                .as("refused before the signature is spent, so the remedy below needs no second one")
                .isEqualTo("APPROVED");

        inTxDo(() -> statements.voidStatement(PILOT, september, MAKER, "the plan was wrong", "corr"));
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(500_000);

        inTx(() -> wallet.proposeDepositReversal(PILOT, deposit, MAKER, "misposted", "corr"));

        assertThat(wallet.balances(PILOT).paidMinor()).isZero().isEqualTo(ledgerSum(WalletEntry.PAID));
        assertThat(depositDue(subscriptionId))
                .as("and the remedy the refusal names actually works")
                .isEqualTo(500_000);
    }

    @Test
    void aSecondGenuineWireWhoseReferenceNormalisesOntoTheFirstIsRefusedByName() {
        recordTransfer(1_000_000, "MT1037");

        assertThatThrownBy(() -> recordTransfer(1_200_000, "MT-1037"))
                .as("the recorder is not told to look for a string that is not in the ledger: they typed "
                        + "MT-1037, what is on file is MT1037, and the refusal has to say so or the money "
                        + "the tenant really paid goes uncredited into arrears")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("A different reference, \"MT1037\", is already recorded")
                .hasMessageContaining("record it under a reference that tells it apart");

        recordTransfer(1_200_000, "MT-1037 wire 2 of 12 Oct");

        assertThat(wallet.balances(PILOT).paidMinor())
                .as("and the recovery the refusal names needs no adjustment and no second signature")
                .isEqualTo(2_200_000)
                .isEqualTo(ledgerSum(WalletEntry.PAID));
        assertThat(wallet.ledger(PILOT, null, 10))
                .extracting(WalletEntry::externalReference)
                .as("both wires keep verbatim what the recorder typed")
                .containsExactlyInAnyOrder("MT1037", "MT-1037 wire 2 of 12 Oct");
    }

    @Test
    void theLedgerRefusesARowNamingOnePersonAsBothRecorderAndApprover() {
        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO commercial.wallet_entries (
                                    id, tenant_id, money_kind, entry_type, amount_minor, currency,
                                    reason, recorded_by, approved_by, approval_request_id)
                                VALUES (:id, :tenant, 'PAID', 'ADJUSTMENT', 50000, 'UZS',
                                    'a correction nobody else signed', 'finance-1', 'finance-1', :request)
                                """)
                        .param("id", UUID.randomUUID())
                        .param("tenant", PILOT)
                        .param("request", UUID.randomUUID())
                        .update())
                .as("the constraint is the second stop; the service writing the request's own requestedBy "
                        + "rather than the acting subject is the first, and neither depends on the other")
                .hasMessageContaining("ck_wallet_entry_four_eyes");

        jdbc.sql("""
                        INSERT INTO commercial.wallet_entries (
                            id, tenant_id, money_kind, entry_type, amount_minor, currency,
                            reason, recorded_by, approved_by, approval_request_id)
                        VALUES (:id, :tenant, 'PAID', 'ADJUSTMENT', 50000, 'UZS',
                            'a correction a second person signed', 'finance-1', 'finance-2', :request)
                        """)
                .param("id", UUID.randomUUID())
                .param("tenant", PILOT)
                .param("request", UUID.randomUUID())
                .update();

        assertThat(ledgerSize())
                .as("the refusal is about the two names being the same, not about the row being malformed")
                .isEqualTo(1);
    }

    @Test
    void aPlainCorrectionOfAMispostedDepositIsNotTheRemedy() {
        clock.set(START);
        UUID versionId = activePlan("BASIC", "UZS", new PlanTerms(null, 500_000, Map.of()), MONTHLY);
        UUID subscriptionId = inTx(() -> subscriptions.start(PILOT, versionId, null, MAKER, "the pilot", "corr"));
        inTx(() -> wallet.recordDeposit(PILOT, "MT103-WRONG", MAKER, "the deposit", "corr"));

        applyChange(() -> wallet.proposeAdjustment(
                PILOT, WalletEntry.PAID, null, -500_000, MAKER, "recorded against the wrong tenant", "corr"));

        assertThat(wallet.balances(PILOT).paidMinor())
                .as("a correction does mend the ledger")
                .isZero();
        assertThat(depositDue(subscriptionId))
                .as("and leaves the obligation cleared, which is why the reversal exists: nothing is due, "
                        + "so the real deposit can never be recorded, and the statement bills no deposit line")
                .isZero();
        assertThatThrownBy(() -> inTx(() -> wallet.recordDeposit(PILOT, "MT103-RIGHT", MAKER, "the deposit", "corr")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("no activation deposit due");
    }

    @Test
    void reArmingADepositAddsToWhatIsOwedRatherThanReplacingIt() {
        clock.set(START);
        UUID versionId = activePlan("BASIC", "UZS", new PlanTerms(null, 500_000, Map.of()), MONTHLY);
        UUID subscriptionId = inTx(() -> subscriptions.start(PILOT, versionId, null, MAKER, "the pilot", "corr"));
        UUID rival = inTx(() -> subscriptions.start(RIVAL, versionId, null, MAKER, "the other one", "corr"));
        JdbcSubscriptionStore store = new JdbcSubscriptionStore(jdbc);

        // Seeded rather than reached through the service: recordDeposit clears
        // the very row a reversal re-arms, so on every path a caller can take
        // the target is at zero and an assignment and an addition are the same
        // statement. The addition exists for the path that does not exist yet,
        // which is exactly why nothing can exercise it from above.
        assertThat(depositDue(subscriptionId)).isEqualTo(500_000);

        assertThat(store.restoreDepositDue(PILOT, subscriptionId, 500_000)).isTrue();

        assertThat(depositDue(subscriptionId))
                .as("an addition, not an assignment: a future mis-targeting can then only ever ask for "
                        + "too much, with ck_subscription_deposit_due >= 0 as the floor, rather than "
                        + "writing a real obligation down to a smaller one with nothing to show it")
                .isEqualTo(1_000_000);

        assertThat(store.restoreDepositDue(PILOT, subscriptionId, 250_000)).isTrue();
        assertThat(depositDue(subscriptionId)).isEqualTo(1_250_000);

        assertThat(store.restoreDepositDue(PILOT, rival, 500_000))
                .as("and the tenant predicate is the whole of the aim: another tenant's subscription id "
                        + "is refused loudly rather than silently updating nothing")
                .isFalse();
        assertThat(depositDue(rival)).isEqualTo(500_000);
        assertThat(depositDue(subscriptionId)).isEqualTo(1_250_000);
    }

    @Test
    void clearingADepositThatIsNoLongerWhatWasReadIsRefused() {
        clock.set(START);
        UUID versionId = activePlan("BASIC", "UZS", new PlanTerms(null, 500_000, Map.of()), MONTHLY);
        UUID subscriptionId = inTx(() -> subscriptions.start(PILOT, versionId, null, MAKER, "the pilot", "corr"));
        JdbcSubscriptionStore store = new JdbcSubscriptionStore(jdbc);

        assertThat(store.clearDepositDue(PILOT, subscriptionId, 400_000))
                .as("the obligation is 500 000, and clearing it against a figure read somewhere else is "
                        + "how plan A's 500 000 came to be written over plan B's 5 000 000")
                .isFalse();
        assertThat(depositDue(subscriptionId)).isEqualTo(500_000);

        assertThat(store.clearDepositDue(PILOT, subscriptionId, 500_000)).isTrue();
        assertThat(depositDue(subscriptionId)).isZero();
    }

    @Test
    void theObligationADepositClearedIsOnTheAuditTrailFromAndTo() {
        clock.set(START);
        UUID versionId = activePlan("BASIC", "UZS", new PlanTerms(null, 500_000, Map.of()), MONTHLY);
        UUID subscriptionId = inTx(() -> subscriptions.start(PILOT, versionId, null, MAKER, "the pilot", "corr"));
        UUID deposit = inTx(() -> wallet.recordDeposit(PILOT, "MT103-DEP", MAKER, "the deposit", "corr"));

        assertThat(changeDocument("commercial.wallet.deposit_recorded"))
                .as("deposit_due_minor is not money, so the ledger cannot reconstruct the obligation this "
                        + "cleared: which subscription, and from what to what, or a mis-clear leaves "
                        + "nothing anywhere to find it by")
                .containsEntry("subscriptionId", subscriptionId.toString())
                .containsEntry("depositDueFromMinor", "500000")
                .containsEntry("depositDueToMinor", "0");

        applyChange(() -> wallet.proposeDepositReversal(PILOT, deposit, MAKER, "the wrong tenant", "corr"));

        assertThat(changeDocument("commercial.wallet.deposit_reversed"))
                .containsEntry("subscriptionId", subscriptionId.toString())
                .containsEntry("depositDueFromMinor", "0")
                .containsEntry("depositDueToMinor", "500000");
    }

    @Test
    void aReversalRestoresAnObligationInItsOwnCurrencyOrNotAtAll() {
        clock.set(START);
        UUID som = activePlan("BASIC", "UZS", new PlanTerms(null, 500_000, Map.of()), MONTHLY);
        UUID subscriptionId = inTx(() -> subscriptions.start(PILOT, som, null, MAKER, "the pilot", "corr"));
        UUID deposit = inTx(() -> wallet.recordDeposit(PILOT, "MT103-DEP", MAKER, "the deposit", "corr"));
        WalletChangeOutcome proposed =
                inTx(() -> wallet.proposeDepositReversal(PILOT, deposit, MAKER, "the wrong tenant", "corr"));
        UUID requestId = Objects.requireNonNull(proposed.approvalRequestId());
        inTxDo(() -> approvals.decide(requestId, ApprovalService.Decision.APPROVE, CHECKER, "checked"));
        long entries = ledgerSize();

        // No store method moves a live subscription onto another version, and
        // no service exposes one: this SQL stands in for the plan-version
        // migration that does not exist yet, which is the only way the guard
        // below can ever be reached. Written as a test rather than left as a
        // comment, so the day such a migration is added the guard is already
        // proven rather than merely present.
        UUID dollars = activePlan("FOREIGN", "USD", new PlanTerms(null, 50_000, Map.of()), MONTHLY);
        jdbc.sql("UPDATE commercial.subscriptions SET plan_version_id = :version WHERE id = :id")
                .param("version", dollars)
                .param("id", subscriptionId)
                .update();

        assertThatThrownBy(
                        () -> inTx(() -> wallet.proposeDepositReversal(PILOT, deposit, MAKER, "the wrong tenant", "c")))
                .as("deposit_due_minor is copied verbatim from the plan version and carries no currency of "
                        + "its own, so writing a som figure onto a dollar obligation records a face value "
                        + "in the wrong money")
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("a reversal restores the obligation in its own currency only");

        assertThat(ledgerSize()).as("a refused reversal writes nothing").isEqualTo(entries);
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(500_000).isEqualTo(ledgerSum(WalletEntry.PAID));
        assertThat(depositDue(subscriptionId))
                .as("and nothing is re-armed in the wrong money")
                .isZero();
        assertThat(requestStatus(requestId))
                .as("refused before the signature is spent, so a retry needs no second one")
                .isEqualTo("APPROVED");
    }

    @Test
    void theLedgerRefusesADepositThatNamesNoObligationAndAnyOtherEntryThatNamesOne() {
        clock.set(START);
        UUID versionId = activePlan("BASIC", "UZS", new PlanTerms(null, 500_000, Map.of()), MONTHLY);
        UUID subscriptionId = inTx(() -> subscriptions.start(PILOT, versionId, null, MAKER, "the pilot", "corr"));

        assertThatThrownBy(() -> insertDeposit(PILOT, null, "MT103-NO-SUB"))
                .as("WalletService dereferences a DEPOSIT's subscription_id on the reversal path, so the "
                        + "equality half of this constraint is what stands between a mis-recorded row and "
                        + "a 500 where a refusal belongs")
                .hasMessageContaining("ck_wallet_entry_subscription_ref");

        assertThatThrownBy(() -> jdbc.sql("""
                                INSERT INTO commercial.wallet_entries (
                                    id, tenant_id, money_kind, entry_type, amount_minor, currency,
                                    subscription_id, reason, recorded_by, approved_by, approval_request_id)
                                VALUES (:id, :tenant, 'PAID', 'ADJUSTMENT', -5000, 'UZS', :subscription,
                                    'a correction that names an obligation', 'finance-1', 'finance-2', :request)
                                """)
                        .param("id", Ids.newId())
                        .param("tenant", PILOT)
                        .param("subscription", subscriptionId)
                        .param("request", Ids.newId())
                        .update())
                .as("stated as an equality so a later entry type cannot quietly acquire one either")
                .hasMessageContaining("ck_wallet_entry_subscription_ref");

        insertDeposit(PILOT, subscriptionId, "MT103-WELL-FORMED");

        assertThat(ledgerSize())
                .as("the refusals are about the reference and not about the rows being malformed")
                .isEqualTo(1);
    }

    @Test
    void aDepositCannotNameAnotherTenantsSubscription() {
        clock.set(START);
        UUID versionId = activePlan("BASIC", "UZS", new PlanTerms(null, 500_000, Map.of()), MONTHLY);
        UUID rivals = inTx(() -> subscriptions.start(RIVAL, versionId, null, MAKER, "the other one", "corr"));

        assertThatThrownBy(() -> insertDeposit(PILOT, rivals, "MT103-RIVALS"))
                .as("the foreign key carries the tenant, so a deposit for one tenant can never clear an "
                        + "obligation belonging to another -- and a reversal of it can never re-arm one")
                .hasMessageContaining("fk_wallet_entry_subscription");

        assertThat(ledgerSize()).isZero();
    }

    @Test
    void everyWalletProposalNamesTheAccountItMovesAndWithholdsTheMakersProse() {
        clock.set(START);
        UUID versionId = activePlan("BASIC", "UZS", new PlanTerms(null, 500_000, Map.of()), MONTHLY);
        inTx(() -> subscriptions.start(PILOT, versionId, null, MAKER, "the pilot", "corr"));
        UUID deposit = inTx(() -> wallet.recordDeposit(PILOT, "MT103-DEP", MAKER, "the deposit", "corr"));
        recordTransfer(50_000_000, "MT103-BIG");
        UUID grant = grantBonus(1_000_000, START.plus(Duration.ofDays(90)));
        Instant lapses = START.plus(Duration.ofDays(120));
        String prose = "the owner of the restaurant asked for it in writing";

        // All four, because the four .excluding().withholding("reason")
        // .about("tenantId") blocks are copies of one another and only the
        // refund's was ever read: changing any one of the other three to
        // .withholding() copies the maker's prose onto the approvals console --
        // the exact ADR 0029 leak withholding exists to prevent -- and nothing
        // was watching. Dropping .about("tenantId") was equally invisible.
        UUID adjustment = proposalRequest(
                () -> wallet.proposeAdjustment(PILOT, WalletEntry.BONUS, grant, -250_000, MAKER, prose, "corr"));
        UUID bonusGrant = proposalRequest(() -> wallet.proposeBonusGrant(PILOT, 750_000, lapses, MAKER, prose, "c"));
        UUID refund = proposalRequest(() -> wallet.proposeRefund(PILOT, 1_000_000, "PAYOUT-1", MAKER, prose, "corr"));
        UUID reversal = proposalRequest(() -> wallet.proposeDepositReversal(PILOT, deposit, MAKER, prose, "corr"));

        assertThat(subjectOf(adjustment))
                .as("a correction of bonus money, which reads identically to one of paid money without "
                        + "its money kind and the grant it lapses with")
                .containsEntry("tenantId", PILOT.toString())
                .containsEntry("moneyKind", WalletEntry.BONUS)
                .containsEntry("grantId", grant.toString())
                .containsEntry("amountMinor", "-250000")
                .containsEntry("currency", "UZS")
                .containsEntry("entryType", WalletEntry.ADJUSTMENT);
        assertThat(subjectOf(bonusGrant))
                .as("a grant's expiry is the second half of what is being given away")
                .containsEntry("tenantId", PILOT.toString())
                .containsEntry("moneyKind", WalletEntry.BONUS)
                .containsEntry("amountMinor", "750000")
                .containsEntry("expiresAt", lapses.toString())
                .containsEntry("currency", "UZS")
                .containsEntry("entryType", WalletEntry.BONUS_GRANT);
        assertThat(subjectOf(refund))
                .containsEntry("tenantId", PILOT.toString())
                .containsEntry("moneyKind", WalletEntry.PAID)
                .containsEntry("amountMinor", "-1000000")
                .containsEntry("payoutReference", "PAYOUT-1")
                .containsEntry("currency", "UZS")
                .containsEntry("entryType", WalletEntry.REFUND);
        assertThat(subjectOf(reversal))
                .containsEntry("tenantId", PILOT.toString())
                .containsEntry("moneyKind", WalletEntry.PAID)
                .containsEntry("amountMinor", "-500000")
                .containsEntry("depositEntryId", deposit.toString())
                .containsEntry("currency", "UZS")
                .containsEntry("entryType", WalletEntry.DEPOSIT_REVERSAL);

        for (UUID requestId : List.of(adjustment, bonusGrant, refund, reversal)) {
            assertThat(subjectTenantOf(requestId))
                    .as("whose account this moves, on every one of the four; null puts the row back under "
                            + "'Tenant not named on this request' on the one queue it is decided from")
                    .isEqualTo(PILOT);
            assertThat(subjectOf(requestId).values())
                    .as("and never the maker's prose, which ADR 0029 keeps off every console")
                    .noneMatch(value -> value.contains("asked for it in writing"));
        }
    }

    // ----------------------------------------------------------------- card

    @Test
    void aCardIsChargedForWhatIsLeftAfterBonusAndPaidMoneyAndNotAPennyMore() {
        startOnPlan(START, MONTHLY);
        grantBonus(200_000, Instant.parse("2027-01-01T00:00:00Z"));
        inTxDo(() ->
                wallet.setPaymentMethod(PILOT, PaymentMethod.CARD, "vault:pilot-card", MAKER, "the owner asked", "c"));
        clock.set(CLOSE);
        UUID september = inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September close", "corr"))
                .statementId();
        assertThat(statementPayment("2026-09").dueMinor()).isEqualTo(MONTHLY - 200_000);

        RecordingCharger charger = new RecordingCharger(new CardCharger.Outcome.Succeeded("CLICK-42"));
        // Outside any transaction of ours, which is the whole point: the provider
        // is asked between two committed transactions, not inside one.
        walletChargingWith(charger).settleCardRemainders(PILOT);

        assertThat(charger.charges).singleElement().satisfies(charge -> {
            assertThat(charge.amountMinor())
                    .as("the remainder, not the total: the bonus already paid 200 000 of it, and a card "
                            + "charged the total would take money the tenant does not owe")
                    .isEqualTo(MONTHLY - 200_000);
            assertThat(charge.cardTokenReference()).isEqualTo("vault:pilot-card");
            assertThat(charge.currency()).isEqualTo("UZS");
            assertThat(charge.idempotencyKey())
                    .as("one attempt, one key: the row commercial.card_charge_attempts wrote before the "
                            + "provider was called, never the statement id -- a statement is charged at "
                            + "different amounts across settlement passes")
                    .isEqualTo(chargeAttemptFor(september));
        });
        assertThat(statementPayment("2026-09").paidMinor()).isEqualTo(MONTHLY);
        assertThat(statementPayment("2026-09").dueMinor()).isZero();
        assertThat(wallet.ledger(PILOT, null, 10))
                .extracting(WalletEntry::entryType, WalletEntry::moneyKind, WalletEntry::amountMinor)
                .containsExactlyInAnyOrder(
                        tuple(WalletEntry.BONUS_GRANT, WalletEntry.BONUS, 200_000L),
                        tuple(WalletEntry.STATEMENT_PAYMENT, WalletEntry.BONUS, -200_000L),
                        tuple(WalletEntry.TOP_UP, WalletEntry.PAID, MONTHLY - 200_000),
                        tuple(WalletEntry.STATEMENT_PAYMENT, WalletEntry.PAID, -(MONTHLY - 200_000)));
        assertThat(wallet.ledger(PILOT, null, 10))
                .filteredOn(entry -> WalletEntry.TOP_UP.equals(entry.entryType()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.externalReference())
                        .as("the provider's own reference is what proves the charge happened")
                        .isEqualTo("CLICK-42"));
        assertThat(auditedActions()).contains("commercial.wallet.card_charged");
        assertThat(meters.get("commercial.wallet.card_charge")
                        .tag("outcome", "succeeded")
                        .counter()
                        .count())
                .as("the denominator of the rate an outage alert divides by: without the success arm one "
                        + "decline reads as 100% failure and a working provider is invisible")
                .isEqualTo(1.0);
        assertThat(meters.find("commercial.wallet.card_charge")
                        .tag("outcome", "failed")
                        .counter())
                .as("and the success path does not double-count into another arm")
                .isNull();
    }

    @Test
    void aSecondChargeForADifferentRemainderCarriesADifferentIdempotencyKey() {
        startOnPlan(START, MONTHLY);
        inTxDo(() ->
                wallet.setPaymentMethod(PILOT, PaymentMethod.CARD, "vault:pilot-card", MAKER, "the owner asked", "c"));
        clock.set(CLOSE);
        inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September close", "corr"));

        RecordingCharger declining = new RecordingCharger(new CardCharger.Outcome.Failed("DECLINED"));
        walletChargingWith(declining).settleCardRemainders(PILOT);
        assertThat(declining.charges)
                .singleElement()
                .satisfies(charge -> assertThat(charge.amountMinor()).isEqualTo(MONTHLY));

        // A transfer arrives, so the next pass owes less -- and used to hand the
        // provider a smaller amount under the statement id it had already
        // declined 1 200 000 under.
        recordTransfer(400_000, "MT103-PART");
        RecordingCharger succeeding = new RecordingCharger(new CardCharger.Outcome.Succeeded("CLICK-77"));
        walletChargingWith(succeeding).settleCardRemainders(PILOT);

        assertThat(succeeding.charges).singleElement().satisfies(charge -> {
            assertThat(charge.amountMinor())
                    .as("the remainder after the transfer, not the whole statement")
                    .isEqualTo(MONTHLY - 400_000);
            assertThat(charge.idempotencyKey())
                    .as("a provider honouring keys would either replay the decline forever or replay a "
                            + "success for an amount nobody charged; a key that identifies the attempt "
                            + "cannot do either")
                    .isNotEqualTo(declining.charges.getFirst().idempotencyKey());
        });
        assertThat(jdbc.sql("""
                        SELECT outcome || ':' || amount_minor FROM commercial.card_charge_attempts
                         WHERE tenant_id = :id ORDER BY attempted_at, amount_minor DESC
                        """).param("id", PILOT).query(String.class).list())
                .as("and each attempt is on the record with what it asked for and what came back -- "
                        + "exactly these, so a settlement that leaked a PENDING row behind it would show")
                .containsExactlyInAnyOrder("FAILED:" + MONTHLY, "SUCCEEDED:" + (MONTHLY - 400_000));
    }

    @Test
    void aDeclinedCardLeavesTheRemainderDueAndSaysWhy() {
        startOnPlan(START, MONTHLY);
        inTxDo(() ->
                wallet.setPaymentMethod(PILOT, PaymentMethod.CARD, "vault:pilot-card", MAKER, "the owner asked", "c"));
        clock.set(CLOSE);
        inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September close", "corr"));
        long entries = ledgerSize();

        RecordingCharger charger = new RecordingCharger(new CardCharger.Outcome.Failed("DECLINED"));
        walletChargingWith(charger).settleCardRemainders(PILOT);

        assertThat(charger.charges)
                .singleElement()
                .satisfies(charge -> assertThat(charge.amountMinor()).isEqualTo(MONTHLY));
        assertThat(statementPayment("2026-09").dueMinor())
                .as("a decline leaves the statement exactly where it was")
                .isEqualTo(MONTHLY);
        assertThat(ledgerSize()).isEqualTo(entries);
        assertThat(auditedActions())
                .as("a tenant in arrears with no recorded cause is what a silent `return 0` produced; "
                        + "finance has to be able to tell 'the provider declined' from 'we never asked'")
                .contains("commercial.wallet.card_charge_declined");
        assertThat(jdbc.sql("""
                        SELECT change_document->>'reason' FROM audit.audit_events
                         WHERE action_code = 'commercial.wallet.card_charge_declined'
                        """).query(String.class).single()).isEqualTo("DECLINED");
        assertThat(meters.get("commercial.wallet.card_charge")
                        .tag("outcome", "failed")
                        .counter()
                        .count())
                .as("and a provider outage is a rate a rule can alert on, not a pile of unrelated arrears")
                .isEqualTo(1.0);
        assertThat(attemptOutcomes()).containsExactly("FAILED:settled");
        assertThat(pendingAttempts()).isZero();
    }

    @Test
    void aCardTenantWithNoTokenOnFileIsHandedToTheAdapterExactlyAsItStands() {
        startOnPlan(START, MONTHLY);
        inTxDo(() -> wallet.setPaymentMethod(PILOT, PaymentMethod.CARD, null, MAKER, "no token yet", "c"));
        clock.set(CLOSE);
        inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September close", "corr"));

        // Its own registry, so the number under test is this call's.
        SimpleMeterRegistry own = new SimpleMeterRegistry();
        RecordingCharger charger = new RecordingCharger(new CardCharger.Outcome.NotConfigured());
        walletChargingWith(charger, own).settleCardRemainders(PILOT);

        assertThat(charger.charges)
                .as("whether a missing token is a refusal is the adapter's contract, not the caller's, "
                        + "so the caller passes what it has rather than deciding on the adapter's behalf")
                .singleElement()
                .satisfies(charge -> assertThat(charge.cardTokenReference()).isNull());
        assertThat(ledgerSize()).isZero();
        assertThat(statementPayment("2026-09").dueMinor()).isEqualTo(MONTHLY);
        assertThat(own.get("commercial.wallet.card_charge")
                        .tag("outcome", "not_configured")
                        .counter()
                        .count())
                .as("counted rather than logged: today this is the expected answer for every CARD tenant, "
                        + "and a warning per statement would drown the decline it must be told apart from")
                .isEqualTo(1.0);
        assertThat(attemptOutcomes())
                .as("NOT_CONFIGURED is the only answer production can produce until a merchant agreement "
                        + "lands, so every row this table holds is written by that arm -- and it has to be "
                        + "settled. A row left PENDING means something else entirely: we asked a provider "
                        + "and never learned the answer")
                .containsExactly("NOT_CONFIGURED:settled");
        assertThat(pendingAttempts()).isZero();
    }

    @Test
    void aCardTenantsRemainderStaysDueWhileNoChargerIsConfigured() {
        startOnPlan(START, MONTHLY);
        inTxDo(() ->
                wallet.setPaymentMethod(PILOT, PaymentMethod.CARD, "vault:pilot-card", MAKER, "the owner asked", "c"));
        clock.set(CLOSE);

        inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September close", "corr"));

        assertThat(wallet.billing(PILOT).paymentMethod()).isEqualTo(PaymentMethod.CARD);
        assertThat(statementPayment("2026-09").dueMinor())
                .as("with no merchant account a CARD tenant is collected exactly like an INVOICE one")
                .isEqualTo(MONTHLY);
        assertThat(ledgerSize())
                .as("and nothing at all is written to the ledger")
                .isZero();

        // The port is wired, which is what makes the answer above a statement
        // about the missing merchant account rather than about missing code.
        WalletService withACharger =
                walletChargingWith(new RecordingCharger(new CardCharger.Outcome.Succeeded("CLICK-1")));
        withACharger.settleCardRemainders(PILOT);

        assertThat(statementPayment("2026-09").dueMinor()).isZero();
        assertThat(wallet.ledger(PILOT, null, 10))
                .extracting(WalletEntry::entryType)
                .containsExactlyInAnyOrder(WalletEntry.TOP_UP, WalletEntry.STATEMENT_PAYMENT);
    }

    @Test
    void theAttemptIsOnTheRecordBeforeTheProviderIsAskedAndNothingIsHeldWhileItAnswers() {
        startOnPlan(START, MONTHLY);
        inTxDo(() ->
                wallet.setPaymentMethod(PILOT, PaymentMethod.CARD, "vault:pilot-card", MAKER, "the owner asked", "c"));
        clock.set(CLOSE);
        inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September close", "corr"));

        WatchfulCharger charger = new WatchfulCharger(new CardCharger.Outcome.Succeeded("CLICK-9"));
        walletChargingWith(charger).settleCardRemainders(PILOT);

        assertThat(charger.attemptWasCommitted)
                .as("V0214 exists so the idempotency key is durable before anything can be charged under "
                        + "it. Written inside the settlement transaction the row was invisible to every "
                        + "other session and rolled back with any later failure -- so the one state the "
                        + "table was built to hold, 'we asked and never learned the answer', could never "
                        + "survive the failure it was written for")
                .isTrue();
        assertThat(charger.transactionWasActive)
                .as("and nothing we do not control is called while a pooled connection and the tenant's "
                        + "billing row lock are held, which is the property ExternalCallTransactionBoundary"
                        + "Tests enforces for media, onboarding and payments")
                .isFalse();
        assertThat(statementPayment("2026-09").dueMinor()).isZero();
    }

    @Test
    void aProviderThatNeverAnswersLeavesTheAttemptPendingAndTheRecordedMoneyAlone() {
        startOnPlan(START, MONTHLY);
        inTxDo(() ->
                wallet.setPaymentMethod(PILOT, PaymentMethod.CARD, "vault:pilot-card", MAKER, "the owner asked", "c"));
        clock.set(CLOSE);
        inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September close", "corr"));
        recordTransfer(400_000, "MT103-WIRE");
        assertThat(statementPayment("2026-09").dueMinor()).isEqualTo(MONTHLY - 400_000);

        SimpleMeterRegistry own = new SimpleMeterRegistry();
        ThrowingCharger charger = new ThrowingCharger();
        walletChargingWith(charger, own).settleCardRemainders(PILOT);

        assertThat(charger.attemptWasCommitted)
                .as("the key was durable before anything could be charged under it, which is the only "
                        + "thing that makes the PENDING row below mean what V0214 says it means")
                .isTrue();

        assertThat(wallet.balances(PILOT).paidMinor())
                .as("the operator's wire is recorded and spent on the statement, whatever the provider "
                        + "does afterwards: called inside the money transaction, a provider timeout took "
                        + "that transfer down with it and the operator saw a 500 with no row on file")
                .isZero()
                .isEqualTo(ledgerSum(WalletEntry.PAID));
        assertThat(statementPayment("2026-09").paidMinor()).isEqualTo(400_000);
        assertThat(statementPayment("2026-09").dueMinor())
                .as("and the remainder is still owed, for the next pass to try again")
                .isEqualTo(MONTHLY - 400_000);
        assertThat(attemptOutcomes())
                .as("the row the reconciler needs: we asked, under this key, and never learned the answer")
                .containsExactly("PENDING:unsettled");
        assertThat(own.get("commercial.wallet.card_charge")
                        .tag("outcome", "unanswered")
                        .counter()
                        .count())
                .isEqualTo(1.0);
        assertThat(auditedActions())
                .as("neither charged nor declined: nobody knows which, and saying either would be a "
                        + "statement about money that may or may not have left the card")
                .doesNotContain("commercial.wallet.card_charged", "commercial.wallet.card_charge_declined");
    }

    @Test
    void theCardIsNeverAskedFromInsideSomebodyElsesTransaction() {
        startOnPlan(START, MONTHLY);
        inTxDo(() ->
                wallet.setPaymentMethod(PILOT, PaymentMethod.CARD, "vault:pilot-card", MAKER, "the owner asked", "c"));

        assertThatThrownBy(() -> inTx(() -> wallet.settleCardRemainders(PILOT)))
                .as("the rule is the fix, so it is enforced rather than remembered: a caller that still "
                        + "holds its unit of work has not committed the money it just recorded")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("between two committed transactions");
    }

    // ------------------------------------------------------------- helpers

    /** The same wallet, with a card charger wired: the port exists, only the merchant account does not. */
    private WalletService walletChargingWith(CardCharger charger) {
        return walletChargingWith(charger, meters);
    }

    private WalletService walletChargingWith(CardCharger charger, SimpleMeterRegistry registry) {
        return new WalletService(
                new JdbcWalletStore(jdbc),
                new JdbcSubscriptionStore(jdbc),
                new JdbcCardChargeAttemptStore(jdbc),
                approvals,
                charger,
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                registry,
                transactions,
                clock);
    }

    /** The same wallet, except that one named grant can never be lapsed — the poison row a sweep must survive. */
    private WalletService refusing(UUID grantId) {
        return new WalletService(
                new JdbcWalletStore(jdbc),
                new JdbcSubscriptionStore(jdbc),
                new JdbcCardChargeAttemptStore(jdbc),
                approvals,
                new NotConfiguredCardCharger(),
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                meters,
                transactions,
                clock) {
            @Override
            public boolean expireGrantIfDue(UUID tenantId, UUID grant, String currency, Instant now) {
                if (grant.equals(grantId)) {
                    throw new IllegalStateException("this grant cannot be lapsed");
                }
                return super.expireGrantIfDue(tenantId, grant, currency, now);
            }
        };
    }

    /** A charger that records what it was asked and answers what the test told it to. */
    private static final class RecordingCharger implements CardCharger {

        private final List<Charge> charges = new ArrayList<>();
        private final Outcome answer;

        RecordingCharger(Outcome answer) {
            this.answer = answer;
        }

        @Override
        public Outcome charge(
                UUID tenantId,
                @Nullable String cardTokenReference,
                long amountMinor,
                String currency,
                String idempotencyKey) {
            charges.add(new Charge(tenantId, cardTokenReference, amountMinor, currency, idempotencyKey));
            return answer;
        }

        private record Charge(
                UUID tenantId,
                @Nullable String cardTokenReference,
                long amountMinor,
                String currency,
                String idempotencyKey) {}
    }

    /**
     * A charger that answers what the test told it to, and records what was
     * true of the world at the moment it was asked: whether the attempt row was
     * already committed, and whether a transaction was open.
     */
    private final class WatchfulCharger implements CardCharger {

        private final Outcome answer;
        private boolean attemptWasCommitted;
        private boolean transactionWasActive = true;

        WatchfulCharger(Outcome answer) {
            this.answer = answer;
        }

        @Override
        public Outcome charge(
                UUID tenantId,
                @Nullable String cardTokenReference,
                long amountMinor,
                String currency,
                String idempotencyKey) {
            transactionWasActive = TransactionSynchronizationManager.isActualTransactionActive();
            attemptWasCommitted = pendingAttemptIsVisibleToAnotherSession(idempotencyKey);
            return answer;
        }
    }

    /** A provider adapter that fails the way a timeout does: no answer at all. */
    private final class ThrowingCharger implements CardCharger {

        private boolean attemptWasCommitted;

        @Override
        public Outcome charge(
                UUID tenantId,
                @Nullable String cardTokenReference,
                long amountMinor,
                String currency,
                String idempotencyKey) {
            attemptWasCommitted = pendingAttemptIsVisibleToAnotherSession(idempotencyKey);
            throw new IllegalStateException("the provider did not answer");
        }
    }

    /**
     * Whether this attempt is committed, asked on a connection of its own.
     *
     * <p>Raw JDBC on purpose: {@code JdbcClient} over the same {@code
     * DataSource} is handed the transaction's own connection when one is bound,
     * and would report an uncommitted row as visible -- which is precisely the
     * distinction under test.
     */
    private boolean pendingAttemptIsVisibleToAnotherSession(String attemptId) {
        try (java.sql.Connection separate = db.dataSource().getConnection();
                java.sql.PreparedStatement query = separate.prepareStatement("""
                        SELECT count(*) FROM commercial.card_charge_attempts
                         WHERE id = CAST(? AS uuid) AND outcome = 'PENDING' AND settled_at IS NULL
                        """)) {
            query.setString(1, attemptId);
            try (java.sql.ResultSet rows = query.executeQuery()) {
                return rows.next() && rows.getLong(1) == 1;
            }
        } catch (java.sql.SQLException unreadable) {
            throw new IllegalStateException("could not read the attempt from a second session", unreadable);
        }
    }

    /** Runs the work in a transaction, as the controller's request does. */
    private <T> T inTx(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }

    private void inTxDo(Runnable work) {
        transactions.executeWithoutResult(status -> work.run());
    }

    /**
     * Proposes a change, has {@code CHECKER} approve it, and proposes the
     * identical change again — the two calls a maker really makes.
     */
    private WalletChangeOutcome applyChange(Supplier<WalletChangeOutcome> change) {
        WalletChangeOutcome first = inTx(change);
        assertThat(first.status()).isEqualTo(WalletChangeOutcome.AWAITING_APPROVAL);
        UUID requestId = Objects.requireNonNull(first.approvalRequestId());
        inTxDo(() -> approvals.decide(requestId, ApprovalService.Decision.APPROVE, CHECKER, "checked"));
        return inTx(change);
    }

    private void recordTransfer(long amountMinor, String bankReference) {
        recordTransfer(PILOT, amountMinor, bankReference);
    }

    private void recordTransfer(UUID tenantId, long amountMinor, String bankReference) {
        inTx(() -> wallet.recordTransfer(tenantId, amountMinor, bankReference, MAKER, "a bank transfer", "corr"));
    }

    /** Grants bonus money the way a person does: proposed, approved by somebody else, proposed again. */
    private UUID grantBonus(long amountMinor, Instant expiresAt) {
        return grantBonus(PILOT, amountMinor, expiresAt);
    }

    private UUID grantBonus(UUID tenantId, long amountMinor, Instant expiresAt) {
        applyChange(() -> wallet.proposeBonusGrant(tenantId, amountMinor, expiresAt, MAKER, "a launch credit", "corr"));
        return jdbc.sql("""
                        SELECT id FROM commercial.wallet_entries
                         WHERE tenant_id = :id AND entry_type = 'BONUS_GRANT' AND expires_at = :expiresAt
                        """)
                .param("id", tenantId)
                .param("expiresAt", expiresAt.atOffset(ZoneOffset.UTC))
                .query(UUID.class)
                .single();
    }

    private void startOnPlan(Instant startAt, long monthlyMinor) {
        clock.set(startAt);
        UUID versionId = activePlan(new PlanTerms(null, 0, Map.of()), monthlyMinor);
        inTx(() -> subscriptions.start(PILOT, versionId, null, MAKER, "the pilot", "corr"));
    }

    private UUID activePlan(PlanTerms terms, long monthlyMinor) {
        return activePlan("BASIC", "UZS", terms, monthlyMinor);
    }

    /**
     * An active plan version, named and priced.
     *
     * <p>The plan code and the currency are arguments rather than constants
     * because {@code plans.createPlan} refuses a code twice, so a fixture that
     * needs a second version — a version priced in another currency, say —
     * cannot have one otherwise.
     */
    private UUID activePlan(String planCode, String currency, PlanTerms terms, long monthlyMinor) {
        UUID planId = inTx(() -> plans.createPlan(planCode, planCode, MAKER, "the price list", "corr"));
        UUID versionId = inTx(() -> plans.draftVersion(
                planId, currency, monthlyMinor, "MONTHLY", null, Map.of(), terms, MAKER, "the 2026 prices", "corr"));
        inTxDo(() -> plans.activate(versionId, CHECKER, "signed off", "corr"));
        return versionId;
    }

    private StatementPayment statementPayment(String periodKey) {
        return statementPayment(PILOT, periodKey);
    }

    private StatementPayment statementPayment(UUID tenantId, String periodKey) {
        return wallet.statementPayments(tenantId).stream()
                .filter(payment -> payment.periodKey().equals(periodKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No issued statement for " + periodKey));
    }

    /** What one statement drew from the wallet: which money, which grant, how much left. */
    private List<Spend> spendsOn(String periodKey) {
        return spendsOn(PILOT, periodKey);
    }

    private List<Spend> spendsOn(UUID tenantId, String periodKey) {
        return jdbc.sql("""
                        SELECT w.money_kind, w.grant_id, w.amount_minor
                          FROM commercial.wallet_entries w
                          JOIN commercial.statements s ON s.id = w.statement_id
                         WHERE w.tenant_id = :id AND s.period_key = :periodKey
                           AND w.entry_type = 'STATEMENT_PAYMENT'
                        """)
                .param("id", tenantId)
                .param("periodKey", periodKey)
                .query((row, number) -> new Spend(
                        row.getString("money_kind"),
                        row.getObject("grant_id", UUID.class),
                        row.getLong("amount_minor")))
                .list();
    }

    /** Every reversal of one statement's draws: which money, which grant, how much came back. */
    private List<Spend> reversalRowsOn(UUID statementId) {
        return jdbc.sql("""
                        SELECT money_kind, grant_id, amount_minor
                          FROM commercial.wallet_entries
                         WHERE tenant_id = :id AND statement_id = :statementId
                           AND entry_type = 'STATEMENT_REVERSAL'
                        """)
                .param("id", PILOT)
                .param("statementId", statementId)
                .query((row, number) -> new Spend(
                        row.getString("money_kind"),
                        row.getObject("grant_id", UUID.class),
                        row.getLong("amount_minor")))
                .list();
    }

    private long grantRemaining(UUID grantId) {
        return grantRemaining(PILOT, grantId);
    }

    private long grantRemaining(UUID tenantId, UUID grantId) {
        return new JdbcWalletStore(jdbc).grantRemaining(tenantId, grantId);
    }

    private long lapsedAmount(UUID grantId) {
        return lapsedAmount(PILOT, grantId);
    }

    private long lapsedAmount(UUID tenantId, UUID grantId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(amount_minor), 0) FROM commercial.wallet_entries
                         WHERE tenant_id = :id AND grant_id = :grant AND entry_type = 'BONUS_EXPIRY'
                        """)
                .param("id", tenantId)
                .param("grant", grantId)
                .query(Long.class)
                .single();
    }

    private long reversalsFor(UUID grantId) {
        return jdbc.sql("""
                        SELECT count(*) FROM commercial.wallet_entries
                         WHERE tenant_id = :id AND grant_id = :grant AND entry_type = 'STATEMENT_REVERSAL'
                        """)
                .param("id", PILOT)
                .param("grant", grantId)
                .query(Long.class)
                .single();
    }

    private long ledgerSum(String moneyKind) {
        return ledgerSum(PILOT, moneyKind);
    }

    private long ledgerSum(UUID tenantId, String moneyKind) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(amount_minor), 0) FROM commercial.wallet_entries
                         WHERE tenant_id = :id AND money_kind = :kind
                        """)
                .param("id", tenantId)
                .param("kind", moneyKind)
                .query(Long.class)
                .single();
    }

    private long ledgerSize() {
        return ledgerSize(PILOT);
    }

    private long ledgerSize(UUID tenantId) {
        return jdbc.sql("SELECT count(*) FROM commercial.wallet_entries WHERE tenant_id = :id")
                .param("id", tenantId)
                .query(Long.class)
                .single();
    }

    /** Every wallet fact written for this tenant, oldest first. */
    private List<String> auditedActions() {
        return jdbc.sql("""
                        SELECT action_code FROM audit.audit_events
                         WHERE action_code LIKE 'commercial.wallet.%'
                         ORDER BY occurred_at, action_code
                        """).query(String.class).list();
    }

    /** What the checker's console is shown about one request, as stored beside it. */
    private Map<String, String> subjectOf(UUID requestId) {
        String json = jdbc.sql("SELECT subject_json::text FROM audit.approval_requests WHERE id = :id")
                .param("id", requestId)
                .query(String.class)
                .single();
        return JsonMapper.builder().build().readValue(json, new TypeReference<Map<String, String>>() {});
    }

    /** Every approval lifecycle fact whose change document names this tenant as the subject. */
    private List<String> lifecycleFactsNaming(UUID tenantId) {
        return jdbc.sql("""
                        SELECT action_code FROM audit.audit_events
                         WHERE action_code LIKE 'approval.%'
                           AND change_document->>'subjectTenantId' = :tenantId
                        """)
                .param("tenantId", tenantId.toString())
                .query(String.class)
                .list();
    }

    private String requestStatus(UUID requestId) {
        return jdbc.sql("SELECT status FROM audit.approval_requests WHERE id = :id")
                .param("id", requestId)
                .query(String.class)
                .single();
    }

    /** Ends the tenant's live subscription, the way a plan change does (SubscriptionStatus.allowedNext). */
    private void terminate(UUID tenantId) {
        Subscription live =
                subscriptions.live(tenantId).orElseThrow(() -> new AssertionError("No live subscription to terminate"));
        inTxDo(() -> subscriptions.transition(
                tenantId,
                SubscriptionStatus.TERMINATED,
                live.version(),
                null,
                null,
                MAKER,
                "the tenant moved to another plan",
                "corr"));
    }

    /**
     * The successful attempt recorded for a statement, whose id is the key the
     * provider was handed. There may be earlier ones for the same statement: a
     * declined pass is an attempt too, and is recorded as one.
     */
    private String chargeAttemptFor(UUID statementId) {
        return jdbc.sql("""
                        SELECT id::text FROM commercial.card_charge_attempts
                         WHERE tenant_id = :tenant AND statement_id = :statement AND outcome = 'SUCCEEDED'
                        """)
                .param("tenant", PILOT)
                .param("statement", statementId)
                .query(String.class)
                .single();
    }

    /** Every card attempt this tenant has, as outcome and whether it was settled. */
    private List<String> attemptOutcomes() {
        return jdbc.sql("""
                        SELECT outcome || ':' || CASE WHEN settled_at IS NULL THEN 'unsettled' ELSE 'settled' END
                          FROM commercial.card_charge_attempts WHERE tenant_id = :id
                         ORDER BY attempted_at, amount_minor DESC
                        """).param("id", PILOT).query(String.class).list();
    }

    private long pendingAttempts() {
        return jdbc.sql("SELECT count(*) FROM commercial.card_charge_attempts WHERE outcome = 'PENDING'")
                .query(Long.class)
                .single();
    }

    /** Raises a proposal and returns the approval request it is waiting on. */
    private UUID proposalRequest(Supplier<WalletChangeOutcome> proposal) {
        WalletChangeOutcome outcome = inTx(proposal);
        assertThat(outcome.status()).isEqualTo(WalletChangeOutcome.AWAITING_APPROVAL);
        return Objects.requireNonNull(outcome.approvalRequestId());
    }

    /** Whose account one request says it moves, as stored beside it. */
    private @Nullable UUID subjectTenantOf(UUID requestId) {
        return jdbc.sql("SELECT subject_tenant_id FROM audit.approval_requests WHERE id = :id")
                .param("id", requestId)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    /** The change document of the one audit fact with this action code. */
    private Map<String, String> changeDocument(String actionCode) {
        String json = jdbc.sql("SELECT change_document::text FROM audit.audit_events WHERE action_code = :code")
                .param("code", actionCode)
                .query(String.class)
                .single();
        return JsonMapper.builder().build().readValue(json, new TypeReference<Map<String, String>>() {});
    }

    /** A DEPOSIT row written straight into the ledger, to reach a constraint the service cannot. */
    private void insertDeposit(UUID tenantId, @Nullable UUID subscriptionId, String reference) {
        jdbc.sql("""
                        INSERT INTO commercial.wallet_entries (
                            id, tenant_id, money_kind, entry_type, amount_minor, currency,
                            subscription_id, external_reference, reason, recorded_by)
                        VALUES (:id, :tenant, 'PAID', 'DEPOSIT', 500000, 'UZS', :subscription,
                            :reference, 'the activation deposit', 'finance-1')
                        """)
                .param("id", Ids.newId())
                .param("tenant", tenantId)
                .param("subscription", subscriptionId)
                .param("reference", reference)
                .update();
    }

    private long depositDue(UUID subscriptionId) {
        return jdbc.sql("SELECT deposit_due_minor FROM commercial.subscriptions WHERE id = :id")
                .param("id", subscriptionId)
                .query(Long.class)
                .single();
    }

    /** One entry of a statement's settlement: which money, which grant, how much left the wallet. */
    private record Spend(String moneyKind, @Nullable UUID grantId, long amountMinor) {}

    /** A clock a test moves forward, so an expiry is lived through rather than asserted at an instant. */
    private static final class MovableClock extends Clock {
        private Instant now;

        MovableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
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
