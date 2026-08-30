package uz.qoida.platform.fiscal.application;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import uz.qoida.platform.fiscal.api.PartnerFiscalizationPort;
import uz.qoida.platform.fiscal.application.FiscalObligationService.ClaimedSubmission;

/**
 * Opens the receipt obligation a finished order owes, and sends it (ADR 0038).
 *
 * <p>The caller ADR 0038's checklist asks for: "open a {@code PARTNER} obligation
 * at capture, submit through {@code FiscalReceiptPort}". It runs a step later
 * than the ADR's wording — at completion rather than at capture — and the reason
 * is worth stating rather than hiding. Ordering publishes no {@code OrderCompleted}
 * or capture contract that this module could listen to, and reaching into either
 * module to add one is not this change's to make. So the trigger is the same
 * shape as the reporting sweeper's: a poll that looks for the <em>absence</em> of
 * a document, which is the one thing no message will ever announce.
 *
 * <p>The consequence of the later trigger is real and is not swept under: a
 * payment captured on an order that never completes has an obligation this build
 * does not open. That is the same gap the ADR already names under the reporting
 * sweeper — a captured payment whose submission never ran — narrowed rather than
 * closed, and closing it needs a capture signal that does not exist yet.
 *
 * <p>Polling PostgreSQL, like ADR 0019's approval deadlines, ADR 0017's expiry,
 * ADR 0041's release buffer and the reporting sweep beside this one. An in-memory
 * timer is lost on every deployment, and the orders it was watching would then
 * carry no fiscal document at all — which is precisely the state this exists to
 * end, reintroduced by the mechanism meant to end it.
 *
 * <p><strong>The provider call is made from here, outside every transaction.</strong>
 * {@link FiscalObligationService} claims documents in one short transaction and
 * settles each in another; the wait on Click or Payme happens between them
 * holding no pooled connection. The pool is ten wide and shared by every module,
 * so a degraded provider that held one per in-flight receipt would stall ordering
 * and tenancy along with itself — the property
 * {@code ExternalCallTransactionBoundaryTests} exists to keep.
 */
@Component
@ConditionalOnProperty(name = "qoida.fiscal.obligation-opener.enabled", havingValue = "true",
        matchIfMissing = true)
public class FiscalObligationSweeper {

    private static final Logger log = LoggerFactory.getLogger(FiscalObligationSweeper.class);

    private final FiscalObligationService obligations;
    private final PartnerFiscalizationPort partner;
    private final int batchSize;

    public FiscalObligationSweeper(FiscalObligationService obligations,
            PartnerFiscalizationPort partner,
            @Value("${qoida.fiscal.obligation-opener.batch-size:200}") int batchSize) {
        this.obligations = obligations;
        this.partner = partner;
        this.batchSize = batchSize;
    }

    /**
     * Every completed order acquires the document it owes.
     *
     * <p>Runs on its own schedule rather than inside the submission pass, because
     * the two answer different questions and fail differently: opening is a local
     * write that cannot fail because of a provider, and a provider outage must not
     * stop orders from acquiring a status.
     */
    @Scheduled(
            initialDelayString = "${qoida.fiscal.obligation-opener.initial-delay:PT20S}",
            fixedDelayString = "${qoida.fiscal.obligation-opener.interval:PT1M}")
    public void openObligations() {
        try {
            int opened = obligations.openObligations(batchSize);
            if (opened > 0) {
                log.debug("Opened {} fiscal obligation(s) for completed orders.", opened);
            }
        } catch (RuntimeException failure) {
            // One tenant's bad row must not stop the pass. A sweeper that dies is
            // indistinguishable, from the outside, from a day on which every order
            // was receipted.
            log.error("The fiscal obligation pass could not run", failure);
        }
    }

    /**
     * Every captured obligation is sent, once.
     *
     * <p>Claim, send, settle — and the send sits between two transactions rather
     * than inside one. A document claimed by a node that dies before it settles
     * stays {@code SUBMITTED} with a deadline on it, and the reporting sweeper
     * turns that into visible work within the hour. That is the correct direction
     * to fail in: the alternative — releasing a claim whose request may have
     * reached Click — is how one payment acquires two sale receipts.
     */
    @Scheduled(
            initialDelayString = "${qoida.fiscal.obligation-opener.submit-initial-delay:PT40S}",
            fixedDelayString = "${qoida.fiscal.obligation-opener.submit-interval:PT1M}")
    public void submitCapturedObligations() {
        List<ClaimedSubmission> claimed;
        try {
            claimed = obligations.claimSubmissions(batchSize);
        } catch (RuntimeException failure) {
            log.error("The fiscal submission pass could not claim documents", failure);
            return;
        }

        for (ClaimedSubmission claim : claimed) {
            try {
                PartnerFiscalizationPort.Outcome outcome = partner.submit(
                        claim.tenantId(), claim.documentId(), claim.idempotencyKey());
                obligations.settle(claim, outcome);
            } catch (RuntimeException failure) {
                // The document stays SUBMITTED. Deliberately: the request may have
                // reached the provider, and a document that may hold a receipt is
                // never returned to the queue. The reporting sweeper blocks it on
                // its deadline, which is a person looking at it rather than a
                // second submission.
                log.error("Fiscal document {} for order {} was claimed and its outcome is "
                                + "unknown; it stays SUBMITTED and will be swept on its deadline.",
                        claim.documentId(), claim.orderId(), failure);
            }
        }
    }
}
