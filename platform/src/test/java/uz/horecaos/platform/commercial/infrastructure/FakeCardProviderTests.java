package uz.horecaos.platform.commercial.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.commercial.application.CardAccount;
import uz.horecaos.platform.commercial.application.CardCharger;
import uz.horecaos.platform.commercial.application.CardEnrolment;

/**
 * The fake provider keeps the contract a real one keeps (ADR 0095), because a fake that says yes proves
 * nothing about the code that talks to it. Each rule here is one the settlement and top-up code relies on,
 * so each is pinned where it is made.
 */
class FakeCardProviderTests {

    private static final UUID TENANT = UUID.fromString("018f6f4e-3300-7000-8000-0000000000e1");
    private static final UUID OTHER = UUID.fromString("018f6f4e-3300-7000-8000-0000000000e2");
    private static final CardAccount ACCOUNT =
            new CardAccount(UUID.randomUUID(), FakeCardProvider.PROVIDER_TYPE, null, null, Map.of());

    private MutableClock clock;
    private FakeCardProvider fake;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-08T09:00:00Z"));
        fake = new FakeCardProvider(clock);
    }

    @Test
    void aTestDoubleSaysSoAndNeedsNoCredential() {
        assertThat(fake.usableInProduction()).isFalse();
        assertThat(fake.requiresSecret()).isFalse();
        assertThat(fake.providerType()).isEqualTo("FAKE_CARD");
    }

    @Test
    void theSameKeyIsTheSameAttemptAndReplaysTheSameAnswer() {
        String token = enrol(TENANT, FakeCardProvider.APPROVING_CARD);

        CardCharger.Outcome first = fake.charge(ACCOUNT, TENANT, token, 500_000, "UZS", "key-1");
        CardCharger.Outcome replay = fake.charge(ACCOUNT, TENANT, token, 500_000, "UZS", "key-1");

        assertThat(first).isInstanceOf(CardCharger.Outcome.Succeeded.class);
        assertThat(replay).isEqualTo(first);
        assertThat(fake.successfulCharges())
                .as("one charge, however often it is asked")
                .hasSize(1);
    }

    @Test
    void aKeyReusedWithDifferentParametersIsRefusedNotReplayed() {
        String token = enrol(TENANT, FakeCardProvider.APPROVING_CARD);
        fake.charge(ACCOUNT, TENANT, token, 500_000, "UZS", "key-1");

        assertThat(fake.charge(ACCOUNT, TENANT, token, 600_000, "UZS", "key-1"))
                .as("a bug that reuses a key for a different amount fails here, loudly")
                .isEqualTo(new CardCharger.Outcome.Failed("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PARAMETERS"));
        assertThat(fake.successfulCharges()).hasSize(1);
    }

    @Test
    void aDeclineIsReplayedForeverUnderItsKeyWhichIsWhyANewAmountNeedsANewKey() {
        String token = enrol(TENANT, FakeCardProvider.DECLINING_CARD);

        assertThat(fake.charge(ACCOUNT, TENANT, token, 500_000, "UZS", "key-1"))
                .isEqualTo(new CardCharger.Outcome.Failed("INSUFFICIENT_FUNDS"));
        assertThat(fake.charge(ACCOUNT, TENANT, token, 500_000, "UZS", "key-1"))
                .isEqualTo(new CardCharger.Outcome.Failed("INSUFFICIENT_FUNDS"));
        assertThat(fake.successfulCharges()).isEmpty();
    }

    @Test
    void aLostAnswerMovedTheMoneyAndStatusSaysSo() {
        String token = enrol(TENANT, FakeCardProvider.UNANSWERING_CARD);

        assertThatThrownBy(() -> fake.charge(ACCOUNT, TENANT, token, 500_000, "UZS", "key-1"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(fake.successfulCharges())
                .as("the money moved before the answer was lost")
                .hasSize(1);
        assertThat(fake.status(ACCOUNT, "key-1")).isInstanceOf(CardCharger.StatusOutcome.Succeeded.class);

        CardCharger.Outcome replay = fake.charge(ACCOUNT, TENANT, token, 500_000, "UZS", "key-1");
        assertThat(replay).isInstanceOf(CardCharger.Outcome.Succeeded.class);
        assertThat(fake.successfulCharges())
                .as("the replay is the same attempt")
                .hasSize(1);
        assertThat(fake.status(ACCOUNT, "never-asked")).isInstanceOf(CardCharger.StatusOutcome.NotSucceeded.class);
    }

    @Test
    void aTokenIsOnlyEverChargeableByTheTenantItWasMintedFor() {
        String token = enrol(TENANT, FakeCardProvider.APPROVING_CARD);

        assertThat(fake.charge(ACCOUNT, OTHER, token, 1_000, "UZS", "key-1"))
                .isEqualTo(new CardCharger.Outcome.Failed("UNKNOWN_TOKEN"));
        assertThat(fake.charge(ACCOUNT, TENANT, null, 1_000, "UZS", "key-2"))
                .isEqualTo(new CardCharger.Outcome.Failed("NO_CARD_ON_FILE"));
        assertThat(fake.successfulCharges()).isEmpty();
    }

    @Test
    void aRevokedTokenCannotBeChargedAndCannotBeRevokedTwice() {
        String token = enrol(TENANT, FakeCardProvider.APPROVING_CARD);

        assertThat(fake.revoke(ACCOUNT, token)).isInstanceOf(CardEnrolment.RevokeOutcome.Revoked.class);
        assertThat(fake.revoke(ACCOUNT, token)).isEqualTo(new CardEnrolment.RevokeOutcome.Failed("UNKNOWN_TOKEN"));
        assertThat(fake.charge(ACCOUNT, TENANT, token, 1_000, "UZS", "key-1"))
                .isEqualTo(new CardCharger.Outcome.Failed("UNKNOWN_TOKEN"));
    }

    @Test
    void aChargeStaysAnswerableAndReplayableAfterItsCardIsRevoked() {
        // A tenant replaces its card while a top-up charged on the old one has no answer yet. The old
        // reference is revoked at once, and the attempt is still asked about, and replayed, under its own key
        // (ADR 0095). A provider that forgot the charge with the card would turn money taken into money
        // never recorded; this is the clause every real adapter is run against before it is activated.
        String token = enrol(TENANT, FakeCardProvider.UNANSWERING_CARD);
        assertThatThrownBy(() -> fake.charge(ACCOUNT, TENANT, token, 400_000, "UZS", "key-1"))
                .isInstanceOf(IllegalStateException.class);

        assertThat(fake.revoke(ACCOUNT, token)).isInstanceOf(CardEnrolment.RevokeOutcome.Revoked.class);

        assertThat(fake.status(ACCOUNT, "key-1"))
                .as("what the card paid is a record of the merchant account, not of the card")
                .isInstanceOf(CardCharger.StatusOutcome.Succeeded.class);
        assertThat(fake.charge(ACCOUNT, TENANT, token, 400_000, "UZS", "key-1"))
                .as("the same attempt, not a new charge against a card that is gone")
                .isInstanceOf(CardCharger.Outcome.Succeeded.class);
        assertThat(fake.successfulCharges()).hasSize(1);
    }

    @Test
    void anAccountKnowsOnlyTheCardsAndKeysItMade() {
        CardAccount other = new CardAccount(UUID.randomUUID(), FakeCardProvider.PROVIDER_TYPE, null, null, Map.of());
        String token = enrol(TENANT, FakeCardProvider.APPROVING_CARD);
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
    void anEnrolledCardNeverShowsItsReferenceInItsOwnDescription() {
        CardEnrolment.BeginOutcome.Begun begun = begin(TENANT);
        CardEnrolment.ConfirmOutcome.Enrolled enrolled = (CardEnrolment.ConfirmOutcome.Enrolled) fake.confirm(
                ACCOUNT,
                TENANT,
                begun.sessionReference(),
                FakeCardProvider.APPROVING_CARD,
                FakeCardProvider.VERIFICATION_CODE);

        assertThat(enrolled.toString()).contains("4242").doesNotContain(enrolled.cardTokenReference());
        assertThat(fake.successfulCharges()).isEmpty();
    }

    @Test
    void aSessionCanBeUsedOnceAndExpiresWithTheClock() {
        CardEnrolment.BeginOutcome.Begun begun = begin(TENANT);
        fake.confirm(
                ACCOUNT,
                TENANT,
                begun.sessionReference(),
                FakeCardProvider.APPROVING_CARD,
                FakeCardProvider.VERIFICATION_CODE);

        assertThat(fake.confirm(
                        ACCOUNT,
                        TENANT,
                        begun.sessionReference(),
                        FakeCardProvider.APPROVING_CARD,
                        FakeCardProvider.VERIFICATION_CODE))
                .as("a completed session is gone")
                .isEqualTo(new CardEnrolment.ConfirmOutcome.Refused("SESSION_UNKNOWN"));

        CardEnrolment.BeginOutcome.Begun late = begin(TENANT);
        clock.now = clock.now.plus(Duration.ofMinutes(16));
        assertThat(fake.confirm(
                        ACCOUNT,
                        TENANT,
                        late.sessionReference(),
                        FakeCardProvider.APPROVING_CARD,
                        FakeCardProvider.VERIFICATION_CODE))
                .isEqualTo(new CardEnrolment.ConfirmOutcome.Refused("SESSION_EXPIRED"));
    }

    private CardEnrolment.BeginOutcome.Begun begin(UUID tenant) {
        return (CardEnrolment.BeginOutcome.Begun) fake.begin(ACCOUNT, tenant);
    }

    private String enrol(UUID tenant, String providerToken) {
        CardEnrolment.BeginOutcome.Begun begun = begin(tenant);
        CardEnrolment.ConfirmOutcome outcome = fake.confirm(
                ACCOUNT, tenant, begun.sessionReference(), providerToken, FakeCardProvider.VERIFICATION_CODE);
        return ((CardEnrolment.ConfirmOutcome.Enrolled) outcome).cardTokenReference();
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
