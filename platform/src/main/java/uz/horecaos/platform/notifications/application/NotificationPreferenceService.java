package uz.horecaos.platform.notifications.application;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.PreferenceRow;

/**
 * What a customer has asked for, per class and channel (ADR 0020).
 *
 * <p>Never a legal basis. A preference says whether the customer wants a message
 * they could lawfully be sent; consent says whether they may be sent one at all,
 * and it lives in {@code customer.consent_decisions} where it is append-only and
 * carries a policy version. Writing a preference must not create or destroy a
 * consent decision, so this service does not touch that table.
 *
 * <p>A class the customer cannot switch off is refused rather than silently
 * accepted and ignored. An interface that lets someone turn off their order
 * confirmations, and then sends them anyway, is worse than one that says no.
 */
@Service
public class NotificationPreferenceService {

    private final JdbcNotificationStore notifications;
    private final Clock clock;

    public NotificationPreferenceService(JdbcNotificationStore notifications, Clock clock) {
        this.notifications = notifications;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PreferenceRow> preferences(UUID tenantId, UUID accountId) {
        return notifications.preferences(tenantId, accountId);
    }

    /**
     * Sets one preference.
     *
     * @param brandId null for the customer's tenant-wide answer, set to override it
     *                for one brand
     */
    @Transactional
    public void set(
            UUID tenantId,
            UUID accountId,
            UUID brandId,
            NotificationClass notificationClass,
            NotificationChannel channel,
            boolean enabled) {

        if (!notificationClass.respectsPreference()) {
            throw new IllegalArgumentException(notificationClass + " is not something a customer can switch off");
        }
        notifications.upsertPreference(
                tenantId, accountId, brandId, notificationClass.name(), channel.name(), enabled, clock.instant());
    }
}
