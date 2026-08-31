package uz.horecaos.platform.loyalty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.ApprovalOutcome;
import uz.horecaos.platform.audit.api.ApprovalRequestCommand;
import uz.horecaos.platform.audit.api.ApprovalService;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.loyalty.api.HeldTenderPort;
import uz.horecaos.platform.loyalty.api.PointsRedemptionPort;
import uz.horecaos.platform.loyalty.application.LoyaltyAccrualService;
import uz.horecaos.platform.loyalty.application.LoyaltyAdjustmentService;
import uz.horecaos.platform.loyalty.application.LoyaltyMaintenanceService;
import uz.horecaos.platform.loyalty.application.LoyaltyPolicyService;
import uz.horecaos.platform.loyalty.application.LoyaltyQueryService;
import uz.horecaos.platform.loyalty.application.PointsRedemptionService;
import uz.horecaos.platform.loyalty.domain.EntryType;
import uz.horecaos.platform.loyalty.domain.LotStatus;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.EntryRow;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.LedgerDrift;
import uz.horecaos.platform.loyalty.infrastructure.persistence.JdbcLoyaltyStore.LotRow;
import uz.horecaos.platform.payments.settlement.JdbcSettlementStore;
import uz.horecaos.platform.payments.settlement.OrderSettlementService;
import uz.horecaos.platform.payments.settlement.OrderSettlementService.PlannedTender;
import uz.horecaos.platform.payments.settlement.OrderSettlementService.SettlementPlan;
import uz.horecaos.platform.payments.settlement.SettlementStatus;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.web.api.ApiException;

/**
 * The points ledger, split tender, and the three properties that make points not
 * money (ADR 0046).
 *
 * <p>Against a real PostgreSQL, because most of what this ADR decided is a
 * property of the database. Whether two tabs can both spend one balance is a
 * question about a conditional UPDATE. Whether a hotfix can rewrite a disputed
 * entry is a question about a GRANT. Whether a balance tender can ever name a
 * payment intent is a question about a CHECK constraint. None of those can be
 * asserted against a mock, and every one of them is the thing that would
 * actually go wrong.
 *
 * <p>Several tests below assert an <em>absence</em> — that no command produces a
 * money movement, that a refund returns no more money than money was tendered,
 * that closure forfeits rather than pays out. Those are the tests worth having:
 * the ADR's central claim is negative, and a positive-only suite would pass on
 * an implementation that quietly grew a payout path.
 */
class LoyaltyLedgerAndSplitTenderTests {

    /**
     * The sweep's question answered "nobody is waiting", which is what these
     * tests mean.
     *
     * <p>{@link HeldTenderPort} exists because a hold stopped meaning only "a
     * checkout in flight" once the settlement seam was wired into checkout: a
     * cash order's balance tender stays held until handover. The tests in this
     * class are about the ledger rather than about that distinction — they take
     * holds directly, with no order behind them — and a tender nobody is waiting
     * on is exactly what the port is documented to answer false for. The sweep
     * therefore behaves here as it always did, and the live-order case is pinned
     * where it belongs, against a real order, in {@code CartCheckoutAndOrderTests}.
     */
    private static final HeldTenderPort NOTHING_AWAITS = (tenantId, tenderId) -> false;

    /**
     * The other answer, for the four tests that are about the question itself.
     *
     * <p>{@link #NOTHING_AWAITS} is the right default for a class whose holds
     * have no order behind them, and it is deliberately left alone: changing the
     * shared constant would silently turn every other sweep assertion here into
     * an assertion about renewal. The tests below that need a live tender build
     * their own {@link LoyaltyMaintenanceService} around this one instead, which
     * is also how the sweep is assembled in production — the port is a
     * constructor argument precisely so the answer can come from somewhere that
     * knows.
     */
    private static final HeldTenderPort SETTLEMENT_STILL_COMING = (tenantId, tenderId) -> true;

    /**
     * Past {@code PointsRedemptionService.HOLD_LIFETIME}, which is
     * package-private to {@code ..loyalty.application} and so cannot be named
     * from here. Restating it as a literal is the point of the tests below: if
     * the constant moves, the sweep tests should have to be looked at.
     */
    private static final Duration PAST_THE_HOLD_LIFETIME = Duration.ofMinutes(31);

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();

    /** A Monday lunchtime in Tashkent. */
    private static final Instant NOW = Instant.parse("2026-08-24T07:00:00Z");

    /**
     * When the accrual and redemption rules start applying.
     *
     * <p>Derived from {@link #NOW}, the clock the services are given, and not
     * from the database's. These rows said {@code now() - interval '1 day'},
     * which reads as safely in the past and is not: {@code now()} is the real
     * clock, {@link #NOW} is a fixed instant, and the two drift apart by a day
     * every day. Once real time crossed 07:00Z the rules became effective
     * <em>after</em> the moment the services ask about, and every redemption
     * refused with "Redemption is not enabled for this brand" — which is true of
     * the fixture and says nothing about the code.
     *
     * <p>A fixture's clock is the test's clock. Reaching for the database's is
     * how a suite starts depending on what time of day it runs.
     */
    private static final java.time.OffsetDateTime VALID_FROM =
            NOW.minus(java.time.Duration.ofDays(1)).atOffset(java.time.ZoneOffset.UTC);

    private static TestDatabase.Handle db;

    private DataSource dataSource;
    private JdbcClient jdbc;
    private JdbcLoyaltyStore store;
    private JdbcSettlementStore settlementStore;
    private TransactionTemplate transactions;
    private MutableClock clock;

    private PointsRedemptionService redemption;
    private LoyaltyAccrualService accrual;
    private LoyaltyAdjustmentService adjustments;
    private LoyaltyMaintenanceService maintenance;
    private LoyaltyQueryService queries;
    private OrderSettlementService settlements;
    private RecordingAudit audit;

    private UUID locationId;
    private UUID otherLocationId;
    private UUID channelId;
    private UUID publicationId;
    private UUID customerId;
    private UUID otherCustomerId;
    private UUID cashMethod;
    private UUID clickMethod;
    private UUID pointsMethod;

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
        jdbc.sql("TRUNCATE TABLE payments.tenders, payments.order_settlements, " + "payments.payment_methods CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE ordering.order_lines, ordering.orders, ordering.carts CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE pricing.quotes CASCADE").update();
        jdbc.sql("TRUNCATE TABLE catalog.publications, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE customer.customer_accounts CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = new MutableClock(NOW);
        store = new JdbcLoyaltyStore(jdbc);
        settlementStore = new JdbcSettlementStore(jdbc);
        audit = new RecordingAudit();
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        LoyaltyPolicyService policies = new LoyaltyPolicyService(store);
        redemption = new PointsRedemptionService(store, policies, clock);
        accrual = new LoyaltyAccrualService(store, policies, clock);
        adjustments = new LoyaltyAdjustmentService(store, new AlwaysApproves(), audit, clock, 100_000L);
        maintenance = new LoyaltyMaintenanceService(store, redemption, NOTHING_AWAITS, clock);
        queries = new LoyaltyQueryService(store, clock);
        settlements = new OrderSettlementService(settlementStore, redemption, clock);

        seedTenancy();
        seedPolicies();
        seedPaymentMethods();
    }

    /**
     * The one invariant this suite was missing, asserted after every test in it
     * rather than in a test of its own.
     *
     * <p>V0042 states it in prose on {@code accounts.balance_minor} — "equals
     * {@code SUM(entries.amount_minor)} for this account at all times" — and
     * {@code LoyaltyQueryService.balanceDrift} exists to compute it and says in
     * its own javadoc that it must be zero. Nothing asserted it anywhere. The
     * tests that did reach for it named one account each, so an account a test
     * touched only in passing was never asked.
     *
     * <p>It is here, and it is over <em>every</em> account in the database,
     * because the two defects it was written for were both invisible to the
     * invariant this suite did assert everywhere. {@code balance_minor ==
     * SUM(lots.remaining_minor)} stayed green through a clawback that debited a
     * balance without writing an entry — the lots and the balance moved together
     * — and green again through a write-off that wrote an entry without moving a
     * balance. Only the ledger's own identity fails both.
     *
     * <p>Signed and per account. The two halves drifted in opposite directions,
     * so a check that summed the drift across accounts could have read zero with
     * both of them broken.
     *
     * <p>An assertion here fires after a failing test as well, which is noise
     * beside a genuine failure and is worth it: the alternative is a suite where
     * the money bug is only ever found by whichever test happened to look.
     */
    @AfterEach
    void everyBalanceIsExactlyItsOwnLedger() {
        if (store == null) {
            return;
        }
        List<LedgerDrift> drifting = store.driftingAccounts(500);
        assertThat(drifting)
                .as("every points balance is the sum of its own movements. A balance that moved "
                        + "with no entry, and an entry with no movement behind it, are the same "
                        + "defect from two sides and this is the only check that sees both")
                .isEmpty();
    }

