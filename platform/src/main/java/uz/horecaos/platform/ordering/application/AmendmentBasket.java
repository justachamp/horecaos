package uz.horecaos.platform.ordering.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.ordering.application.OrderAmendmentService.AmendmentRefusedException;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderLineRow;
import uz.horecaos.platform.ordering.infrastructure.persistence.JdbcOrderStore.OrderModifierRow;
import uz.horecaos.platform.pricing.api.CartPricingPort.PricingCommand;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.web.api.Quantities;

/**
 * The live basket of an order, as an amendment prices it again (ADR 0039, ADR 0136).
 *
 * <p>An amendment prices the whole order fresh through the same {@code CartPricingPort} a
 * cart does, so every live line has to be turned back into what a customer would have put in
 * a cart. For an ordinary line that is the line. For a combo it is not: a combo was bought
 * as one cart line and stored as several order lines, one per component, and pricing those
 * components as if each were an ordinary line would charge each at its own variant price
 * instead of its combo price. This class is the place that folds the component lines back
 * into the one combo they were, and unfolds the quote that comes back.
 *
 * <p>A weight captured at the pass (ADR 0137) prices the same basket the same way, with one line's
 * grams added ({@link #pricingItemsWeighing}), and reads the quote back the same way
 * ({@link Unit#replacedBy}).
 *
 * <p>Pure: values in, values out. Nothing here reads a database, so every rule below is
 * tested on a literal.
 */
final class AmendmentBasket {

    private AmendmentBasket() {}

    /**
     * One thing the live basket is priced as: an ordinary line, or all the component lines of
     * one combo purchase.
     */
    record Unit(List<OrderLineRow> lines) {

        Unit {
            lines = List.copyOf(lines);
        }

        OrderLineRow first() {
            return lines.get(0);
        }

        @Nullable
        UUID selectionId() {
            return first().comboSelectionId();
        }

        boolean combo() {
            return selectionId() != null;
        }

        /**
         * The key pricing is asked about it under. A quote line's key is this, and for a combo's
         * components this followed by {@code ~} and a position.
         */
        String key() {
            UUID selection = selectionId();
            return selection != null ? selection.toString() : first().lineId().toString();
        }

        boolean includes(UUID orderLineId) {
            return lines.stream().anyMatch(line -> line.lineId().equals(orderLineId));
        }

        /** The live line a priced quote line replaces: the same component, or the line itself. */
        OrderLineRow replacedBy(QuoteSnapshot.Line quoted) {
            if (!combo()) {
                return first();
            }
            UUID component = quoted.comboComponentId();
            return lines.stream()
                    .filter(line -> component != null && component.equals(line.comboComponentId()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "The quote priced a component this combo purchase never had: " + quoted.lineKey()));
        }
    }

    /** The live lines grouped into what they were bought as, in the order they were first bought. */
    static List<Unit> units(List<OrderLineRow> liveLines) {
        Map<String, List<OrderLineRow>> grouped = new LinkedHashMap<>();
        for (OrderLineRow line : liveLines) {
            UUID selection = line.comboSelectionId();
            String key =
                    selection != null ? selection.toString() : line.lineId().toString();
            grouped.computeIfAbsent(key, ignored -> new ArrayList<>()).add(line);
        }
        return grouped.values().stream().map(Unit::new).toList();
    }

    /**
     * The units a set of targeted order lines belong to, in basket order. Targeting any one
     * component line of a combo touches the whole purchase.
     */
    static List<Unit> touched(List<Unit> units, Set<UUID> targetedLineIds) {
        return units.stream()
                .filter(unit -> unit.lines().stream().anyMatch(line -> targetedLineIds.contains(line.lineId())))
                .toList();
    }

    /**
     * The pricing request for every live unit, with the quantities an amendment asks to change.
     *
     * @param changedQuantities new quantities by order line id. For a combo's component line the
     *        quantity is that line's new units, which has to be a whole number of combos
     * @param modifiersByLine the stored selections by order line, including the ones the server
     *        applied and the second-level ones
     */
    static List<PricingCommand.Item> pricingItems(
            List<Unit> units, Map<UUID, Integer> changedQuantities, Map<UUID, List<OrderModifierRow>> modifiersByLine) {
        return pricingItems(units, changedQuantities, modifiersByLine, null, null);
    }

