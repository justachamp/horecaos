package uz.horecaos.platform.fulfillment.domain.sourcing;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.fulfillment.api.ShipmentBookingPort.PartnerOption;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Action;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Conditions;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Ladder;
import uz.horecaos.platform.fulfillment.domain.sourcing.DispatchRulesDocument.Rule;

/**
 * Which dispatch rule applies to an order, and why not the ones above it (ADR 0142
 * Decision 4: "the simulator is the evaluator").
 *
 * <p>A pure function in the genre of {@link SourcingPlanner} and {@link QuoteScoring}: no
 * clock, no database, no port. The runtime ({@code DeliveryPlanningService.open}) and the
 * simulator call the same method over the same facts, so what the simulator shows is what
 * happens -- and so is the property ADR 0014 requires of every selection, that it is
 * "explainable and reproducible from stored evidence": the evidence is this method's
 * arguments.
 *
 * <pre>
 * rule     = first enabled rule in document order whose present conditions all match,
 *            else document.default
 * decision = { ruleId | "DEFAULT", mode, partners, dispatchAt, grouping }
 * </pre>
 *
 * <p>Conditions are checked in a fixed order ({@link Condition}) and a rule that does not
 * match reports the <em>first</em> one that failed, which is the sentence an operator needs
 * ("not this zone") rather than a list of everything that was also wrong.
 */
public final class DispatchRuleEvaluator {

    private DispatchRuleEvaluator() {}

    /** A condition of the closed vocabulary, in the order they are checked. */
    public enum Condition {
        SOURCE,
        CHANNEL,
        ZONE,
        BRANCH,
        PREPARATION,
        DISTANCE,
        LOCAL_TIME,
        PREPAID
    }

    /** What happened to one rule. */
    public enum RuleState {

        /** This rule won. */
        MATCHED,

        /** Enabled, evaluated, and a condition failed. */
        NOT_MATCHED,

        /** Switched off: keeps its place and never matches. */
        DISABLED,

        /** After the winner: never looked at. */
        NOT_EVALUATED
    }

    /**
     * One rule's part in an evaluation.
     *
     * @param failedCondition the first condition that did not hold, for {@link RuleState#NOT_MATCHED}
     */
    public record RuleTrace(
            String ruleId,
            String name,
            RuleState state,
            @Nullable Condition failedCondition) {}

    /**
     * @param decision the action in force, with the rule that produced it
     * @param trace    every rule in document order, and what became of it
     * @param ladder   the partners that action leaves once applied to the branch's bound
     *                 installations. Empty when the evaluation was given none
     */
    public record Evaluation(DispatchDecision decision, List<RuleTrace> trace, Ladder ladder) {}

    /** {@link #evaluate(DispatchRulesDocument, DispatchFacts, List)} with no knowledge of what is bound: no skips can be recorded. */
    public static Evaluation evaluate(DispatchRulesDocument document, DispatchFacts facts) {
        return evaluate(document, facts, null);
    }

    /**
     * @param bound the branch's bound delivery partners, in the binding order ADR 0026 returns.
     *              Used to resolve {@code partners.order} and to record what could not be
     *              honoured; null when the caller does not know, which records no skips
     */
    public static Evaluation evaluate(
            DispatchRulesDocument document, DispatchFacts facts, @Nullable List<PartnerOption> bound) {

        List<RuleTrace> trace = new ArrayList<>(document.rules().size());
        Optional<Rule> winner = Optional.empty();
        for (Rule rule : document.rules()) {
            if (winner.isPresent()) {
                trace.add(new RuleTrace(rule.id(), rule.name(), RuleState.NOT_EVALUATED, null));
                continue;
            }
            if (!rule.enabled()) {
                trace.add(new RuleTrace(rule.id(), rule.name(), RuleState.DISABLED, null));
                continue;
            }
            Condition failed = firstFailure(rule.when(), facts);
            if (failed == null) {
                winner = Optional.of(rule);
                trace.add(new RuleTrace(rule.id(), rule.name(), RuleState.MATCHED, null));
            } else {
                trace.add(new RuleTrace(rule.id(), rule.name(), RuleState.NOT_MATCHED, failed));
            }
        }

        Action action = winner.map(Rule::then).orElse(document.fallback());
        String ruleId = winner.map(Rule::id).orElse(DispatchDecision.DEFAULT_RULE);
        Ladder ladder = bound == null
                ? new Ladder(List.of(), List.of())
                : action.partners().apply(bound);
        return new Evaluation(DispatchDecision.of(ruleId, action, ladder.skips()), List.copyOf(trace), ladder);
    }

    /** The first condition of {@code when} that {@code facts} does not satisfy, or null when all hold. */
    static @Nullable Condition firstFailure(Conditions when, DispatchFacts facts) {
        if (!when.sources().isEmpty()
                && (facts.sourceSystemType() == null || !when.sources().contains(facts.sourceSystemType()))) {
            return Condition.SOURCE;
        }
        if (!when.channelIds().isEmpty()
                && (facts.channelId() == null || !when.channelIds().contains(facts.channelId()))) {
            return Condition.CHANNEL;
        }
        if (!when.zoneIds().isEmpty()
                && (facts.zoneId() == null || !when.zoneIds().contains(facts.zoneId()))) {
            // An order with no zone evidence never matches a rule that names zones: guessing
            // would send an order priced outside every zone down a far-zone rule.
            return Condition.ZONE;
        }
        if (!when.locationIds().isEmpty() && !when.locationIds().contains(facts.locationId())) {
            return Condition.BRANCH;
        }
        if (when.prepMinutes() != null && !when.prepMinutes().contains(facts.preparationMinutes())) {
            return Condition.PREPARATION;
        }
        if (when.distanceMeters() != null && !when.distanceMeters().contains(facts.distanceMeters())) {
            return Condition.DISTANCE;
        }
        if (when.localTime() != null) {
            ZonedDateTime local = facts.confirmedAt().atZone(facts.branchZone());
            int minuteOfDay = local.getHour() * 60 + local.getMinute();
            if (!when.localTime().contains(local.getDayOfWeek(), minuteOfDay)) {
                return Condition.LOCAL_TIME;
            }
        }
        if (when.prepaid() != null && when.prepaid() != facts.prepaid()) {
            return Condition.PREPAID;
        }
        return null;
    }
}
