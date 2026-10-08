package uz.horecaos.platform.reviews.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Marks the clocks of open service-recovery cases that have passed them, once each (ADR 0159,
 * Decision 10).
 *
 * <p>A case is "at risk" and "breached" on read, from its due times, so the board is never wrong. What
 * this worker adds is the <em>mark</em>: the single instant a breach is counted and published, which is
 * what {@code recovery.sla_breach_rate} and the {@code RecoveryCaseSlaBreached} fact are made of. It
 * selects open cases only -- a case dismissed from NEW or resolved before this reaches it is never marked
 * afterwards -- and {@link RecoveryCaseService#markBreaches} takes the row lock first, so two workers
 * cannot both mark one and a case a person is closing at this instant is left to them.
 *
 * <p>Nothing here alerts anybody: whether a breach is announced to a staff chat is the brand's {@code
 * alertOnBreach} setting, read by {@code notifications}.
 */
@Component
public class RecoverySlaSweeper {

    private static final Logger log = LoggerFactory.getLogger(RecoverySlaSweeper.class);

    private final RecoveryCaseService cases;
    private final int batchSize;

    public RecoverySlaSweeper(
            RecoveryCaseService cases, @Value("${horecaos.reviews.sla-sweep.batch-size:200}") int batchSize) {
        this.cases = cases;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${horecaos.reviews.sla-sweep.initial-delay:PT1M}",
            fixedDelayString = "${horecaos.reviews.sla-sweep.interval:PT1M}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("The service-recovery clock sweep could not run", failure);
        }
    }

    /** @return how many clocks this pass marked, for a deterministic test */
    public int runOnce() {
        int marked = cases.markBreaches(batchSize);
        if (marked > 0) {
            log.info("Service-recovery sweep: {} clocks marked as breached", marked);
        }
        return marked;
    }
}
