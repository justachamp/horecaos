package uz.horecaos.platform.customers.spi;

import java.util.Set;
import java.util.UUID;

/**
 * The seam another module's own erasure operation is called through (ADR 0029, ADR 0044).
 *
 * <p>Customers owns the account and the request that governs erasure, but it does not own every table that
 * carries a copy of a customer's identity or their behaviour. {@code CustomerErasureService} calls every
 * registered participant, in the same transaction as its own anonymisation, whenever a request is executed, and
 * stores what each one reports as the request's proof.
 *
 * <p><strong>Customers depends on nothing here; other modules depend on this.</strong> A module that keeps a
 * copy of customer identity or behaviour registers a bean implementing this interface and carrying {@link
 * ErasesCustomerData}. Customers never imports {@code marketing}, {@code loyalty} or {@code ordering}, which would
 * invert the module boundary; it discovers the adapters by type, the same direction {@code
 * customers.spi.VerificationCodeTransport} already uses for the SMS adapter customers does not own.
 *
 * <p><strong>The inventory is a build failure, not a convention.</strong> {@code ErasureInventoryTests} reads
 * the database catalog for every table that carries a customer id or a PERSONAL-class column and requires each to
 * be named by a participant's {@link ErasesCustomerData} or by an exemption with a reason. A module that adds a
 * table holding a phone number cannot merge without saying who erases it.
 *
 * <p>Every participant is called inside the same database transaction as the request's own transition to {@code
 * COMPLETED}, so a participant that throws rolls the whole execution back rather than leaving the account
 * anonymised while a projection row survives it.
 */
public interface CustomerErasureParticipant {

    /**
     * Erases or anonymises whatever this participant's own module holds for one customer, and reports how many
     * rows it changed per table.
     *
     * <p>Never called outside a transaction already bound to {@code tenantId}. Implementations are idempotent
     * under retry, the same way {@code CustomerErasureService} itself is: an execution that failed after some
     * participants ran and is retried calls every participant again.
     *
     * @param held what the account held that identifies her in another module's rows, read <em>before</em> the
     *             account's own contact points were overwritten, because afterwards no number is left to match on
     */
    ErasureCounts erase(UUID tenantId, UUID customerAccountId, ErasedContacts held);

    /**
     * The keyed lookup hashes (ADR 0029) of the phone numbers an erased account held, as they were when the
     * erasure began. A hash, never a number: a participant can find its own rows that hold the same number
     * without the number leaving {@code customers}.
     */
    record ErasedContacts(Set<String> phoneLookupHashes) {

        public ErasedContacts {
            phoneLookupHashes = Set.copyOf(phoneLookupHashes);
        }

        public static ErasedContacts none() {
            return new ErasedContacts(Set.of());
        }
    }
}
