package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.commercial.api.EntitlementKeys;
import uz.horecaos.platform.commercial.api.EntitlementService;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.marketing.api.CampaignMessagePort;
import uz.horecaos.platform.marketing.domain.CampaignStatus;
import uz.horecaos.platform.marketing.domain.EngagementPolicy;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore.CampaignRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore.NewCampaign;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;

/**
 * The campaign lifecycle: authoring, estimating, approving, and stopping
 * (ADR 0044).
 *
 * <p>A campaign cannot send without approval and the approver must not be the
 * author. The failure being prevented is a marketer testing a template and sending
 * forty thousand real SMS, and there is no undo for an SMS. The rule is enforced in
 * the UPDATE predicate as well as in a CHECK, because a read-then-write would leave
 * the window between reading the author and writing the approver.
 *
 * <p>A campaign references a benefit and never defines one. There is no method here
 * that takes a discount type, a value, a minimum basket, or a stacking rule, and no
 * column on the table to put one in. Marketing decides <em>who</em> receives a
 * grant and <em>when</em>; ADR 0018 decides what a grant is worth and whether it may
 * combine with anything else, and ADR 0046 owns points. That absence is the
 * enforcement.
 */
@Service
public class CampaignService {

    private final JdbcCampaignStore campaigns;
    private final JdbcEngagementStore engagement;
    private final AudienceService audiences;
    private final CampaignCostEstimator estimator;
    private final CampaignMessagePort messages;
    private final AuditRecorder audit;
    private final EntitlementService entitlements;
    private final Clock clock;

    public CampaignService(
            JdbcCampaignStore campaigns,
            JdbcEngagementStore engagement,
            AudienceService audiences,
            CampaignCostEstimator estimator,
            CampaignMessagePort messages,
            AuditRecorder audit,
            EntitlementService entitlements,
            Clock clock) {
        this.campaigns = campaigns;
        this.engagement = engagement;
        this.audiences = audiences;
        this.estimator = estimator;
        this.messages = messages;
        this.audit = audit;
        this.entitlements = entitlements;
        this.clock = clock;
    }

    /**
     * Creates a draft.
     *
     * @param costCeilingMinor optional on a channel with no marginal money, and
     *                         required on one that has it. Integer minor units; for
     *                         UZS a minor unit is a whole som
     * @param benefitOfferId an existing ADR 0018 offer, or null. The campaign
     *                       editor selects from offers and has no field in which to
     *                       invent one
     * @param loyaltyAccrualRuleId an existing ADR 0046 accrual rule, or null when
     *                             this campaign grants no points
     */
    @Transactional
    public UUID create(
            UUID tenantId,
            UUID brandId,
            String name,
            MarketingChannel channel,
            String consentPurpose,
            UUID audienceId,
            String templateKey,
            int recipientCap,
            @Nullable Long costCeilingMinor,
            String currency,
            @Nullable UUID benefitOfferId,
            @Nullable UUID loyaltyAccrualRuleId,
            UUID authorId) {

        if (channel.carriesMarginalCost() && costCeilingMinor == null) {
            throw new IllegalArgumentException(
                    "A %s campaign needs a cost ceiling: this channel bills per segment and the ".formatted(channel)
                            + "mistake is unrecoverable");
        }

        EngagementPolicy policy = engagement.resolvePolicy(tenantId, brandId);
        UUID id = UUID.randomUUID();

        campaigns.insertCampaign(new NewCampaign(
                id,
                tenantId,
                brandId,
                name,
                channel.name(),
                consentPurpose,
                audienceId,
                templateKey,
                recipientCap,
                costCeilingMinor,
                currency,
                policy.timezone().getId(),
                benefitOfferId,
                loyaltyAccrualRuleId,
                authorId,
                clock.instant()));
        return id;
    }

