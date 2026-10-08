package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.marketing.api.CampaignMessagePort;
import uz.horecaos.platform.marketing.api.CampaignMessagePort.MarketingMessage;
import uz.horecaos.platform.marketing.api.MarketingConfigurationKeys;
import uz.horecaos.platform.marketing.api.ScenarioParticipantStopped;
import uz.horecaos.platform.marketing.api.ScenarioStepDecided;
import uz.horecaos.platform.marketing.application.ContactPolicyService.ContactDecision;
import uz.horecaos.platform.marketing.application.ContactPolicyService.ContactRequest;
import uz.horecaos.platform.marketing.domain.CampaignStatus;
import uz.horecaos.platform.marketing.domain.EngagementPolicy;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.domain.RefusalReason;
import uz.horecaos.platform.marketing.domain.ScenarioChannel;
import uz.horecaos.platform.marketing.domain.ScenarioCondition;
import uz.horecaos.platform.marketing.domain.ScenarioOutcome;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAudienceStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore.CampaignRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcOfferStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcOfferStore.OfferRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore.NewDecision;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore.ParticipantRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore.StepRow;
import uz.horecaos.platform.ordering.api.OrderDirectory;
import uz.horecaos.platform.tenancy.api.ConfigurationResolver;

/**
 * Action selection: for one due guest, decide what happens at their current step, and
 * write down why (ADR 0112 Decision 2).
 *
 * <p>"Event, condition, action" as one method. For a guest whose wait has elapsed it asks,
 * in this order and stopping at the first that says no: does the scenario still apply to
 * this guest (the stop and continuation conditions, the offer's window), may this guest be
 * contacted at all (consent, suppression, the platform cap, the brand's own contact
 * policy), does this step conflict with another live scenario's offer, does a broadcast due
 * for the same guest on the same channel outrank it, and can its cost be reserved. Exactly
 * one {@code scenario_step_decisions} row is written for the answer: {@code SENT} with the
 * handle the delivery path gave, or {@code BLOCKED} with a {@link RefusalReason} and a
 * sentence.
 *
 * <p><strong>A block is a deferral or a stop, never a silent drop.</strong> A cap, a lost
 * priority race and a conflict leave the guest on the same step with a later wait. A
 * consent that has gone, a suppression, an offer no longer in force, a stop condition, a
 * channel that can no longer deliver: those end the guest's run, with the outcome and the
 * reason, and the next guest is unaffected.
 *
 * <p><strong>Each guest is its own transaction</strong>, taken with {@code FOR UPDATE SKIP
 * LOCKED}, so a fault deciding one guest rolls back that guest alone, two replicas partition
 * the due guests instead of racing for them, and the once-per-step index is the backstop
 * for the case where they do anyway. Nothing here is called from inside a business
 * transaction of another module; the scheduler calls it.
 *
 * <p>Dispatch is the same call a broadcast makes: {@code CampaignMessagePort#enqueue}, an
 * ADR 0020 intent keyed by campaign, guest and step, so a redelivered tick finds the message
 * it already made. A scenario writes no private message queue and no private contact log.
 */
