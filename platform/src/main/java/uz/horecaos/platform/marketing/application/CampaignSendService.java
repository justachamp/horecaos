package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.marketing.api.CampaignMessagePort;
import uz.horecaos.platform.marketing.api.CampaignMessagePort.MarketingMessage;
import uz.horecaos.platform.marketing.domain.CampaignStatus;
import uz.horecaos.platform.marketing.domain.EngagementPolicy;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.domain.RefusalReason;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAudienceStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAudienceStore.SnapshotMemberRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore.BatchClaim;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore.CampaignRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;

/**
 * Turning a snapshot into ADR 0020 intents, one bounded batch at a time
 * (ADR 0044).
 *
 * <p>Three properties matter, and each is enforced by the database rather than
 * remembered by this class.
 *
 * <p><strong>The ceiling holds under concurrency.</strong> A batch is reserved by a
 * single conditional UPDATE that checks and increments together, so two workers
 * racing produce one winner and one refusal. Summing sent rows and comparing was
 * the alternative and it is how two workers both read the same total, both conclude
 * there is budget left, and both spend it.
 *
 * <p><strong>A replay produces no second message.</strong> The batch row's primary
 * key is {@code (campaign_id, snapshot_id, batch_sequence)} and the recipient row's
 * is {@code (campaign_id, customer_account_id)}, which is also the ADR 0020
 * idempotency key. A repeated expansion inserts nothing and reserves nothing.
 *
 * <p><strong>The unsubscribe that arrived after approval wins.</strong> The same
 * five checks that built the snapshot run again here, per recipient, immediately
 * before the intent is created. A recipient who fails one is written down as
 * refused with the reason rather than dropped, because "why did this customer not
 * get it" is the question a tenant actually asks.
 */
@Service
public class CampaignSendService {

    private static final Logger log = LoggerFactory.getLogger(CampaignSendService.class);

    private final JdbcCampaignStore campaigns;
    private final JdbcAudienceStore audiences;
    private final JdbcEngagementStore engagement;
    private final MarketingEligibility eligibility;
    private final CampaignCostEstimator estimator;
    private final CampaignMessagePort messages;
    private final Clock clock;
    private final int batchSize;

    public CampaignSendService(
            JdbcCampaignStore campaigns,
            JdbcAudienceStore audiences,
            JdbcEngagementStore engagement,
            MarketingEligibility eligibility,
            CampaignCostEstimator estimator,
            CampaignMessagePort messages,
            Clock clock,
            @Value("${horecaos.marketing.batch-size:200}") int batchSize) {
        this.campaigns = campaigns;
        this.audiences = audiences;
        this.engagement = engagement;
        this.eligibility = eligibility;
        this.estimator = estimator;
        this.messages = messages;
        this.clock = clock;
        this.batchSize = batchSize;
    }

