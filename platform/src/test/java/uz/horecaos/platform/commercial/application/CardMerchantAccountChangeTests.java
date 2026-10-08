package uz.horecaos.platform.commercial.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.commercial.domain.CardTopUp;
import uz.horecaos.platform.commercial.domain.PlatformCardInstallation;
import uz.horecaos.platform.commercial.infrastructure.FakeCardProvider;
import uz.horecaos.platform.web.api.ApiException;

/**
 * Replacing the card merchant account while a charge is still waiting for the provider's answer (ADR
 * 0095). The charge was asked under the old account, which may have taken the money; the new account has
 * never heard of the key, so it cannot say, and it must not be allowed to call the attempt declined.
 *
 * <p>Two accounts with their own memory: the fake scopes cards and idempotency keys to the installation
 * that made them, so a charge made through one is unknown to the other, as it is at two real merchant
 * accounts. A fake that answered for both would hide the whole problem.
 */
class CardMerchantAccountChangeTests extends WalletBillingFixture {

    // ------------------------------------------------------------------ a top-up waiting for the answer

    @Test
    void anAccountIsNotSuspendedWhileATopUpIsStillWaitingForItsAnswer() {
        UUID first = activateFake();
        bindCard(PILOT, FakeCardProvider.UNANSWERING_CARD);
        assertThat(topUps.requestTopUp(PILOT, 400_000, TENANT_USER, "c").outcome())
                .isEqualTo(CardTopUp.PENDING);

        assertThatThrownBy(() -> inTx(() -> installations.suspend(first, 1, MAKER, "replacing it", "c")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties())
                                .containsEntry("reason", "UNRESOLVED_CARD_CHARGES")
                                .containsEntry("unresolvedTopUps", 1L)
                                .containsEntry("unresolvedStatementCharges", 0L));
        assertThat(installationStore.find(first).orElseThrow().status()).isEqualTo(PlatformCardInstallation.ACTIVE);

        assertThat(topUps.reconcilePending(Duration.ZERO, 10)).isEqualTo(1);
        inTx(() -> installations.suspend(first, 1, MAKER, "replacing it", "c"));
        assertThat(installationStore.find(first).orElseThrow().status()).isEqualTo(PlatformCardInstallation.SUSPENDED);
    }

    @Test
    void anAttemptTheOldAccountMayHaveTakenIsNeverDeclinedByTheNewOneAndIsResolvedWhenTheOldIsBack() {
        UUID first = activateFake();
        bindCard(PILOT, FakeCardProvider.UNANSWERING_CARD);
        CardTopUp pending = topUps.requestTopUp(PILOT, 400_000, TENANT_USER, "c");
        assertThat(pending.outcome()).isEqualTo(CardTopUp.PENDING);
        assertThat(chargedThrough(first)).as("the old account took the money").hasSize(1);

        // The operator is told what is waiting and replaces the account regardless.
        inTx(() -> installations.suspend(first, 1, true, MAKER, "the old account is unreachable", "c"));
        UUID replacement = createSecond();
        inTx(() -> installations.activate(replacement, 0, MAKER, "the new account", "c"));

        assertThat(topUps.reconcilePending(Duration.ZERO, 10))
                .as("the new account cannot say what the old one did, so nothing is resolved")
                .isZero();

        CardTopUp stranded = topUpStore.find(PILOT, pending.id()).orElseThrow();
        assertThat(stranded.outcome())
                .as("not FAILED: a decline is final, and the card has been charged")
                .isEqualTo(CardTopUp.PENDING);
        assertThat(ledgerSize(PILOT)).isZero();
        assertThat(chargedThrough(replacement)).isEmpty();
        assertThat(meters.get("commercial.wallet.card_top_up")
                        .tag("outcome", "stranded")
                        .counter()
                        .count())
                .as("loud enough for an alert")
                .isEqualTo(1.0);

        // Back to the account that holds the key: nothing was lost, and the money is recorded once.
        inTx(() -> installations.suspend(replacement, 1, MAKER, "back to the first", "c"));
        inTx(() -> installations.activate(first, 2, MAKER, "back to the first", "c"));

        assertThat(topUps.reconcilePending(Duration.ZERO, 10)).isEqualTo(1);
        assertThat(topUpStore.find(PILOT, pending.id()).orElseThrow().outcome()).isEqualTo(CardTopUp.SUCCEEDED);
        assertThat(wallet.balances(PILOT).paidMinor()).isEqualTo(400_000);
        assertThat(chargedThrough(first)).as("never charged a second time").hasSize(1);
    }

    @Test
    void anAcknowledgedSuspensionSaysHowManyChargesWereLeftWaiting() {
        UUID first = activateFake();
        bindCard(PILOT, FakeCardProvider.UNANSWERING_CARD);
        topUps.requestTopUp(PILOT, 400_000, TENANT_USER, "c");

        inTx(() -> installations.suspend(first, 1, true, MAKER, "the old account is unreachable", "c"));

        assertThat(jdbc.sql("""
                                SELECT change_document::text FROM audit.audit_events
                                 WHERE action_code = 'commercial.card_installation.suspended'
                                """).query(String.class).single())
                .contains("unresolvedTopUps")
                .contains("unresolvedStatementCharges");
    }

