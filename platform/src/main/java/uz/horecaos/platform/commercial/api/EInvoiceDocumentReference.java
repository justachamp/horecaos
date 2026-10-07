package uz.horecaos.platform.commercial.api;

import java.time.LocalDate;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * How to find a document already sent (ADR 0096): the operator's own identifier when
 * it gave one, otherwise the number and client reference the send carried -- which is
 * what resolves a send whose answer was lost.
 */
public record EInvoiceDocumentReference(
        @Nullable String operatorDocumentId, String clientReference, String documentNumber, LocalDate documentDate) {

    public EInvoiceDocumentReference {
        Objects.requireNonNull(clientReference, "A client reference is required");
        Objects.requireNonNull(documentNumber, "A document number is required");
        Objects.requireNonNull(documentDate, "A document date is required");
    }
}
