package uz.horecaos.platform.commercial.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.commercial.domain.Subscription;
import uz.horecaos.platform.commercial.domain.SubscriptionStatus;
import uz.horecaos.platform.commercial.infrastructure.FakeCardProvider;
import uz.horecaos.platform.commercial.infrastructure.persistence.JdbcWalletStore.OpenDue;

/**
 * What a payment does to a tenant in arrears (ADR 0127, ADR 0089).
 *
 * <p>ADR 0089 decided that lateness is a conversation and that nothing moves a subscription by itself, and
 * these tests hold the build to that: a tenant that pays in full is TOLD to be paid — one audit fact, on the
 * activity log — and stays exactly as late as a person says it is. The fact is written when a payment
 * clears the last open statement, not when a pass finds nothing to do, so one clearing is one fact.
 */
class ArrearsPaymentConsequenceTests extends WalletBillingFixture {

    private static final String PAID_IN_FULL = "commercial.arrears.paid_in_full";

    private static final long DEPOSIT = 300_000L;

    @Test
    void payingTheLastOpenStatementSaysSoOnceAndLeavesTheSubscriptionPastDue() {
        startOnPlan(START, MONTHLY);
        issue(PILOT, "2026-10", CLOSE);
        moveTo(PILOT, SubscriptionStatus.PAST_DUE);

        recordTransfer(PILOT, MONTHLY - 200_000, "MT103-A");
        assertThat(auditedActions(PAID_IN_FULL)).as("part-paid is still late").isEmpty();

        recordTransfer(PILOT, 200_000, "MT103-B");

        assertThat(auditedActions(PAID_IN_FULL)).containsExactly(PAID_IN_FULL);
        assertThat(liveStatus(PILOT))
                .as("nothing moves a subscription by itself (ADR 0089); a person restores it")
                .isEqualTo(SubscriptionStatus.PAST_DUE);

        recordTransfer(PILOT, 50_000, "MT103-C");
        assertThat(auditedActions(PAID_IN_FULL))
                .as("a payment with nothing left to clear says nothing")
                .hasSize(1);
        assertThat(jdbc.sql("SELECT change_document::text FROM audit.audit_events WHERE action_code = :code")
                        .param("code", PAID_IN_FULL)
                        .query(String.class)
                        .single())
                .contains("PAST_DUE");
    }

    @Test
    void aSuspendedTenantThatPaysInFullIsToldTooAndStaysSuspended() {
        startOnPlan(START, MONTHLY);
        issue(PILOT, "2026-10", CLOSE);
        moveTo(PILOT, SubscriptionStatus.SUSPENDED);

        recordTransfer(PILOT, MONTHLY, "MT103-S");

        assertThat(auditedActions(PAID_IN_FULL)).hasSize(1);
        assertThat(liveStatus(PILOT)).isEqualTo(SubscriptionStatus.SUSPENDED);
    }

    @Test
    void aTenantInGoodStandingThatPaysSaysNothing() {
        startOnPlan(START, MONTHLY);
        issue(PILOT, "2026-10", CLOSE);

        recordTransfer(PILOT, MONTHLY, "MT103-G");

        assertThat(auditedActions(PAID_IN_FULL)).isEmpty();
    }

    @Test
    void aCardChargeThatClearsTheLastStatementSaysSoToo() {
        startOnPlan(START, MONTHLY);
        activateFake();
        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        chooseCard(PILOT);
        issue(PILOT, "2026-10", CLOSE);
        moveTo(PILOT, SubscriptionStatus.PAST_DUE);

        wallet.settleCardRemainders(PILOT);

        assertThat(auditedActions(PAID_IN_FULL)).hasSize(1);
        assertThat(liveStatus(PILOT)).isEqualTo(SubscriptionStatus.PAST_DUE);
    }