    // ------------------------------------------------------- the golden ledger

    @Test
    @DisplayName("golden fixture: accrual, maturity, redemption across lots, expiry and refund "
            + "reproduce the balance entry by entry")
    void theLedgerReproducesTheBalance() {
        UUID first = completedOrder("A-1", 100_000L, 0L);
        UUID second = completedOrder("A-2", 200_000L, 0L);

        // 3% of each, deferred 24 hours.
        transactions.executeWithoutResult(status -> accrual.accrue(completion(first, 100_000L, 0L)));
        clock.advance(Duration.ofHours(1));
        transactions.executeWithoutResult(status -> accrual.accrue(completion(second, 200_000L, 0L)));

        UUID accountId = accountId();
        assertThat(queries.balance(TENANT, accountId).balanceMinor())
                .as("the balance carries what was earned the moment it was earned")
                .isEqualTo(9_000L);
        assertThat(queries.balance(TENANT, accountId).spendableMinor())
                .as("and none of it is spendable until the earn delay elapses, which is what "
                        + "stops a cancelled order clawing back points already spent")
                .isZero();

        clock.advance(Duration.ofHours(30));
        transactions.executeWithoutResult(status -> maintenance.matureLots());
        assertThat(queries.balance(TENANT, accountId).spendableMinor()).isEqualTo(9_000L);

        // Spend 4 000: the whole of the first lot (3 000, expiring first) and
        // 1 000 of the second.
        UUID spendOrder = completedOrder("A-3", 100_000L, 10_000L);
        UUID tenderId = splitTender(spendOrder, 100_000L, 4_000L);

        List<EntryRow> redemptions = store.entriesOfTender(TENANT, tenderId);
        assertThat(redemptions)
                .as("one entry per lot, because the lot is what expires and one aggregate debit "
                        + "could not say which expiry the customer just used up")
                .hasSize(2);
        assertThat(redemptions.get(0).amountMinor()).isEqualTo(-3_000L);
        assertThat(redemptions.get(1).amountMinor()).isEqualTo(-1_000L);
        assertThat(lotRemaining(redemptions.get(0).lotId()))
                .as("oldest-expiry-first: the earlier lot is exhausted before the later one is " + "touched")
                .isZero();

        assertThat(queries.balance(TENANT, accountId).balanceMinor()).isEqualTo(5_000L);
        assertThat(queries.balanceDrift(TENANT, accountId))
                .as("the stored balance is exactly the sum of the movements, at every point")
                .isZero();

        // Refund the whole order: the money tender first, then the points.
        transactions.executeWithoutResult(
                status -> settlements.recordTenderSettled(TENANT, spendOrder, tenderId, "test"));
        settleMoneyTenders(spendOrder);
        long asMoney = transactions.execute(
                status -> settlements.refund(TENANT, spendOrder, 100_000L, "ORDER_REFUNDED", "test"));

        assertThat(asMoney)
                .as("a full refund of a points-settled order returns at most the money tendered")
                .isEqualTo(96_000L);
        assertThat(queries.balance(TENANT, accountId).balanceMinor())
                .as("and the points come back as points")
                .isEqualTo(9_000L);
        assertThat(queries.balanceDrift(TENANT, accountId)).isZero();
    }

    @Test
    @DisplayName("two partial refunds cannot return more than the tender settled")
    void repeatedPartialRefundsCannotExceedTheTender() {
        // Enough matured points to fund a small points portion, so the order
        // settles across one points tender and one money tender.
        UUID earnOrder = completedOrder("C-1", 1_000_000L, 0L);
        transactions.executeWithoutResult(status -> accrual.accrue(completion(earnOrder, 1_000_000L, 0L)));
        clock.advance(Duration.ofHours(25));
        transactions.executeWithoutResult(status -> maintenance.matureLots());

        UUID order = completedOrder("C-2", 100_000L, 0L);
        UUID pointsTender = splitTender(order, 100_000L, 4_000L);
        transactions.executeWithoutResult(
                status -> settlements.recordTenderSettled(TENANT, order, pointsTender, "test"));
        settleMoneyTenders(order);

        // 96 000 of money is on the table. Take 60 000 of it.
        long first =
                transactions.execute(status -> settlements.refund(TENANT, order, 60_000L, "ORDER_REFUNDED", "test"));
        assertThat(first).isEqualTo(60_000L);

        // The money tender is still SETTLED, because it was not consumed exactly.
        // Before the cumulative cap it was therefore refundable in full again, so
        // this second call returned another 60 000 -- 120 000 out of a 100 000
        // order, with the excess coming out of the points tender as cash.
        assertThatThrownBy(() -> transactions.execute(
                        status -> settlements.refund(TENANT, order, 60_000L, "ORDER_REFUNDED", "test")))
                .as("the tender has 36 000 left, not another 60 000")
                .isInstanceOf(ApiException.class);

        // What genuinely remains is still refundable, exactly once.
        long second =
                transactions.execute(status -> settlements.refund(TENANT, order, 40_000L, "ORDER_REFUNDED", "test"));
        assertThat(second)
                .as("36 000 of money is left; the remaining 4 000 comes back as points")
                .isEqualTo(36_000L);
    }

    @Test
    @DisplayName("a reversal restores a lot at its original expiry, not at a fresh one")
    void aReversalDoesNotResetTheExpiryClock() {
        UUID order = completedOrder("B-1", 1_000_000L, 0L);
        transactions.executeWithoutResult(status -> accrual.accrue(completion(order, 1_000_000L, 0L)));
        clock.advance(Duration.ofHours(25));
        transactions.executeWithoutResult(status -> maintenance.matureLots());

        UUID accountId = accountId();
        Instant originalExpiry = store.openLots(TENANT, accountId).get(0).expiresAt();

        UUID spendOrder = completedOrder("B-2", 100_000L, 0L);
        UUID tenderId = splitTender(spendOrder, 100_000L, 5_000L);
        transactions.executeWithoutResult(
                status -> settlements.recordTenderSettled(TENANT, spendOrder, tenderId, "test"));

        // Six weeks later. If the reversal reset the clock, the returned points
        // would outlive the ones that were never spent.
        clock.advance(Duration.ofDays(42));
        transactions.executeWithoutResult(
                status -> redemption.reverse(TENANT, tenderId, 5_000L, "SERVICE_RECOVERY", "test"));

        assertThat(store.openLots(TENANT, accountId).get(0).expiresAt())
                .as("points three days from expiry when spent are three days from expiry when "
                        + "returned; resetting the clock is a giveaway that compounds")
                .isEqualTo(originalExpiry);
    }

    @Test
    @DisplayName("expiry destroys the remainder and says which lot it was")
    void expiryDestroysValueAndExplainsItself() {
        UUID order = completedOrder("C-1", 100_000L, 0L);
        transactions.executeWithoutResult(status -> accrual.accrue(completion(order, 100_000L, 0L)));
        clock.advance(Duration.ofDays(200));

        transactions.executeWithoutResult(status -> maintenance.expireLots());

        UUID accountId = accountId();
        assertThat(queries.balance(TENANT, accountId).balanceMinor()).isZero();
        assertThat(queries.entries(TENANT, accountId))
                .as("the customer asking where 3 000 went is answered with the lot and the date, "
                        + "not with a smaller number")
                .anyMatch(entry -> entry.entryType() == EntryType.EXPIRY
                        && entry.amountMinor() == -3_000L
                        && entry.lotId() != null);
        assertThat(queries.balanceDrift(TENANT, accountId)).isZero();
    }

