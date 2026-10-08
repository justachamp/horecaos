package uz.horecaos.platform.commercial.api;

/**
 * What sending one document to an operator did (ADR 0096, ADR 0007's four outcomes
 * folded to the three a statement's sender acts on differently).
 *
 * <p>{@link Refused} and {@link NotSent} both mean the operator holds nothing, and
 * differ only in whether trying again could change the answer; {@link Uncertain} is
 * the one that matters, and it is never resolved by sending again.
 */
public sealed interface EInvoiceSendOutcome {

    /** The operator accepted the draft and named it. */
    record Accepted(String operatorDocumentId, EInvoiceOperatorState state, String rawStatus)
            implements EInvoiceSendOutcome {}

    /** The operator answered and refused the document on business grounds. Nothing is held there. */
    record Refused(String code, String detail) implements EInvoiceSendOutcome {}

    /** The document never reached the operator (connection refused, circuit open, no account). Nothing is held there. */
    record NotSent(String code, String detail) implements EInvoiceSendOutcome {}

    /** The operator may or may not hold the document; ask it, never send again. */
    record Uncertain(String code, String detail) implements EInvoiceSendOutcome {}
}