    @Test
    void acknowledgingIsNotNeededForAnAccountNothingIsWaitingOn() {
        UUID first = activateFake();
        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        assertThat(topUps.requestTopUp(PILOT, 100_000, TENANT_USER, "c").outcome())
                .isEqualTo(CardTopUp.SUCCEEDED);

        inTx(() -> installations.suspend(first, 1, MAKER, "replacing it", "c"));

        assertThat(installationStore.find(first).orElseThrow().status()).isEqualTo(PlatformCardInstallation.SUSPENDED);
    }

    @Test
    void anAttemptStillPendingUnderAHandTypedReferenceBelongsToWhicheverAccountIsActive() {
        UUID first = activateFake();
        UUID attempt = uz.horecaos.platform.configuration.Ids.newId();
        inTxDo(() -> topUpStore.begin(
                attempt, PILOT, 250_000, "UZS", "vault:typed", TENANT_USER.subject(), clock.instant()));

        assertThatThrownBy(() -> inTx(() -> installations.suspend(first, 1, MAKER, "replacing it", "c")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties()).containsEntry("reason", "UNRESOLVED_CARD_CHARGES"));
    }

    // ------------------------------------------------------------ a statement charge waiting for the answer

    @Test
    void anAccountIsNotSuspendedWhileAStatementChargeIsStillWaitingForItsAnswer() {
        UUID first = cardTenantWithAChargeWhoseAnswerWasLost();

        assertThatThrownBy(() -> inTx(() -> installations.suspend(first, 1, MAKER, "replacing it", "c")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        refused -> assertThat(refused.properties())
                                .containsEntry("reason", "UNRESOLVED_CARD_CHARGES")
                                .containsEntry("unresolvedTopUps", 0L)
                                .containsEntry("unresolvedStatementCharges", 1L));

        assertThat(wallet.settleCardRemainders(PILOT)).isEqualTo(MONTHLY);
        inTx(() -> installations.suspend(first, 1, MAKER, "replacing it", "c"));
    }

    @Test
    void aStatementChargeTheOldAccountMayHaveTakenIsNeverDeclinedByTheNewOne() {
        UUID first = cardTenantWithAChargeWhoseAnswerWasLost();
        inTx(() -> installations.suspend(first, 1, true, MAKER, "the old account is unreachable", "c"));
        UUID replacement = createSecond();
        inTx(() -> installations.activate(replacement, 0, MAKER, "the new account", "c"));

        assertThat(wallet.settleCardRemainders(PILOT)).isZero();

        assertThat(attemptOutcomes())
                .as("not FAILED and not NOT_CONFIGURED: the card has been charged for this statement")
                .containsExactly("PENDING");
        assertThat(statementPayment(PILOT, "2026-10").dueMinor()).isEqualTo(MONTHLY);
        assertThat(chargedThrough(replacement)).isEmpty();

        inTx(() -> installations.suspend(replacement, 1, MAKER, "back to the first", "c"));
        inTx(() -> installations.activate(first, 2, MAKER, "back to the first", "c"));

        assertThat(wallet.settleCardRemainders(PILOT)).isEqualTo(MONTHLY);
        assertThat(attemptOutcomes()).containsExactly("SUCCEEDED");
        assertThat(statementPayment(PILOT, "2026-10").dueMinor()).isZero();
        assertThat(chargedThrough(first)).as("never charged a second time").hasSize(1);
    }

    @Test
    void aStatementChargeAskedWithNoAccountAtAllIsNotCalledUnconfiguredEither() {
        UUID first = cardTenantWithAChargeWhoseAnswerWasLost();
        inTx(() -> installations.suspend(first, 1, true, MAKER, "the old account is unreachable", "c"));

        assertThat(wallet.settleCardRemainders(PILOT)).isZero();

        assertThat(attemptOutcomes())
                .as("an attempt asked under an account is not 'not configured' because the account is paused")
                .containsExactly("PENDING");
    }

    // -------------------------------------------------------------------- helpers

    /** A CARD tenant whose October statement was charged, the money taken, and the answer lost. */
    private UUID cardTenantWithAChargeWhoseAnswerWasLost() {
        startOnPlan(START, MONTHLY);
        UUID first = activateFake();
        bindCard(PILOT, FakeCardProvider.UNANSWERING_CARD);
        chooseCard(PILOT);
        issue(PILOT, "2026-10", CLOSE);
        assertThat(wallet.settleCardRemainders(PILOT)).isZero();
        assertThat(chargedThrough(first)).as("the old account took the money").hasSize(1);
        assertThat(attemptOutcomes()).containsExactly("PENDING");
        return first;
    }

    private UUID createSecond() {
        return inTx(() -> installations.create(
                FakeCardProvider.PROVIDER_TYPE, null, "Second account", null, null, Map.of(), MAKER, "x", "c"));
    }

    /** What the provider took through one merchant account. The fake keeps each account's charges apart. */
    private List<FakeCardProvider.Charge> chargedThrough(UUID installationId) {
        return fake.successfulCharges().stream()
                .filter(charge -> charge.installationId().equals(installationId))
                .toList();
    }

    private List<String> attemptOutcomes() {
        return jdbc.sql("SELECT outcome FROM commercial.card_charge_attempts ORDER BY attempted_at")
                .query(String.class)
                .list();
    }
}
