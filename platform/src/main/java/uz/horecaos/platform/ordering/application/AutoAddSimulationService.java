package uz.horecaos.platform.ordering.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.ordering.domain.AutoAddEvaluator;
import uz.horecaos.platform.ordering.domain.AutoAddEvaluator.Injection;
import uz.horecaos.platform.ordering.domain.AutoAddEvaluator.Offer;
import uz.horecaos.platform.ordering.domain.AutoAddEvaluator.Outcome;
import uz.horecaos.platform.ordering.domain.AutoAddEvaluator.Skipped;
import uz.horecaos.platform.ordering.domain.BasketFacts;
import uz.horecaos.platform.ordering.domain.PublishedAutoAddRule;

/**
 * The console's simulator for cart-injection rules (ADR 0158): a hypothetical basket goes through the
 * very {@link AutoAddEvaluator} the cart runs, so what an author previews is what the cart does.
 *
 * <p>It reads the authoring tables (drafts and active rules, never archived), not the publication: an
 * author wants to see a rule before it is live. Nothing is written and no cart is involved. The only
 * check run on a rule's variant is that it is an active variant of the brand; stock, sale windows and
 * prices belong to a real cart at a real branch, and a rule that fails them there is skipped, which this
 * preview cannot know.
 */
@Service
public class AutoAddSimulationService {

    private final AutoAddDraftRules drafts;

    public AutoAddSimulationService(AutoAddDraftRules drafts) {
        this.drafts = drafts;
    }

    /** A hypothetical basket line. */
    public record SimulatedLine(UUID variantId, BigDecimal quantity) {}

    /** What the simulator was asked. */
    public record Simulation(
            String fulfillmentMode,
            String channelSystemType,
            @Nullable UUID locationId,
            List<SimulatedLine> lines,
            Set<UUID> declinedRuleIds) {}

    /** One rule's result: {@code INJECT}, {@code OFFER} or {@code SKIPPED}. */
    public record SimulatedOutcome(
            UUID ruleId,
            String ruleCode,
            String ruleStatus,
            String outcome,
            @Nullable UUID variantId,
            @Nullable BigDecimal quantity,
            @Nullable String reason) {}

    /** The simulator's answer: the basket's totals as a rule reads them, and what each rule did. */
    public record Result(BigDecimal units, BigDecimal portions, List<SimulatedOutcome> outcomes) {}

    @Transactional(readOnly = true)
    public Result simulate(UUID tenantId, UUID brandId, Simulation simulation) {
        List<AutoAddDraftRules.DraftRule> rules = drafts.rules(tenantId, brandId);
        Set<UUID> variantIds = new LinkedHashSet<>();
        simulation.lines().forEach(line -> variantIds.add(line.variantId()));
        Map<UUID, AutoAddDraftRules.VariantFacts> facts = drafts.variantFacts(tenantId, brandId, variantIds);

        List<BasketFacts.Line> basket = new ArrayList<>();
        BigDecimal units = BigDecimal.ZERO;
        for (SimulatedLine line : simulation.lines()) {
            AutoAddDraftRules.VariantFacts fact = facts.get(line.variantId());
            basket.add(BasketFacts.Line.of(
                    line.variantId(),
                    fact == null ? null : fact.productId(),
                    fact == null ? Set.of() : fact.categoryIds(),
                    line.quantity(),
                    fact == null ? null : fact.portionsPerUnit()));
            units = units.add(line.quantity());
        }
        BasketFacts basketFacts = new BasketFacts(basket);
        BigDecimal portions = basket.stream().map(BasketFacts.Line::portions).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<PublishedAutoAddRule> published =
                rules.stream().map(AutoAddDraftRules.DraftRule::rule).toList();
        Map<UUID, String> statusById = new LinkedHashMap<>();
        rules.forEach(draft -> statusById.put(draft.rule().ruleId(), draft.status()));

        Set<UUID> addedVariants = new LinkedHashSet<>();
        published.forEach(rule -> {
            if (rule.addVariantId() != null) {
                addedVariants.add(rule.addVariantId());
            }
            rule.bands().forEach(band -> addedVariants.add(band.addVariantId()));
        });
        Map<UUID, AutoAddDraftRules.VariantFacts> addedFacts = drafts.variantFacts(tenantId, brandId, addedVariants);

        List<Outcome> outcomes = AutoAddEvaluator.evaluate(
                published,
                basketFacts,
                new AutoAddEvaluator.Context(
                        simulation.fulfillmentMode(), simulation.channelSystemType(), simulation.locationId()),
                simulation.declinedRuleIds(),
                (rule, variantId) -> {
                    AutoAddDraftRules.VariantFacts fact = addedFacts.get(variantId);
                    return fact != null && fact.active() ? Optional.empty() : Optional.of("VARIANT_NOT_ACTIVE");
                });

        List<SimulatedOutcome> result = new ArrayList<>();
        for (Outcome outcome : outcomes) {
            String status = statusById.getOrDefault(outcome.rule().ruleId(), "DRAFT");
            if (outcome instanceof Injection injection) {
                result.add(new SimulatedOutcome(
                        injection.rule().ruleId(),
                        injection.rule().code(),
                        status,
                        "INJECT",
                        injection.variantId(),
                        injection.quantity(),
                        null));
            } else if (outcome instanceof Offer offer) {
                result.add(new SimulatedOutcome(
                        offer.rule().ruleId(),
                        offer.rule().code(),
                        status,
                        "OFFER",
                        offer.variantId(),
                        offer.quantity(),
                        null));
            } else if (outcome instanceof Skipped skipped) {
                result.add(new SimulatedOutcome(
                        skipped.rule().ruleId(),
                        skipped.rule().code(),
                        status,
                        "SKIPPED",
                        skipped.variantId(),
                        null,
                        skipped.reason()));
            }
        }
        return new Result(units, portions, List.copyOf(result));
    }
}
