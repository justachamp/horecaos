package uz.horecaos.platform.notifications.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes the unsubscribe tokens of campaign emails whose way out has expired (ADR 0182, D13).
 *
 * <p>A token is valid for 400 days and then answers as an unknown one does (V0671). Every campaign email mints one, so
 * left in place the table grows by a row per message for ever, each naming a customer account and a notification. The
 * table is trimmed rather than kept; V0671 granted the application role {@code DELETE} and built {@code
 * ix_unsubscribe_token_expiry} for exactly this. Safe to run twice and to run concurrently: it deletes only rows already
 * past their expiry.
 *
 * <p>Not report-only, unlike the sweeps over personal data (ADR 0029): an expired token holds no address, no name and no
 * token, and removing it changes no answer anyone can be given.
 */
@Component
public class UnsubscribeTokenSweeper {

    private static final Logger log = LoggerFactory.getLogger(UnsubscribeTokenSweeper.class);

    private static final int BATCH = 500;

    private final EmailUnsubscribeService unsubscribes;

    public UnsubscribeTokenSweeper(EmailUnsubscribeService unsubscribes) {
        this.unsubscribes = unsubscribes;
    }

    @Scheduled(
            initialDelayString = "${horecaos.notifications.email.unsubscribe-sweep-initial-delay:PT12M}",
            fixedDelayString = "${horecaos.notifications.email.unsubscribe-sweep-interval:PT6H}")
    public void sweepOnce() {
        try {
            int removed = purgeExpired();
            if (removed > 0) {
                log.info("Removed {} expired unsubscribe tokens", removed);
            }
        } catch (RuntimeException failure) {
            log.error("The unsubscribe token sweep could not run", failure);
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
