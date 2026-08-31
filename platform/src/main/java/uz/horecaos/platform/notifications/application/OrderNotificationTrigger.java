package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.migration.api.ExternalEffect;
import uz.horecaos.platform.migration.api.ImportSuppression;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NewNotification;
import uz.horecaos.platform.ordering.api.OrderConfirmed;
import uz.horecaos.platform.ordering.api.OrderRejected;
import uz.horecaos.platform.ordering.api.OrderingEvent;

/**
 * Turning an ADR 0019 order fact into a durable notification intent (ADR 0020).
 *
 * <p>{@link TransactionPhase#BEFORE_COMMIT}, so the intent and the decision that
 * caused it commit together. The alternative — creating the intent after commit —
 * leaves a window in which an order was confirmed and nobody will ever be told,
 * which is the failure the outbox pattern exists to close and is exactly what a
 * customer would report as "I never got a confirmation".
 *
 * <p>One INSERT and nothing else. Consent, templates, locale, and the recipient
 * are all resolved later by the worker, because ADR 0019 is explicit that a
 * notification problem must not fail — or reverse — a confirmation the restaurant
 * has already made. A template lookup inside the confirming transaction would do
 * precisely that.
 *
 * <p>Confirmation and rejection only. {@code OrderReceived} could reasonably
 * produce a "we have your order" message and does not here:
 * {@code docs/minimum-viable-cutover.md} scopes this ADR to confirmation and
 * rejection on one channel, and a third template is a third piece of copy a pilot
 * tenant would have to write and translate in three languages before going live.
 */
@Component
public class OrderNotificationTrigger {

    /** The semantic template keys a tenant authors wording against. */
    public static final String ORDER_CONFIRMED = "ORDER_CONFIRMED";

    public static final String ORDER_REJECTED = "ORDER_REJECTED";

    static final String SUBJECT_TYPE = "Order";

    private static final Logger log = LoggerFactory.getLogger(OrderNotificationTrigger.class);

    private final JdbcNotificationStore notifications;
    private final OperationsAlertFanoutService operationsAlerts;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final NotificationChannel channel;
    private final Duration expiry;

    public OrderNotificationTrigger(
            JdbcNotificationStore notifications,
            OperationsAlertFanoutService operationsAlerts,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${horecaos.notifications.order-channel:SMS}") String channel,
            // An open input on ADR 0020: how long an unsent confirmation is still
            // worth sending is a product decision, not one this build invents. Six
            // hours is a stated default, not a considered answer.
            @Value("${horecaos.notifications.order-expiry:PT6H}") Duration expiry) {
        this.notifications = notifications;
        this.operationsAlerts = operationsAlerts;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.channel = NotificationChannel.valueOf(channel);
        this.expiry = expiry;
    }

    @TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
    public void onOrderingEvent(OrderingEvent event) {
        switch (event) {
            case OrderConfirmed confirmed ->
                create(confirmed, ORDER_CONFIRMED, confirmed.brandId(), confirmed.locationId(), Map.of());
            case OrderRejected rejected ->
                create(
                        rejected,
                        ORDER_REJECTED,
                        rejected.brandId(),
                        rejected.locationId(),
                        // The only thing that exists on the event and nowhere else. A
                        // reason code read from the order row later would be missing,
                        // because ordering stores the decision, not the message.
                        reasonVariables(rejected.reasonCode()));
            // Every other ordering fact is deliberately silent. Adding a case here
            // is adding a message a customer receives, which is a product decision
            // and should look like one in a diff.
            default -> {}
        }
    }

    private void create(
            OrderingEvent event,
            String templateKey,
            UUID brandId,
            UUID locationId,
            Map<String, String> triggerVariables) {

        // ADR 0024's headline suppression, and the one the ADR names first:
        // importing five years of confirmed orders would otherwise write five
        // years of confirmation intents, which the delivery worker would then send
        // to real phone numbers.
        //
        // Suppressed at intent creation and not at delivery, which is the only
        // place it can be. NotificationWorker claims and dispatches on a scheduler
        // thread; ImportContext is a ScopedValue confined to the importing thread
        // and does not follow work handed to a pool. An intent written under an
        // import and left in the table is a message with a stamped send-by time
        // and nothing on it that says not to send.
        if (ImportSuppression.suppress(ExternalEffect.CUSTOMER_NOTIFICATION, SUBJECT_TYPE, event.orderId())) {
            return;
        }

        Instant now = clock.instant();
        UUID tenantId = event.tenantId().value();

        // Keyed on the subject rather than the event id. A replayed OrderConfirmed
        // carries a fresh event id, and keying on that would send the customer a
        // second confirmation for the same order — which is the visible failure
        // ADR 0020 names. One message per order per template per channel, for as
        // long as the row exists.
        String idempotencyKey = "%s:%s:%s:%s".formatted(templateKey, SUBJECT_TYPE, event.orderId(), channel.name());

        boolean created = notifications.createIntent(new NewNotification(
                UUID.randomUUID(),
                tenantId,
                brandId,
                locationId,
                // Stated here rather than configured, because it is not a
                // tenant's choice. A confirmation is a receipt for money the
                // customer spent: it is not marketing, it does not need marketing
                // consent, and it must not become suppressible by a preference
                // toggle. What is genuinely open — the legal basis behind that
                // classification — ADR 0020 leaves with counsel.
                NotificationClass.TRANSACTIONAL_REQUIRED.name(),
                channel.name(),
                templateKey,
                SUBJECT_TYPE,
                event.orderId(),
                // Resolved by eligibility from the order, not guessed here. The
                // event does not carry it, and ADR 0032 is right that it should not
                // have to.
                null,
                event.eventId(),
                idempotencyKey,
                json(triggerVariables),
                now,
                now.plus(expiry),
                now));

        if (!created) {
            // The expected outcome of a redelivery, not a problem. Logged at debug
            // so a replay storm does not look like an incident.
            log.debug("A {} notification already exists for order {}", templateKey, event.orderId());
        }

        // ADR 0058 stage 1: the same fact, fanned out to every bound operations
        // chat subscribed to it. Independent of the customer send above — a
        // suspended customer contact must never hold back the branch's own
        // group, and vice versa — and gated by the same import suppression
        // check above it, so a backfill does not also flood every ops group.
        operationsAlerts.fanOut(
                tenantId,
                brandId,
                locationId,
                templateKey,
                templateKey,
                SUBJECT_TYPE,
                event.orderId(),
                event.eventId(),
                "%s:%s:%s".formatted(templateKey, SUBJECT_TYPE, event.orderId()),
                triggerVariables,
                expiry);
    }

    /**
     * The rejection reason, as the stable code the event carries.
     *
     * <p>Package-visible rather than private so
     * {@code TelegramOperationsMessageClassificationTests} can assert directly
     * that this is the entire variable set an ORDER_REJECTED message — customer
     * or operations — ever renders with, and that none of it is protected data.
     */
    static Map<String, String> reasonVariables(@Nullable String reasonCode) {
        Map<String, String> variables = new LinkedHashMap<>();
        variables.put("reasonCode", reasonCode == null ? "UNSPECIFIED" : reasonCode);
        return variables;
    }

    private String json(Map<String, String> variables) {
        return objectMapper.writeValueAsString(variables);
    }
}
