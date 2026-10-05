package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderRequoteStore;

/**
 * Judges each scheduled order's promotions again as its promised time approaches (ADR 0140,
 * ADR 0019, row 6.1).
 *
 * <p>The checkpoint is the order's promised time less {@code lead}: one hour by default, a
 * provisional operational figure and not a tenant policy, because ADR 0019 leaves the checkpoint
 * instants of a scheduled order open. Each order is re-quoted once. An order the sweep could not
 * price is recorded as such rather than retried for ever, and the sweep is safe to run on every
 * node: the finding is unique per order, so the node that loses a race writes nothing.
 *
 * <p>Polling PostgreSQL, like the kitchen's release sweep, because an in-memory timer is lost on
 * every restart and a checkpoint that never ran is a price nobody re-checked.
 */
@Component
@ConditionalOnProperty(
        name = "horecaos.ordering.scheduled-requote.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ScheduledOrderRequoteWorker {

    private static final Logger log = LoggerFactory.getLogger(ScheduledOrderRequoteWorker.class);

    private final ScheduledOrderRequoteService requotes;
    private final JdbcOrderRequoteStore store;
    private final Clock clock;
    private final Duration lead;
    private final int batchSize;

    public ScheduledOrderRequoteWorker(
            ScheduledOrderRequoteService requotes,
            JdbcOrderRequoteStore store,
            Clock clock,
            @Value("${horecaos.ordering.scheduled-requote.lead:PT60M}") Duration lead,
            @Value("${horecaos.ordering.scheduled-requote.batch-size:50}") int batchSize) {
        this.requotes = requotes;
        this.store = store;
        this.clock = clock;
        this.lead = lead;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${horecaos.ordering.scheduled-requote.initial-delay:PT30S}",
            fixedDelayString = "${horecaos.ordering.scheduled-requote.interval:PT1M}")
    public void sweep() {
        try {
            int judged = sweepOnce(clock.instant());
            if (judged > 0) {
                log.debug("Re-quoted {} scheduled order(s) at their checkpoint", judged);
            }
        } catch (RuntimeException failure) {
            // One sweep's failure must not stop the next: the orders are still due.
            log.error("The scheduled-order re-quote sweep could not run", failure);
        }
    }

    /**
     * One pass over the orders due at {@code now}.
     *
     * @return how many findings were written
     */
    public int sweepOnce(Instant now) {
        int judged = 0;
        for (var due : store.dueForCheckpoint(now, lead.toSeconds(), batchSize)) {
            try {
                if (requotes.requoteAtCheckpoint(due.tenantId(), due.orderId(), now)
                        .isPresent()) {
                    judged++;
                }
            } catch (RuntimeException failure) {
                // The order's id and the failure's class, never its message: a pricing or
                // decryption message can carry an address or a name (ADR 0029).
                log.error(
                        "Order {} could not be re-quoted at its checkpoint: {}",
                        due.orderId(),
                        failure.getClass().getSimpleName());
                try {
                    requotes.recordFailure(due.tenantId(), due.orderId(), now);
                    judged++;
                } catch (RuntimeException recording) {
                    log.error(
                            "The failed re-quote of order {} could not be recorded: {}",
                            due.orderId(),
                            recording.getClass().getSimpleName());
                }
            }
        }
        return judged;
    }
}
