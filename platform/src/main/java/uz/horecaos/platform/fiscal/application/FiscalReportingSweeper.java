package uz.horecaos.platform.fiscal.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Turns a provider's silence into work somebody can see (ADR 0038).
 *
 * <p>ADR 0038 says in as many words that nobody else owns this, so it does.
 * Payme's {@code SetFiscalData} is inbound and optional to implement, and
 * {@code receipts.set_fiscal_data} runs the other way — it is for a merchant who
 * fiscalized on their own equipment — so there is no merchant-initiated retry on
 * the reporting path at all. Without a timer, "not yet reported" and "there is no
 * receipt" are the same row in the same status, and the tenant learns the
 * difference from an inspector.
 *
 * <p>Polling PostgreSQL, like ADR 0041's release worker, ADR 0019's approval
 * deadlines and ADR 0017's expiry before it. An in-memory timer is lost on every
 * restart and every deployment, and the documents it was watching would then sit
 * {@code SUBMITTED} for ever — which is precisely the failure this exists to
 * remove, reintroduced by the mechanism meant to remove it.
 *
 * <p>Deliberately not a Kafka consumer and deliberately not driven by an event.
 * The thing being detected is the <em>absence</em> of a message. Nothing will
 * arrive to trigger it.
 */
@Component
@ConditionalOnProperty(name = "horecaos.fiscal.reporting-sweeper.enabled", havingValue = "true", matchIfMissing = true)
public class FiscalReportingSweeper {

    private static final Logger log = LoggerFactory.getLogger(FiscalReportingSweeper.class);

    private final FiscalDocumentService documents;
    private final int batchSize;

    public FiscalReportingSweeper(
            FiscalDocumentService documents,
            @Value("${horecaos.fiscal.reporting-sweeper.batch-size:200}") int batchSize) {
        this.documents = documents;
        this.batchSize = batchSize;
    }

    /**
     * The interval is a minute by default and the deadline is an hour, so a
     * document is blocked within a minute of becoming overdue rather than within
     * an hour of it. The sweep is cheap — a partial index on
     * {@code status = 'SUBMITTED'} over a set that is normally empty — and running
     * it rarely would add a second, invisible deadline on top of the configured
     * one.
     */
    @Scheduled(
            initialDelayString = "${horecaos.fiscal.reporting-sweeper.initial-delay:PT30S}",
            fixedDelayString = "${horecaos.fiscal.reporting-sweeper.interval:PT1M}")
    public void blockOverdueReports() {
        try {
            int blocked = documents.sweepOverdueReports(batchSize);
            if (blocked > 0) {
                // At WARN, and counted. A sweep that blocks documents every run is
                // either a provider that has stopped reporting or a deadline set too
                // short, and both of those are things somebody should be told about
                // rather than something to find by reading a worklist.
                log.warn("The fiscal reporting sweep blocked {} unreported document(s).", blocked);
            }
        } catch (RuntimeException failure) {
            // One tenant's failure must not stop the sweep. A sweeper that dies is
            // indistinguishable, from the outside, from a day on which every
            // provider reported on time.
            log.error("The fiscal reporting sweep could not run", failure);
        }
    }
}
