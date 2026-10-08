package uz.horecaos.platform.commercial.api;

/** What asking an operator about one document answered (ADR 0096). */
public sealed interface EInvoiceStateOutcome {

    /** The operator knows the document, and this is its state in the operator's own words and ours. */
    record Known(String operatorDocumentId, EInvoiceOperatorState state, String rawStatus)
            implements EInvoiceStateOutcome {}

    /** The operator holds no document under this identifier, number or client reference. */
    record NotFound() implements EInvoiceStateOutcome {}

    /** The operator could not be asked or did not make sense; nothing is concluded. */
    record Unavailable(String code, String detail) implements EInvoiceStateOutcome {}
}
