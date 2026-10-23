package uz.horecaos.platform.commercial.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.commercial.api.CardAccount;
import uz.horecaos.platform.commercial.api.CardCharger;
import uz.horecaos.platform.commercial.api.CardEnrolment;
import uz.horecaos.platform.commercial.api.CardProviderAdapter;
import uz.horecaos.platform.commercial.api.CardProviderContract;

/**
 * The fake provider keeps the contract a real one keeps (ADR 0095), because a fake that says yes proves
 * nothing about the code that talks to it. The contract itself is {@link CardProviderContract}, the one suite
 * the real adapter runs through its fake HTTP provider too; what is here is what only this double promises:
 * it refuses a key reused with different parameters, it scopes a card to the tenant it was made for, and an
 * account knows only the keys it made.
 */
class FakeCardProviderTests extends CardProviderContract {

    private static final CardAccount ACCOUNT =
            new CardAccount(UUID.randomUUID(), FakeCardProvider.PROVIDER_TYPE, null, null, Map.of());

    private MutableClock clock;
    private FakeCardProvider fake;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-08T09:00:00Z"));
        fake = new FakeCardProvider(clock);
    }

    // ------------------------------------------------------------ the contract's hooks

    @Override
    protected CardProviderAdapter adapter() {
        return fake;
    }

    @Override
    protected CardAccount account() {
        return ACCOUNT;
    }

    @Override
    protected Instant now() {
        return clock.now;
    }

    @Override
    protected void advanceClock(Duration by) {
        clock.now = clock.now.plus(by);
    }

    @Override
    protected String bankCode() {
        return FakeCardProvider.VERIFICATION_CODE;
    }

    @Override
    protected CardEnrolment.ConfirmOutcome confirm(
            CardEnrolment.BeginOutcome.Begun begun, UUID tenant, Card card, String bankCode) {
        String formToken =
                switch (card) {
                    case APPROVING -> FakeCardProvider.APPROVING_CARD;
                    case DECLINING -> FakeCardProvider.DECLINING_CARD;
                    case LOSING_THE_ANSWER -> FakeCardProvider.UNANSWERING_CARD;
                };
        return fake.confirm(ACCOUNT, tenant, begun.sessionReference(), formToken, bankCode);
    }

    @Override
    protected int moneyMoved() {
        return fake.successfulCharges().size();
    }

    // ------------------------------------------------------------ the shared contract, one test per rule

    @Test
    @DisplayName("every rule of the shared contract has a test in this class")
    void everyRuleOfTheContractHasATestHere() {
        assertEveryRuleHasATestHere();
    }

    @Test
    @DisplayName("an enrolment opens a session that lasts a while and is never a secret")
    void anEnrolmentOpensASession() {
        assertAnEnrolmentOpensASession();
    }

    @Test
    @DisplayName("an enrolled card says which card, never which reference")
    void anEnrolledCardNeverShowsItsReferenceInItsOwnDescription() {
        assertAnEnrolledCardNeverShowsItsReferenceInItsOwnDescription();
    }

    @Test
    @DisplayName("a session nobody opened, or another tenant's, is refused as unknown")
    void aSessionIsOnlyEverUsableByTheTenantItWasOpenedFor() {
        assertASessionIsOnlyEverUsableByTheTenantItWasOpenedFor();
    }

    @Test
    @DisplayName("a wrong bank code is refused and the session survives, so the cardholder can try again")
    void aWrongCodeIsRefusedAndTheSessionSurvives() {
        assertAWrongCodeIsRefusedAndTheSessionSurvives();
    }

    @Test
    @DisplayName("a session expires with the clock")
    void aSessionExpiresWithTheClock() {
        assertASessionExpiresWithTheClock();
    }

    @Test
    @DisplayName("the same key is the same attempt: one charge, however often it is asked, and the same answer")
    void theSameKeyIsOneCharge() {
        assertTheSameKeyIsOneCharge();
    }

    @Test
    @DisplayName("a declined charge moves no money and says it was declined")
    void aDeclineMovesNothing() {
        assertADeclineMovesNothing();
    }

    @Test
    @DisplayName(
            "an answer lost after the money moved is not an answer, status says it succeeded, and a replay is the same attempt")
    void aLostAnswerMovedTheMoneyAndStatusSaysSo() {
        assertALostAnswerMovedTheMoneyAndStatusSaysSo();
    }

    @Test
    @DisplayName("no card on file is a refusal, never a charge")
    void noTokenIsNoCharge() {
        assertNoTokenIsNoCharge();
    }

    @Test
    @DisplayName("a revoked card cannot be charged and cannot be revoked twice")
    void aRevokedTokenCannotBeChargedAndCannotBeRevokedTwice() {
        assertARevokedTokenCannotBeChargedAndCannotBeRevokedTwice();
    }

    @Test
    @DisplayName(
            "a charge stays answerable and replayable after its card is revoked: what a card paid is a record of the account")
    void aChargeStaysAnswerableAndReplayableAfterItsCardIsRevoked() {
        assertAChargeStaysAnswerableAndReplayableAfterItsCardIsRevoked();
    }

    // ------------------------------------------------------------ what only this double promises

    @Test
    void aTestDoubleSaysSoAndNeedsNoCredential() {
        assertThat(fake.usableInProduction()).isFalse();
        assertThat(fake.requiresSecret()).isFalse();
        assertThat(fake.providerType()).isEqualTo("FAKE_CARD");
    }

    @Test
    void aKeyReusedWithDifferentParametersIsRefusedNotReplayed() {
        String token = enrol(TENANT, Card.APPROVING);
        fake.charge(ACCOUNT, TENANT, token, 500_000, "UZS", "key-1");

        assertThat(fake.charge(ACCOUNT, TENANT, token, 600_000, "UZS", "key-1"))
                .as("a bug that reuses a key for a different amount fails here, loudly")
                .isEqualTo(new CardCharger.Outcome.Failed("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PARAMETERS"));
        assertThat(fake.successfulCharges()).hasSize(1);
    }

    @Test
    void aDeclineIsReplayedForeverUnderItsKeyWhichIsWhyANewAmountNeedsANewKey() {
        String token = enrol(TENANT, Card.DECLINING);

        assertThat(fake.charge(ACCOUNT, TENANT, token, 500_000, "UZS", "key-1"))
                .isEqualTo(new CardCharger.Outcome.Failed("INSUFFICIENT_FUNDS"));
        assertThat(fake.charge(ACCOUNT, TENANT, token, 500_000, "UZS", "key-1"))
                .isEqualTo(new CardCharger.Outcome.Failed("INSUFFICIENT_FUNDS"));
        assertThat(fake.successfulCharges()).isEmpty();
    }

    @Test
    void aTokenIsOnlyEverChargeableByTheTenantItWasMintedFor() {
        String token = enrol(TENANT, Card.APPROVING);

        assertThat(fake.charge(ACCOUNT, OTHER_TENANT, token, 1_000, "UZS", "key-1"))
                .isEqualTo(new CardCharger.Outcome.Failed("UNKNOWN_TOKEN"));
        assertThat(fake.successfulCharges()).isEmpty();
    }

    @Test
    void aRevokedTokenIsUnknownToTheCharge() {
        String token = enrol(TENANT, Card.APPROVING);

        fake.revoke(ACCOUNT, token);

        assertThat(fake.revoke(ACCOUNT, token)).isEqualTo(new CardEnrolment.RevokeOutcome.Failed("UNKNOWN_TOKEN"));
        assertThat(fake.charge(ACCOUNT, TENANT, token, 1_000, "UZS", "key-1"))
                .isEqualTo(new CardCharger.Outcome.Failed("UNKNOWN_TOKEN"));
    }

    @Test
    void anAccountKnowsOnlyTheCardsAndKeysItMade() {
        CardAccount other = new CardAccount(UUID.randomUUID(), FakeCardProvider.PROVIDER_TYPE, null, null, Map.of());
        String token = enrol(TENANT, Card.APPROVING);
        fake.charge(ACCOUNT, TENANT, token, 500_000, "UZS", "key-1");

        assertThat(fake.status(other, "key-1"))
                .as("a key charged through one merchant account is unknown to another")
                .isInstanceOf(CardCharger.StatusOutcome.NotSucceeded.class);
        assertThat(fake.charge(other, TENANT, token, 500_000, "UZS", "key-1"))
                .as("and so is the card")
                .isEqualTo(new CardCharger.Outcome.Failed("UNKNOWN_TOKEN"));
        assertThat(fake.successfulCharges()).hasSize(1);
    }

    @Test
    void aCompletedSessionIsGone() {
        CardEnrolment.BeginOutcome.Begun begun = begin(TENANT);
        fake.confirm(ACCOUNT, TENANT, begun.sessionReference(), FakeCardProvider.APPROVING_CARD, bankCode());

        assertThat(fake.confirm(ACCOUNT, TENANT, begun.sessionReference(), FakeCardProvider.APPROVING_CARD, bankCode()))
                .isEqualTo(new CardEnrolment.ConfirmOutcome.Refused("SESSION_UNKNOWN"));
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        @Override
        public java.time.ZoneId getZone() {
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
}
