package uz.horecaos.platform.marketing.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
import uz.horecaos.platform.audit.api.ChangeDocuments;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.marketing.api.CampaignMessagePort;
import uz.horecaos.platform.marketing.domain.CampaignStatus;
import uz.horecaos.platform.marketing.domain.EngagementPolicy;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.domain.ScenarioChannel;
import uz.horecaos.platform.marketing.domain.ScenarioCondition;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore.CampaignRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore.NewCampaign;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcEngagementStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcOfferStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcOfferStore.OfferRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore.NewStep;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore.StepRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Authoring a scenario campaign and holding it to what ADR 0112 says a version is.
 *
 * <p>A scenario is a campaign row ({@code kind = SCENARIO}) with an ordered list of
 * steps, so everything ADR 0044 built for a broadcast applies to it unchanged: it is
 * estimated against a snapshot, submitted, approved by somebody who is not its author,
 * and halted at its cost ceiling. This class adds only what a per-guest sequence needs
 * that a one-off send does not.
 *
 * <p><strong>A published version is immutable.</strong> The steps may be replaced while
 * the campaign is a {@code DRAFT} and never afterwards; changing an approved scenario is
 * {@link #revise}, which makes a new draft that points at it and needs its own approval,
 * and starting the revision halts the version it supersedes. That is the rule ADR 0044
 * already enforces for a broadcast's audience snapshot, applied to something with many
 * steps instead of one send, and its cost is the one the record names: a scenario needs an
 * approval at first publication and at every version after.
 *
 * <p><strong>No step can invent a discount.</strong> A step may reference an
 * {@code marketing.offers} version, which references one promotion or accrual rule; the
 * step itself carries a channel, a wait, two conditions from closed sets and a template
 * key, and there is no field for an amount, a percentage or a number of points.
 */
@Service
public class ScenarioService {

    /** More steps than this is a program, not a scenario. */
    static final int MAX_STEPS = 10;

    /** A guest is never held on one wait for longer than a quarter. */
    static final Duration MAX_WAIT = Duration.ofDays(90);

    private final JdbcCampaignStore campaigns;
    private final JdbcScenarioStore scenarios;
    private final JdbcOfferStore offers;
    private final JdbcEngagementStore engagement;
    private final AudienceService audiences;
    private final CampaignMessagePort messages;
    private final AuditRecorder audit;
    private final Clock clock;

    public ScenarioService(
            JdbcCampaignStore campaigns,
            JdbcScenarioStore scenarios,
            JdbcOfferStore offers,
            JdbcEngagementStore engagement,
            AudienceService audiences,
            CampaignMessagePort messages,
            AuditRecorder audit,
            Clock clock) {
        this.campaigns = campaigns;
        this.scenarios = scenarios;
        this.offers = offers;
        this.engagement = engagement;
        this.audiences = audiences;
        this.messages = messages;
        this.audit = audit;
        this.clock = clock;
    }

    /** One step as an author writes it. */
    public record StepDraft(
            String channel,
            @Nullable UUID offerId,
            @Nullable String templateKey,
            int waitAfterPreviousSeconds,
            @Nullable String continuationCondition,
            @Nullable String stopCondition) {}

    /** A scenario as an author writes it. */
    public record ScenarioDraft(
            String name,
            UUID audienceId,
            String consentPurpose,
            int recipientCap,
            @Nullable Long costCeilingMinor,
            String currency,
            @Nullable Integer controlGroupPercent,
            @Nullable Instant scheduledAt,
            List<StepDraft> steps) {}

    /** What a scenario is, with where its guests are. */
    public record ScenarioView(
            CampaignRow campaign,
            List<StepRow> steps,
            Map<String, Integer> participants,
            Map<String, Integer> decisions) {}

    @Transactional
    public UUID create(
            UUID tenantId, UUID brandId, ScenarioDraft draft, UUID authorId, ActorRef actor, String correlationId) {
        List<ResolvedStep> steps = resolve(tenantId, brandId, draft.steps());
        if (draft.controlGroupPercent() != null
                && (draft.controlGroupPercent() < 0 || draft.controlGroupPercent() > 100)) {
            throw invalid("A control group is a percentage between 0 and 100");
        }
        if (draft.scheduledAt() != null && !draft.scheduledAt().isAfter(clock.instant())) {
            throw invalid("scheduledAt must be in the future, not " + draft.scheduledAt());
        }
        // The audience has to be this brand's: a scenario cannot be estimated against a
        // sibling brand's segment even if the id is named.
        try {
            audiences.get(tenantId, brandId, draft.audienceId());
        } catch (RuntimeException notThisBrands) {
            throw invalid("No audience " + draft.audienceId() + " belongs to this brand");
        }

        ResolvedStep primary = steps.stream()
                .filter(step -> step.channel().messaging().isPresent())
                .findFirst()
                .orElseThrow(() -> invalid("A scenario needs at least one step that sends a message: its cost "
                        + "ceiling, consent purpose and estimate are those of a messaging channel"));
        MarketingChannel primaryChannel = primary.channel().messaging().orElseThrow();
        if (primaryChannel.carriesMarginalCost() && draft.costCeilingMinor() == null) {
            throw invalid("A scenario that sends %s needs a cost ceiling: this channel bills per segment and the "
                            .formatted(primaryChannel)
                    + "mistake is unrecoverable");
        }

        UUID id = Ids.newId();
        EngagementPolicy policy = engagement.resolvePolicy(tenantId, brandId);
        campaigns.insertCampaign(new NewCampaign(
                id,
                tenantId,
                brandId,
                draft.name(),
                primaryChannel.name(),
                draft.consentPurpose(),
                draft.audienceId(),
                primary.templateKey(),
                draft.recipientCap(),
                draft.costCeilingMinor(),
                draft.currency(),
                policy.timezone().getId(),
                null,
                null,
                authorId,
                draft.scheduledAt(),
                clock.instant(),
                "SCENARIO",
                draft.controlGroupPercent(),
                null));
        writeSteps(tenantId, brandId, id, steps);

        audit.record(AuditFact.of("MARKETING_SCENARIO_DRAFTED", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.brand(tenantId, brandId))
                .target("MarketingCampaign", id)
                .because("A scenario was drafted")
                .changed(ChangeDocuments.created(describe(draft, steps.size())))
                .usingCapability("campaign.author")
                .correlatedBy(correlationId)
                .occurredAt(clock.instant())
                .build());
        return id;
    }

    /**
     * Replaces a draft's steps. Refused from the moment the campaign leaves {@code DRAFT}:
     * an approved scenario is approved as it stands.
     */
    @Transactional
    public void replaceSteps(
            UUID tenantId,
            UUID brandId,
            UUID campaignId,
            List<StepDraft> drafts,
            ActorRef actor,
            String correlationId) {
        CampaignRow campaign = requireScenario(tenantId, brandId, campaignId);
        if (campaign.status() != CampaignStatus.DRAFT) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE,
                    ("A scenario's steps are fixed once it leaves DRAFT (this one is %s). An edit is a new "
                                    + "version that needs its own approval: revise it instead")
                            .formatted(campaign.status()));
        }
        List<ResolvedStep> steps = resolve(tenantId, brandId, drafts);
        scenarios.deleteSteps(tenantId, campaignId);
        writeSteps(tenantId, brandId, campaignId, steps);

        audit.record(AuditFact.of("MARKETING_SCENARIO_STEPS_REPLACED", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.brand(tenantId, brandId))
                .target("MarketingCampaign", campaignId)
                .because("A draft scenario's steps were replaced")
                .changed(ChangeDocuments.change("stepCount", null, steps.size()))
                .usingCapability("campaign.author")
                .correlatedBy(correlationId)
                .occurredAt(clock.instant())
                .build());
    }

    /**
     * A new DRAFT version of a scenario, with the same steps and a link to the one it
     * supersedes. It must be estimated, submitted and approved like any campaign, and
     * starting it halts the version it replaces.
     */
    @Transactional
    public UUID revise(
            UUID tenantId, UUID brandId, UUID campaignId, UUID authorId, ActorRef actor, String correlationId) {
        CampaignRow source = requireScenario(tenantId, brandId, campaignId);
        if (source.status() == CampaignStatus.DRAFT) {
            throw new ApiException(
                    ErrorCode.UNPROCESSABLE_STATE, "A draft is edited in place; only a scenario past DRAFT is revised");
        }
        List<StepRow> sourceSteps = scenarios.steps(tenantId, campaignId);
        UUID id = Ids.newId();
        campaigns.insertCampaign(new NewCampaign(
                id,
                tenantId,
                brandId,
                source.name(),
                source.channel(),
                source.consentPurpose(),
                source.audienceId(),
                source.templateKey(),
                source.recipientCap(),
                source.costCeilingMinor(),
                source.currency(),
                source.timezone(),
                null,
                null,
                authorId,
                null,
                clock.instant(),
                "SCENARIO",
                source.controlGroupPercent(),
                campaignId));
        for (StepRow step : sourceSteps) {
            scenarios.insertStep(
                    new NewStep(
                            Ids.newId(),
                            tenantId,
                            brandId,
                            id,
                            step.sequence(),
                            step.channel(),
                            step.offerId(),
                            step.templateKey(),
                            step.templateVersion(),
                            step.waitAfterPreviousSeconds(),
                            step.continuationCondition(),
                            step.stopCondition()),
                    clock.instant());
        }
        audit.record(AuditFact.of("MARKETING_SCENARIO_REVISED", AuditClass.BUSINESS)
                .by(actor)
                .at(ResourceScope.brand(tenantId, brandId))
                .target("MarketingCampaign", id)
                .because("A new version of a scenario was drafted")
                .changed(ChangeDocuments.created(Map.of("supersedes", campaignId.toString())))
                .usingCapability("campaign.author")
                .correlatedBy(correlationId)
                .occurredAt(clock.instant())
                .build());
        return id;
    }

    @Transactional(readOnly = true)
    public ScenarioView view(UUID tenantId, UUID brandId, UUID campaignId) {
        CampaignRow campaign = requireScenario(tenantId, brandId, campaignId);
        return new ScenarioView(
                campaign,
                scenarios.steps(tenantId, campaignId),
                scenarios.participantCounts(tenantId, campaignId),
                scenarios.decisionCounts(tenantId, campaignId));
    }

    /**
     * Refuses to start a scenario that cannot deliver, at the moment of launch.
     *
     * <p>Every messaging step's channel must be wired for this brand and every offer a
     * step names must still be in force or yet to start. Read before {@code SENDING}, so
     * the second signature is not spent on a scenario whose third step dies at send time.
     */
    @Transactional(readOnly = true)
    public void assertStartable(CampaignRow campaign) {
        Instant now = clock.instant();
        for (StepRow step : scenarios.steps(campaign.tenantId(), campaign.id())) {
            ScenarioChannel channel = ScenarioChannel.valueOf(step.channel());
            if (channel == ScenarioChannel.CALL_CENTRE) {
                throw new ApiException(
                        ErrorCode.UNPROCESSABLE_STATE,
                        "CHANNEL_NOT_WIRED: step %d hands off to the call centre, which has no lead queue yet"
                                .formatted(step.sequence()));
            }
            if (channel.messaging().isPresent()) {
                CampaignMessagePort.Wiring wiring = messages.wiring(
                        campaign.tenantId(),
                        campaign.brandId(),
                        channel.messaging().orElseThrow().name(),
                        CampaignMessagePort.PURPOSE_MARKETING);
                if (!wiring.isWired()) {
                    throw new ApiException(
                            ErrorCode.UNPROCESSABLE_STATE,
                            "No ADR 0020 delivery path is wired for %s for this brand (%s); step %d cannot be sent"
                                    .formatted(channel, wiring.reason(), step.sequence()));
                }
            }
            if (step.offerId() != null) {
                Optional<OfferRow> offer = offers.find(campaign.tenantId(), step.offerId());
                if (offer.isEmpty()
                        || !"PUBLISHED".equals(offer.get().status())
                        || (offer.get().validUntil() != null
                                && !offer.get().validUntil().isAfter(now))) {
                    throw new ApiException(
                            ErrorCode.UNPROCESSABLE_STATE,
                            "The offer step %d references is not in force".formatted(step.sequence()));
                }
            }
        }
    }

    /** After a scenario's launch: the version it supersedes stops, because the new one replaces it for everyone. */
    @Transactional
    public void onStarted(CampaignRow campaign) {
        UUID superseded = campaign.supersedesCampaignId();
        if (superseded == null) {
            return;
        }
        campaigns.find(campaign.tenantId(), superseded).ifPresent(old -> {
            if (!old.status().isTerminal()) {
                campaigns.halt(
                        old.tenantId(),
                        old.id(),
                        old.status(),
                        CampaignStatus.HALTED_OPERATOR,
                        "Superseded by a newer version of this scenario",
                        clock.instant());
            }
        });
    }

    // ----------------------------------------------------------------- helpers

    private CampaignRow requireScenario(UUID tenantId, UUID brandId, UUID campaignId) {
        CampaignRow campaign = campaigns
                .find(tenantId, campaignId)
                .filter(row -> row.brandId().equals(brandId) && row.isScenario())
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No scenario " + campaignId + " belongs to this brand"));
        return campaign;
    }

    private record ResolvedStep(
            ScenarioChannel channel,
            @Nullable UUID offerId,
            String templateKey,
            int waitSeconds,
            String continuation,
            String stop) {}

    private List<ResolvedStep> resolve(UUID tenantId, UUID brandId, List<StepDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            throw invalid("A scenario has at least one step");
        }
        if (drafts.size() > MAX_STEPS) {
            throw invalid("A scenario has at most %d steps".formatted(MAX_STEPS));
        }
        Instant now = clock.instant();
        List<ResolvedStep> resolved = new ArrayList<>();
        for (int index = 0; index < drafts.size(); index++) {
            StepDraft draft = drafts.get(index);
            int number = index + 1;
            ScenarioChannel channel = parse(ScenarioChannel.class, draft.channel(), "step " + number + "'s channel");
            if (channel == ScenarioChannel.CALL_CENTRE) {
                // ADR 0112 hands a call-centre step to ADR 0111's lead queue as a command
                // consumed by customers. No such queue exists in this build, so offering
                // the channel would be a promise nothing keeps.
                throw invalid(
                        "CHANNEL_NOT_WIRED: step %d is a call-centre step, and the lead queue it hands off to (ADR 0111) does not exist yet"
                                .formatted(number));
            }
            ScenarioCondition.Continuation continuation = draft.continuationCondition() == null
                    ? ScenarioCondition.Continuation.ALWAYS
                    : parse(
                            ScenarioCondition.Continuation.class,
                            draft.continuationCondition(),
                            "step " + number + "'s continuation condition");
            ScenarioCondition.Stop stop = draft.stopCondition() == null
                    ? ScenarioCondition.Stop.NONE
                    : parse(
                            ScenarioCondition.Stop.class,
                            draft.stopCondition(),
                            "step " + number + "'s stop condition");
            if (draft.waitAfterPreviousSeconds() < 0 || draft.waitAfterPreviousSeconds() > MAX_WAIT.toSeconds()) {
                throw invalid("Step %d waits between 0 and %d days".formatted(number, MAX_WAIT.toDays()));
            }

            OfferRow offer = null;
            if (draft.offerId() != null) {
                offer = offers.find(tenantId, draft.offerId())
                        .filter(found -> found.brandId().equals(brandId))
                        .orElseThrow(() -> invalid("No offer " + draft.offerId() + " belongs to this brand"));
                if (!"PUBLISHED".equals(offer.status())) {
                    throw invalid("Step %d references an offer that is %s: only a published offer can be used"
                            .formatted(number, offer.status()));
                }
                if (offer.validUntil() != null && !offer.validUntil().isAfter(now)) {
                    throw invalid(
                            "Step %d references an offer whose validity window has already ended".formatted(number));
                }
                if (!offer.allowedChannels().contains(channel.name())) {
                    throw invalid("Step %d sends on %s, which the offer is not allowed in (%s)"
                            .formatted(number, channel, offer.allowedChannels()));
                }
            } else if (channel == ScenarioChannel.IN_APP) {
                throw invalid("Step %d shows an in-app banner and names no offer to show".formatted(number));
            }
            String templateKey =
                    draft.templateKey() != null && !draft.templateKey().isBlank()
                            ? draft.templateKey()
                            : offer == null ? null : offer.templateKey();
            if (templateKey == null) {
                throw invalid("Step %d needs a template key, or an offer that names one".formatted(number));
            }
            resolved.add(new ResolvedStep(
                    channel,
                    draft.offerId(),
                    templateKey,
                    draft.waitAfterPreviousSeconds(),
                    continuation.name(),
                    stop.name()));
        }
        return resolved;
    }

    private void writeSteps(UUID tenantId, UUID brandId, UUID campaignId, List<ResolvedStep> steps) {
        Instant now = clock.instant();
        int sequence = 1;
        for (ResolvedStep step : steps) {
            scenarios.insertStep(
                    new NewStep(
                            Ids.newId(),
                            tenantId,
                            brandId,
                            campaignId,
                            sequence++,
                            step.channel().name(),
                            step.offerId(),
                            step.templateKey(),
                            null,
                            step.waitSeconds(),
                            step.continuation(),
                            step.stop()),
                    now);
        }
    }

    private static Map<String, Object> describe(ScenarioDraft draft, int steps) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("name", draft.name());
        state.put("steps", steps);
        state.put(
                "controlGroupPercent",
                draft.controlGroupPercent() == null
                        ? "none"
                        : draft.controlGroupPercent().toString());
        state.put("recipientCap", draft.recipientCap());
        return state;
    }

    private static <E extends Enum<E>> E parse(Class<E> type, @Nullable String value, String what) {
        try {
            return Enum.valueOf(type, value == null ? "" : value);
        } catch (IllegalArgumentException unknown) {
            throw invalid(value + " is not a valid value for " + what);
        }
    }

    private static ApiException invalid(String message) {
        return new ApiException(ErrorCode.VALIDATION_FAILED, message);
    }
}
