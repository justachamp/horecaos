package uz.horecaos.platform.commercial.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
import uz.horecaos.platform.commercial.domain.StatementPayment;
import uz.horecaos.platform.commercial.domain.Subscription;
import uz.horecaos.platform.commercial.domain.SubscriptionStatus;
import uz.horecaos.platform.commercial.domain.WalletEntry;
import uz.horecaos.platform.commercial.infrastructure.NotConfiguredCardCharger;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcCardChargeAttemptStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcModuleStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcPlanStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcStatementStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcSubscriptionStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcUsageStore;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcWalletStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcConfigurationResolver;

/**
 * The wallet's one invariant that no constraint can hold: money is never spent
 * twice and no balance goes below zero (ADR 0095).
 *
 * <p>{@code commercial.wallet_entries} has no balance column, so PostgreSQL
 * cannot check a SUM; every rule that keeps a balance above zero, or a
 * statement from being paid past its total, is a read followed by a write in
 * application code under READ COMMITTED. The only thing joining the two is
 * {@code JdbcWalletStore.lockBilling}, whose {@code FOR UPDATE} on the
 * tenant's {@code commercial.tenant_billing} row serialises that tenant's
 * writers onto one at a time.
 *
 * <p><strong>Why this suite exists.</strong> Every other wallet test is
 * single-threaded, and a single-threaded test cannot see a lock. Delete the
 * three words {@code FOR UPDATE} from {@code JdbcWalletStore.lockBilling} and
 * the rest of the suite stays green while the ledger starts paying out money
 * nobody has. Each test here fails with those three words gone and passes with
 * them back, which is the only form in which a lock is actually asserted.
 *
 * <p>Each thread opens its own transaction, and therefore holds its own
 * connection: Spring binds a connection to the thread that began the
 * transaction, so one {@link TransactionTemplate} over the shared pool gives
 * two threads two connections. The barrier is tripped inside both
 * transactions, after each has begun and before either touches the wallet, so
 * the two really are in flight together rather than merely started together.
 */
class WalletConcurrencyTests {

    private static final UUID PILOT = UUID.fromString("018f6f4e-2100-7000-8000-0000000000c1");

    /** After V0211's approval policies become valid, exactly as {@code WalletTests} starts. */
    private static final Instant START = Instant.parse("2026-09-15T09:00:00Z");

    private static final Instant CLOSE = Instant.parse("2026-11-05T09:00:00Z");

    private static final ActorRef MAKER = ActorRef.user("finance-1", null);
    private static final ActorRef CHECKER = ActorRef.user("finance-2", null);

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
        jdbc.sql("TRUNCATE TABLE audit.approval_requests CASCADE").update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
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

        approvals = new JdbcApprovalService(
                jdbc,
                audit,
                clock,
                new SimpleMeterRegistry(),
                JsonMapper.builder().build());
        wallet = new WalletService(
                walletStore,
                subscriptionStore,
                new JdbcCardChargeAttemptStore(jdbc),
                approvals,
                new NotConfiguredCardCharger(),
                audit,
                new SimpleMeterRegistry(),
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
    }

    @Test
    void twoApprovedRefundsThatTogetherExceedThePaidBalanceLeaveOneRefused() {
        recordTransfer(1_000_000, "MT103-RACE");
        UUID first = approveRefund(600_000, "PAYOUT-A");
        UUID second = approveRefund(600_000, "PAYOUT-B");
        assertThat(first).isNotEqualTo(second);

        List<Outcome> outcomes = bothAtOnce(
                () -> wallet.proposeRefund(PILOT, 600_000, "PAYOUT-A", MAKER, "the tenant left", "corr"),
                () -> wallet.proposeRefund(PILOT, 600_000, "PAYOUT-B", MAKER, "the tenant left", "corr"));

        assertThat(outcomes)
                .as("each refund is approved and each is legal on its own; only together are they more "
                        + "money than the tenant ever paid, so exactly one of them may go through")
                .filteredOn(Outcome::succeeded)
                .hasSize(1);
        assertThat(outcomes)
                .filteredOn(outcome -> !outcome.succeeded())
                .singleElement()
                .satisfies(refused -> assertThat(refused.failure()).contains("below zero"));

        assertThat(wallet.balances(PILOT).paidMinor())
                .as("a negative paid balance is money the platform paid out and never received, written "
                        + "into a ledger no correction can edit and no constraint can refuse")
                .isEqualTo(400_000)
                .isEqualTo(ledgerSum(WalletEntry.PAID));
        assertThat(refundRows()).as("and the ledger holds one refund, not two").isEqualTo(1L);
    }

