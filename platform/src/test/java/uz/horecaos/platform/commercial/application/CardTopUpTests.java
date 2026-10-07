package uz.horecaos.platform.commercial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import uz.horecaos.platform.commercial.domain.CardTopUp;
import uz.horecaos.platform.commercial.domain.PaymentMethod;
import uz.horecaos.platform.commercial.domain.WalletEntry;
import uz.horecaos.platform.commercial.infrastructure.FakeCardProvider;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.web.api.ApiException;

/**
 * A tenant tops its wallet up from the card it keeps on file (ADR 0095, decision 2: money in by "a card
 * charge that succeeded"), against PostgreSQL and the fake provider.
 *
 * <p>What is under test is that money which left a card is on the ledger exactly once and money that did
 * not is not: whatever the provider does between being asked and answering. The fake keeps its own record
 * of what it charged, so a double charge shows there even where the ledger and the attempt row agree with
 * each other.
 */
class CardTopUpTests extends WalletBillingFixture {

    @Test
    void aTopUpIsChargedToTheCardCreditedToTheLedgerAndPaysWhatIsOwed() {
        startOnPlan(START, MONTHLY);
        activateFake();
        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        issue(PILOT, "2026-10", CLOSE);
        assertThat(statementPayment(PILOT, "2026-10").dueMinor()).isEqualTo(MONTHLY);

        CardTopUp topUp = topUps.requestTopUp(PILOT, MONTHLY + 500_000, TENANT_USER, "c");

        assertThat(topUp.outcome()).isEqualTo(CardTopUp.SUCCEEDED);
        assertThat(topUp.walletEntryId()).isNotNull();
        assertThat(fake.successfulCharges()).singleElement().satisfies(charge -> {
            assertThat(charge.amountMinor()).isEqualTo(MONTHLY + 500_000);
            assertThat(charge.idempotencyKey())
                    .as("the attempt row's id is the key the provider was handed")
                    .isEqualTo(topUp.id().toString());
        });
        assertThat(wallet.ledger(PILOT, null, 10))
                .extracting(
                        WalletEntry::entryType,
                        WalletEntry::moneyKind,
                        WalletEntry::amountMinor,
                        WalletEntry::recordedBy)
                .containsExactlyInAnyOrder(
                        tuple(WalletEntry.TOP_UP, WalletEntry.PAID, MONTHLY + 500_000, TENANT_USER.subject()),
                        tuple(WalletEntry.STATEMENT_PAYMENT, WalletEntry.PAID, -MONTHLY, "system:wallet-settlement"));
        assertThat(statementPayment(PILOT, "2026-10").dueMinor()).isZero();
        assertThat(wallet.balances(PILOT).paidMinor())
                .as("what is left after the statement is the tenant's, and refundable")
                .isEqualTo(500_000);
        assertThat(ledgerSum(PILOT, WalletEntry.PAID)).isEqualTo(500_000);
        assertThat(auditedActions("commercial.wallet.card_topped_up")).hasSize(1);
        assertThat(meters.get("commercial.wallet.card_top_up")
                        .tag("outcome", "succeeded")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }

    @Test
    void aDeclinedTopUpPutsNothingOnTheLedgerAndTheTenantMayTryAgain() {
        activateFake();
        bindCard(PILOT, FakeCardProvider.DECLINING_CARD);

        CardTopUp declined = topUps.requestTopUp(PILOT, 300_000, TENANT_USER, "c");

        assertThat(declined.outcome()).isEqualTo(CardTopUp.FAILED);
        assertThat(declined.providerDetail()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(declined.walletEntryId()).isNull();
        assertThat(ledgerSize(PILOT)).isZero();
        assertThat(fake.successfulCharges()).isEmpty();
        assertThat(auditedActions("commercial.wallet.card_top_up"))
                .containsExactly("commercial.wallet.card_top_up_declined");

        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        CardTopUp again = topUps.requestTopUp(PILOT, 300_000, TENANT_USER, "c");
        assertThat(again.outcome()).isEqualTo(CardTopUp.SUCCEEDED);
        assertThat(again.id())
                .as("a new attempt, a new key: the decline of the first is replayed for ever under its own")
                .isNotEqualTo(declined.id());
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(300_000);
    }

    @Test
    void aLostAnswerLeavesTheTopUpPendingAndTheMoneyOffTheLedgerUntilItIsResolvedOnce() {
        activateFake();
        bindCard(PILOT, FakeCardProvider.UNANSWERING_CARD);

        CardTopUp pending = topUps.requestTopUp(PILOT, 400_000, TENANT_USER, "c");

        assertThat(pending.outcome()).isEqualTo(CardTopUp.PENDING);
        assertThat(fake.successfulCharges()).as("the provider took the money").hasSize(1);
        assertThat(ledgerSize(PILOT)).as("and nothing is on the ledger yet").isZero();
        assertThatThrownBy(() -> topUps.requestTopUp(PILOT, 400_000, TENANT_USER, "c"))
                .as("an impatient second click is not a second charge")
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "TOP_UP_IN_FLIGHT"));
        assertThat(fake.successfulCharges()).hasSize(1);

        assertThat(topUps.reconcilePending(Duration.ZERO, 10)).isEqualTo(1);

        assertThat(fake.successfulCharges())
                .as("asked what the provider believes before charging, so it was recorded and not repeated")
                .hasSize(1);
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(400_000);
        assertThat(topUpStore.find(PILOT, pending.id()).orElseThrow().outcome()).isEqualTo(CardTopUp.SUCCEEDED);

        assertThat(topUps.reconcilePending(Duration.ZERO, 10))
                .as("nothing left to resolve")
                .isZero();
        assertThat(ledgerSize(PILOT)).isEqualTo(1);
        assertThat(fake.successfulCharges()).hasSize(1);
    }

