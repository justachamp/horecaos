package uz.horecaos.platform.commercial.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The contract every {@link CardProviderAdapter} keeps (ADR 0095, ADR 0007), as one suite that runs against
 * the in-process test double and against the real adapter through its controlled fake HTTP provider.
 *
 * <p>This is the executable form of what {@code docs/runbooks/connect-card-merchant-account.md} calls the
 * contract, and the suite re-run in the provider's own sandbox before an installation is activated: each rule
 * here is one the settlement and top-up code relies on, so each is pinned where it is made. A fake that says
 * yes proves nothing about the code that talks to it; running the same assertions against the adapter that
 * speaks the provider's wire is what makes the fake's behaviour evidence about the real one.
 *
 * <p>What is deliberately not here is what only one provider can promise. The in-process double refuses a key
 * reused with different parameters and scopes a card to its tenant; Click holds neither, and its adapter keeps
 * the charge contract by asking before it charges. Those live beside the adapter they describe.
 */
public abstract class CardProviderContract {

    protected static final UUID TENANT = UUID.fromString("018f6f4e-3300-7000-8000-0000000000e1");
    protected static final UUID OTHER_TENANT = UUID.fromString("018f6f4e-3300-7000-8000-0000000000e2");

    /** The behaviours a test card can be asked to show. */
    protected enum Card {
        APPROVING,
        DECLINING,
        /** Takes the money on the first charge and loses the answer. */
        LOSING_THE_ANSWER
    }

    protected abstract CardProviderAdapter adapter();

    protected abstract CardAccount account();

    /** The provider's clock, which the session lifetime and a lookup's date are read from. */
    protected abstract Instant now();

    protected abstract void advanceClock(Duration by);

    /** What the cardholder's bank would text. */
    protected abstract String bankCode();

    /**
     * Completes an enrolment the way the provider's own form would have: obtains whatever the form returns for
     * this kind of card (the browser's job, which the adapter never sees) and confirms it.
     */
    protected abstract CardEnrolment.ConfirmOutcome confirm(
            CardEnrolment.BeginOutcome.Begun begun, UUID tenant, Card card, String bankCode);

    /** How many charges took money at the provider, however often it was asked. A double charge shows up here. */
    protected abstract int moneyMoved();

    // ----------------------------------------------------------------- helpers

    protected final CardEnrolment.BeginOutcome.Begun begin(UUID tenant) {
        CardEnrolment.BeginOutcome outcome = adapter().begin(account(), tenant);
        assertThat(outcome).isInstanceOf(CardEnrolment.BeginOutcome.Begun.class);
        return (CardEnrolment.BeginOutcome.Begun) outcome;
    }

    protected final String enrol(UUID tenant, Card card) {
        CardEnrolment.ConfirmOutcome outcome = confirm(begin(tenant), tenant, card, bankCode());
        assertThat(outcome).isInstanceOf(CardEnrolment.ConfirmOutcome.Enrolled.class);
        return ((CardEnrolment.ConfirmOutcome.Enrolled) outcome).cardTokenReference();
    }

    // ------------------------------------------------------------------ the tests

    @Test
    @DisplayName("an enrolment opens a session that lasts a while and is never a secret")
    void anEnrolmentOpensASession() {
        CardEnrolment.BeginOutcome.Begun begun = begin(TENANT);

        assertThat(begun.sessionReference()).isNotBlank();
        assertThat(begun.expiresAt()).isAfter(now());
        assertThat(begun.clientParameters().values())
                .as("what the browser is told is public: a merchant id, an address, never a credential")
                .noneMatch(value -> value.contains("secret"));
    }

    @Test
    @DisplayName("an enrolled card says which card, never which reference")
    void anEnrolledCardNeverShowsItsReferenceInItsOwnDescription() {
        CardEnrolment.ConfirmOutcome outcome = confirm(begin(TENANT), TENANT, Card.APPROVING, bankCode());

        CardEnrolment.ConfirmOutcome.Enrolled enrolled = (CardEnrolment.ConfirmOutcome.Enrolled) outcome;
        assertThat(enrolled.last4()).matches("\\d{4}");
        assertThat(enrolled.toString()).contains(enrolled.last4()).doesNotContain(enrolled.cardTokenReference());
        assertThat(moneyMoved()).as("enrolling a card charges nothing").isZero();
    }

    @Test
    @DisplayName("a session nobody opened, or another tenant's, is refused as unknown")
    void aSessionIsOnlyEverUsableByTheTenantItWasOpenedFor() {
        CardEnrolment.BeginOutcome.Begun begun = begin(TENANT);

        assertThat(confirm(begun, OTHER_TENANT, Card.APPROVING, bankCode()))
                .isEqualTo(new CardEnrolment.ConfirmOutcome.Refused("SESSION_UNKNOWN"));

        CardEnrolment.BeginOutcome.Begun invented =
                new CardEnrolment.BeginOutcome.Begun("not-a-session", null, java.util.Map.of(), begun.expiresAt());
        assertThat(confirm(invented, TENANT, Card.APPROVING, bankCode()))
                .isEqualTo(new CardEnrolment.ConfirmOutcome.Refused("SESSION_UNKNOWN"));
    }

    @Test
    @DisplayName("a wrong bank code is refused and the session survives, so the cardholder can try again")
    void aWrongCodeIsRefusedAndTheSessionSurvives() {
        CardEnrolment.BeginOutcome.Begun begun = begin(TENANT);

        assertThat(confirm(begun, TENANT, Card.APPROVING, "999999"))
                .isInstanceOf(CardEnrolment.ConfirmOutcome.Refused.class);
        assertThat(confirm(begun, TENANT, Card.APPROVING, bankCode()))
                .isInstanceOf(CardEnrolment.ConfirmOutcome.Enrolled.class);
    }

