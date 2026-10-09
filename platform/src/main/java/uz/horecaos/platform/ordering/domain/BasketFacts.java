package uz.horecaos.platform.ordering.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * What the basket holds, as an auto-add rule may look at it (ADR 0158).
 *
 * <p>One {@link Line} per sold line: a combo is expanded into the component lines it puts on the
 * order, and <strong>an injected line is never in here</strong>. That is how chaining is made
 * impossible by construction: a rule cannot fire another rule because what one injects is not a
 * trigger unit and not a portion. The caller builds the facts from the customer's own lines.
 */
public record BasketFacts(List<Line> lines) {

    public BasketFacts {
        lines = List.copyOf(lines);
    }

    /**
     * One sold line.
     *
     * @param productId   the product the variant belongs to, or null when the published menu does
     *                    not say
     * @param categoryIds the categories that product sits in
     * @param units       the quantity sold (a combo component's is the line quantity times the pick)
     * @param portions    {@code units * portionsPerUnit}; zero for a variant that states no portions
     */
    public record Line(
            UUID variantId, @Nullable UUID productId, Set<UUID> categoryIds, BigDecimal units, BigDecimal portions) {

        public Line {
            categoryIds = Set.copyOf(categoryIds);
        }

        /** A line whose portions are its units times what one unit is worth; blank counts as zero. */
        public static Line of(
                UUID variantId,
                @Nullable UUID productId,
                Set<UUID> categoryIds,
                BigDecimal units,
                @Nullable BigDecimal portionsPerUnit) {
            BigDecimal portions = portionsPerUnit == null ? BigDecimal.ZERO : units.multiply(portionsPerUnit);
            return new Line(variantId, productId, categoryIds, units, portions);
        }
    }

    /** Units of the lines any of these trigger nodes match. */
    BigDecimal unitsMatching(List<PublishedAutoAddRule.Trigger> triggers) {
        BigDecimal total = BigDecimal.ZERO;
        for (Line line : lines) {
            if (triggers.stream().anyMatch(trigger -> trigger.matches(line))) {
                total = total.add(line.units());
            }
        }
        return total;
    }

    /** The portions across the whole basket. */
    BigDecimal totalPortions() {
        BigDecimal total = BigDecimal.ZERO;
        for (Line line : lines) {
            total = total.add(line.portions());
        }
        return total;
    }
}
