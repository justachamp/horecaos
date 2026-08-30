package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import uz.horecaos.platform.notifications.domain.NotificationStatus;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NotificationRow;

/**
 * Drives the durable notification work (ADR 0020).
 *
 * <p>Polling PostgreSQL rather than scheduling in memory, for the reason
 * {@code OrderProcessWorker} gives: an in-memory timer is lost on every restart
 * and every deployment, and the messages it was holding are never sent — which is
 * invisible until a customer calls.
 *
 * <p>Safe to run on every node. The claim is {@code FOR UPDATE SKIP LOCKED}, so
 * workers share the queue rather than duplicating it, and the claim itself pushes
 * the row's next attempt out by a lease so a node that dies mid-send leaves work
 * that is recoverable rather than stuck.
 *
 * <p>One message's failure never stops the batch. A stalled sweep would hold every
 * other branch's confirmations behind one bad row.
 */
@Component
@ConditionalOnProperty(name = "horecaos.notifications.worker.enabled", havingValue = "true",
        matchIfMissing = true)
public class NotificationWorker {

    private static final Logger log = LoggerFactory.getLogger(NotificationWorker.class);

    private final JdbcNotificationStore notifications;
    private final NotificationEligibilityService eligibility;
    private final NotificationDispatchService dispatch;
    private final Clock clock;
    private final int batchSize;
    private final Duration lease;

    public NotificationWorker(JdbcNotificationStore notifications,
            NotificationEligibilityService eligibility, NotificationDispatchService dispatch,
            Clock clock,
            @Value("${horecaos.notifications.worker.batch-size:50}") int batchSize,
            @Value("${horecaos.notifications.worker.lease:PT2M}") Duration lease) {
        this.notifications = notifications;
        this.eligibility = eligibility;
        this.dispatch = dispatch;
        this.clock = clock;
        this.batchSize = batchSize;
        this.lease = lease;
    }

    @Scheduled(
            initialDelayString = "${horecaos.notifications.worker.initial-delay:PT10S}",
            fixedDelayString = "${horecaos.notifications.worker.interval:PT5S}")
    public void runOnce() {
        try {
            drain();
        } catch (RuntimeException failure) {
            log.error("The notification sweep could not run", failure);
        }
    }

    /**
     * Claims and settles one batch.
     *
     * @return how many messages were carried a step forward, so a test can drive
     *         the sweep deterministically rather than waiting on the scheduler
     */
    public int drain() {
        Instant now = clock.instant();
        UUID claimToken = UUID.randomUUID();
        List<NotificationRow> claimed =
                notifications.claimDue(now, now.plus(lease), batchSize, claimToken);

        int handled = 0;
        for (NotificationRow row : claimed) {
            try {
                advance(row);
                handled++;
            } catch (RuntimeException failure) {
                // Left claimed. The lease expires and the row comes back, so a
                // transient fault costs a delay rather than the message, and a
                // permanent one runs out of attempts and reaches an operator.
                log.error("Notification {} could not be advanced", row.id(), failure);
            }
        }
        return handled;
    }

    /**
     * Moves one claimed message one step.
     *
     * <p>Eligibility and dispatch are separate steps rather than one pass, so a
     * message that becomes ready is dispatched on the same sweep but a suppressed
     * one stops immediately. The row is re-read between them because eligibility
     * froze the template, the locale, and the endpoint onto it, and dispatch must
     * work from what was stored rather than from what was decided in memory.
     */
    private void advance(NotificationRow row) {
        if (NotificationStatus.CREATED.name().equals(row.status())) {
            if (!eligibility.evaluate(row)) {
                return;
            }
            notifications.find(row.tenantId(), row.id()).ifPresent(dispatch::dispatch);
            return;
        }
        dispatch.dispatch(row);
    }
}
