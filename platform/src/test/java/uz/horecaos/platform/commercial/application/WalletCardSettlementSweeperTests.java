package uz.horecaos.platform.commercial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.commercial.domain.CardTopUp;
import uz.horecaos.platform.commercial.domain.PaymentMethod;
import uz.horecaos.platform.commercial.infrastructure.FakeCardProvider;

/**
 * The settlement pass that does not wait for somebody to trigger it (ADR 0095).
 *
 * <p>The sweeper's whole value is in what it does NOT do: it does not ask a card that just declined, it
 * does not ask one that has declined three times, it does nothing at all when there is no merchant account,
 * and it never charges a tenant that is not collected by card. Each of those has a test that fails when the
 * holdback is removed.
 */
class WalletCardSettlementSweeperTests extends WalletBillingFixture {

    @Test
    void nothingHappensWhileNoMerchantAccountIsConnectedAndNoAttemptRowIsWrittenToSaySo() {
        startOnPlan(START, MONTHLY);
        inTxDo(() -> wallet.setPaymentMethod(PILOT, PaymentMethod.CARD, "vault:typed", MAKER, "asked", "c"));
        issue(PILOT, "2026-10", CLOSE);

        WalletCardSettlementSweeper.Result result = sweeper.runOnce();

        assertThat(result).isEqualTo(new WalletCardSettlementSweeper.Result(0, 0, 0));
        assertThat(attemptRows(PILOT))
                .as("a person-triggered pass records NOT_CONFIGURED; a sweep per tenant per five minutes must not")
                .isZero();
    }

    @Test
    void aCardThatWorksIsChargedWithoutAnybodyTriggeringAPassAndOnlyOnce() {
        startOnPlan(START, MONTHLY);
        activateFake();
        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        chooseCard(PILOT);
        issue(PILOT, "2026-10", CLOSE);

        WalletCardSettlementSweeper.Result first = sweeper.runOnce();

        assertThat(first.tenantsSettled()).isEqualTo(1);
        assertThat(first.chargedMinor()).isEqualTo(MONTHLY);
        assertThat(statementPayment(PILOT, "2026-10").dueMinor()).isZero();
        assertThat(sweeper.runOnce()).isEqualTo(new WalletCardSettlementSweeper.Result(0, 0, 0));
        assertThat(fake.successfulCharges()).hasSize(1);
    }

    @Test
    void aDeclinedCardIsNotAskedAgainBeforeTheHoldbackAndIsAfterIt() {
        startOnPlan(START, MONTHLY);
        activateFake();
        bindCard(PILOT, FakeCardProvider.DECLINING_CARD);
        chooseCard(PILOT);
        issue(PILOT, "2026-10", CLOSE);

        sweeper.runOnce();
        assertThat(attemptRows(PILOT)).isEqualTo(1);

        clock.advance(Duration.ofHours(23));
        sweeper.runOnce();
        assertThat(attemptRows(PILOT))
                .as("a day has not passed since the decline")
                .isEqualTo(1);

        clock.advance(Duration.ofHours(2));
        sweeper.runOnce();
        assertThat(attemptRows(PILOT))
                .as("a day has, so the card is asked once more")
                .isEqualTo(2);
        assertThat(fake.successfulCharges()).isEmpty();
    }

    @Test
    void aCardThatKeepsDecliningIsGivenUpOnUntilItChangesAndAPersonCanStillTry() {
        startOnPlan(START, MONTHLY);
        activateFake();
        bindCard(PILOT, FakeCardProvider.DECLINING_CARD);
        chooseCard(PILOT);
        issue(PILOT, "2026-10", CLOSE);

        for (int pass = 0; pass < 3; pass++) {
            sweeper.runOnce();
            clock.advance(Duration.ofHours(25));
        }
        assertThat(attemptRows(PILOT)).isEqualTo(3);

        sweeper.runOnce();
        clock.advance(Duration.ofHours(25));
        sweeper.runOnce();
        assertThat(attemptRows(PILOT))
                .as("three declines under this card since it last worked: a card reported lost is not charged for ever")
                .isEqualTo(3);

        wallet.settleCardRemainders(PILOT);
        assertThat(attemptRows(PILOT))
                .as("a person's own pass is not held back by either rule")
                .isEqualTo(4);

        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        WalletCardSettlementSweeper.Result afterNewCard = sweeper.runOnce();
        assertThat(afterNewCard.chargedMinor()).isEqualTo(MONTHLY);
        assertThat(statementPayment(PILOT, "2026-10").dueMinor()).isZero();
    }