    @Test
    void anAttemptTheProviderNeverReceivedIsChargedOnceUnderTheSameKey() {
        activateFake();
        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        String reference =
                java.util.Objects.requireNonNull(wallet.billing(PILOT).cardTokenReference());
        UUID attempt = Ids.newId();
        inTxDo(() ->
                topUpStore.begin(attempt, PILOT, 250_000, "UZS", reference, TENANT_USER.subject(), clock.instant()));

        assertThat(topUps.reconcilePending(Duration.ZERO, 10)).isEqualTo(1);

        assertThat(fake.successfulCharges())
                .singleElement()
                .satisfies(charge -> assertThat(charge.idempotencyKey()).isEqualTo(attempt.toString()));
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(250_000);
    }

    @Test
    void anAttemptKeepsItsOwnCardEvenWhenTheTenantReplacesItWhileTheAnswerIsOutstanding() {
        activateFake();
        bindCard(PILOT, FakeCardProvider.UNANSWERING_CARD);
        CardTopUp pending = topUps.requestTopUp(PILOT, 400_000, TENANT_USER, "c");
        assertThat(pending.outcome()).isEqualTo(CardTopUp.PENDING);

        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        assertThat(topUps.reconcilePending(Duration.ZERO, 10)).isEqualTo(1);

        assertThat(fake.successfulCharges())
                .as("the new card was never asked about the old attempt")
                .hasSize(1);
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(400_000);
        assertThat(wallet.cardOnFile(PILOT).orElseThrow().last4()).isEqualTo("4242");
    }

    @Test
    void theCardIsAskedWithNothingHeldAndTheAttemptAlreadyCommittedToAnotherSession() {
        activateFake();
        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        AtomicBoolean inTransaction = new AtomicBoolean(true);
        AtomicBoolean visibleElsewhere = new AtomicBoolean(false);
        CardCharger spying = new CardCharger() {
            @Override
            public Outcome charge(
                    UUID tenantId, @Nullable String token, long amountMinor, String currency, String idempotencyKey) {
                inTransaction.set(TransactionSynchronizationManager.isActualTransactionActive());
                visibleElsewhere.set(pendingTopUpIsVisibleToAnotherSession(idempotencyKey));
                return gateway.charge(tenantId, token, amountMinor, currency, idempotencyKey);
            }

            @Override
            public StatusOutcome status(String idempotencyKey) {
                return gateway.status(idempotencyKey);
            }
        };

        serviceWith(spying).requestTopUp(PILOT, 100_000, TENANT_USER, "c");

        assertThat(inTransaction)
                .as("no transaction, no connection and no billing lock across the provider call")
                .isFalse();
        assertThat(visibleElsewhere)
                .as("the attempt, and so the key, was durable before anything could be charged under it")
                .isTrue();
        assertThatThrownBy(() -> transactions.execute(status -> topUps.requestTopUp(PILOT, 1_000, TENANT_USER, "c")))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void twoRequestsRacingForTheSameTenantMakeOneCharge() throws Exception {
        activateFake();
        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        CountDownLatch inProvider = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CardCharger blocking = new CardCharger() {
            @Override
            public Outcome charge(
                    UUID tenantId, @Nullable String token, long amountMinor, String currency, String idempotencyKey) {
                inProvider.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return gateway.charge(tenantId, token, amountMinor, currency, idempotencyKey);
            }

            @Override
            public StatusOutcome status(String idempotencyKey) {
                return gateway.status(idempotencyKey);
            }
        };
        CardTopUpService racing = serviceWith(blocking);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<CardTopUp> first = pool.submit(() -> racing.requestTopUp(PILOT, 100_000, TENANT_USER, "c"));
            assertThat(inProvider.await(30, TimeUnit.SECONDS)).isTrue();

            AtomicReference<Throwable> second = new AtomicReference<>();
            try {
                racing.requestTopUp(PILOT, 100_000, ActorOther.USER, "c");
            } catch (RuntimeException refused) {
                second.set(refused);
            }
            release.countDown();

            assertThat(second.get())
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            refused -> assertThat(refused.properties()).containsEntry("reason", "TOP_UP_IN_FLIGHT"));
            assertThat(first.get(30, TimeUnit.SECONDS).outcome()).isEqualTo(CardTopUp.SUCCEEDED);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(fake.successfulCharges()).hasSize(1);
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(100_000);
    }