    /**
     * Builds the snapshot this campaign will send against and prices it.
     *
     * <p>Runs before approval, because the whole reason for a snapshot is that the
     * number an approver signs off is the number that will be sent to. Re-evaluating
     * at send instead was the cheaper alternative and was rejected: finance signs off
     * on twelve thousand recipients and thirty-eight thousand receive it because an
     * import ran overnight.
     */
    @Transactional
    public Estimate prepare(UUID tenantId, UUID campaignId, ActorRef actor, String correlationId) {
        CampaignRow campaign = require(tenantId, campaignId);
        if (campaign.status() != CampaignStatus.DRAFT) {
            throw new IllegalStateException("A campaign is estimated while it is a draft, not in " + campaign.status());
        }

        MarketingChannel channel = MarketingChannel.valueOf(campaign.channel());
        // The campaign's brand, so a campaign cannot be estimated against a
        // sibling brand's audience even if one is named on the row.
        var snapshot = audiences.buildSnapshot(
                tenantId,
                campaign.brandId(),
                campaign.audienceId(),
                channel,
                campaign.consentPurpose(),
                actor,
                correlationId);

        EngagementPolicy policy = engagement.resolvePolicy(tenantId, campaign.brandId());
        Map<String, Integer> localeCounts = audiences.memberLocales(tenantId, snapshot.snapshotId());
        Map<String, String> bodies =
                messages.templateBodies(tenantId, campaign.brandId(), campaign.templateKey(), channel.name());

        Optional<CampaignCostEstimator.Estimate> cost = estimator.estimate(
                channel, bodies, localeCounts, policy.smsPricePerSegmentMinor(), campaign.currency());

        // ADR 0059 stage 4: "estimated delivery window, not a promise", computed
        // at the same moment as the cost estimate and against the same rate the
        // send will actually be paced at. Empty for a channel notifications
        // reports no pacing ceiling for — nothing paces SMS/EMAIL/PUSH sends
        // today, so null is the honest answer rather than zero, which would read
        // as "instant".
        OptionalDouble rate = messages.campaignRatePerSecond(channel.name());
        Long estimatedDeliverySeconds =
                rate.isPresent() ? (long) Math.ceil(snapshot.memberCount() / rate.getAsDouble()) : null;

        campaigns.recordEstimate(
                tenantId,
                campaignId,
                snapshot.snapshotId(),
                snapshot.memberCount(),
                cost.map(CampaignCostEstimator.Estimate::lowMinor).orElse(null),
                cost.map(CampaignCostEstimator.Estimate::highMinor).orElse(null),
                estimatedDeliverySeconds,
                clock.instant());

        return new Estimate(
                snapshot.snapshotId(),
                snapshot.memberCount(),
                snapshot.candidateCount(),
                cost.map(CampaignCostEstimator.Estimate::lowMinor).orElse(null),
                cost.map(CampaignCostEstimator.Estimate::highMinor).orElse(null),
                campaign.currency(),
                estimatedDeliverySeconds);
    }

    @Transactional
    public boolean submitForReview(UUID tenantId, UUID campaignId) {
        CampaignRow campaign = require(tenantId, campaignId);
        if (campaign.snapshotId() == null) {
            throw new IllegalStateException(
                    "A campaign is reviewed against a snapshot and an estimate, and this one has "
                            + "neither: nothing has been approved until somebody has seen a number");
        }
        return campaigns.transition(
                tenantId, campaignId, CampaignStatus.DRAFT, CampaignStatus.IN_REVIEW, clock.instant());
    }