    @Test
    void aTopUpThatCoversWhatIsOwedSaysSoToo() {
        startOnPlan(START, MONTHLY);
        activateFake();
        bindCard(PILOT, FakeCardProvider.APPROVING_CARD);
        issue(PILOT, "2026-10", CLOSE);
        moveTo(PILOT, SubscriptionStatus.PAST_DUE);

        topUps.requestTopUp(PILOT, MONTHLY, TENANT_USER, "c");

        assertThat(auditedActions(PAID_IN_FULL)).hasSize(1);
        assertThat(liveStatus(PILOT)).isEqualTo(SubscriptionStatus.PAST_DUE);
    }

    @Test
    void aPaymentThatLeavesTheActivationDepositDueDoesNotSayPaidInFullUntilTheDepositIsPaidToo() {
        startOnPlan(PILOT, START, MONTHLY, DEPOSIT);
        issue(PILOT, "2026-10", CLOSE);
        moveTo(PILOT, SubscriptionStatus.PAST_DUE);

        recordTransfer(PILOT, MONTHLY, "MT103-D1");

        assertThat(wallet.openDueByTenant(java.util.List.of(PILOT)))
                .as("the statement is paid; the deposit is owed beside it and is not on any statement")
                .isEmpty();
        assertThat(auditedActions(PAID_IN_FULL))
                .as("a tenant that still owes its deposit has not paid in full")
                .isEmpty();

        inTx(() -> wallet.recordDeposit(PILOT, "MT103-DEP", MAKER, "the activation deposit", "c"));

        assertThat(auditedActions(PAID_IN_FULL))
                .as("the deposit was the last thing owed, and paying it is the clearing")
                .hasSize(1);
        assertThat(liveStatus(PILOT)).isEqualTo(SubscriptionStatus.PAST_DUE);
    }

    @Test
    void theNextStatementPaidAtIssueFromTheWalletIsNotAnotherClearing() {
        startOnPlan(START, MONTHLY);
        issue(PILOT, "2026-10", CLOSE);
        moveTo(PILOT, SubscriptionStatus.PAST_DUE);

        recordTransfer(PILOT, 2 * MONTHLY, "MT103-TWO");
        assertThat(auditedActions(PAID_IN_FULL))
                .as("October was what made it late")
                .hasSize(1);

        issue(PILOT, "2026-11", Instant.parse("2026-12-05T09:00:00Z"));

        assertThat(statementPayment(PILOT, "2026-11").dueMinor())
                .as("paid at issue, from the money left over")
                .isZero();
        assertThat(auditedActions(PAID_IN_FULL))
                .as("a statement issued after it went late was never part of what made it late: one clearing, "
                        + "one fact")
                .hasSize(1);
    }

    @Test
    void whatEachTenantStillOwesIsReadForTheWholeBoardInOneQueryAndNeverAcrossTenants() {
        startOnPlan(PILOT, START, MONTHLY);
        startOnPlan(RIVAL, START, MONTHLY);
        issue(PILOT, "2026-10", CLOSE);
        issue(RIVAL, "2026-10", CLOSE);
        recordTransfer(PILOT, 700_000, "MT103-P");
        recordTransfer(RIVAL, MONTHLY, "MT103-R");

        Map<UUID, OpenDue> owed = wallet.openDueByTenant(java.util.List.of(PILOT, RIVAL));

        assertThat(owed).containsOnlyKeys(PILOT);
        assertThat(owed.get(PILOT)).isEqualTo(new OpenDue(1, MONTHLY - 700_000, "UZS"));
        assertThat(wallet.openDueByTenant(java.util.List.of())).isEmpty();
        assertThat(wallet.openDueByTenant(java.util.List.of(RIVAL))).isEmpty();
    }

    private void moveTo(UUID tenantId, SubscriptionStatus status) {
        Subscription live = subscriptions.live(tenantId).orElseThrow();
        inTxDo(() -> subscriptions.transition(
                tenantId,
                status,
                live.version(),
                status == SubscriptionStatus.SUSPENDED ? "unpaid" : null,
                null,
                MAKER,
                "the statement is late",
                "corr"));
    }

    private SubscriptionStatus liveStatus(UUID tenantId) {
        return subscriptions.live(tenantId).orElseThrow().status();
    }
}
