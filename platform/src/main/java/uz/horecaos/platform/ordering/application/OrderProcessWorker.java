package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.DueTimerRow;

/**
 * Drives the durable order work: approval deadlines and the inventory,
 * fulfillment and payment process managers (ADR 0019).
 *
 * <p>Polling PostgreSQL rather than scheduling in memory. An in-memory timer is
 * lost on every restart and on every deployment, and the orders it was holding
 * sit awaiting an approval nobody will ever be asked for — which is invisible
 * until a customer calls.
 *
 * <p>Every method is safe to run on every node. The timer claim and every
 * process claim are {@code FOR UPDATE SKIP LOCKED}, so two workers share the
 * work rather than duplicating it.
 */
@Component
@ConditionalOnProperty(name = "horecaos.ordering.workers.enabled", havingValue = "true", matchIfMissing = true)
public class OrderProcessWorker {

    private static final Logger log = LoggerFactory.getLogger(OrderProcessWorker.class);

    /** Mirrors {@code OrderInventoryProcess.MAX_ATTEMPTS} — the same retry budget shape (H9). */
    private static final int MAX_TIMER_ATTEMPTS = 8;

    private final JdbcOrderStore orders;
    private final OrderStateService state;
    private final OrderInventoryProcess inventoryProcess;
    private final OrderFulfillmentProcess fulfillmentProcess;
    private final OrderPaymentProcess paymentProcess;
    private final Clock clock;
    private final int batchSize;
    private final Duration fulfillmentRecheckInterval;
    private final Duration paymentStaleAfter;
    private final Duration paymentRecheckInterval;
    private final Duration timerRetryBackoff;
    private final Duration timerStaleFiredAfter;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public OrderProcessWorker(
            JdbcOrderStore orders,
            OrderStateService state,
            OrderInventoryProcess inventoryProcess,
            OrderFulfillmentProcess fulfillmentProcess,
            OrderPaymentProcess paymentProcess,
            Clock clock,
            @Value("${horecaos.ordering.workers.batch-size:50}") int batchSize,
            @Value("${horecaos.ordering.workers.fulfillment.recheck-interval:PT30S}")
                    Duration fulfillmentRecheckInterval,
            // An open input on ADR 0019, same as checkout payment timing itself:
            // how long an order may sit in PAYMENT_AUTHORIZING before an operator
            // should look is a product decision, and this default is stated, not
            // considered. Never used to cancel anything — only to flag.
            @Value("${horecaos.ordering.workers.payment.stale-after:PT30M}") Duration paymentStaleAfter,
            @Value("${horecaos.ordering.workers.payment.recheck-interval:PT1M}") Duration paymentRecheckInterval,
            // H9: the same fixed backoff shape OrderInventoryProcess.RETRY_BACKOFF
            // uses for a thrown failure.
            @Value("${horecaos.ordering.workers.timers.retry-backoff:PT30S}") Duration timerRetryBackoff,
            // How long a timer may sit FIRED, with its order still
            // AWAITING_APPROVAL, before claimDueTimers treats it as abandoned by
            // whichever node claimed it (a restart or deploy between claim and
            // apply, not a thrown exception) rather than merely in flight. Far
            // longer than this worker's own PT5S tick so a live claim is never
            // mistaken for a stranded one.
            @Value("${horecaos.ordering.workers.timers.stale-fired-after:PT2M}") Duration timerStaleFiredAfter) {
        this.orders = orders;
        this.state = state;
        this.inventoryProcess = inventoryProcess;
        this.fulfillmentProcess = fulfillmentProcess;
        this.paymentProcess = paymentProcess;
        this.clock = clock;
        this.batchSize = batchSize;
        this.fulfillmentRecheckInterval = fulfillmentRecheckInterval;
        this.paymentStaleAfter = paymentStaleAfter;
        this.paymentRecheckInterval = paymentRecheckInterval;
        this.timerRetryBackoff = timerRetryBackoff;
        this.timerStaleFiredAfter = timerStaleFiredAfter;
    }

