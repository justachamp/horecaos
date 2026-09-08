package uz.horecaos.platform.ordering.application;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.fulfillment.api.DeliveryPlanner;
import uz.horecaos.platform.fulfillment.api.DeliveryPlanner.SourcingOutcome;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderProcessStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderProcessStore.ProcessRow;

/**
 * The fulfillment process manager (ADR 0019).
 *
 * <p>Unlike {@link OrderInventoryProcess}, this manager never drives the
 * effect — {@link DeliveryPlanTrigger} already opened the plan and {@code
 * fulfillment}'s own sourcing job already works it, durably and idempotently,
 * with its own ladder. What this class does is poll the one signal {@link
 * DeliveryPlanner#sourcingOutcome} exposes and reflect it: a stuck delivery is
 * a stuck delivery whether or not anyone happens to be looking at the
 * fulfillment module's own screen, and until now nothing put that fact next to
 * the same order's payment and inventory rows.
 *
 * <p>Deliberately not a competing timeout. Fulfillment's own state machine
 * ({@code fulfillment.domain.sourcing.PlanStatus}) already has a considered
 * ladder for "try another courier", and {@code PlanStatus#MANUAL_ACTION_REQUIRED}
 * is the one signal that means it has genuinely given up — this class defers to
 * that signal entirely rather than inventing a second, less informed opinion
 * about how long sourcing should be allowed to take.
 */
@Service
public class OrderFulfillmentProcess {

    public static final String PROCESS_NAME = "ORDER_FULFILLMENT";

    private static final Logger log = LoggerFactory.getLogger(OrderFulfillmentProcess.class);

    /**
     * How long a claimed row may find no plan at all before it needs a person —
     * the one anomaly this class can observe on its own rather than deferring to
     * fulfillment's signal, because it means the checkpoint's own plan id no
     * longer resolves to anything.
     */
    private static final int MAX_MISSING_PLAN_ATTEMPTS = 8;

    private final JdbcOrderProcessStore processes;
    private final DeliveryPlanner planner;
    private final ObjectMapper objectMapper;

    public OrderFulfillmentProcess(
            JdbcOrderProcessStore processes, DeliveryPlanner planner, ObjectMapper objectMapper) {
        this.processes = processes;
        this.planner = planner;
        this.objectMapper = objectMapper;
    }

    /** Called inside the same transaction {@link DeliveryPlanTrigger} opens the plan in. */
    public void enqueue(UUID orderId, UUID tenantId, UUID planId, Instant now) {
        processes.enqueue(orderId, tenantId, PROCESS_NAME, checkpoint(planId), now);
    }

    /**
     * Polls the outstanding rows, one transaction for the batch — the claim and
     * the settle have to land together, for the reason {@link
     * OrderInventoryProcess#runOnce} gives for its own batch.
     *
     * @return how many rows were checked
     */
    @Transactional
    public int runOnce(int batchSize, Instant now, Duration recheckInterval) {
        List<ProcessRow> claimed = processes.claim(PROCESS_NAME, now, batchSize);
        int checked = 0;
        for (ProcessRow row : claimed) {
            try {
                settleRow(row, now, recheckInterval);
            } catch (RuntimeException failure) {
                quarantine(row, failure, now, recheckInterval);
            }
            checked++;
        }
        return checked;
    }

    private void settleRow(ProcessRow row, Instant now, Duration recheckInterval) {
        UUID planId = planIdOf(row);
        Optional<SourcingOutcome> outcome = planner.sourcingOutcome(row.tenantId(), row.orderId());

        if (outcome.isEmpty()) {
            // Fulfillment cannot find a plan this row was enqueued for having
            // opened. Not the ordinary case DeliveryPlanTrigger already handles
            // (nothing to plan never enqueues this row at all) — a genuine
            // anomaly, so it gets its own small ladder rather than deferring to
            // a signal that will never arrive.
            if (row.attemptCount() + 1 < MAX_MISSING_PLAN_ATTEMPTS) {
                processes.settle(
                        row.orderId(),
                        PROCESS_NAME,
                        row.version(),
                        "FAILED_RETRYABLE",
                        row.checkpointJson(),
                        now.plus(recheckInterval),
                        "No delivery plan " + planId + " found for this order",
                        now);
            } else {
                processes.settle(
                        row.orderId(),
                        PROCESS_NAME,
                        row.version(),
                        "MANUAL_ACTION_REQUIRED",
                        row.checkpointJson(),
                        null,
                        "No delivery plan " + planId + " found after " + MAX_MISSING_PLAN_ATTEMPTS + " checks",
                        now);
                log.error(
                        "Order {}'s delivery plan {} could not be found; manual action required",
                        row.orderId(),
                        planId);
            }
            return;
        }

        switch (outcome.get()) {
            case SOURCED, CANCELLED ->
                processes.settle(
                        row.orderId(), PROCESS_NAME, row.version(), "COMPLETED", row.checkpointJson(), null, null, now);
            case MANUAL_ACTION_REQUIRED -> {
                processes.settle(
                        row.orderId(),
                        PROCESS_NAME,
                        row.version(),
                        "MANUAL_ACTION_REQUIRED",
                        row.checkpointJson(),
                        null,
                        "Delivery sourcing for plan " + planId + " requires manual action",
                        now);
                log.warn("Order {}'s delivery plan {} needs manual action", row.orderId(), planId);
            }
            case IN_PROGRESS ->
                processes.settle(
                        row.orderId(),
                        PROCESS_NAME,
                        row.version(),
                        "WAITING",
                        row.checkpointJson(),
                        now.plus(recheckInterval),
                        null,
                        now);
        }
    }

    /**
     * A row this poll could not check keeps its schedule rather than the run's
     * own transaction rolling back and leaving it immediately reclaimable — the
     * same reasoning {@link OrderInventoryProcess}'s own quarantine step gives.
     */
    private void quarantine(ProcessRow row, RuntimeException failure, Instant now, Duration recheckInterval) {
        log.error("The fulfillment process could not check order {}", row.orderId(), failure);
        processes.settle(
                row.orderId(),
                PROCESS_NAME,
                row.version(),
                "WAITING",
                row.checkpointJson(),
                now.plus(recheckInterval),
                failure.getClass().getSimpleName() + ": " + failure.getMessage(),
                now);
    }

    private UUID planIdOf(ProcessRow row) {
        Map<?, ?> checkpoint = objectMapper.readValue(row.checkpointJson(), Map.class);
        return UUID.fromString(String.valueOf(checkpoint.get("planId")));
    }

    private String checkpoint(UUID planId) {
        return objectMapper.writeValueAsString(Map.of("planId", planId.toString()));
    }
}