    /**
     * The pricing request for every live unit with one line's weight just captured at the pass
     * (ADR 0137), and no quantity changed.
     *
     * <p>The same basket an amendment prices, so a weight capture cannot disagree with it about
     * what a combo, a hidden option or a second-level choice is: the weighed line carries the
     * grams, every other line keeps the weight it already had (or none), and a combo goes back as
     * its container and picks.
     *
     * @param weighedLineId the catchweight line the weight is for. A combo's component is never
     *        catchweight, so it is always an ordinary unit
     */
    static List<PricingCommand.Item> pricingItemsWeighing(
            List<Unit> units, Map<UUID, List<OrderModifierRow>> modifiersByLine, UUID weighedLineId, int weighedGrams) {
        return pricingItems(units, Map.of(), modifiersByLine, weighedLineId, weighedGrams);
    }

    private static List<PricingCommand.Item> pricingItems(
            List<Unit> units,
            Map<UUID, Integer> changedQuantities,
            Map<UUID, List<OrderModifierRow>> modifiersByLine,
            @Nullable UUID weighedLineId,
            @Nullable Integer weighedGrams) {
        List<PricingCommand.Item> items = new ArrayList<>();
        for (Unit unit : units) {
            items.add(
                    unit.combo()
                            ? comboItem(unit, changedQuantities)
                            : plainItem(unit, changedQuantities, modifiersByLine, weighedLineId, weighedGrams));
        }
        return items;
    }

    private static PricingCommand.Item plainItem(
            Unit unit,
            Map<UUID, Integer> changedQuantities,
            Map<UUID, List<OrderModifierRow>> modifiersByLine,
            @Nullable UUID weighedLineId,
            @Nullable Integer weighedGrams) {
        OrderLineRow line = unit.first();
        Integer changedTo = changedQuantities.get(line.lineId());
        boolean changed = changedTo != null;
        BigDecimal quantity = changedTo != null ? BigDecimal.valueOf(changedTo) : line.quantity();
        if (changed && quantity.compareTo(line.quantity()) <= 0) {
            throw decreaseRefused(line.lineId());
        }
        List<OrderModifierRow> rows = modifiersByLine.getOrDefault(line.lineId(), List.of());

        // What the customer chose, and nothing the server applied: the hidden options are
        // applied again by pricing for this order's fulfilment mode, and passing them back as
        // choices would charge them twice.
        List<UUID> chosen = rows.stream()
                .filter(row -> !row.autoSelected() && row.parentModifierId() == null)
                .map(OrderModifierRow::sourceOptionId)
                .toList();
        Map<UUID, UUID> optionByModifier = new LinkedHashMap<>();
        rows.forEach(row -> optionByModifier.put(row.modifierId(), row.sourceOptionId()));
        List<PricingCommand.NestedModifier> nested = rows.stream()
                .filter(row -> row.parentModifierId() != null)
                .map(row -> new PricingCommand.NestedModifier(
                        requireUuid(optionByModifier.get(row.parentModifierId())), row.sourceOptionId()))
                .toList();

        return new PricingCommand.Item(
                line.lineId().toString(),
                line.sourceVariantId(),
                quantity,
                chosen,
                List.of(),
                nested,
                // ADR 0137: a line that was already weighed keeps its weight through an
                // amendment that did not touch it; one whose quantity just changed no longer
                // has a weight that means anything, and goes back to provisional so the
                // handover asks for it again. The line being weighed right now carries the
                // weight just captured.
                line.lineId().equals(weighedLineId) ? weighedGrams : changed ? null : line.actualWeightGrams());
    }