    /**
     * The sweep reads a batch of lots and then works through it, and a redemption
     * commits in that gap as a matter of course — the customer opens the app while
     * the sweep is running. Expiring the figure the batch carried destroys the
     * points that redemption has already taken, a second time.
     *
     * <p>The store below hands the sweep the row as it was a moment before, which
     * is exactly what a plain read gives it.
     */
    @Test
    @DisplayName("expiry destroys what the lot still holds, not what the sweep read")
    void expiryDoesNotDestroyPointsARedemptionAlreadyTook() {
        seedBalance(4_000L);
        UUID accountId = accountId();
        LotRow asTheSweepSawIt = store.openLots(TENANT, accountId).getFirst();

        // The redemption's own transaction, which commits between the batch read
        // and the loop: 3 000 off the lot and out of the balance, into a hold.
        transactions.executeWithoutResult(status -> {
            store.debitBalance(TENANT, accountId, 3_000L, clock.instant());
            store.consumeLot(TENANT, asTheSweepSawIt.id(), 3_000L, clock.instant());
            // Recorded as an ADJUSTMENT rather than a REDEMPTION only because a
            // redemption entry must name the order and the tender it settled, and
            // this test has neither. What matters here is that the ledger and the
            // cached balance agree, so the drift assertion below means something.
            store.appendEntry(
                    new JdbcLoyaltyStore.NewEntry(
                            UUID.randomUUID(),
                            TENANT,
                            accountId,
                            EntryType.ADJUSTMENT,
                            -3_000L,
                            1_000L,
                            asTheSweepSawIt.id(),
                            null,
                            null,
                            null,
                            null,
                            "SPENT_WHILE_THE_SWEEP_RAN",
                            "checkout",
                            null,
                            "SPEND:" + UUID.randomUUID(),
                            clock.instant()),
                    clock.instant());
        });

        // Past the lot's own expiry, which the fixture had stopped doing. The
        // seeded lot lives 180 days and the sweep it is handed to is the real
        // one: expireLot carries expires_at <= :now in its WHERE clause so that
        // it can never be the statement that expires a lot with life left in it,
        // whatever a caller believes. A fixture's clock is the test's clock, and
        // a sweep run at a moment when nothing is due destroys nothing and proves
        // nothing.
        clock.advance(Duration.ofDays(200));

        var sweep = new LoyaltyMaintenanceService(
                new StaleBatchLoyaltyStore(jdbc, asTheSweepSawIt), redemption, NOTHING_AWAITS, clock);
        transactions.executeWithoutResult(status -> sweep.expireLots());

        assertThat(queries.balance(TENANT, accountId).balanceMinor())
                .as("the 3 000 the customer spent is not destroyed twice")
                .isZero();
        assertThat(queries.entries(TENANT, accountId))
                .anyMatch(entry -> entry.entryType() == EntryType.EXPIRY && entry.amountMinor() == -1_000L);
        assertThat(queries.balanceDrift(TENANT, accountId))
                .as("and the ledger still reconciles to the cached balance")
                .isZero();
    }

    /**
     * A threshold that only ever looks at the command in front of it is beaten by
     * anyone who can divide. Three credits of 40 000 are the 120 000 credit the
     * operator was not allowed to make in one go.
     */
    @Test
    @DisplayName("adjustments split under the threshold still reach it in aggregate")
    void theApprovalThresholdIsAggregate() {
        assertThat(adjustBy(40_000L, "split-1")).isInstanceOf(ApprovalOutcome.NotRequired.class);
        assertThat(adjustBy(40_000L, "split-2"))
                .as("80 000 of 100 000: still an ordinary support gesture")
                .isInstanceOf(ApprovalOutcome.NotRequired.class);

        assertThat(adjustBy(40_000L, "split-3"))
                .as("120 000 in a day is the movement the threshold exists for, whether it "
                        + "arrived in one command or three")
                .isInstanceOf(ApprovalOutcome.Approved.class);

        // The window rolls. A day later the operator is back to their ordinary
        // discretion, which is what stops the control turning into a ratchet that
        // eventually needs approval for everything.
        clock.advance(Duration.ofHours(25));
        assertThat(adjustBy(40_000L, "split-4")).isInstanceOf(ApprovalOutcome.NotRequired.class);
    }

    // ------------------------------------------------------------ concurrency

    @Test
    @DisplayName("two checkouts against one balance settle once; the loser is refused rather " + "than overdrawing")
    void concurrentRedemptionsSpendTheBalanceOnce() throws Exception {
        seedBalance(5_000L);

        UUID orderOne = completedOrder("D-1", 100_000L, 0L);
        UUID orderTwo = completedOrder("D-2", 100_000L, 0L);

        List<Throwable> failures = new CopyOnWriteArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<?>> attempts = new ArrayList<>();
            for (UUID order : List.of(orderOne, orderTwo)) {
                attempts.add(pool.submit(() -> {
                    Throwable failure = catchThrowable(() -> splitTender(order, 100_000L, 5_000L));
                    if (failure != null) {
                        failures.add(failure);
                    }
                }));
            }
            for (Future<?> attempt : attempts) {
                attempt.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(failures)
                .as("exactly one of two concurrent checkouts may spend one balance")
                .hasSize(1);
        assertThat(failures.get(0)).isInstanceOf(ApiException.class);

        UUID accountId = accountId();
        assertThat(queries.balance(TENANT, accountId).balanceMinor())
                .as("the loser never produces a negative balance")
                .isZero();
        assertThat(queries.balanceDrift(TENANT, accountId)).isZero();
    }

    // ------------------------------------------------ points are not money

    @Test
    @DisplayName("the ledger admits no movement that funds or drains an account")
    void theDatabaseRejectsTopUpAndWithdrawal() {
        seedBalance(5_000L);
        UUID accountId = accountId();

        for (String forbidden : List.of("TOPUP", "WITHDRAWAL", "PAYOUT")) {
            Throwable refusal = catchThrowable(() -> jdbc.sql("""
                    INSERT INTO loyalty.entries (id, tenant_id, account_id, entry_type,
                        amount_minor, balance_after_minor, reason_code, actor, idempotency_key,
                        occurred_at)
                    VALUES (:id, :tenantId, :accountId, :type, 1000, 6000, 'X', 'x', :key, now())
                    """)
                    .param("id", UUID.randomUUID())
                    .param("tenantId", TENANT)
                    .param("accountId", accountId)
                    .param("type", forbidden)
                    .param("key", forbidden)
                    .update());

            assertThat(refusal)
                    .as(
                            "%s must be refused by the database, not merely absent from an " + "application enum",
                            forbidden)
                    .isNotNull();
        }
    }

    @Test
    @DisplayName("the application role cannot rewrite a recorded movement")
    void theLedgerIsAppendOnlyAtTheGrantLevel() {
        List<String> privileges = jdbc.sql("""
                SELECT privilege_type FROM information_schema.role_table_grants
                 WHERE table_schema = 'loyalty' AND table_name = 'entries'
                   AND grantee = 'horecaos_application'
                """).query(String.class).list();

        // The grant, not a convention. A convention survives until the first
        // hotfix that needs to "just correct one row"; a missing UPDATE grant
        // does not, and a disputed figure must never be able to become an
        // unrecorded one.
        assertThat(privileges).containsExactlyInAnyOrder("SELECT", "INSERT");
    }

    @Test
    @DisplayName("a trigger refuses the rewrite the grant does not reach")
    void theLedgerIsAppendOnlyAtTheTriggerLevel() {
        seedBalance(5_000L);

        // Migrations, psql sessions, and superuser connections route around a
        // GRANT. They do not route around this.
        assertThat(catchThrowable(() -> jdbc.sql("UPDATE loyalty.entries SET amount_minor = 999999")
                        .update()))
                .isNotNull();
        assertThat(catchThrowable(() -> jdbc.sql("DELETE FROM loyalty.entries").update()))
                .isNotNull();
    }

    @Test
    @DisplayName("a balance tender can never name a payment intent")
    void pointsCannotReachAProvider() {
        UUID order = completedOrder("E-1", 100_000L, 0L);
        UUID settlementId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payments.order_settlements (id, tenant_id, order_id, currency,
                    total_due_minor, settled_minor, status)
                VALUES (:id, :tenantId, :orderId, 'UZS', 100000, 0, 'PLANNED')
                """)
                .param("id", settlementId)
                .param("tenantId", TENANT)
                .param("orderId", order)
                .update();

        // The structural form of "not withdrawable": platform-held value has no
        // column in which it could become an outbound provider call, so nothing
        // can inflate a Click received_ecash with it even by a mistaken insert.
        Throwable refusal = catchThrowable(() -> jdbc.sql("""
                INSERT INTO payments.tenders (id, tenant_id, settlement_id, sequence,
                    payment_method_id, settles_from_balance, amount_minor, currency, status,
                    payment_intent_id, idempotency_key)
                VALUES (:id, :tenantId, :settlementId, 1, :methodId, true, 5000, 'UZS',
                        'RESERVED', :intentId, 'k')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("settlementId", settlementId)
                .param("methodId", pointsMethod)
                .param("intentId", UUID.randomUUID())
                .update());

        assertThat(refusal).isNotNull();
    }

