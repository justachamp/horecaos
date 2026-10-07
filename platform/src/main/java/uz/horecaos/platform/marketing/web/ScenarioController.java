package uz.horecaos.platform.marketing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.marketing.application.CampaignService;
import uz.horecaos.platform.marketing.application.ScenarioResultsService;
import uz.horecaos.platform.marketing.application.ScenarioResultsService.Results;
import uz.horecaos.platform.marketing.application.ScenarioService;
import uz.horecaos.platform.marketing.application.ScenarioService.ScenarioDraft;
import uz.horecaos.platform.marketing.application.ScenarioService.ScenarioView;
import uz.horecaos.platform.marketing.application.ScenarioService.StepDraft;
import uz.horecaos.platform.marketing.domain.AttributionModel;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore.CampaignRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore.DecisionRow;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcScenarioStore.StepRow;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * Scenario campaigns: per-guest steps beside the one-off broadcast (ADR 0112).
 *
 * <p>A scenario is a campaign, so the lifecycle is the one every campaign has and is not
 * duplicated here: {@code POST .../campaigns/{id}/estimates}, {@code /submissions},
 * {@code /approvals} (by somebody who is not the author), {@code /launches} and
 * {@code /halts}. What this controller adds is what only a scenario has: its steps, a new
 * version of it, every decision it made and why, and whether it worked against its
 * control group.
 *
 * <p>No request here can state a discount or a points award: a step names an offer, and
 * an offer names a promotion or an accrual rule that already exists. No response carries
 * a contact value; a guest is an account id.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/marketing/scenarios")
@Tag(
        name = "Marketing scenarios",
        description = "Per-guest steps with a wait, a control group and a decision log (ADR 0112)")
public class ScenarioController {

    private static final int DECISION_PAGE = 200;

    private final ScenarioService scenarios;
    private final ScenarioResultsService results;
    private final CampaignService campaigns;
    private final JdbcScenarioStore store;
    private final CurrentActor currentActor;

    public ScenarioController(
            ScenarioService scenarios,
            ScenarioResultsService results,
            CampaignService campaigns,
            JdbcScenarioStore store,
            CurrentActor currentActor) {
        this.scenarios = scenarios;
        this.results = results;
        this.campaigns = campaigns;
        this.store = store;
        this.currentActor = currentActor;
    }

    @GetMapping
    @RequiresCapability(value = Capability.CAMPAIGN_AUTHOR, scope = ScopeType.BRAND)
    @Operation(summary = "Every scenario this brand has drafted, run or stopped, newest first")
    public ResponseEntity<List<ScenarioSummary>> list(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(campaigns.list(tenantId, brandId).stream()
                .filter(CampaignRow::isScenario)
                .map(ScenarioSummary::of)
                .toList());
    }

    @PostMapping
    @RequiresCapability(value = Capability.CAMPAIGN_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Draft a scenario",
            description = "An ordered list of steps, each a channel, an optional offer, a template, a "
                    + "wait and two conditions from closed sets. A DRAFT until estimated, submitted and "
                    + "approved by somebody who is not its author, exactly as a broadcast is: nothing here "
                    + "sends anything. `controlGroupPercent` is optional; omit it to run against the whole "
                    + "audience with no measurement baseline. A call-centre step is refused until ADR 0111's "
                    + "lead queue exists (`CHANNEL_NOT_WIRED`).")
    public ResponseEntity<ScenarioResponse> create(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody CreateScenarioRequest body) {
        UUID id = scenarios.create(
                tenantId,
                brandId,
                new ScenarioDraft(
                        body.name(),
                        body.audienceId(),
                        body.consentPurpose(),
                        body.recipientCap(),
                        body.costCeilingMinor(),
                        body.currency(),
                        body.controlGroupPercent(),
                        body.scheduledAt(),
                        body.steps().stream().map(StepRequest::toDraft).toList()),
                actorId(),
                actor(),
                correlationId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ScenarioResponse.of(scenarios.view(tenantId, brandId, id)));
    }

