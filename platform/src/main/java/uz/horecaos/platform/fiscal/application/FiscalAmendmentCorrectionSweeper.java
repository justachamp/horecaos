package uz.horecaos.platform.fiscal.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link FiscalAmendmentCorrections} on a schedule (ADR 0166 Decision 8).
 *
 * <p>Polling PostgreSQL, like the other fiscal sweepers: the flag is a column on an append-only table, and an
 * in-memory timer lost on every deployment would leave an amended order with a receipt that no longer states
 * it and nobody told. The pass is cheap (a partial index over the flagged revisions of a window) and
 * idempotent, so running it often costs nothing and running it rarely only delays a worklist entry.
 */
@Component
@ConditionalOnProperty(
        name = "horecaos.fiscal.amendment-corrections.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class FiscalAmendmentCorrectionSweeper {

    private static final Logger log = LoggerFactory.getLogger(FiscalAmendmentCorrectionSweeper.class);

    private final FiscalAmendmentCorrections corrections;
    private final int batchSize;

    public FiscalAmendmentCorrectionSweeper(
            FiscalAmendmentCorrections corrections,
            @Value("${horecaos.fiscal.amendment-corrections.batch-size:500}") int batchSize) {
        this.corrections = corrections;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${horecaos.fiscal.amendment-corrections.initial-delay:PT90S}",
            fixedDelayString = "${horecaos.fiscal.amendment-corrections.interval:PT5M}")
    public void openRequests() {
        try {
            int opened = corrections.openRequests(batchSize);
            if (opened > 0) {
                log.info("Opened {} correction request(s) for orders amended after their receipt was filed.", opened);
            }
        } catch (RuntimeException failure) {
            // A sweeper that dies is indistinguishable, from the outside, from a day on which no order was amended.
            log.error("The fiscal amendment correction pass could not run", failure);
        }
    }
}
