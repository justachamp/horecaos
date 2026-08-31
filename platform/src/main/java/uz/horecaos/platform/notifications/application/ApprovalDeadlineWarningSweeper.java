package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.api.OrderDirectory.ApprovalDeadlineWarning;

/**
 * The flagship ADR 0058 operations notification: an order approaching its
 * approval deadline, at the board's own severity threshold
 * (docs/operations-spec/orders.md §2.6: {@code AWAITING_APPROVAL with < 2 min to
 * deadline}).
 *
 * <p>Where {@code approval_deadline_at} is armed and fired is
 * {@code ordering.order_timers} and {@code OrderProcessWorker.fireDueTimers},
 * which claims a due timer exactly once and transitions the order. This is
 * deliberately not that: a warning is not a state transition, it does not need
 * a claim, and a re-scan before the message goes out is safe to repeat — the
 * intent's own idempotency key (one warning per order, ever) is what a repeat
 * scan collapses onto, the same way {@code OutboxRelay} does not need its
 * claim to also be its dedup. Reusing the timer table for a second timer type
 * would have meant teaching {@code fireDueTimers} to branch on why a timer
 * fired, inside ordering's own state machine, for a notification concern; a
 * small, separate, read-only sweeper here is the smaller and more honest
 * change.
 */
@Component
@ConditionalOnProperty(
        name = "horecaos.notifications.telegram.approval-warning.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ApprovalDeadlineWarningSweeper {

    /** The semantic template key a tenant authors this warning's wording against. */
    public static final String TEMPLATE_KEY = "ORDER_APPROVAL_DEADLINE_WARNING";

    static final String SUBJECT_TYPE = "Order";

    private static final Logger log = LoggerFactory.getLogger(ApprovalDeadlineWarningSweeper.class);

    private final OrderDirectory orders;
    private final OperationsAlertFanoutService operationsAlerts;
    private final Clock clock;
    private final Duration severityThreshold;
    private final int batchSize;

    public ApprovalDeadlineWarningSweeper(
            OrderDirectory orders,
            OperationsAlertFanoutService operationsAlerts,
            Clock clock,
            // The board's own threshold (orders.md §2.6, severity level 2). Not
            // reimplemented as a separate constant: Внимание's queue and this
            // warning have to agree on when "about to breach" starts, or an
            // operator sees the tab turn red with no message in the chat yet.
            @Value("${horecaos.notifications.telegram.approval-warning.threshold:PT2M}") Duration severityThreshold,
            @Value("${horecaos.notifications.telegram.approval-warning.batch-size:100}") int batchSize) {
        this.orders = orders;
        this.operationsAlerts = operationsAlerts;
        this.clock = clock;
        this.severityThreshold = severityThreshold;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${horecaos.notifications.telegram.approval-warning.initial-delay:PT10S}",
            fixedDelayString = "${horecaos.notifications.telegram.approval-warning.interval:PT15S}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("The approval-deadline warning sweep could not run", failure);
        }
    }

    /** @return how many orders were fanned out this pass, for a deterministic test */
    public int runOnce() {
        Instant now = clock.instant();
        List<ApprovalDeadlineWarning> due = orders.ordersNearingApprovalDeadline(now, severityThreshold, batchSize);

        for (ApprovalDeadlineWarning warning : due) {
            try {
                warn(warning, now);
            } catch (RuntimeException failure) {
                // One order's failure must not stop the sweep from warning about
                // every other branch's orders in this batch.
                log.error("Could not fan out the approval-deadline warning for order {}", warning.orderId(), failure);
            }
        }
        return due.size();
    }

    private void warn(ApprovalDeadlineWarning warning, Instant now) {
        Map<String, String> variables = variablesFor(warning);

        operationsAlerts.fanOut(
                warning.tenantId(),
                warning.brandId(),
                warning.locationId(),
                TEMPLATE_KEY,
                TEMPLATE_KEY,
                SUBJECT_TYPE,
                warning.orderId(),
                null,
                "%s:%s:%s".formatted(TEMPLATE_KEY, SUBJECT_TYPE, warning.orderId()),
                variables,
                // Worth sending only until the deadline itself passes; past that
                // the order has already moved on (approved or expired) and a late
                // warning is noise, exactly as OrderNotificationTrigger's own
                // expiry reasoning goes for a stale confirmation.
                Duration.between(now, warning.approvalDeadlineAt()));
    }

    /**
     * The entire variable set this warning ever renders with — an order number
     * and a timestamp, nothing about the customer. Package-visible so
     * {@code TelegramOperationsMessageClassificationTests} asserts that directly
     * rather than trusting a comment.
     */
    static Map<String, String> variablesFor(ApprovalDeadlineWarning warning) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("orderNumber", warning.publicOrderNumber() == null ? "" : warning.publicOrderNumber());
        variables.put("approvalDeadlineAt", warning.approvalDeadlineAt().toString());
        return variables;
    }
}
