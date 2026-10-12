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
import org.springframework.beans.factory.annotation.Autowired;
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
import uz.horecaos.platform.ordering.api.OrderAwaitingApproval;
import uz.horecaos.platform.ordering.api.OrderCancelled;
import uz.horecaos.platform.ordering.api.OrderCompleted;
import uz.horecaos.platform.ordering.api.OrderConfirmed;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.api.OrderExpired;
import uz.horecaos.platform.ordering.api.OrderPaymentLapsed;
import uz.horecaos.platform.ordering.api.OrderRejected;
import uz.horecaos.platform.ordering.api.OrderingEvent;
import uz.horecaos.platform.reviews.api.FeedbackPromptPolicy;

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
 * <p>One bounded exception, added by ADR 0058 stage 2: which channel the
 * intent is created for. Unlike consent/templates/locale/recipient, the
 * channel cannot be deferred to the worker — {@code
 * NotificationEligibilityService} resolves a recipient <em>for the channel
 * the row already names</em>, so "SMS or Telegram" has to be decided before
 * the row exists, not after. {@link CustomerTelegramChannelRouter} is that
 * one extra read, and it is written to fail open: any problem answers with
 * the configured default channel rather than propagating into this
 * transaction, so the "must not fail a confirmation" rule above still holds
 * for everything this class does.
 *
 * <p>Confirmation and rejection only. {@code OrderReceived} could reasonably
 * produce a "we have your order" message and does not here:
 * {@code docs/minimum-viable-cutover.md} scopes this ADR to confirmation and
 * rejection on one channel, and a third template is a third piece of copy a pilot
 * tenant would have to write and translate in three languages before going live.
 *
 * <p><strong>The rating prompt is timed by a policy (ADR 0159, Decision 4).</strong> The
 * {@link #ORDER_COMPLETED} intent used to be created at once and expire after {@code order-expiry}.
 * For a tenant that has switched {@code feature.feedback_settings} on, {@link FeedbackPromptPolicy}
 * now says whether the prompt is sent on the channel this customer routes to, when it becomes
 * sendable (a delay, moved to the next opening of the send window) and when it stops being worth
 * sending. This class still decides nothing about the notification's class, its template or its
 * delivery, and an unactivated template still sends nothing. For a tenant that has not switched it
 * on, the intent is byte for byte what it always was.
 */
@Component
public class OrderNotificationTrigger {

    /** The semantic template keys a tenant authors wording against. */
    public static final String ORDER_CONFIRMED = "ORDER_CONFIRMED";

    public static final String ORDER_REJECTED = "ORDER_REJECTED";

    /**
     * ADR 0020's own design names "cancellation messages" alongside payment
     * and rejection as favoring correctness over aggressive multi-channel
     * fallback (docs/adr/partial/0020-notification-preferences-templates-and-delivery.md:224)
     * — a category this key was missing from entirely. Fired for every
     * {@code OrderCancelled}, confirmed or not yet: the customer paid or was
     * about to, and is owed the same "your order will not be coming" message
     * rejection already sends.
     */
    public static final String ORDER_CANCELLED = "ORDER_CANCELLED";

    /** The restaurant never answered in time (ADR 0002). Distinct wording from a rejection — see {@link OrderExpired}. */
    public static final String ORDER_EXPIRED = "ORDER_EXPIRED";

    /**
     * ADR 0170: the order was not paid within its window and ended as {@code PAYMENT_FAILED}. Its own key,
     * because «cancelled» would tell the customer someone decided, and «expired» that the restaurant never
     * looked; neither is what happened. No amount is a variable (ADR 0020).
     */
    public static final String ORDER_PAYMENT_LAPSED = "ORDER_PAYMENT_LAPSED";

    /**
     * ADR 0075: the order is finished, and this is the message a rating prompt
     * rides on. Customer-facing, like confirmation and rejection — it is the one
     * moment a customer has an opinion worth asking for, and ADR 0071 accepts a
     * review only once the order has reached COMPLETED.
     *
     * <p>A tenant that would rather not ask silences it the way it silences any
     * other template: by not activating one. An unactivated template sends
     * nothing, so this is opt-in per tenant without a second switch.
     */
    public static final String ORDER_COMPLETED = "ORDER_COMPLETED";

    /**
     * ADR 0060 §2: an order needs a restaurant decision. Operations-only —
     * unlike confirmation and rejection, nothing here is customer-facing, so
     * this key is never handed to {@link JdbcNotificationStore#createIntent},
     * only to {@link OperationsAlertFanoutService#fanOut}. On the Telegram
     * channel this is the templateKey {@code TelegramChannelAdapter} matches
     * to attach the Approve/Reject keyboard.
     */
    public static final String ORDER_AWAITING_APPROVAL = "ORDER_AWAITING_APPROVAL";

    static final String SUBJECT_TYPE = "Order";

    private static final Logger log = LoggerFactory.getLogger(OrderNotificationTrigger.class);

    private final JdbcNotificationStore notifications;
    private final OperationsAlertFanoutService operationsAlerts;
    private final OrderDirectory orders;
    private final CustomerTelegramChannelRouter channelRouter;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final NotificationChannel defaultChannel;
    private final Duration expiry;
    private final FeedbackPromptPolicy prompts;

    @Autowired
    public OrderNotificationTrigger(
            JdbcNotificationStore notifications,
            OperationsAlertFanoutService operationsAlerts,
            OrderDirectory orders,
            CustomerTelegramChannelRouter channelRouter,
            ObjectMapper objectMapper,
            Clock clock,
            @Value("${horecaos.notifications.order-channel:SMS}") String channel,
            // An open input on ADR 0020: how long an unsent confirmation is still
            // worth sending is a product decision, not one this build invents. Six
            // hours is a stated default, not a considered answer.
            @Value("${horecaos.notifications.order-expiry:PT6H}") Duration expiry,
            FeedbackPromptPolicy prompts) {
        this.notifications = notifications;
        this.operationsAlerts = operationsAlerts;
        this.orders = orders;
        this.channelRouter = channelRouter;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.defaultChannel = NotificationChannel.valueOf(channel);
        this.expiry = expiry;
        this.prompts = prompts;
    }

    /**
     * The shape that predates ADR 0159: every prompt is immediate and expires after {@code expiry}.
     * Kept for the callers and tests that build a trigger by hand and have no prompt policy to give.
     */
    public OrderNotificationTrigger(
            JdbcNotificationStore notifications,
            OperationsAlertFanoutService operationsAlerts,
            OrderDirectory orders,
            CustomerTelegramChannelRouter channelRouter,
            ObjectMapper objectMapper,
            Clock clock,
            String channel,
            Duration expiry) {
        this(
                notifications,
                operationsAlerts,
                orders,
                channelRouter,
                objectMapper,
                clock,
                channel,
                expiry,
                new Ungoverned());
    }

    /** A prompt policy that governs no tenant: today's immediate send, for a trigger built without one. */
    private static final class Ungoverned implements FeedbackPromptPolicy {

        @Override
        public boolean governs(UUID tenantId) {
            return false;
        }

        @Override
        public Decision decide(
                UUID tenantId,
                UUID brandId,
                UUID locationId,
                @Nullable String fulfillmentMode,
                String channel,
                Instant completedAt) {
            throw new IllegalStateException("An ungoverned prompt is never decided");
        }
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
            // ADR 0060 §2: the order notification the bot's Approve/Reject
            // buttons ride. Operations-only, unlike the two cases above — a
            // customer already knows they placed an order and is waiting, so
            // this never becomes a customer notification intent.
            case OrderAwaitingApproval awaiting ->
                createOperationsOnly(awaiting, ORDER_AWAITING_APPROVAL, awaiting.brandId(), awaiting.locationId());
            case OrderCompleted completed ->
                create(
                        completed,
                        ORDER_COMPLETED,
                        completed.brandId(),
                        completed.locationId(),
                        Map.of(),
                        completed.completedAt());
            case OrderCancelled cancelled ->
                create(
                        cancelled,
                        ORDER_CANCELLED,
                        cancelled.brandId(),
                        cancelled.locationId(),
                        // The same stable-code-only shape rejection already uses.
                        // cancelled.reasonCode() is a registry code (ADR 0039), never
                        // an operator's free text -- that stays behind
                        // OrderOutcomeService's own encrypted note and never reaches
                        // this event at all.
                        reasonVariables(cancelled.reasonCode()));
            case OrderExpired expired ->
                create(expired, ORDER_EXPIRED, expired.brandId(), expired.locationId(), Map.of());
            case OrderPaymentLapsed lapsed ->
                create(lapsed, ORDER_PAYMENT_LAPSED, lapsed.brandId(), lapsed.locationId(), Map.of());
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
        create(event, templateKey, brandId, locationId, triggerVariables, null);
    }

    /**
     * @param promptCompletedAt non-null only for the rating prompt: when the order was completed, which
     *                          {@link FeedbackPromptPolicy} times the prompt from
     */
    private void create(
            OrderingEvent event,
            String templateKey,
            UUID brandId,
            UUID locationId,
            Map<String, String> triggerVariables,
            @Nullable Instant promptCompletedAt) {

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

        // Best-effort and read-only: a guest order (or an order this read
        // cannot see, which does not happen for an event this transaction
        // just produced) simply routes to the default channel below, the same
        // as before this class read anything about the customer at all.
        UUID customerAccountId = orders.summary(tenantId, event.orderId())
                .map(OrderDirectory.OrderSummary::customerAccountId)
                .orElse(null);
        NotificationChannel channel = channelRouter.resolve(
                tenantId, brandId, customerAccountId, NotificationClass.TRANSACTIONAL_REQUIRED, defaultChannel);

        // Keyed on the subject rather than the event id. A replayed OrderConfirmed
        // carries a fresh event id, and keying on that would send the customer a
        // second confirmation for the same order — which is the visible failure
        // ADR 0020 names. One message per order per template per channel, for as
        // long as the row exists.
        String idempotencyKey = "%s:%s:%s:%s".formatted(templateKey, SUBJECT_TYPE, event.orderId(), channel.name());

        // ADR 0159, Decision 4. The rating prompt is the one intent whose timing a tenant governs: when
        // its policy is on, the policy says whether the prompt goes out on this channel, when it
        // becomes sendable and when it stops being worth sending. Off, none of this runs and the intent
        // below is the one this class always created.
        Instant scheduledAt = now;
        Instant expiresAt = now.plus(expiry);
        boolean sendCustomerIntent = true;
        if (promptCompletedAt != null && prompts.governs(tenantId)) {
            String mode = orders.notificationContext(tenantId, event.orderId())
                    .map(context -> context.fulfillmentMode().name())
                    .orElse(null);
            FeedbackPromptPolicy.Decision decision =
                    prompts.decide(tenantId, brandId, locationId, mode, channel.name(), promptCompletedAt);
            if (decision.send()) {
                scheduledAt = java.util.Objects.requireNonNull(decision.scheduledAt());
                expiresAt = java.util.Objects.requireNonNull(decision.expiresAt());
            } else {
                sendCustomerIntent = false;
            }
        }

        boolean created = sendCustomerIntent
                && notifications.createIntent(new NewNotification(
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
                        scheduledAt,
                        expiresAt,
                        now));

        if (sendCustomerIntent && !created) {
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
     * The operations-only counterpart of {@link #create}: the same import
     * suppression and the same fan-out call, minus the customer notification
     * intent — there is no customer-facing wording for "a restaurant is
     * deciding on your order" in this rollout, and creating one is exactly
     * the product decision {@link #onOrderingEvent}'s own {@code default}
     * case comment warns against making silently.
     */
    private void createOperationsOnly(OrderingEvent event, String templateKey, UUID brandId, UUID locationId) {
        if (ImportSuppression.suppress(ExternalEffect.CUSTOMER_NOTIFICATION, SUBJECT_TYPE, event.orderId())) {
            return;
        }

        UUID tenantId = event.tenantId().value();

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
                Map.of(),
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
