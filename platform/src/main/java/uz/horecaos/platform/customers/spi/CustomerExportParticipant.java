package uz.horecaos.platform.customers.spi;

import java.util.Set;
import java.util.UUID;

/**
 * The seam another module's own data is gathered into a data-subject export through (ADR 0029: "export: assemble a
 * customer's data across modules, decrypted under audit").
 *
 * <p>The mirror of {@link CustomerErasureParticipant}, and for the same reason: customers owns the request and the
 * bundle, but it does not own every table that carries a copy of a customer's identity or behaviour. {@code
 * CustomerExportService} calls every registered participant, in the transaction that produces the bundle, and writes
 * one section per participant. Customers never imports {@code ordering}, {@code loyalty} or {@code marketing}; it
 * discovers the adapters by type, the direction the erasure seam already takes.
 *
 * <p><strong>The inventory is a build failure here too.</strong> {@code ExportInventoryTests} requires every table an
 * erasure participant answers for to be named by a participant's {@link ExportsCustomerData} or by an exemption with the
 * reason it is not part of a subject-access answer. A module that adds a table of a customer's data cannot merge without
 * saying whether she gets to see it.
 *
 * <p><strong>A participant decrypts, and says why.</strong> The values it returns are plaintext, because the bundle is
 * what the customer asked for; it reveals each protected value through {@code FieldProtection} with {@link
 * ExportSubject#purpose()}, the purpose the producing person declared, which {@code CustomerExportService} has already
 * written to the audit trail before this is called. It returns only what is hers: a participant never reads another
 * customer's row, a staff member's note about her that she is not entitled to, or a secret (a session token, a
 * verification code, a webhook credential). What it leaves out on purpose it names in {@link ExportSection#withheld()},
 * so the bundle's manifest says what is not there and why, instead of a reader having to guess.
 */
public interface CustomerExportParticipant {

    /**
     * Gathers whatever this participant's own module holds for the subject. Read-only; never called outside a
     * transaction already bound to {@code subject.tenantId()}.
     */
    ExportSection export(ExportSubject subject);

    /**
     * Whose data, and what the producing person declared.
     *
     * @param phoneLookupHashes the keyed lookup hashes (ADR 0029) of the phone numbers the account holds, so a
     *                          participant can find its own rows that hold the same number (a guest booking, a call, a
     *                          lead) without the number leaving {@code customers}
     * @param purpose           the declared purpose of the reveal, passed to every {@code FieldProtection.reveal}
     */
    record ExportSubject(UUID tenantId, UUID customerAccountId, Set<String> phoneLookupHashes, String purpose) {

        public ExportSubject {
            phoneLookupHashes = Set.copyOf(phoneLookupHashes);
        }
    }
}