    /**
     * Expands the next batch of one sending campaign.
     *
     * <p>One batch per call rather than a loop to exhaustion. A worker that dies
     * halfway through a hundred thousand recipients should lose one batch, and the
     * caller decides how fast to go.
     */
    @Transactional
    public BatchOutcome expandNextBatch(UUID tenantId, UUID campaignId) {
        CampaignRow campaign = campaigns
                .find(tenantId, campaignId)
                .orElseThrow(() ->
                        new IllegalArgumentException("No campaign %s belongs to this tenant".formatted(campaignId)));

        if (!campaign.status().isExpanding()) {
            return BatchOutcome.notSending(campaign.status());
        }

        Instant now = clock.instant();
        MarketingChannel channel = MarketingChannel.valueOf(campaign.channel());
        if (!messages.isWired(channel.name())) {
            // Read before anything is claimed. A campaign that expands forty
            // thousand recipients against an unwired delivery path has spent an
            // approval and produced nothing.
            throw new IllegalStateException(
                    "No ADR 0020 delivery path is wired for %s; a campaign cannot expand into one".formatted(channel));
        }
        EngagementPolicy policy = engagement.resolvePolicy(tenantId, campaign.brandId());

        UUID cursor = campaigns.lastRecipientAccountId(tenantId, campaignId).orElse(null);
        List<SnapshotMemberRow> members =
                audiences.includedMembersAfter(tenantId, campaign.snapshotId(), cursor, batchSize);

        if (members.isEmpty()) {
            return complete(tenantId, campaign, now);
        }

        Map<String, String> bodies =
                messages.templateBodies(tenantId, campaign.brandId(), campaign.templateKey(), channel.name());

        long reservation = 0;
        for (SnapshotMemberRow member : members) {
            reservation += estimator.perRecipientCostMinor(
                    channel, bodies.get(localeOf(member)), policy.smsPricePerSegmentMinor());
        }

        int sequence = campaigns.nextBatchSequence(tenantId, campaignId);
        BatchClaim claim = campaigns.claimBatch(
                tenantId, campaignId, campaign.snapshotId(), sequence, members.size(), reservation, now);

        switch (claim) {
            case ALREADY_CLAIMED -> {
                // Another worker, or this one on a retry, already has this sequence.
                // Doing nothing is correct: its recipient rows are either written or
                // about to be, and writing them again would be the second message
                // the idempotency key exists to prevent.
                log.debug("Campaign {} batch {} was already claimed", campaignId, sequence);
                return BatchOutcome.replayed(sequence);
            }
            case REFUSED -> {
                // The reservation did not fit. Halted rather than trimmed to fit:
                // an approver signed off a ceiling, and quietly sending as much as
                // fits under it is a different campaign from the one approved.
                campaigns.halt(
                        tenantId,
                        campaignId,
                        CampaignStatus.SENDING,
                        CampaignStatus.HALTED_BUDGET,
                        "The next batch of %d recipients would exceed the cost ceiling or the "
                                        .formatted(members.size())
                                + "recipient cap",
                        now);
                log.warn(
                        "Campaign {} halted at its ceiling after {} reserved",
                        campaignId,
                        campaign.reservedCostMinor());
                return BatchOutcome.haltedAtCeiling(sequence);
            }
            case RESERVED -> {
                // Fall through to the expansion below.
            }
        }

        // Held to the next open boundary rather than dropped. A marketer reading a
        // delivered count cannot distinguish a quiet-hour drop from a suppression,
        // so the message is scheduled and the recipient row records the deferral.
        boolean quiet = policy.isQuiet(now);
        Instant deliverAt = quiet ? policy.nextOpenBoundary(now) : now;

        int base = campaigns.recipientCount(tenantId, campaignId);
        int queued = 0;
        int refused = 0;
        long spent = 0;

        for (int offset = 0; offset < members.size(); offset++) {
            SnapshotMemberRow member = members.get(offset);
            UUID accountId = member.customerAccountId();

            Optional<RefusalReason> refusal = eligibility.refusalFor(
                    tenantId,
                    campaign.brandId(),
                    accountId,
                    channel,
                    campaign.consentPurpose(),
                    policy,
                    audiences.isReachableAccount(tenantId, accountId),
                    now);

            if (refusal.isPresent()) {
                campaigns.recordRecipient(
                        tenantId,
                        campaignId,
                        accountId,
                        base + offset,
                        "REFUSED",
                        null,
                        refusal.get(),
                        "Refused at send after the snapshot was built",
                        null,
                        now);
                refused++;
                continue;
            }

            // The idempotency key ADR 0044 names. Derived rather than random, so a
            // replayed batch produces the same key and the delivery path collapses
            // it onto the intent that already exists.
            String idempotencyKey = "campaign:%s:%s".formatted(campaignId, accountId);

            UUID notificationId = messages.enqueue(new MarketingMessage(
                    tenantId,
                    campaign.brandId(),
                    accountId,
                    channel.name(),
                    campaign.templateKey(),
                    campaign.consentPurpose(),
                    campaignId,
                    idempotencyKey,
                    Map.of(),
                    deliverAt,
                    null));

            campaigns.recordRecipient(
                    tenantId,
                    campaignId,
                    accountId,
                    base + offset,
                    quiet ? "DEFERRED" : "QUEUED",
                    notificationId,
                    null,
                    null,
                    quiet ? deliverAt : null,
                    now);

            // The frequency ledger, written against the moment the message will
            // land rather than the moment it was expanded. A message held overnight
            // counts towards tomorrow's window, which is the one the customer will
            // experience it in.
            engagement.recordSend(
                    tenantId,
                    campaign.brandId(),
                    accountId,
                    channel.name(),
                    "CAMPAIGN",
                    campaignId,
                    notificationId,
                    deliverAt);

            spent += estimator.perRecipientCostMinor(
                    channel, bodies.get(localeOf(member)), policy.smsPricePerSegmentMinor());
            queued++;
        }

        campaigns.recordSpend(tenantId, campaignId, spent, now);
        return new BatchOutcome(sequence, members.size(), queued, refused, spent, false, false, quiet, null);
    }

    private BatchOutcome complete(UUID tenantId, CampaignRow campaign, Instant now) {
        Map<String, Integer> counts = campaigns.recipientCounts(tenantId, campaign.id());
        int refused = counts.getOrDefault("REFUSED", 0);

        // PARTIALLY_SENT whenever anybody was refused. It is not a failure and it is
        // not the same as SENT: the difference is exactly what a marketer needs to
        // see to go and read the refusal reasons.
        CampaignStatus terminal = refused > 0 ? CampaignStatus.PARTIALLY_SENT : CampaignStatus.SENT;
        campaigns.transition(tenantId, campaign.id(), CampaignStatus.SENDING, terminal, now);

        return BatchOutcome.completed(terminal);
    }

    private static String localeOf(SnapshotMemberRow member) {
        // The locale frozen onto the snapshot member, not today's. The estimate an
        // approver saw was computed from these, and pricing the send from a
        // different set would make the two disagree for no reason a marketer could
        // discover.
        return member.localeAtEvaluation() == null ? "ru" : member.localeAtEvaluation();
    }

    /**
     * What one expansion call did.
     *
     * @param deferred whether the batch landed inside quiet hours and was held to
     *                 the next open boundary. A campaign released at 20:50 finishes
     *                 the following morning and its report spans two days
     */
    public record BatchOutcome(
            int batchSequence,
            int claimed,
            int queued,
            int refused,
            long spentMinor,
            boolean finished,
            boolean haltedAtCeiling,
            boolean deferred,
            @Nullable CampaignStatus terminalStatus) {

        static BatchOutcome replayed(int sequence) {
            return new BatchOutcome(sequence, 0, 0, 0, 0, false, false, false, null);
        }

        static BatchOutcome haltedAtCeiling(int sequence) {
            return new BatchOutcome(sequence, 0, 0, 0, 0, true, true, false, CampaignStatus.HALTED_BUDGET);
        }

        static BatchOutcome completed(CampaignStatus terminal) {
            return new BatchOutcome(-1, 0, 0, 0, 0, true, false, false, terminal);
        }

        static BatchOutcome notSending(CampaignStatus status) {
            return new BatchOutcome(-1, 0, 0, 0, 0, status.isTerminal(), false, false, status);
        }
    }
}