    @Test
    void twoTransfersArrivingAtOnceNeverPayMoreThanTheStatementOwes() {
        startOnPlan();
        clock.set(CLOSE);
        inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September close", "corr"));
        assertThat(statementPayment("2026-09").dueMinor()).isEqualTo(MONTHLY);

        // Two people at the reconciliation, each recording a genuine transfer of
        // 700 000 against a statement that owes 1 200 000. Each call appends its
        // own money and then settles from the balance it reads, and under READ
        // COMMITTED neither read sees the other's uncommitted row.
        bothAtOnce(
                () -> wallet.recordTransfer(PILOT, 700_000, "MT103-A", MAKER, "a bank transfer", "corr"),
                () -> wallet.recordTransfer(PILOT, 700_000, "MT103-B", MAKER, "a bank transfer", "corr"));

        assertThat(statementPayment("2026-09").paidMinor())
                .as("a statement paid more than it asks for is money taken out of the wallet against a "
                        + "debt already settled, and nothing in the schema can refuse it")
                .isEqualTo(MONTHLY);
        assertThat(statementPayment("2026-09").dueMinor()).isZero();
        assertThat(wallet.balances(PILOT).paidMinor())
                .as("what the statement did not need stays the tenant's")
                .isEqualTo(1_400_000 - MONTHLY)
                .isEqualTo(ledgerSum(WalletEntry.PAID));
    }

    @Test
    void aDepositRecordedWhileTheTenantChangesPlansNeverErasesAnObligationItDidNotRead() {
        clock.set(START);
        UUID planA = activePlan("BASIC", 500_000);
        UUID planB = activePlan("LARGE", 5_000_000);
        UUID onPlanA = inTx(() -> subscriptions.start(PILOT, planA, null, MAKER, "the pilot", "corr"));
        assertThat(depositDue(onPlanA)).isEqualTo(500_000);

        // One operator records the deposit while another moves the tenant onto
        // a plan whose activation deposit is ten times larger. recordDeposit
        // used to read the amount, the subscription and the currency in three
        // separate statements, and the billing lock it holds serialises wallet
        // writers and not subscription lifecycle -- so plan A's 500 000 could
        // be read and then written onto plan B's row as a clear to zero.
        bothAtOnce(() -> wallet.recordDeposit(PILOT, "MT103-DEP", MAKER, "the activation deposit", "corr"), () -> {
            terminate();
            return subscriptions.start(PILOT, planB, null, MAKER, "the bigger plan", "corr");
        });

        UUID onPlanB = subscriptionOn(planB);
        assertThat(onPlanB)
                .as("the plan change is the half of the race that must always land")
                .isNotNull();
        assertThat(obligationsAndWhatPaidThem())
                .as("whichever order the two landed in, every obligation is still either owed or paid by "
                        + "a deposit of exactly its size. Plan B's 4 500 000 going missing with nothing "
                        + "anywhere to show it -- no deposit line on any statement, and a ledger that "
                        + "records money rather than obligations -- is what this asserts against")
                .containsExactlyInAnyOrder(onPlanA + ":500000", onPlanB + ":5000000");
    }

    @Test
    void readingTheObligationHoldsItUntilTheDepositIsRecorded() throws Exception {
        clock.set(START);
        UUID onPlanA = inTx(() -> subscriptions.start(PILOT, activePlan("BASIC", 500_000), null, MAKER, "pilot", "c"));
        JdbcSubscriptionStore store = new JdbcSubscriptionStore(jdbc);
        CyclicBarrier gate = new CyclicBarrier(2);
        AtomicLong readerFinished = new AtomicLong();
        AtomicLong terminatorReturned = new AtomicLong();

        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<?> reader = threads.submit(() -> transactions.executeWithoutResult(status -> {
                assertThat(store.lockLiveDepositObligation(PILOT)).isPresent();
                trip(gate);
                // Long enough that a terminator which did not have to wait
                // would have returned well inside it.
                sleepFor(500);
                readerFinished.set(System.nanoTime());
            }));
            Future<?> terminator = threads.submit(() -> transactions.executeWithoutResult(status -> {
                trip(gate);
                Subscription live = store.findById(PILOT, onPlanA).orElseThrow();
                assertThat(store.transition(
                                PILOT,
                                onPlanA,
                                live.status(),
                                SubscriptionStatus.TERMINATED,
                                live.version(),
                                null,
                                null,
                                null,
                                clock.instant(),
                                clock.instant()))
                        .isTrue();
                terminatorReturned.set(System.nanoTime());
            }));
            reader.get(60, TimeUnit.SECONDS);
            terminator.get(60, TimeUnit.SECONDS);
        }

