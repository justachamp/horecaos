package uz.horecaos.platform.integration.inbox;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Re-drives inbox work that nothing else will (ADR 0006).
 *
 * <p>Before this existed, a {@code RETRY_PENDING} row waited on a Kafka
 * redelivery, which only arrives because the offset was never acknowledged —
 * and that in turn only holds while the consumer stays assigned to the
 * partition. A rebalance, a restart, or a deploy advanced past the record and
 * the row sat at its scheduled retry time forever, counted by the backlog gauge
 * and driven by nothing. The two states that had no owner at all were the row
 * parked behind an earlier sibling, whose offset is deliberately acknowledged,
 * and the row abandoned mid-flight by a worker that died holding the lease.
 *
 * <p>The worker is the retry loop the ADR calls for and the listener is the
 * arrival path; they share {@link InboxExecutor}'s driving logic, so an item
 * cannot acquire different blocking or lease semantics depending on which one
 * happened to pick it up.
 */
@Component
@ConditionalOnProperty(
        name = "horecaos.messaging.inbox.retry-worker.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class InboxRetryWorker {

    private static final Logger log = LoggerFactory.getLogger(InboxRetryWorker.class);

    private final JdbcInboxStore store;
    private final InboxExecutor executor;
    private final InboxHandlerRegistry registry;
    private final int batchSize;
    private final AtomicBoolean running = new AtomicBoolean();

    public InboxRetryWorker(
            JdbcInboxStore store,
            InboxExecutor executor,
            InboxHandlerRegistry registry,
            @Value("${horecaos.messaging.inbox.retry-batch-size:20}") int batchSize) {
        if (batchSize < 1) {
            throw new IllegalArgumentException("The inbox retry batch size must be positive");
        }
        this.store = store;
        this.executor = executor;
        this.registry = registry;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${horecaos.messaging.inbox.retry-interval:5s}")
    public void redriveScheduledBatch() {
        redriveOnce();
    }

    /**
     * Drives one pass over the inbox items that are due for retry.
     *
     * @return how many due items this pass drove, which includes an item it
     *         found blocked and re-parked; the number is a measure of work
     *         attempted, not of side effects produced
     */
    public int redriveOnce() {
        if (!running.compareAndSet(false, true)) {
            // A previous pass is still running. Overlapping passes would select
            // the same due rows and lose the race on every one of them.
            return 0;
        }

        try {
            int driven = 0;
            // Per consumer rather than one query over the table, because the due
            // index leads on consumer_name; a scan without it would grow with
            // the processed history, which nothing deletes from.
            for (String consumerName : registry.consumerNames()) {
                List<JdbcInboxStore.StoredInboxItem> due = store.due(consumerName, batchSize);
                for (JdbcInboxStore.StoredInboxItem item : due) {
                    driven += redrive(item) ? 1 : 0;
                }
            }
            return driven;
        } finally {
            running.set(false);
        }
    }

    private boolean redrive(JdbcInboxStore.StoredInboxItem item) {
        try {
            executor.redrive(item);
            return true;
        } catch (RuntimeException failure) {
            // One poisonous row must not stop the pass, or the whole backlog
            // stalls behind it. The row keeps its state and comes round again.
            log.error("Inbox retry failed to drive item {} for {}", item.id(), item.consumerName(), failure);
            return false;
        }
    }
}
