package uz.horecaos.platform.fulfillment.application;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort;
import uz.horecaos.platform.fulfillment.api.DeliveryOrderPort.DispatchOrderFacts;
import uz.horecaos.platform.fulfillment.api.InternalFleetPort;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.PartnerOption;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliveryPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.DeliverySourcingPolicy;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchDecision;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchFacts;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRuleEvaluator;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRuleEvaluator.Evaluation;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRuleEvaluator.RuleTrace;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Skip;
import uz.horecaos.platform.fulfillment.domain.sourcing.PickupPlan;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingMode;
import uz.horecaos.platform.fulfillment.domain.sourcing.SourcingPlanner;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryPlanStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchBranchStore.DispatchBranch;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchRuleStore;
import uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDispatchRuleStore.InstallationRow;
import uz.horecaos.platform.iam.api.ResourceScope;
import uz.horecaos.platform.tenancy.api.PolicyResolver;
import uz.horecaos.platform.tenancy.api.ResolvedPolicy;
import uz.horecaos.platform.tenancy.api.SalesChannelSystemType;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * Which rule an order would match, and which provider it would go to -- without sending anyone
 * anywhere (ADR 0142 Decision 4).
 *
 * <p><strong>The simulator is the evaluator.</strong> It calls {@link DispatchRuleEvaluator} over the same
 * facts and {@code PartnerSet.apply} over the same bound-partner list that {@code
 * DeliveryPlanningService.open} and {@code DeliverySourcingService.source} call, and {@link PickupPlan}
 * for the start, so what it shows is what happens -- not a second implementation that agrees until it
 * does not. A test drives a plan through the live path and the same facts through here and compares the
 * decisions.
 *
 * <p>It takes a scope, an optional <em>draft</em> document (what the editor holds unsaved) and a scenario:
 * typed facts, or the id of a recent plan whose facts are re-read. A plan is re-read without decrypting
 * anything -- its channel, zone and prepayment come from {@link DeliveryOrderPort#dispatchFacts} -- so
 * asking "which rule would this order match today" never reveals where a customer lives.
 *
 * <p>It calls no provider, requests no quote and publishes no signal, and the response says so. A {@code
 * CHEAPEST} rule's winner is decided by quotes at the moment of booking, which a simulator cannot and must
 * not ask for; it names the candidates in the order the booking would consider them and says the choice among
 * them is made then.
 *
 * <p><strong>The one exception is a rule that groups, simulated against a recent plan</strong> (ADR 0170): the
 * feed is measured exactly as sourcing measures it, which reads the drop-off point of that plan and of the
 * orders the candidate couriers carry, under the purpose {@code DISPATCH_GROUPING}. Each read is a recorded
 * reveal in the audit trail, attributed to the person who asked, and what leaves is a distance, never a place.
 * It is done for the document in force only while the platform's grouping switch is on, because only then does
 * the live path do it; a draft is measured either way, since previewing the feed before the switch is turned on
 * is how the record rolls grouping out.
 */
@Service
public class DispatchRuleSimulator {

    private final DispatchRulesAuthoringService rules;
    private final PolicyResolver policies;
    private final ShipmentBookingPort bookings;
    private final DeliveryOrderPort orders;
    private final JdbcDeliveryPlanStore plans;
    private final JdbcDispatchBranchStore branches;
    private final JdbcDispatchRuleStore store;
    private final @Nullable InternalFleetPort fleet;

    /** A simulator that cannot measure the fleet: it says so in a note instead of inventing a feed. */
    public DispatchRuleSimulator(
            DispatchRulesAuthoringService rules,
            PolicyResolver policies,
            ShipmentBookingPort bookings,
            DeliveryOrderPort orders,
            JdbcDeliveryPlanStore plans,
            JdbcDispatchBranchStore branches,
            JdbcDispatchRuleStore store) {
        this(rules, policies, bookings, orders, plans, branches, store, null);
    }

    @Autowired
    public DispatchRuleSimulator(
            DispatchRulesAuthoringService rules,
            PolicyResolver policies,
            ShipmentBookingPort bookings,
            DeliveryOrderPort orders,
            JdbcDeliveryPlanStore plans,
            JdbcDispatchBranchStore branches,
            JdbcDispatchRuleStore store,
            @Nullable InternalFleetPort fleet) {
        this.rules = rules;
        this.policies = policies;
        this.bookings = bookings;
        this.orders = orders;
        this.plans = plans;
        this.branches = branches;
        this.store = store;
        this.fleet = fleet;
    }

    /**
     * Evaluates one order against one document.
     *
     * @param editingScope the scope the {@code draft} is being written for, which decides what the draft
     *                     may name; ignored without a draft
     * @param draft        an unsaved document to evaluate instead of the one in force, or null
     * @param scenario     typed facts, or null when {@code planId} supplies them
     * @param planId       a plan at this branch whose facts are re-read, or null
     */
    public Simulation simulate(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            ResourceScope editingScope,
            @Nullable DispatchRulesDocument draft,
            @Nullable Scenario scenario,
            @Nullable UUID planId) {
        return simulate(tenantId, brandId, locationId, editingScope, draft, scenario, planId, null);
    }

    /**
     * The same, on behalf of a person: when a rule that groups is measured against a recent plan, the drop-offs
     * that measurement reveals are recorded as read for them (ADR 0029).
     *
     * @param requestedBy who asked; null records the reveals as the sourcing job's
     */
    public Simulation simulate(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            ResourceScope editingScope,
            @Nullable DispatchRulesDocument draft,
            @Nullable Scenario scenario,
            @Nullable UUID planId,
            @Nullable ActorRef requestedBy) {

        if ((scenario == null) == (planId == null)) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Give either a scenario or the id of a plan, not both and not neither");
        }
        DispatchBranch branch = branches.find(tenantId, brandId, locationId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such branch"));
        ResourceScope locationScope = ResourceScope.location(tenantId, brandId, locationId);

        DispatchFacts facts = scenario != null
                ? factsOf(scenario, brandId, locationId, branch.timezone())
                : factsOfPlan(tenantId, brandId, locationId, Objects.requireNonNull(planId));

        // The document under test: the draft when there is one, else what is in force for this branch.
        String source;
        UUID policyId = null;
        int policyVersion = 0;
        DispatchRulesDocument document;
        List<String> violations = List.of();
        if (draft != null) {
            source = "DRAFT";
            document = draft;
            violations = rules.violations(editingScope, draft);
        } else {
            Optional<ResolvedPolicy<DispatchRulesDocument>> inForce =
                    policies.resolveUncached(DeliverySourcingPolicies.DISPATCH_RULES, locationScope);
            source = inForce.isPresent() ? "PUBLISHED" : "BUILT_IN";
            document = inForce.map(ResolvedPolicy::document).orElseGet(DispatchRulesDocument::builtIn);
            policyId = inForce.map(ResolvedPolicy::policyId).orElse(null);
            policyVersion = inForce.map(ResolvedPolicy::policyVersion).orElse(0);
        }

        // The live bound-partner list for this branch, so the ladder is the one the runtime would use.
        List<PartnerOption> bound = bookings.partners(tenantId, brandId, locationId);
        Evaluation evaluation = DispatchRuleEvaluator.evaluate(document, facts, bound);
        DispatchDecision decision = evaluation.decision();

        DeliverySourcingPolicy timing = policies.resolveUncached(DeliverySourcingPolicies.SOURCING, locationScope)
                .map(ResolvedPolicy::document)
                .orElse(DeliverySourcingPolicy.DEFAULTS);
        PickupPlan pickup = PickupPlan.forOrder(
                facts.confirmedAt(), facts.preparation(), facts.branchZone(), timing, decision.dispatchAt());

        Map<UUID, InstallationRow> installations = store.deliveryInstallations(tenantId).stream()
                .collect(Collectors.toMap(InstallationRow::id, Function.identity(), (first, second) -> first));
        List<LadderStep> ladder = new ArrayList<>();
        List<PartnerOption> options = evaluation.ladder().options();
        for (int position = 0; position < options.size(); position++) {
            PartnerOption option = options.get(position);
            InstallationRow installation =
                    option.installationId() == null ? null : installations.get(option.installationId());
            ladder.add(new LadderStep(
                    position + 1,
                    option.installationId(),
                    option.providerType(),
                    installation == null ? null : installation.displayName()));
        }

        List<String> notes = new ArrayList<>(notesFor(decision, ladder, facts));
        GroupingFeed feed = groupingFeed(
                tenantId, brandId, locationId, planId, decision, "DRAFT".equals(source), requestedBy, notes);
        return new Simulation(
                source,
                policyId,
                policyVersion,
                facts,
                decision,
                evaluation.trace(),
                lanesOf(decision.mode()),
                ladder,
                decision.skips(),
                pickup,
                List.copyOf(notes),
                violations,
                feed);
    }

    /**
     * What the grouping bias would see for a plan right now (ADR 0170): each candidate courier's measured
     * distance to a carried drop-off and the pay basis the offer would be stamped with.
     *
     * <p>Only for a recent plan, because a typed scenario has no door to measure from; a scenario gets a note
     * saying so. It reads the live fleet and asks no provider and no quote; the reveal is the same named-purpose
     * decrypt the live feed makes, and what comes back is a distance, never a place.
     */
    private @Nullable GroupingFeed groupingFeed(
            UUID tenantId,
            UUID brandId,
            UUID locationId,
            @Nullable UUID planId,
            DispatchDecision decision,
            boolean draft,
            @Nullable ActorRef requestedBy,
            List<String> notes) {

        DispatchRulesDocument.Grouping grouping = decision.grouping();
        if (grouping == null || !decision.mode().usesFleet()) {
            return null;
        }
        if (!draft && !rules.groupingAllowed()) {
            // The kill switch (ADR 0170 Decision 6) is off, so the live path ignores the grouping of the
            // document in force: it would not be measured, ranked on or stamped. Showing a feed for it would
            // be a simulation of something that does not happen, and a read of customers' doors for nothing.
            // A draft is different on purpose: previewing the feed with the switch still off is the rollout's
            // own step (ADR 0170, rollout), and the draft's violations already say it cannot be published yet.
            notes.add("GROUPING_SWITCHED_OFF");
            return null;
        }
        int ceiling = store.highestCourierConcurrency(tenantId);
        if (ceiling < grouping.maxOrdersPerRun()) {
            notes.add("GROUPING_CEILING_BELOW_RUN");
        }
        if (planId == null) {
            notes.add("GROUPING_NEEDS_A_PLAN_TO_MEASURE");
            return null;
        }
        if (fleet == null) {
            notes.add("FLEET_NOT_MEASURABLE");
            return null;
        }
        DeliveryPlan plan = plans.find(tenantId, planId).orElse(null);
        if (plan == null) {
            return null;
        }
        List<InternalFleetPort.FleetCandidate> candidates = fleet.candidates(
                tenantId,
                brandId,
                locationId,
                plan.distanceMeters() == null ? 0 : plan.distanceMeters(),
                new InternalFleetPort.GroupingProbe(
                        planId, grouping.mergeRadiusMeters(), grouping.maxOrdersPerRun(), requestedBy));
        List<GroupedCandidate> measured = candidates.stream()
                .map(candidate -> new GroupedCandidate(
                        candidate.courierId(),
                        candidate.activeAssignments(),
                        candidate.concurrencyCeiling(),
                        SourcingPlanner.isGroupable(candidate, grouping) ? candidate.groupableWithMetres() : null,
                        SourcingPlanner.isGroupable(candidate, grouping) && candidate.runIncrementPay()
                                ? "RUN_INCREMENT"
                                : "DIRECT"))
                .toList();
        return new GroupingFeed(candidates.size(), measured);
    }

    private DispatchFacts factsOf(Scenario scenario, UUID brandId, UUID locationId, ZoneId branchZone) {
        if (scenario.sourceSystemType() != null
                && Arrays.stream(SalesChannelSystemType.values())
                        .noneMatch(type -> type.name().equals(scenario.sourceSystemType()))) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED, "\"" + scenario.sourceSystemType() + "\" is not a sales channel type");
        }
        if (scenario.preparationMinutes() < 0 || scenario.preparationMinutes() > 1440) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "preparationMinutes must be between 0 and 1440");
        }
        if (scenario.distanceMeters() < 0 || scenario.distanceMeters() > 100_000) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "distanceMeters must be between 0 and 100000");
        }
        return new DispatchFacts(
                scenario.sourceSystemType(),
                scenario.channelId(),
                scenario.zoneId(),
                brandId,
                locationId,
                Duration.ofMinutes(scenario.preparationMinutes()),
                scenario.distanceMeters(),
                scenario.confirmedAt(),
                branchZone,
                scenario.prepaid());
    }

    private DispatchFacts factsOfPlan(UUID tenantId, UUID brandId, UUID locationId, UUID planId) {
        DeliveryPlan plan = plans.find(tenantId, planId)
                .filter(found ->
                        found.brandId().equals(brandId) && found.locationId().equals(locationId))
                .orElseThrow(
                        () -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such delivery plan at this branch"));
        Optional<DispatchOrderFacts> order = orders.dispatchFacts(tenantId, plan.orderId());
        return new DispatchFacts(
                order.map(DispatchOrderFacts::channelSystemType).orElse(null),
                order.map(DispatchOrderFacts::channelId).orElse(null),
                order.map(DispatchOrderFacts::zoneId).orElse(null),
                brandId,
                locationId,
                plan.pickup().preparation(),
                plan.distanceMeters() == null ? 0 : plan.distanceMeters(),
                plan.pickup().confirmedAt(),
                plan.pickup().branchZone(),
                order.map(DispatchOrderFacts::prepaid).orElse(false));
    }

    /** The lanes a mode asks, in the order it asks them. */
    static List<String> lanesOf(SourcingMode mode) {
        return switch (mode) {
            case FLEET_FIRST -> List.of("FLEET", "PARTNERS");
            case FLEET_ONLY -> List.of("FLEET");
            case PARTNER_ONLY -> List.of("PARTNERS");
            case PARTNER_FIRST -> List.of("PARTNERS", "FLEET");
            case MANUAL -> List.of();
        };
    }

    private static List<String> notesFor(DispatchDecision decision, List<LadderStep> ladder, DispatchFacts facts) {
        List<String> notes = new ArrayList<>();
        if (decision.mode().usesPartners() && ladder.isEmpty()) {
            notes.add("NO_PARTNER_AVAILABLE");
        }
        if (decision.mode().usesPartners()
                && decision.partners().selection() == DispatchRulesDocument.PartnerSelection.CHEAPEST
                && ladder.size() > 1) {
            notes.add("WINNER_DECIDED_BY_QUOTES");
        }
        if (decision.mode() == SourcingMode.MANUAL) {
            notes.add("OPERATIONS_ASSIGNS");
        }
        if (facts.zoneId() == null) {
            notes.add("NO_ZONE_EVIDENCE");
        }
        if (decision.grouping() != null) {
            notes.add("GROUPING_REQUESTED");
        }
        return List.copyOf(notes);
    }

    /**
     * Typed facts for a simulation. Everything is a fact a rule may ask and nothing else: no address, no
     * customer, no money.
     */
    public record Scenario(
            @Nullable String sourceSystemType,
            @Nullable UUID channelId,
            @Nullable UUID zoneId,
            int preparationMinutes,
            int distanceMeters,
            Instant confirmedAt,
            boolean prepaid) {}

    /** One partner the decision would try, in order. */
    public record LadderStep(
            int position,
            @Nullable UUID installationId,
            String providerType,
            @Nullable String displayName) {}

    /**
     * @param documentSource {@code DRAFT}, {@code PUBLISHED} or {@code BUILT_IN}
     * @param trace          every rule in document order and what became of it
     * @param lanes          {@code FLEET} and {@code PARTNERS} in the order the mode asks them
     * @param pickup         the time model the order would be planned under: when sourcing starts and
     *                       the window it is held to
     * @param notes          stable codes for what an operator should look at
     * @param violations     for a draft, why it would not be published; empty otherwise
     * @param grouping       for a recent plan under a rule that groups: what the live fleet measured and the pay
     *                       basis each offer would carry (ADR 0170); null otherwise
     */
    public record Simulation(
            String documentSource,
            @Nullable UUID policyId,
            int policyVersion,
            DispatchFacts facts,
            DispatchDecision decision,
            List<RuleTrace> trace,
            List<String> lanes,
            List<LadderStep> ladder,
            List<Skip> skips,
            PickupPlan pickup,
            List<String> notes,
            List<String> violations,
            @Nullable GroupingFeed grouping) {

        /** A simulation with no grouping feed: every caller that predates ADR 0170. */
        public Simulation(
                String documentSource,
                @Nullable UUID policyId,
                int policyVersion,
                DispatchFacts facts,
                DispatchDecision decision,
                List<RuleTrace> trace,
                List<String> lanes,
                List<LadderStep> ladder,
                List<Skip> skips,
                PickupPlan pickup,
                List<String> notes,
                List<String> violations) {
            this(
                    documentSource,
                    policyId,
                    policyVersion,
                    facts,
                    decision,
                    trace,
                    lanes,
                    ladder,
                    skips,
                    pickup,
                    notes,
                    violations,
                    null);
        }
    }

    /**
     * The grouping feed for one plan: how many couriers were candidates and what was measured for each.
     *
     * @param candidates how many couriers the fleet would offer the plan to
     * @param measured   each of them with their distance to a carried drop-off, or null when they were not
     *                   groupable, and the pay basis their offer would be stamped with
     */
    public record GroupingFeed(int candidates, List<GroupedCandidate> measured) {}

    /** One candidate courier of the feed: ids and numbers only, no name, no place (ADR 0029). */
    public record GroupedCandidate(
            UUID courierId,
            int activeAssignments,
            int concurrencyCeiling,
            @Nullable Integer groupableWithMetres,
            String payBasis) {}
}
