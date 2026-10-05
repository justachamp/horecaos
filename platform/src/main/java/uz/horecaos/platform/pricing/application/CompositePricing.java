package uz.horecaos.platform.pricing.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.pricing.domain.QuoteRequest;

/**
 * The facts and the selection rules of composite products (ADR 0136), as pure
 * functions of values.
 *
 * <p>Pricing never reads the catalog while it is computing: {@link
 * CompositeProductsLookup} resolves every fact here before {@link PricingEngine}
 * runs, and this class only decides what those facts allow. That is what keeps
 * the engine deterministic -- two runs over the same {@link CompositeInputs} take
 * the same lines in the same order to the same total -- and what lets every rule
 * below be tested on a literal.
 *
 * <p>What this class refuses it refuses with a {@link CompositeSelectionException}
 * carrying a stable code and the id that caused it, because a storefront can say
 * "this group needs one more pick" and cannot say anything useful about a failed
 * assertion.
 */
public final class CompositePricing {

    private CompositePricing() {}

    /** One choice a combo asks for, as the quote needs it. */
    public record ComboGroupFact(
            UUID id,
            UUID containerVariantId,
            int minimumSelections,
            int maximumSelections,
            boolean allowSameComponentMultipleTimes,
            int sortOrder) {}

    /** A variant offered inside a group; only active pairings of active variants are facts. */
    public record ComboComponentFact(
            UUID id, UUID comboGroupId, UUID componentVariantId, int defaultQuantity, int sortOrder) {}

    /**
     * A modifier option the server applies without the customer choosing it.
     *
     * <p>Its price is an ordinary {@code MODIFIER_OPTION} price; what makes it hidden
     * is that the customer never selects it.
     */
    public record HiddenCharge(UUID groupId, UUID optionId) {}

    /** A modifier option a selection refers to, with the variant it links, if any. */
    public record OptionFact(
            UUID optionId, UUID groupId, @Nullable UUID linkedVariantId, int maximumQuantity) {}

    /**
     * A customer-facing modifier group a linked variant offers, with the values in
     * force for that variant: its attachment's overrides laid over the shared group.
     */
    public record NestedGroupFact(
            UUID groupId,
            boolean required,
            int minimumSelections,
            int maximumSelections,
            boolean allowSameOptionMultipleTimes,
            Set<UUID> optionIds) {}