    @Test
    @DisplayName("a redemption against another customer's order is refused")
    void pointsAreNotTransferableToAnotherPerson() {
        seedBalance(5_000L);
        UUID foreignOrder = orderFor(otherCustomerId, "F-1", 100_000L, 0L, BRAND, locationId);

        Throwable refusal = catchThrowable(() ->
                transactions.executeWithoutResult(status -> redemption.reserve(new PointsRedemptionPort.ReserveCommand(
                        TENANT,
                        BRAND,
                        customerId,
                        foreignOrder,
                        seedBareTender(foreignOrder, 5_000L),
                        5_000L,
                        "UZS",
                        "k-foreign",
                        "test"))));

        assertThat(refusal)
                .as("the order's customer is read from the order, not accepted from the caller")
                .isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("points earned at one brand cannot be spent at another")
    void pointsDoNotCrossBrands() {
        seedBalance(5_000L);
        UUID otherBrandOrder = orderFor(customerId, "G-1", 100_000L, 0L, OTHER_BRAND, otherLocationId);

        Throwable refusal = catchThrowable(() ->
                transactions.executeWithoutResult(status -> redemption.reserve(new PointsRedemptionPort.ReserveCommand(
                        TENANT,
                        BRAND,
                        customerId,
                        otherBrandOrder,
                        seedBareTender(otherBrandOrder, 5_000L),
                        5_000L,
                        "UZS",
                        "k-brand",
                        "test"))));

        assertThat(refusal).isInstanceOf(ApiException.class);

        // And the accounts themselves are separate rows, so there is nowhere a
        // pooled balance could live even if the check were removed.
        transactions.executeWithoutResult(status -> accrual.accrue(new LoyaltyAccrualService.CompletedOrder(
                TENANT,
                OTHER_BRAND,
                otherLocationId,
                channelId,
                customerId,
                otherBrandOrder,
                "UZS",
                100_000L,
                0L,
                clock.instant())));
        assertThat(queries.balancesOfCustomer(TENANT, customerId))
                .as("one customer, two brand balances, each labelled by the brand that will "
                        + "honour it. A read, not a pool")
                .hasSize(2)
                .allSatisfy(view -> assertThat(view.balanceMinor()).isGreaterThan(0L));
    }

    @Test
    @DisplayName("closing an account forfeits the balance and pays out nothing")
    void closureForfeitsRatherThanPaysOut() {
        seedBalance(5_000L);
        UUID accountId = accountId();

        long forfeited = transactions.execute(status -> adjustments.forfeit(
                TENANT, accountId, "ACCOUNT_CLOSED", ActorRef.user("support-1", "Support"), "corr-1"));

        assertThat(forfeited).isEqualTo(5_000L);
        assertThat(queries.balance(TENANT, accountId).balanceMinor()).isZero();
        assertThat(queries.entries(TENANT, accountId))
                .as("a FORFEITURE, and no movement that resembles a payment")
                .anyMatch(entry -> entry.entryType() == EntryType.FORFEITURE);
        assertThat(store.entries(TENANT, accountId, 100))
                .as("the entries are retained; only the value is destroyed")
                .isNotEmpty();
    }

    /**
     * A clawback the balance cannot cover charges the shortfall to the tenant,
     * and the shortfall is not a movement on the customer's ledger.
     *
     * <p>It used to be written as a {@code WRITE_OFF} entry of {@code -2 000} on
     * the customer's account with nothing moving to match it, which is the one
     * thing a ledger entry may never be. The balance was already at zero and the
     * floor is a CHECK constraint, so there was no movement available to it: an
     * entry that cannot move a balance is not an entry, it is a liability fact
     * about the tenant wearing an entry's clothes, and it left the account
     * 2 000 out of step with its own movements for ever.
     */
    @Test
    @DisplayName(
            "a clawback larger than the balance is charged to the tenant and leaves the " + "customer's books balanced")
    void aShortfallIsWrittenOffRatherThanGoingNegative() {
        seedBalance(1_000L);
        UUID order = completedOrder("H-1", 100_000L, 0L);

        long written = transactions.execute(
                status -> adjustments.clawBack(TENANT, BRAND, customerId, 3_000L, order, "loyalty-clawback"));

        assertThat(written).isEqualTo(2_000L);
        assertThat(queries.balance(TENANT, accountId()).balanceMinor())
                .as("never a negative balance the customer finds on their next order")
                .isZero();
        assertThat(queries.entries(TENANT, accountId()))
                .as("and never an entry the balance did not move for")
                .noneMatch(entry -> entry.entryType() == EntryType.WRITE_OFF);
        assertThat(queries.balanceDrift(TENANT, accountId()))
                .as("2 000 the platform could not recover is the tenant's loss, not 2 000 of the "
                        + "customer's ledger that never happened")
                .isZero();

        JdbcLoyaltyStore.ClawbackRow recorded =
                store.findClawback(TENANT, order).orElseThrow();
        assertThat(recorded.writtenOffMinor())
                .as("the fact is real and is a liability line with a brand against it, which is "
                        + "what it is recorded as")
                .isEqualTo(2_000L);
        assertThat(recorded.recoveredMinor()).isEqualTo(1_000L);
        assertThat(recorded.brandId()).isEqualTo(BRAND);
    }

    /**
     * The other half of the decision, at the database. An entry that moves no
     * balance is not a movement, and saying so only in the application leaves it
     * reachable from a migration, a psql session, and the next well-meant hotfix
     * — which is the argument V0042 already makes about TOPUP and WITHDRAWAL.
     */
    @Test
    @DisplayName("the ledger no longer admits a write-off, because no balance could move for one")
    void theLedgerRefusesAnEntryThatCouldNotHaveMovedABalance() {
        seedBalance(1_000L);
        UUID accountId = accountId();

        Throwable refusal = catchThrowable(() -> jdbc.sql("""
                INSERT INTO loyalty.entries (id, tenant_id, account_id, entry_type,
                    amount_minor, balance_after_minor, reason_code, actor, idempotency_key,
                    occurred_at)
                VALUES (:id, :tenantId, :accountId, 'WRITE_OFF', -2000, 0, 'X', 'x', :key, now())
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("accountId", accountId)
                .param("key", "write-off")
                .update());

        assertThat(refusal)
                .as("a shortfall is charged to the brand in loyalty.clawbacks, not to a customer "
                        + "ledger it cannot move")
                .isNotNull();
    }

    /**
     * The caller this method's own javadoc describes is "a machine following a
     * refunded order", which is the shape that gets redelivered.
     *
     * <p>The movement used to happen before the entry, and the entry's key was
     * derived from the order alone: {@code appendEntry} answered false on the
     * second delivery and nobody read the answer, so the balance came down again
     * for a movement the ledger already had. Seeded 5 000 and clawed back 1 000
     * twice, the balance went 5 000, 4 000, 3 000 against a ledger that summed to
     * 4 000 — and {@code balance == SUM(lots.remaining_minor)} was 3 000 == 3 000
     * throughout, which is why nothing caught it.
     */
    @Test
    @DisplayName("a redelivered clawback takes the value once")
    void aRedeliveredClawbackTakesTheValueOnce() {
        seedBalance(5_000L);
        UUID order = completedOrder("H-2", 100_000L, 0L);

        long first = transactions.execute(
                status -> adjustments.clawBack(TENANT, BRAND, customerId, 1_000L, order, "loyalty-clawback"));
        assertThat(first)
                .as("the balance covered it, so nothing is charged to the tenant")
                .isZero();
        assertThat(queries.balance(TENANT, accountId()).balanceMinor()).isEqualTo(4_000L);

        long redelivered = transactions.execute(
                status -> adjustments.clawBack(TENANT, BRAND, customerId, 1_000L, order, "loyalty-clawback"));

        assertThat(redelivered)
                .as("the same answer, read back from what was recorded rather than recomputed "
                        + "against a balance the first delivery already moved")
                .isZero();
        assertThat(queries.balance(TENANT, accountId()).balanceMinor())
                .as("one refunded order claws back one accrual")
                .isEqualTo(4_000L);
        assertThat(store.entries(TENANT, accountId(), 100).stream()
                        .filter(entry -> "ORDER_ACCRUAL_CLAWBACK".equals(entry.reasonCode()))
                        .toList())
                .as("one movement, one entry")
                .hasSize(1);
        assertThat(store.findClawback(TENANT, order).orElseThrow().recoveredMinor())
                .as("and one refunded order, one clawback row")
                .isEqualTo(1_000L);
        assertThat(queries.balanceDrift(TENANT, accountId())).isZero();
    }

    /**
     * The redelivery of the other half. The answer cannot be recomputed from the
     * balance, because the first delivery took the balance to zero: a second
     * pass would find nothing recoverable and report the whole 3 000 as written
     * off, having written off 2 000.
     */
    @Test
    @DisplayName("a redelivered shortfall is charged to the tenant once and reported the same way " + "twice")
    void aRedeliveredShortfallIsChargedOnce() {
        seedBalance(1_000L);
        UUID order = completedOrder("H-3", 100_000L, 0L);

        long first = transactions.execute(
                status -> adjustments.clawBack(TENANT, BRAND, customerId, 3_000L, order, "loyalty-clawback"));
        long redelivered = transactions.execute(
                status -> adjustments.clawBack(TENANT, BRAND, customerId, 3_000L, order, "loyalty-clawback"));

        assertThat(first).isEqualTo(2_000L);
        assertThat(redelivered)
                .as("the balance is zero now, so recomputing would answer 3 000 for something "
                        + "already charged at 2 000")
                .isEqualTo(2_000L);
        assertThat(queries.balance(TENANT, accountId()).balanceMinor()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM loyalty.clawbacks WHERE tenant_id = :tenantId "
                                + "AND order_id = :orderId")
                        .param("tenantId", TENANT)
                        .param("orderId", order)
                        .query(Long.class)
                        .single())
                .as("the brand absorbs one refunded order's shortfall once")
                .isEqualTo(1L);
        assertThat(queries.balanceDrift(TENANT, accountId())).isZero();
    }

    /**
     * The invariant asserted after every test in this class also has to run
     * where the interesting redeliveries happen, which is production.
     *
     * <p>Neither defect can be written through the services any more, so the
     * drift is forced directly here — a cached balance moved with no movement
     * behind it, which is precisely what both of them left. The pass reports and
     * repairs nothing on purpose: which of the two sides is wrong is a judgement
     * over the account's history, and a sweep that guessed would turn a
     * reconciliation into a second unexplained movement.
     */
    @Test
    @DisplayName("the reconciliation pass finds a balance that is not the sum of its movements")
    void theReconciliationPassFindsDrift() {
        seedBalance(5_000L);
        UUID accountId = accountId();
        int whenHealthy = transactions.execute(status -> maintenance.reconcileLedger());
        assertThat(whenHealthy).as("a healthy account is not reported").isZero();

        moveTheCachedBalanceBy(accountId, 1_000L);
        int whenDrifting = transactions.execute(status -> maintenance.reconcileLedger());
        assertThat(whenDrifting)
                .as("1 000 of balance with no entry behind it is exactly what a clawback that "
                        + "debited before it recorded used to leave")
                .isEqualTo(1);

        // Put back, because the assertion this class runs after every test is
        // the one being demonstrated here.
        moveTheCachedBalanceBy(accountId, -1_000L);
    }

    // ------------------------------------------------------------ split tender

    @Test
    @DisplayName("a plan whose tenders do not sum to the order total is refused before any " + "provider call")
    void tendersMustSumToTheOrderTotal() {
        UUID order = completedOrder("I-1", 100_000L, 0L);
        seedBalance(5_000L);

        Throwable refusal = catchThrowable(() -> transactions.execute(status -> settlements.plan(new SettlementPlan(
                TENANT,
                BRAND,
                order,
                customerId,
                "UZS",
                100_000L,
                List.of(new PlannedTender(pointsMethod, 5_000L), new PlannedTender(clickMethod, 90_000L)),
                "k-sum",
                "test"))));

        assertThat(refusal).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("a plan with no money tender is refused even when the balance would cover it")
    void someMoneyMustChangeHands() {
        UUID order = completedOrder("J-1", 100_000L, 0L);
        seedBalance(200_000L);

        // Not a policy number. An order with no money tender has no fiscal path
        // at all, and on a cash order it is a courier who collects nothing while
        // handing over food.
        Throwable refusal = catchThrowable(() -> transactions.execute(status -> settlements.plan(new SettlementPlan(
                TENANT,
                BRAND,
                order,
                customerId,
                "UZS",
                100_000L,
                List.of(new PlannedTender(pointsMethod, 100_000L)),
                "k-nomoney",
                "test"))));

        assertThat(refusal).isInstanceOf(ApiException.class);
    }

    @Test
    @DisplayName("the balance tender reserves first, so a failed external tender never leaves a " + "spent balance")
    void theBalanceTenderIsSequencedFirst() {
        UUID order = completedOrder("K-1", 100_000L, 10_000L);
        seedBalance(5_000L);

        transactions.execute(status -> settlements.plan(new SettlementPlan(
                TENANT,
                BRAND,
                order,
                customerId,
                "UZS",
                100_000L,
                // Deliberately listed money-first, to prove the ordering is the
                // service's and not the caller's.
                List.of(new PlannedTender(clickMethod, 95_000L), new PlannedTender(pointsMethod, 5_000L)),
                "k-order",
                "test")));

        List<JdbcSettlementStore.TenderRow> tenders = settlementStore.tendersOf(
                TENANT,
                settlementStore.findSettlement(TENANT, order).orElseThrow().id());

        assertThat(tenders.get(0).settlesFromBalance())
                .as("releasing a points hold is a local write; reversing a captured card payment "
                        + "is a provider refund with an uncertainty window")
                .isTrue();

        // The checkout then fails. Every hold comes back and nothing is left half
        // paid.
        transactions.executeWithoutResult(status -> settlements.fail(TENANT, order, "PROVIDER_DECLINED", "test"));

        assertThat(queries.balance(TENANT, accountId()).balanceMinor()).isEqualTo(5_000L);
        assertThat(queries.balance(TENANT, accountId()).heldMinor()).isZero();
        assertThat(settlementStore.findSettlement(TENANT, order).orElseThrow().status())
                .isEqualTo(SettlementStatus.FAILED);
    }

    @Test
    @DisplayName("the courier is shown the order total less the settled non-cash tenders")
    void theCourierCollectsTheRightCash() {
        UUID order = completedOrder("L-1", 94_000L, 10_000L);
        seedBalance(12_000L);

        transactions.execute(status -> settlements.plan(new SettlementPlan(
                TENANT,
                BRAND,
                order,
                customerId,
                "UZS",
                94_000L,
                List.of(new PlannedTender(pointsMethod, 12_000L), new PlannedTender(cashMethod, 82_000L)),
                "k-cash",
                "test")));

        UUID settlementId =
                settlementStore.findSettlement(TENANT, order).orElseThrow().id();
        UUID pointsTender = settlementStore.tendersOf(TENANT, settlementId).stream()
                .filter(JdbcSettlementStore.TenderRow::settlesFromBalance)
                .findFirst()
                .orElseThrow()
                .id();
        transactions.executeWithoutResult(
                status -> settlements.recordTenderSettled(TENANT, order, pointsTender, "test"));

        assertThat(settlements.cashDueMinor(TENANT, order, "CASH"))
                .as("a courier who sees only the order total collects 94 000, the customer has "
                        + "paid twice, and the tenant refunds")
                .isEqualTo(82_000L);
    }

    @Test
    @DisplayName("accrual excludes the redeemed portion and the delivery fee")
    void accrualIsNetOfTheRedemptionAndTheFee() {
        UUID order = completedOrder("M-1", 94_000L, 10_000L);

        // 94 000 total, 10 000 fee, 12 000 from points: 82 000 of money, 72 000
        // of it after the fee, at 3%.
        transactions.executeWithoutResult(status -> accrual.accrue(completion(order, 82_000L, 10_000L)));

        assertThat(queries.balance(TENANT, accountId()).balanceMinor())
                .as("accruing on the redeemed portion is a balance that never decays, which "
                        + "finance finds as a liability growing without a matching sale")
                .isEqualTo(2_160L);
    }

    @Test
    @DisplayName("a manual adjustment has one account and one amount, and leaves evidence")
    void anAdjustmentIsVisibleAndAttributable() {
        seedBalance(1_000L);

        ApprovalOutcome outcome =
                transactions.execute(status -> adjustments.adjust(new LoyaltyAdjustmentService.AdjustmentCommand(
                        TENANT,
                        BRAND,
                        customerId,
                        250_000L,
                        "UZS",
                        "GOODWILL",
                        "Cold delivery on 24 August",
                        ActorRef.user("support-1", "Support"),
                        "adj-1",
                        "corr-1")));

        assertThat(outcome.mayProceed()).isTrue();
        assertThat(queries.balance(TENANT, accountId()).balanceMinor()).isEqualTo(251_000L);
        assertThat(audit.facts)
                .as("an unbounded manual credit is a cash drawer any console login can open")
                .anyMatch(
                        fact -> fact.actionCode().equals("loyalty.balance.adjust") && fact.approvalRequestId() != null);
    }

    // -------------------------------------------- one movement, one entry

    /**
     * Two reversals of equal size against the same lot are two movements and owe
     * the ledger two entries.
     *
     * <p>The entry's idempotency key used to be
     * {@code REVERSAL:<tender>:<lot>:<amount>}, which says nothing about
     * <em>which</em> reversal this is. Reverse is the only path that legitimately
     * runs more than once against one (tender, lot) pair, so it is the only one
     * whose key could collide — and it did, byte for byte, the moment a refund
     * happened to return the same amount twice. {@code appendEntry} is
     * {@code ON CONFLICT DO NOTHING} and answered false; the caller threw that
     * answer away and credited the balance regardless.
     *
     * <p>What makes it invisible is that the invariant everything else here
     * asserts still holds: {@code balance_minor == SUM(lots.remaining_minor)} is
     * 4 000 == 4 000 throughout, and the returned value sits on a healthy
     * {@code ACTIVE} lot, so {@code unbackedValueMinor} sees nothing either. The
     * invariant that catches it is the ledger's own:
     * {@code balance_minor - SUM(entries.amount_minor) == 0}.
     */
    @Test
    @DisplayName("two refunds of equal size against one lot are two entries, not one credit twice")
    void aSecondReversalOfTheSameSizeIsRecordedRatherThanSilentlyCredited() {
        seedBalance(4_000L);
        UUID accountId = accountId();

        UUID order = completedOrder("R-1", 100_000L, 0L);
        UUID pointsTender = splitTender(order, 100_000L, 4_000L);
        transactions.executeWithoutResult(
                status -> settlements.recordTenderSettled(TENANT, order, pointsTender, "test"));
        settleMoneyTenders(order);

        // 96 000 of money and 2 000 of points.
        long asMoney =
                transactions.execute(status -> settlements.refund(TENANT, order, 98_000L, "ORDER_REFUNDED", "test"));
        assertThat(asMoney).isEqualTo(96_000L);
        assertThat(queries.balanceDrift(TENANT, accountId))
                .as("the first reversal is sound; it is the second that collides with it")
                .isZero();

        // The remaining 2 000, which is the same amount against the same lot on
        // the same tender.
        transactions.execute(status -> settlements.refund(TENANT, order, 2_000L, "ORDER_REFUNDED", "test"));

        assertThat(store.entriesOfTender(TENANT, pointsTender).stream()
                        .filter(entry -> entry.entryType() == EntryType.REVERSAL)
                        .toList())
                .as("two reversals, two entries: the key has to say which run this is")
                .hasSize(2);
        assertThat(queries.balance(TENANT, accountId).balanceMinor()).isEqualTo(4_000L);
        assertThat(queries.balanceDrift(TENANT, accountId))
                .as("a credit that happens without its entry is 2 000 som of balance the ledger " + "cannot explain")
                .isZero();
    }

    /**
     * Points come back to an account that has been closed, and the ledger has to
     * say where they went.
     *
     * <p>{@code restoreLot} carried no status predicate and a {@code CASE} that
     * could only emit EXPIRED, PENDING or ACTIVE, so a {@code FORFEITED} lot — a
     * closure, or an ADR 0029 erasure — came back {@code ACTIVE}; and
     * {@code creditBalance} asked nothing about the account's status, so a
     * {@code CLOSED} account was handed a spendable balance. The path is not
     * exotic: {@code OrderStateService} calls {@code settlements.fail} for every
     * CANCELLED, REJECTED, EXPIRED and PAYMENT_FAILED order, and that releases
     * every points hold on it.
     *
     * <p>The money moved and the customer is gone, so the value cannot simply be
     * given back and cannot simply vanish: it is returned as a {@code RELEASE}
     * and forfeited again in the same transaction, with a {@code FORFEITURE}
     * entry naming the lot. The schema comment said CLOSED is terminal and is
     * reached with a zero balance; nothing enforced it, which is how this
     * survived.
     */
    @Test
    @DisplayName("a hold released after closure is forfeited again rather than resurrected")
    void pointsReturnedToAClosedAccountAreForfeitedRatherThanRestored() {
        seedBalance(20_000L);
        UUID accountId = accountId();

        UUID order = completedOrder("R-2", 100_000L, 0L);
        splitTender(order, 100_000L, 12_000L);

        UUID heldLot = store.entries(TENANT, accountId, 100).stream()
                .filter(entry -> entry.entryType() == EntryType.REDEMPTION)
                .findFirst()
                .orElseThrow()
                .lotId();

        transactions.execute(status -> adjustments.forfeit(
                TENANT, accountId, "ACCOUNT_CLOSED", ActorRef.user("support-1", "Support"), "corr-closed"));
        assertThat(queries.balance(TENANT, accountId).balanceMinor()).isZero();

        // The order ends. This is the ordinary cancellation path.
        transactions.executeWithoutResult(status -> settlements.fail(TENANT, order, "ORDER_CANCELLED", "ordering"));

        assertThat(queries.balance(TENANT, accountId).balanceMinor())
                .as("CLOSED is terminal and is reached with a zero balance")
                .isZero();
        assertThat(store.findLot(TENANT, heldLot).orElseThrow().status())
                .as("a forfeited lot is not relabelled ACTIVE by a refund")
                .isEqualTo(LotStatus.FORFEITED);
        assertThat(queries.entries(TENANT, accountId))
                .as("the value has to go somewhere and the ledger has to say where")
                .anyMatch(entry -> entry.entryType() == EntryType.FORFEITURE
                        && entry.amountMinor() == -12_000L
                        && entry.lotId() != null);
        assertThat(store.unbackedValueMinor(TENANT, accountId)).isZero();
        assertThat(queries.balanceDrift(TENANT, accountId)).isZero();
    }

    /**
     * An invariant should fail the thing that breaks it, not the next caller.
     *
     * <p>The unbacked-value guard was stated as an absolute — "this account
     * carries nothing on a terminal lot" — and checked at the end of every
     * return. So an account that was <em>already</em> inconsistent, from a lot
     * left holding value before the return path learned to close it, turned the
     * next cancellation into an {@code IllegalStateException}. The order still
     * had to end; the customer was owed their points back; and the thing that
     * refused had not broken anything. An assertion that converts a pre-existing
     * inconsistency into a failed cancellation is worse than the inconsistency.
     *
     * <p>Stated as a delta it still catches the case it was written for — a
     * return that creates unbacked value rolls back — and stops catching the
     * caller that merely arrived afterwards.
     */
    @Test
    @DisplayName("a cancellation is not refused by damage that was already there")
    void aPreExistingInconsistencyDoesNotFailTheNextCancellation() {
        seedBalance(20_000L);
        UUID accountId = accountId();

        UUID order = completedOrder("R-3", 100_000L, 0L);
        splitTender(order, 100_000L, 12_000L);

        // A second lot, left holding value on a terminal status. This is the
        // exact shape a return to an already-expired lot used to leave behind,
        // before the return path started closing such a lot itself: the value is
        // in the balance, no redemption can reach it, and only the repair arm of
        // the expiry sweep is ever coming for it.
        seedBalance(5_000L);
        UUID orphaned = store.openLots(TENANT, accountId).stream()
                .filter(lot -> lot.remainingMinor() == 5_000L)
                .findFirst()
                .orElseThrow()
                .id();
        jdbc.sql("UPDATE loyalty.lots SET status = 'EXPIRED' WHERE tenant_id = :tenantId " + "AND id = :id")
                .param("tenantId", TENANT)
                .param("id", orphaned)
                .update();
        assertThat(store.unbackedValueMinor(TENANT, accountId)).isEqualTo(5_000L);

        transactions.executeWithoutResult(status -> settlements.fail(TENANT, order, "ORDER_CANCELLED", "ordering"));

        assertThat(queries.balance(TENANT, accountId).balanceMinor())
                .as("the cancellation completes and the held points come back")
                .isEqualTo(25_000L);
        assertThat(queries.balance(TENANT, accountId).heldMinor()).isZero();
        assertThat(store.unbackedValueMinor(TENANT, accountId))
                .as("and the return added nothing to what was already wrong")
                .isEqualTo(5_000L);
        assertThat(queries.balanceDrift(TENANT, accountId)).isZero();
    }

    // --------------------------------------------------- the hold as a lease

    /**
     * The hold sweep's expiry is a lease and not a fuse. A cash order's balance
     * tender is outstanding until handover, which in this market is routinely
     * longer than the thirty minutes the constant was sized for.
     */
    @Test
    @DisplayName("a live order's hold is renewed by the sweep rather than released")
    void aLiveOrdersHoldSurvivesTheSweep() {
        seedBalance(12_000L);
        UUID accountId = accountId();
        UUID order = completedOrder("S-1", 100_000L, 0L);
        splitTender(order, 100_000L, 12_000L);

        clock.advance(PAST_THE_HOLD_LIFETIME);
        LoyaltyMaintenanceService sweep =
                new LoyaltyMaintenanceService(store, redemption, SETTLEMENT_STILL_COMING, clock);
        int released = transactions.execute(status -> sweep.releaseStaleHolds());
        assertThat(released)
                .as("a renewal is not a release and is not counted as one")
                .isZero();

        assertThat(queries.balance(TENANT, accountId).balanceMinor())
                .as("the points stay held: the courier has not knocked yet")
                .isZero();
        assertThat(queries.balance(TENANT, accountId).heldMinor()).isEqualTo(12_000L);
        assertThat(store.staleReservations(clock.instant(), 500))
                .as("renewed rather than skipped. A skipped row keeps matching the batch's LIMIT "
                        + "and starves the genuinely abandoned holds behind it")
                .isEmpty();
    }

    @Test
    @DisplayName("an abandoned checkout's hold is still released after thirty minutes")
    void anAbandonedCheckoutsHoldIsStillReleased() {
        seedBalance(12_000L);
        UUID accountId = accountId();
        UUID order = completedOrder("S-2", 100_000L, 0L);
        splitTender(order, 100_000L, 12_000L);

        clock.advance(PAST_THE_HOLD_LIFETIME);
        int released = transactions.execute(status -> maintenance.releaseStaleHolds());
        assertThat(released)
                .as("nobody is waiting on this tender, which is what an abandoned cart is")
                .isEqualTo(1);

        assertThat(queries.balance(TENANT, accountId).balanceMinor()).isEqualTo(12_000L);
        assertThat(queries.balance(TENANT, accountId).heldMinor()).isZero();
        assertThat(queries.entries(TENANT, accountId))
                .anyMatch(entry -> entry.entryType() == EntryType.RELEASE && "HOLD_EXPIRED".equals(entry.reasonCode()));
        assertThat(queries.balanceDrift(TENANT, accountId)).isZero();
    }

    /**
     * A settlement that closes {@code SETTLED} while one of its tenders never
     * settled is indistinguishable from a healthy order in every report. It has
     * to refuse instead.
     */
    @Test
    @DisplayName("settling a tender whose hold was already released refuses, and settles nothing")
    void settlingAReleasedHoldRefuses() {
        seedBalance(12_000L);
        UUID accountId = accountId();
        UUID order = completedOrder("S-3", 100_000L, 0L);
        UUID pointsTender = splitTender(order, 100_000L, 12_000L);

        clock.advance(PAST_THE_HOLD_LIFETIME);
        transactions.execute(status -> maintenance.releaseStaleHolds());

        assertThatThrownBy(() -> transactions.execute(
                        status -> settlements.recordTenderSettled(TENANT, order, pointsTender, "test")))
                .as("a guarded transition whose refusal is discarded is how a money bug becomes " + "silent")
                .isInstanceOf(ApiException.class);

        assertThat(settlementStore.findSettlement(TENANT, order).orElseThrow().status())
                .as("the refusal rolls the caller back rather than closing the settlement")
                .isEqualTo(SettlementStatus.PLANNED);
        assertThat(queries.balance(TENANT, accountId).balanceMinor())
                .as("and the points stay where the release put them")
                .isEqualTo(12_000L);
        assertThat(queries.balanceDrift(TENANT, accountId)).isZero();
    }

    @Test
    @DisplayName("an ended order's hold comes back at once, not at the end of the lease")
    void anEndedOrdersHoldComesBackImmediately() {
        seedBalance(12_000L);
        UUID accountId = accountId();
        UUID order = completedOrder("S-4", 100_000L, 0L);
        splitTender(order, 100_000L, 12_000L);
        assertThat(queries.balance(TENANT, accountId).balanceMinor()).isZero();

        // OrderStateService fails the settlement on the way out of every terminal
        // status. Waiting for the sweep would leave the points invisible for up to
        // a lease, and renewable for as long as the port kept saying yes.
        transactions.executeWithoutResult(status -> settlements.fail(TENANT, order, "ORDER_CANCELLED", "ordering"));

        assertThat(queries.balance(TENANT, accountId).balanceMinor())
                .as("returned at once, well inside the hold lifetime")
                .isEqualTo(12_000L);
        assertThat(queries.balance(TENANT, accountId).heldMinor()).isZero();

        // And the sweep afterwards has nothing left to do, whatever the port says.
        clock.advance(PAST_THE_HOLD_LIFETIME);
        LoyaltyMaintenanceService sweep =
                new LoyaltyMaintenanceService(store, redemption, SETTLEMENT_STILL_COMING, clock);
        int released = transactions.execute(status -> sweep.releaseStaleHolds());
        assertThat(released).isZero();
        assertThat(store.staleReservations(clock.instant(), 500)).isEmpty();
        assertThat(queries.balance(TENANT, accountId).balanceMinor())
                .as("and the points are not returned a second time")
                .isEqualTo(12_000L);
        assertThat(queries.balanceDrift(TENANT, accountId)).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private UUID accountId() {
        return store.findAccount(TENANT, BRAND, customerId).orElseThrow().id();
    }

    /**
     * Moves the cached balance and nothing else, which no service can do and
     * both defects did.
     */
    private void moveTheCachedBalanceBy(UUID accountId, long amountMinor) {
        jdbc.sql("UPDATE loyalty.accounts SET balance_minor = balance_minor + :amount "
                        + "WHERE tenant_id = :tenantId AND id = :id")
                .param("amount", amountMinor)
                .param("tenantId", TENANT)
                .param("id", accountId)
                .update();
    }

    private long lotRemaining(UUID lotId) {
        return store.findLot(TENANT, lotId).orElseThrow().remainingMinor();
    }

    private LoyaltyAccrualService.CompletedOrder completion(UUID orderId, long moneyMinor, long feeMinor) {
        return new LoyaltyAccrualService.CompletedOrder(
                TENANT,
                BRAND,
                locationId,
                channelId,
                customerId,
                orderId,
                "UZS",
                moneyMinor,
                feeMinor,
                clock.instant());
    }

    /**
     * Gives the account a spendable balance the way an operator would: an
     * adjustment with a reason, which is also the ADR 0046 rollout path for a
     * legacy opening balance. It grants an immediately spendable lot, so a test
     * about redemption does not first have to be a test about the earn delay.
     */
    private void seedBalance(long amountMinor) {
        transactions.executeWithoutResult(status -> adjustments.adjust(new LoyaltyAdjustmentService.AdjustmentCommand(
                TENANT,
                BRAND,
                customerId,
                amountMinor,
                "UZS",
                LoyaltyAdjustmentService.REASON_LEGACY_OPENING_BALANCE,
                "Seeded for the test",
                ActorRef.user("seed-operator", "Seed"),
                "seed-" + UUID.randomUUID(),
                "corr-seed")));
    }

    /** Plans a points-plus-card settlement and returns the points tender. */
    private UUID splitTender(UUID orderId, long totalMinor, long pointsMinor) {
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
        return settlementStore.tendersOf(TENANT, settlementId).stream()
                .filter(JdbcSettlementStore.TenderRow::settlesFromBalance)
                .findFirst()
                .orElseThrow()
                .id();
    }

    private void settleMoneyTenders(UUID orderId) {
        UUID settlementId =
                settlementStore.findSettlement(TENANT, orderId).orElseThrow().id();
        settlementStore.tendersOf(TENANT, settlementId).stream()
                .filter(tender -> !tender.settlesFromBalance())
                .forEach(tender -> transactions.executeWithoutResult(
                        status -> settlements.recordTenderSettled(TENANT, orderId, tender.id(), "test")));
    }

    /** A points tender with no settlement plan around it, for the refusal tests. */
    private UUID seedBareTender(UUID orderId, long amountMinor) {
        UUID settlementId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payments.order_settlements (id, tenant_id, order_id, currency,
                    total_due_minor, settled_minor, status)
                VALUES (:id, :tenantId, :orderId, 'UZS', 100000, 0, 'PLANNED')
                """)
                .param("id", settlementId)
                .param("tenantId", TENANT)
                .param("orderId", orderId)
                .update();

        UUID tenderId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO payments.tenders (id, tenant_id, settlement_id, sequence,
                    payment_method_id, settles_from_balance, amount_minor, currency, status,
                    idempotency_key)
                VALUES (:id, :tenantId, :settlementId, 1, :methodId, true, :amount, 'UZS',
                        'PLANNED', :key)
                """)
                .param("id", tenderId)
                .param("tenantId", TENANT)
                .param("settlementId", settlementId)
                .param("methodId", pointsMethod)
                .param("amount", amountMinor)
                .param("key", "bare-" + tenderId)
                .update();
        return tenderId;
    }

    private UUID completedOrder(String number, long totalMinor, long feeMinor) {
        return orderFor(customerId, number, totalMinor, feeMinor, BRAND, locationId);
    }

    private UUID orderFor(UUID customer, String number, long totalMinor, long feeMinor, UUID brandId, UUID location) {
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
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("locationId", location)
                .param("publicationId", publicationId)
                .param("total", totalMinor)
                .update();

        jdbc.sql("""
                INSERT INTO ordering.carts (id, tenant_id, brand_id, location_id, channel_id,
                    fulfillment_mode, currency, status, customer_account_id, expires_at)
                VALUES (:id, :tenantId, :brandId, :locationId, :channelId, 'DELIVERY', 'UZS',
                        'ACTIVE', :customer, now() + interval '1 hour')
                """)
                .param("id", cartId)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("locationId", location)
                .param("channelId", channelId)
                .param("customer", customer)
                .update();

        Map<String, Object> order = new HashMap<>();
        order.put("id", orderId);
        order.put("number", number);
        order.put("tenantId", TENANT);
        order.put("brandId", brandId);
        order.put("locationId", location);
        order.put("channelId", channelId);
        order.put("quoteId", quoteId);
        order.put("cartId", cartId);
        order.put("publicationId", publicationId);
        order.put("customer", customer);
        order.put("total", totalMinor);
        order.put("fee", feeMinor);
        // ordering.orders reconciles subtotal + tax - discount + fee to the total.
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
                    :customer, 'DELIVERY', 'AUTO_CONFIRM', NULL, 0, 'NONE', NULL, 'CONFIRMED',
                    'UZS', :subtotal, 0, :fee, :total, :quoteId, 'hash', :publicationId, :cartId,
                    :key, 1, now())
                """).params(order).update();

        return orderId;
    }

    private void seedTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'loyalty-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", TENANT).update();

        insertBrand(BRAND, "MAIN", "main");
        insertBrand(OTHER_BRAND, "SECOND", "second");
        locationId = insertLocation(BRAND, "CENTRE", "centre");
        otherLocationId = insertLocation(OTHER_BRAND, "NORTH", "north");

        channelId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type,
                    display_name, status)
                VALUES (:id, :tenantId, 'WEB', 'WEB', 'Web', 'ACTIVE')
                """).param("id", channelId).param("tenantId", TENANT).update();

        UUID catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        publicationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'WEB', 'PUBLISHED', 'hash', now())
                """)
                .param("id", publicationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();

        customerId = insertCustomer();
        otherCustomerId = insertCustomer();
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

    private UUID insertLocation(UUID brandId, String code, String slug) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, :code, 'Asia/Tashkent',
                        'ACTIVE', 0)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("code", code)
                .param("slug", slug)
                .update();
        return id;
    }

    private UUID insertCustomer() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO customer.customer_accounts (id, tenant_id, status,
                    identity_policy_version, version)
                VALUES (:id, :tenantId, 'ACTIVE', 1, 1)
                """).param("id", id).param("tenantId", TENANT).update();
        return id;
    }

    private void seedPolicies() {
        // ADR 0046's six proposed defaults, as the rows product and finance would
        // confirm them into. There is no code default for either table: a brand
        // with no active policy does not redeem and does not accrue.
        jdbc.sql("""
                INSERT INTO loyalty.accrual_rules (id, tenant_id, brand_id, scope_type,
                    rate_basis_points, max_accrual_minor, earn_delay_hours, lot_lifetime_days,
                    expiry_warning_days, status, version, valid_from)
                VALUES (:id, :tenantId, :brandId, 'BRAND', 300, 30000, 24, 180, 14, 'ACTIVE', 1,
                        :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("validFrom", VALID_FROM)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO loyalty.accrual_rules (id, tenant_id, brand_id, scope_type,
                    rate_basis_points, max_accrual_minor, earn_delay_hours, lot_lifetime_days,
                    expiry_warning_days, status, version, valid_from)
                VALUES (:id, :tenantId, :brandId, 'BRAND', 300, 30000, 24, 180, 14, 'ACTIVE', 1,
                        :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("validFrom", VALID_FROM)
                .param("brandId", OTHER_BRAND)
                .update();

        jdbc.sql("""
                INSERT INTO loyalty.redemption_policies (id, tenant_id, brand_id,
                    max_share_basis_points, min_order_minor, excludes_delivery_fee,
                    allowed_channels, status, version, valid_from)
                VALUES (:id, :tenantId, :brandId, 5000, 50000, true, '{}', 'ACTIVE', 1,
                        :validFrom)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("validFrom", VALID_FROM)
                .param("brandId", BRAND)
                .update();
    }

    private void seedPaymentMethods() {
        Instant now = clock.instant();
        cashMethod = settlementStore.registerMethod(TENANT, "CASH", "Наличные", "OPERATOR", false, now);
        clickMethod = settlementStore.registerMethod(TENANT, "CLICK", "Click", "PARTNER", false, now);
        // The one row ADR 0046 contributes to ADR 0038's registry. The withdrawn
        // second row was CUSTOMER_DEPOSIT.
        pointsMethod = settlementStore.registerMethod(TENANT, "LOYALTY_POINTS", "Баллы", "OPERATOR", true, now);
    }

    /** One operator's adjustment, with a reason they legitimately use all day. */
    private ApprovalOutcome adjustBy(long amountMinor, String key) {
        return transactions.execute(status -> adjustments.adjust(new LoyaltyAdjustmentService.AdjustmentCommand(
                TENANT,
                BRAND,
                customerId,
                amountMinor,
                "UZS",
                "GOODWILL",
                "Cold delivery",
                ActorRef.user("support-1", "Support"),
                key,
                "corr-" + key)));
    }

    // ---------------------------------------------------------------- doubles

    /** Hands the expiry sweep the batch row as it was before a redemption landed. */
    private static final class StaleBatchLoyaltyStore extends JdbcLoyaltyStore {

        private final LotRow stale;

        private StaleBatchLoyaltyStore(JdbcClient jdbc, LotRow stale) {
            super(jdbc);
            this.stale = stale;
        }

        @Override
        public List<LotRow> expiredLots(Instant asOf, int limit) {
            return List.of(stale);
        }
    }

    /** A clock the tests move, because every rule here is about elapsed time. */
    private static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    /**
     * Approves everything, so the adjustment tests exercise the movement rather
     * than ADR 0027's own state machine, which has its own suite.
     */
    private static final class AlwaysApproves implements ApprovalService {

        @Override
        public ApprovalOutcome requireApproval(ApprovalRequestCommand command) {
            return new ApprovalOutcome.Approved(UUID.randomUUID(), "checker-1", () -> {});
        }

        @Override
        public void decide(UUID requestId, Decision decision, ActorRef approver, String reason) {}

        @Override
        public int expireOverdue() {
            return 0;
        }
    }

    private static final class RecordingAudit implements AuditRecorder {

        private final List<AuditFact> facts = new CopyOnWriteArrayList<>();

        @Override
        public void record(AuditFact fact) {
            facts.add(fact);
        }
    }
}