    @Test
    void aChargeWhoseAnswerWasLostIsResolvedByTheSweeperWithoutASecondCharge() {
        startOnPlan(START, MONTHLY);
        activateFake();
        bindCard(PILOT, FakeCardProvider.UNANSWERING_CARD);
        chooseCard(PILOT);
        issue(PILOT, "2026-10", CLOSE);

        assertThat(sweeper.runOnce().chargedMinor()).isZero();
        assertThat(fake.successfulCharges()).hasSize(1);

        assertThat(sweeper.runOnce().chargedMinor()).isEqualTo(MONTHLY);

        assertThat(fake.successfulCharges()).as("resolved, never repeated").hasSize(1);
        assertThat(statementPayment(PILOT, "2026-10").dueMinor()).isZero();
    }

    @Test
    void aTopUpStillWaitingForAnAnswerIsResolvedOnceTheGraceHasPassed() {
        activateFake();
        bindCard(PILOT, FakeCardProvider.UNANSWERING_CARD);
        CardTopUp pending = topUps.requestTopUp(PILOT, 400_000, TENANT_USER, "c");
        assertThat(pending.outcome()).isEqualTo(CardTopUp.PENDING);

        assertThat(sweeper.runOnce().topUpsResolved())
                .as("the request that asked may still be about to hear; the sweeper waits two minutes")
                .isZero();
        clock.advance(Duration.ofMinutes(3));
        assertThat(sweeper.runOnce().topUpsResolved()).isEqualTo(1);

        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(400_000);
        assertThat(fake.successfulCharges()).hasSize(1);
        assertThat(sweeper.runOnce().topUpsResolved()).isZero();
    }

    @Test
    void oneTenantThatCannotBeSettledDoesNotHoldBackTheNext() {
        startOnPlan(PILOT, START, MONTHLY);
        startOnPlan(RIVAL, START, MONTHLY);
        activateFake();
        for (UUID tenant : new UUID[] {PILOT, RIVAL}) {
            bindCard(tenant, FakeCardProvider.APPROVING_CARD);
            chooseCard(tenant);
            issue(tenant, "2026-10", CLOSE);
        }
        WalletService failing = spy(wallet);
        doThrow(new IllegalStateException("the pilot's settlement fails"))
                .when(failing)
                .settleCardRemainders(PILOT);
        WalletCardSettlementSweeper sweepingWithOneBrokenTenant = new WalletCardSettlementSweeper(
                failing, topUps, attemptStore, gateway, clock, 100, Duration.ofHours(24), 3, Duration.ofMinutes(2));

        WalletCardSettlementSweeper.Result result = sweepingWithOneBrokenTenant.runOnce();

        assertThat(result.tenantsSettled()).isEqualTo(1);
        assertThat(statementPayment(RIVAL, "2026-10").dueMinor()).isZero();
        assertThat(statementPayment(PILOT, "2026-10").dueMinor()).isEqualTo(MONTHLY);
    }

    @Test
    void aTenantNotCollectedByCardIsNeverCharged() {
        startOnPlan(PILOT, START, MONTHLY);
        startOnPlan(RIVAL, START, MONTHLY);
        activateFake();
        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        bindCard(RIVAL, FakeCardProvider.APPROVING_CARD);
        cardOnFile.chooseMethod(RIVAL, PaymentMethod.WALLET, TENANT_USER, "c");
        issue(PILOT, "2026-10", CLOSE);
        issue(RIVAL, "2026-10", CLOSE);

        WalletCardSettlementSweeper.Result result = sweeper.runOnce();

        assertThat(result).isEqualTo(new WalletCardSettlementSweeper.Result(0, 0, 0));
        assertThat(fake.successfulCharges())
                .as("a card on file is for topping up with; only choosing CARD consents to being charged")
                .isEmpty();
        assertThat(statementPayment(PILOT, "2026-10").dueMinor()).isEqualTo(MONTHLY);
        assertThat(statementPayment(RIVAL, "2026-10").dueMinor()).isEqualTo(MONTHLY);
    }

    private long attemptRows(UUID tenantId) {
        return jdbc.sql("SELECT count(*) FROM commercial.card_charge_attempts WHERE tenant_id = :id")
                .param("id", tenantId)
                .query(Long.class)
                .single();
    }
}
