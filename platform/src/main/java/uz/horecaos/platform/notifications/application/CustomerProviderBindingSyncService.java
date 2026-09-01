package uz.horecaos.platform.notifications.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.notifications.api.CustomerProviderBindingSync;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.EndpointRow;

/**
 * {@link CustomerProviderBindingSync}'s implementation (ADR 0058 stage 2).
 */
@Service
public class CustomerProviderBindingSyncService implements CustomerProviderBindingSync {

    private static final Logger log = LoggerFactory.getLogger(CustomerProviderBindingSyncService.class);

    /**
     * Every class a preference toggle can actually refuse — {@code
     * NotificationPreferenceService#set} throws for any other, so a 403 sync
     * that tried them all would throw on its very first, unrelated, class.
     */
    private static final List<NotificationClass> PREFERENCE_RESPECTING_CLASSES =
            List.of(NotificationClass.TRANSACTIONAL_OPTIONAL, NotificationClass.MARKETING);

    private final JdbcNotificationStore notifications;
    private final NotificationPreferenceService preferences;

    public CustomerProviderBindingSyncService(
            JdbcNotificationStore notifications, NotificationPreferenceService preferences) {
        this.notifications = notifications;
        this.preferences = preferences;
    }

    @Override
    @Transactional
    public void onCustomerBindingLinked(UUID tenantId, UUID providerBindingId, UUID customerAccountId, Instant now) {
        notifications.ensureCustomerProviderBindingEndpoint(tenantId, providerBindingId, customerAccountId, now);
    }

    @Override
    @Transactional
    public void onCustomerBindingImported(
            UUID tenantId, UUID providerBindingId, UUID customerAccountId, boolean subscribed, Instant now) {
        notifications.ensureCustomerProviderBindingEndpoint(tenantId, providerBindingId, customerAccountId, now);
        for (NotificationClass notificationClass : PREFERENCE_RESPECTING_CLASSES) {
            preferences.set(
                    tenantId, customerAccountId, null, notificationClass, NotificationChannel.TELEGRAM, subscribed);
        }
        log.info(
                "Synced TELEGRAM preference {} for customer {} in tenant {} from an import binding {}",
                subscribed ? "on" : "off",
                customerAccountId,
                tenantId,
                providerBindingId);
    }

    @Override
    @Transactional
    public void onProviderBindingRetired(UUID tenantId, UUID providerBindingId, String reason, Instant now) {
        Optional<EndpointRow> endpoint = notifications.findByProviderBinding(tenantId, providerBindingId);
        if (endpoint.isEmpty()) {
            // A binding that never carried a recipient_endpoints row at all —
            // reachable only if something retires a binding before its first
            // fan-out or link ever materialized one. Nothing to retire.
            return;
        }

        notifications.retireEndpoint(tenantId, endpoint.get().id(), now);

        UUID customerAccountId = endpoint.get().customerAccountId();
        if (customerAccountId == null) {
            // An OPERATIONS or PLATFORM binding: retiring its endpoint above
            // is the whole job. ck_endpoint_owner (V0107) guarantees only a
            // customer's own binding-shaped endpoint ever carries an account.
            return;
        }

        for (NotificationClass notificationClass : PREFERENCE_RESPECTING_CLASSES) {
            preferences.set(tenantId, customerAccountId, null, notificationClass, NotificationChannel.TELEGRAM, false);
        }
        log.info(
                "Synced TELEGRAM preference off for customer {} in tenant {} after binding {} retired ({})",
                customerAccountId,
                tenantId,
                providerBindingId,
                reason);
    }

    @Override
    public Optional<UUID> activeBindingFor(UUID tenantId, UUID customerAccountId) {
        return notifications.activeCustomerProviderBindingId(tenantId, customerAccountId);
    }
}
