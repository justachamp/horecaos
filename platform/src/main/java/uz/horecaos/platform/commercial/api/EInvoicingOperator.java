package uz.horecaos.platform.commercial.api;

/**
 * One e-invoicing operator behind one port (ADR 0096, ADR 0007): Didox and
 * Faktura.uz each implement this, and neither operator's types, endpoints or
 * status codes leave their adapter.
 *
 * <p>Declared here and implemented in the {@code integration} module, which is
 * where provider connectivity lives (ADR 0007) and which already depends on this
 * module's API. The commercial module decides <em>what</em> is invoiced and
 * records <em>what became of it</em>; it never speaks an operator's protocol.
 *
 * <p>Nothing is thrown for an operator's failure. Every outcome an operator can
 * produce -- including "I did not answer" -- comes back as one of the sealed
 * results below, because the caller's next move depends on the distinction between
 * a document the operator refused (nothing is held there) and one it may hold
 * (never send it again, ask).
 *
 * <p>An adapter never sees, stores or returns signature material: a document is
 * sent as an unsigned draft and signed by people inside the operator's own product
 * (ADR 0096 decision 3).
 */
public interface EInvoicingOperator {

    /** The platform installation's provider type this adapter serves: {@code DIDOX} or {@code FAKTURA_UZ}. */
    String providerType();

    /** A non-sensitive version of what this adapter implements, recorded on the installation. */
    String adapterVersion();

    /**
     * Sends one document to the operator as a draft invoice from the seller to the buyer.
     *
     * <p>Sending is not idempotent at the operator: a second call may create a second
     * invoice. So a caller sends once per attempt and resolves {@link
     * EInvoiceSendOutcome.Uncertain} by {@link #state}, never by sending again.
     */
    EInvoiceSendOutcome send(EInvoiceOperatorAccount account, EInvoiceDocument document);

    /**
     * Asks the operator what became of a document: by its own identifier when the
     * operator named one, otherwise by the document number and client reference the
     * send carried. Sends nothing.
     */
    EInvoiceStateOutcome state(EInvoiceOperatorAccount account, EInvoiceDocumentReference reference);
}
