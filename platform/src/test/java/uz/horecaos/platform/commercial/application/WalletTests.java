package uz.horecaos.platform.commercial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
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
import uz.horecaos.platform.commercial.domain.WalletBalances;
import uz.horecaos.platform.commercial.domain.WalletEntry;
import uz.horecaos.platform.commercial.infrastructure.NotConfiguredCardCharger;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcModuleStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcPlanStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcStatementStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcSubscriptionStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcUsageStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcWalletStore;
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
        jdbc.sql("""
                TRUNCATE TABLE commercial.wallet_entries, commercial.tenant_billing,
                    commercial.statement_lines, commercial.statements, commercial.subscriptions,
                    commercial.usage_events, commercial.usage_aggregates, commercial.usage_adjustments,
                    commercial.entitlement_overrides, commercial.tenant_modules, commercial.modules,
                    commercial.plan_entitlements, commercial.plan_versions, commercial.plans CASCADE
                """).update();
        jdbc.sql("DELETE FROM tenant.tenants WHERE id = :id").param("id", PILOT).update();
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'pilot', 'Non uyi', 'Non uyi', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", PILOT).update();

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

        approvals = new JdbcApprovalService(jdbc, audit, clock, new SimpleMeterRegistry());
        wallet = new WalletService(
                walletStore, subscriptionStore, approvals, new NotConfiguredCardCharger(), audit, clock);
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

    // ----------------------------------------------------------------- card

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
        WalletService withACharger = new WalletService(
                new JdbcWalletStore(jdbc),
                new JdbcSubscriptionStore(jdbc),
                approvals,
                (tenantId, token, amountMinor, currency, idempotencyKey) ->
                        new CardCharger.Outcome.Succeeded("CLICK-" + idempotencyKey),
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                clock);
        inTx(() -> withACharger.applyAvailableFunds(PILOT));

        assertThat(statementPayment("2026-09").dueMinor()).isZero();
        assertThat(wallet.ledger(PILOT, null, 10))
                .extracting(WalletEntry::entryType)
                .containsExactlyInAnyOrder(WalletEntry.TOP_UP, WalletEntry.STATEMENT_PAYMENT);
    }

    // ------------------------------------------------------------- helpers

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
        inTx(() -> wallet.recordTransfer(PILOT, amountMinor, bankReference, MAKER, "a bank transfer", "corr"));
    }

    /** Grants bonus money the way a person does: proposed, approved by somebody else, proposed again. */
    private UUID grantBonus(long amountMinor, Instant expiresAt) {
        applyChange(() -> wallet.proposeBonusGrant(PILOT, amountMinor, expiresAt, MAKER, "a launch credit", "corr"));
        return jdbc.sql("""
                        SELECT id FROM commercial.wallet_entries
                         WHERE tenant_id = :id AND entry_type = 'BONUS_GRANT' AND expires_at = :expiresAt
                        """)
                .param("id", PILOT)
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
        UUID planId = inTx(() -> plans.createPlan("BASIC", "Basic", MAKER, "the price list", "corr"));
        UUID versionId = inTx(() -> plans.draftVersion(
                planId, "UZS", monthlyMinor, "MONTHLY", null, Map.of(), terms, MAKER, "the 2026 prices", "corr"));
        inTxDo(() -> plans.activate(versionId, CHECKER, "signed off", "corr"));
        return versionId;
    }

    private StatementPayment statementPayment(String periodKey) {
        return wallet.statementPayments(PILOT).stream()
                .filter(payment -> payment.periodKey().equals(periodKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No issued statement for " + periodKey));
    }

    /** What one statement drew from the wallet: which money, which grant, how much left. */
    private List<Spend> spendsOn(String periodKey) {
        return jdbc.sql("""
                        SELECT w.money_kind, w.grant_id, w.amount_minor
                          FROM commercial.wallet_entries w
                          JOIN commercial.statements s ON s.id = w.statement_id
                         WHERE w.tenant_id = :id AND s.period_key = :periodKey
                           AND w.entry_type = 'STATEMENT_PAYMENT'
                        """)
                .param("id", PILOT)
                .param("periodKey", periodKey)
                .query((row, number) -> new Spend(
                        row.getString("money_kind"),
                        row.getObject("grant_id", UUID.class),
                        row.getLong("amount_minor")))
                .list();
    }

    private long grantRemaining(UUID grantId) {
        return new JdbcWalletStore(jdbc).grantRemaining(PILOT, grantId);
    }

    private long lapsedAmount(UUID grantId) {
        return jdbc.sql("""
                        SELECT COALESCE(SUM(amount_minor), 0) FROM commercial.wallet_entries
                         WHERE tenant_id = :id AND grant_id = :grant AND entry_type = 'BONUS_EXPIRY'
                        """)
                .param("id", PILOT)
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
        return jdbc.sql("""
                        SELECT COALESCE(SUM(amount_minor), 0) FROM commercial.wallet_entries
                         WHERE tenant_id = :id AND money_kind = :kind
                        """)
                .param("id", PILOT)
                .param("kind", moneyKind)
                .query(Long.class)
                .single();
    }

    private long ledgerSize() {
        return jdbc.sql("SELECT count(*) FROM commercial.wallet_entries WHERE tenant_id = :id")
                .param("id", PILOT)
                .query(Long.class)
                .single();
    }

    private String requestStatus(UUID requestId) {
        return jdbc.sql("SELECT status FROM audit.approval_requests WHERE id = :id")
                .param("id", requestId)
                .query(String.class)
                .single();
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