    /**
     * The second signature.
     *
     * @return false when the campaign was not in review, or when the approver is
     *         the author. Both are refusals rather than errors, and both are
     *         audited as {@code REJECTED} so a repeated attempt is visible
     */
    @Transactional
    public boolean approve(
            UUID tenantId,
            UUID campaignId,
            UUID approverId,
            UUID approvalId,
            ActorRef actor,
            String reason,
            String correlationId) {

        CampaignRow campaign = require(tenantId, campaignId);
        Instant now = clock.instant();
        boolean approved = campaigns.approve(tenantId, campaignId, approverId, approvalId, now);

        audit.record(AuditFact.of("MARKETING_CAMPAIGN_APPROVED", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.brand(tenantId, campaign.brandId()))
                .target("MarketingCampaign", campaignId)
                .targetVersion((long) campaign.version())
                .outcome(approved ? AuditFact.Outcome.SUCCEEDED : AuditFact.Outcome.REJECTED)
                .because(reason)
                .changed(Map.of(
                        "estimatedRecipients",
                        campaign.estimatedRecipients() == null ? 0 : campaign.estimatedRecipients(),
                        "costCeilingMinor",
                        String.valueOf(campaign.costCeilingMinor()),
                        "recipientCap",
                        campaign.recipientCap(),
                        "authorIsApprover",
                        approverId.equals(campaign.createdBy())))
                .underApproval(approvalId)
                .usingCapability("campaign.approve")
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());

        return approved;
    }

    /**
     * Opens the send. Nothing reaches {@code SENDING} except from an approval.
     *
     * <p>The one place ADR 0059 stage 4's Telegram entitlement is checked. A
     * campaign may be authored, estimated, reviewed, and approved for a tenant
     * whose plan does not include broadcasts — none of that spends anything —
     * but launching is the "activation" {@link
     * EntitlementService#requireFeature}'s own contract describes, so it is
     * refused here rather than discovered as a silent {@code isWired(channel) ==
     * false} refusal three steps later in {@code CampaignSendService}.
     */
    @Transactional
    public boolean start(UUID tenantId, UUID campaignId) {
        CampaignRow campaign = require(tenantId, campaignId);
        if (MarketingChannel.valueOf(campaign.channel()) == MarketingChannel.MESSAGING_APP) {
            entitlements.requireFeature(tenantId, EntitlementKeys.TELEGRAM_BROADCASTS_ENABLED);
        }
        if (!campaign.status().canTransitionTo(CampaignStatus.SENDING)) {
            return false;
        }
        return campaigns.transition(tenantId, campaignId, campaign.status(), CampaignStatus.SENDING, clock.instant());
    }

    /**
     * Stops a campaign on an operator's word.
     *
     * <p>Terminal rather than resumable. A campaign that stopped and can be
     * restarted at the press of the same button is a stop somebody undoes by
     * reflex, and the cases this exists for — wrong template, wrong audience, wrong
     * price — are all ones where the right next step is a new campaign with a new
     * approval.
     */
    @Transactional
    public boolean halt(UUID tenantId, UUID campaignId, ActorRef actor, String reason, String correlationId) {

        CampaignRow campaign = require(tenantId, campaignId);
        Instant now = clock.instant();
        boolean halted =
                campaigns.halt(tenantId, campaignId, campaign.status(), CampaignStatus.HALTED_OPERATOR, reason, now);

        audit.record(AuditFact.of("MARKETING_CAMPAIGN_HALTED", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.brand(tenantId, campaign.brandId()))
                .target("MarketingCampaign", campaignId)
                .outcome(halted ? AuditFact.Outcome.SUCCEEDED : AuditFact.Outcome.REJECTED)
                .because(reason)
                .changed(Map.of(
                        "statusBefore", campaign.status().name(),
                        "spentCostMinor", campaign.spentCostMinor(),
                        "reservedCostMinor", campaign.reservedCostMinor()))
                .usingCapability("campaign.approve")
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());

        return halted;
    }

