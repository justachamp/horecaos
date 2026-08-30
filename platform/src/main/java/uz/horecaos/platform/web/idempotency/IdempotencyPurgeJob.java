package uz.horecaos.platform.web.idempotency;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes expired idempotency records (ADR 0031).
 *
 * <p>Deliberately scheduled rather than done on the request path. Purging inline
 * would make an ordinary checkout occasionally pay for a bulk delete, and the
 * latency would appear at random.
 *
 * <p>Retention is per record, so a monetary operation can outlive the default
 * without holding every other record for the same period.
 */
@Component
@ConditionalOnProperty(name = "horecaos.api.idempotency.purge.enabled", havingValue = "true", matchIfMissing = true)
public class IdempotencyPurgeJob {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyPurgeJob.class);

    private final IdempotencyService idempotency;

    public IdempotencyPurgeJob(IdempotencyService idempotency) {
        this.idempotency = idempotency;
    }

    @Scheduled(
            initialDelayString = "${horecaos.api.idempotency.purge.initial-delay:PT5M}",
            fixedDelayString = "${horecaos.api.idempotency.purge.interval:PT1H}")
    public void purge() {
        int removed = idempotency.purgeExpired();
        if (removed > 0) {
            log.info("Purged {} expired idempotency records", removed);
        }
    }
}
