package uz.horecaos.platform.pricing.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.Promotion.Action;
import uz.horecaos.platform.pricing.domain.Promotion.Condition;
import uz.horecaos.platform.pricing.domain.TaxCalculation;

/**
 * ADR 0018 stages 3 and 4: which promotions apply, and what they are worth.
 *
 * <p>A pure function, for the same reason {@link PricingEngine} is one. It reads
 * no clock beyond the instant it is handed, touches no database, and consults
 * nothing that could differ between two runs — which is what makes the context
 * hash meaningful. Everything it needs about the catalog and the customer
 * arrives as values in {@link Basket} and {@link PromotionContext}.
 *
 * <h2>Stacking</h2>
 *
 * Best-one-wins per group, which ADR 0018 held open as a finance input and which
 * is now decided. Within one {@code stackingGroup} exactly one promotion
 * applies: the one worth most to the customer. Different groups combine. An
 * {@code exclusive} promotion suppresses every other promotion in the cart, and
 * if two are exclusive, the better of those two wins alone.
 *
 * <p>Ties are broken by priority and then by promotion id. That last tiebreak
 * looks like superstition and is not: ADR 0018 names "apply promotions in
 * whatever order the database returns rows" as a thing to never do, because two
 * runs of the same cart would produce different totals. Sorting by benefit alone
 * is not a total order — two promotions worth the same amount would be separated
 * by whatever order they arrived in — so the comparator has to end somewhere
 * stable.
 *
 * <h2>Two passes, and why</h2>
 *
 * Item promotions are selected and applied first, and the order stage then runs
 * against the reduced subtotal. That is the ADR's pipeline order and it is
 * load-bearing twice over: a percentage order discount computed on the
 * pre-discount subtotal would give away more than the rule says, and a
 * {@code SUBTOTAL_AT_LEAST} condition tested against it would let a basket
 * qualify for a threshold it no longer meets.
 */
@Component
public class PromotionEvaluator {

    /**
     * Runs stages 3 and 4 (item promotions, then order and delivery promotions)
     * over the basket and returns what applies.
     *
     * @param now compared against each promotion's window. Handed in rather than
     *        read, so this method answers identically on a second run.
     */
    public Outcome evaluate(List<Promotion> promotions, Basket basket, PromotionContext context, Instant now) {

        List<Promotion> live = promotions.stream()
                .filter(promotion -> promotion.isInForceAt(now))
                // A coupon promotion applies only when its code was presented.
                // Without this an automatic read of the promotion table would
                // hand every customer every coupon in the brand.
                .filter(promotion -> !promotion.requiresCoupon()
                        || context.presentedCouponPromotionIds().contains(promotion.promotionId()))
                .toList();

        // Stage 3, against the basket as priced.
        List<Candidate> itemCandidates = candidates(live, Promotion.Scope.ITEM, basket, context);
        List<Candidate> chosenItems = select(itemCandidates);
        Map<String, Long> lineDiscounts = accumulateLineDiscounts(chosenItems);

        long discountedSubtotal = basket.goodsSubtotalMinor()
                - lineDiscounts.values().stream().mapToLong(Long::longValue).sum();

        // Stage 4, against what stage 3 left. A basket that fell under a
        // threshold because of an item discount no longer meets it.
        Basket reduced = basket.withGoodsSubtotal(discountedSubtotal);
        List<Candidate> orderCandidates = new ArrayList<>();
        orderCandidates.addAll(candidates(live, Promotion.Scope.ORDER, reduced, context));
        orderCandidates.addAll(candidates(live, Promotion.Scope.DELIVERY, reduced, context));

        // Selection spans both stages together, because a stacking group is a
        // statement about the cart and not about a pipeline stage: an operator
        // who puts an item offer and an order offer in one group means "one of
        // these two", and selecting each stage independently would grant both.
        List<Candidate> chosen = new ArrayList<>(chosenItems);
        chosen.addAll(orderCandidates);
        chosen = select(chosen);

        return build(chosen, basket);
    }

