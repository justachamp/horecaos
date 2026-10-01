package uz.horecaos.platform.fulfillment.web;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
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
import uz.horecaos.platform.fulfillment.application.DispatchRuleSimulator;
import uz.horecaos.platform.fulfillment.application.DispatchRuleSimulator.LadderStep;
import uz.horecaos.platform.fulfillment.application.DispatchRuleSimulator.Scenario;
import uz.horecaos.platform.fulfillment.application.DispatchRuleSimulator.Simulation;
import uz.horecaos.platform.fulfillment.application.DispatchRulesAuthoringService;
import uz.horecaos.platform.fulfillment.application.DispatchRulesAuthoringService.Editor;
import uz.horecaos.platform.fulfillment.application.DispatchRulesAuthoringService.Level;
import uz.horecaos.platform.fulfillment.application.DispatchRulesAuthoringService.Options;
import uz.horecaos.platform.fulfillment.application.SourcingPolicyAuthoringService;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliverySourcingPolicy;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchDecision;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchFacts;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRuleEvaluator.RuleTrace;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Action;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Conditions;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Grouping;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.IntRange;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.PartnerSelection;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.PartnerSet;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Rule;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Skip;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Start;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.StartBasis;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.TimeWindow;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Weekday;
import uz.horecaos.platform.fulfillment.domain.sourcing.PickupPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingMode;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchRuleStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchRuleStore.ChannelRow;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchRuleStore.InstallationRow;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchRuleStore.LocationRow;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchRuleStore.RecentPlanRow;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchRuleStore.ZoneRow;
import uz.horecaos.platform.iam.api.AuthorizationService;
import uz.horecaos.platform.iam.api.Capability;
import uz.horecaos.platform.iam.api.CurrentActor;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.iam.api.ResourceScope.ScopeType;
import uz.horecaos.platform.web.api.AggregateVersion;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;
import uz.horecaos.platform.web.idempotency.Idempotent;

/**
 * The dispatch rules and the sourcing timings, authored per scope (ADR 0142, ADR 0030, ADR 0031; gap
 * map row {@code 3.8}).
 *
 * <p>These mirror {@code GET}/{@code PUT .../courier-policy} in {@code OperationsCourierController},
 * including its explicit per-scope authorization, for the reason that controller documents at length: a
 * {@code @RequiresCapability} scope is fixed per method, {@code brandId} and {@code locationId} are
 * optional here (omitting both is the tenant-wide document the console's scope ladder starts from), and a
 * tenant-scoped declaration is never satisfied by a brand manager's brand-scoped grant. So each handler
 * calls {@code authorization.require} against the scope it actually resolves, and {@code
 * EndpointCapabilityDeclarationTests} exempts exactly these paths.
 *
 * <p>The write is the ADR 0031 command shape -- {@code If-Match} carrying the version the {@code GET} at
 * this same scope returned (0 when the scope has authored nothing and inherits), {@code Idempotency-Key},
 * and the document replaced whole. {@code PUT} never edits a version in force: orders keep resolving the
 * version they already did.
 *
 * <p>The simulator is a {@code POST} because its body can carry a whole draft document. It declares the
 * read capability, writes nothing, calls no provider and asks no quote, so ADR 0031's idempotency rules do
 * not apply to it.
 *
 * <p>Request bodies box every optional field: Jackson 3 refuses a missing primitive as a malformed body,
 * and an editor that leaves an offset blank is not malformed.
 */
@RestController
@RequestMapping("/api/v1/operations/tenants/{tenantId}")
@Tag(
        name = "Dispatch rules",
        description = "Which delivery installation serves an order, in what order, and when sourcing starts (ADR 0142)")
public class OperationsDispatchRulesController {

    /** How far back the usage read may look. */
    private static final int MAX_USAGE_DAYS = 90;

    /** How many recent plans the simulator's picker is offered. */
    private static final int RECENT_PLANS = 25;

    private final DispatchRulesAuthoringService rules;
    private final SourcingPolicyAuthoringService timings;
    private final DispatchRuleSimulator simulator;
    private final JdbcDispatchRuleStore store;
    private final AuthorizationService authorization;
    private final CurrentActor currentActor;
    private final Clock clock;

