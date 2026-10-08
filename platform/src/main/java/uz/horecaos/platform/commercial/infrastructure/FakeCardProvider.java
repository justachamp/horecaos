package uz.horecaos.platform.commercial.infrastructure;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.commercial.application.CardAccount;
import uz.horecaos.platform.commercial.application.CardCharger;
import uz.horecaos.platform.commercial.application.CardEnrolment;
import uz.horecaos.platform.commercial.application.CardProviderAdapter;

/**
 * An in-process card provider that behaves like the real ones will (ADR 0095): the
 * stand-in for HorecaOS's own Click or Payme merchant account, which does not exist.
 *
 * <p>It exists so the whole recurring-token flow is built, run and tested before the
 * account does, and so a developer can walk it through the console. It is NOT a stub
 * that says yes: it keeps the contract a real provider keeps, and the contract is the
 * part worth testing.
 *
 * <ul>
 *   <li><strong>The card number never arrives.</strong> A tenant "types its card into
 *       the provider's form" by sending one of the provider tokens below, which is what
 *       a real form's script would hand the browser; {@code 000000} is the code the
 *       cardholder's bank would have texted.
 *   <li><strong>An idempotency key is one attempt.</strong> The same key replays the same
 *       answer, including a decline, which is exactly why a charge for a different
 *       amount must carry a new key. The same key with different parameters is refused,
 *       so a bug that reuses a key fails here loudly instead of at a provider.
 *   <li><strong>An account knows only its own.</strong> Cards and idempotency keys are scoped to the
 *       installation that made them, as they are at two real merchant accounts: a key charged through
 *       one account is unknown to the other, so replacing the account and asking the new one what
 *       happened to an old attempt answers "not that I know of", which is the whole reason that
 *       question cannot settle an attempt as declined.
 *   <li><strong>An answer can be lost after the money moved.</strong> {@link
 *       #UNANSWERING_CARD}'s first charge succeeds provider-side and then throws, which is
 *       the double-charge trap {@code status} exists to defuse.
 * </ul>
 *
 * <p>Provider tokens: {@link #APPROVING_CARD} (ending 4242), {@link #DECLINING_CARD}
 * (ending 0002) and {@link #UNANSWERING_CARD} (ending 0003). Any other token is refused.
 *
 * <p>State is in memory, so a restart forgets every card and charge. That is right for a
 * test double and is the reason it can never be activated in production: see {@link
 * #usableInProduction()}.
 */
@Component
public class FakeCardProvider implements CardProviderAdapter {

    public static final String PROVIDER_TYPE = "FAKE_CARD";

    /** The code the cardholder's bank "texts". */
    public static final String VERIFICATION_CODE = "000000";

    public static final String APPROVING_CARD = "tok_fake_approve";
    public static final String DECLINING_CARD = "tok_fake_decline";
    public static final String UNANSWERING_CARD = "tok_fake_timeout";

    private static final long SESSION_MINUTES = 15;

    private final Clock clock;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Card> cards = new ConcurrentHashMap<>();
    private final Map<String, Charge> charges = new ConcurrentHashMap<>();
    private final List<Charge> successes = new ArrayList<>();

    public FakeCardProvider(Clock clock) {
        this.clock = clock;
    }

    @Override
    public String providerType() {
        return PROVIDER_TYPE;
    }

    @Override
    public boolean usableInProduction() {
        return false;
    }

    @Override
    public boolean requiresSecret() {
        return false;
    }

    @Override
    public CardEnrolment.BeginOutcome begin(CardAccount account, UUID tenantId) {
        String reference = "fake-session-" + UUID.randomUUID();
        Instant expiresAt = clock.instant().plusSeconds(SESSION_MINUTES * 60);
        sessions.put(reference, new Session(tenantId, expiresAt));
        return new CardEnrolment.BeginOutcome.Begun(
                reference,
                "https://fake-card.invalid/enrol/" + reference,
                Map.of("provider", PROVIDER_TYPE),
                expiresAt);
    }

    @Override
    public CardEnrolment.ConfirmOutcome confirm(
            CardAccount account,
            UUID tenantId,
            String sessionReference,
            String providerToken,
            String verificationCode) {
        Session session = sessions.get(sessionReference);
        if (session == null || !session.tenantId().equals(tenantId)) {
            return new CardEnrolment.ConfirmOutcome.Refused("SESSION_UNKNOWN");
        }
        if (!session.expiresAt().isAfter(clock.instant())) {
            sessions.remove(sessionReference);
            return new CardEnrolment.ConfirmOutcome.Refused("SESSION_EXPIRED");
        }
        if (!VERIFICATION_CODE.equals(verificationCode)) {
            // The session survives a wrong code, as a real one does, so the cardholder can retry.
            return new CardEnrolment.ConfirmOutcome.Refused("WRONG_CODE");
        }
        Behaviour behaviour =
                switch (providerToken) {
                    case APPROVING_CARD -> Behaviour.APPROVE;
                    case DECLINING_CARD -> Behaviour.DECLINE;
                    case UNANSWERING_CARD -> Behaviour.LOSE_THE_ANSWER;
                    default -> null;
                };
        if (behaviour == null) {
            return new CardEnrolment.ConfirmOutcome.Refused("UNKNOWN_CARD_TOKEN");
        }
        sessions.remove(sessionReference);
        String token = "fake_card_" + UUID.randomUUID();
        cards.put(scoped(account, token), new Card(tenantId, behaviour));
        YearMonth expiry =
                YearMonth.from(clock.instant().atZone(ZoneOffset.UTC)).plusYears(3);
        return new CardEnrolment.ConfirmOutcome.Enrolled(
                token, behaviour.last4, "HUMO", expiry.getMonthValue(), expiry.getYear());
    }

