package uz.horecaos.platform.payments.application;

import uz.horecaos.platform.payments.domain.FiscalDocument;
import uz.horecaos.platform.payments.domain.ProviderBinding;
import uz.horecaos.platform.payments.domain.SentReceipt;

/**
 * Keeps what was filed, so an incorrect receipt is explicable later (ADR 0166, ADR 0029).
 *
 * <p>Called once a provider has been sent a receipt request, before the document's status moves,
 * so the evidence of the request is on the row whatever the provider then answers.
 */
public interface SentEvidenceRecorder {

    /**
     * @param linesSynthetic true when the lines were the bridge's one-line placeholder and not lines
     *                       built from the sale
     */
    void record(FiscalDocument document, ProviderBinding binding, SentReceipt sent, boolean linesSynthetic);

    /** Records nothing: for the suites that predate the evidence surface and assert nothing about it. */
    SentEvidenceRecorder NONE = (document, binding, sent, linesSynthetic) -> {};
}