    /** Every promotion of one scope whose conditions hold, with what it is worth. */
    private List<Candidate> candidates(
            List<Promotion> promotions, Promotion.Scope scope, Basket basket, PromotionContext context) {

        List<Candidate> candidates = new ArrayList<>();
        for (Promotion promotion : promotions) {
            if (promotion.scope() != scope) {
                continue;
            }
            Set<String> matchedLines = matchingLines(promotion, basket);
            if (!conditionsHold(promotion, basket, context, matchedLines)) {
                continue;
            }
            Benefit benefit = benefitOf(promotion, basket, matchedLines);
            if (benefit.totalMinor() <= 0) {
                // Worth nothing to this customer. Dropped rather than recorded,
                // so a zero adjustment never appears on a quote as an offer that
                // "applied" and did nothing.
                continue;
            }
            candidates.add(new Candidate(promotion, benefit));
        }
        return candidates;
    }

    /**
     * Best-one-wins, per group, with exclusivity on top.
     *
     * <p>Returns at most one promotion per stacking group, and exactly one
     * promotion in total when any candidate is exclusive.
     */
    private List<Candidate> select(List<Candidate> candidates) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        List<Candidate> exclusive =
                candidates.stream().filter(c -> c.promotion().exclusive()).toList();
        if (!exclusive.isEmpty()) {
            return List.of(exclusive.stream().max(BY_BENEFIT).orElseThrow());
        }
        // LinkedHashMap so the returned order follows first appearance rather than
        // hash order: the adjustment sequence numbers written onto the quote are
        // read back by people, and they should not shuffle between runs.
        Map<String, Candidate> best = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            best.merge(
                    candidate.promotion().stackingGroup(),
                    candidate,
                    (left, right) -> BY_BENEFIT.compare(left, right) >= 0 ? left : right);
        }
        return List.copyOf(best.values());
    }

    /**
     * More benefit wins; then higher priority; then the lower id.
     *
     * <p>The last clause is what makes this a total order. See the class comment.
     */
    private static final Comparator<Candidate> BY_BENEFIT = Comparator.comparingLong(
                    (Candidate candidate) -> candidate.benefit().totalMinor())
            .thenComparingInt(candidate -> candidate.promotion().priority())
            .thenComparing(candidate -> candidate.promotion().promotionId(), Comparator.reverseOrder());

    /** Which of the basket's lines this promotion's item conditions name. */
    private Set<String> matchingLines(Promotion promotion, Basket basket) {
        Set<String> matched = basket.lines().stream()
                .map(BasketLine::lineId)
                .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));

        for (Condition condition : promotion.conditions()) {
            switch (condition.type()) {
                case PRODUCT -> {
                    Set<UUID> ids = condition.operands().requireIds("productIds");
                    matched.retainAll(idsOf(basket, line -> ids.contains(line.productId())));
                }
                case VARIANT -> {
                    Set<UUID> ids = condition.operands().requireIds("variantIds");
                    matched.retainAll(idsOf(basket, line -> ids.contains(line.variantId())));
                }
                case CATEGORY -> {
                    Set<UUID> ids = condition.operands().requireIds("categoryIds");
                    matched.retainAll(
                            idsOf(basket, line -> line.categoryIds().stream().anyMatch(ids::contains)));
                }
                default -> {
                    /* Not a line predicate. Handled in conditionsHold. */
                }
            }
        }
        return matched;
    }

    private Set<String> idsOf(Basket basket, java.util.function.Predicate<BasketLine> predicate) {
        return basket.lines().stream()
                .filter(predicate)
                .map(BasketLine::lineId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    /** Every condition must hold. There is no OR, by design — see {@link Promotion}. */
    private boolean conditionsHold(
            Promotion promotion, Basket basket, PromotionContext context, Set<String> matchedLines) {

        for (Condition condition : promotion.conditions()) {
            boolean holds =
                    switch (condition.type()) {
                        // The three line predicates hold when anything survived the
                        // intersection above. A promotion naming a product the cart does
                        // not contain matches no line and therefore does not apply.
                        case PRODUCT, VARIANT, CATEGORY -> !matchedLines.isEmpty();
                        case QUANTITY_AT_LEAST ->
                            quantityOf(basket, matchedLines)
                                    >= condition.operands().requireInt("quantity");
                        case SUBTOTAL_AT_LEAST ->
                            basket.goodsSubtotalMinor() >= condition.operands().requireLong("amountMinor");
                        case CHANNEL ->
                            condition.operands().requireStrings("channels").contains(context.channel());
                        case LOCATION ->
                            condition.operands().requireIds("locationIds").contains(context.locationId());
                        case FULFILLMENT_MODE ->
                            condition
                                    .operands()
                                    .requireStrings("fulfillmentModes")
                                    .contains(context.fulfillmentMode());
                        case DAY_OF_WEEK ->
                            condition.operands().requireInts("daysOfWeek").contains(context.localDayOfWeek());
                        case TIME_OF_DAY -> withinWindow(condition, context.localMinuteOfDay());
                        case FIRST_ORDER -> context.firstOrder();
                        case CUSTOMER_SEGMENT ->
                            condition.operands().requireStrings("segments").stream()
                                    .anyMatch(context.customerSegments()::contains);
                    };
            if (!holds) {
                return false;
            }
        }
        return true;
    }

    /**
     * A local-time window, which may wrap past midnight.
     *
     * <p>A window of 22:00 to 02:00 is a real thing an operator writes, and
     * testing it as {@code from <= now && now < to} excludes every minute of it.
     */
    private boolean withinWindow(Condition condition, int minuteOfDay) {
        int from = condition.operands().requireInt("fromMinuteOfDay");
        int to = condition.operands().requireInt("toMinuteOfDay");
        return from <= to ? minuteOfDay >= from && minuteOfDay < to : minuteOfDay >= from || minuteOfDay < to;
    }

    private int quantityOf(Basket basket, Set<String> lineIds) {
        return basket.lines().stream()
                .filter(line -> lineIds.contains(line.lineId()))
                .mapToInt(BasketLine::quantity)
                .sum();
    }

    /**
     * What one promotion is worth, capped by its own maximum.
     *
     * <p>The cap is applied here rather than at the end so that selection compares
     * what the customer would actually receive. Comparing uncapped benefits would
     * pick a promotion worth 50 000 capped at 5 000 over one worth a flat 10 000.
     */
    private Benefit benefitOf(Promotion promotion, Basket basket, Set<String> matchedLines) {
        Map<String, Long> perLine = new HashMap<>();
        long orderMinor = 0;
        long deliveryMinor = 0;

        for (Action action : promotion.actions()) {
            switch (action.type()) {
                case ITEM_PERCENTAGE_DISCOUNT -> {
                    long basisPoints = action.operands().requireLong("basisPoints");
                    for (BasketLine line : basket.linesIn(matchedLines)) {
                        perLine.merge(line.lineId(), percentageOf(line.lineGrossMinor(), basisPoints), Long::sum);
                    }
                }
                case ITEM_FIXED_DISCOUNT -> {
                    long perUnit = action.operands().requireLong("amountMinor");
                    for (BasketLine line : basket.linesIn(matchedLines)) {
                        // Capped at the line: a fixed discount larger than the
                        // item must not make the line negative, which ADR 0018
                        // rejects outright.
                        perLine.merge(
                                line.lineId(), Math.min(perUnit * line.quantity(), line.lineGrossMinor()), Long::sum);
                    }
                }
                case ITEM_FIXED_PRICE -> {
                    long unitPrice = action.operands().requireLong("amountMinor");
                    for (BasketLine line : basket.linesIn(matchedLines)) {
                        long target = unitPrice * line.quantity();
                        perLine.merge(line.lineId(), Math.max(0, line.lineGrossMinor() - target), Long::sum);
                    }
                }
                case ORDER_PERCENTAGE_DISCOUNT ->
                    orderMinor += percentageOf(
                            basket.goodsSubtotalMinor(), action.operands().requireLong("basisPoints"));
                case ORDER_FIXED_DISCOUNT ->
                    orderMinor += Math.min(action.operands().requireLong("amountMinor"), basket.goodsSubtotalMinor());
                case FREE_DELIVERY -> deliveryMinor += basket.deliveryFeeMinor();
                case REDUCED_DELIVERY -> {
                    long reduction = action.operands()
                            .optionalLong("amountMinor")
                            .orElseGet(() -> percentageOf(
                                    basket.deliveryFeeMinor(), action.operands().requireLong("basisPoints")));
                    deliveryMinor += Math.min(reduction, basket.deliveryFeeMinor());
                }
                case FREE_ITEM -> {
                    Set<UUID> variantIds = action.operands().requireIds("variantIds");
                    int bound = action.operands().requireInt("quantity");
                    for (BasketLine line : basket.lines()) {
                        if (!variantIds.contains(line.variantId())) {
                            continue;
                        }
                        // Bounded, and bounded by the line as well as by the rule:
                        // giving away more units than the customer is buying would
                        // take the line below zero.
                        int free = Math.min(bound, line.quantity());
                        perLine.merge(line.lineId(), line.unitAmountMinor() * free, Long::sum);
                    }
                }
            }
        }

        long total = perLine.values().stream().mapToLong(Long::longValue).sum() + orderMinor + deliveryMinor;
        if (promotion.maximumDiscountMinor() != null && total > promotion.maximumDiscountMinor()) {
            return capped(promotion, perLine, orderMinor, deliveryMinor);
        }
        return new Benefit(Map.copyOf(perLine), orderMinor, deliveryMinor, total);
    }

    /**
     * Scales a benefit down to the promotion's cap.
     *
     * <p>Largest-remainder, so the parts still sum to the capped total. Scaling
     * each part independently and rounding each one leaves a total that is off by
     * a few minor units from the cap, and a quote whose adjustments do not sum to
     * its own discount is one nobody can reconcile.
     */
    private Benefit capped(Promotion promotion, Map<String, Long> perLine, long orderMinor, long deliveryMinor) {

        // Every caller only reaches capped() after confirming maximumDiscountMinor
        // is present and exceeded; requireNonNull documents that invariant rather
        // than letting an unboxing NPE explain it badly if it were ever violated.
        long cap = Objects.requireNonNull(
                promotion.maximumDiscountMinor(), "capped() is only called once a maximum discount is known");
        List<String> keys = new ArrayList<>(perLine.keySet());
        long[] weights = new long[keys.size() + 2];
        for (int i = 0; i < keys.size(); i++) {
            // keys is exactly perLine.keySet(), so every key here is present;
            // requireNonNull documents that rather than letting an unboxing NPE
            // explain it badly if the invariant were ever broken.
            weights[i] = Objects.requireNonNull(perLine.get(keys.get(i)), "key came from perLine's own keySet");
        }
        weights[keys.size()] = orderMinor;
        weights[keys.size() + 1] = deliveryMinor;

        // The same largest-remainder apportionment the tax split uses, and reused
        // rather than rewritten so a capped discount and an apportioned tax round
        // the same way.
        long[] shares = TaxCalculation.apportion(cap, weights);

        Map<String, Long> scaled = new LinkedHashMap<>();
        for (int i = 0; i < keys.size(); i++) {
            if (shares[i] > 0) {
                scaled.put(keys.get(i), shares[i]);
            }
        }
        return new Benefit(Map.copyOf(scaled), shares[keys.size()], shares[keys.size() + 1], cap);
    }

    /**
     * Basis points of an amount, rounded half-up on a non-negative value.
     *
     * <p>Integer arithmetic throughout. A percentage computed in floating point is
     * how the same cart prices differently on two machines.
     */
    static long percentageOf(long amountMinor, long basisPoints) {
        return (amountMinor * basisPoints + 5_000) / 10_000;
    }

    private Map<String, Long> accumulateLineDiscounts(List<Candidate> chosen) {
        Map<String, Long> discounts = new LinkedHashMap<>();
        for (Candidate candidate : chosen) {
            candidate.benefit().perLineMinor().forEach((lineId, amount) -> discounts.merge(lineId, amount, Long::sum));
        }
        return discounts;
    }

    private Outcome build(List<Candidate> chosen, Basket basket) {
        Map<String, Long> lineDiscounts = accumulateLineDiscounts(chosen);
        long orderDiscount =
                chosen.stream().mapToLong(c -> c.benefit().orderMinor()).sum();
        long deliveryBenefit =
                chosen.stream().mapToLong(c -> c.benefit().deliveryMinor()).sum();

        // The order discount cannot exceed what is left after item discounts. Two
        // promotions in different stacking groups can each be legitimate and
        // together take a basket below zero, which ADR 0018 rejects.
        long afterItems = basket.goodsSubtotalMinor()
                - lineDiscounts.values().stream().mapToLong(Long::longValue).sum();
        orderDiscount = Math.min(orderDiscount, Math.max(0, afterItems));
        deliveryBenefit = Math.min(deliveryBenefit, basket.deliveryFeeMinor());

        List<AppliedPromotion> applied = chosen.stream()
                .map(candidate -> new AppliedPromotion(
                        candidate.promotion().promotionId(),
                        candidate.promotion().code(),
                        candidate.promotion().definitionVersion(),
                        candidate.promotion().scope(),
                        candidate.benefit().perLineMinor(),
                        candidate.benefit().orderMinor(),
                        candidate.benefit().deliveryMinor()))
                .toList();

        return new Outcome(Map.copyOf(lineDiscounts), orderDiscount, deliveryBenefit, applied);
    }

    // ------------------------------------------------------------------ values

    /** One promotion that applies, and what it is worth. */
    private record Candidate(Promotion promotion, Benefit benefit) {}

    private record Benefit(Map<String, Long> perLineMinor, long orderMinor, long deliveryMinor, long totalMinor) {}

    /**
     * The basket as priced by stages 1 and 2, plus what the catalog says the
     * lines are.
     *
     * <p>{@code productId} and {@code categoryIds} come from the catalog through
     * the pricing context port and arrive here as values, so this class never
     * learns what a catalog is.
     */
    public record Basket(String currency, List<BasketLine> lines, long goodsSubtotalMinor, long deliveryFeeMinor) {

        public Basket {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }

        Basket withGoodsSubtotal(long subtotalMinor) {
            return new Basket(currency, lines, subtotalMinor, deliveryFeeMinor);
        }

        List<BasketLine> linesIn(Set<String> lineIds) {
            return lines.stream()
                    .filter(line -> lineIds.contains(line.lineId()))
                    .toList();
        }
    }

    /**
     * One priced line of the basket, as stages 3 and 4 see it.
     *
     * @param productId null when the variant matched no catalog membership row.
     */
    public record BasketLine(
            String lineId,
            UUID variantId,
            @Nullable UUID productId,
            Set<UUID> categoryIds,
            int quantity,
            long unitAmountMinor,
            long lineGrossMinor) {

        public BasketLine {
            categoryIds = categoryIds == null ? Set.of() : Set.copyOf(categoryIds);
        }
    }

    /**
     * Everything outside the basket a condition may ask about.
     *
     * <p>The local day and minute are resolved by the caller from the location's
     * IANA timezone, not computed here: a "lunchtime" promotion means lunchtime
     * where the branch is, and this class may not read a clock or a zone.
     */
    public record PromotionContext(
            String channel,
            UUID locationId,
            String fulfillmentMode,
            boolean firstOrder,
            Set<String> customerSegments,
            Set<UUID> presentedCouponPromotionIds,
            int localDayOfWeek,
            int localMinuteOfDay) {

        public PromotionContext {
            customerSegments = customerSegments == null ? Set.of() : Set.copyOf(customerSegments);
            presentedCouponPromotionIds =
                    presentedCouponPromotionIds == null ? Set.of() : Set.copyOf(presentedCouponPromotionIds);
        }
    }

    /** What stages 3 and 4 decided, for the engine to apply and record. */
    public record Outcome(
            Map<String, Long> lineDiscountsMinor,
            long orderDiscountMinor,
            long deliveryBenefitMinor,
            List<AppliedPromotion> applied) {

        public Outcome {
            lineDiscountsMinor = lineDiscountsMinor == null ? Map.of() : Map.copyOf(lineDiscountsMinor);
            applied = applied == null ? List.of() : List.copyOf(applied);
        }

        public boolean isEmpty() {
            return applied.isEmpty();
        }

        public long totalDiscountMinor() {
            return lineDiscountsMinor.values().stream()
                            .mapToLong(Long::longValue)
                            .sum()
                    + orderDiscountMinor;
        }
    }

    /** One promotion that made it onto the quote, for the adjustment record. */
    public record AppliedPromotion(
            UUID promotionId,
            String code,
            int definitionVersion,
            Promotion.Scope scope,
            Map<String, Long> perLineMinor,
            long orderMinor,
            long deliveryMinor) {}
}
