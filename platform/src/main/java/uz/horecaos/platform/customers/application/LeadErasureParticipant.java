package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcLeadStore;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant.ErasedContacts;
import uz.horecaos.platform.iam.api.protection.DataClass;
import uz.horecaos.platform.iam.api.protection.FieldProtection;
import uz.horecaos.platform.iam.api.protection.FieldProtection.RecordRef;

/**
 * Erases what a lead holds of an erased customer (ADR 0029, ADR 0015, ADR 0111).
 *
 * <p>A lead keeps an encrypted phone, name and notes, and a customer's erasure that left them behind
 * would anonymise the account while the call centre's queue still knew who she was. {@code
 * customers} owns the table, so it registers itself as a participant of its own erasure rather than
 * extending {@code CustomerErasureService}: the same seam {@code marketing}'s projection uses, called
 * in the same transaction, so a failure here rolls the whole erasure back.
 *
 * <p>Two kinds of lead are hers. A lead an operator <em>linked</em> to the account -- when the
 * guest was identified, or when the lead converted into her order -- is reached by the link. A lead
 * nobody linked is reached by her number: the erasure hands this participant the lookup hashes of
 * the numbers she held, read before the account's own contact points were overwritten, and an
 * unlinked lead holding one of them is hers too. A lead linked to a <em>different</em> account is
 * that account's, whatever number it holds, and stays. The journal of contact attempts holds no
 * personal data and is left as it is: it is evidence that a call was made, not who was called.
 *
 * <p>Idempotent: a retried erasure overwrites the same rows with new tombstones.
 */
@Component
public class LeadErasureParticipant implements CustomerErasureParticipant {

    private static final String TOMBSTONE = "[erased]";
    private static final String LEAD_TABLE = "customer.leads";

    private final JdbcLeadStore leads;
    private final FieldProtection protection;
    private final Clock clock;

    public LeadErasureParticipant(JdbcLeadStore leads, FieldProtection protection, Clock clock) {
        this.leads = leads;
        this.protection = protection;
        this.clock = clock;
    }

    @Override
    public void erase(UUID tenantId, UUID customerAccountId) {
        erase(tenantId, customerAccountId, ErasedContacts.none());
    }

    @Override
    public void erase(UUID tenantId, UUID customerAccountId, ErasedContacts held) {
        leads.erasePersonalFields(
                tenantId,
                customerAccountId,
                held.phoneLookupHashes(),
                id -> protection
                        .protect(
                                tenantId,
                                DataClass.PERSONAL,
                                new RecordRef(LEAD_TABLE, "phone_encrypted", id),
                                TOMBSTONE)
                        .serialize(),
                TOMBSTONE,
                clock.instant());
    }
}
