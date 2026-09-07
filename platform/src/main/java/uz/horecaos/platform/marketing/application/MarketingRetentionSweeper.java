package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAudienceStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAudienceStore.SnapshotForPurge;

/**
 * Wires {@link AudienceService#purgeExpiredMembers} to a clock (ADR 0044).
 *
 * <p>ADR 0044's Retention section names twenty-four months for audience
 * snapshot membership: past that, "what did you send and on what basis" is
 * answered by the snapshot header, its counts, and the campaign recipient
 * rows without the membership list. {@code JdbcAudienceStore.purgeMembers} has
 * existed since the schema that carries {@code members_purged_at} (V0043) and
 * is exercised by {@code MarketingCampaignTests}, but until this class nothing
 * ever called it outside a test — the twenty-four months were a number in a
 * document, not a job.
 *
 * <p>Of the other windows the Retention section states, this is the only one
 * that needs a sweep. Suppression expiry (twelve months for {@code
 * HARD_BOUNCE}/{@code INVALID_NUMBER}) is already self-enforcing: {@code
 * JdbcEngagementStore} stamps {@code expires_at} at write time from {@code
 * SuppressionReason#lifetime}, and every read that decides whether a
 * suppression is active — {@code findActiveSuppression}, {@code
 * listByBrand}'s {@code activeOnly} — filters on it, so an expired row is
 * already inert without being deleted. Trigger firing rows, coded benefit
 * grants, and review free text have no rows to age out: triggers, the coded
 * grant, and reviews are all named "Not built" in this record's own
 * implementation checklist.
 *
 * <p>Same shape as {@link CampaignExpansionScheduler} and, in the retention
 * genre specifically, {@code ConversationRetentionSweeper}: a worklist read —
 * {@link JdbcAudienceStore#snapshotsPastRetention}, itself a partial-index scan
 * (V0176) rather than a sequential one — followed by one call per item, each
 * failure logged and swallowed so one snapshot's failure cannot stop the rest
 * of the pass or lose what a batch already purged.
 *
 * <p>No {@code TenantRlsSession} binding, for the identical reason {@link
 * CustomerMetricProjectionSweeper}'s own doc gives at length: {@code
 * marketing.audience_snapshots} and {@code marketing.audience_snapshot_members}
 * carry no ADR 0056 policy today, and no other service in this module binds a
 * tenant or the bypass role either, so adding it to only this class would
 * protect nothing and would look like coverage that is not there.
 */
@Component
public class MarketingRetentionSweeper {

    private static final Logger log = LoggerFactory.getLogger(MarketingRetentionSweeper.class);

    private final JdbcAudienceStore audiences;
    private final AudienceService audienceService;
    private final Clock clock;
    private final int retentionMonths;
    private final int batchSize;

    public MarketingRetentionSweeper(
            JdbcAudienceStore audiences,
            AudienceService audienceService,
            Clock clock,
            @Value("${horecaos.marketing.retention-sweeper.months:24}") int retentionMonths,
            @Value("${horecaos.marketing.retention-sweeper.batch-size:200}") int batchSize) {
        this.audiences = audiences;
        this.audienceService = audienceService;
        this.clock = clock;
        this.retentionMonths = retentionMonths;
        this.batchSize = batchSize;
    }

    @Scheduled(
            initialDelayString = "${horecaos.marketing.retention-sweeper.initial-delay:PT1M}",
            fixedDelayString = "${horecaos.marketing.retention-sweeper.interval:PT1H}")
    public void sweepOnce() {
        try {
            runOnce();
        } catch (RuntimeException failure) {
            // Logged and swallowed, not rethrown: the next tick retries, and the
            // alternative is a dead scheduler that also stops every other
            // module's timer sharing this pool (SchedulingConfiguration).
            log.error("The marketing retention sweep could not run", failure);
        }
    }

    /** @return how many snapshots were due and how many member rows were purged, for a deterministic test */
    public Result runOnce() {
        Instant now = clock.instant();
        List<SnapshotForPurge> due = audiences.snapshotsPastRetention(now, retentionMonths, batchSize);

        int purged = 0;
        for (SnapshotForPurge snapshot : due) {
            try {
                purged += audienceService.purgeExpiredMembers(snapshot.tenantId(), snapshot.snapshotId(), now);
            } catch (RuntimeException failure) {
                // One snapshot's failure must not lose what this pass already
                // purged, nor stop it from reaching the rest of the batch.
                log.error("Marketing retention sweep could not purge snapshot {}", snapshot.snapshotId(), failure);
            }
        }
        if (purged > 0) {
            log.info("Marketing retention sweep purged {} member rows across {} snapshots", purged, due.size());
        }
        return new Result(due.size(), purged);
    }

    /** What one pass found due, and how many member rows it purged. */
    public record Result(int snapshotsDue, int membersPurged) {}
}
