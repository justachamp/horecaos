package uz.horecaos.platform.commercial.api;

import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * How to find a document already sent (ADR 0096): the operator's own identifier when
 * it gave one, otherwise the number and client reference the send carried -- which is
 * what resolves a send whose answer was lost.
 *
 * <p>{@code otherAttemptDocumentIds} are the identifiers the operator has already given for
 * <em>other</em> attempts to send the same statement. A number is shared by every attempt for a
 * statement, so a lookup by number can meet the documents of earlier attempts as well as the one it
 * is looking for; an adapter that finds a document by number must not answer with one of these,
 * because it belongs to an attempt that has its own record.
 */
public record EInvoiceDocumentReference(
        @Nullable String operatorDocumentId,
        String clientReference,
        String documentNumber,
        LocalDate documentDate,
        Set<String> otherAttemptDocumentIds) {

    public EInvoiceDocumentReference {
        Objects.requireNonNull(clientReference, "A client reference is required");
        Objects.requireNonNull(documentNumber, "A document number is required");
        Objects.requireNonNull(documentDate, "A document date is required");
        otherAttemptDocumentIds = Set.copyOf(Objects.requireNonNull(otherAttemptDocumentIds));
    }

    /** A reference to a statement none of whose other attempts hold a document at the operator. */
    public EInvoiceDocumentReference(
            @Nullable String operatorDocumentId,
            String clientReference,
            String documentNumber,
            LocalDate documentDate) {
        this(operatorDocumentId, clientReference, documentNumber, documentDate, Set.of());
    }
}
