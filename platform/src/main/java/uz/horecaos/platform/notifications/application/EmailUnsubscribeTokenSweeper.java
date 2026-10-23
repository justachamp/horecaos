package uz.horecaos.platform.notifications.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes the unsubscribe tokens of campaign emails that can no longer be used (ADR 0182, D13).
 *
 * <p>A token is valid for {@code horecaos.notifications.email.unsubscribe-token-validity} (400 days by
 * default) and then does nothing: an expired token and an unknown one are the same answer. A token is
 * minted for every send attempt, a retry included, and each row names a customer account and a
 * notification, so left in place the table would be a row per attempt forever. It is trimmed instead.
 * Safe to run twice and to run concurrently: it deletes only rows already past their expiry.
 */
@Component
public class EmailUnsubscribeTokenSweeper {

    private static final Logger log = LoggerFactory.getLogger(EmailUnsubscribeTokenSweeper.class);

    private static final int BATCH = 500;

    private final EmailUnsubscribeService unsubscribes;

    public EmailUnsubscribeTokenSweeper(EmailUnsubscribeService unsubscribes) {
        this.unsubscribes = unsubscribes;
    }

    @Scheduled(
            initialDelayString = "${horecaos.notifications.email.unsubscribe-token-sweep-initial-delay:PT10M}",
            fixedDelayString = "${horecaos.notifications.email.unsubscribe-token-sweep-interval:PT6H}")
    public void sweepOnce() {
        try {
            int removed = purgeExpired();
            if (removed > 0) {
                log.info("Removed {} expired email unsubscribe tokens", removed);
            }
        } catch (RuntimeException failure) {
            log.error("The email unsubscribe token sweep could not run", failure);
        }
    }

    /** @return how many expired tokens this pass removed, across as many batches as there were */
    public int purgeExpired() {
        int total = 0;
        int removed;
        do {
            removed = unsubscribes.purgeExpired(BATCH);
            total += removed;
        } while (removed == BATCH);
        return total;
    }
}
