package uz.horecaos.platform.notifications.application;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.notifications.api.CustomerAlertPort;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NewNotification;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.ordering.api.OrderDirectory.OrderSummary;

/**
 * {@link CustomerAlertPort}'s implementation — {@code
 * OperationsAlertFanoutService}'s customer-audience counterpart.
 */
@Component
public class CustomerAlertFanoutService implements CustomerAlertPort {

    private static final Logger log = LoggerFactory.getLogger(CustomerAlertFanoutService.class);

    private final JdbcNotificationStore notifications;
    private final OrderDirectory orders;
    private final CustomerTelegramChannelRouter channelRouter;
    private final ObjectMapper objectMapper;
    private final NotificationChannel fallbackChannel;

    public CustomerAlertFanoutService(
            JdbcNotificationStore notifications,
            OrderDirectory orders,
            CustomerTelegramChannelRouter channelRouter,
            ObjectMapper objectMapper,
            // Same configuration key OrderNotificationTrigger reads, so the two
            // triggers agree on what "the existing channel" means without a
            // caller having to pass it.
            @Value("${horecaos.notifications.order-channel:SMS}") String fallbackChannel) {
        this.notifications = notifications;
        this.orders = orders;
        this.channelRouter = channelRouter;
        this.objectMapper = objectMapper;
        this.fallbackChannel = NotificationChannel.valueOf(fallbackChannel);
    }

    @Override
    @Transactional
    public void notifyCustomer(
            UUID tenantId,
            UUID orderId,
            String templateKey,
            String subjectType,
            @Nullable UUID triggerEventId,
            String idempotencyKey,
            Map<String, String> variables,
            Instant now,
            Duration expiry) {

        Optional<OrderSummary> order = orders.summary(tenantId, orderId);
        if (order.isEmpty()) {
            // Not this tenant's order — the same data fault
            // NotificationEligibilityService treats as an IllegalStateException
            // rather than a suppression, because nothing was decided about a
            // customer; unlike that path, this one has not written a row yet,
            // so there is nothing to leave in an inconsistent state either.
            log.warn("notifyCustomer called for an order {} this tenant does not own", orderId);
            return;
        }
        UUID customerAccountId = order.get().customerAccountId();
        if (customerAccountId == null) {
            // A guest order. Not an error: there is no account to notify, the
            // same answer NotificationEligibilityService gives as
            // NO_RECIPIENT_ACCOUNT for an intent that did reach the table.
            return;
        }
        UUID brandId = order.get().brandId();
        UUID locationId = order.get().locationId();

        NotificationChannel channel = channelRouter.resolve(
                tenantId, brandId, customerAccountId, NotificationClass.TRANSACTIONAL_REQUIRED, fallbackChannel);

        // subjectId is orderId, not a fiscal document id or anything else the
        // caller might think of as "the subject": NotificationEligibilityService.evaluate
        // resolves the recipient and every rendered amount/currency variable
        // from OrderDirectory.summary(tenantId, row.subjectId()) unconditionally,
        // so any other value here fails eligibility with "names an order this
        // tenant does not own" the moment the worker picks the row up.
        //
        // The 17-arg canonical constructor, not the 16-arg "no endpoint
        // known yet" convenience one: that overload declares triggerEventId
        // non-null (every existing caller always has one), and this port's
        // own contract allows a caller with none, the same allowance
        // OperationsAlertPort's fanOut makes for the same reason.
        boolean created = notifications.createIntent(new NewNotification(
                UUID.randomUUID(),
                tenantId,
                brandId,
                locationId,
                NotificationClass.TRANSACTIONAL_REQUIRED.name(),
                channel.name(),
                templateKey,
                subjectType,
                orderId,
                null,
                triggerEventId,
                idempotencyKey,
                objectMapper.writeValueAsString(variables),
                now,
                now.plus(expiry),
                now,
                null));

        if (!created) {
            log.debug("A {} customer notification already exists for order {}", templateKey, orderId);
        }
    }
}