    /**
     * Fires approval deadlines that have come due.
     *
     * <p>Claiming and applying are separate transactions on purpose. The claim
     * marks the timer fired so no other worker takes it; applying it goes through
     * the same conditional update as a human decision, so a restaurant confirming
     * in the same instant still wins or loses deterministically rather than both
     * outcomes landing.
     *
     * <p>H9: a thrown failure here used to be logged and dropped, leaving the
     * timer permanently {@code FIRED} with nothing to reclaim it. It is now
     * quarantined into {@code FAILED_RETRYABLE} with a bounded backoff — the same
     * shape {@link OrderInventoryProcess} already uses — and, once {@link
     * #MAX_TIMER_ATTEMPTS} is exhausted, {@code MANUAL_ACTION_REQUIRED}: this
     * table's dead letter, logged at {@code ERROR} for an operator to find. {@link
     * JdbcOrderStore#claimDueTimers} separately reclaims a {@code FIRED} row whose
     * node never got this far at all.
     */
    @Scheduled(
            initialDelayString = "${horecaos.ordering.workers.timers.initial-delay:PT10S}",
            fixedDelayString = "${horecaos.ordering.workers.timers.interval:PT5S}")
    public void fireDueTimers() {
        Instant now = clock.instant();
        List<DueTimerRow> due = orders.claimDueTimers(now, now.minus(timerStaleFiredAfter), batchSize);
        for (DueTimerRow timer : due) {
            try {
                state.approvalDeadlineReached(timer.tenantId(), timer.orderId());
            } catch (RuntimeException failure) {
                quarantineTimer(timer, failure, now);
            }
        }
    }

    private void quarantineTimer(DueTimerRow timer, RuntimeException failure, Instant now) {
        int attempt = timer.attemptCount() + 1;
        if (attempt < MAX_TIMER_ATTEMPTS) {
            orders.markTimerFailed(timer.tenantId(), timer.timerId(), attempt, now.plus(timerRetryBackoff));
            log.warn(
                    "Approval deadline for order {} could not be applied; retrying (attempt {})",
                    timer.orderId(),
                    attempt,
                    failure);
        } else {
            orders.markTimerFailed(timer.tenantId(), timer.timerId(), attempt, null);
            log.error(
                    "Approval deadline for order {} could not be applied after {} attempts; manual action required",
                    timer.orderId(),
                    MAX_TIMER_ATTEMPTS,
                    failure);
        }
    }

    @Scheduled(
            initialDelayString = "${horecaos.ordering.workers.inventory.initial-delay:PT10S}",
            fixedDelayString = "${horecaos.ordering.workers.inventory.interval:PT5S}")
    public void runInventoryProcess() {
        try {
            int settled = inventoryProcess.runOnce(batchSize);
            if (settled > 0) {
                log.debug("Settled {} inventory process rows", settled);
            }
        } catch (RuntimeException failure) {
            log.error("The order inventory process could not run", failure);
        }
    }

    /**
     * Polls {@code ORDER_FULFILLMENT} rows, reflecting fulfillment's own
     * sourcing outcome rather than driving it (ADR 0019).
     */
    @Scheduled(
            initialDelayString = "${horecaos.ordering.workers.fulfillment.initial-delay:PT10S}",
            fixedDelayString = "${horecaos.ordering.workers.fulfillment.interval:PT5S}")
    public void runFulfillmentProcess() {
        try {
            int checked = fulfillmentProcess.runOnce(batchSize, clock.instant(), fulfillmentRecheckInterval);
            if (checked > 0) {
                log.debug("Checked {} fulfillment process rows", checked);
            }
        } catch (RuntimeException failure) {
            log.error("The order fulfillment process could not run", failure);
        }
    }

    /**
     * The {@code ORDER_PAYMENT} safety net and stuck list (ADR 0019): closes a
     * row the synchronous settle at the order's own transition somehow missed,
     * and flags — never cancels — an order that has sat in {@code
     * PAYMENT_AUTHORIZING} past a configured threshold.
     */
    @Scheduled(
            initialDelayString = "${horecaos.ordering.workers.payment.initial-delay:PT30S}",
            fixedDelayString = "${horecaos.ordering.workers.payment.interval:PT30S}")
    public void sweepStalePayments() {
        try {
            int checked = paymentProcess.sweep(clock.instant(), paymentStaleAfter, paymentRecheckInterval, batchSize);
            if (checked > 0) {
                log.debug("Checked {} payment process rows", checked);
            }
        } catch (RuntimeException failure) {
            log.error("The payment process sweep could not run", failure);
        }
    }
}
