package uz.horecaos.platform.commercial.application;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Putting a tenant's card on file with HorecaOS's card provider, so it can be
 * charged later without the tenant present (ADR 0095, the recurring token flow).
 *
 * <p><strong>The card number never reaches HorecaOS.</strong> The tenant types
 * it into the provider's own form, which hands the browser a one-time provider
 * token; HorecaOS sees that token and the code the cardholder's bank texted, and
 * nothing else (ADR 0028). That is why this port has no card number to take, and
 * why a confirmation carries a provider token rather than a card.
 *
 * <p>Declared here and implemented by whichever adapter the active platform card
 * installation names, exactly like {@link CardCharger}. Every method may be
 * called with no installation active, and then answers {@code NotConfigured}.
 */
public interface CardEnrolment {

    /**
     * Opens one enrolment with the provider for this tenant.
     *
     * @return what the browser needs to show the provider's form
     */
    BeginOutcome begin(UUID tenantId);

    /**
     * Completes an enrolment: the provider checks the token the browser got from
     * its form against the code the cardholder's bank sent, and answers with a
     * reference HorecaOS may charge later.
     */
    ConfirmOutcome confirm(UUID tenantId, String sessionReference, String providerToken, String verificationCode);

    /**
     * Tells the provider to forget a reference, so a card a tenant removed
     * cannot be charged by anyone, including a bug of ours.
     */
    RevokeOutcome revoke(String cardTokenReference);

    /** What starting an enrolment did. */
    sealed interface BeginOutcome permits BeginOutcome.Begun, BeginOutcome.NotConfigured {

        /**
         * @param sessionReference what the browser hands back to confirm; never a secret
         * @param hostedFormUrl    where the provider's form is, when it is a page rather than a script
         * @param clientParameters the non-sensitive values the provider's script needs, such as a merchant id
         */
        record Begun(
                String sessionReference,
                @Nullable String hostedFormUrl,
                Map<String, String> clientParameters,
                Instant expiresAt)
                implements BeginOutcome {

            public Begun {
                clientParameters = Map.copyOf(clientParameters);
            }
        }

        /** No merchant account is connected yet. */
        record NotConfigured() implements BeginOutcome {}
    }

    /** What completing an enrolment did. */
    sealed interface ConfirmOutcome
            permits ConfirmOutcome.Enrolled, ConfirmOutcome.Refused, ConfirmOutcome.NotConfigured {

        /**
         * @param cardTokenReference a reference to charge, never a card number
         * @param last4              safe to show: the last four digits
         * @param brand              safe to show, when the provider says
         */
        record Enrolled(
                String cardTokenReference,
                String last4,
                @Nullable String brand,
                int expiryMonth,
                int expiryYear) implements ConfirmOutcome {

            /** Says which card, never which reference: a record's {@code toString} prints every component. */
            @Override
            public String toString() {
                return "Enrolled[card ending " + last4 + "]";
            }
        }

        /** The provider would not enrol it: a wrong code, an expired session, a card it will not keep. */
        record Refused(String reason) implements ConfirmOutcome {}

        record NotConfigured() implements ConfirmOutcome {}
    }

    /** What revoking a reference did. */
    sealed interface RevokeOutcome permits RevokeOutcome.Revoked, RevokeOutcome.Failed, RevokeOutcome.NotConfigured {

        record Revoked() implements RevokeOutcome {}

        /** The provider kept it, or could not be asked; the reference is already gone from HorecaOS either way. */
        record Failed(String reason) implements RevokeOutcome {}

        record NotConfigured() implements RevokeOutcome {}
    }
}