    @GetMapping("/{campaignId}")
    @RequiresCapability(value = Capability.CAMPAIGN_AUTHOR, scope = ScopeType.BRAND)
    @Operation(
            summary = "One scenario: its steps, where its guests are, and how its decisions came out",
            description = "`participants` counts guests by where they are (IN_PROGRESS, CONTROL, and "
                    + "each outcome); `decisions` counts decisions by SENT or by the reason they were "
                    + "blocked. The campaign's own lifecycle state is `campaign.status`.")
    public ResponseEntity<ScenarioResponse> read(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID campaignId) {
        return ResponseEntity.ok(ScenarioResponse.of(scenarios.view(tenantId, brandId, campaignId)));
    }

    @PutMapping("/{campaignId}/steps")
    @RequiresCapability(value = Capability.CAMPAIGN_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Replace a draft scenario's steps",
            description = "Only while the scenario is a DRAFT. From the moment it leaves DRAFT its steps "
                    + "are fixed, and a change is a new version that needs its own approval: see "
                    + "`POST .../revisions`.")
    public ResponseEntity<ScenarioResponse> replaceSteps(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID campaignId,
            @Valid @RequestBody ReplaceStepsRequest body) {
        scenarios.replaceSteps(
                tenantId,
                brandId,
                campaignId,
                body.steps().stream().map(StepRequest::toDraft).toList(),
                actor(),
                correlationId());
        return ResponseEntity.ok(ScenarioResponse.of(scenarios.view(tenantId, brandId, campaignId)));
    }

    @PostMapping("/{campaignId}/revisions")
    @RequiresCapability(value = Capability.CAMPAIGN_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Draft a new version of a scenario that is past DRAFT",
            description = "A new DRAFT with the same steps, pointing at the version it supersedes. It is "
                    + "estimated, submitted and approved like any campaign, and launching it halts the "
                    + "version it replaces. The old version is never edited.")
    public ResponseEntity<ScenarioResponse> revise(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID campaignId) {
        UUID id = scenarios.revise(tenantId, brandId, campaignId, actorId(), actor(), correlationId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ScenarioResponse.of(scenarios.view(tenantId, brandId, id)));
    }

    @GetMapping("/{campaignId}/decisions")
    @RequiresCapability(value = Capability.CAMPAIGN_AUTHOR, scope = ScopeType.BRAND)
    @Operation(
            summary = "What the scenario decided, newest first, and for every block why",
            description = "One row per choice action selection made: SENT with the handle the delivery path "
                    + "gave, or BLOCKED with a reason from the refusal vocabulary and a sentence. "
                    + "`accountId` narrows it to one guest, which is the answer to why a particular guest "
                    + "did not get a step.")
    public ResponseEntity<List<DecisionResponse>> decisions(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID campaignId,
            @RequestParam(required = false) @Nullable UUID accountId,
            @RequestParam(defaultValue = "100") @Min(1) @Max(DECISION_PAGE) int limit) {
        scenarios.view(tenantId, brandId, campaignId); // a scenario of another brand is not found
        return ResponseEntity.ok(store.decisions(tenantId, campaignId, accountId, limit).stream()
                .map(DecisionResponse::of)
                .toList());
    }