    @Test
    @DisplayName("a session expires with the clock")
    void aSessionExpiresWithTheClock() {
        CardEnrolment.BeginOutcome.Begun late = begin(TENANT);

        advanceClock(Duration.ofMinutes(16));

        assertThat(confirm(late, TENANT, Card.APPROVING, bankCode()))
                .isEqualTo(new CardEnrolment.ConfirmOutcome.Refused("SESSION_EXPIRED"));
    }

    @Test
    @DisplayName("the same key is the same attempt: one charge, however often it is asked, and the same answer")
    void theSameKeyIsOneCharge() {
        String token = enrol(TENANT, Card.APPROVING);

        CardCharger.Outcome first = adapter().charge(account(), TENANT, token, 500_000, "UZS", "key-1");
        CardCharger.Outcome replay = adapter().charge(account(), TENANT, token, 500_000, "UZS", "key-1");

        assertThat(first).isInstanceOf(CardCharger.Outcome.Succeeded.class);
        assertThat(replay).isEqualTo(first);
        assertThat(moneyMoved()).as("one charge, however often it is asked").isEqualTo(1);
    }

    @Test
    @DisplayName("a declined charge moves no money and says it was declined")
    void aDeclineMovesNothing() {
        String token = enrol(TENANT, Card.DECLINING);

        assertThat(adapter().charge(account(), TENANT, token, 500_000, "UZS", "key-1"))
                .isInstanceOf(CardCharger.Outcome.Failed.class);
        assertThat(adapter().status(account(), "key-1")).isInstanceOf(CardCharger.StatusOutcome.NotSucceeded.class);
        assertThat(moneyMoved()).isZero();
    }

    @Test
    @DisplayName(
            "an answer lost after the money moved is not an answer, status says it succeeded, and a replay is the same attempt")
    void aLostAnswerMovedTheMoneyAndStatusSaysSo() {
        String token = enrol(TENANT, Card.LOSING_THE_ANSWER);

        assertThatThrownBy(() -> adapter().charge(account(), TENANT, token, 500_000, "UZS", "key-1"))
                .as(
                        "an adapter that cannot say whether the money moved says nothing: the caller keeps the attempt pending")
                .isInstanceOf(RuntimeException.class);
        assertThat(moneyMoved())
                .as("the money moved before the answer was lost")
                .isEqualTo(1);
        assertThat(adapter().status(account(), "key-1")).isInstanceOf(CardCharger.StatusOutcome.Succeeded.class);

        CardCharger.Outcome replay = adapter().charge(account(), TENANT, token, 500_000, "UZS", "key-1");
        assertThat(replay).isInstanceOf(CardCharger.Outcome.Succeeded.class);
        assertThat(moneyMoved()).as("the replay is the same attempt").isEqualTo(1);
        assertThat(adapter().status(account(), "never-asked"))
                .isInstanceOf(CardCharger.StatusOutcome.NotSucceeded.class);
    }

    @Test
    @DisplayName("no card on file is a refusal, never a charge")
    void noTokenIsNoCharge() {
        assertThat(adapter().charge(account(), TENANT, null, 1_000, "UZS", "key-1"))
                .isEqualTo(new CardCharger.Outcome.Failed("NO_CARD_ON_FILE"));
        assertThat(moneyMoved()).isZero();
    }

    @Test
    @DisplayName("a revoked card cannot be charged and cannot be revoked twice")
    void aRevokedTokenCannotBeChargedAndCannotBeRevokedTwice() {
        String token = enrol(TENANT, Card.APPROVING);

        assertThat(adapter().revoke(account(), token)).isInstanceOf(CardEnrolment.RevokeOutcome.Revoked.class);
        assertThat(adapter().revoke(account(), token)).isInstanceOf(CardEnrolment.RevokeOutcome.Failed.class);
        assertThat(adapter().charge(account(), TENANT, token, 1_000, "UZS", "key-1"))
                .isInstanceOf(CardCharger.Outcome.Failed.class);
        assertThat(moneyMoved()).isZero();
    }

    @Test
    @DisplayName(
            "a charge stays answerable and replayable after its card is revoked: what a card paid is a record of the account")
    void aChargeStaysAnswerableAndReplayableAfterItsCardIsRevoked() {
        // A tenant replaces its card while a top-up charged on the old one has no answer yet. The old reference
        // is revoked at once, and the attempt is still asked about, and replayed, under its own key (ADR 0095).
        // A provider that forgot the charge with the card would turn money taken into money never recorded; this
        // is the clause every real adapter is run against before it is activated.
        String token = enrol(TENANT, Card.LOSING_THE_ANSWER);
        assertThatThrownBy(() -> adapter().charge(account(), TENANT, token, 400_000, "UZS", "key-1"))
                .isInstanceOf(RuntimeException.class);

        assertThat(adapter().revoke(account(), token)).isInstanceOf(CardEnrolment.RevokeOutcome.Revoked.class);

        assertThat(adapter().status(account(), "key-1"))
                .as("what the card paid is a record of the merchant account, not of the card")
                .isInstanceOf(CardCharger.StatusOutcome.Succeeded.class);
        assertThat(adapter().charge(account(), TENANT, token, 400_000, "UZS", "key-1"))
                .as("the same attempt, not a new charge against a card that is gone")
                .isInstanceOf(CardCharger.Outcome.Succeeded.class);
        assertThat(moneyMoved()).isEqualTo(1);
    }
}