    private static PricingCommand.Item comboItem(Unit unit, Map<UUID, Integer> changedQuantities) {
        OrderLineRow first = unit.first();
        int combos = requireInt(first.comboQuantity());

        Integer requested = null;
        for (OrderLineRow line : unit.lines()) {
            Integer target = changedQuantities.get(line.lineId());
            if (target == null) {
                continue;
            }
            // The units one combo puts on the order for this component: the pairing's default
            // quantity times how many times it was picked. Exact, because the line's quantity
            // is the combo count times that.
            int lineUnits = wholeUnits(line.quantity());
            int perCombo = lineUnits / combos;
            if (perCombo <= 0 || target % perCombo != 0) {
                throw new AmendmentRefusedException(
                        "COMBO_QUANTITY_NOT_WHOLE",
                        ("Line %s is %d unit(s) of a combo that puts %d on the order for each one bought; "
                                        + "a combo changes by whole combos")
                                .formatted(line.lineId(), lineUnits, perCombo));
            }
            int wanted = target / perCombo;
            if (requested != null && requested != wanted) {
                throw new AmendmentRefusedException(
                        "COMBO_QUANTITY_CONFLICT",
                        "The lines of one combo purchase were asked to change to different numbers of combos");
            }
            requested = wanted;
        }
        if (requested != null) {
            if (requested <= combos) {
                throw decreaseRefused(first.lineId());
            }
            combos = requested;
        }

        List<PricingCommand.ComboPick> picks = unit.lines().stream()
                .map(line -> new PricingCommand.ComboPick(
                        requireUuid(line.comboComponentId()), requireInt(line.comboPickQuantity())))
                .toList();
        return new PricingCommand.Item(
                unit.key(),
                requireUuid(first.comboContainerVariantId()),
                BigDecimal.valueOf(combos),
                List.of(),
                picks,
                List.of());
    }

    /**
     * What an amendment has to reserve more of, by variant: the units the quote now has beyond
     * what the live lines hold, for the units it changes, and every unit of what it adds.
     *
     * <p>Read from the quote's lines rather than from the request, because a combo added or
     * grown is several variants that the request does not name: only the quote knows its
     * components. Never the whole repriced basket, which would count stock a still-live line
     * already holds.
     */
    static Map<UUID, Integer> increases(QuoteSnapshot quote, List<Unit> changedUnits, Set<String> addedKeys) {
        Map<String, Unit> changedByKey =
                changedUnits.stream().collect(Collectors.toMap(Unit::key, unit -> unit, (a, b) -> a));
        Map<UUID, Integer> increaseByVariant = new LinkedHashMap<>();
        for (QuoteSnapshot.Line quoted : quote.lines()) {
            String key = quoted.cartLineKey();
            if (addedKeys.contains(key)) {
                // Whole units, rounded up: stock is held whole (ADR 0137 leaves a fractional
                // reservation to the record that next touches the inventory ledger).
                increaseByVariant.merge(
                        quoted.variantId(), Quantities.wholeUnitsCeiling(quoted.quantity()), Integer::sum);
                continue;
            }
            Unit unit = changedByKey.get(key);
            if (unit == null) {
                continue;
            }
            OrderLineRow live = unit.replacedBy(quoted);
            int delta = Quantities.wholeUnitsCeiling(quoted.quantity().subtract(live.quantity()));
            if (delta > 0) {
                increaseByVariant.merge(live.sourceVariantId(), delta, Integer::sum);
            }
        }
        return increaseByVariant;
    }

    private static AmendmentRefusedException decreaseRefused(UUID lineId) {
        return new AmendmentRefusedException(
                "QUANTITY_DECREASE_NOT_SUPPORTED",
                ("Line %s cannot be reduced: releasing already-committed stock has no ADR "
                                + "0017 primitive in this build yet (a return-to-stock or write-off "
                                + "movement, not one of hold/commit/release). Withdraw this amendment "
                                + "and place a new order for the corrected quantity.")
                        .formatted(lineId));
    }

    /** A combo component's units are always a whole number: the combo count times the picks. */
    private static int wholeUnits(BigDecimal quantity) {
        return quantity.intValueExact();
    }

    private static int requireInt(@Nullable Integer value) {
        if (value == null) {
            throw new IllegalStateException(
                    "A combo component line is missing a combo fact; ck_order_line_combo_provenance forbids it");
        }
        return value;
    }

    private static UUID requireUuid(@Nullable UUID value) {
        if (value == null) {
            throw new IllegalStateException(
                    "A combo component line is missing a combo fact; ck_order_line_combo_provenance forbids it");
        }
        return value;
    }
}
