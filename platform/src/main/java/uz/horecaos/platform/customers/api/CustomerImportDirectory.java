package uz.horecaos.platform.customers.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * The one door a bulk contact import may use to bring customer accounts into
 * existence and record what an external source said about consent (ADR 0059
 * stage 3: the SendPulse contact-export import).
 *
 * <p>Deliberately not {@link CustomerDirectory}, whose own javadoc calls
 * itself "a lookup, never a creation" for a reason that still holds here: a
 * storefront request must never manufacture an account, and widening that
 * interface would blur why it refuses to. An import is a different kind of
 * caller with a different kind of authority — a capability-gated,
 * ADR 0027-audited control-plane operation, never a request a customer's own
 * token can reach — so it gets its own narrow port instead.
 *
 * <p>{@link #record} is the one write path in this module's api surface for
 * {@code customer.consent_decisions}, and it exists only because an import
 * is transcribing a decision a person already made somewhere else (SendPulse's
 * own subscribe/unsubscribe state), not forming a new opinion about consent —
 * the same distinction {@code ConsentService}'s own javadoc draws between
 * "notifications must not form its own opinion" and reading what was decided.
 * The implementation fixes the recorded {@code source} to {@code IMPORT}
 * (ADR 0015's existing vocabulary) so a caller through this port can never
 * claim a storefront or support-agent provenance for a decision that was
 * never made through either.
 */
public interface CustomerImportDirectory {

    /**
     * Every account already holding this phone number.
     *
     * <p>Plural for the same reason {@code CustomerProfileService.findAccountsByContact}
     * is: two people share a household phone, and a recycled number changes
     * owner. An import that found more than one match has no principled way
     * to choose between them and must report the row as needs-attention
     * rather than guess.
     */
    List<UUID> accountsWithPhone(UUID tenantId, String rawPhone);

    /**
     * Creates an account with no Keycloak principal link, for a contact whose
     * export row carries no phone number at all.
     */
    CustomerAccountRef createAccountWithoutPrincipal(UUID tenantId, UUID brandId);

    /**
     * Attaches a phone contact point to an account an import matched or just
     * created. Not marked primary when the account already holds one — see
     * the implementation's own handling of {@code ux_contact_point_primary}.
     */
    UUID attachPhoneContact(UUID tenantId, UUID accountId, String rawPhone);

    /**
     * Records one imported consent decision, sourced as {@code IMPORT}.
     *
     * @param brandId    null for a tenant-wide purpose, matching every other
     *                   caller of {@code ConsentService.record} at this scope
     * @param granted    true for a subscribed contact, false for an
     *                   unsubscribed or blocked one — never omitted, per the
     *                   record's own "never a silent default" rule
     * @param decidedAt  the date SendPulse recorded the subscription change,
     *                   when the export carries one; the import's own
     *                   timestamp otherwise
     * @param evidenceReference names the import run and row, so a later
     *                   subject-access request can point at exactly which
     *                   import produced this decision
     * @return the new consent_decisions row id
     */
    UUID record(
            UUID tenantId,
            UUID accountId,
            @Nullable UUID brandId,
            String purpose,
            String channel,
            boolean granted,
            String policyVersion,
            String evidenceReference,
            Instant decidedAt);
}