    @Test
    void aSuccessTheRequestAndAReconciliationPassAreBothToldIsCreditedOnce() {
        activateFake();
        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        AtomicReference<CardTopUpService> service = new AtomicReference<>();
        CardCharger racing = new CardCharger() {
            @Override
            public Outcome charge(
                    UUID tenantId, @Nullable String token, long amountMinor, String currency, String idempotencyKey) {
                Outcome answer = gateway.charge(tenantId, token, amountMinor, currency, idempotencyKey);
                // The money has moved and the request has not heard yet; a pass that finds the attempt still
                // PENDING resolves it first, and then the request is told the very same answer.
                java.util.Objects.requireNonNull(service.get()).reconcilePending(Duration.ZERO, 10);
                return answer;
            }

            @Override
            public StatusOutcome status(String idempotencyKey) {
                return gateway.status(idempotencyKey);
            }
        };
        service.set(serviceWith(racing));

        CardTopUp told = java.util.Objects.requireNonNull(service.get()).requestTopUp(PILOT, 100_000, TENANT_USER, "c");

        assertThat(told.outcome()).isEqualTo(CardTopUp.SUCCEEDED);
        assertThat(fake.successfulCharges()).hasSize(1);
        assertThat(ledgerSize(PILOT))
                .as("the second report of the same success finds the attempt resolved and credits nothing")
                .isEqualTo(1);
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(100_000);
        assertThat(auditedActions("commercial.wallet.card_topped_up")).hasSize(1);
    }

