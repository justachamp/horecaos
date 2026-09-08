package uz.horecaos.platform.ordering.application;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderProcessStore;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderProcessStore.ProcessRow;

/**
 * The payment process manager (ADR 0019).
 *
 * <p>ADR 0019's own record found this the starkest of its four missing rows:
 * "{@code ORDER_PAYMENT} is driven by nothing at all". {@code
 * CheckoutProgressionStep.awaitPayment} holds an order in {@code
 * PAYMENT_AUTHORIZING} and, until now, armed nothing durable — an order whose
 * customer abandoned the provider's page, or whose {@link
 * PaymentCaptureConfirmationTrigger} somehow never fired, sat there with no
 * checkpoint and no way for an operator to find it except by scanning every
 * order in that status by hand.
 *
 * <p>Unlike {@link OrderInventoryProcess}, this manager performs no provider
 * effect of its own — there is nothing here for a retry to double, because
 * nothing here is ever retried. What it does is exactly two things, both
 * conditional writes to the row {@link #enqueue} created:
 *
 * <ol>
 *   <li>{@link #settleResolved} closes the row the instant the order's own
 *       state machine leaves {@code PAYMENT_AUTHORIZING} — captured or
 *       cancelled today, and (once ADR 0019's own open input on failure policy
 *       is answered) failed later. {@code OrderStateService} calls it from
 *       every path that can leave that status — {@code paymentCaptured}
 *       directly, and {@code applyConsequences} for every other transition
 *       method that funnels through it — so there is no fourth path this class
 *       has to defend against on its own; a row {@link #sweep} ever finds still
 *       {@code WAITING} genuinely is still authorizing.</li>
 *   <li>{@link #sweep} is the stuck list, not a safety net for a settle that
 *       has full coverage already. It never cancels or otherwise decides an
 *       order's fate — ADR 0019 leaves checkout payment timing and
 *       cancellation as an explicit open product input, and this class does
 *       not answer it by acting as if it had. An order still authorizing past
 *       a configured threshold is flagged {@code MANUAL_ACTION_REQUIRED} so it
 *       appears on {@code JdbcOrderProcessStore#stuck}, which is a fact
 *       ("nobody has resolved this"), not a decision ("cancel it").</li>
 * </ol>
 */
@Service
public class OrderPaymentProcess {

    public static final String PROCESS_NAME = "ORDER_PAYMENT";

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentProcess.class);

    private final JdbcOrderProcessStore processes;
    private final ObjectMapper objectMapper;

    public OrderPaymentProcess(JdbcOrderProcessStore processes, ObjectMapper objectMapper) {
        this.processes = processes;
        this.objectMapper = objectMapper;
    }

    /**
     * Called inside the same transaction that puts the order into {@code
     * PAYMENT_AUTHORIZING} ({@code CheckoutProgressionStep.awaitPayment}).
     */
    public void enqueue(UUID orderId, UUID tenantId, Instant now) {
        processes.enqueue(orderId, tenantId, PROCESS_NAME, enteredCheckpoint(now), now);
    }

    /**
     * Called inside the same transaction that moves the order out of {@code
     * PAYMENT_AUTHORIZING}, however it left — a capture ({@link
     * OrderStateService#paymentCaptured}) or a cancellation ({@link
     * OrderStateService}'s {@code applyConsequences}).
     *
     * <p>Idempotent by construction: {@code
     * JdbcOrderProcessStore#settleCompletedIfActive} is a no-op once the row is
     * already {@code COMPLETED}, so a capture and a racing cancellation that
     * both call this settle the row exactly once between them.
     */
    public void settleResolved(UUID orderId, Instant now) {
        boolean applied = processes.settleCompletedIfActive(orderId, PROCESS_NAME, resolvedCheckpoint(now), now);
        if (applied) {
            log.debug("Order {} left PAYMENT_AUTHORIZING; its payment process is COMPLETED", orderId);
        }
    }

    /**
     * The stuck list, one pass at a time.
     *
     * <p>Every claimed row gets a write back, never a silent skip: a row left
     * exactly as {@code claim} found it would still satisfy {@code
     * next_attempt_at <= now} on the very next tick and be claimed again
     * immediately, which is a busy loop rather than a schedule.
     *
     * @return how many rows were checked
     */
    @Transactional
    public int sweep(Instant now, Duration staleAfter, Duration recheckInterval, int batchSize) {
        List<ProcessRow> claimed = processes.claim(PROCESS_NAME, now, batchSize);
        int checked = 0;
        for (ProcessRow row : claimed) {
            try {
                sweepRow(row, now, staleAfter, recheckInterval);
            } catch (RuntimeException failure) {
                requeue(row, failure, now, recheckInterval);
            }
            checked++;
        }
        return checked;
    }

    private void sweepRow(ProcessRow row, Instant now, Duration staleAfter, Duration recheckInterval) {
        Instant enteredAt = enteredAtOf(row);

        if (!enteredAt.isAfter(now.minus(staleAfter))) {
            // A fact, not a decision. ADR 0019 leaves cancellation-on-timeout as
            // an open product input; this never cancels, only says an operator
            // should look.
            processes.settle(
                    row.orderId(),
                    PROCESS_NAME,
                    row.version(),
                    "MANUAL_ACTION_REQUIRED",
                    outcomeCheckpoint(enteredAt, "STALE"),
                    null,
                    "No payment resolution within %s of entering PAYMENT_AUTHORIZING".formatted(staleAfter),
                    now);
            log.warn(
                    "Order {} has been PAYMENT_AUTHORIZING for at least {}; manual action required",
                    row.orderId(),
                    staleAfter);
            return;
        }

        processes.settle(
                row.orderId(),
                PROCESS_NAME,
                row.version(),
                "WAITING",
                enteredCheckpoint(enteredAt),
                now.plus(recheckInterval),
                null,
                now);
    }

    /**
     * A row this sweep could not check keeps its place in the schedule rather
     * than the run's own transaction rolling back and leaving it claimable
     * again immediately — the same reasoning {@link OrderInventoryProcess}
     * gives for quarantining rather than throwing out of its own loop.
     */
    private void requeue(ProcessRow row, RuntimeException failure, Instant now, Duration recheckInterval) {
        log.error("The payment process sweep could not check order {}", row.orderId(), failure);
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

    private Instant enteredAtOf(ProcessRow row) {
        Map<?, ?> checkpoint = objectMapper.readValue(row.checkpointJson(), Map.class);
        return Instant.parse(String.valueOf(checkpoint.get("enteredAuthorizingAt")));
    }

    private String enteredCheckpoint(Instant enteredAt) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("enteredAuthorizingAt", enteredAt.toString());
        return objectMapper.writeValueAsString(checkpoint);
    }

    private String outcomeCheckpoint(Instant enteredAt, String outcome) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("enteredAuthorizingAt", enteredAt.toString());
        checkpoint.put("outcome", outcome);
        return objectMapper.writeValueAsString(checkpoint);
    }

    /** No prior checkpoint to preserve an {@code enteredAuthorizingAt} from. */
    private String resolvedCheckpoint(Instant now) {
        Map<String, Object> checkpoint = new LinkedHashMap<>();
        checkpoint.put("outcome", "RESOLVED");
        checkpoint.put("resolvedAt", now.toString());
        return objectMapper.writeValueAsString(checkpoint);
    }
}
