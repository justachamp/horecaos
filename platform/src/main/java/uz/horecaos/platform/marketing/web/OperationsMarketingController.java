package uz.horecaos.platform.marketing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
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
import uz.horecaos.platform.marketing.application.AudienceService;
import uz.horecaos.platform.marketing.application.CampaignService;
import uz.horecaos.platform.marketing.application.MarketingSuppressionService;
import uz.horecaos.platform.marketing.domain.AudiencePredicate;
import uz.horecaos.platform.marketing.domain.MarketingChannel;
import uz.horecaos.platform.marketing.domain.PredicateOperator;
import uz.horecaos.platform.marketing.domain.PredicateType;
import uz.horecaos.platform.marketing.domain.SuppressionReason;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcAudienceStore;
import uz.horecaos.platform.marketing.infrastructure.persistence.JdbcCampaignStore;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * The operations surface for ADR 0044: audiences, campaigns, and suppression.
 *
 * <p>Brand-scoped throughout, and that is a decision rather than a URL shape. The
 * frequency cap is per brand, quiet hours are in the brand's timezone, and two
 * brands under one tenant are two businesses to the customer — so a tenant-scoped
 * grant over this surface would let one brand's marketer read and spend against
 * another's.
 *
 * <p>No response here carries a phone number, an email address, or a customer's
 * name. A recipient appears as an account id with a status and, when it was
 * refused, the reason. Turning one into the other needs
 * {@link Capability#CUSTOMER_PII_REVEAL} in the customers module, which is where
 * that decision belongs and where it is recorded.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/brands/{brandId}/marketing")
@Tag(name = "Marketing", description = "Audiences, campaigns, and suppression (ADR 0044)")
public class OperationsMarketingController {

    /** A page of recipients. Large enough to be useful, small enough not to be an export. */
    private static final int RECIPIENT_PAGE = 200;

    private final AudienceService audiences;
    private final CampaignService campaigns;
    private final MarketingSuppressionService suppressions;
    private final JdbcCampaignStore campaignStore;
    private final JdbcAudienceStore audienceStore;
    private final CurrentActor currentActor;

    public OperationsMarketingController(
            AudienceService audiences,
            CampaignService campaigns,
            MarketingSuppressionService suppressions,
            JdbcCampaignStore campaignStore,
            JdbcAudienceStore audienceStore,
            CurrentActor currentActor) {
        this.audiences = audiences;
        this.campaigns = campaigns;
        this.suppressions = suppressions;
        this.campaignStore = campaignStore;
        this.audienceStore = audienceStore;
        this.currentActor = currentActor;
    }

    // ---------------------------------------------------------------- audiences,
    // Customers 5.3's segment builder over ADR 0044's closed predicate catalogue

    @GetMapping("/audiences")
    @RequiresCapability(value = Capability.AUDIENCE_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Every segment defined for this brand",
            description = "Most recently touched first, each beside its latest completed "
                    + "snapshot's reach — a saved-segment entity Customers 5.3 lists to decide "
                    + "which one to open or hand to a campaign (Marketing 6.4).")
    public ResponseEntity<List<AudienceSummaryResponse>> audienceList(
            @PathVariable UUID tenantId, @PathVariable UUID brandId) {

        return ResponseEntity.ok(audienceStore.listAudiences(tenantId, brandId).stream()
                .map(AudienceSummaryResponse::of)
                .toList());
    }

    @GetMapping("/audiences/{audienceId}")
    @RequiresCapability(value = Capability.AUDIENCE_READ, scope = ScopeType.BRAND)
    @Operation(summary = "One segment's predicates, for the builder to reopen and edit")
    public ResponseEntity<AudienceDetailResponse> audienceDetail(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID audienceId) {

        var audience = audienceStore
                .findAudienceDetail(tenantId, audienceId)
                .filter(row -> row.brandId().equals(brandId))
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No audience %s belongs to this brand".formatted(audienceId)));

        List<AudiencePredicate> predicates =
                audienceStore.loadPredicates(tenantId, audienceId, audience.definitionVersion());
        return ResponseEntity.ok(AudienceDetailResponse.of(audience, predicates));
    }

    @PostMapping("/audiences")
    @RequiresCapability(value = Capability.AUDIENCE_READ, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Define a new segment",
            description = "The RFM builder (recency, frequency, monetary, registration source, "
                    + "birthday window) over ADR 0044's closed predicate catalogue. An audience "
                    + "needs at least one predicate: an empty definition is the whole customer "
                    + "base, and that must be asked for rather than defaulted to.")
    public ResponseEntity<DefineAudienceResponse> defineAudience(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody DefineAudienceRequest body) {

        try {
            UUID audienceId = audiences.define(
                    tenantId,
                    brandId,
                    body.name(),
                    body.description(),
                    body.predicates().stream()
                            .map(AudiencePredicateRequest::toDomain)
                            .toList(),
                    actorId(),
                    correlationId());
            return ResponseEntity.ok(new DefineAudienceResponse(audienceId));
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
        }
    }

    @PutMapping("/audiences/{audienceId}")
    @RequiresCapability(value = Capability.AUDIENCE_READ, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Replace a segment's predicates",
            description = "Bumps the audience's definition_version. A snapshot already built "
                    + "under an earlier version keeps recording that version, so a dispute about "
                    + "who a past send targeted is answered against the definition of the day.")
    public ResponseEntity<RedefineAudienceResponse> redefineAudience(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID audienceId,
            @Valid @RequestBody RedefineAudienceRequest body) {

        var audience = audienceStore
                .findAudienceDetail(tenantId, audienceId)
                .filter(row -> row.brandId().equals(brandId))
                .orElseThrow(() -> new ApiException(
                        ErrorCode.RESOURCE_NOT_FOUND, "No audience %s belongs to this brand".formatted(audienceId)));

        try {
            int version = audiences.redefine(
                    tenantId,
                    audience.id(),
                    body.predicates().stream()
                            .map(AudiencePredicateRequest::toDomain)
                            .toList());
            return ResponseEntity.ok(new RedefineAudienceResponse(version));
        } catch (IllegalArgumentException invalid) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
        }
    }

    @PostMapping("/audiences/{audienceId}/snapshots")
    @RequiresCapability(value = Capability.AUDIENCE_READ, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Evaluate an audience into an immutable snapshot",
            description = "Applies the predicates, then subtracts lifecycle, consent, "
                    + "suppression, frequency cap, and endpoint, recording the reason per "
                    + "exclusion. The member count is an upper bound: the same five checks run "
                    + "again per recipient at send, so the delivered count is always lower.")
    public ResponseEntity<SnapshotResponse> buildSnapshot(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID audienceId,
            @Valid @RequestBody SnapshotRequest body) {

        var result = audiences.buildSnapshot(
                tenantId,
                brandId,
                audienceId,
                channel(body.channel()),
                body.consentPurpose(),
                actor(),
                correlationId());

        return ResponseEntity.ok(new SnapshotResponse(
                result.snapshotId(),
                result.candidateCount(),
                result.memberCount(),
                result.candidateCount() - result.memberCount()));
    }

    @PostMapping("/audiences/snapshots/{snapshotId}/exports")
    @RequiresCapability(value = Capability.AUDIENCE_EXPORT, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Export a snapshot's members as pseudonymous account ids",
            description = "Metrics and account ids only. No contact value crosses this endpoint "
                    + "and none can. The export is itself an audited fact carrying the "
                    + "requester, the audience version, the row count, and the stated purpose, "
                    + "because an unrestricted download of the customer base is how a tenant's "
                    + "list ends up on a competitor's desk. Nothing here uploads a segment to a "
                    + "third party: that is a disclosure to a new controller, not a feature.")
    public ResponseEntity<List<UUID>> export(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID snapshotId,
            @Valid @RequestBody ExportRequest body) {

        return ResponseEntity.ok(audiences.export(
                tenantId,
                snapshotId,
                actor(),
                body.purpose(),
                correlationId(),
                body.limit() == null ? RECIPIENT_PAGE : body.limit()));
    }

    @PostMapping("/campaigns/{campaignId}/estimates")
    @RequiresCapability(value = Capability.CAMPAIGN_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Snapshot the audience and price the send",
            description = "The cost is a range because a personalised name changes the rendered "
                    + "length per recipient, and it is counted in SMS segments per locale rather "
                    + "than in recipients: the same body is two segments in uz-Latn and three in "
                    + "ru. A null cost means it is not knowable — no active template, or no "
                    + "configured price per segment — and is deliberately not reported as zero.")
    public ResponseEntity<EstimateResponse> estimate(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID campaignId) {

        var estimate = campaigns.prepare(tenantId, campaignId, actor(), correlationId());
        return ResponseEntity.ok(new EstimateResponse(
                estimate.snapshotId(),
                estimate.memberCount(),
                estimate.candidateCount(),
                estimate.lowMinor(),
                estimate.highMinor(),
                estimate.currency(),
                estimate.estimatedDeliverySeconds()));
    }

    @PostMapping("/campaigns/{campaignId}/submissions")
    @RequiresCapability(value = Capability.CAMPAIGN_AUTHOR, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Send a campaign for review",
            description = "Refused until a snapshot and an estimate exist: nothing has been "
                    + "approved until somebody has seen a number.")
    public ResponseEntity<Void> submit(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID campaignId) {

        if (!campaigns.submitForReview(tenantId, campaignId)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "This campaign is not a draft, so it cannot be submitted for review");
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/campaigns/{campaignId}/approvals")
    @RequiresCapability(value = Capability.CAMPAIGN_APPROVE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "The second signature",
            description = "Refused when the approver is the author. The failure being prevented "
                    + "is a marketer testing a template and sending forty thousand real SMS, and "
                    + "there is no undo for an SMS.")
    public ResponseEntity<Void> approve(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID campaignId,
            @Valid @RequestBody ReasonRequest body) {

        UUID approverId = actorId();
        boolean approved = campaigns.approve(
                tenantId, campaignId, approverId, UUID.randomUUID(), actor(), body.reason(), correlationId());

        if (!approved) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This campaign is not awaiting review, or you are its author: an approval "
                            + "has to come from somebody other than the person who wrote it");
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/campaigns/{campaignId}/launches")
    @RequiresCapability(value = Capability.CAMPAIGN_APPROVE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Open the send",
            description = "Nothing reaches SENDING except from an approval. A TELEGRAM campaign "
                    + "additionally needs the ADR 0059 stage 4 broadcasts entitlement, refused "
                    + "here as ENTITLEMENT_REQUIRED rather than discovered silently three steps "
                    + "later when the delivery worker finds no wired path.")
    public ResponseEntity<Void> launch(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID campaignId) {

        if (!campaigns.start(tenantId, campaignId)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT,
                    "This campaign is not approved or scheduled, so there is nothing to launch");
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/campaigns/{campaignId}/halts")
    @RequiresCapability(value = Capability.CAMPAIGN_APPROVE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Stop a running campaign",
            description = "Terminal rather than resumable. The cases this exists for — wrong "
                    + "template, wrong audience, wrong price — all end in a new campaign with a "
                    + "new approval, and a stop that can be undone by pressing the same button "
                    + "is one somebody undoes by reflex.")
    public ResponseEntity<Void> halt(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID campaignId,
            @Valid @RequestBody ReasonRequest body) {

        if (!campaigns.halt(tenantId, campaignId, actor(), body.reason(), correlationId())) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "This campaign has already finished, so there is nothing to halt");
        }
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/campaigns/{campaignId}/resumptions")
    @RequiresCapability(value = Capability.CAMPAIGN_APPROVE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Resume a campaign the block-rate guard paused",
            description = "SENDING again, so expansion and delivery continue. Nothing already "
                    + "suppressed with CAMPAIGN_NOT_SENDING while the campaign sat paused is "
                    + "retried; the response reports how many that was, so the operator knows "
                    + "what the pause cost before resuming. A TELEGRAM campaign re-checks the "
                    + "ADR 0059 stage 4 broadcasts entitlement, the same as launching.")
    public ResponseEntity<ResumeResponse> resume(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID campaignId,
            @Valid @RequestBody ReasonRequest body) {

        var outcome = campaigns.resume(tenantId, campaignId, actor(), body.reason(), correlationId());
        if (!outcome.resumed()) {
            throw new ApiException(
                    ErrorCode.RESOURCE_CONFLICT, "This campaign is not paused, so there is nothing to resume");
        }
        return ResponseEntity.ok(new ResumeResponse(outcome.suppressedDuringPause()));
    }

    @GetMapping("/campaigns/{campaignId}/recipients")
    @RequiresCapability(value = Capability.CAMPAIGN_AUTHOR, scope = ScopeType.BRAND)
    @Operation(
            summary = "Per-recipient outcomes, including everybody who was refused",
            description = "The refused rows are the point. A dropped recipient leaves no row and "
                    + "no answer to 'why did this customer not get it', which is the question a "
                    + "tenant actually asks.")
    public ResponseEntity<List<RecipientResponse>> recipients(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID campaignId,
            @RequestParam(defaultValue = "200") @Min(1) int limit) {

        // The campaign is read through the service so a campaign belonging to
        // another brand of the same tenant cannot be listed by naming its id here.
        var campaign = campaigns.require(tenantId, campaignId);
        if (!campaign.brandId().equals(brandId)) {
            throw new ApiException(
                    ErrorCode.RESOURCE_NOT_FOUND, "No campaign " + campaignId + " belongs to this brand");
        }

        return ResponseEntity.ok(
                campaignStore.recipients(tenantId, campaignId, Math.min(limit, RECIPIENT_PAGE)).stream()
                        .map(row -> new RecipientResponse(
                                row.customerAccountId(),
                                row.status(),
                                row.notificationId(),
                                row.refusalReason(),
                                row.deferredUntil() == null
                                        ? null
                                        : row.deferredUntil().toString(),
                                row.terminalStatus()))
                        .toList());
    }

    @PostMapping("/suppressions")
    @RequiresCapability(value = Capability.SUPPRESSION_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Record a suppression",
            description = "A deliverability or abuse fact, distinct from ADR 0015 consent and "
                    + "outranking it. PLATFORM_BLOCK is refused here: it is how HorecaOS stops a "
                    + "tenant messaging somebody who complained to a regulator, and a tenant "
                    + "operator who could set it could also lift it.")
    public ResponseEntity<SuppressionResponse> suppress(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody SuppressionRequest body) {

        SuppressionReason reason = reason(body.reason());
        if (reason.isControlPlaneOnly()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, reason + " is settable only by the control plane");
        }

        UUID id = suppressions.suppress(
                tenantId,
                brandId,
                body.customerAccountId(),
                body.channel() == null ? null : channel(body.channel()),
                reason,
                MarketingSuppressionService.ACTOR_OPERATOR,
                actorId(),
                actor(),
                body.statedReason(),
                correlationId());

        return ResponseEntity.ok(
                new SuppressionResponse(id, reason.name(), reason.lifetime().isEmpty()));
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    /**
     * The acting user as a UUID.
     *
     * <p>The subject is a Keycloak identifier and is a UUID in this deployment. It
     * is parsed rather than assumed: a subject that is not one would otherwise be
     * stored as a nil id and quietly satisfy the four-eyes rule for everybody.
     */
    private UUID actorId() {
        String subject = currentActor.get().subject();
        try {
            return UUID.fromString(subject);
        } catch (IllegalArgumentException notAUuid) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "This principal has no identifier that can be recorded as an approver");
        }
    }

    private static String correlationId() {
        String correlationId = MDC.get("correlationId");
        return correlationId == null ? UUID.randomUUID().toString() : correlationId;
    }

    private static MarketingChannel channel(String value) {
        try {
            return MarketingChannel.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, value + " is not a marketing channel");
        }
    }

    private static SuppressionReason reason(String value) {
        try {
            return SuppressionReason.valueOf(value);
        } catch (IllegalArgumentException unknown) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, value + " is not a suppression reason");
        }
    }

    /** One saved segment, beside its latest completed snapshot's reach. */
    public record AudienceSummaryResponse(
            UUID audienceId,
            String name,
            @Nullable String description,
            String status,
            int definitionVersion,
            Instant createdAt,
            Instant updatedAt,
            @Nullable Integer lastReach,
            @Nullable Instant lastEvaluatedAt) {

        static AudienceSummaryResponse of(JdbcAudienceStore.AudienceSummaryRow row) {
            return new AudienceSummaryResponse(
                    row.id(),
                    row.name(),
                    row.description(),
                    row.status(),
                    row.definitionVersion(),
                    row.createdAt(),
                    row.updatedAt(),
                    row.lastSnapshotMemberCount(),
                    row.lastSnapshotCompletedAt());
        }
    }

    /** One segment's full definition, for the builder to reopen. */
    public record AudienceDetailResponse(
            UUID audienceId,
            String name,
            @Nullable String description,
            String status,
            int definitionVersion,
            Instant createdAt,
            Instant updatedAt,
            List<AudiencePredicateResponse> predicates) {

        static AudienceDetailResponse of(JdbcAudienceStore.AudienceDetailRow row, List<AudiencePredicate> predicates) {
            return new AudienceDetailResponse(
                    row.id(),
                    row.name(),
                    row.description(),
                    row.status(),
                    row.definitionVersion(),
                    row.createdAt(),
                    row.updatedAt(),
                    predicates.stream().map(AudiencePredicateResponse::of).toList());
        }
    }

    public record AudiencePredicateResponse(
            String type,
            String operator,
            @Nullable Long numericLow,
            @Nullable Long numericHigh,
            @Nullable LocalDate dateLow,
            @Nullable LocalDate dateHigh,
            @Nullable List<String> textValues,
            @Nullable UUID audienceId) {

        static AudiencePredicateResponse of(AudiencePredicate predicate) {
            return new AudiencePredicateResponse(
                    predicate.type().name(),
                    predicate.operator().name(),
                    predicate.numericLow(),
                    predicate.numericHigh(),
                    predicate.dateLow(),
                    predicate.dateHigh(),
                    predicate.textValues(),
                    predicate.audienceId());
        }
    }

    /**
     * One predicate over the wire, from ADR 0044's closed catalogue.
     *
     * <p>Unknown {@code type}/{@code operator} names and a value shape the type
     * rejects both fail here as {@code VALIDATION_FAILED} rather than reaching
     * {@link AudiencePredicate}'s constructor as an unchecked exception — the
     * distinction between "you asked for something that does not exist" and a
     * platform error is the whole reason the error code exists.
     */
    public record AudiencePredicateRequest(
            @NotBlank String type,
            @NotBlank String operator,
            @Nullable Long numericLow,
            @Nullable Long numericHigh,
            @Nullable LocalDate dateLow,
            @Nullable LocalDate dateHigh,
            @Nullable List<String> textValues,
            @Nullable UUID audienceId) {

        AudiencePredicate toDomain() {
            try {
                PredicateType predicateType = PredicateType.valueOf(type());
                PredicateOperator predicateOperator = PredicateOperator.valueOf(operator());
                return new AudiencePredicate(
                        predicateType,
                        predicateOperator,
                        numericLow(),
                        numericHigh(),
                        dateLow(),
                        dateHigh(),
                        textValues(),
                        audienceId());
            } catch (IllegalArgumentException invalid) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
            }
        }
    }

    public record DefineAudienceRequest(
            @NotBlank @Size(max = 120) String name,
            @Size(max = 500) @Nullable String description,
            @NotNull List<@Valid AudiencePredicateRequest> predicates) {}

    public record RedefineAudienceRequest(@NotNull List<@Valid AudiencePredicateRequest> predicates) {}

    /** Carries nothing but a generated id — no personal data of any kind. */
    public record DefineAudienceResponse(UUID audienceId) {}

    /** Carries nothing but a version counter — no personal data of any kind. */
    public record RedefineAudienceResponse(int definitionVersion) {}

    /**
     * What channel and consent purpose to evaluate the audience against.
     *
     * @param consentPurpose the ADR 0015 purpose this send needs a decision on. A
     *                       marketing purpose, never a transactional one: an order
     *                       confirmation needs no marketing consent and a promotion
     *                       does, and the distinction is legal rather than tonal
     */
    public record SnapshotRequest(
            @NotBlank String channel,
            @NotBlank @Size(max = 64) String consentPurpose) {}

    /**
     * What a snapshot evaluation produced.
     *
     * @param excluded how many candidates the five subtractions removed. Shown
     *                 beside the reach because a marketer who sees only the reach
     *                 concludes the audience is broken
     */
    public record SnapshotResponse(UUID snapshotId, int candidates, int members, int excluded) {}

    public record ExportRequest(@NotBlank @Size(max = 512) String purpose, Integer limit) {}

    public record ReasonRequest(@NotBlank @Size(max = 512) String reason) {}

    public record EstimateResponse(
            UUID snapshotId,
            int members,
            int candidates,
            @Nullable Long costLowMinor,
            @Nullable Long costHighMinor,
            String currency,
            @Nullable Long estimatedDeliverySeconds) {}

    /** @param suppressedDuringPause what the pause cost: messages CAMPAIGN_NOT_SENDING will not retry */
    public record ResumeResponse(int suppressedDuringPause) {}

    public record RecipientResponse(
            UUID customerAccountId,
            String status,
            UUID notificationId,
            String refusalReason,
            @Nullable String deferredUntil,
            String terminalStatus) {}

    public record SuppressionRequest(
            @NotNull UUID customerAccountId,
            String channel,
            @NotBlank String reason,
            @Size(max = 500) String statedReason) {}

    public record SuppressionResponse(UUID suppressionId, String reason, boolean permanent) {}
}
