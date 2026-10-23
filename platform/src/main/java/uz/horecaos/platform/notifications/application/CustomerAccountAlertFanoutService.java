package uz.horecaos.platform.notifications.application;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import uz.horecaos.platform.notifications.api.CustomerAccountAlertPort;
import uz.horecaos.platform.notifications.api.NotificationTransport;
import uz.horecaos.platform.notifications.domain.MessageLocale;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NewNotification;

/**
 * {@link CustomerAccountAlertPort}'s implementation: {@link CustomerAlertFanoutService} for a message that has no
 * order (ADR 0020, ADR 0181).
 *
 * <p>The recipient is the account the caller names, stored on the intent as {@code recipient_account_id}.
 * {@code NotificationEligibilityService} reads that column as "already resolved" and neither looks the subject up
 * as an order nor needs one to exist; everything after that -- consent for a class that needs it, the customer's
 * preference, quiet hours, the endpoint for the chosen channel, the active template and its provider review -- is
 * the pipeline every other customer message goes through.
 */
@Component
public class CustomerAccountAlertFanoutService implements CustomerAccountAlertPort {

    private static final Logger log = LoggerFactory.getLogger(CustomerAccountAlertFanoutService.class);

    /** The class every account-keyed message is created with until counsel rules otherwise (ADR 0020). */
    static final NotificationClass NOTIFICATION_CLASS = NotificationClass.TRANSACTIONAL_REQUIRED;

    private final JdbcNotificationStore notifications;
    private final CustomerTelegramChannelRouter channelRouter;
    private final NotificationTemplateService templates;
    private final NotificationTransport transport;
    private final ObjectMapper objectMapper;
    private final NotificationChannel fallbackChannel;

    public CustomerAccountAlertFanoutService(
            JdbcNotificationStore notifications,
            CustomerTelegramChannelRouter channelRouter,
            NotificationTemplateService templates,
            NotificationTransport transport,
            ObjectMapper objectMapper,
            // The same key the order triggers read, so "the existing channel" means one thing.
            @Value("${horecaos.notifications.order-channel:SMS}") String fallbackChannel) {
        this.notifications = notifications;
        this.channelRouter = channelRouter;
        this.templates = templates;
        this.transport = transport;
        this.objectMapper = objectMapper;
        this.fallbackChannel = NotificationChannel.valueOf(fallbackChannel);
    }

    /**
     * Asks the questions {@link NotificationEligibilityService#evaluate} would answer with a suppression, in the
     * order it asks them: the channel, then the wording, then the gateway's approval of it.
     *
     * <p>The wording is resolved in the platform's fallback language. Whether a template <em>exists</em> does not
     * depend on the language it is read in -- {@link NotificationTemplateService#resolve} answers
     * {@code NO_ACTIVE_TEMPLATE} before it looks at one -- and the language chain ends in the brand's default and
     * that fallback, which every activated template carries (the brand's required languages are checked when a
     * version is activated). A customer's own language only picks which of an existing template's bodies is read.
     */
    @Override
    @Transactional(readOnly = true)
    public boolean canDeliver(UUID tenantId, UUID brandId, UUID customerAccountId, String templateKey) {
        NotificationChannel channel =
                channelRouter.resolve(tenantId, brandId, customerAccountId, NOTIFICATION_CLASS, fallbackChannel);
        if (!channel.isWired() || !transport.supports(channel.name())) {
            return false;
        }
        NotificationTemplateService.Resolution wording = templates.resolve(
                tenantId, brandId, templateKey, channel, MessageLocale.FALLBACK, null, null, NOTIFICATION_CLASS);
        if (!wording.isFound()) {
            return false;
        }
        // The platform's own wording carries no moderation state of its own: it is the platform's to get approved.
        var version = wording.version();
        return version == null || !TemplateProviderReviewService.withheld(version.providerReview());
    }

    @Override
    @Transactional
    public boolean notifyAccount(AccountAlert alert) {
        UUID tenantId = alert.tenantId();
        NotificationChannel channel = channelRouter.resolve(
                tenantId, alert.brandId(), alert.customerAccountId(), NOTIFICATION_CLASS, fallbackChannel);

        boolean created = notifications.createIntent(new NewNotification(
                UUID.randomUUID(),
                tenantId,
                alert.brandId(),
                null,
                NOTIFICATION_CLASS.name(),
                channel.name(),
                alert.templateKey(),
                alert.subjectType(),
                alert.subjectId(),
                alert.customerAccountId(),
                null,
                alert.idempotencyKey(),
                objectMapper.writeValueAsString(alert.variables()),
                alert.now(),
                alert.now().plus(alert.expiry()),
                alert.now(),
                null));

        if (!created) {
            log.debug("A {} notification already exists for key {}", alert.templateKey(), alert.idempotencyKey());
        }
        return created;
    }
}
