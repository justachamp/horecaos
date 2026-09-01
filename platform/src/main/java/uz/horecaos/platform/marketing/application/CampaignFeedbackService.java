package uz.horecaos.platform.marketing.application;

import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.marketing.api.CampaignFeedbackPort;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore;

/**
 * {@link CampaignFeedbackPort}'s implementation (ADR 0059 stage 4): whether a
 * campaign should keep sending, and the block-rate guard that can decide it
 * should not.
 *
 * <p>The guard trips on two independent conditions, either one sufficient —
 * "default conservative" means erring toward pausing early, not toward
 * needing both signals to agree before it acts:
 *
 * <ul>
 *   <li>{@code blockedCount >= absoluteThreshold}, a floor that catches a
 *       genuinely bad send (a broken audience, a template that reads as spam)
 *       even on a campaign too small for a percentage to mean anything yet;
 *   <li>{@code blockedCount / attempted >= percentageThreshold}, once
 *       {@code attempted} has cleared {@code minimumSample} — a percentage
 *       computed over one or two sends is noise, not a rate.
 * </ul>
 *
 * <p>Both numbers are provisional defaults in the same spirit ADR 0044's own
 * quiet-hours and frequency-cap values are: enforced from day one, conservative
 * on purpose, and a number product or the operator running a given tenant's
 * pilot may want to tune rather than an argument this class makes.
 */
@Service
public class CampaignFeedbackService implements CampaignFeedbackPort {

    private final JdbcCampaignStore campaigns;
    private final int absoluteThreshold;
    private final double percentageThreshold;
    private final int minimumSample;

    public CampaignFeedbackService(
            JdbcCampaignStore campaigns,
            @Value("${horecaos.marketing.block-guard.absolute-threshold:25}") int absoluteThreshold,
            @Value("${horecaos.marketing.block-guard.percentage-threshold:0.05}") double percentageThreshold,
            @Value("${horecaos.marketing.block-guard.minimum-sample:20}") int minimumSample) {
        this.campaigns = campaigns;
        this.absoluteThreshold = absoluteThreshold;
        this.percentageThreshold = percentageThreshold;
        this.minimumSample = minimumSample;
    }

    @Override
    public boolean isSending(UUID tenantId, UUID campaignId) {
        return campaigns
                .find(tenantId, campaignId)
                .map(row -> row.status().isExpanding())
                .orElse(false);
    }

    @Override
    @Transactional
    public BlockOutcome recordBlocked(UUID tenantId, UUID campaignId, UUID customerAccountId, Instant now) {
        int blockedCount = campaigns
                .incrementBlockedCount(tenantId, campaignId, now)
                // A campaign id this tenant does not own is a data fault on the
                // notification row that named it, not something the delivery
                // worker should throw over mid-dispatch. Answered as "no block
                // recorded, nothing paused" rather than propagated.
                .orElse(0);
        int attempted = campaigns.attemptedRecipientCount(tenantId, campaignId);

        boolean overAbsolute = blockedCount >= absoluteThreshold;
        boolean overPercentage =
                attempted >= minimumSample && blockedCount >= Math.ceil(attempted * percentageThreshold);

        boolean pausedByThisCall = false;
        if (overAbsolute || overPercentage) {
            pausedByThisCall = campaigns.pauseForBlockRate(
                    tenantId,
                    campaignId,
                    "Block-rate guard: %d of %d recipients attempted blocked the bot (threshold %d absolute or %.0f%% of at least %d)"
                            .formatted(
                                    blockedCount,
                                    attempted,
                                    absoluteThreshold,
                                    percentageThreshold * 100,
                                    minimumSample),
                    now);
        }
        return new BlockOutcome(blockedCount, attempted, pausedByThisCall);
    }
}
