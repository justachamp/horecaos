package uz.horecaos.platform.customers.application;

import java.time.Clock;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes out and then removes verification challenges (ADR 0015, ADR 0029).
 *
 * <p>Two jobs in one sweep because they are two ends of one lifecycle. Marking a
 * lapsed challenge {@code EXPIRED} is bookkeeping — nothing depends on it, because
 * every statement that touches a live challenge tests {@code expires_at} as well
 * as the status, and a correctness rule that only holds while a background job is
 * healthy is not a rule.
 *
 * <p>The delete is not bookkeeping. A challenge row carries a phone number under
 * ADR 0029 envelope encryption, and personal data kept because nobody scheduled
 * its removal is personal data kept for no lawful purpose. A settled challenge's
 * only remaining use is a short investigation window — "did we send that person a
 * code last Tuesday" — and after that the row is a liability with no reader.
 *
 * <p>Deliberately not done on the request path. Purging inline would make an
 * ordinary sign-in occasionally pay for a bulk delete, and the latency would
 * appear at random.
 */
@Component
@ConditionalOnProperty(
        name = "horecaos.customers.verification.sweep.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class VerificationChallengeSweeper {

    private static final Logger log = LoggerFactory.getLogger(VerificationChallengeSweeper.class);

    private final VerificationChallengeStore challenges;
    private final Clock clock;
    private final Duration retention;
    private final int batchSize;

    public VerificationChallengeSweeper(
            VerificationChallengeStore challenges,
            Clock clock,
            @Value("${horecaos.customers.verification.retention:P30D}") Duration retention,
            @Value("${horecaos.customers.verification.sweep.batch-size:1000}") int batchSize) {
        this.challenges = challenges;
        this.clock = clock;
        this.retention = retention;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${horecaos.customers.verification.sweep.initial-delay:PT2M}",
            fixedDelayString = "${horecaos.customers.verification.sweep.interval:PT15M}")
    public void sweep() {
        int expired = challenges.expirePending(clock.instant(), batchSize);
        int purged = challenges.purgeSettledBefore(clock.instant().minus(retention), batchSize);

        if (expired > 0 || purged > 0) {
            // Counts only. Naming the challenges would be harmless on its own and
            // would put a per-customer trail in a log that has no reason to hold
            // one.
            log.info("Verification sweep: expired {}, purged {}", expired, purged);
        }
    }
}
