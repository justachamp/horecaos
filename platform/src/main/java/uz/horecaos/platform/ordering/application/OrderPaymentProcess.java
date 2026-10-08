package uz.horecaos.platform.ordering.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.ordering.domain.PaymentWindowPolicy;
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
 *   <li>{@link #sweep} is the stuck list, and, where a tenant published {@code CANCEL} (ADR 0170), the
 *       decision to end what has not been paid. By default it never decides an order's fate: an order
 *       still authorizing past the window is flagged {@code MANUAL_ACTION_REQUIRED} so it appears on
 *       {@code JdbcOrderProcessStore#stuck}, which is a fact ("nobody has resolved this"), not a decision
 *       ("cancel it"). A published {@code CANCEL} ends the order as {@code PAYMENT_FAILED} only when the
 *       caller supplied a {@link Lapse} (the deployment's {@code cancel.mode} is {@code ENFORCE}), the order
 *       is still authorizing, and no payment attempt is {@code UNCERTAIN}; in {@code SHADOW} it counts what
 *       it would have ended and flags as it always did.</li>
 * </ol>
 */
@Service
public class OrderPaymentProcess {

    public static final String PROCESS_NAME = "ORDER_PAYMENT";

    private static final Logger log = LoggerFactory.getLogger(OrderPaymentProcess.class);

    private final JdbcOrderProcessStore processes;
    private final ObjectMapper objectMapper;
    private final @Nullable PaymentWindowAuthoringService windows;
    private final @Nullable MeterRegistry meters;

    /** ADR 0170: ends an unpaid order whose window elapsed; absent, a published {@code CANCEL} is only counted. */
    @FunctionalInterface
    public interface Lapse {

        /**
         * @return true when this call ended the order; false when it had already left {@code
         *         PAYMENT_AUTHORIZING} (a capture won the race) and nothing was applied
         */
        boolean apply(UUID tenantId, UUID orderId, int windowMinutes, int policyVersion);
    }

    @Autowired
    public OrderPaymentProcess(
            JdbcOrderProcessStore processes,
            ObjectMapper objectMapper,
            PaymentWindowAuthoringService windows,
            MeterRegistry meters) {
        this.processes = processes;
        this.objectMapper = objectMapper;
        this.windows = windows;
        this.meters = meters;
    }

    /** The sweep with a window store and no meter: every unit test that predates the counters. */
    public OrderPaymentProcess(
            JdbcOrderProcessStore processes, ObjectMapper objectMapper, PaymentWindowAuthoringService windows) {
        this.processes = processes;
        this.objectMapper = objectMapper;
        this.windows = windows;
        this.meters = null;
    }

    /**
     * A sweep that knows only the deploy property: every order is waited for as long as {@code
     * staleAfter} says. For a caller with no policy store, which is every unit test that predates
     * ADR 0142's {@code ordering.payment_window}.
     */
    public OrderPaymentProcess(JdbcOrderProcessStore processes, ObjectMapper objectMapper) {
        this.processes = processes;
        this.objectMapper = objectMapper;
        this.windows = null;
        this.meters = null;
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
     * <p>{@code staleAfter} is the <em>fallback</em> window -- the deploy property -- and applies to an
     * order whose location has no {@code ordering.payment_window} document in its chain. Where one is
     * published its window replaces it for that order (ADR 0142 Decision 7). What is done at the end of
     * the window is the document's {@code action}: {@code FLAG_ONLY} (and the fallback) flags the order,
     * {@code CANCEL} ends it as {@code PAYMENT_FAILED} when the caller supplied a {@link Lapse}.
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
        return sweep(now, staleAfter, recheckInterval, batchSize, null);
    }

    /**
     * The sweep, able to end what a published {@code CANCEL} window says to end (ADR 0170).
     *
     * @param lapse how an order is ended; null in {@code SHADOW}, where a {@code CANCEL} window is counted
     *              ({@code would_lapse}) and the order flagged exactly as {@code FLAG_ONLY} would
     */
    @Transactional
    public int sweep(Instant now, Duration staleAfter, Duration recheckInterval, int batchSize, @Nullable Lapse lapse) {
        List<ProcessRow> claimed = processes.claim(PROCESS_NAME, now, batchSize);
        int checked = 0;
        for (ProcessRow row : claimed) {
            try {
                sweepRow(row, now, staleAfter, recheckInterval, lapse);
            } catch (RuntimeException failure) {
                requeue(row, failure, now, recheckInterval);
            }
            checked++;
        }
        return checked;
    }

    private void sweepRow(
            ProcessRow row, Instant now, Duration fallbackStaleAfter, Duration recheckInterval, @Nullable Lapse lapse) {
        Instant enteredAt = enteredAtOf(row);
        Duration staleAfter = fallbackStaleAfter;
        PaymentWindowAuthoringService.Window window = null;
        if (windows != null) {
            window = windows.windowFor(row.tenantId(), row.orderId(), fallbackStaleAfter);
            staleAfter = window.staleAfter();
        }

        if (!enteredAt.isAfter(now.minus(staleAfter))) {
            if (window != null && window.action() == PaymentWindowPolicy.Action.CANCEL) {
                if (lapse == null) {
                    // SHADOW: say what would have ended and end nothing. The order is flagged as FLAG_ONLY
                    // does, so the fortnight of counts costs the stuck list nothing it did not already hold.
                    count("would_lapse");
                } else if (processes.hasUncertainPaymentAttempt(row.tenantId(), row.orderId())) {
                    // ADR 0007: a question about money is owned by its resolver. The order stays flagged
                    // as a problem and comes back on the next tick, when the answer may have arrived.
                    count("skipped_uncertain");
                    processes.settle(
                            row.orderId(),
                            PROCESS_NAME,
                            row.version(),
                            "FAILED_RETRYABLE",
                            outcomeCheckpoint(enteredAt, "STALE_UNCERTAIN"),
                            now.plus(recheckInterval),
                            "A payment attempt is UNCERTAIN; the order is not ended over an unanswered question",
                            now);
                    log.warn(
                            "Order {} is past its CANCEL window but a payment attempt is UNCERTAIN; "
                                    + "it stays flagged and is rechecked",
                            row.orderId());
                    return;
                } else {
                    boolean applied = lapse.apply(
                            row.tenantId(), row.orderId(), (int) staleAfter.toMinutes(), window.policyVersion());
                    if (applied) {
                        count("lapsed");
                    }
                    // Applied: the order's own transition settled this row COMPLETED. Not applied: a
                    // capture won the race and settled it. Either way there is nothing left to write.
                    return;
                }
            }
            // A fact, not a decision. This never cancels on its own authority; a CANCEL window is the
            // tenant's published decision and is applied above, under its guards.
            count("flagged");
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

    private void count(String outcome) {
        if (meters != null) {
            meters.counter("ordering.payment_window", "outcome", outcome).increment();
        }
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