    /**
     * Un-pauses a campaign the block-rate guard stopped: {@code PAUSED ->
     * SENDING}, so expansion and delivery continue.
     *
     * <p>Nothing already suppressed with {@code CAMPAIGN_NOT_SENDING} while the
     * campaign sat paused is retried — that message already reached the front
     * of the ADR 0020 queue, found the campaign not sending, and was recorded
     * as such, and this method's own transition happens after the fact. What
     * it can and does report is how many that was, so the operator resuming
     * the campaign knows what the pause cost before they press the button.
     *
     * <p>The same Telegram entitlement {@link #start} checks, for the same
     * reason: a tenant whose plan lost {@code telegram.broadcasts.enabled}
     * between pause and resume must not have that broadcast quietly continue.
     *
     * <p>The block-rate guard's own counter resets to zero on resume ({@link
     * JdbcCampaignStore#resume}) — a deliberate choice, not an oversight. The
     * guard measures the run it is watching, and carrying a stale count
     * forward would let a resumed campaign re-cross the threshold on its very
     * first new block, which defeats the point of resuming at all.
     *
     * @return {@link ResumeOutcome#resumed()} false when the campaign was not
     *         {@code PAUSED} — a refusal rather than an error, the same
     *         posture {@link #halt} takes
     */
    @Transactional
    public ResumeOutcome resume(UUID tenantId, UUID campaignId, ActorRef actor, String reason, String correlationId) {
        CampaignRow campaign = require(tenantId, campaignId);
        if (MarketingChannel.valueOf(campaign.channel()) == MarketingChannel.MESSAGING_APP) {
            entitlements.requireFeature(tenantId, EntitlementKeys.TELEGRAM_BROADCASTS_ENABLED);
        }
        if (campaign.status() != CampaignStatus.PAUSED) {
            return ResumeOutcome.refused();
        }

        Instant now = clock.instant();
        // Read before the transition below clears it: the boundary a resumed
        // campaign's own suppression count is measured from. Epoch is the
        // honest fallback for a row this pause did not itself set (there is
        // none in the code path that reaches PAUSED today, which always goes
        // through JdbcCampaignStore#pauseForBlockRate) — reporting the whole
        // history is more honest than reporting zero for an unknown boundary.
        Instant pausedSince = campaign.pausedAt() == null ? Instant.EPOCH : campaign.pausedAt();
        int suppressed = messages.countSuppressedForNotSending(tenantId, campaignId, pausedSince);
        boolean resumed = campaigns.resume(tenantId, campaignId, now);

        audit.record(AuditFact.of("MARKETING_CAMPAIGN_RESUMED", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.brand(tenantId, campaign.brandId()))
                .target("MarketingCampaign", campaignId)
                .targetVersion((long) campaign.version())
                .outcome(resumed ? AuditFact.Outcome.SUCCEEDED : AuditFact.Outcome.REJECTED)
                .because(reason)
                .changed(
                        Map.of("blockedCountBeforeReset", campaign.blockedCount(), "suppressedDuringPause", suppressed))
                .usingCapability("campaign.approve")
                .correlatedBy(correlationId)
                .occurredAt(now)
                .build());

        return new ResumeOutcome(resumed, suppressed);
    }

    public CampaignRow require(UUID tenantId, UUID campaignId) {
        return campaigns
                .find(tenantId, campaignId)
                .orElseThrow(() ->
                        new IllegalArgumentException("No campaign %s belongs to this tenant".formatted(campaignId)));
    }

    /**
     * What an approver is shown.
     *
     * @param memberCount the reach after the five subtractions. An upper bound: the
     *                    same checks run again per recipient, so the delivered count
     *                    is always lower
     * @param lowMinor null when the cost is not knowable — no active template, or no
     *                 configured price per segment. Null rather than zero, because
     *                 zero passes every ceiling check there is
     * @param estimatedDeliverySeconds a planning number, not a promise: quiet
     *                                 hours and the block-rate guard can both
     *                                 make the real send take longer. Null for a
     *                                 channel with no configured pacing ceiling
     */
    public record Estimate(
            UUID snapshotId,
            int memberCount,
            int candidateCount,
            @Nullable Long lowMinor,
            @Nullable Long highMinor,
            String currency,
            @Nullable Long estimatedDeliverySeconds) {}

    /**
     * What a resume did.
     *
     * @param suppressedDuringPause how many messages the pause itself cost —
     *                              zero and meaningless when {@link #resumed}
     *                              is false, since nothing was measured
     */
    public record ResumeOutcome(boolean resumed, int suppressedDuringPause) {
        static ResumeOutcome refused() {
            return new ResumeOutcome(false, 0);
        }
    }
}
