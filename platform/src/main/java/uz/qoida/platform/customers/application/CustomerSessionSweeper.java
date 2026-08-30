package uz.qoida.platform.customers.application;

import java.time.Clock;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Removes customer sessions that are over (ADR 0051).
 *
 * <p>Not a correctness control, and it matters that it is not: every statement
 * that resolves a session tests {@code expires_at} and {@code revoked_at} itself,
 * so a sweeper that stopped running would leave rows behind and would not let
 * anybody in. A rule that only holds while a background job is healthy is not a
 * rule, and this platform has been bitten by treating one as though it were.
 *
 * <p>What it is instead is housekeeping with a deadline attached. A row per
 * sign-in per customer, kept forever, is a table that grows with traffic and
 * answers nothing: the durable record of a sign-in is the ADR 0027 audit fact,
 * which outlives the session and is designed to be kept. The grace period is what
 * keeps a support conversation possible for a little while after the session
 * ended.
 *
 * <p>Deliberately not done on the request path. Purging inline would make an
 * ordinary page view occasionally pay for a bulk delete.
 */
@Component
@ConditionalOnProperty(name = "qoida.customers.session.sweep.enabled",
        havingValue = "true", matchIfMissing = true)
public class CustomerSessionSweeper {

    private static final Logger log = LoggerFactory.getLogger(CustomerSessionSweeper.class);

    private final CustomerSessionStore sessions;
    private final Clock clock;
    private final Duration grace;
    private final int batchSize;

    public CustomerSessionSweeper(
            CustomerSessionStore sessions,
            Clock clock,
            @Value("${qoida.customers.session.retention:P7D}") Duration grace,
            @Value("${qoida.customers.session.sweep.batch-size:1000}") int batchSize) {

        this.sessions = sessions;
        this.clock = clock;
        this.grace = grace;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${qoida.customers.session.sweep.initial-delay:PT3M}",
            fixedDelayString = "${qoida.customers.session.sweep.interval:PT1H}")
    public void sweep() {
        int purged = sessions.purgeEndedBefore(clock.instant().minus(grace), batchSize);
        if (purged > 0) {
            // A count. Naming the sessions would put a per-customer trail in a log
            // that has no reason to hold one.
            log.info("Customer session sweep: purged {}", purged);
        }
    }
}
