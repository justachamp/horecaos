package uz.horecaos.platform.integration.notifications;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.integration.api.DeadLetterRecorded;
import uz.horecaos.platform.notifications.api.OperationsAlertPort;
import uz.horecaos.platform.ordering.api.OrderDirectory;

/**
 * Integration's operations Telegram trigger (ADR 0058): a dead-letter
 * arrival (ADR 0006).
 *
 * <p>Lives in {@code integration}, not beside {@code OrderNotificationTrigger}
 * in {@code notifications.application} — the placement every other trigger
 * in this build uses. {@code integration} already depends on {@code
 * notifications} (the Telegram/Camel adapter layer implements {@code
 * NotificationTransport} and {@code OperationsSubscriptionDirectory} from
 * inside this module), so a listener in {@code notifications} importing
 * {@link DeadLetterRecorded} from {@code integration.api} would close a
 * cycle — {@code ModularArchitectureTests.verifiesModuleBoundaries} caught
 * exactly that during this build. Calling {@link OperationsAlertPort} from
 * here instead is the one-way edge that already exists, just used for a new
 * reason.
 *
 * <p>Plain {@link EventListener}, not {@code TransactionalEventListener}:
 * {@link DeadLetterRecorded} is published from code that is deliberately
 * not inside a business transaction — {@code OutboxRelay.publish} is a
 * background sweep over already-committed rows, and {@code
 * InboxExecutor}'s three dead-letter sites are terminal writes on a failure
 * path — so there is no commit for a transactional listener to defer to;
 * one would silently drop this event.
 */
@Component
public class DeadLetterOperationsAlertTrigger {

    /** The semantic template key a tenant authors this alert's wording against. */
    public static final String DEAD_LETTER_RECORDED = "DEAD_LETTER_RECORDED";

    static final String SUBJECT_TYPE = "DeadLetter";

    private static final Logger log = LoggerFactory.getLogger(DeadLetterOperationsAlertTrigger.class);

    private final OperationsAlertPort operationsAlerts;
    private final OrderDirectory orders;
    private final Duration expiry;

    public DeadLetterOperationsAlertTrigger(
            OperationsAlertPort operationsAlerts,
            OrderDirectory orders,
            // docs/runbooks/dead-letter-decision.md's queue is not a short
            // wait; generous rather than the payment/inventory alerts'
            // short defaults.
            @Value("${horecaos.notifications.telegram.dead-letter-alert-expiry:P3D}") Duration expiry) {
        this.operationsAlerts = operationsAlerts;
        this.orders = orders;
        this.expiry = expiry;
    }

    @EventListener
    public void onDeadLetterRecorded(DeadLetterRecorded event) {
        // Resolved only for the aggregate types this build knows how to
        // route: a dead letter can be about anything on any topic, and
        // DeadLetterRecorded's own Javadoc is explicit that there is no
        // uniform brand/location lookup for one. "Order" is the common
        // case (POS/webhook dead letters tied to a real order) and the
        // only one wired; every other aggregate type logs and is silently
        // not fanned out — a named gap, not a missed one.
        if (!"Order".equals(event.aggregateType())) {
            log.debug(
                    "Dead letter {} on aggregate type {} has no brand/location resolver; no operations alert raised.",
                    event.eventId(),
                    event.aggregateType());
            return;
        }

        orders.summary(event.tenantId(), event.aggregateId())
                .ifPresentOrElse(
                        order -> operationsAlerts.fanOut(
                                event.tenantId(),
                                order.brandId(),
                                order.locationId(),
                                DEAD_LETTER_RECORDED,
                                DEAD_LETTER_RECORDED,
                                SUBJECT_TYPE,
                                event.aggregateId(),
                                event.eventId(),
                                // Keyed on the dead-lettered record's own event id
                                // (DeadLetterRecorded's Javadoc: that field carries
                                // the original id, not a fresh one): a row
                                // dead-letters at most once, so this is a true
                                // per-occurrence key.
                                "%s:%s:%s".formatted(DEAD_LETTER_RECORDED, SUBJECT_TYPE, event.eventId()),
                                variables(event.source(), event.reasonCode()),
                                expiry),
                        () -> log.debug(
                                "Dead letter {} named order {} which no longer resolves; no operations alert raised.",
                                event.eventId(),
                                event.aggregateId()));
    }

    /**
     * The entire variable set this alert ever renders with — a source and a
     * reason code, nothing about the payload that dead-lettered.
     * Package-visible so {@code TelegramOperationsMessageClassificationTests}
     * (in {@code notifications}) asserts against a call fixed here directly,
     * since this class itself sits outside that module.
     */
    static Map<String, String> variables(String source, String reasonCode) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("source", source);
        variables.put("reasonCode", reasonCode);
        return variables;
    }
}
