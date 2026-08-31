package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.notifications.api.OperationsSubscriptionDirectory;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NewNotification;

/**
 * Fans one operations event out to every subscribed chat (ADR 0058).
 *
 * <p>Where {@code OrderNotificationTrigger} creates exactly one intent per
 * order, an operations event legitimately creates several: ADR 0058 names
 * several groups wanting the same alert as ordinary, not an edge case. Each
 * bound chat gets its own notification row, its own endpoint, and — because the
 * idempotency key includes the binding id — its own independent delivery
 * lifecycle; one chat being suspended never blocks another's copy of the same
 * alert.
 *
 * <p>Channel-agnostic in shape (it asks {@link OperationsSubscriptionDirectory}
 * "who wants this", not "which Telegram chats want this"), even though
 * {@link NotificationChannel#TELEGRAM} is the only operations channel this slice
 * wires. A second one would extend this class's channel choice, not its
 * structure.
 */
@Component
public class OperationsAlertFanoutService {

    private static final Logger log = LoggerFactory.getLogger(OperationsAlertFanoutService.class);

    private final OperationsSubscriptionDirectory subscriptions;
    private final JdbcNotificationStore notifications;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public OperationsAlertFanoutService(
            OperationsSubscriptionDirectory subscriptions,
            JdbcNotificationStore notifications,
            ObjectMapper objectMapper,
            Clock clock) {
        this.subscriptions = subscriptions;
        this.notifications = notifications;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * @param idempotencyKeyBase unique per (subject, event class); this method
     *                           appends the binding id so a re-scan or a replayed
     *                           trigger fans out to the same set exactly once
     *                           per chat rather than zero or many times
     * @param triggerEventId null for an alert with no originating Kafka event,
     *                       e.g. a scheduled sweep
     */
    public void fanOut(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            String eventClass,
            String templateKey,
            String subjectType,
            UUID subjectId,
            @Nullable UUID triggerEventId,
            String idempotencyKeyBase,
            Map<String, String> triggerVariables,
            Duration expiry) {

        List<UUID> bindingIds = subscriptions.subscribedBindings(tenantId, brandId, locationId, eventClass);
        if (bindingIds.isEmpty()) {
            return;
        }

        Instant now = clock.instant();
        String variablesJson = objectMapper.writeValueAsString(triggerVariables);

        for (UUID bindingId : bindingIds) {
            UUID endpointId = notifications.ensureProviderBindingEndpoint(tenantId, bindingId, now);
            boolean created = notifications.createIntent(new NewNotification(
                    UUID.randomUUID(),
                    tenantId,
                    brandId,
                    locationId,
                    NotificationClass.OPERATIONS_ALERT.name(),
                    NotificationChannel.TELEGRAM.name(),
                    templateKey,
                    subjectType,
                    subjectId,
                    null,
                    triggerEventId,
                    idempotencyKeyBase + ":" + bindingId,
                    variablesJson,
                    now,
                    now.plus(expiry),
                    now,
                    endpointId));
            if (!created) {
                log.debug(
                        "An operations alert already exists for {}/{} on binding {}",
                        subjectType,
                        subjectId,
                        bindingId);
            }
        }
    }
}
