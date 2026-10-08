package uz.horecaos.platform.printing.application.documents;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import uz.horecaos.platform.ordering.api.OrderPrintSource.Line;
import uz.horecaos.platform.ordering.api.OrderPrintSource.Modifier;
import uz.horecaos.platform.printing.domain.PrintDocument.Item;

/**
 * How an order line is written on paper: its name, its quantity, and what hangs beneath it (modifiers one
 * level nested, coded presets, a note). Shared by the kitchen ticket and the order slip so the two cannot
 * come to write one dish two ways.
 */
final class LineLabels {

    private LineLabels() {}

    /** A quantity as a person writes it: {@code 2}, {@code 0.5}, never {@code 2.000}. */
    static String quantity(BigDecimal quantity) {
        BigDecimal stripped = quantity.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    /** The dish and, when it has one, its variant: {@code Lagman (large)}. */
    static String name(Line line) {
        String variant = line.variantName();
        if (variant == null || variant.isBlank() || variant.equalsIgnoreCase(line.productName())) {
            return line.productName();
        }
        return line.productName() + " (" + variant + ")";
    }

    /**
     * What hangs beneath a line on a kitchen ticket: modifiers (a nested one under the option that offered
     * it), the coded presets the customer picked, then the free-text note. {@code withNote} is false on an
     * order slip, which never carries a note.
     */
    static List<Item> details(Line line, String locale, boolean withKitchenInstructions) {
        List<Item> details = new ArrayList<>();
        Map<UUID, List<Modifier>> nested = line.modifiers().stream()
                .filter(modifier -> modifier.parentId() != null)
                .collect(Collectors.groupingBy(
                        modifier -> java.util.Objects.requireNonNull(modifier.parentId()),
                        java.util.LinkedHashMap::new,
                        Collectors.toList()));
        for (Modifier modifier : line.modifiers()) {
            if (modifier.parentId() != null) {
                continue;
            }
            List<Item> children = nested.getOrDefault(modifier.id(), List.of()).stream()
                    .map(child -> Item.of(modifierText(child), null, null))
                    .toList();
            details.add(new Item(modifierText(modifier), null, null, false, children));
        }
        if (withKitchenInstructions) {
            line.presets().forEach(preset -> details.add(Item.of("* " + preset.label(locale), null, null)));
            String note = line.note();
            if (note != null && !note.isBlank()) {
                details.add(Item.of("! " + note.strip(), null, null));
            }
        }
        return details;
    }

    private static String modifierText(Modifier modifier) {
        String count = modifier.quantity() > 1 ? modifier.quantity() + "× " : "";
        return "+ " + count + modifier.optionName();
    }

    static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