@Service
public class ScenarioRunner {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunner.class);

    /** How long a step that conflicts with another scenario's fresh offer waits before asking again. */
    static final Duration CONFLICT_DEFERRAL = Duration.ofHours(6);

    /** How long a step that lost a priority race waits: until the broadcast it lost to has likely reached the guest. */
    static final Duration PRIORITY_DEFERRAL = Duration.ofMinutes(15);

    /** How recently another scenario must have handed this guest an offer for this one to defer. */
    static final Duration OFFER_FRESHNESS = Duration.ofHours(24);

    /** Guests decided per scenario per pass. A pass is repeated on every tick. */
    static final int GUESTS_PER_PASS = 100;

    private final JdbcCampaignStore campaigns;
    private final JdbcScenarioStore scenarios;
    private final JdbcOfferStore offers;
    private final JdbcAudienceStore audiences;
    private final JdbcEngagementStore engagement;
    private final MarketingEligibility eligibility;
    private final ContactPolicyService contactPolicy;
    private final CampaignMessagePort messages;
    private final CampaignCostEstimator estimator;
    private final PresentedOfferService presented;
    private final OrderDirectory orders;
    private final ConfigurationResolver configuration;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final Clock clock;

    public ScenarioRunner(
            JdbcCampaignStore campaigns,
            JdbcScenarioStore scenarios,
            JdbcOfferStore offers,
            JdbcAudienceStore audiences,
            JdbcEngagementStore engagement,
            MarketingEligibility eligibility,
            ContactPolicyService contactPolicy,
            CampaignMessagePort messages,
            CampaignCostEstimator estimator,
            PresentedOfferService presented,
            OrderDirectory orders,
            ConfigurationResolver configuration,
            ApplicationEventPublisher events,
            TransactionTemplate transactions,
            Clock clock) {
        this.campaigns = campaigns;
        this.scenarios = scenarios;
        this.offers = offers;
        this.audiences = audiences;
        this.engagement = engagement;
        this.eligibility = eligibility;
        this.contactPolicy = contactPolicy;
        this.messages = messages;
        this.estimator = estimator;
        this.presented = presented;
        this.orders = orders;
        this.configuration = configuration;
        this.events = events;
        this.transactions = transactions;
        this.clock = clock;
    }

    /** Every scenario that is sending, one pass each. */
    public int sweepOnce() {
        int decided = 0;
        for (JdbcCampaignStore.CampaignRef ref : campaigns.sendingScenarios(100)) {
            try {
                decided += processDue(ref.tenantId(), ref.campaignId());
            } catch (RuntimeException failure) {
                // One scenario's fault must not stop the others'.
                log.error("Scenario {} could not be processed", ref.campaignId(), failure);
            }
        }
        return decided;
    }

    /**
     * One pass over one scenario's due guests.
     *
     * @return how many guests were decided
     */
    public int processDue(UUID tenantId, UUID campaignId) {
        int decided = 0;
        for (int guest = 0; guest < GUESTS_PER_PASS; guest++) {
            Boolean any = transactions.execute(status -> decideNextDueGuest(tenantId, campaignId));
            if (!Boolean.TRUE.equals(any)) {
                break;
            }
            decided++;
        }
        return decided;
    }

    /** @return whether there was a due guest to decide */
    private boolean decideNextDueGuest(UUID tenantId, UUID campaignId) {
        Instant now = clock.instant();
        CampaignRow campaign = campaigns.find(tenantId, campaignId).orElse(null);
        if (campaign == null || !campaign.isScenario() || campaign.status() != CampaignStatus.SENDING) {
            return false;
        }
        List<ParticipantRow> due = scenarios.lockDue(tenantId, campaignId, now, 1);
        if (due.isEmpty()) {
            return false;
        }
        List<StepRow> steps = scenarios.steps(tenantId, campaignId);
        decide(campaign, steps, due.getFirst(), now);
        return true;
    }

    // ------------------------------------------------------------ the decision

    private void decide(CampaignRow campaign, List<StepRow> steps, ParticipantRow guest, Instant now) {
        UUID tenantId = campaign.tenantId();
        UUID account = guest.customerAccountId();
        StepRow step = steps.get(guest.currentStepSequence() - 1);
        ScenarioChannel channel = ScenarioChannel.valueOf(step.channel());

        // 1. Does the scenario still apply to this guest at all?
        if (ScenarioCondition.Stop.valueOf(step.stopCondition()) == ScenarioCondition.Stop.ORDER_PLACED_SINCE_ENTRY
                && orderedSince(campaign, account, guest.enteredAt())) {
            stop(
                    campaign,
                    guest,
                    step,
                    channel,
                    ScenarioOutcome.STOPPED_BY_CONDITION,
                    RefusalReason.SCENARIO_STOPPED,
                    "The stop condition is met: the guest placed an order after entering the scenario",
                    now);
            return;
        }
        if (ScenarioCondition.Continuation.valueOf(step.continuationCondition())
                        == ScenarioCondition.Continuation.NO_ORDER_SINCE_ENTRY
                && orderedSince(campaign, account, guest.enteredAt())) {
            stop(
                    campaign,
                    guest,
                    step,
                    channel,
                    ScenarioOutcome.STOPPED_BY_CONDITION,
                    RefusalReason.SCENARIO_STOPPED,
                    "The continuation condition is not met: the guest placed an order after entering the scenario",
                    now);
            return;
        }

        // 2. Is the offer still in force?
        OfferRow offer = null;
        if (step.offerId() != null) {
            offer = offers.find(tenantId, step.offerId()).orElse(null);
            // Honoured, not merely "the newest": a newer version published since this scenario
            // was approved supersedes the one it carries for new authors and does not end it.
            if (offer == null || !offer.honouredAt(now)) {
                stop(
                        campaign,
                        guest,
                        step,
                        channel,
                        ScenarioOutcome.STOPPED_BY_CONDITION,
                        RefusalReason.SCENARIO_STOPPED,
                        offer == null
                                ? "The offer this step references no longer exists"
                                : "The offer this step references is %s and not in force".formatted(offer.status()),
                        now);
                return;
            }
        }

        Optional<MarketingChannel> messaging = channel.messaging();
        EngagementPolicy brandPolicy = engagement.resolvePolicy(tenantId, campaign.brandId());
        boolean reachable = audiences.isReachableAccount(tenantId, account);

        // 3. May this guest be contacted? Consent, suppression and the platform cap are
        //    MarketingEligibility's, asked the same way a broadcast asks them.
        if (messaging.isPresent()) {
            CampaignMessagePort.Wiring wiring = messages.wiring(
                    tenantId, campaign.brandId(), messaging.get().name(), CampaignMessagePort.PURPOSE_MARKETING);
            if (!wiring.isWired()) {
                stop(
                        campaign,
                        guest,
                        step,
                        channel,
                        ScenarioOutcome.STOPPED_BY_CONDITION,
                        RefusalReason.SCENARIO_STOPPED,
                        "%s can no longer deliver for this brand (%s)".formatted(channel, wiring.reason()),
                        now);
                return;
            }
            Optional<RefusalReason> refusal = eligibility.refusalFor(
                    tenantId,
                    campaign.brandId(),
                    account,
                    messaging.get(),
                    campaign.consentPurpose(),
                    brandPolicy,
                    reachable,
                    now);
            if (refusal.isPresent()) {
                handleRefusal(campaign, guest, step, channel, refusal.get(), now);
                return;
            }
        } else if (!reachable) {
            stop(
                    campaign,
                    guest,
                    step,
                    channel,
                    ScenarioOutcome.STOPPED_BY_CONDITION,
                    RefusalReason.ACCOUNT_NOT_ACTIVE,
                    "The guest's account is no longer active",
                    now);
            return;
        }

        // 4. The brand's own contact policy: its caps and its quiet hours, per channel and purpose.
        Instant deliverAt = now;
        String note = null;
        if (messaging.isPresent()) {
            ContactDecision decision = contactPolicy.decide(
                    new ContactRequest(
                            tenantId, campaign.brandId(), account, messaging.get(), campaign.consentPurpose()),
                    now);
            if (!decision.allowed()) {
                block(
                        campaign,
                        guest,
                        step,
                        channel,
                        decision.reason(),
                        decision.reasonText(),
                        decision.deferUntil(),
                        now);
                return;
            }
            deliverAt = decision.deliverAt() == null ? now : decision.deliverAt();
            note = decision.reasonText();
        }

        // 5. Does this step collide with an offer another live scenario just gave this guest?
        if (step.offerId() != null
                && scenarios.offerSentElsewhereSince(tenantId, account, campaign.id(), now.minus(OFFER_FRESHNESS))) {
            block(
                    campaign,
                    guest,
                    step,
                    channel,
                    RefusalReason.SCENARIO_CONFLICT,
                    "Another live scenario handed this guest an offer within the last %d hours"
                            .formatted(OFFER_FRESHNESS.toHours()),
                    now.plus(CONFLICT_DEFERRAL),
                    now);
            return;
        }

        // 6. Does a broadcast due for this guest on this channel outrank it?
        if (messaging.isPresent()) {
            Optional<String> rival = scenarios.pendingBroadcastPurpose(
                    tenantId, campaign.brandId(), account, messaging.get().name());
            if (rival.isPresent() && !outranks(tenantId, campaign.consentPurpose(), rival.get())) {
                block(
                        campaign,
                        guest,
                        step,
                        channel,
                        RefusalReason.SCENARIO_PRIORITY_LOST,
                        "A broadcast about %s is also due for this guest on %s and ranks at or above this scenario's purpose (%s)"
                                .formatted(rival.get(), channel, campaign.consentPurpose()),
                        now.plus(PRIORITY_DEFERRAL),
                        now);
                return;
            }
        }

        // 7. Dispatch.
        UUID handle = null;
        if (messaging.isPresent()) {
            handle = dispatchMessage(campaign, guest, step, messaging.get(), deliverAt, now);
            if (handle == null) {
                return; // dispatchMessage wrote the block or the halt
            }
        } else if (channel == ScenarioChannel.IN_APP) {
            presented.present(
                    tenantId,
                    campaign.brandId(),
                    campaign.id(),
                    java.util.Objects.requireNonNull(step.offerId(), "an in-app step always names an offer"),
                    account,
                    PresentedOfferService.STOREFRONT,
                    now);
        }

        recordSent(campaign, guest, step, channel, handle, note, now);
        advance(campaign, guest, steps, now);
    }

    /** The step is sent: one SENT row, the frequency ledger, and the event, together. */
    private void recordSent(
            CampaignRow campaign,
            ParticipantRow guest,
            StepRow step,
            ScenarioChannel channel,
            @Nullable UUID handle,
            @Nullable String note,
            Instant now) {
        boolean first = scenarios.insertDecision(new NewDecision(
                Ids.newId(),
                campaign.tenantId(),
                campaign.brandId(),
                campaign.id(),
                guest.customerAccountId(),
                step.sequence(),
                "SENT",
                null,
                note,
                channel.name(),
                handle,
                now));
        if (!first) {
            // The database says this step was already sent to this guest: a second
            // runner, or a replayed tick. The guest moves on; nothing else happens.
            return;
        }
        if (handle != null && channel.messaging().isPresent()) {
            // Written against the moment the message will land, as a broadcast's is, and
            // keyed by campaign and step so one guest's second step is its own ledger row.
            engagement.recordSend(
                    campaign.tenantId(),
                    campaign.brandId(),
                    guest.customerAccountId(),
                    channel.name(),
                    "CAMPAIGN",
                    stepSource(campaign.id(), step.sequence()),
                    handle,
                    now);
        }
        events.publishEvent(new ScenarioStepDecided(
                Ids.newId(),
                campaign.tenantId(),
                campaign.brandId(),
                campaign.id(),
                guest.customerAccountId(),
                step.sequence(),
                "SENT",
                null,
                now));
    }

    private @Nullable UUID dispatchMessage(
            CampaignRow campaign,
            ParticipantRow guest,
            StepRow step,
            MarketingChannel channel,
            Instant deliverAt,
            Instant now) {
        UUID tenantId = campaign.tenantId();
        long cost = stepCost(campaign, step, channel, brandPrice(campaign));
        if (cost > 0 && !campaigns.reserveStepCost(tenantId, campaign.id(), cost, now)) {
            // The ceiling an approver signed off is not exceeded to finish a step. The
            // campaign halts, as a broadcast does, and the guest is told why in a row.
            campaigns.halt(
                    tenantId,
                    campaign.id(),
                    CampaignStatus.SENDING,
                    CampaignStatus.HALTED_BUDGET,
                    "Step %d would exceed the cost ceiling".formatted(step.sequence()),
                    now);
            writeBlock(
                    campaign,
                    guest,
                    step,
                    ScenarioChannel.valueOf(step.channel()),
                    RefusalReason.SCENARIO_STOPPED,
                    "The scenario's cost ceiling would be exceeded by this step, so the scenario halted",
                    now);
            return null;
        }
        String key = "scenario:%s:%s:%d".formatted(campaign.id(), guest.customerAccountId(), step.sequence());
        UUID handle = messages.enqueue(new MarketingMessage(
                tenantId,
                campaign.brandId(),
                guest.customerAccountId(),
                channel.name(),
                step.templateKey(),
                campaign.consentPurpose(),
                campaign.id(),
                key,
                Map.of(),
                deliverAt,
                null));
        if (handle == null) {
            // Wired a moment ago and nothing came back: a deployment gap. Asked again soon
            // rather than dropped, and said so.
            block(
                    campaign,
                    guest,
                    step,
                    ScenarioChannel.valueOf(step.channel()),
                    RefusalReason.SCENARIO_STOPPED,
                    "The delivery path accepted no message for this step",
                    now.plus(Duration.ofHours(1)),
                    now);
        }
        return handle;
    }

    private long stepCost(CampaignRow campaign, StepRow step, MarketingChannel channel, @Nullable Long price) {
        if (!channel.carriesMarginalCost() || price == null) {
            return 0;
        }
        // The dearest locale, because a reservation at the lower bound would let a step of
        // long messages overrun a ceiling the arithmetic said it fitted under.
        return messages
                .templateBodies(campaign.tenantId(), campaign.brandId(), step.templateKey(), channel.name())
                .values()
                .stream()
                .mapToLong(body -> estimator.perRecipientCostMinor(channel, body, price))
                .max()
                .orElse(0L);
    }

    private @Nullable Long brandPrice(CampaignRow campaign) {
        return engagement.resolvePolicy(campaign.tenantId(), campaign.brandId()).smsPricePerSegmentMinor();
    }

    private void handleRefusal(
            CampaignRow campaign,
            ParticipantRow guest,
            StepRow step,
            ScenarioChannel channel,
            RefusalReason refusal,
            Instant now) {
        switch (refusal) {
            case CONSENT_WITHHELD ->
                stop(
                        campaign,
                        guest,
                        step,
                        channel,
                        ScenarioOutcome.STOPPED_BY_CONSENT_WITHDRAWN,
                        refusal,
                        "Consent for %s on %s is no longer granted".formatted(campaign.consentPurpose(), channel),
                        now);
            case SUPPRESSED ->
                stop(
                        campaign,
                        guest,
                        step,
                        channel,
                        ScenarioOutcome.STOPPED_BY_SUPPRESSION,
                        refusal,
                        "An active suppression covers this guest on %s".formatted(channel),
                        now);
            case ACCOUNT_NOT_ACTIVE ->
                stop(
                        campaign,
                        guest,
                        step,
                        channel,
                        ScenarioOutcome.STOPPED_BY_CONDITION,
                        refusal,
                        "The guest's account is no longer active",
                        now);
            case FREQUENCY_CAP_REACHED ->
                block(
                        campaign,
                        guest,
                        step,
                        channel,
                        refusal,
                        "The platform's frequency cap is reached for this guest across every channel",
                        now.plus(Duration.ofDays(1)),
                        now);
            case NO_VERIFIED_ENDPOINT ->
                block(
                        campaign,
                        guest,
                        step,
                        channel,
                        refusal,
                        "The guest has no verified %s contact to reach".formatted(channel),
                        now.plus(Duration.ofDays(1)),
                        now);
            default ->
                block(
                        campaign,
                        guest,
                        step,
                        channel,
                        refusal,
                        "Refused: " + refusal,
                        now.plus(Duration.ofDays(1)),
                        now);
        }
    }

    // ------------------------------------------------------------- outcomes

    /** A block that waits: one BLOCKED row, the guest held on this step until {@code retryAt}. */
    private void block(
            CampaignRow campaign,
            ParticipantRow guest,
            StepRow step,
            ScenarioChannel channel,
            @Nullable RefusalReason reason,
            @Nullable String text,
            @Nullable Instant retryAt,
            Instant now) {
        RefusalReason actual = reason == null ? RefusalReason.SCENARIO_STOPPED : reason;
        writeBlock(campaign, guest, step, channel, actual, text == null ? "Blocked: " + actual : text, now);
        scenarios.defer(
                campaign.tenantId(),
                campaign.id(),
                guest.customerAccountId(),
                retryAt == null ? now.plus(Duration.ofDays(1)) : retryAt,
                now);
    }

    /** A block that ends the guest's run: the BLOCKED row, the outcome, and the event. */
    private void stop(
            CampaignRow campaign,
            ParticipantRow guest,
            StepRow step,
            ScenarioChannel channel,
            ScenarioOutcome outcome,
            RefusalReason reason,
            String text,
            Instant now) {
        writeBlock(campaign, guest, step, channel, reason, text, now);
        finish(campaign, guest, outcome, now);
    }

    private void writeBlock(
            CampaignRow campaign,
            ParticipantRow guest,
            StepRow step,
            ScenarioChannel channel,
            RefusalReason reason,
            String text,
            Instant now) {
        scenarios.insertDecision(new NewDecision(
                Ids.newId(),
                campaign.tenantId(),
                campaign.brandId(),
                campaign.id(),
                guest.customerAccountId(),
                step.sequence(),
                "BLOCKED",
                reason.name(),
                text.length() > 500 ? text.substring(0, 500) : text,
                channel.name(),
                null,
                now));
        events.publishEvent(new ScenarioStepDecided(
                Ids.newId(),
                campaign.tenantId(),
                campaign.brandId(),
                campaign.id(),
                guest.customerAccountId(),
                step.sequence(),
                "BLOCKED",
                reason.name(),
                now));
    }

    private void advance(CampaignRow campaign, ParticipantRow guest, List<StepRow> steps, Instant now) {
        int next = guest.currentStepSequence() + 1;
        if (next > steps.size()) {
            finish(campaign, guest, ScenarioOutcome.COMPLETED, now);
            return;
        }
        scenarios.advance(
                campaign.tenantId(),
                campaign.id(),
                guest.customerAccountId(),
                next,
                now.plusSeconds(steps.get(next - 1).waitAfterPreviousSeconds()),
                now);
    }

    private void finish(CampaignRow campaign, ParticipantRow guest, ScenarioOutcome outcome, Instant now) {
        if (scenarios.finish(campaign.tenantId(), campaign.id(), guest.customerAccountId(), outcome.name(), now)) {
            events.publishEvent(new ScenarioParticipantStopped(
                    Ids.newId(),
                    campaign.tenantId(),
                    campaign.brandId(),
                    campaign.id(),
                    guest.customerAccountId(),
                    outcome.name(),
                    now));
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean orderedSince(CampaignRow campaign, UUID account, Instant since) {
        List<OrderDirectory.RecentOrder> recent =
                orders.recentForCustomer(campaign.tenantId(), campaign.brandId(), account, 1);
        return !recent.isEmpty() && recent.getFirst().placedAt().isAfter(since);
    }

    /**
     * Whether the scenario's purpose strictly outranks a rival broadcast's.
     *
     * <p>The tenant's {@code marketing.channel.priority.order} (ADR 0030): purposes, most
     * important first. A purpose not named ranks below every one that is, and a tie, which
     * includes the case of no ranking at all, goes to the broadcast: it is already under
     * way, and the scenario step is the one that can wait fifteen minutes without anybody
     * noticing.
     */
    boolean outranks(UUID tenantId, String scenarioPurpose, String broadcastPurpose) {
        String configured = configuration
                .resolve(MarketingConfigurationKeys.CHANNEL_PRIORITY_ORDER, ResourceScope.tenant(tenantId))
                .value();
        List<String> ranking = configured == null || configured.isBlank()
                ? List.of()
                : Arrays.stream(configured.split(","))
                        .map(String::strip)
                        .filter(word -> !word.isEmpty())
                        .toList();
        int scenario = rank(ranking, scenarioPurpose);
        int broadcast = rank(ranking, broadcastPurpose);
        return scenario < broadcast;
    }

    private static int rank(List<String> ranking, String purpose) {
        int index = ranking.indexOf(purpose);
        return index < 0 ? Integer.MAX_VALUE : index;
    }

    static UUID stepSource(UUID campaignId, int step) {
        return UUID.nameUUIDFromBytes(
                ("scenario-step:" + campaignId + ":" + step).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
