package uz.horecaos.platform.ordering.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.inventory.api.InventoryReservationPort;
import uz.horecaos.platform.inventory.api.StockDisposal;
import uz.horecaos.platform.inventory.api.StockDisposalCommand;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderProcessStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderProcessStore.ProcessRow;

/**
 * The inventory process manager (ADR 0019).
 *
 * <p>Commits the hold when an order is confirmed and releases it when the order
 * is rejected, expires, or is cancelled. Durable rather than inline, for the
 * reason ADR 0019 gives for splitting process managers out at all: a failure to
 * commit stock must not fail — or reverse — a confirmation the customer has
 * already been told about. The instruction is written in the same transaction as
 * the state change, so the two can never disagree, and carried out afterwards
 * with its own retries.
 *
 * <p>Rebuilding this process from history cannot double an effect. Both
 * operations are conditional updates on a {@code HELD} reservation, so a commit
 * replayed after a release does nothing rather than reviving stock, and a
 * release replayed after a commit does not un-sell it.
 */
@Service
public class OrderInventoryProcess {

    public static final String PROCESS_NAME = "ORDER_INVENTORY";

    private static final Logger log = LoggerFactory.getLogger(OrderInventoryProcess.class);
    private static final String COMMIT = "COMMIT";
    private static final String RELEASE = "RELEASE";
    private static final String DISPOSE = "DISPOSE";
    private static final int MAX_ATTEMPTS = 8;
    private static final Duration RETRY_BACKOFF = Duration.ofSeconds(30);

