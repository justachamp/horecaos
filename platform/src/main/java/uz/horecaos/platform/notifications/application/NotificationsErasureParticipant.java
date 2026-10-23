package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.customers.spi.CustomerErasureParticipant;
import uz.horecaos.platform.customers.spi.ErasesCustomerData;
import uz.horecaos.platform.customers.spi.ErasureCounts;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcUnsubscribeTokenStore;

/**
 * Stops anything being sent to an erased customer and forgets how she liked to be reached (ADR 0029, ADR 0020).
 *
 * <p>Her notification preferences (channels, quiet hours) are deleted. Her delivery endpoints are retired and the lookup
 * hash they copied from her contact point is replaced, because the contact point's own hash was randomised and an
 * endpoint that kept the old one would still answer a search for her number. The notification log stays: by design it
 * holds no recipient number, name or rendered body (V0026), only the template, the frozen variables and two hashes,
 * so it is evidence that a message was sent and not a record of whom it was sent to. The unsubscribe tokens minted for her
 * campaign emails (V0671) are deleted: each is a working capability, valid for 400 days, that names her account and the
 * message it was sent with, and an account that is gone has nothing left to withdraw.
 */
@Component
@ErasesCustomerData(
        name = "notifications",
        tables = {
            "notifications.notification_preferences",
            "notifications.recipient_endpoints",
            "notifications.unsubscribe_tokens"
        })
public class NotificationsErasureParticipant implements CustomerErasureParticipant {

    private final JdbcNotificationStore notifications;
    private final JdbcUnsubscribeTokenStore unsubscribeTokens;
    private final Clock clock;

    public NotificationsErasureParticipant(
            JdbcNotificationStore notifications, JdbcUnsubscribeTokenStore unsubscribeTokens, Clock clock) {
        this.notifications = notifications;
        this.unsubscribeTokens = unsubscribeTokens;
        this.clock = clock;
    }

    @Override
    @Transactional
    public ErasureCounts erase(UUID tenantId, UUID customerAccountId, ErasedContacts held) {
        return ErasureCounts.builder()
                .add(
                        "notifications.notification_preferences",
                        notifications.deletePreferencesOfCustomer(tenantId, customerAccountId))
                .add(
                        "notifications.recipient_endpoints",
                        notifications.retireEndpointsOfCustomer(tenantId, customerAccountId, clock.instant()))
                .add(
                        "notifications.unsubscribe_tokens",
                        unsubscribeTokens.deleteForCustomer(tenantId, customerAccountId))
                .build();
    }
}
