package uz.horecaos.platform.notifications;

import java.time.Instant;
import java.util.UUID;
import uz.horecaos.platform.marketing.api.CampaignFeedbackPort;

/**
 * The {@link CampaignFeedbackPort} every notifications test that is not about
 * ADR 0044 campaigns wires in place of {@code CampaignFeedbackService}: no row
 * in these tests carries {@code CampaignTelegramDeliveryService.CAMPAIGN_SUBJECT_TYPE}
 * as its subject, so {@link NotificationEligibilityService} and {@link
 * uz.horecaos.platform.notifications.application.CampaignBlockRateMonitor}
 * never actually ask this anything — but every constructor still needs one.
 * {@code isSending} answers {@code true} rather than throwing, on the same
 * defensive-default reasoning {@code AlwaysEntitledService} uses: a wrong
 * answer here should look like "nothing to suppress", not a test failure
 * unrelated to what the test is actually about.
 */
final class AlwaysSendingCampaignFeedback implements CampaignFeedbackPort {

    @Override
    public boolean isSending(UUID tenantId, UUID campaignId) {
        return true;
    }

    @Override
    public BlockOutcome recordBlocked(UUID tenantId, UUID campaignId, UUID customerAccountId, Instant now) {
        throw new UnsupportedOperationException("Not exercised outside the ADR 0059 stage 4 broadcast tests");
    }
}
