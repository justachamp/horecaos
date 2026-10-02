package uz.horecaos.platform.pricing.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.PromotionAuthoringService;
import uz.horecaos.platform.pricing.application.PromotionEvaluator;
import uz.horecaos.platform.pricing.application.PromotionSimulationService;
import uz.horecaos.platform.pricing.application.PromotionValidator;
import uz.horecaos.platform.pricing.application.QuoteService;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.PromotionDefinition;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromotionStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromotionStore.DefinitionVersionRow;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromotionStore.PromotionRow;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.authorization.RequiresCapability;

/**
 * A brand's automatic promotions and markups: authoring, lifecycle, simulator and
 * redemptions (ADR 0140, operations surface).
 *
 * <p>Reads and the simulator declare {@code PRICING_READ}, which holds nothing
 * about any customer. Every mutation declares {@code PRICING_PROMOTION_MANAGE} at
 * {@code BRAND} scope, the capability ADR 0072 holds apart from {@code
 * PRICING_AUTHOR} as "a decision to give the tenant's own money away". Promo codes
 * keep their own surface ({@code PromoCodeController}); a promotion authored here is
 * automatic or a markup, never coupon-gated.
 *
 * <p>Every aggregate mutation takes the version it read in {@code If-Match}
 * (ADR 0031), and answers with the new one as an {@code ETag}.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}/brands/{brandId}/promotions")
@Tag(name = "Promotions", description = "Automatic discounts and markups: authoring, lifecycle, simulator, redemptions")
public class PromotionController {

    private final PromotionAuthoringService authoring;
    private final PromotionSimulationService simulation;

    public PromotionController(PromotionAuthoringService authoring, PromotionSimulationService simulation) {
        this.authoring = authoring;
        this.simulation = simulation;
    }

    // ------------------------------------------------------------------- reads

    @GetMapping
    @RequiresCapability(value = Capability.PRICING_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Every automatic promotion and markup this brand has authored",
            description = "Every lifecycle state together, newest first -- an authoring screen needs the "
                    + "lineage, not only what is live. Promo codes are listed on their own endpoint.")
    public ResponseEntity<List<PromotionResponse>> list(@PathVariable UUID tenantId, @PathVariable UUID brandId) {
        return ResponseEntity.ok(authoring.list(tenantId, brandId).stream()
                .map(row -> PromotionResponse.of(row, List.of()))
                .toList());
    }

    @GetMapping("/{promotionId}")
    @RequiresCapability(value = Capability.PRICING_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "One promotion with its recorded definition versions",
            description = "The immutable history is what makes an old quote explainable against the rule "
                    + "that priced it: one version per validation, activation or reordering, newest first.")
    public ResponseEntity<PromotionResponse> get(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID promotionId) {
        var detail = authoring.get(tenantId, brandId, promotionId);
        return respond(PromotionResponse.of(detail.promotion(), detail.versions()), detail.promotion());
    }

    @GetMapping("/{promotionId}/redemptions")
    @RequiresCapability(value = Capability.PRICING_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "The redemptions recorded against one promotion",
            description = "Bounded, newest first, ids and amounts only: an account id and an order id, never "
                    + "a name or a contact. One row per (order, promotion): an amended order moves its row in "
                    + "place, so a redemption is never counted twice.")
    public ResponseEntity<List<RedemptionResponse>> redemptions(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @PathVariable UUID promotionId) {
        return ResponseEntity.ok(authoring.redemptions(tenantId, brandId, promotionId).stream()
                .map(RedemptionResponse::of)
                .toList());
    }

    // --------------------------------------------------------------- authoring

    @PostMapping
    @RequiresCapability(value = Capability.PRICING_PROMOTION_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Draft a promotion",
            description = "A DRAFT cannot reach a customer by any route. The rule is data in a closed "
                    + "vocabulary -- conditions are ANDed, there is no OR -- and nothing is checked until "
                    + "validate, which is where a mistyped operand is caught.")
    public ResponseEntity<PromotionResponse> draft(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody PromotionBody body) {
        PromotionRow created = authoring.create(tenantId, brandId, body.toDefinition());
        return ResponseEntity.status(HttpStatus.CREATED)
                .eTag(AggregateVersion.toETag(created.version()))
                .body(PromotionResponse.of(created, List.of()));
    }

    @PutMapping("/{promotionId}")
    @RequiresCapability(value = Capability.PRICING_PROMOTION_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Replace a promotion's definition",
            description = "Requires If-Match. A DRAFT, VALIDATED or SUSPENDED promotion goes back to DRAFT "
                    + "(and takes a new definition version once the version it leaves was recorded); an "
                    + "ACTIVE one is never edited in place -- suspend it first.")
    public ResponseEntity<PromotionResponse> update(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID promotionId,
            @Valid @RequestBody PromotionBody body,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        PromotionRow updated = authoring.update(tenantId, brandId, promotionId, (int) expected, body.toDefinition());
        return respond(PromotionResponse.of(updated, List.of()), updated);
    }

    @PostMapping("/{promotionId}/validate")
    @RequiresCapability(value = Capability.PRICING_PROMOTION_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Run the rule checker",
            description = "Requires If-Match. A refusal leaves the promotion a DRAFT and is returned as a "
                    + "stable code with the condition or action it concerns; a pass moves it to VALIDATED "
                    + "and records the definition in the immutable history. Warnings do not stop a "
                    + "promotion being validated.")
    public ResponseEntity<ValidationResponse> validate(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID promotionId,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        var result = authoring.validate(tenantId, brandId, promotionId, (int) expected);
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(result.promotion().version()))
                .body(ValidationResponse.of(result));
    }

    @PostMapping("/{promotionId}/activate")
    @RequiresCapability(value = Capability.PRICING_PROMOTION_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Put a validated promotion in front of customers",
            description = "Requires If-Match. A markup, or a promotion whose largest percentage or fixed "
                    + "amount exceeds the configured thresholds, needs a second person (ADR 0027): the "
                    + "response is then 202 with the approval request, and nothing is activated until "
                    + "somebody else decides it and this call is repeated. Below the thresholds it "
                    + "activates directly.")
    public ResponseEntity<ActivationResponse> activate(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID promotionId,
            @Nullable @RequestBody(required = false) ReasonBody body,
            HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        var result =
                authoring.activate(tenantId, brandId, promotionId, (int) expected, body == null ? null : body.reason());
        if (result.isPending()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(new ActivationResponse("PENDING_APPROVAL", result.pendingApprovalRequestId(), null));
        }
        PromotionRow activated = java.util.Objects.requireNonNull(result.promotion());
        return ResponseEntity.ok()
                .eTag(AggregateVersion.toETag(activated.version()))
                .body(new ActivationResponse("ACTIVATED", null, PromotionResponse.of(activated, List.of())));
    }

    @PostMapping("/{promotionId}/suspend")
    @RequiresCapability(value = Capability.PRICING_PROMOTION_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Stop a live promotion applying to new orders",
            description = "Requires If-Match. An order that already holds the promotion keeps it under the "
                    + "definition version it was priced with, even through an amendment.")
    public ResponseEntity<PromotionResponse> suspend(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID promotionId,
            HttpServletRequest request) {
        PromotionRow row =
                authoring.suspend(tenantId, brandId, promotionId, (int) AggregateVersion.requireIfMatch(request));
        return respond(PromotionResponse.of(row, List.of()), row);
    }

    @PostMapping("/{promotionId}/resume")
    @RequiresCapability(value = Capability.PRICING_PROMOTION_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Put a suspended promotion back in front of customers",
            description = "Requires If-Match. The same definition, re-checked against the world as it is now.")
    public ResponseEntity<PromotionResponse> resume(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID promotionId,
            HttpServletRequest request) {
        PromotionRow row =
                authoring.resume(tenantId, brandId, promotionId, (int) AggregateVersion.requireIfMatch(request));
        return respond(PromotionResponse.of(row, List.of()), row);
    }

    @PostMapping("/{promotionId}/archive")
    @RequiresCapability(value = Capability.PRICING_PROMOTION_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Retire a promotion",
            description = "Requires If-Match. An archived promotion is history and is never applied again; "
                    + "it is not deleted, because the orders and definition versions that name it stay "
                    + "reconcilable.")
    public ResponseEntity<PromotionResponse> archive(
            @PathVariable UUID tenantId,
            @PathVariable UUID brandId,
            @PathVariable UUID promotionId,
            HttpServletRequest request) {
        PromotionRow row =
                authoring.archive(tenantId, brandId, promotionId, (int) AggregateVersion.requireIfMatch(request));
        return respond(PromotionResponse.of(row, List.of()), row);
    }

    @PutMapping("/priority")
    @RequiresCapability(value = Capability.PRICING_PROMOTION_MANAGE, scope = ScopeType.BRAND, mutating = true)
    @Operation(
            summary = "Reorder priorities within one stacking group",
            description = "The first promotion listed gets the highest priority. Priority only breaks ties "
                    + "(benefit comes first), so it is the one change a live promotion accepts without "
                    + "going back to DRAFT; each promotion whose priority moves takes a new definition "
                    + "version and a history row.")
    public ResponseEntity<List<PromotionResponse>> reorder(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody PriorityBody body) {
        return ResponseEntity.ok(
                authoring.reorder(tenantId, brandId, body.stackingGroup(), body.orderedPromotionIds()).stream()
                        .map(row -> PromotionResponse.of(row, List.of()))
                        .toList());
    }

    // --------------------------------------------------------------- simulator

    @PostMapping("/simulate")
    @RequiresCapability(value = Capability.PRICING_READ, scope = ScopeType.BRAND)
    @Operation(
            summary = "Simulate a cart against the brand's promotions",
            description = "The real engine with a different sink: the same price book, tax profile, "
                    + "delivery resolution and promotion inputs a real quote uses, writing no quote, no "
                    + "redemption and no counter. The customer is synthetic facts, never an account id. "
                    + "Optionally tries an unsaved candidate definition, or replays a stored definition "
                    + "version. The decision trace names every promotion in the brand and says whether "
                    + "it applied or why it did not.")
    public ResponseEntity<SimulationResponse> simulate(
            @PathVariable UUID tenantId, @PathVariable UUID brandId, @Valid @RequestBody SimulationBody body) {
        try {
            return ResponseEntity.ok(SimulationResponse.of(simulation.simulate(tenantId, brandId, body.toRequest())));
        } catch (PricingEngine.UnpricedItemException unpriced) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    unpriced.getMessage(),
                    Map.of("priceableId", unpriced.priceableId().toString()));
        } catch (QuoteService.NoPublishedMenuException
                | QuoteService.NoPriceBookException
                | QuoteService.NoTaxProfileException misconfigured) {
            throw new ApiException(ErrorCode.RESOURCE_NOT_FOUND, misconfigured.getMessage());
        }
    }

    private static <T> ResponseEntity<T> respond(T body, PromotionRow row) {
        return ResponseEntity.ok().eTag(AggregateVersion.toETag(row.version())).body(body);
    }

    // ------------------------------------------------------------- wire shapes

    /** One condition or action: a type from the closed vocabulary and its operands. */
    public record RuleBody(
            @Positive int sequence,
            @NotNull String type,
            @Nullable Map<String, Object> operands) {}

    /**
     * A promotion definition. Optional primitives are boxed: Jackson 3 refuses a
     * missing primitive in a request body, and the console omits what it does not
     * set.
     */
    public record PromotionBody(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull Promotion.Kind kind,
            @NotNull Promotion.Scope scope,
            @NotBlank @Size(max = 64) String stackingGroup,
            @Nullable Boolean exclusive,
            @Nullable Integer priority,
            @Nullable @Positive Long maximumDiscountMinor,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @Nullable Instant validFrom,
            @Nullable Instant validUntil,
            @Nullable @Min(1) Integer maximumRedemptions,
            @Nullable @Min(1) Integer maximumPerCustomer,
            Promotion.@Nullable LoyaltyAccrual loyaltyAccrual,
            Promotion.@Nullable LoyaltyRedemption loyaltyRedemption,
            @Nullable @Size(max = 20) List<@Valid RuleBody> conditions,
            @NotEmpty @Size(max = 20) List<@Valid RuleBody> actions) {

        PromotionDefinition toDefinition() {
            return new PromotionDefinition(
                    code,
                    name,
                    kind,
                    scope,
                    stackingGroup,
                    exclusive != null && exclusive,
                    priority == null ? 0 : priority,
                    false,
                    maximumDiscountMinor,
                    currency,
                    validFrom,
                    validUntil,
                    maximumRedemptions,
                    maximumPerCustomer,
                    loyaltyAccrual == null ? Promotion.LoyaltyAccrual.ACCRUE : loyaltyAccrual,
                    loyaltyRedemption == null ? Promotion.LoyaltyRedemption.ALLOW : loyaltyRedemption,
                    conditions == null
                            ? List.of()
                            : conditions.stream()
                                    .map(rule -> new PromotionDefinition.ConditionDefinition(
                                            rule.sequence(),
                                            conditionType(rule.type()),
                                            rule.operands() == null ? Map.of() : rule.operands()))
                                    .toList(),
                    actions.stream()
                            .map(rule -> new PromotionDefinition.ActionDefinition(
                                    rule.sequence(),
                                    actionType(rule.type()),
                                    rule.operands() == null ? Map.of() : rule.operands()))
                            .toList());
        }

        private static Promotion.Condition.Type conditionType(String type) {
            try {
                return Promotion.Condition.Type.valueOf(type);
            } catch (IllegalArgumentException unknown) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown condition type " + type);
            }
        }

        private static Promotion.Action.Type actionType(String type) {
            try {
                return Promotion.Action.Type.valueOf(type);
            } catch (IllegalArgumentException unknown) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "Unknown action type " + type);
            }
        }
    }

    public record ReasonBody(@Nullable @Size(max = 500) String reason) {}

    public record PriorityBody(
            @NotBlank String stackingGroup,
            @NotEmpty @Size(max = 200) List<UUID> orderedPromotionIds) {}

    public record RuleResponse(int sequence, String type, Map<String, Object> operands) {}

    /** One recorded definition version: the canonical document exactly as the engine read it. */
    public record VersionResponse(
            int definitionVersion,
            String reason,
            String recordedBy,
            Instant recordedAt,
            Map<String, Object> definition) {}

    /**
     * @param version the row version, also the {@code ETag}: send it back in {@code If-Match}
     * @param definitionVersion the immutable definition version an adjustment names
     * @param versions the recorded definition versions, newest first (the detail read only)
     */
    public record PromotionResponse(
            UUID promotionId,
            int version,
            int definitionVersion,
            String code,
            String name,
            String kind,
            String scope,
            String stackingGroup,
            boolean exclusive,
            int priority,
            String status,
            @Nullable Long maximumDiscountMinor,
            String currency,
            @Nullable Instant validFrom,
            @Nullable Instant validUntil,
            @Nullable Integer maximumRedemptions,
            @Nullable Integer maximumPerCustomer,
            int consumedCount,
            String loyaltyAccrual,
            String loyaltyRedemption,
            List<RuleResponse> conditions,
            List<RuleResponse> actions,
            @Nullable Instant validatedAt,
            @Nullable Instant activatedAt,
            @Nullable String activatedBy,
            @Nullable UUID approvalId,
            Instant createdAt,
            Instant updatedAt,
            List<VersionResponse> versions) {

        static PromotionResponse of(PromotionRow row, List<DefinitionVersionRow> versions) {
            PromotionDefinition d = row.definition();
            return new PromotionResponse(
                    row.id(),
                    row.version(),
                    row.definitionVersion(),
                    d.code(),
                    d.name(),
                    d.kind().name(),
                    d.scope().name(),
                    d.stackingGroup(),
                    d.exclusive(),
                    d.priority(),
                    row.status(),
                    d.maximumDiscountMinor(),
                    d.currency(),
                    d.validFrom(),
                    d.validUntil(),
                    d.maximumRedemptions(),
                    d.maximumPerCustomer(),
                    row.consumedCount(),
                    d.loyaltyAccrual().name(),
                    d.loyaltyRedemption().name(),
                    d.conditions().stream()
                            .map(rule -> new RuleResponse(
                                    rule.sequence(), rule.type().name(), rule.operands()))
                            .toList(),
                    d.actions().stream()
                            .map(rule -> new RuleResponse(
                                    rule.sequence(), rule.type().name(), rule.operands()))
                            .toList(),
                    row.validatedAt(),
                    row.activatedAt(),
                    row.activatedBy(),
                    row.approvalId(),
                    row.createdAt(),
                    row.updatedAt(),
                    versions.stream()
                            .map(version -> new VersionResponse(
                                    version.definitionVersion(),
                                    version.reason(),
                                    version.recordedBy(),
                                    version.recordedAt(),
                                    version.definition().canonical()))
                            .toList());
        }
    }

    public record IssueResponse(
            String code, String message, @Nullable Integer sequence) {}

    public record ValidationResponse(
            boolean valid, List<IssueResponse> refusals, List<IssueResponse> warnings, PromotionResponse promotion) {

        static ValidationResponse of(PromotionAuthoringService.ValidationResult result) {
            return new ValidationResponse(
                    result.report().isValid(),
                    result.report().refusals().stream()
                            .map(PromotionController::issue)
                            .toList(),
                    result.report().warnings().stream()
                            .map(PromotionController::issue)
                            .toList(),
                    PromotionResponse.of(result.promotion(), List.of()));
        }
    }

    private static IssueResponse issue(PromotionValidator.Issue issue) {
        return new IssueResponse(issue.code(), issue.message(), issue.sequence());
    }

    /**
     * @param outcome {@code ACTIVATED}, or {@code PENDING_APPROVAL} when a second person has been asked
     */
    public record ActivationResponse(
            String outcome,
            @Nullable UUID approvalRequestId,
            @Nullable PromotionResponse promotion) {}

    public record RedemptionResponse(
            UUID redemptionId,
            UUID orderId,
            @Nullable UUID customerAccountId,
            int definitionVersion,
            long discountMinor,
            long markupMinor,
            String currency,
            String status) {

        static RedemptionResponse of(JdbcPromotionStore.LedgerRow row) {
            return new RedemptionResponse(
                    row.id(),
                    row.orderId(),
                    row.customerAccountId(),
                    row.definitionVersion(),
                    row.discountMinor(),
                    row.markupMinor(),
                    row.currency(),
                    row.status());
        }
    }

    // ---------------------------------------------------------------- simulator

    public record SimulationLineBody(
            @NotBlank @Size(max = 64) String lineId,
            @NotNull UUID variantId,
            @Positive @Max(999) int quantity,
            @Nullable @Size(max = 20) List<UUID> modifierOptionIds) {}

    public record DestinationBody(
            @NotNull Double latitude, @NotNull Double longitude) {}

    public record FactsBody(
            @Nullable @Min(1) Integer brandOrderPosition,
            @Nullable @Min(1) Integer channelOrderPosition,
            @Nullable @Size(max = 50) Set<String> segments) {}

    public record ReplayBody(
            @NotNull UUID promotionId, @Positive int definitionVersion) {}

    /**
     * A synthetic cart. There is no account id on it, by design.
     *
     * @param serviceInstant the instant windows are judged at; omitted means now
     */
    public record SimulationBody(
            @NotNull UUID locationId,
            @NotBlank @Size(max = 32) String channelCode,
            @Nullable String fulfillmentMode,
            @Nullable @Size(max = 32) String paymentMethodCode,
            @Nullable Instant serviceInstant,
            @NotEmpty @Size(max = 200) List<@Valid SimulationLineBody> lines,
            @Nullable @Size(max = 32) String presentedCouponCode,
            @Nullable @Valid DestinationBody destination,
            @Nullable @Valid FactsBody facts,
            @Nullable @Valid PromotionBody candidate,
            @Nullable @Size(max = 20) List<@Valid ReplayBody> definitionVersions) {

        PromotionSimulationService.Request toRequest() {
            Map<UUID, Integer> replay = new LinkedHashMap<>();
            if (definitionVersions != null) {
                definitionVersions.forEach(entry -> replay.put(entry.promotionId(), entry.definitionVersion()));
            }
            return new PromotionSimulationService.Request(
                    locationId,
                    channelCode,
                    fulfillmentMode,
                    paymentMethodCode,
                    serviceInstant,
                    lines.stream()
                            .map(line -> new PromotionSimulationService.Line(
                                    line.lineId(),
                                    line.variantId(),
                                    line.quantity(),
                                    line.modifierOptionIds() == null ? List.of() : line.modifierOptionIds()))
                            .toList(),
                    presentedCouponCode,
                    destination == null ? null : new GeoPoint(destination.latitude(), destination.longitude()),
                    facts == null
                            ? null
                            : new PromotionSimulationService.Facts(
                                    facts.brandOrderPosition(),
                                    facts.channelOrderPosition(),
                                    facts.segments() == null ? Set.of() : facts.segments()),
                    candidate == null ? null : candidate.toDefinition(),
                    replay);
        }
    }

    public record TraceResponse(
            UUID promotionId,
            String code,
            String verdict,
            @Nullable Integer conditionSequence,
            List<UUID> lostTo,
            long benefitMinor) {

        static TraceResponse of(PromotionEvaluator.TraceEntry entry) {
            return new TraceResponse(
                    entry.promotionId(),
                    entry.code(),
                    entry.verdict().name(),
                    entry.conditionSequence(),
                    entry.lostTo(),
                    entry.benefitMinor());
        }
    }

    public record SimulatedLine(
            String lineId,
            @Nullable UUID variantId,
            BigDecimal quantity,
            String description,
            long unitAmountMinor,
            long finalAmountMinor,
            long taxAmountMinor) {}

    public record SimulatedAdjustment(
            int sequence,
            @Nullable String lineId,
            String type,
            String descriptionCode,
            @Nullable UUID promotionId,
            @Nullable Integer definitionVersion,
            long amountMinor) {}

    /**
     * What the quote endpoint would return, plus the decision trace and the inputs the engine read.
     *
     * @param contextHash equal to a real quote's for the same inputs, which a test asserts
     */
    public record SimulationResponse(
            String currency,
            String contextHash,
            long subtotalMinor,
            long taxMinor,
            long feeMinor,
            long discountMinor,
            long totalMinor,
            boolean loyaltyAccrualAllowed,
            boolean loyaltyRedemptionAllowed,
            Instant serviceInstant,
            String timeZone,
            String fulfillmentMode,
            @Nullable String paymentMethodCode,
            List<SimulatedLine> lines,
            List<SimulatedAdjustment> adjustments,
            List<TraceResponse> trace) {

        static SimulationResponse of(QuoteService.Priced priced) {
            var result = priced.result();
            var recorded = priced.recorded();
            return new SimulationResponse(
                    priced.currency(),
                    result.contextHash(),
                    result.subtotal().minor(),
                    result.tax().minor(),
                    result.fees().minor(),
                    result.discount().minor(),
                    result.total().minor(),
                    result.loyaltyAccrualAllowed(),
                    result.loyaltyRedemptionAllowed(),
                    recorded.serviceInstant(),
                    recorded.timeZone(),
                    recorded.fulfillmentMode(),
                    recorded.paymentMethodCode(),
                    result.lines().stream()
                            .map(line -> new SimulatedLine(
                                    line.lineId(),
                                    line.variantId(),
                                    line.quantity(),
                                    line.descriptionSnapshot(),
                                    line.unitAmount().minor(),
                                    line.finalAmount().minor(),
                                    line.taxAmount().minor()))
                            .toList(),
                    result.adjustments().stream()
                            .map(a -> new SimulatedAdjustment(
                                    a.sequence(),
                                    a.lineId(),
                                    a.type().name(),
                                    a.descriptionCode(),
                                    "PROMOTION".equals(a.sourceType()) ? a.sourceId() : null,
                                    "PROMOTION".equals(a.sourceType()) ? a.sourceVersion() : null,
                                    a.amount().minor()))
                            .toList(),
                    result.promotionTrace().stream().map(TraceResponse::of).toList());
        }
    }
}