        assertThat(terminatorReturned.get())
                .as("the billing lock recordDeposit holds does not serialise subscription lifecycle -- "
                        + "neither start nor transition takes it -- so the obligation's own row is what "
                        + "has to be held. Without FOR UPDATE the terminate lands between the read and "
                        + "the clear, and the deposit clears an obligation it never read")
                .isGreaterThan(readerFinished.get());
    }

    @Test
    void twoSettlementPassesForTheSameTenantProduceOnePendingAttemptAndOneProviderCall() {
        startOnPlan();
        inTxDo(() ->
                wallet.setPaymentMethod(PILOT, PaymentMethod.CARD, "vault:pilot-card", MAKER, "the owner asked", "c"));
        clock.set(CLOSE);
        UUID september = inTx(() -> statements.issue(PILOT, "2026-09", MAKER, "September close", "corr"))
                .statementId();
        assertThat(statementPayment("2026-09").dueMinor()).isEqualTo(MONTHLY);

        // Two settlement passes for this tenant, exactly as two of the six
        // endpoints that call settleCardRemainders(tenantId) after their own
        // commit would if they landed close together. Neither runs inside a
        // transaction of its own -- settleCardRemainders refuses that -- so
        // the barrier only starts them together; wallet.lockBilling's FOR
        // UPDATE is what actually serialises the two beginCardAttempt calls,
        // and GatedCharger is what proves the second one, finding the first's
        // attempt already on file, asks the provider about it instead of
        // opening a second one under a key of its own.
        GatedCharger charger = new GatedCharger(new CardCharger.Outcome.Succeeded("CLICK-RACE"));
        WalletService racing = walletChargingWith(charger);
        CyclicBarrier gate = new CyclicBarrier(2);
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            List<Future<?>> passes = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                passes.add(threads.submit(() -> {
                    trip(gate);
                    racing.settleCardRemainders(PILOT);
                }));
            }
            for (Future<?> pass : passes) {
                pass.get(60, TimeUnit.SECONDS);
            }
        } catch (Exception failed) {
            throw new IllegalStateException("a racing settlement pass never finished", failed);
        }

        assertThat(charger.charges)
                .as("the attempt found already PENDING is asked about, not charged again while the first "
                        + "call's answer can instead be learned -- GatedCharger's status() waits for a "
                        + "concurrent charge() to conclude and mirrors it, exactly as a real provider "
                        + "answering both calls under the same key would")
                .hasSize(1);
        assertThat(cardChargeAttemptRows(september))
                .as("one statement, one attempt, however many settlement passes asked -- never a second "
                        + "row for the same remainder")
                .isEqualTo(1L);
        assertThat(statementPayment("2026-09").dueMinor()).isZero();
        assertThat(statementPayment("2026-09").paidMinor()).isEqualTo(MONTHLY);
        assertThat(walletEntryCount(WalletEntry.TOP_UP))
                .as("one charge, told to two racing passes, is still one deposit into the ledger -- not one "
                        + "per pass that asked")
                .isEqualTo(1L);
        assertThat(walletEntryCount(WalletEntry.STATEMENT_PAYMENT)).isEqualTo(1L);
        assertThat(auditedActions())
                .as("one card actually charged, audited once -- not twice for the two passes that asked "
                        + "about it, and never as a surplus: the second pass's report of the same attempt "
                        + "is recognised as the same attempt, not money the statement no longer owed")
                .filteredOn("commercial.wallet.card_charged"::equals)
                .hasSize(1);
        assertThat(auditedActions()).doesNotContain("commercial.wallet.card_charge_surplus_refused");
    }

    // ------------------------------------------------------------- fixtures

    private static void trip(CyclicBarrier gate) {
        try {
            gate.await(30, TimeUnit.SECONDS);
        } catch (Exception interrupted) {
            throw new IllegalStateException("the gate never opened", interrupted);
        }
    }

    private static void sleepFor(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while holding the obligation", interrupted);
        }
    }

    /** Every subscription's obligation plus the deposits that cleared it, which must equal what it sold. */
    private List<String> obligationsAndWhatPaidThem() {
        return jdbc.sql("""
                        SELECT s.id::text || ':'
                                 || (s.deposit_due_minor + COALESCE(SUM(w.amount_minor), 0))::text
                          FROM commercial.subscriptions s
                          LEFT JOIN commercial.wallet_entries w
                                 ON w.subscription_id = s.id AND w.tenant_id = s.tenant_id
                                AND w.entry_type = 'DEPOSIT'
                         WHERE s.tenant_id = :id
                         GROUP BY s.id, s.deposit_due_minor
                        """).param("id", PILOT).query(String.class).list();
    }

    private @Nullable UUID subscriptionOn(UUID planVersionId) {
        return jdbc.sql("SELECT id FROM commercial.subscriptions WHERE tenant_id = :id AND plan_version_id = :version")
                .param("id", PILOT)
                .param("version", planVersionId)
                .query(UUID.class)
                .optional()
                .orElse(null);
    }

    private long depositDue(UUID subscriptionId) {
        return jdbc.sql("SELECT deposit_due_minor FROM commercial.subscriptions WHERE id = :id")
                .param("id", subscriptionId)
                .query(Long.class)
                .single();
    }

    /** Ends the live subscription, the way a plan change does. */
    private void terminate() {
        Subscription live =
                subscriptions.live(PILOT).orElseThrow(() -> new AssertionError("No live subscription to terminate"));
        subscriptions.transition(
                PILOT,
                SubscriptionStatus.TERMINATED,
                live.version(),
                null,
                null,
                MAKER,
                "the tenant moved to another plan",
                "corr");
    }

    private UUID activePlan(String planCode, long activationDepositMinor) {
        UUID planId = inTx(() -> plans.createPlan(planCode, planCode, MAKER, "the price list", "corr"));
        UUID versionId = inTx(() -> plans.draftVersion(
                planId,
                "UZS",
                MONTHLY,
                "MONTHLY",
                null,
                Map.of(),
                new PlanTerms(null, activationDepositMinor, Map.of()),
                MAKER,
                "the 2026 prices",
                "corr"));
        inTxDo(() -> plans.activate(versionId, CHECKER, "signed off", "corr"));
        return versionId;
    }

    /**
     * Runs both pieces of work on their own threads, each in its own
     * transaction, released together by a barrier tripped after both
     * transactions have begun.
     */
    private List<Outcome> bothAtOnce(Supplier<?> left, Supplier<?> right) {
        CyclicBarrier gate = new CyclicBarrier(2);
        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Supplier<?> work : List.of(left, right)) {
                futures.add(threads.submit((Callable<Outcome>) () -> {
                    try {
                        return Objects.requireNonNull(transactions.execute(status -> {
                            try {
                                gate.await(30, TimeUnit.SECONDS);
                            } catch (Exception interrupted) {
                                throw new IllegalStateException("the gate never opened", interrupted);
                            }
                            var ignored = work.get();
                            return new Outcome(true, "");
                        }));
                    } catch (RuntimeException refused) {
                        return new Outcome(false, String.valueOf(refused.getMessage()));
                    }
                }));
            }
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                try {
                    outcomes.add(future.get(60, TimeUnit.SECONDS));
                } catch (Exception failed) {
                    throw new IllegalStateException("a racing thread never finished", failed);
                }
            }
            return List.copyOf(outcomes);
        }
    }

    /** Whether one racing call went through, and what it said if it did not. */
    private record Outcome(boolean succeeded, String failure) {}

    private <T> T inTx(Supplier<T> work) {
        return Objects.requireNonNull(transactions.execute(status -> work.get()));
    }

    private void inTxDo(Runnable work) {
        transactions.executeWithoutResult(status -> work.run());
    }

    private void recordTransfer(long amountMinor, String bankReference) {
        inTx(() -> wallet.recordTransfer(PILOT, amountMinor, bankReference, MAKER, "a bank transfer", "corr"));
    }

    /** Raises the refund and has CHECKER approve it, leaving a signature the race will try to spend. */
    private UUID approveRefund(long amountMinor, String payoutReference) {
        WalletChangeOutcome proposed =
                inTx(() -> wallet.proposeRefund(PILOT, amountMinor, payoutReference, MAKER, "the tenant left", "corr"));
        assertThat(proposed.status()).isEqualTo(WalletChangeOutcome.AWAITING_APPROVAL);
        UUID requestId = Objects.requireNonNull(proposed.approvalRequestId());
        inTxDo(() -> approvals.decide(requestId, ApprovalService.Decision.APPROVE, CHECKER, "checked"));
        return requestId;
    }

    private void startOnPlan() {
        clock.set(START);
        UUID planId = inTx(() -> plans.createPlan("BASIC", "Basic", MAKER, "the price list", "corr"));
        UUID versionId = inTx(() -> plans.draftVersion(
                planId,
                "UZS",
                MONTHLY,
                "MONTHLY",
                null,
                Map.of(),
                new PlanTerms(null, 0, Map.of()),
                MAKER,
                "the 2026 prices",
                "corr"));
        inTxDo(() -> plans.activate(versionId, CHECKER, "signed off", "corr"));
        inTx(() -> subscriptions.start(PILOT, versionId, null, MAKER, "the pilot", "corr"));
    }

    private StatementPayment statementPayment(String periodKey) {
        return wallet.statementPayments(PILOT).stream()
                .filter(payment -> payment.periodKey().equals(periodKey))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No issued statement for " + periodKey));
    }

    private long refundRows() {
        return jdbc.sql("""
                        SELECT count(*) FROM commercial.wallet_entries
                         WHERE tenant_id = :id AND entry_type = 'REFUND'
                        """).param("id", PILOT).query(Long.class).single();
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

    /** The same wallet, with a card charger wired: the port exists, only the merchant account does not. */
    private WalletService walletChargingWith(CardCharger charger) {
        return new WalletService(
                new JdbcWalletStore(jdbc),
                new JdbcSubscriptionStore(jdbc),
                new JdbcCardChargeAttemptStore(jdbc),
                approvals,
                charger,
                new JdbcAuditRecorder(jdbc, JsonMapper.builder().build()),
                new SimpleMeterRegistry(),
                transactions,
                clock);
    }

    /** How many attempts this statement has on file, settled or not -- never more than one at a time (V0222). */
    private long cardChargeAttemptRows(UUID statementId) {
        return jdbc.sql("""
                        SELECT count(*) FROM commercial.card_charge_attempts
                         WHERE tenant_id = :id AND statement_id = :statement
                        """)
                .param("id", PILOT)
                .param("statement", statementId)
                .query(Long.class)
                .single();
    }

    private long walletEntryCount(String entryType) {
        return jdbc.sql("""
                        SELECT count(*) FROM commercial.wallet_entries WHERE tenant_id = :id AND entry_type = :type
                        """)
                .param("id", PILOT)
                .param("type", entryType)
                .query(Long.class)
                .single();
    }

    private List<String> auditedActions() {
        return jdbc.sql("""
                        SELECT action_code FROM audit.audit_events
                         WHERE action_code LIKE 'commercial.wallet.%'
                         ORDER BY occurred_at, action_code
                        """).query(String.class).list();
    }

    /**
     * A charger that answers a canned outcome to {@code charge()}, and makes
     * {@code status()} wait for a concurrent {@code charge()} call to
     * conclude and then mirror its answer -- exactly what a real provider
     * does for two calls under the same idempotency key, one asking to
     * charge and one only asking what happened. Proves that the settlement
     * pass which finds an attempt already PENDING learns the first call's
     * answer instead of dialing the provider a second time.
     */
    private static final class GatedCharger implements CardCharger {

        private final Outcome answer;
        private final List<String> charges = new CopyOnWriteArrayList<>();
        private final List<String> statusChecks = new CopyOnWriteArrayList<>();
        private final CountDownLatch charged = new CountDownLatch(1);

        GatedCharger(Outcome answer) {
            this.answer = answer;
        }

        @Override
        public Outcome charge(
                UUID tenantId,
                @Nullable String cardTokenReference,
                long amountMinor,
                String currency,
                String idempotencyKey) {
            charges.add(idempotencyKey);
            charged.countDown();
            return answer;
        }

        @Override
        public StatusOutcome status(String idempotencyKey) {
            statusChecks.add(idempotencyKey);
            try {
                if (!charged.await(30, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("the concurrent charge() never completed");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while waiting for a concurrent charge()", interrupted);
            }
            return answer instanceof Outcome.Succeeded succeeded
                    ? new StatusOutcome.Succeeded(succeeded.providerReference())
                    : new StatusOutcome.NotSucceeded();
        }
    }

    /** A clock a test moves forward; set before any worker thread starts, and read from several. */
    private static final class MovableClock extends Clock {
        private volatile Instant now;

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
