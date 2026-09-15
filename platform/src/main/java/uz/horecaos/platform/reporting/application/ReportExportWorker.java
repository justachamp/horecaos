package uz.horecaos.platform.reporting.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains {@code reporting.report_exports} (ADR 0043/ADR 0029, wave P28's export centre) — the
 * audited job queue {@code POST .../reporting/exports} would otherwise queue rows into forever,
 * with nothing that ever produces an artefact.
 *
 * <p>One row per tick, claimed and processed to completion by {@link
 * ReportExportService#processNextQueued}, the same shape {@code CustomerImportRunWorker} uses for
 * the identical reason: an export is a single request-scale query and CSV write, not a stream of
 * independent jobs to batch, so the simplest shape that still lets {@code GET
 * .../reporting/reports/{id}} watch a row leave {@code QUEUED} is the whole job finishing inside
 * one scheduled invocation. The same accepted limitation as that class's own doc states: a process
 * killed mid-run leaves the row {@code RUNNING} with no lease to expire and no sweep to reclaim it.
 */
@Component
@ConditionalOnProperty(
        name = "horecaos.reporting.exports.scheduler.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ReportExportWorker {

    private static final Logger log = LoggerFactory.getLogger(ReportExportWorker.class);

    private final ReportExportService exports;

    public ReportExportWorker(ReportExportService exports) {
        this.exports = exports;
    }

    @Scheduled(
            initialDelayString = "${horecaos.reporting.exports.scheduler.initial-delay:PT5S}",
            fixedDelayString = "${horecaos.reporting.exports.scheduler.interval:PT5S}")
    public void processQueuedExports() {
        try {
            exports.processNextQueued();
        } catch (RuntimeException failure) {
            // The claim itself faulted (a database hiccup) -- ReportExportService already
            // settles any export it manages to claim, so there is no export id to attribute
            // this to. The schedule continues; the next tick retries.
            log.warn("Report export worker tick failed to claim an export", failure);
        }
    }
}
