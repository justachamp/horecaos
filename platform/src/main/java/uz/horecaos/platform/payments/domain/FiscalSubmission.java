package uz.horecaos.platform.payments.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * What a provider answered when asked to fiscalize (ADR 0013, ADR 0038).
 *
 * <p>Carries a classification for the same reason a payment does: a submission
 * whose response was lost is neither accepted nor refused. Click's
 * {@code submit_items} idempotency for a repeated {@code payment_id} is an open
 * question to Click, so an uncertain submission is read back through
 * {@code GET payment/ofd_data/{service_id}/{payment_id}} rather than resubmitted.
 *
 * @param status  what the document should become. {@link FiscalStatus#SUBMITTED}
 *                on Payme, whose outcome arrives later through
 *                {@code SetFiscalData} and may never arrive at all
 * @param providerStatusCode the provider's own code, kept verbatim. A non-zero one
 *                           is the evidence that there is <em>no</em> receipt
 */
public record FiscalSubmission(
        ProviderOutcome.Classification classification,
        FiscalStatus status,
        FiscalDocument.FiscalEvidence evidence,
        String providerStatusCode,
        String providerMessage,
        Instant submittedAt) {

    public FiscalSubmission {
        Objects.requireNonNull(classification, "A classification is required");
        Objects.requireNonNull(status, "A resulting fiscal status is required");
        Objects.requireNonNull(submittedAt, "A submission time is required");
    }

    public static FiscalSubmission issued(FiscalDocument.FiscalEvidence evidence, Instant at) {
        return new FiscalSubmission(ProviderOutcome.Classification.SUCCESS, FiscalStatus.ISSUED,
                evidence, null, null, at);
    }

    /** Payme: accepted, with the outcome to arrive later and asynchronously. */
    public static FiscalSubmission accepted(Instant at) {
        return new FiscalSubmission(ProviderOutcome.Classification.SUCCESS, FiscalStatus.SUBMITTED,
                null, null, null, at);
    }

    public static FiscalSubmission rejected(String statusCode, String message, Instant at) {
        return new FiscalSubmission(ProviderOutcome.Classification.REJECTED, FiscalStatus.FAILED,
                null, statusCode, message, at);
    }

    public static FiscalSubmission uncertain(String detail, Instant at) {
        return new FiscalSubmission(ProviderOutcome.Classification.UNCERTAIN, FiscalStatus.SUBMITTED,
                null, null, detail, at);
    }

    public Optional<FiscalDocument.FiscalEvidence> fiscalEvidence() {
        return Optional.ofNullable(evidence);
    }
}
