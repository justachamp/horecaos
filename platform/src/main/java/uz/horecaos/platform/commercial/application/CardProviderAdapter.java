package uz.horecaos.platform.commercial.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One card provider, behind ADR 0007's rules: its types never leave the adapter,
 * and the core never asks "which provider is this" (ADR 0095).
 *
 * <p>The platform card installation names a {@link #providerType()}; the gateway
 * hands the call to the adapter that answers for it, with the {@link CardAccount}
 * it is acting for. Adding Click or Payme once HorecaOS has a merchant account is
 * one class that implements this and nothing else.
 *
 * <p>The idempotency contract on {@link #charge} is the one {@link CardCharger}
 * documents, and it is the adapter's to keep: a repeated key is the same attempt,
 * never a new charge.
 *
 * <p>So is its other half: <strong>a charge outlives its card.</strong> A tenant may
 * replace its card while a charge asked on the old one has no answer yet, and the
 * old reference is revoked at once (ADR 0095: the attempt keeps its own key and
 * card, and is not refused the replacement). {@link #status} and a replay of
 * {@link #charge} under the same key must go on answering for that attempt after
 * {@link #revoke}, because what a card paid is a record of the merchant account and
 * not of the card. An adapter that forgets a charge with its card turns money taken
 * into money never recorded, which nothing in HorecaOS can see from the outside;
 * {@code FakeCardProviderTests} states the clause and a real adapter is run against
 * it in the provider's sandbox before it is activated.
 */
public interface CardProviderAdapter {

    /** The {@code provider_type} an installation declares to be answered by this adapter. */
    String providerType();

    /**
     * False for a test double. A platform installation naming such an adapter is
     * refused at activation, and never used, unless {@code
     * horecaos.commercial.card.allow-fake} says this is a local or test run.
     */
    boolean usableInProduction();

    /** Whether an installation of this type needs a secret reference to be activated. */
    boolean requiresSecret();

    CardEnrolment.BeginOutcome begin(CardAccount account, UUID tenantId);

    CardEnrolment.ConfirmOutcome confirm(
            CardAccount account, UUID tenantId, String sessionReference, String providerToken, String verificationCode);

    CardEnrolment.RevokeOutcome revoke(CardAccount account, String providerToken);

    CardCharger.Outcome charge(
            CardAccount account,
            UUID tenantId,
            @Nullable String providerToken,
            long amountMinor,
            String currency,
            String idempotencyKey);

    CardCharger.StatusOutcome status(CardAccount account, String idempotencyKey);
}
