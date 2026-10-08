package uz.horecaos.platform.marketing.application;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.marketing.domain.CampaignStatus;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAudienceStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAudienceStore.SnapshotMemberRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore.BatchClaim;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore.CampaignRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore.StepRow;

/**
 * Putting an approved snapshot's guests into a scenario, one bounded batch at a time
 * (ADR 0112).
 *
 * <p>The enrolment half of what {@code CampaignSendService} does for a broadcast, and
 * deliberately the same shape: a batch is claimed against the recipient cap by the same
 * conditional UPDATE, a replay claims nothing, and a halted campaign enrols nobody. What is
 * different is what a guest becomes: not a queued message, but a state row, with the wait
 * to their first step.
 *
 * <p><strong>The control group is decided here, once.</strong> A guest is withheld or not at
 * the moment they enter, by a function of the campaign and the guest alone, and the answer
 * is stored on their state row. Nothing resamples it per step, because a guest who was
 * measured as treated at step one and withheld at step two measures nothing; and being a
 * deterministic function rather than a random draw, a replayed batch assigns the same guest
 * to the same side.
 */
@Service
public class ScenarioEnrolmentService {

    private static final Logger log = LoggerFactory.getLogger(ScenarioEnrolmentService.class);

    private final JdbcCampaignStore campaigns;
    private final JdbcAudienceStore audiences;
    private final JdbcScenarioStore scenarios;

    public ScenarioEnrolmentService(
            JdbcCampaignStore campaigns, JdbcAudienceStore audiences, JdbcScenarioStore scenarios) {
        this.campaigns = campaigns;
        this.audiences = audiences;
        this.scenarios = scenarios;
    }

    /** What one enrolment pass did. */
    public record Enrolment(
            int batchSequence,
            int claimed,
            int entered,
            boolean exhausted,
            boolean activeParticipantsRemain,
            boolean haltedAtCap) {}

    /**
     * Enrols the next batch of the campaign's snapshot.
     *
     * @return what it did; {@code exhausted} when the snapshot has nobody left to enrol
     */
    public Enrolment enrolNextBatch(CampaignRow campaign, int batchSize, Instant now) {
        UUID tenantId = campaign.tenantId();
        List<StepRow> steps = scenarios.steps(tenantId, campaign.id());
        if (steps.isEmpty()) {
            throw new IllegalStateException("Scenario %s has no steps to enrol anybody into".formatted(campaign.id()));
        }

        UUID cursor =
                scenarios.lastParticipantAccountId(tenantId, campaign.id()).orElse(null);
        List<SnapshotMemberRow> members =
                audiences.includedMembersAfter(tenantId, campaign.snapshotId(), cursor, batchSize);
        if (members.isEmpty()) {
            return new Enrolment(-1, 0, 0, true, scenarios.activeParticipants(tenantId, campaign.id()) > 0, false);
        }

        int sequence = campaigns.nextBatchSequence(tenantId, campaign.id());
        // No cost reserved here: a scenario's cost is reserved step by step as each is
        // decided. What the claim still enforces is the recipient cap.
        BatchClaim claim =
                campaigns.claimBatch(tenantId, campaign.id(), campaign.snapshotId(), sequence, members.size(), 0L, now);
        switch (claim) {
            case ALREADY_CLAIMED -> {
                return new Enrolment(sequence, 0, 0, false, true, false);
            }
            case REFUSED -> {
                campaigns.halt(
                        tenantId,
                        campaign.id(),
                        CampaignStatus.SENDING,
                        CampaignStatus.HALTED_BUDGET,
                        "The next batch of %d guests would exceed the recipient cap".formatted(members.size()),
                        now);
                log.warn("Scenario {} halted at its recipient cap", campaign.id());
                return new Enrolment(sequence, 0, 0, false, true, true);
            }
            case RESERVED -> {
                // Fall through to the enrolment below.
            }
        }

        Instant firstStepDue = now.plusSeconds(steps.getFirst().waitAfterPreviousSeconds());
        int entered = 0;
        for (SnapshotMemberRow member : members) {
            boolean withheld = isWithheld(campaign.id(), member.customerAccountId(), campaign.controlGroupPercent());
            if (scenarios.enrol(
                    tenantId,
                    campaign.brandId(),
                    campaign.id(),
                    member.customerAccountId(),
                    withheld,
                    firstStepDue,
                    now)) {
                entered++;
            }
        }
        return new Enrolment(sequence, members.size(), entered, false, true, false);
    }

    /**
     * Whether a guest is in the withheld control group.
     *
     * <p>A hash of the campaign and the guest, reduced to a hundred buckets, compared with
     * the percentage. Stable across a replay and independent of the order guests arrive in,
     * which a counter ("withhold every tenth") would not be.
     */
    static boolean isWithheld(UUID campaignId, UUID accountId, @Nullable Integer controlGroupPercent) {
        if (controlGroupPercent == null || controlGroupPercent <= 0) {
            return false;
        }
        long bucket = Math.floorMod(
                UUID.nameUUIDFromBytes(("control:" + campaignId + ":" + accountId).getBytes(StandardCharsets.UTF_8))
                        .getLeastSignificantBits(),
                100L);
        return bucket < controlGroupPercent;
    }
}