    @GetMapping("/{campaignId}/results")
    @RequiresCapability(value = Capability.CAMPAIGN_AUTHOR, scope = ScopeType.BRAND)
    @Operation(
            summary = "Did it work: the treated group's goal rate against the withheld control group's",
            description = "The goal is the guest's next order within `windowDays` of entering. Control-group "
                    + "guests were never contacted, so their rate is raw; a treated guest's order counts for "
                    + "this scenario only if the attribution model credits it (FIRST_TOUCH: the first campaign "
                    + "to contact them in the window; LAST_TOUCH: the most recent before the order). `lift` is "
                    + "null when the scenario ran without a control group, because there is no baseline to "
                    + "state it against.")
    public ResponseEntity<Results> results(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID campaignId,
            @RequestParam(defaultValue = "FIRST_TOUCH") String model,
            @RequestParam(required = false) @Nullable @Positive Integer windowDays) {
        AttributionModel attribution;
        try {
            attribution = AttributionModel.valueOf(model);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, model + " is not an attribution model: FIRST_TOUCH or LAST_TOUCH");
        }
        return ResponseEntity.ok(results.results(tenantId, brandId, campaignId, attribution, windowDays));
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    private UUID actorId() {
        String subject = currentActor.get().subject();
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException notAUuid) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "This principal has no identifier that can be recorded as an author");
        }
    }

    private static String correlationId() {
        String correlationId = MDC.get("correlationId");
        return correlationId == null ? UUID.randomUUID().toString() : correlationId;
    }

    // ------------------------------------------------------------ wire shapes

    public record StepRequest(
            @NotBlank String channel,
            @Nullable UUID offerId,
            @Nullable @Size(max = 64) String templateKey,
            @NotNull @Min(0) Integer waitAfterPreviousSeconds,
            @Nullable String continuationCondition,
            @Nullable String stopCondition) {

        StepDraft toDraft() {
            return new StepDraft(
                    channel, offerId, templateKey, waitAfterPreviousSeconds, continuationCondition, stopCondition);
        }
    }

    public record CreateScenarioRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull UUID audienceId,
            @NotBlank @Size(max = 64) String consentPurpose,
            @NotNull @Positive Integer recipientCap,
            @Nullable @Min(0) Long costCeilingMinor,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @Nullable @Min(0) @Max(100) Integer controlGroupPercent,
            @Nullable Instant scheduledAt,
            @NotEmpty @Valid List<StepRequest> steps) {}

    public record ReplaceStepsRequest(@NotEmpty @Valid List<StepRequest> steps) {}

    public record ScenarioSummary(
            UUID campaignId,
            String name,
            String status,
            String consentPurpose,
            @Nullable Integer controlGroupPercent,
            @Nullable UUID supersedesCampaignId,
            Instant createdAt) {

        static ScenarioSummary of(CampaignRow row) {
            return new ScenarioSummary(
                    row.id(),
                    row.name(),
                    row.status().name(),
                    row.consentPurpose(),
                    row.controlGroupPercent(),
                    row.supersedesCampaignId(),
                    row.createdAt());
        }
    }

    public record StepResponse(
            int sequence,
            String channel,
            @Nullable UUID offerId,
            String templateKey,
            int waitAfterPreviousSeconds,
            String continuationCondition,
            String stopCondition) {

        static StepResponse of(StepRow row) {
            return new StepResponse(
                    row.sequence(),
                    row.channel(),
                    row.offerId(),
                    row.templateKey(),
                    row.waitAfterPreviousSeconds(),
                    row.continuationCondition(),
                    row.stopCondition());
        }
    }

    public record ScenarioResponse(
            ScenarioSummary campaign,
            List<StepResponse> steps,
            Map<String, Integer> participants,
            Map<String, Integer> decisions) {

        static ScenarioResponse of(ScenarioView view) {
            return new ScenarioResponse(
                    ScenarioSummary.of(view.campaign()),
                    view.steps().stream().map(StepResponse::of).toList(),
                    view.participants(),
                    view.decisions());
        }
    }

    public record DecisionResponse(
            UUID decisionId,
            UUID customerAccountId,
            int stepSequence,
            String decision,
            @Nullable String refusalReason,
            @Nullable String reasonText,
            @Nullable String resolvedChannel,
            @Nullable UUID attemptId,
            @Nullable Instant acknowledgedAt,
            Instant decidedAt) {

        static DecisionResponse of(DecisionRow row) {
            return new DecisionResponse(
                    row.id(),
                    row.customerAccountId(),
                    row.stepSequence(),
                    row.decision(),
                    row.refusalReason(),
                    row.reasonText(),
                    row.resolvedChannel(),
                    row.attemptId(),
                    row.acknowledgedAt(),
                    row.decidedAt());
        }
    }
}
