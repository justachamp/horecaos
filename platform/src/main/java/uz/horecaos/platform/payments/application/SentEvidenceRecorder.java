package uz.horecaos.platform.payments.application;

import java.time.Instant;
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
     * Keeps the request that left, beside every one that left before it for this document: a reply that
     * never came proves nothing about what the provider received, so a resubmission adds to the history and
     * does not replace it.
     *
     * @param sentAt when the request left, by the platform's clock
     */
    void record(FiscalDocument document, ProviderBinding binding, SentReceipt sent, Instant sentAt);

    /**
     * The same, for a request that left for the tenant's own fiscal terminal (ADR 0166 «Providers»): there is
     * no merchant account to name, so the document keeps none. Records nothing by default, as the older
     * recorders did for everything.
     */
    default void recordTerminal(FiscalDocument document, SentReceipt sent, Instant sentAt) {}

    /** Records nothing: for the suites that predate the evidence surface and assert nothing about it. */
    SentEvidenceRecorder NONE = (document, binding, sent, sentAt) -> {};
}
