package uz.horecaos.platform.notifications.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes the click tokens of push notifications that can no longer be tapped (ADR 0182, D14).
 *
 * <p>A token outlives the message by a week and then does nothing: an expired token and an unknown one
 * are the same answer. Left in place it would be a row per push message forever, each naming a
 * notification, so the table is trimmed rather than kept. Safe to run twice and to run concurrently:
 * it deletes only rows already past their expiry.
 */
@Component
public class PushClickTokenSweeper {

    private static final Logger log = LoggerFactory.getLogger(PushClickTokenSweeper.class);

    private static final int BATCH = 500;

    private final PushClickService clicks;

    public PushClickTokenSweeper(PushClickService clicks) {
        this.clicks = clicks;
    }

    @Scheduled(
            initialDelayString = "${horecaos.notifications.push.token-sweep-initial-delay:PT10M}",
            fixedDelayString = "${horecaos.notifications.push.token-sweep-interval:PT6H}")
    public void sweepOnce() {
        try {
            int removed = purgeExpired();
            if (removed > 0) {
                log.info("Removed {} expired push click tokens", removed);
            }
        } catch (RuntimeException failure) {
            log.error("The push click token sweep could not run", failure);
        }
    }

    /** @return how many expired tokens this pass removed, across as many batches as there were */
    public int purgeExpired() {
        int total = 0;
        int removed;
        do {
            removed = clicks.purgeExpired(BATCH);
            total += removed;
        } while (removed == BATCH);
        return total;
    }
}
