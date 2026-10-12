package uz.horecaos.platform.ordering.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Which lines an auto-add rule set puts in a basket (ADR 0158): one pure function over a
 * {@link BasketFacts} and the published rules.
 *
 * <p>No Spring, no SQL and no clock, so the console's simulator and the cart run exactly this code
 * and what an author previews is what the cart does. The checks the cart runs on a customer's own
 * line (on the menu at the location, on sale now, available, priced) are the cart's, not a second
 * copy: the caller runs them and hands the answer in through {@link Eligibility}, and a failure is a
 * {@link Skipped}, never a refusal.
 *
 * <p>What a rule may condition on is closed: the order's fulfilment mode, the channel's system
 * type, optionally the location, and the basket's contents. Never the customer, the time, the
 * payment method, a coupon or a money amount; those are ADR 0140's.
 */
public final class AutoAddEvaluator {

    private AutoAddEvaluator() {}

    /** The order's context: the only facts about the order, apart from its contents, a rule reads. */
    public record Context(
            String fulfillmentMode,
            String channelSystemType,
            @Nullable UUID locationId) {}

    /** The caller's answer to "may this rule put this variant in this cart", given as data. */
    @FunctionalInterface
    public interface Eligibility {

        /** Every rule is eligible: the answer before any check has run. */
        Eligibility ALL = (rule, variantId) -> Optional.empty();

        /** The reason the rule is skipped for this variant, or empty when it may fire. */
        Optional<String> skipReason(PublishedAutoAddRule rule, UUID variantId);
    }

    /** What one rule came to. */
    public sealed interface Outcome permits Injection, Offer, Skipped {

        PublishedAutoAddRule rule();
    }

    /** The rule fires in INJECT mode: write this line. */
    public record Injection(PublishedAutoAddRule rule, UUID variantId, BigDecimal quantity) implements Outcome {}

    /** The rule fires in OFFER mode: suggest this line; nothing is written. */
    public record Offer(PublishedAutoAddRule rule, UUID variantId, BigDecimal quantity) implements Outcome {}

    /** The rule would have fired and could not: a declined rule, or a variant the cart cannot sell. */
    public record Skipped(
            PublishedAutoAddRule rule, @Nullable UUID variantId, String reason) implements Outcome {}

    /** The reason code of a rule the customer removed from this cart. */
    public static final String DECLINED = "DECLINED";

    /**
     * Evaluates every rule whose context matches.
     *
     * <p>A rule that does not fire (a zero raw quantity, no band containing the total, a context
     * that does not match, or a basket the customer has put nothing in) produces no outcome at all. The result is ordered by the rule's code so
     * the same basket always yields the same list.
     *
     * @param declined rules the customer removed from this cart; each yields {@link Skipped}
     *                 with {@link #DECLINED} instead of firing
     */
    public static List<Outcome> evaluate(
            List<PublishedAutoAddRule> rules,
            BasketFacts basket,
            Context context,
            java.util.Set<UUID> declined,
            Eligibility eligibility) {
        if (basket.lines().isEmpty()) {
            // Packaging goes with something the customer chose, never alone: a basket with nothing in it
            // fires no rule of any kind, even a PLAIN rule whose context matches. Without this the last
            // dish could never be removed (the rule would write its line straight back) and a cart
            // holding only a bag could be priced and paid for.
            return List.of();
        }
        List<Outcome> outcomes = new ArrayList<>();
        List<PublishedAutoAddRule> ordered = rules.stream()
                .sorted(Comparator.comparing(PublishedAutoAddRule::code).thenComparing(PublishedAutoAddRule::ruleId))
                .toList();
        for (PublishedAutoAddRule rule : ordered) {
            if (!rule.appliesTo(context)) {
                continue;
            }
            Fired fired = fire(rule, basket);
            if (fired == null) {
                continue;
            }
            if (declined.contains(rule.ruleId())) {
                outcomes.add(new Skipped(rule, fired.variantId(), DECLINED));
                continue;
            }
            Optional<String> reason = eligibility.skipReason(rule, fired.variantId());
            if (reason.isPresent()) {
                outcomes.add(new Skipped(rule, fired.variantId(), reason.get()));
                continue;
            }
            outcomes.add(
                    rule.mode() == PublishedAutoAddRule.Mode.OFFER
                            ? new Offer(rule, fired.variantId(), fired.quantity())
                            : new Injection(rule, fired.variantId(), fired.quantity()));
        }
        return List.copyOf(outcomes);
    }

    private record Fired(UUID variantId, BigDecimal quantity) {}

    /** The variant and clamped quantity a rule adds for this basket, or null when it does not fire. */
    private static @Nullable Fired fire(PublishedAutoAddRule rule, BasketFacts basket) {
        BigDecimal raw;
        UUID variantId;
        switch (rule.kind()) {
            case PLAIN -> {
                variantId = rule.addVariantId();
                raw = rule.quantity();
            }
            case PRODUCT_TRIGGERED -> {
                variantId = rule.addVariantId();
                BigDecimal threshold = rule.triggerThreshold();
                if (threshold == null || threshold.signum() <= 0 || rule.quantity() == null) {
                    return null;
                }
                BigDecimal units = basket.unitsMatching(rule.triggers());
                BigDecimal steps = rule.repeatPerMultiple()
                        ? units.divide(threshold, 0, RoundingMode.FLOOR)
                        : (units.compareTo(threshold) >= 0 ? BigDecimal.ONE : BigDecimal.ZERO);
                raw = rule.quantity().multiply(steps);
            }
            case PORTION_BAND -> {
                BigDecimal portions = basket.totalPortions();
                // A basket that counts no portions at all never fires a band, even one that
                // starts at zero: a brand where no variant states a portion value publishes with
                // a warning and the rule stays silent rather than adding cutlery to everything.
                if (portions.signum() <= 0) {
                    return null;
                }
                PublishedAutoAddRule.Band band = rule.bands().stream()
                        .filter(candidate -> candidate.contains(portions))
                        .findFirst()
                        .orElse(null);
                if (band == null) {
                    return null;
                }
                variantId = band.addVariantId();
                raw = band.quantity();
            }
            default -> {
                return null;
            }
        }
        if (variantId == null || raw == null || raw.signum() <= 0) {
            return null;
        }
        return new Fired(variantId, clamp(raw, rule.minimumQuantity(), rule.maximumQuantity()));
    }

    /** {@code clamp(raw, minimum, maximum)}, kept to the three fraction digits a cart quantity has. */
    static BigDecimal clamp(BigDecimal raw, BigDecimal minimum, BigDecimal maximum) {
        BigDecimal bounded = raw.max(minimum).min(maximum);
        BigDecimal stripped = bounded.signum() == 0 ? BigDecimal.ZERO : bounded.stripTrailingZeros();
        return stripped.scale() < 0 ? stripped.setScale(0) : stripped;
    }
}