    /**
     * Everything composite pricing resolved before the engine runs.
     *
     * @param comboGroupIdsByContainer the active groups of each container variant
     *        among the cart's lines; a variant absent here is not a container
     * @param comboComponentPrices {@code COMBO_COMPONENT} amounts in the resolved
     *        price book, per unit, by component id
     * @param hiddenChargesByVariant the hidden groups that apply on this order's
     *        fulfilment mode, by the priced variant that carries them. Already
     *        filtered to the mode and checked unambiguous: a group that cannot be
     *        resolved is refused when it is looked up, never guessed here
     * @param optionFacts facts for every first- and second-level option a line names
     * @param nestedGroupsByVariant for a variant that a first-level option links, the
     *        customer-facing groups it offers
     * @param comboSelectionIds one id per combo cart line, minted when the quote is
     *        built and shared by every component line that line becomes. A value the
     *        caller supplies, not one the engine invents, so the engine stays pure
     */
    public record CompositeInputs(
            Map<UUID, ComboGroupFact> comboGroups,
            Map<UUID, List<UUID>> comboGroupIdsByContainer,
            Map<UUID, ComboComponentFact> comboComponents,
            Map<UUID, Long> comboComponentPrices,
            Map<UUID, List<HiddenCharge>> hiddenChargesByVariant,
            Map<UUID, OptionFact> optionFacts,
            Map<UUID, List<NestedGroupFact>> nestedGroupsByVariant,
            Map<String, UUID> comboSelectionIds) {

        public CompositeInputs {
            comboGroups = Map.copyOf(comboGroups);
            comboGroupIdsByContainer = Map.copyOf(comboGroupIdsByContainer);
            comboComponents = Map.copyOf(comboComponents);
            comboComponentPrices = Map.copyOf(comboComponentPrices);
            hiddenChargesByVariant = Map.copyOf(hiddenChargesByVariant);
            optionFacts = Map.copyOf(optionFacts);
            nestedGroupsByVariant = Map.copyOf(nestedGroupsByVariant);
            comboSelectionIds = Map.copyOf(comboSelectionIds);
        }

        public static CompositeInputs none() {
            return new CompositeInputs(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }

        public boolean isContainer(UUID variantId) {
            return comboGroupIdsByContainer.containsKey(variantId);
        }

        public List<HiddenCharge> hiddenChargesOf(UUID variantId) {
            return hiddenChargesByVariant.getOrDefault(variantId, List.of());
        }
    }

    /** One component line a combo order becomes, in the order the quote lists it. */
    public record ComboLine(ComboComponentFact component, int groupSortOrder, int pickQuantity) {

        /** Units of the component this pick puts on the order for one combo. */
        public int unitsPerCombo() {
            return Math.multiplyExact(component.defaultQuantity(), pickQuantity);
        }
    }

    /**
     * A selection the facts do not allow.
     *
     * <p>Codes are stable: a client branches on them, and {@code subjectId} names the
     * group, component or option so the message can point at what to fix.
     */
    public static final class CompositeSelectionException extends RuntimeException {

        private final String code;
        private final transient UUID subjectId;

        public CompositeSelectionException(String code, UUID subjectId, String message) {
            super(message);
            this.code = code;
            this.subjectId = subjectId;
        }

        public String code() {
            return code;
        }

        public UUID subjectId() {
            return subjectId;
        }
    }

    // ---------------------------------------------------------------- combos

    /**
     * Resolves a line's combo picks into the component lines it becomes, or null when
     * the line is not a combo at all.
     *
     * <p>Ordered by group, then component, by sort order and then id -- never by the
     * order the customer's request happened to list them -- so the same selection
     * produces the same lines, with the same positions, on every run.
     *
     * @throws CompositeSelectionException when the selection is not one the combo allows
     */
    public static @Nullable List<ComboLine> resolveCombo(QuoteRequest.Line line, CompositeInputs inputs) {
        List<UUID> groupIds = inputs.comboGroupIdsByContainer().get(line.variantId());

        if (groupIds == null) {
            if (!line.comboPicks().isEmpty()) {
                throw new CompositeSelectionException(
                        "COMBO_NOT_CONFIGURED",
                        line.variantId(),
                        "Variant %s is not a combo on the live menu (one that was authored or changed since the last publication is not sold until it is published) but the line carries combo picks"
                                .formatted(line.variantId()));
            }
            return null;
        }

        // The container is never priced or sold directly (ADR 0136): there is no line
        // for it to be, so an order for it with nothing picked has nothing to charge.
        if (line.comboPicks().isEmpty()) {
            throw new CompositeSelectionException(
                    "COMBO_SELECTION_REQUIRED",
                    line.variantId(),
                    "Variant %s is a combo and is sold only through its components".formatted(line.variantId()));
        }
        // Modifiers belong to a priced line, and a combo's container is not one. A
        // charge here would have no line to land on.
        if (!line.modifierOptionIds().isEmpty() || !line.nestedModifiers().isEmpty()) {
            throw new CompositeSelectionException(
                    "COMBO_MODIFIERS_NOT_SUPPORTED",
                    line.variantId(),
                    "A combo line takes no modifiers of its own; its components are priced individually");
        }

        // The same component picked twice in a request is one pick of two.
        Map<UUID, Integer> picked = new LinkedHashMap<>();
        for (QuoteRequest.ComboPick pick : line.comboPicks()) {
            picked.merge(pick.componentId(), pick.quantity(), Math::addExact);
        }

        Map<UUID, Integer> perGroup = new LinkedHashMap<>();
        List<ComboLine> lines = new ArrayList<>();
        for (Map.Entry<UUID, Integer> entry : picked.entrySet()) {
            ComboComponentFact component = inputs.comboComponents().get(entry.getKey());
            if (component == null || !groupIds.contains(component.comboGroupId())) {
                throw new CompositeSelectionException(
                        "COMBO_COMPONENT_NOT_OFFERED",
                        entry.getKey(),
                        "Component %s is not offered by combo %s".formatted(entry.getKey(), line.variantId()));
            }
            ComboGroupFact group = inputs.comboGroups().get(component.comboGroupId());
            if (group == null) {
                throw new CompositeSelectionException(
                        "COMBO_COMPONENT_NOT_OFFERED",
                        entry.getKey(),
                        "Component %s has no active group".formatted(entry.getKey()));
            }
            if (entry.getValue() > 1 && !group.allowSameComponentMultipleTimes()) {
                throw new CompositeSelectionException(
                        "COMBO_COMPONENT_NOT_REPEATABLE",
                        component.id(),
                        "Component %s may be picked once in this group".formatted(component.id()));
            }
            perGroup.merge(group.id(), entry.getValue(), Math::addExact);
            lines.add(new ComboLine(component, group.sortOrder(), entry.getValue()));
        }

        // Every group of the container, not only the ones the customer touched: a
        // group they skipped entirely is exactly the one whose minimum is unmet.
        for (UUID groupId : groupIds) {
            ComboGroupFact group = inputs.comboGroups().get(groupId);
            if (group == null) {
                continue;
            }
            int count = perGroup.getOrDefault(groupId, 0);
            if (count < group.minimumSelections()) {
                throw new CompositeSelectionException(
                        "COMBO_GROUP_MINIMUM_NOT_MET",
                        groupId,
                        "Combo group %s needs at least %d picks and has %d"
                                .formatted(groupId, group.minimumSelections(), count));
            }
            if (count > group.maximumSelections()) {
                throw new CompositeSelectionException(
                        "COMBO_GROUP_MAXIMUM_EXCEEDED",
                        groupId,
                        "Combo group %s allows at most %d picks and has %d"
                                .formatted(groupId, group.maximumSelections(), count));
            }
        }

        lines.sort(Comparator.comparingInt(ComboLine::groupSortOrder)
                .thenComparing(comboLine -> comboLine.component().comboGroupId())
                .thenComparingInt(comboLine -> comboLine.component().sortOrder())
                .thenComparing(comboLine -> comboLine.component().id()));
        return List.copyOf(lines);
    }

    // ---------------------------------------------------------------- nesting

    /**
     * Validates a line's second-level selections and returns the option ids to price.
     *
     * <p>One level and no more (ADR 0136). A selection whose parent is itself a
     * nested option is a third level and is refused with {@code
     * MODIFIER_NESTING_DEPTH_EXCEEDED} rather than priced as if it were a first-level
     * choice, and a first-level option whose linked variant has a required group the
     * customer never answered is refused even when they sent no nested selection at
     * all -- an unanswered required group is exactly what a missing selection looks like.
     *
     * @throws CompositeSelectionException when a selection is not one the facts allow
     */
    public static List<UUID> resolveNested(QuoteRequest.Line line, CompositeInputs inputs) {
        Set<UUID> firstLevel = new HashSet<>(line.modifierOptionIds());
        Set<UUID> secondLevel = new HashSet<>();
        line.nestedModifiers().forEach(nested -> secondLevel.add(nested.optionId()));

        Map<UUID, List<UUID>> childrenOfParent = new LinkedHashMap<>();
        for (QuoteRequest.NestedModifier nested : line.nestedModifiers()) {
            if (!firstLevel.contains(nested.parentOptionId())) {
                if (secondLevel.contains(nested.parentOptionId())) {
                    throw new CompositeSelectionException(
                            "MODIFIER_NESTING_DEPTH_EXCEEDED",
                            nested.optionId(),
                            "Option %s would be a third level; one level of nesting is supported"
                                    .formatted(nested.optionId()));
                }
                throw new CompositeSelectionException(
                        "MODIFIER_NESTED_PARENT_NOT_SELECTED",
                        nested.parentOptionId(),
                        "Option %s was not selected on this line".formatted(nested.parentOptionId()));
            }
            childrenOfParent
                    .computeIfAbsent(nested.parentOptionId(), key -> new ArrayList<>())
                    .add(nested.optionId());
        }

        List<UUID> toPrice = new ArrayList<>();
        // Parents in a fixed order -- the first-level list's own, first occurrence --
        // so the nested options are priced and recorded in the same order every run.
        Set<UUID> seen = new HashSet<>();
        for (UUID parentId : line.modifierOptionIds()) {
            if (!seen.add(parentId)) {
                if (childrenOfParent.containsKey(parentId) || offersGroups(parentId, inputs)) {
                    throw new CompositeSelectionException(
                            "MODIFIER_NESTED_PARENT_REPEATED",
                            parentId,
                            "Option %s offers choices of its own and cannot be selected twice on one line"
                                    .formatted(parentId));
                }
                continue;
            }
            OptionFact parent = inputs.optionFacts().get(parentId);
            List<NestedGroupFact> groups = parent == null || parent.linkedVariantId() == null
                    ? List.of()
                    : inputs.nestedGroupsByVariant().getOrDefault(parent.linkedVariantId(), List.of());
            List<UUID> chosen = childrenOfParent.getOrDefault(parentId, List.of());

            if (groups.isEmpty()) {
                if (!chosen.isEmpty()) {
                    throw new CompositeSelectionException(
                            "MODIFIER_NESTED_OPTION_NOT_OFFERED",
                            chosen.get(0),
                            "Option %s offers no choices of its own".formatted(parentId));
                }
                continue;
            }
            toPrice.addAll(checkGroups(parentId, groups, chosen, inputs));
        }
        return List.copyOf(toPrice);
    }

    private static boolean offersGroups(UUID optionId, CompositeInputs inputs) {
        OptionFact fact = inputs.optionFacts().get(optionId);
        return fact != null
                && fact.linkedVariantId() != null
                && !inputs.nestedGroupsByVariant()
                        .getOrDefault(fact.linkedVariantId(), List.of())
                        .isEmpty();
    }

    private static List<UUID> checkGroups(
            UUID parentId, List<NestedGroupFact> groups, List<UUID> chosen, CompositeInputs inputs) {
        Map<UUID, NestedGroupFact> byOption = new LinkedHashMap<>();
        for (NestedGroupFact group : groups) {
            group.optionIds().forEach(optionId -> byOption.put(optionId, group));
        }

        Map<UUID, Integer> perOption = new LinkedHashMap<>();
        Map<UUID, Integer> perGroup = new LinkedHashMap<>();
        for (UUID optionId : chosen) {
            NestedGroupFact group = byOption.get(optionId);
            if (group == null) {
                throw new CompositeSelectionException(
                        "MODIFIER_NESTED_OPTION_NOT_OFFERED",
                        optionId,
                        "Option %s is not offered under option %s".formatted(optionId, parentId));
            }
            perOption.merge(optionId, 1, Integer::sum);
            perGroup.merge(group.groupId(), 1, Integer::sum);
        }

        for (NestedGroupFact group : groups) {
            int count = perGroup.getOrDefault(group.groupId(), 0);
            int minimum = group.required() ? Math.max(group.minimumSelections(), 1) : group.minimumSelections();
            if (count < minimum) {
                throw new CompositeSelectionException(
                        "MODIFIER_GROUP_MINIMUM_NOT_MET",
                        group.groupId(),
                        "Group %s under option %s needs at least %d selections and has %d"
                                .formatted(group.groupId(), parentId, minimum, count));
            }
            if (count > group.maximumSelections()) {
                throw new CompositeSelectionException(
                        "MODIFIER_GROUP_MAXIMUM_EXCEEDED",
                        group.groupId(),
                        "Group %s under option %s allows at most %d selections and has %d"
                                .formatted(group.groupId(), parentId, group.maximumSelections(), count));
            }
        }
        for (Map.Entry<UUID, Integer> entry : perOption.entrySet()) {
            NestedGroupFact group = byOption.get(entry.getKey());
            OptionFact fact = inputs.optionFacts().get(entry.getKey());
            int cap = fact == null ? 1 : fact.maximumQuantity();
            if (entry.getValue() > 1 && group != null && !group.allowSameOptionMultipleTimes()) {
                throw new CompositeSelectionException(
                        "MODIFIER_OPTION_NOT_REPEATABLE",
                        entry.getKey(),
                        "Option %s may be selected once in its group".formatted(entry.getKey()));
            }
            if (entry.getValue() > cap) {
                throw new CompositeSelectionException(
                        "MODIFIER_OPTION_QUANTITY_EXCEEDED",
                        entry.getKey(),
                        "Option %s allows at most %d".formatted(entry.getKey(), cap));
            }
        }
        return List.copyOf(chosen);
    }
}