    public OperationsDispatchRulesController(
            DispatchRulesAuthoringService rules,
            SourcingPolicyAuthoringService timings,
            DispatchRuleSimulator simulator,
            JdbcDispatchRuleStore store,
            AuthorizationService authorization,
            CurrentActor currentActor,
            Clock clock) {
        this.rules = rules;
        this.timings = timings;
        this.simulator = simulator;
        this.store = store;
        this.authorization = authorization;
        this.currentActor = currentActor;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ rules

    @GetMapping("/dispatch-rules")
    @Operation(
            summary = "The dispatch rules in force at a scope (IA 3.8)",
            description = "Resolves fulfillment.dispatch_rules through the ADR 0030 chain (location, brand, "
                    + "tenant), replace-not-merge, or the built-in default -- which is today's behaviour, the "
                    + "fleet first, partners in binding order and the cheapest quote -- when nothing was "
                    + "published. Omit brandId/locationId for the tenant-wide document. The ETag, and "
                    + "versionAtScope, are the version authored at exactly this scope (0 when it inherits): "
                    + "send it back as If-Match.")
    public ResponseEntity<RulesResponse> readRules(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @Nullable UUID brandId,
            @RequestParam(required = false) @Nullable UUID locationId) {

        ResourceScope scope = scopeOf(tenantId, brandId, locationId);
        authorization.require(currentActor.get().subject(), Capability.DELIVERY_DISPATCH_RULES_READ, scope);
        Editor editor = rules.view(scope);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(editor.versionAtScope()))
                .body(RulesResponse.of(editor, rules.groupingAllowed()));
    }

