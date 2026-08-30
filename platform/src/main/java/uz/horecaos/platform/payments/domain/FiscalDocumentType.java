package uz.horecaos.platform.payments.domain;

/**
 * What a fiscal document records (ADR 0038).
 *
 * <p>A refund or a correction is a <strong>separate document</strong> linked to
 * the sale, never an overwrite of it. Payme's own statement is that a
 * {@code PERFORM} and a {@code CANCEL} produce two distinct receipts for one
 * order; an implementer who reads ADR 0038's "exactly one fiscal document" as a
 * uniqueness constraint writes the cancel data over the perform data and destroys
 * the only record that the sale was ever fiscalized.
 */
public enum FiscalDocumentType {
    SALE,

    REFUND,

    CORRECTION;

    public boolean corrects() {
        return this != SALE;
    }
}
