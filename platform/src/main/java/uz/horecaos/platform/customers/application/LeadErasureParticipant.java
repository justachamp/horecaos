package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.customers.infrastructure.persistence.JdbcLeadStore;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
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
 * <p>Only leads <em>linked</em> to the account are reached. A lead that was never linked is a number
 * and a first name with nothing tying it to the erased account, and the account's own contact points
 * have already been overwritten by the time participants run, so there is no number left to match
 * on; that is the one gap, and it is why an operator confirming a duplicate links the lead to the
 * account first. The journal of contact attempts holds no personal data and is left as it is: it is
 * evidence that a call was made, not who was called.
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
        leads.erasePersonalFields(
                tenantId,
                customerAccountId,
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
