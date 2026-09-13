package uz.horecaos.platform.customers.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains {@code customer.customer_import_runs} (row {@code X.13}/{@code
 * 5.1b}) — the async job surface the brief names as missing: without this,
 * a customer CSV import's {@code QUEUED} row would sit forever with nothing
 * ever polling for progress to advance.
 *
 * <p>One run per tick, claimed and processed to completion by {@link
 * CustomerImportService#processNextQueuedRun}, rather than a lease-and-batch
 * worker like {@code MediaVerificationWorker}: a customer CSV import is a
 * single request-scale file, not a stream of independent jobs, so there is
 * nothing to batch and the whole run finishing inside one scheduled
 * invocation is the simplest shape that still lets a poller watch {@code
 * rows_processed} climb in real time. The known cost of that shape: a
 * process killed mid-run leaves the row {@code RUNNING} with no lease to
 * expire and no sweep to reclaim it — an accepted limitation for this wave,
 * recorded rather than silently carried; a stuck-run alert in the shape of
 * {@code OnboardingStuckRunAlertSweeper} is the natural follow-up if this
 * ever bites in practice.
 */
@Component
@ConditionalOnProperty(
        name = "horecaos.customers.import.scheduler.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class CustomerImportRunWorker {

    private static final Logger log = LoggerFactory.getLogger(CustomerImportRunWorker.class);

    private final CustomerImportService imports;

    public CustomerImportRunWorker(CustomerImportService imports) {
        this.imports = imports;
    }

    @Scheduled(
            initialDelayString = "${horecaos.customers.import.scheduler.initial-delay:PT5S}",
            fixedDelayString = "${horecaos.customers.import.scheduler.interval:PT5S}")
    public void processQueuedRuns() {
        try {
            imports.processNextQueuedRun();
        } catch (RuntimeException failure) {
            // The claim itself faulted (a database hiccup) -- CustomerImportService
            // already settles any run it manages to claim, so there is no run id
            // to attribute this to. The schedule continues; the next tick retries.
            log.warn("Customer CSV import worker tick failed to claim a run", failure);
        }
    }
}
