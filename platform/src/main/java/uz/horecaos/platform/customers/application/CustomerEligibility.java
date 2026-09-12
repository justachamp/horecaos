package uz.horecaos.platform.customers.application;

import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * Whether a customer may be reached for a purpose and channel, right now
 * (ADR 0015, ADR 0044).
 *
 * <p>The gap this closes: recording a consent decision from the operations
 * console never told anyone whether it actually made the customer
 * contactable. {@code MarketingEligibility.refusalFor} answers exactly that
 * question already, but only for a campaign in flight — it also weighs a
 * suppression list and a frequency cap, both meaningless outside a send, and
 * it lives in the {@code marketing} module, which depends on {@code
 * customers} and never the other way; importing it here would be a cycle
 * Spring Modulith refuses. So this class asks the same first two questions
 * {@code MarketingEligibility} asks — consent, then a verified endpoint —
 * directly against this module's own {@link ConsentService} and {@link
 * CustomerProfileService}, and stops there.
 *
 * <p>Nothing here reveals a contact value or a consent's evidence reference.
 * It answers a yes/no and, on no, which of two reasons — the read a
 * {@code CUSTOMER_READ} capability is enough for, never {@code
 * CUSTOMER_PII_REVEAL}.
 */
@Service
public class CustomerEligibility {

    private final ConsentService consent;
    private final CustomerProfileService profiles;

    public CustomerEligibility(ConsentService consent, CustomerProfileService profiles) {
        this.consent = consent;
        this.profiles = profiles;
    }

    /**
     * @param brandId required: a consent row with no brand is tenant-wide and
     *                a brand-scoped one is not, so an answer that did not ask
     *                the question at a specific brand would not be the
     *                question the console's consent form is actually about
     * @param channel {@code SMS}, {@code EMAIL}, {@code PUSH} or {@code
     *                TELEGRAM} — {@code MarketingEligibility#consentChannel}'s
     *                own vocabulary. {@code PUSH} and {@code TELEGRAM} address
     *                a device or a chat rather than a contact point (ADR
     *                0020), so only consent is asked for them; there is no
     *                endpoint here to be unverified
     */
    public Answer answer(UUID tenantId, UUID accountId, UUID brandId, String purpose, String channel) {
        boolean granted = consent.hasConsent(tenantId, accountId, brandId, purpose, channel);
        if (!granted) {
            return new Answer(false, Refusal.CONSENT_WITHHELD);
        }

        CustomerProfileService.ContactType required = contactTypeFor(channel);
        if (required != null) {
            boolean verified = profiles.contactPointSummaries(tenantId, accountId).stream()
                    .anyMatch(contact -> contact.type() == required && "VERIFIED".equals(contact.verificationStatus()));
            if (!verified) {
                return new Answer(false, Refusal.NO_VERIFIED_ENDPOINT);
            }
        }

        return new Answer(true, null);
    }

    /**
     * The ADR 0015 contact kind {@code channel} addresses, or null for a
     * channel this module holds no endpoint for. Mirrors {@code
     * marketing.MarketingEligibility#contactMethodFor} without importing it,
     * for the reason the class doc gives.
     */
    private static CustomerProfileService.@Nullable ContactType contactTypeFor(String channel) {
        return switch (channel) {
            case "SMS" -> CustomerProfileService.ContactType.PHONE;
            case "EMAIL" -> CustomerProfileService.ContactType.EMAIL;
            default -> null;
        };
    }

    /** Why {@link Answer#eligible} is false — {@code marketing.domain.RefusalReason}'s own two spellings. */
    public enum Refusal {
        CONSENT_WITHHELD,
        NO_VERIFIED_ENDPOINT
    }

    public record Answer(boolean eligible, @Nullable Refusal refusalReason) {}
}