    @Test
    void aTopUpNeedsACardOnFileAndAPositiveAmount() {
        activateFake();

        assertThatThrownBy(() -> topUps.requestTopUp(PILOT, 100_000, TENANT_USER, "c"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "NO_CARD_ON_FILE"));
        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        assertThatThrownBy(() -> topUps.requestTopUp(PILOT, 0, TENANT_USER, "c"))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> topUps.requestTopUp(PILOT, -5, TENANT_USER, "c"))
                .isInstanceOf(ApiException.class);
        assertThat(topUpStore.recent(PILOT, 10)).isEmpty();
    }

    @Test
    void withNoMerchantAccountATopUpIsRefusedHonestlyAndCreditsNothing() {
        inTxDo(() -> wallet.setPaymentMethod(PILOT, PaymentMethod.CARD, "vault:typed", MAKER, "asked", "c"));

        CardTopUp topUp = topUps.requestTopUp(PILOT, 100_000, TENANT_USER, "c");

        assertThat(topUp.outcome()).isEqualTo(CardTopUp.NOT_CONFIGURED);
        assertThat(ledgerSize(PILOT)).isZero();
    }

    @Test
    void theSameProviderReferenceIsNeverCreditedTwice() {
        UUID topUpId = Ids.newId();
        inTx(() -> wallet.creditCardTopUp(PILOT, 100_000, "UZS", "FAKE-ONCE", "u", TENANT_USER, topUpId));

        assertThatThrownBy(() -> inTx(
                        () -> wallet.creditCardTopUp(PILOT, 100_000, "UZS", "FAKE-ONCE", "u", TENANT_USER, topUpId)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> inTx(
                        () -> wallet.creditCardTopUp(PILOT, 100_000, "UZS", " fake-once ", "u", TENANT_USER, topUpId)))
                .as("the same reference typed another way is the same charge")
                .isInstanceOf(ApiException.class);

        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(100_000);
        assertThat(ledgerSize(PILOT)).isEqualTo(1);
    }

    @Test
    void aChargeInAnotherCurrencyThanTheWalletHoldsIsKeptPendingForAPersonNotCreditedAtFaceValue() {
        activateFake();
        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        String reference =
                java.util.Objects.requireNonNull(wallet.billing(PILOT).cardTokenReference());
        UUID attempt = Ids.newId();
        inTxDo(() ->
                topUpStore.begin(attempt, PILOT, 250_000, "USD", reference, TENANT_USER.subject(), clock.instant()));

        assertThat(topUps.reconcilePending(Duration.ZERO, 10))
                .as("it could not be resolved")
                .isZero();

        assertThat(topUpStore.find(PILOT, attempt).orElseThrow().outcome()).isEqualTo(CardTopUp.PENDING);
        assertThat(ledgerSize(PILOT))
                .as("four cents' worth of som must not be recorded for a dollar receipt")
                .isZero();
    }

    @Test
    void aCardTenantsTopUpIsFollowedByCollectingWhatIsStillOwed() {
        startOnPlan(START, MONTHLY);
        activateFake();
        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        chooseCard(PILOT);
        issue(PILOT, "2026-10", CLOSE);

        CardTopUp topUp = topUps.requestTopUp(PILOT, 300_000, TENANT_USER, "c");

        assertThat(topUp.outcome()).isEqualTo(CardTopUp.SUCCEEDED);
        assertThat(fake.successfulCharges())
                .extracting(FakeCardProvider.Charge::amountMinor)
                .as("the top-up, then the rest of the statement the wallet could not cover")
                .containsExactly(300_000L, MONTHLY - 300_000);
        assertThat(statementPayment(PILOT, "2026-10").dueMinor()).isZero();
    }

    @Test
    void aPendingTopUpBlocksRemovingTheCardUntilItHasAnAnswer() {
        activateFake();
        bindCard(PILOT, FakeCardProvider.UNANSWERING_CARD);
        topUps.requestTopUp(PILOT, 100_000, TENANT_USER, "c");

        assertThatThrownBy(() -> cardOnFile.removeCard(PILOT, TENANT_USER, "c"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "CHARGE_IN_FLIGHT"));
        assertThat(wallet.cardOnFile(PILOT)).isPresent();

        topUps.reconcilePending(Duration.ZERO, 10);
        cardOnFile.removeCard(PILOT, TENANT_USER, "c");
        assertThat(wallet.cardOnFile(PILOT)).isEmpty();
    }

    @Test
    void oneTenantsTopUpsAreNeitherSeenNorBlockedByAnother() {
        activateFake();
        bindCard(PILOT, FakeCardProvider.UNANSWERING_CARD);
        bindCard(RIVAL, FakeCardProvider.APPROVING_CARD);
        topUps.requestTopUp(PILOT, 100_000, TENANT_USER, "c");

        CardTopUp rival = topUps.requestTopUp(RIVAL, 70_000, TENANT_USER, "c");

        assertThat(rival.outcome()).isEqualTo(CardTopUp.SUCCEEDED);
        assertThat(topUps.recent(RIVAL, 10)).extracting(CardTopUp::tenantId).containsOnly(RIVAL);
        assertThat(topUps.recent(PILOT, 10)).extracting(CardTopUp::tenantId).containsOnly(PILOT);
        assertThat(wallet.balances(RIVAL).paidMinor()).isEqualTo(70_000);
        assertThat(wallet.balances(PILOT).paidMinor()).isZero();
    }

    // -------------------------------------------------------------------- helpers

    private CardTopUpService serviceWith(CardCharger charger) {
        return new CardTopUpService(topUpStore, walletStore, wallet, charger, audit, meters, transactions, clock);
    }

    /** Whether this attempt is committed, asked on a connection of its own (JdbcClient would reuse the open transaction's). */
    private boolean pendingTopUpIsVisibleToAnotherSession(String attemptId) {
        try (java.sql.Connection separate = db.dataSource().getConnection();
                java.sql.PreparedStatement query = separate.prepareStatement("""
                        SELECT count(*) FROM commercial.card_top_ups
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

    /** A second person at the same tenant, so a double click is not the only way to race. */
    private static final class ActorOther {
        static final uz.horecaos.platform.audit.api.ActorRef USER =
                uz.horecaos.platform.audit.api.ActorRef.user("tenant-owner-2", null);
    }

    @SuppressWarnings("unused")
    private static List<String> unused() {
        return List.of();
    }

    @SuppressWarnings("unused")
    private static Map<String, Object> unusedMap() {
        return Map.of();
    }
}