    private final JdbcOrderProcessStore processes;
    private final InventoryReservationPort inventory;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OrderInventoryProcess(
            JdbcOrderProcessStore processes,
            InventoryReservationPort inventory,
            ObjectMapper objectMapper,
            Clock clock) {
        this.processes = processes;
        this.inventory = inventory;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /** Called inside the transaction that confirms an order. */
    public void enqueueCommit(UUID orderId, UUID tenantId, UUID quoteId, Instant now) {
        processes.enqueue(orderId, tenantId, PROCESS_NAME, checkpoint(COMMIT, quoteId), now);
    }

    /** Called inside the transaction that rejects, expires, or cancels an order. */
    public void enqueueRelease(UUID orderId, UUID tenantId, UUID quoteId, Instant now) {
        processes.enqueue(orderId, tenantId, PROCESS_NAME, checkpoint(RELEASE, quoteId), now);
    }

    /**
     * What an order that has been sold, or amended, gives back when it ends (ADR 0169, decision 11).
     *
     * <p>A cancellation after the sale used to enqueue a release, which only acts on a {@code HELD} hold and so
     * left every committed unit sold. The instruction now carries the units the order still holds and the
     * declared disposition; inventory reads the reservations itself to tell a hold from a sale.
     *
     * @param quoteIds every quote that owns a reservation of this order: each revision's, original first
     * @param disposition {@code RETURN_TO_STOCK}, {@code WRITE_OFF} or {@code NO_EFFECT}
     * @param reasonCategory the cancellation reason's system category, for the ledger's reason code
     */
    public record DisposeInstruction(
            UUID brandId,
            UUID locationId,
            List<UUID> quoteIds,
            String disposition,
            String reasonCategory,
            List<Line> lines) {

        /** The whole units of one variant the order still holds. */
        public record Line(UUID variantId, int units) {}
    }

    /** Called inside the transaction that ends an order whose stock may have been sold (ADR 0169). */
    public void enqueueDispose(UUID orderId, UUID tenantId, UUID quoteId, DisposeInstruction instruction, Instant now) {
        processes.enqueue(
                orderId,
                tenantId,
                PROCESS_NAME,
                objectMapper.writeValueAsString(
                        Map.of("action", DISPOSE, "quoteId", quoteId.toString(), "dispose", instruction)),
                now);
    }

    /**
     * Runs the outstanding work.
     *
     * <p>One transaction for the batch. The batch is small and every operation is
     * a single conditional UPDATE, so the blast radius of a rollback is a handful
     * of rows that stay claimable; splitting into a transaction per row would
     * release the {@code SKIP LOCKED} lock between claim and execution and let a
     * second worker pick the same row up.
     *
     * <p>One row's failure is that row's. Before, a checkpoint this version could
     * not read — the realistic source is a rolling deploy, where a new node writes
     * an action an old node has never heard of — threw out of the loop, rolled the
     * whole transaction back including the claim, and left the same batch to be
     * claimed and to throw again on every tick for ever. The process manager then
     * stops for every tenant, {@code attempt_count} never advances so
     * {@link #MAX_ATTEMPTS} never bites, and nothing anywhere says so: it looks
     * exactly like a process that is merely slow. A row that cannot be run is now
     * quarantined instead, and the rest of the batch settles.
     *
     * <p>A failure that has poisoned the transaction itself — a broken database
     * rather than a broken row — still fails the quarantining write and rolls the
     * batch back, which is the right answer for that case: nothing was applied and
     * everything stays claimable.
     *
     * @return how many process rows were settled
     */
    @Transactional
    public int runOnce(int batchSize) {
        Instant now = clock.instant();
        List<ProcessRow> claimed = processes.claim(PROCESS_NAME, now, batchSize);
        int settled = 0;

        for (ProcessRow row : claimed) {
            try {
                settleRow(row, now);
            } catch (RuntimeException failure) {
                quarantine(row, failure, now);
            }
            settled++;
        }
        return settled;
    }

    private void settleRow(ProcessRow row, Instant now) {
        Map<?, ?> checkpoint = objectMapper.readValue(row.checkpointJson(), Map.class);
        String action = String.valueOf(checkpoint.get("action"));
        UUID quoteId = UUID.fromString(String.valueOf(checkpoint.get("quoteId")));

        boolean applied =
                switch (action) {
                    case COMMIT -> inventory.commit(row.tenantId(), quoteId);
                    case RELEASE -> inventory.release(row.tenantId(), quoteId);
                    case DISPOSE -> dispose(row, checkpoint);
                    default -> throw new IllegalStateException("Unknown inventory process action " + action);
                };

        if (applied) {
            processes.settle(
                    row.orderId(),
                    PROCESS_NAME,
                    row.version(),
                    "COMPLETED",
                    result(action, quoteId, "APPLIED"),
                    null,
                    null,
                    now);
            return;
        }

        // The reservation was not in a state this action could change. That is
        // the normal end state for a replay — already committed, already
        // released, or swept as expired — and for those the process is done,
        // not stuck. Only a commit against a hold that has lapsed is worth an
        // operator's attention, because stock the customer was promised was
        // never actually taken.
        boolean needsAttention = COMMIT.equals(action);
        if (needsAttention && row.attemptCount() + 1 < MAX_ATTEMPTS) {
            processes.settle(
                    row.orderId(),
                    PROCESS_NAME,
                    row.version(),
                    "FAILED_RETRYABLE",
                    result(action, quoteId, "NO_LIVE_HOLD"),
                    now.plus(RETRY_BACKOFF),
                    "No held reservation to commit",
                    now);
            log.warn("Order {} has no live reservation to commit; retrying", row.orderId());
        } else if (needsAttention) {
            processes.settle(
                    row.orderId(),
                    PROCESS_NAME,
                    row.version(),
                    "MANUAL_ACTION_REQUIRED",
                    result(action, quoteId, "NO_LIVE_HOLD"),
                    null,
                    "The reservation could not be committed after %d attempts".formatted(MAX_ATTEMPTS),
                    now);
            log.error("Order {} could not commit its reservation; manual action required", row.orderId());
        } else {
            processes.settle(
                    row.orderId(),
                    PROCESS_NAME,
                    row.version(),
                    "COMPLETED",
                    result(action, quoteId, "NOTHING_TO_RELEASE"),
                    null,
                    null,
                    now);
        }
    }

    /**
     * Gives the order's stock back by the declared disposition (ADR 0169). Idempotent on the order: a retry
     * after a failure past this point finds the first answer and moves nothing.
     *
     * @return true: a disposal that cannot be carried out throws, and the row is retried and then flagged
     */
    private boolean dispose(ProcessRow row, Map<?, ?> checkpoint) {
        DisposeInstruction instruction = objectMapper.convertValue(checkpoint.get("dispose"), DisposeInstruction.class);
        StockDisposal disposal = inventory.dispose(new StockDisposalCommand(
                row.tenantId(),
                instruction.brandId(),
                instruction.locationId(),
                row.orderId(),
                instruction.quoteIds(),
                instruction.lines().stream()
                        .map(line -> new StockDisposalCommand.Line(line.variantId(), line.units(), 0))
                        .toList(),
                StockDisposalCommand.Disposition.valueOf(instruction.disposition()),
                instruction.reasonCategory(),
                "SYSTEM_JOB",
                null,
                "cancel:" + row.orderId()));
        List<UUID> movements = disposal.movementIds();
        if (!movements.isEmpty()) {
            processes.recordOutcomeMovement(row.tenantId(), row.orderId(), movements.get(0));
        }
        return true;
    }

    /**
     * Takes a row this version cannot run out of the batch's way.
     *
     * <p>The checkpoint is written back unchanged rather than replaced with an
     * outcome, because nothing was done and this class's rule is that the
     * checkpoint records the effect. The instruction has to survive so that the
     * node which does understand it can still carry it out.
     */
    private void quarantine(ProcessRow row, RuntimeException failure, Instant now) {
        String detail = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        boolean retry = row.attemptCount() + 1 < MAX_ATTEMPTS;

        processes.settle(
                row.orderId(),
                PROCESS_NAME,
                row.version(),
                retry ? "FAILED_RETRYABLE" : "MANUAL_ACTION_REQUIRED",
                row.checkpointJson(),
                retry ? now.plus(RETRY_BACKOFF) : null,
                detail,
                now);

        if (retry) {
            log.warn("The inventory process could not run order {}; retrying", row.orderId(), failure);
        } else {
            log.error(
                    "The inventory process could not run order {} after {} attempts; " + "manual action required",
                    row.orderId(),
                    MAX_ATTEMPTS,
                    failure);
        }
    }

    private String checkpoint(String action, UUID quoteId) {
        return objectMapper.writeValueAsString(Map.of("action", action, "quoteId", quoteId.toString()));
    }

    /**
     * The checkpoint after the fact, recording the effect rather than the intent.
     *
     * <p>ADR 0019: rebuilding a process from history must not repeat a provider
     * effect. Recording what was actually done is what makes that checkable.
     */
    private String result(String action, UUID quoteId, String outcome) {
        return objectMapper.writeValueAsString(Map.of(
                "action", action,
                "quoteId", quoteId.toString(),
                "outcome", outcome));
    }
}