    @PutMapping("/dispatch-rules")
    @Idempotent
    @Operation(
            summary = "Publish the next version of the dispatch rules at exactly one scope",
            description = "Whole-document replace: every rule and the default are sent, because ADR 0030 versions "
                    + "the document as one unit and rule order is part of its meaning. Refused with "
                    + "VALIDATION_FAILED, naming each reason, when a rule names an installation that is not this "
                    + "company's active delivery installation, a zone that is not a delivery zone, or a source "
                    + "outside the closed set; when a rule can never match because one above it takes every order "
                    + "it would; when its dispatch start leaves a partner no time; when it enables grouping "
                    + "(not available until the pay treatment for a run is decided) or asks for a hold. "
                    + "Requires If-Match carrying the version the GET at this same scope returned, or the write "
                    + "is refused with STALE_VERSION naming both versions. The version this replaces is never "
                    + "touched: a plan keeps the document version it was created under.")
    public ResponseEntity<RulesResponse> writeRules(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @Nullable UUID brandId,
            @RequestParam(required = false) @Nullable UUID locationId,
            @Valid @RequestBody RulesWriteRequest body,
            HttpServletRequest request) {

        ResourceScope scope = scopeOf(tenantId, brandId, locationId);
        authorization.require(currentActor.get().subject(), Capability.DELIVERY_DISPATCH_RULES_WRITE, scope);
        int expectedVersion = expectedVersion(request);
        Editor published = rules.author(scope, body.toDocument(), expectedVersion, actor(), body.reason());
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(published.versionAtScope()))
                .body(RulesResponse.of(published, rules.groupingAllowed()));
    }

    @GetMapping("/dispatch-rules/options")
    @Operation(
            summary = "What a dispatch rule at a scope may name",
            description = "The delivery installations, delivery zones, sales channels and branches the editor "
                    + "draws its pickers from, and the newest plans of the scope for the simulator to re-read. "
                    + "Operator-facing labels only: no credential, no address, nothing about a customer.")
    public OptionsResponse readOptions(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @Nullable UUID brandId,
            @RequestParam(required = false) @Nullable UUID locationId) {

        ResourceScope scope = scopeOf(tenantId, brandId, locationId);
        authorization.require(currentActor.get().subject(), Capability.DELIVERY_DISPATCH_RULES_READ, scope);
        return OptionsResponse.of(rules.options(scope), store.recentPlans(tenantId, brandId, locationId, RECENT_PLANS));
    }

    @GetMapping("/dispatch-rules/usage")
    @Operation(
            summary = "How many plans each dispatch rule produced",
            description = "Counted from delivery_plans over the last N days (default 30, at most 90), by the rule "
                    + "id each plan recorded; the default (built-in or the document's own) is reported as DEFAULT. "
                    + "Never a metric label: a rule id is operator-typed text.")
    public UsageResponse readUsage(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @Nullable UUID brandId,
            @RequestParam(required = false) @Nullable UUID locationId,
            @RequestParam(defaultValue = "30") @Min(1) @Max(MAX_USAGE_DAYS) int days) {

        ResourceScope scope = scopeOf(tenantId, brandId, locationId);
        authorization.require(currentActor.get().subject(), Capability.DELIVERY_DISPATCH_RULES_READ, scope);
        Instant since = clock.instant().minus(Duration.ofDays(days));
        Map<String, Long> counts = store.plansPerRule(tenantId, brandId, locationId, since);
        List<RuleUsage> perRule = counts.entrySet().stream()
                .map(entry -> new RuleUsage(
                        entry.getKey() == null ? DispatchDecision.DEFAULT_RULE : entry.getKey(), entry.getValue()))
                .toList();
        return new UsageResponse(
                days, since, perRule.stream().mapToLong(RuleUsage::plans).sum(), perRule);
    }

    @PostMapping("/dispatch-rules/simulations")
    @Operation(
            summary = "Which rule and provider an order would get (the simulator)",
            description = "Evaluates the document in force at the branch -- or the unsaved draft in the body -- "
                    + "against typed facts or a recent plan's, with the very evaluator the live path runs. Returns "
                    + "the matched rule, why each earlier rule did not match (its first failing condition), the "
                    + "resolved action, the partners in the order they would be tried, what could not be honoured, "
                    + "and when sourcing would start. It calls no provider, asks no quote and writes nothing.")
    public SimulationResponse simulate(@PathVariable UUID tenantId, @Valid @RequestBody SimulationRequest body) {

        ResourceScope location = ResourceScope.location(tenantId, body.brandId(), body.locationId());
        authorization.require(currentActor.get().subject(), Capability.DELIVERY_DISPATCH_RULES_READ, location);
        ResourceScope editingScope = body.editingScope(tenantId);
        Simulation result = simulator.simulate(
                tenantId,
                body.brandId(),
                body.locationId(),
                editingScope,
                body.draft() == null ? null : body.draft().toDocument(),
                body.scenario() == null ? null : body.scenario().toScenario(),
                body.planId());
        return SimulationResponse.of(result);
    }

    // ---------------------------------------------------------------- timings

    @GetMapping("/sourcing-policy")
    @Operation(
            summary = "The sourcing timings in force at a scope",
            description = "The fulfillment.sourcing numbers behind ADR 0014: the in-house and partner lead times, "
                    + "the safety buffer, the pickup window width, how many couriers are offered an order before "
                    + "a partner is called, the ceiling on an offer's life, and how far past the window an "
                    + "assignment may still be attempted. The provisional defaults when nothing was published. "
                    + "The ETag is the version authored at exactly this scope.")
    public ResponseEntity<TimingResponse> readTimings(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @Nullable UUID brandId,
            @RequestParam(required = false) @Nullable UUID locationId) {

        ResourceScope scope = scopeOf(tenantId, brandId, locationId);
        authorization.require(currentActor.get().subject(), Capability.DELIVERY_DISPATCH_RULES_READ, scope);
        SourcingPolicyAuthoringService.Editor editor = timings.view(scope);
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(editor.versionAtScope()))
                .body(TimingResponse.of(editor));
    }

    @PutMapping("/sourcing-policy")
    @Idempotent
    @Operation(
            summary = "Publish the next version of the sourcing timings at exactly one scope",
            description = "Whole-document replace: the seven numbers are one conversation between operations and "
                    + "the branch, and a lead time changed without the buffer that goes with it is a mistake made "
                    + "by editing one of a pair. Takes effect on the next sourcing tick of every open plan -- the "
                    + "timings are re-resolved each tick and the version used is recorded on each attempt -- and "
                    + "on the start of every plan created afterwards. Requires If-Match, as for the rules.")
    public ResponseEntity<TimingResponse> writeTimings(
            @PathVariable UUID tenantId,
            @RequestParam(required = false) @Nullable UUID brandId,
            @RequestParam(required = false) @Nullable UUID locationId,
            @Valid @RequestBody TimingWriteRequest body,
            HttpServletRequest request) {

        ResourceScope scope = scopeOf(tenantId, brandId, locationId);
        authorization.require(currentActor.get().subject(), Capability.DELIVERY_DISPATCH_RULES_WRITE, scope);
        int expectedVersion = expectedVersion(request);
        SourcingPolicyAuthoringService.Editor published =
                timings.author(scope, body.toDocument(), expectedVersion, actor(), body.reason());
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, AggregateVersion.toETag(published.versionAtScope()))
                .body(TimingResponse.of(published));
    }

    // ---------------------------------------------------------------- helpers

    /** Omit brandId/locationId for the tenant-wide scope; supply either for a brand or location document. */
    private static ResourceScope scopeOf(UUID tenantId, @Nullable UUID brandId, @Nullable UUID locationId) {
        if (locationId != null) {
            if (brandId == null) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, "locationId needs the brandId it belongs to");
            }
            return ResourceScope.location(tenantId, brandId, locationId);
        }
        return brandId != null ? ResourceScope.brand(tenantId, brandId) : ResourceScope.tenant(tenantId);
    }

    private static int expectedVersion(HttpServletRequest request) {
        long expected = AggregateVersion.requireIfMatch(request);
        if (expected < 0 || expected > Integer.MAX_VALUE) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "If-Match must carry a document version");
        }
        return (int) expected;
    }

    private ActorRef actor() {
        return ActorRef.user(currentActor.get().subject(), null);
    }

    // ------------------------------------------------------------- wire: rules

    /**
     * The document in force with what the editor needs beside it.
     *
     * @param isBuiltIn      nothing was published anywhere in the chain
     * @param winningScope   the scope whose document is in force, null for the built-in default
     * @param versionAtScope the latest version authored at exactly the requested scope (0 when it
     *                       inherits): send it back as If-Match
     * @param groupingAllowed whether this deployment lets a rule enable grouping
     */
    public record RulesResponse(
            int schema,
            List<Rule> rules,
            @JsonProperty("default") Action fallback,
            boolean isBuiltIn,
            @Nullable ScopeType winningScope,
            @Nullable UUID policyId,
            int policyVersion,
            int versionAtScope,
            List<Level> levels,
            boolean groupingAllowed) {

        static RulesResponse of(Editor editor, boolean groupingAllowed) {
            DispatchRulesDocument document = editor.document();
            return new RulesResponse(
                    document.schema(),
                    document.rules(),
                    document.fallback(),
                    editor.isBuiltIn(),
                    editor.winningScope(),
                    editor.policyId(),
                    editor.policyVersion(),
                    editor.versionAtScope(),
                    editor.levels(),
                    groupingAllowed);
        }
    }

    /** A document to publish. {@code default} is required; an empty {@code rules} is a document of its default alone. */
    public record RulesWriteRequest(
            @NotNull List<@Valid RuleBody> rules,
            @JsonProperty("default") @NotNull @Valid ActionBody fallback,
            @NotBlank @Size(max = 1000) String reason) {

        DispatchRulesDocument toDocument() {
            return new DispatchRulesDocument(
                    DispatchRulesDocument.SCHEMA,
                    rules.stream().map(RuleBody::toRule).toList(),
                    fallback.toAction());
        }
    }

    /** The document without a reason: a draft the editor holds unsaved. */
    public record DraftBody(
            @NotNull List<@Valid RuleBody> rules,
            @JsonProperty("default") @NotNull @Valid ActionBody fallback) {

        DispatchRulesDocument toDocument() {
            return new DispatchRulesDocument(
                    DispatchRulesDocument.SCHEMA,
                    rules.stream().map(RuleBody::toRule).toList(),
                    fallback.toAction());
        }
    }

    public record RuleBody(
            @NotBlank @Size(max = 64) String id,
            @Nullable @Size(max = 200) String name,
            @Nullable Boolean enabled,
            @Nullable @Valid ConditionsBody when,
            @NotNull @Valid ActionBody then) {

        Rule toRule() {
            return new Rule(
                    id,
                    name == null ? "" : name,
                    enabled == null || enabled,
                    when == null ? Conditions.any() : when.toConditions(),
                    then.toAction());
        }
    }

    public record ConditionsBody(
            @Nullable List<String> sources,
            @Nullable List<UUID> channelIds,
            @Nullable List<UUID> zoneIds,
            @Nullable List<UUID> locationIds,
            @Nullable @Valid RangeBody prepMinutes,
            @Nullable @Valid RangeBody distanceMeters,
            @Nullable @Valid WindowBody localTime,
            @Nullable Boolean prepaid) {

        Conditions toConditions() {
            return new Conditions(
                    sources == null ? List.of() : sources,
                    channelIds == null ? List.of() : channelIds,
                    zoneIds == null ? List.of() : zoneIds,
                    locationIds == null ? List.of() : locationIds,
                    prepMinutes == null ? null : prepMinutes.toRange(),
                    distanceMeters == null ? null : distanceMeters.toRange(),
                    localTime == null ? null : localTime.toWindow(),
                    prepaid);
        }
    }

    public record RangeBody(@Nullable Integer min, @Nullable Integer max) {

        IntRange toRange() {
            return new IntRange(min, max);
        }
    }

    public record WindowBody(
            @Nullable List<Weekday> days,
            @NotNull String from,
            @NotNull String to) {

        TimeWindow toWindow() {
            return new TimeWindow(days == null ? List.of() : days, from, to);
        }
    }

    public record ActionBody(
            @NotNull SourcingMode mode,
            @Nullable @Valid PartnersBody partners,
            @Nullable @Valid StartBody dispatchAt,
            @Nullable @Valid GroupingBody grouping,
            @Nullable Boolean holdBeforeConfirm) {

        Action toAction() {
            return new Action(
                    mode,
                    partners == null ? PartnerSet.bindingOrder() : partners.toPartnerSet(),
                    dispatchAt == null ? Start.lead() : dispatchAt.toStart(),
                    grouping == null ? null : grouping.toGrouping(),
                    holdBeforeConfirm);
        }
    }

    public record PartnersBody(
            @Nullable List<UUID> order,
            @Nullable List<UUID> exclude,
            @Nullable PartnerSelection selection) {

        PartnerSet toPartnerSet() {
            return new PartnerSet(
                    order == null ? List.of() : order,
                    exclude == null ? List.of() : exclude,
                    selection == null ? PartnerSelection.CHEAPEST : selection);
        }
    }

    public record StartBody(
            @Nullable StartBasis basis, @Nullable Integer offsetSeconds) {

        Start toStart() {
            return new Start(basis == null ? StartBasis.LEAD : basis, offsetSeconds == null ? 0 : offsetSeconds);
        }
    }

    public record GroupingBody(
            @NotNull Integer mergeRadiusMeters,
            @NotNull Integer maxOrdersPerRun,
            @NotNull Integer maxWaitSeconds) {

        Grouping toGrouping() {
            return new Grouping(mergeRadiusMeters, maxOrdersPerRun, maxWaitSeconds);
        }
    }

    // ----------------------------------------------------------- wire: options

    public record OptionsResponse(
            List<InstallationOption> installations,
            List<ZoneOption> zones,
            List<ChannelOption> channels,
            List<LocationOption> locations,
            List<RecentPlan> recentPlans,
            boolean groupingAllowed) {

        static OptionsResponse of(Options options, List<RecentPlanRow> recent) {
            return new OptionsResponse(
                    options.installations().stream().map(InstallationOption::of).toList(),
                    options.zones().stream().map(ZoneOption::of).toList(),
                    options.channels().stream().map(ChannelOption::of).toList(),
                    options.locations().stream().map(LocationOption::of).toList(),
                    recent.stream().map(RecentPlan::of).toList(),
                    options.groupingAllowed());
        }
    }

    public record InstallationOption(UUID id, String providerType, String displayName, String status) {

        static InstallationOption of(InstallationRow row) {
            return new InstallationOption(row.id(), row.providerType(), row.displayName(), row.status());
        }
    }

    public record ZoneOption(
            UUID id, UUID brandId, String code, String nameEn, String nameRu, String nameUz, String status) {

        static ZoneOption of(ZoneRow row) {
            return new ZoneOption(
                    row.id(), row.brandId(), row.code(), row.nameEn(), row.nameRu(), row.nameUz(), row.status());
        }
    }

    public record ChannelOption(UUID id, String code, String systemType, String displayName, String status) {

        static ChannelOption of(ChannelRow row) {
            return new ChannelOption(row.id(), row.code(), row.systemType(), row.displayName(), row.status());
        }
    }

    public record LocationOption(UUID id, UUID brandId, String displayName) {

        static LocationOption of(LocationRow row) {
            return new LocationOption(row.id(), row.brandId(), row.displayName());
        }
    }

    public record RecentPlan(
            UUID planId,
            UUID locationId,
            String orderReference,
            String status,
            String sourcingMode,
            @Nullable String ruleId,
            Instant createdAt) {

        static RecentPlan of(RecentPlanRow row) {
            return new RecentPlan(
                    row.planId(),
                    row.locationId(),
                    row.orderReference(),
                    row.status(),
                    row.sourcingMode(),
                    row.ruleId(),
                    row.createdAt());
        }
    }

    // ------------------------------------------------------------ wire: usage

    public record UsageResponse(int days, Instant since, long totalPlans, List<RuleUsage> perRule) {}

    /** @param ruleId the rule's id, or {@code DEFAULT} for plans the default (built-in or the document's own) decided */
    public record RuleUsage(String ruleId, long plans) {}

    // ------------------------------------------------------- wire: simulation

    /**
     * @param brandId    the branch the order is for -- which partners are bound, which clock, which timings
     * @param scopeType  the scope the {@code draft} is being written for, deciding what it may name;
     *                   {@code LOCATION} by default. Ignored without a draft
     * @param draft      an unsaved document to evaluate instead of the one in force
     * @param scenario   typed facts, or
     * @param planId     a recent plan at this branch whose facts are re-read, exactly one of the two
     */
    public record SimulationRequest(
            @NotNull UUID brandId,
            @NotNull UUID locationId,
            @Nullable ScopeType scopeType,
            @Nullable @Valid DraftBody draft,
            @Nullable @Valid ScenarioBody scenario,
            @Nullable UUID planId) {

        ResourceScope editingScope(UUID tenantId) {
            ScopeType type = scopeType == null ? ScopeType.LOCATION : scopeType;
            return switch (type) {
                case PLATFORM ->
                    throw new ApiException(
                            ErrorCode.VALIDATION_FAILED, "scopeType PLATFORM is not settable from operations");
                case TENANT -> ResourceScope.tenant(tenantId);
                case BRAND -> ResourceScope.brand(tenantId, brandId);
                case LOCATION -> ResourceScope.location(tenantId, brandId, locationId);
            };
        }
    }

    public record ScenarioBody(
            @Nullable String sourceSystemType,
            @Nullable UUID channelId,
            @Nullable UUID zoneId,
            @NotNull @Min(0) @Max(1440) Integer preparationMinutes,
            @NotNull @Min(0) @Max(100_000) Integer distanceMeters,
            @NotNull Instant confirmedAt,
            @Nullable Boolean prepaid) {

        Scenario toScenario() {
            return new Scenario(
                    sourceSystemType,
                    channelId,
                    zoneId,
                    preparationMinutes,
                    distanceMeters,
                    confirmedAt,
                    prepaid != null && prepaid);
        }
    }

    public record SimulationResponse(
            String documentSource,
            @Nullable UUID policyId,
            int policyVersion,
            FactsView facts,
            DecisionView decision,
            List<TraceView> trace,
            List<String> lanes,
            List<LadderStep> ladder,
            List<Skip> skips,
            PickupView pickup,
            List<String> notes,
            List<String> violations,
            boolean providerCalled) {

        static SimulationResponse of(Simulation simulation) {
            return new SimulationResponse(
                    simulation.documentSource(),
                    simulation.policyId(),
                    simulation.policyVersion(),
                    FactsView.of(simulation.facts()),
                    DecisionView.of(simulation.decision()),
                    simulation.trace().stream().map(TraceView::of).toList(),
                    simulation.lanes(),
                    simulation.ladder(),
                    simulation.skips(),
                    PickupView.of(simulation.pickup()),
                    simulation.notes(),
                    simulation.violations(),
                    // Stated, not implied: a simulation asks no provider and no quote, ever.
                    false);
        }
    }

    public record FactsView(
            @Nullable String sourceSystemType,
            @Nullable UUID channelId,
            @Nullable UUID zoneId,
            int preparationMinutes,
            int distanceMeters,
            Instant confirmedAt,
            String branchTimezone,
            boolean prepaid) {

        static FactsView of(DispatchFacts facts) {
            return new FactsView(
                    facts.sourceSystemType(),
                    facts.channelId(),
                    facts.zoneId(),
                    facts.preparationMinutes(),
                    facts.distanceMeters(),
                    facts.confirmedAt(),
                    facts.branchZone().getId(),
                    facts.prepaid());
        }
    }

    public record DecisionView(
            String ruleId,
            SourcingMode mode,
            PartnerSet partners,
            Start dispatchAt,
            @Nullable Grouping grouping,
            List<Skip> skips) {

        static DecisionView of(DispatchDecision decision) {
            return new DecisionView(
                    decision.ruleId(),
                    decision.mode(),
                    decision.partners(),
                    decision.dispatchAt(),
                    decision.grouping(),
                    decision.skips());
        }
    }

    /** @param state {@code MATCHED}, {@code NOT_MATCHED}, {@code DISABLED} or {@code NOT_EVALUATED} */
    public record TraceView(
            String ruleId,
            String name,
            String state,
            @Nullable String failedCondition) {

        static TraceView of(RuleTrace trace) {
            return new TraceView(
                    trace.ruleId(),
                    trace.name(),
                    trace.state().name(),
                    trace.failedCondition() == null
                            ? null
                            : trace.failedCondition().name());
        }
    }

    public record PickupView(
            Instant confirmedAt,
            Instant sourceAt,
            Instant pickupWindowStart,
            Instant pickupWindowEnd,
            Instant latestAssignmentAt,
            int calculationVersion) {

        static PickupView of(PickupPlan plan) {
            return new PickupView(
                    plan.confirmedAt(),
                    plan.sourceAt(),
                    plan.pickupWindowStart(),
                    plan.pickupWindowEnd(),
                    plan.latestAssignmentAt(),
                    plan.calculationVersion());
        }
    }

    // ------------------------------------------------------------ wire: timings

    public record TimingResponse(
            int preparationLeadSeconds,
            int partnerLeadSeconds,
            int safetyBufferSeconds,
            int pickupToleranceSeconds,
            int offerRounds,
            int maxOfferSeconds,
            int latestAssignmentSlackSeconds,
            boolean isDefaults,
            @Nullable ScopeType winningScope,
            @Nullable UUID policyId,
            int policyVersion,
            int versionAtScope,
            List<SourcingPolicyAuthoringService.Level> levels) {

        static TimingResponse of(SourcingPolicyAuthoringService.Editor editor) {
            DeliverySourcingPolicy document = editor.document();
            return new TimingResponse(
                    document.preparationLeadSeconds(),
                    document.partnerLeadSeconds(),
                    document.safetyBufferSeconds(),
                    document.pickupToleranceSeconds(),
                    document.offerRounds(),
                    document.maxOfferSeconds(),
                    document.latestAssignmentSlackSeconds(),
                    editor.isDefaults(),
                    editor.winningScope(),
                    editor.policyId(),
                    editor.policyVersion(),
                    editor.versionAtScope(),
                    editor.levels());
        }
    }

    public record TimingWriteRequest(
            @NotNull Integer preparationLeadSeconds,
            @NotNull Integer partnerLeadSeconds,
            @NotNull Integer safetyBufferSeconds,
            @NotNull Integer pickupToleranceSeconds,
            @NotNull Integer offerRounds,
            @NotNull Integer maxOfferSeconds,
            @NotNull Integer latestAssignmentSlackSeconds,
            @NotBlank @Size(max = 1000) String reason) {

        DeliverySourcingPolicy toDocument() {
            // The record's own constructor refuses a nonsense number with IllegalArgumentException, which
            // would surface as a 500. A refusal names the field, as a 400 with ADR 0031's stable code.
            DeliverySourcingPolicy candidate;
            try {
                candidate = new DeliverySourcingPolicy(
                        preparationLeadSeconds,
                        partnerLeadSeconds,
                        safetyBufferSeconds,
                        pickupToleranceSeconds,
                        offerRounds,
                        maxOfferSeconds,
                        latestAssignmentSlackSeconds);
            } catch (IllegalArgumentException invalid) {
                throw new ApiException(ErrorCode.VALIDATION_FAILED, invalid.getMessage());
            }
            return candidate;
        }
    }
}
