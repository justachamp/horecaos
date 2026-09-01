package uz.horecaos.platform.notifications.application;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.marketing.api.CampaignFeedbackPort;
import uz.horecaos.platform.notifications.api.DispatchOutcome;
import uz.horecaos.platform.notifications.api.OperationsAlertPort;
import uz.horecaos.platform.notifications.domain.NotificationChannel;
import uz.horecaos.platform.notifications.domain.NotificationClass;
import uz.horecaos.platform.notifications.infrastructure.persistence.JdbcNotificationStore.NotificationRow;

/**
 * The block-rate guard's delivery-side half (ADR 0059 stage 4: "the guard
 * protects the bot").
 *
 * <p>{@link NotificationDispatchService} calls {@link #onRejected} once for
 * every rejected send, for every channel and every class — this class is what
 * narrows that firehose to the one signal the guard cares about: a TELEGRAM
 * {@code MARKETING} message whose binding just retired. {@code
 * TelegramChannelAdapter} already gives that signal a stable shape,
 * independent of the exact Telegram reason underneath it — {@code
 * CustomerProviderBindingSyncService} treats the very same {@code
 * BINDING_RETIRED_*} prefix as consent revocation, and this class treats it as
 * a block for the same reason: whatever caused the binding to retire, this
 * bot just lost the ability to reach that chat, which is exactly what
 * Telegram's own anti-spam system is watching for.
 */
@Component
public class CampaignBlockRateMonitor {

    private static final Logger log = LoggerFactory.getLogger(CampaignBlockRateMonitor.class);

    /** The prefix every {@code TelegramChannelAdapter} binding retirement rejection carries. */
    private static final String BINDING_RETIRED_PREFIX = "BINDING_RETIRED";

    /** The semantic template key a tenant authors this alert's wording against. */
    public static final String BLOCK_RATE_PAUSED_TEMPLATE_KEY = "CAMPAIGN_BLOCK_RATE_PAUSED";

    private final CampaignFeedbackPort campaignFeedback;
    private final OperationsAlertPort operationsAlerts;
    private final MeterRegistry meters;

    public CampaignBlockRateMonitor(
            CampaignFeedbackPort campaignFeedback, OperationsAlertPort operationsAlerts, MeterRegistry meters) {
        this.campaignFeedback = campaignFeedback;
        this.operationsAlerts = operationsAlerts;
        this.meters = meters;
    }

    /**
     * Called after every rejected send is settled. A no-op for anything that is
     * not a campaign message losing its recipient's Telegram binding.
     */
    public void onRejected(NotificationRow row, DispatchOutcome outcome, Instant now) {
        if (!NotificationClass.MARKETING.name().equals(row.notificationClass())
                || !NotificationChannel.TELEGRAM.name().equals(row.channel())
                || !CampaignTelegramDeliveryService.CAMPAIGN_SUBJECT_TYPE.equals(row.subjectType())
                || outcome.errorCode() == null
                || !outcome.errorCode().startsWith(BINDING_RETIRED_PREFIX)) {
            return;
        }
        UUID accountId = row.recipientAccountId();
        if (accountId == null) {
            // Cannot happen for a row this class itself creates with the
            // account pre-resolved (see CampaignTelegramDeliveryService), but a
            // malformed row is a reason to skip the guard, not to throw out of
            // the delivery worker's settle path.
            log.warn("Campaign notification {} has no recipient account; the block guard cannot count it", row.id());
            return;
        }

        meters.counter("horecaos.notifications.campaign_blocks", "channel", row.channel())
                .increment();

        CampaignFeedbackPort.BlockOutcome result =
                campaignFeedback.recordBlocked(row.tenantId(), row.subjectId(), accountId, now);
        log.info(
                "Campaign {} blocked recipient {}: {} of {} attempted now blocked",
                row.subjectId(),
                accountId,
                result.blockedCount(),
                result.attempted());

        if (result.pausedByThisCall()) {
            raiseOperatorAlert(row, result, now);
        }
    }

    private void raiseOperatorAlert(NotificationRow row, CampaignFeedbackPort.BlockOutcome result, Instant now) {
        Map<String, String> variables = Map.of(
                "blockedCount", String.valueOf(result.blockedCount()),
                "attempted", String.valueOf(result.attempted()));

        operationsAlerts.fanOut(
                row.tenantId(),
                row.brandId(),
                // Brand-scoped, not location-scoped: a campaign has no single
                // location, and OperationsSubscriptionDirectory's own query
                // resolves a null location to "every binding bound at brand
                // scope", which is the right audience for this alert.
                null,
                BLOCK_RATE_PAUSED_TEMPLATE_KEY,
                BLOCK_RATE_PAUSED_TEMPLATE_KEY,
                CampaignTelegramDeliveryService.CAMPAIGN_SUBJECT_TYPE,
                row.subjectId(),
                null,
                // One alert per campaign, ever: the guard pauses a campaign
                // exactly once (pausedByThisCall is true on only one call), and
                // a re-scan or a redelivered attempt must not fan this out
                // again for the same pause.
                "CAMPAIGN_BLOCK_RATE_PAUSED:%s".formatted(row.subjectId()),
                variables,
                Duration.ofHours(6));
    }
}
