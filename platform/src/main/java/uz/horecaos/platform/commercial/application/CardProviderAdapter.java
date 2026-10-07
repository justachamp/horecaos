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
