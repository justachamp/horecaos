package uz.horecaos.platform.catalog.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains {@code catalog.import_runs} (row 4.5b) — modelled directly on {@code
 * CustomerImportRunWorker}: without this, a catalog import's {@code QUEUED}
 * row would sit forever with nothing ever polling for progress to advance.
 *
 * <p>One run per tick, claimed and processed to completion by {@link
 * CatalogImportService#processNextQueuedRun}, for the identical reasoning
 * {@code CustomerImportRunWorker}'s own doc gives: a catalog CSV import is a
 * single request-scale file, not a stream of independent jobs, so the whole
 * run finishing inside one scheduled invocation is the simplest shape that
 * still lets a poller watch {@code rows_processed} climb in real time. A
 * process killed mid-run leaves the row {@code RUNNING} with no lease to
 * expire — the identical accepted limitation, recorded rather than silently
 * carried.
 */
@Component
@ConditionalOnProperty(name = "horecaos.catalog.import.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public class CatalogImportRunWorker {

    private static final Logger log = LoggerFactory.getLogger(CatalogImportRunWorker.class);

    private final CatalogImportService imports;

    public CatalogImportRunWorker(CatalogImportService imports) {
        this.imports = imports;
    }

    @Scheduled(
            initialDelayString = "${horecaos.catalog.import.scheduler.initial-delay:PT5S}",
            fixedDelayString = "${horecaos.catalog.import.scheduler.interval:PT5S}")
    public void processQueuedRuns() {
        try {
            imports.processNextQueuedRun();
        } catch (RuntimeException failure) {
            // The claim itself faulted (a database hiccup) -- CatalogImportService
            // already settles any run it manages to claim, so there is no run id
            // to attribute this to. The schedule continues; the next tick retries.
            log.warn("Catalog import worker tick failed to claim a run", failure);
        }
    }
}
