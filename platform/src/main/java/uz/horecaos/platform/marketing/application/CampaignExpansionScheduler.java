package uz.horecaos.platform.marketing.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore.CampaignRef;

/**
 * Keeps every {@code SENDING} campaign expanding (ADR 0044, ADR 0059 stage 4).
 *
 * <p>{@code CampaignSendService#expandNextBatch} deliberately does one batch
 * per call — "a worker that dies halfway through a hundred thousand recipients
 * should lose one batch, and the caller decides how fast to go" — and until
 * this class existed, nothing was the caller: a campaign approved and started
 * would sit in {@code SENDING} forever, having expanded nothing. This is the
 * same shape {@code ApprovalDeadlineWarningSweeper} and {@code
 * OnboardingStuckRunAlertSweeper} already give a cross-tenant sweep: infrastructure
 * walking a partial index ({@code ix_campaigns_sending}), not a tenant-scoped
 * business read, one call per tick per campaign so no single tenant's send
 * monopolises the scheduler thread.
 *
 * <p>Pacing a Telegram campaign's own traffic within the bot's ceiling is a
 * separate concern, decided in {@code notifications} at the moment each
 * message is scheduled (see {@code CampaignPacer}), not here: this class's
 * only job is to keep calling {@code expandNextBatch} until a campaign finishes,
 * halts, or pauses, at whatever cadence its own batches and reservations allow.
 */
@Component
public class CampaignExpansionScheduler {

    private static final Logger log = LoggerFactory.getLogger(CampaignExpansionScheduler.class);

    private final JdbcCampaignStore campaigns;
    private final CampaignSendService sends;
    private final int batchSize;

    public CampaignExpansionScheduler(
            JdbcCampaignStore campaigns,
            CampaignSendService sends,
            @Value("${horecaos.marketing.expansion.sweep-size:50}") int batchSize) {
        this.campaigns = campaigns;
        this.sends = sends;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${horecaos.marketing.expansion.initial-delay:PT10S}",
            fixedDelayString = "${horecaos.marketing.expansion.interval:PT5S}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            log.error("The campaign expansion sweep could not run", failure);
        }
    }

    /** @return how many campaigns this pass tried to expand, for a deterministic test */
    public int runOnce() {
        var sending = campaigns.sendingCampaigns(batchSize);
        for (CampaignRef ref : sending) {
            try {
                sends.expandNextBatch(ref.tenantId(), ref.campaignId());
            } catch (RuntimeException failure) {
                // One campaign's failure must not stop every other tenant's
                // campaign from expanding on this pass.
                log.error("Campaign {} could not be expanded", ref.campaignId(), failure);
            }
        }
        return sending.size();
    }
}
