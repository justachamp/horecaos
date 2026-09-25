package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.audit.api.AuditClass;
import uz.horecaos.platform.audit.api.AuditFact;
import uz.horecaos.platform.audit.api.AuditRecorder;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.marketing.api.CampaignMessagePort;
import uz.horecaos.platform.marketing.api.CampaignMessagePort.MarketingMessage;
import uz.horecaos.platform.marketing.domain.EngagementPolicy;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.domain.RefusalReason;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAudienceStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRuleStore.AutomationRuleRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAutomationRunStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;

/**
 * Fires one automation rule for one customer (gap-map row 6.5, ADR 0044
 * Triggers).
 *
 * <p>Every sweep in {@link AutomationSweepService} funnels through {@link
 * #attemptFire}, which is deliberately the campaign send path's own shape —
 * claim the guard, check the same five subtractions {@link MarketingEligibility}
 * already applies to a campaign recipient, defer inside quiet hours rather than
 * drop, enqueue through the one {@link CampaignMessagePort} both campaigns and
 * automations share, and write the frequency ledger — because a trigger firing
 * counts towards the same cross-channel cap a campaign does (ADR 0044) and
 * "a second, parallel send path" is exactly the drift this class exists to
 * avoid.
 *
 * <p>The automation rule's own id stands in for {@code campaignId} on {@link
 * MarketingMessage} — {@code notifications} stores it as an opaque {@code
 * subject_id} under {@code subject_type = "MarketingCampaign"} with no foreign
 * key back to {@code marketing.campaigns} (see that table's own migration), and
 * {@code CampaignFeedbackService#recordBlocked}'s own doc is explicit that "a
 * campaign id this tenant does not own" is answered as "no block recorded,
 * nothing paused" rather than thrown. Reusing the port this way costs the
 * block-rate guard for automation sends — a rule id names no {@code
 * marketing.campaigns} row for {@code CampaignBlockRateMonitor} to pause — which
 * is accepted rather than building a second delivery adapter for one missing
 * safety net.
 */
@Service
public class AutomationFiringService {

    private final JdbcAutomationRunStore runs;
    private final JdbcAudienceStore audiences;
    private final JdbcEngagementStore engagement;
    private final MarketingEligibility eligibility;
    private final CampaignMessagePort messages;
    private final AuditRecorder audit;
    private final Clock clock;

    public AutomationFiringService(
            JdbcAutomationRunStore runs,
            JdbcAudienceStore audiences,
            JdbcEngagementStore engagement,
            MarketingEligibility eligibility,
            CampaignMessagePort messages,
            AuditRecorder audit,
            Clock clock) {
        this.runs = runs;
        this.audiences = audiences;
        this.engagement = engagement;
        this.eligibility = eligibility;
        this.messages = messages;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * @param subjectId the cart id for {@code CART_ABANDONMENT}, null for a
     *                  trigger with no such domain-fact subject
     * @param alreadyConvertedReason non-null when the caller has already
     *                               established, before calling this, that the
     *                               domain fact no longer holds — "the customer
     *                               ordered before the send" (ADR 0044: "cancelled
     *                               if the cart converts first"). The guard key is
     *                               still claimed and recorded {@code CANCELLED}
     *                               so the same cart is never reconsidered
     * @return what happened, for the sweep's own counters and tests
     */
    @Transactional
    public FireOutcome attemptFire(
            AutomationRuleRow rule,
            UUID customerAccountId,
            String guardKey,
            @Nullable UUID subjectId,
            Map<String, String> variables,
            @Nullable String alreadyConvertedReason) {

        Instant now = clock.instant();
        UUID runId = Ids.newId();

        boolean claimed = runs.claim(
                runId,
                rule.tenantId(),
                rule.brandId(),
                rule.id(),
                customerAccountId,
                rule.triggerType(),
                guardKey,
                subjectId,
                now);
        if (!claimed) {
            return FireOutcome.ALREADY_GUARDED;
        }

        if (alreadyConvertedReason != null) {
            runs.markCancelled(rule.tenantId(), runId, alreadyConvertedReason);
            return FireOutcome.CANCELLED;
        }

        MarketingChannel channel = MarketingChannel.valueOf(rule.channel());
        if (!messages.isWired(channel.name())) {
            runs.markRefused(rule.tenantId(), runId, "CHANNEL_NOT_WIRED");
            return FireOutcome.CHANNEL_NOT_WIRED;
        }

        EngagementPolicy policy = engagement.resolvePolicy(rule.tenantId(), rule.brandId());
        boolean reachable = audiences.isReachableAccount(rule.tenantId(), customerAccountId);
        Optional<RefusalReason> refusal = eligibility.refusalFor(
                rule.tenantId(),
                rule.brandId(),
                customerAccountId,
                channel,
                rule.consentPurpose(),
                policy,
                reachable,
                now);
        if (refusal.isPresent()) {
            runs.markRefused(rule.tenantId(), runId, refusal.get().name());
            return FireOutcome.REFUSED;
        }

        boolean quiet = policy.isQuiet(now);
        Instant deliverAt = quiet ? policy.nextOpenBoundary(now) : now;
        String idempotencyKey = "automation:%s:%s:%s".formatted(rule.id(), customerAccountId, guardKey);

        UUID notificationId = messages.enqueue(new MarketingMessage(
                rule.tenantId(),
                rule.brandId(),
                customerAccountId,
                channel.name(),
                rule.templateKey(),
                rule.consentPurpose(),
                rule.id(),
                idempotencyKey,
                variables,
                deliverAt,
                null));
        if (notificationId == null) {
            // isWired said yes and the port still returned nothing wired: a
            // deployment gap the guard must not silently paper over by leaving
            // the row PENDING (V0415's own CHECK refuses that at commit anyway).
            runs.markRefused(rule.tenantId(), runId, "CHANNEL_NOT_WIRED");
            return FireOutcome.CHANNEL_NOT_WIRED;
        }

        runs.markFired(rule.tenantId(), runId, notificationId, deliverAt);
        engagement.recordSend(
                rule.tenantId(),
                rule.brandId(),
                customerAccountId,
                channel.name(),
                "AUTOMATION_RULE",
                rule.id(),
                notificationId,
                deliverAt);

        audit.record(AuditFact.of("MARKETING_AUTOMATION_FIRED", AuditClass.BUSINESS)
                .by(ActorRef.systemJob("marketing-automation-sweep"))
                .at(ResourceScope.brand(rule.tenantId(), rule.brandId()))
                .target("MarketingAutomationRule", rule.id())
                .outcome(AuditFact.Outcome.SUCCEEDED)
                .because("Trigger " + rule.triggerType() + " fired for guard key " + guardKey)
                .changed(Map.of("customerAccountId", customerAccountId.toString(), "channel", channel.name()))
                .correlatedBy(runId.toString())
                .occurredAt(now)
                .build());

        return FireOutcome.FIRED;
    }

    public enum FireOutcome {
        FIRED,
        REFUSED,
        CANCELLED,
        CHANNEL_NOT_WIRED,
        ALREADY_GUARDED
    }
}