    @Override
    public CardEnrolment.RevokeOutcome revoke(CardAccount account, String providerToken) {
        return cards.remove(scoped(account, providerToken)) == null
                ? new CardEnrolment.RevokeOutcome.Failed("UNKNOWN_TOKEN")
                : new CardEnrolment.RevokeOutcome.Revoked();
    }

    @Override
    public synchronized CardCharger.Outcome charge(
            CardAccount account,
            UUID tenantId,
            @Nullable String providerToken,
            long amountMinor,
            String currency,
            String idempotencyKey) {
        if (providerToken == null) {
            return new CardCharger.Outcome.Failed("NO_CARD_ON_FILE");
        }
        Charge seen = charges.get(scoped(account, idempotencyKey));
        if (seen != null) {
            boolean sameAttempt = seen.tenantId().equals(tenantId)
                    && seen.token().equals(providerToken)
                    && seen.amountMinor() == amountMinor
                    && seen.currency().equals(currency);
            if (!sameAttempt) {
                return new CardCharger.Outcome.Failed("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_PARAMETERS");
            }
            return seen.answer();
        }
        Card card = cards.get(scoped(account, providerToken));
        if (card == null || !card.tenantId().equals(tenantId)) {
            return new CardCharger.Outcome.Failed("UNKNOWN_TOKEN");
        }
        String reference = "FAKE-" + idempotencyKey.replace("-", "").toUpperCase(Locale.ROOT);
        boolean approved = card.behaviour() != Behaviour.DECLINE;
        CardCharger.Outcome answer = approved
                ? new CardCharger.Outcome.Succeeded(reference)
                : new CardCharger.Outcome.Failed("INSUFFICIENT_FUNDS");
        Charge charge = new Charge(
                account.installationId(), tenantId, providerToken, amountMinor, currency, idempotencyKey, answer);
        charges.put(scoped(account, idempotencyKey), charge);
        if (approved) {
            successes.add(charge);
        }
        if (card.behaviour() == Behaviour.LOSE_THE_ANSWER && card.consumeLostAnswer()) {
            throw new IllegalStateException("fake provider: the charge went through and the answer was lost");
        }
        return answer;
    }

    @Override
    public synchronized CardCharger.StatusOutcome status(CardAccount account, String idempotencyKey) {
        Charge charge = charges.get(scoped(account, idempotencyKey));
        if (charge != null && charge.answer() instanceof CardCharger.Outcome.Succeeded succeeded) {
            return new CardCharger.StatusOutcome.Succeeded(succeeded.providerReference());
        }
        return new CardCharger.StatusOutcome.NotSucceeded();
    }

    // ---------------------------------------------------------------- test and local hooks

    /** Every charge that really moved money, in order. A double charge shows up here and nowhere else. */
    public synchronized List<Charge> successfulCharges() {
        return List.copyOf(successes);
    }

    /** Forgets every card, session and charge. */
    public synchronized void reset() {
        sessions.clear();
        cards.clear();
        charges.clear();
        successes.clear();
    }

    /** What the provider recorded for one charge attempt. */
    public record Charge(
            UUID installationId,
            UUID tenantId,
            String token,
            long amountMinor,
            String currency,
            String idempotencyKey,
            CardCharger.Outcome answer) {

        /** Says which attempt, never the token. */
        @Override
        public String toString() {
            return "Charge[key=%s amount=%d %s]".formatted(idempotencyKey, amountMinor, currency);
        }
    }

    /** A card token or an idempotency key means something only to the account that made it. */
    private static String scoped(CardAccount account, String value) {
        return account.installationId() + "/" + value;
    }

    private record Session(UUID tenantId, Instant expiresAt) {}

    private static final class Card {
        private final UUID tenantId;
        private final Behaviour behaviour;
        private boolean answerStillToLose = true;

        Card(UUID tenantId, Behaviour behaviour) {
            this.tenantId = tenantId;
            this.behaviour = behaviour;
        }

        UUID tenantId() {
            return tenantId;
        }

        Behaviour behaviour() {
            return behaviour;
        }

        /** The answer is lost once; every later question about the same attempt is answered. */
        synchronized boolean consumeLostAnswer() {
            boolean lose = answerStillToLose;
            answerStillToLose = false;
            return lose;
        }
    }

    private enum Behaviour {
        APPROVE("4242"),
        DECLINE("0002"),
        LOSE_THE_ANSWER("0003");

        private final String last4;

        Behaviour(String last4) {
            this.last4 = last4;
        }
    }
}
