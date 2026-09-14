package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore.CampaignRef;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Writes the status {@code CampaignStatus} declared and nobody wrote (ADR
 * 0044, operations §6.4 Campaigns): {@code APPROVED -> SCHEDULED -> SENDING}.
 *
 * <p>Before this wave, {@code scheduled_at} existed on {@code
 * marketing.campaigns} since V0043 and nothing ever read or wrote it — a
 * campaign could be armed for a future moment only by an operator watching
 * the clock and pressing launch by hand. This sweep is {@code
 * CampaignExpansionScheduler}'s own sibling: cross-tenant infrastructure
 * walking a partial index ({@code ix_campaigns_scheduled}, V0307), one call
 * per tick per campaign so no single tenant's schedule monopolises the
 * scheduler thread.
 *
 * <p>The actual promotion is {@code CampaignService#start}, the same method
 * an operator's own "launch" call reaches — see that method's own doc for
 * why one method serves both callers, and for the {@code isWired} refusal
 * this sweep inherits from it: a scheduled campaign whose channel is still
 * unwired when its moment arrives is refused and logged here, exactly the
 * visibility ADR 0044's isWired fix exists to give an immediate launch.
 */
@Component
public class CampaignScheduledSendScheduler {

    private static final Logger log = LoggerFactory.getLogger(CampaignScheduledSendScheduler.class);

    private final JdbcCampaignStore campaigns;
    private final CampaignService campaignService;
    private final Clock clock;
    private final int batchSize;

    public CampaignScheduledSendScheduler(
            JdbcCampaignStore campaigns,
            CampaignService campaignService,
            Clock clock,
            @Value("${horecaos.marketing.scheduled-send.sweep-size:50}") int batchSize) {
        this.campaigns = campaigns;
        this.campaignService = campaignService;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${horecaos.marketing.scheduled-send.initial-delay:PT10S}",
            fixedDelayString = "${horecaos.marketing.scheduled-send.interval:PT15S}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("The campaign scheduled-send sweep could not run", failure);
        }
    }

    /** @return how many campaigns this pass tried to promote, for a deterministic test */
    public int runOnce() {
        var due = campaigns.dueScheduledCampaigns(clock.instant(), batchSize);
        for (CampaignRef ref : due) {
            try {
                if (!campaignService.start(ref.tenantId(), ref.campaignId())) {
                    log.warn(
                            "Campaign {} was due to send but was no longer SCHEDULED when the sweep reached it",
                            ref.campaignId());
                }
            } catch (ApiException failure) {
                // UNPROCESSABLE_STATE here means CampaignService#start refused the
                // SCHEDULED -> SENDING move outright (an unwired channel, most
                // often) — a state that will never resolve itself between sweeps.
                // Left alone, scheduled_at stays in the past and every sweep from
                // now on re-selects and re-refuses the same campaign, forever.
                // Disarming it (see clearFailedSchedule's own doc) turns that into
                // one refusal an operator can see and act on, not an unbounded log.
                log.error("Campaign {} could not be promoted from SCHEDULED", ref.campaignId(), failure);
                if (failure.errorCode() == ErrorCode.UNPROCESSABLE_STATE) {
                    String reason = failure.getMessage() == null ? "UNPROCESSABLE_STATE" : failure.getMessage();
                    campaigns.clearFailedSchedule(ref.tenantId(), ref.campaignId(), reason, clock.instant());
                }
            } catch (RuntimeException failure) {
                // A transient failure (a lapsed entitlement check, a DB blip) must
                // not disarm the schedule — it is retried on the next sweep, and
                // must not stop every other tenant's scheduled campaign from being
                // promoted on this same pass either.
                log.error("Campaign {} could not be promoted from SCHEDULED", ref.campaignId(), failure);
            }
        }
        return due.size();
    }
}
