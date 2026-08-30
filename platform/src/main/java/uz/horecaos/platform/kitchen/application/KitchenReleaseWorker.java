package uz.horecaos.platform.kitchen.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Fires buffered tickets when their release instant arrives (ADR 0041).
 *
 * <p>Polling PostgreSQL, not scheduling in memory and not Kafka. ADR 0041 is
 * explicit that Kafka is not the timer, and an in-memory timer is lost on every
 * restart and every deployment: the preorders it was holding would then never
 * reach a station, and nobody would find out until the customer arrived at 20:00
 * for food nobody had started.
 *
 * <p>Safe to run on every node. The claim is {@code FOR UPDATE SKIP LOCKED} and
 * the release itself is a conditional update, so two nodes share the buffer
 * rather than firing every ticket twice, and a cook pressing "release now" in the
 * same instant still settles at one outcome.
 *
 * <p>This is the third durable timer in the codebase after ADR 0019's approval
 * deadlines and ADR 0017's expiry, and ADR 0041 predicted it would be written a
 * third time rather than shared. It has been: the shared abstraction is worth
 * extracting once there are three real call sites to extract it from, and
 * inventing it here from one would fix the shape of a leasing API against a
 * single example.
 */
@Component
@ConditionalOnProperty(name = "horecaos.kitchen.release-worker.enabled", havingValue = "true",
        matchIfMissing = true)
public class KitchenReleaseWorker {

    private static final Logger log = LoggerFactory.getLogger(KitchenReleaseWorker.class);

    private final KitchenTicketService tickets;
    private final int batchSize;

    public KitchenReleaseWorker(KitchenTicketService tickets,
            @Value("${horecaos.kitchen.release-worker.batch-size:50}") int batchSize) {
        this.tickets = tickets;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${horecaos.kitchen.release-worker.initial-delay:PT10S}",
            fixedDelayString = "${horecaos.kitchen.release-worker.interval:PT5S}")
    public void releaseDueTickets() {
        try {
            int fired = tickets.releaseDue(batchSize);
            if (fired > 0) {
                log.debug("Released {} buffered ticket(s)", fired);
            }
        } catch (RuntimeException failure) {
            // One branch's failure must not stop the sweep: a stalled release
            // worker holds every other branch's preorders in the buffer, and a
            // buffer nobody drains is a service nobody cooks.
            log.error("The kitchen release sweep could not run", failure);
        }
    }
}
