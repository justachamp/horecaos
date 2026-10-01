package uz.horecaos.platform.pricing.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.Promotion.Action;
import uz.horecaos.platform.pricing.domain.Promotion.Condition;
import uz.horecaos.platform.pricing.domain.TaxCalculation;

/**
 * ADR 0018 stages 2b, 3 and 4: which promotions apply, and what they are worth.
 *
 * <p>A pure function, for the same reason {@link PricingEngine} is one. It reads
 * no clock beyond the instant it is handed, touches no database, and consults
 * nothing that could differ between two runs — which is what makes the context
 * hash meaningful. Everything it needs about the catalog and the customer
 * arrives as values in {@link Basket} and {@link PromotionContext}.
 *
 * <h2>Composition (ADR 0140), the only one there is</h2>
 *
 * <ol>
 *   <li><b>Stage 2b, markups.</b> {@link #evaluateMarkups}: the highest-priority
 *       markup of each stacking group applies, groups add, and the engine uplifts
 *       the lines before any discount sees them.
 *   <li><b>Stage 3, item discounts.</b> In each item-scope stacking group the
 *       candidate worth most to the customer wins (then higher priority, then the
 *       lower id). Across groups the discounts add, and a per-line clamp scales
 *       them down by largest-remainder apportionment so no line goes below zero.
 *   <li><b>Stage 4, order and delivery discounts.</b> Computed on the subtotal the
 *       stage 3 result left. {@code ORDER} and {@code DELIVERY} scope may share a
 *       group; an {@code ITEM} promotion may not share one with either, which
 *       removes the cross-scope contest the previous evaluator papered over.
 *   <li><b>Exclusive promotions are scenarios.</b> Each is evaluated as if it were
 *       the only discount in the cart, and the customer receives whichever is
 *       larger: the best exclusive scenario or the combination built by steps 3
 *       and 4. A tie goes to the exclusive promotion. A promo code (always
 *       exclusive) therefore still never combines with an automatic offer, but it
 *       no longer erases a better one.
 * </ol>
 *
 * <p>Ties are broken by priority and then by promotion id. That last tiebreak
 * looks like superstition and is not: ADR 0018 names "apply promotions in
 * whatever order the database returns rows" as a thing to never do, because two
 * runs of the same cart would produce different totals. Sorting by benefit alone
 * is not a total order, so the comparator has to end somewhere stable. For the
 * same reason every list that enters is sorted by promotion id first, so the
 * outcome does not depend on the order the caller supplied the promotions in.
 */
@Component
public class PromotionEvaluator {

    /**
     * Runs stages 3 and 4 (item promotions, then order and delivery promotions)
     * over the basket and returns what applies.
     *
     * @param now compared against each promotion's window unless the context
     *        carries a service instant, which then wins. Handed in rather than
     *        read, so this method answers identically on a second run.
     */
    public Outcome evaluate(List<Promotion> promotions, Basket basket, PromotionContext context, Instant now) {
        Instant at = context.serviceInstant() != null ? context.serviceInstant() : now;
        Map<UUID, TraceEntry> trace = new LinkedHashMap<>();

        List<Promotion> live = new ArrayList<>();
        for (Promotion promotion : sortedById(promotions)) {
            if (promotion.kind() != Promotion.Kind.DISCOUNT) {
                continue;
            }
            TraceEntry refused = gate(promotion, basket, context, at);
            if (refused != null) {
                trace.put(promotion.promotionId(), refused);
            } else {
                live.add(promotion);
            }
        }

        List<Promotion> automatic =
                live.stream().filter(promotion -> !promotion.exclusive()).toList();
        List<Promotion> exclusive = live.stream().filter(Promotion::exclusive).toList();

        Combination combination = combine(automatic, basket, context, trace);

        Combination bestExclusive = null;
        List<Combination> scenarios = new ArrayList<>();
        for (Promotion candidate : exclusive) {
            Combination scenario = combine(List.of(candidate), basket, context, trace);
            if (scenario.chosen().isEmpty()) {
                continue;
            }
            scenarios.add(scenario);
            if (bestExclusive == null || BY_SCENARIO.compare(scenario, bestExclusive) > 0) {
                bestExclusive = scenario;
            }
        }

        Combination winner;
        if (bestExclusive != null && bestExclusive.valueMinor() >= combination.valueMinor()) {
            winner = bestExclusive;
            Candidate exclusiveWinner = bestExclusive.chosen().get(0);
            for (Candidate seen : combination.seen()) {
                trace.put(
                        seen.promotion().promotionId(),
                        new TraceEntry(
                                seen.promotion().promotionId(),
                                seen.promotion().code(),
                                Verdict.SUPPRESSED_BY_EXCLUSIVE,
                                null,
                                List.of(exclusiveWinner.promotion().promotionId()),
                                0L));
            }
            for (Combination loser : scenarios) {
                if (loser == bestExclusive) {
                    continue;
                }
                Candidate lost = loser.chosen().get(0);
                trace.put(
                        lost.promotion().promotionId(),
                        new TraceEntry(
                                lost.promotion().promotionId(),
                                lost.promotion().code(),
                                Verdict.LOST_TO,
                                null,
                                List.of(exclusiveWinner.promotion().promotionId()),
                                0L));
            }
        } else {
            winner = combination;
            List<UUID> applied = combination.chosen().stream()
                    .map(candidate -> candidate.promotion().promotionId())
                    .toList();
            for (Promotion candidate : exclusive) {
                TraceEntry existing = trace.get(candidate.promotionId());
                if (existing == null) {
                    trace.put(
                            candidate.promotionId(),
                            new TraceEntry(
                                    candidate.promotionId(), candidate.code(), Verdict.LOST_TO, null, applied, 0L));
                }
            }
            for (Map.Entry<UUID, UUID> loser : combination.groupLosers().entrySet()) {
                Promotion lost = byId(automatic, loser.getKey());
                trace.put(
                        loser.getKey(),
                        new TraceEntry(
                                loser.getKey(), lost.code(), Verdict.LOST_TO, null, List.of(loser.getValue()), 0L));
            }
        }

        return build(winner.chosen(), basket, new ArrayList<>(trace.values()));
    }

    /**
     * Stage 2b. Which markups apply to the basket as stages 1 and 2 priced it.
     *
     * <p>Within one stacking group the highest priority wins (then the lower id),
     * never the largest uplift: "best for the customer" would make a markup defeat
     * itself. Markups in different groups add, each computed on the line's own
     * pre-markup unit price (never compounded), and are never suppressed by
     * exclusivity or compared with a discount.
     */
    public MarkupOutcome evaluateMarkups(
            List<Promotion> promotions, Basket basket, PromotionContext context, Instant now) {
        Instant at = context.serviceInstant() != null ? context.serviceInstant() : now;
        Map<UUID, TraceEntry> trace = new LinkedHashMap<>();
        Map<String, Candidate> groupWinners = new LinkedHashMap<>();

        for (Promotion promotion : sortedById(promotions)) {
            if (promotion.kind() != Promotion.Kind.MARKUP || promotion.scope() != Promotion.Scope.ITEM) {
                continue;
            }
            TraceEntry refused = gate(promotion, basket, context, at);
            if (refused != null) {
                trace.put(promotion.promotionId(), refused);
                continue;
            }
            Set<String> matched = matchingLines(promotion, basket);
            int failed = firstFailingCondition(promotion, basket, context, matched);
            if (failed >= 0) {
                trace.put(promotion.promotionId(), conditionFailed(promotion, failed));
                continue;
            }
            Benefit benefit = markupOf(promotion, basket, matched);
            if (benefit.totalMinor() <= 0) {
                trace.put(
                        promotion.promotionId(),
                        new TraceEntry(
                                promotion.promotionId(), promotion.code(), Verdict.ZERO_BENEFIT, null, List.of(), 0L));
                continue;
            }
            Candidate candidate = new Candidate(promotion, benefit);
            Candidate incumbent = groupWinners.get(promotion.stackingGroup());
            if (incumbent == null || BY_PRIORITY.compare(candidate, incumbent) > 0) {
                if (incumbent != null) {
                    trace.put(
                            incumbent.promotion().promotionId(),
                            lostTo(incumbent.promotion(), promotion.promotionId()));
                }
                groupWinners.put(promotion.stackingGroup(), candidate);
            } else {
                trace.put(
                        promotion.promotionId(),
                        lostTo(promotion, incumbent.promotion().promotionId()));
            }
        }

        Map<String, Long> lineMarkups = new LinkedHashMap<>();
        List<AppliedMarkup> applied = new ArrayList<>();
        for (Candidate winner : groupWinners.values()) {
            winner.benefit().perLineMinor().forEach((lineId, amount) -> lineMarkups.merge(lineId, amount, Long::sum));
            applied.add(new AppliedMarkup(
                    winner.promotion().promotionId(),
                    winner.promotion().code(),
                    winner.promotion().definitionVersion(),
                    winner.benefit().perLineMinor(),
                    winner.promotion().loyaltyAccrual(),
                    winner.promotion().loyaltyRedemption()));
            trace.put(
                    winner.promotion().promotionId(),
                    new TraceEntry(
                            winner.promotion().promotionId(),
                            winner.promotion().code(),
                            Verdict.APPLIED,
                            null,
                            List.of(),
                            winner.benefit().totalMinor()));
        }
        return new MarkupOutcome(Map.copyOf(lineMarkups), List.copyOf(applied), new ArrayList<>(trace.values()));
    }

    // ----------------------------------------------------------------- gates

    /**
     * The checks that need no basket: the window, the currency, the coupon and
     * the usage limits. Null when the promotion may be evaluated.
     */
    private @Nullable TraceEntry gate(Promotion promotion, Basket basket, PromotionContext context, Instant at) {
        if (!promotion.isInForceAt(at)) {
            return refusal(promotion, Verdict.OUTSIDE_WINDOW);
        }
        if (!promotion.currency().equals(basket.currency())) {
            // A promotion authored in another currency cannot be applied to this
            // quote by reinterpreting its minor units.
            return refusal(promotion, Verdict.CURRENCY_MISMATCH);
        }
        // A coupon promotion applies only when its code was presented. Without
        // this an automatic read of the promotion table would hand every customer
        // every coupon in the brand.
        if (promotion.requiresCoupon() && !context.presentedCouponPromotionIds().contains(promotion.promotionId())) {
            return refusal(promotion, Verdict.COUPON_NOT_PRESENTED);
        }
        if (context.notClaimedAtPlacementPromotionIds().contains(promotion.promotionId())) {
            return refusal(promotion, Verdict.NOT_CLAIMED_AT_PLACEMENT);
        }
        if (context.limitReachedPromotionIds().contains(promotion.promotionId())) {
            return refusal(promotion, Verdict.LIMIT_REACHED);
        }
        return null;
    }

    private static TraceEntry refusal(Promotion promotion, Verdict verdict) {
        return new TraceEntry(promotion.promotionId(), promotion.code(), verdict, null, List.of(), 0L);
    }

    private static TraceEntry conditionFailed(Promotion promotion, int sequence) {
        return new TraceEntry(
                promotion.promotionId(), promotion.code(), Verdict.CONDITION_FAILED, sequence, List.of(), 0L);
    }

    private static TraceEntry lostTo(Promotion promotion, UUID winner) {
        return new TraceEntry(promotion.promotionId(), promotion.code(), Verdict.LOST_TO, null, List.of(winner), 0L);
    }

    // ------------------------------------------------------------ combination

    /**
     * Stages 3 and 4 over one pool of promotions, as if the pool were everything
     * on offer. Called once for the automatic set and once per exclusive
     * promotion, which is what makes an exclusive scenario comparable.
     */
    private Combination combine(
            List<Promotion> pool, Basket basket, PromotionContext context, Map<UUID, TraceEntry> trace) {

        Map<UUID, UUID> losers = new LinkedHashMap<>();
        List<Candidate> seen = new ArrayList<>();

        // Stage 3, against the basket as priced.
        List<Candidate> itemCandidates = candidates(pool, Promotion.Scope.ITEM, basket, context, trace);
        seen.addAll(itemCandidates);
        List<Candidate> chosenItems = clampToLines(selectPerGroup("I", itemCandidates, losers), basket);
        long itemDiscounts = chosenItems.stream()
                .mapToLong(candidate -> candidate.benefit().perLineTotal())
                .sum();

        // Stage 4, against what stage 3 left. A basket that fell under a
        // threshold because of an item discount no longer meets it.
        Basket reduced = basket.withGoodsSubtotal(basket.goodsSubtotalMinor() - itemDiscounts);
        List<Candidate> orderCandidates = new ArrayList<>();
        orderCandidates.addAll(candidates(pool, Promotion.Scope.ORDER, reduced, context, trace));
        orderCandidates.addAll(candidates(pool, Promotion.Scope.DELIVERY, reduced, context, trace));
        seen.addAll(orderCandidates);
        List<Candidate> chosenOrder = selectPerGroup("O", orderCandidates, losers);

        List<Candidate> chosen = new ArrayList<>(chosenItems);
        chosen.addAll(chosenOrder);

        long afterItems = basket.goodsSubtotalMinor() - itemDiscounts;
        long orderRaw = chosenOrder.stream()
                .mapToLong(candidate -> candidate.benefit().orderMinor())
                .sum();
        long deliveryRaw = chosenOrder.stream()
                .mapToLong(candidate -> candidate.benefit().deliveryMinor())
                .sum();
        long value = itemDiscounts
                + Math.min(orderRaw, Math.max(0, afterItems))
                + Math.min(deliveryRaw, basket.deliveryFeeMinor());
        return new Combination(List.copyOf(chosen), List.copyOf(seen), Map.copyOf(losers), value);
    }

    /** Every promotion of one scope whose conditions hold, with what it is worth. */
    private List<Candidate> candidates(
            List<Promotion> promotions,
            Promotion.Scope scope,
            Basket basket,
            PromotionContext context,
            Map<UUID, TraceEntry> trace) {

        List<Candidate> candidates = new ArrayList<>();
        for (Promotion promotion : promotions) {
            if (promotion.scope() != scope) {
                continue;
            }
            Set<String> matchedLines = matchingLines(promotion, basket);
            int failed = firstFailingCondition(promotion, basket, context, matchedLines);
            if (failed >= 0) {
                trace.put(promotion.promotionId(), conditionFailed(promotion, failed));
                continue;
            }
            Benefit benefit = benefitOf(promotion, basket, matchedLines);
            if (benefit.totalMinor() <= 0) {
                // Worth nothing to this customer. Dropped rather than recorded as
                // applied, so a zero adjustment never appears on a quote as an
                // offer that "applied" and did nothing.
                trace.put(
                        promotion.promotionId(),
                        new TraceEntry(
                                promotion.promotionId(), promotion.code(), Verdict.ZERO_BENEFIT, null, List.of(), 0L));
                continue;
            }
            candidates.add(new Candidate(promotion, benefit));
        }
        return candidates;
    }

    /**
     * Best-one-wins, per group: the candidate worth most to the customer, then
     * higher priority, then the lower id. The losers are recorded against their
     * winner for the decision trace.
     */
    private List<Candidate> selectPerGroup(String stage, List<Candidate> candidates, Map<UUID, UUID> losers) {
        // LinkedHashMap so the returned order follows first appearance rather than
        // hash order: the adjustment sequence numbers written onto the quote are
        // read back by people, and they should not shuffle between runs.
        Map<String, Candidate> best = new LinkedHashMap<>();
        for (Candidate candidate : candidates) {
            String key = stage + ":" + candidate.promotion().stackingGroup();
            Candidate incumbent = best.get(key);
            if (incumbent == null) {
                best.put(key, candidate);
            } else if (BY_BENEFIT.compare(candidate, incumbent) > 0) {
                losers.put(
                        incumbent.promotion().promotionId(),
                        candidate.promotion().promotionId());
                best.put(key, candidate);
            } else {
                losers.put(
                        candidate.promotion().promotionId(),
                        incumbent.promotion().promotionId());
            }
        }
        return List.copyOf(best.values());
    }

    /**
     * Item discounts from different stacking groups accumulate per line, and a
     * line can only give up what it costs. Where the groups together would take
     * more than the line's gross, every candidate's share of that line is scaled
     * down by largest-remainder apportionment, so the parts still sum to the cap.
     */
    private List<Candidate> clampToLines(List<Candidate> chosen, Basket basket) {
        Map<String, Long> sums = new HashMap<>();
        for (Candidate candidate : chosen) {
            candidate.benefit().perLineMinor().forEach((lineId, amount) -> sums.merge(lineId, amount, Long::sum));
        }
        Map<String, Long> overdrawn = new HashMap<>();
        for (BasketLine line : basket.lines()) {
            long sum = sums.getOrDefault(line.lineId(), 0L);
            if (sum > line.lineGrossMinor()) {
                overdrawn.put(line.lineId(), line.lineGrossMinor());
            }
        }
        if (overdrawn.isEmpty()) {
            return chosen;
        }

        List<Map<String, Long>> scaled = new ArrayList<>();
        for (Candidate candidate : chosen) {
            scaled.add(new LinkedHashMap<>(candidate.benefit().perLineMinor()));
        }
        for (Map.Entry<String, Long> cap : overdrawn.entrySet()) {
            long[] weights = new long[chosen.size()];
            for (int i = 0; i < chosen.size(); i++) {
                weights[i] = chosen.get(i).benefit().perLineMinor().getOrDefault(cap.getKey(), 0L);
            }
            long[] shares = TaxCalculation.apportion(cap.getValue(), weights);
            for (int i = 0; i < chosen.size(); i++) {
                if (weights[i] > 0) {
                    scaled.get(i).put(cap.getKey(), shares[i]);
                }
            }
        }

        List<Candidate> result = new ArrayList<>(chosen.size());
        for (int i = 0; i < chosen.size(); i++) {
            Candidate original = chosen.get(i);
            Map<String, Long> perLine = new LinkedHashMap<>();
            scaled.get(i).forEach((lineId, amount) -> {
                if (amount > 0) {
                    perLine.put(lineId, amount);
                }
            });
            result.add(new Candidate(
                    original.promotion(),
                    new Benefit(
                            Map.copyOf(perLine),
                            original.benefit().orderMinor(),
                            original.benefit().deliveryMinor(),
                            perLine.values().stream().mapToLong(Long::longValue).sum()
                                    + original.benefit().orderMinor()
                                    + original.benefit().deliveryMinor())));
        }
        return result;
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

    /** Markups: higher priority wins, then the lower id. Never the larger uplift. */
    private static final Comparator<Candidate> BY_PRIORITY = Comparator.comparingInt(
                    (Candidate candidate) -> candidate.promotion().priority())
            .thenComparing(candidate -> candidate.promotion().promotionId(), Comparator.reverseOrder());

    /** An exclusive scenario ranks like a candidate: worth, then priority, then the lower id. */
    private static final Comparator<Combination> BY_SCENARIO = Comparator.comparingLong(Combination::valueMinor)
            .thenComparingInt(scenario -> scenario.chosen().get(0).promotion().priority())
            .thenComparing(scenario -> scenario.chosen().get(0).promotion().promotionId(), Comparator.reverseOrder());

    private static List<Promotion> sortedById(List<Promotion> promotions) {
        return promotions.stream()
                .sorted(Comparator.comparing(Promotion::promotionId))
                .toList();
    }

    private static Promotion byId(List<Promotion> promotions, UUID id) {
        return promotions.stream()
                .filter(promotion -> promotion.promotionId().equals(id))
                .findFirst()
                .orElseThrow();
    }

    // -------------------------------------------------------------- matching

    /** Which of the basket's lines this promotion's item conditions name. */
    private Set<String> matchingLines(Promotion promotion, Basket basket) {
        Set<String> matched =
                basket.lines().stream().map(BasketLine::lineId).collect(Collectors.toCollection(LinkedHashSet::new));

        for (Condition condition : promotion.conditions()) {
            switch (condition.type()) {
                case PRODUCT -> {
                    Set<UUID> ids = condition.operands().requireIds("productIds");
                    apply(matched, condition, idsOf(basket, line -> ids.contains(line.productId())));
                }
                case VARIANT -> {
                    Set<UUID> ids = condition.operands().requireIds("variantIds");
                    apply(matched, condition, idsOf(basket, line -> ids.contains(line.variantId())));
                }
                case CATEGORY -> {
                    Set<UUID> ids = condition.operands().requireIds("categoryIds");
                    apply(
                            matched,
                            condition,
                            idsOf(basket, line -> line.categoryIds().stream().anyMatch(ids::contains)));
                }
                default -> {
                    /* Not a line predicate. Handled in firstFailingCondition. */
                }
            }
        }
        return matched;
    }

    /** An intersection, or, for an {@code exclude} operand, a set difference. */
    private static void apply(Set<String> matched, Condition condition, Set<String> named) {
        if (condition.operands().optionalBoolean("exclude", false)) {
            matched.removeAll(named);
        } else {
            matched.retainAll(named);
        }
    }

    private Set<String> idsOf(Basket basket, Predicate<BasketLine> predicate) {
        return basket.lines().stream()
                .filter(predicate)
                .map(BasketLine::lineId)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The sequence of the first condition that does not hold, or -1 when all
     * of them do. Every condition must hold. There is no OR, by design — see
     * {@link Promotion}.
     */
    private int firstFailingCondition(
            Promotion promotion, Basket basket, PromotionContext context, Set<String> matchedLines) {

        for (Condition condition : promotion.conditions()) {
            boolean holds =
                    switch (condition.type()) {
                        // The three line predicates hold when anything survived the
                        // intersection above. A promotion naming a product the cart does
                        // not contain matches no line and therefore does not apply.
                        case PRODUCT, VARIANT, CATEGORY -> !matchedLines.isEmpty();
                        case QUANTITY_AT_LEAST -> {
                            int have = quantityOf(basket, matchedLines);
                            int want = condition.operands().requireInt("quantity");
                            yield condition.operands().optionalBoolean("exact", false) ? have == want : have >= want;
                        }
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
                        case PAYMENT_METHOD ->
                            context.paymentMethodCode() != null
                                    && condition
                                            .operands()
                                            .requireStrings("paymentMethodCodes")
                                            .contains(context.paymentMethodCode());
                        case CHANNEL_TYPE ->
                            context.channelType() != null
                                    && condition
                                            .operands()
                                            .requireStrings("channelTypes")
                                            .contains(context.channelType());
                        case ORDER_SEQUENCE -> sequenceHolds(condition, context);
                        case DELIVERY_ZONE ->
                            context.deliveryZoneId() != null
                                    && condition
                                            .operands()
                                            .requireIds("zoneIds")
                                            .contains(context.deliveryZoneId());
                    };
            if (!holds) {
                return condition.sequence();
            }
        }
        return -1;
    }

    /**
     * First, the Nth, or every Nth order of the customer at the brand or on the
     * channel. A guest never matches: there is no history to count, and a
     * promotion that rewarded "the first order of a stranger" would reward every
     * guest checkout.
     */
    private boolean sequenceHolds(Condition condition, PromotionContext context) {
        String basis = condition.operands().optionalString("basis").orElse("BRAND");
        Integer position = "CHANNEL".equals(basis) ? context.channelOrderPosition() : context.brandOrderPosition();
        if (position == null) {
            return false;
        }
        String mode = condition.operands().optionalString("mode").orElse("FIRST");
        return switch (mode) {
            case "FIRST" -> position == 1;
            case "NTH" -> position == condition.operands().requireInt("n");
            case "EVERY_NTH" -> position % condition.operands().requireInt("n") == 0;
            default -> throw new IllegalStateException("Unknown ORDER_SEQUENCE mode " + mode);
        };
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

    // --------------------------------------------------------------- benefit

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
                case FREE_ITEM ->
                    freeItem(action, basket, matchedLines)
                            .forEach((lineId, amount) -> perLine.merge(lineId, amount, Long::sum));
                case ITEM_PERCENTAGE_MARKUP, ITEM_FIXED_MARKUP -> {
                    /* A markup action on a discount is refused by the validator. */
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
     * A gift, bounded per promotion and allocated in a fixed order (ADR 0140).
     *
     * <p>One number is computed for the whole cart and then spread over the lines.
     * The gift lines are the basket lines whose variant is named; {@code M} is the
     * summed quantity of the lines the promotion's item conditions match, counted
     * once for the promotion and counting every matched unit, including one that
     * ends up free. The free units are {@code min(quantity, gift units in the
     * cart)} for {@code ONCE} and additionally {@code floor(M / triggerQuantity)}
     * for {@code PER_MULTIPLE}. Pricing never invents a line, so the gift can
     * never exceed what the cart holds.
     *
     * <p>They are then allocated highest unit amount first (modifiers included, as
     * priced), then lowest line id, each line taking {@code min(remaining,
     * line.quantity)}. The order makes the result independent of the order the
     * lines arrive in, and gives the customer the larger benefit. A cart that
     * splits the gift variant over any number of lines therefore receives exactly
     * the units one line would; the earlier per-line bound gave one gift per line.
     */
    private Map<String, Long> freeItem(Action action, Basket basket, Set<String> matchedLines) {
        Set<UUID> variantIds = action.operands().requireIds("variantIds");
        int bound = action.operands().requireInt("quantity");
        String mode = action.operands().optionalString("mode").orElse("ONCE");
        int trigger = action.operands()
                .optionalLong("triggerQuantity")
                .map(Long::intValue)
                .orElse(1);

        List<BasketLine> giftLines = basket.lines().stream()
                .filter(line -> variantIds.contains(line.variantId()))
                .sorted(Comparator.comparingLong(BasketLine::unitAmountMinor)
                        .reversed()
                        .thenComparing(BasketLine::lineId))
                .toList();
        long giftUnits = giftLines.stream().mapToLong(BasketLine::quantity).sum();

        long free = Math.min(bound, giftUnits);
        if ("PER_MULTIPLE".equals(mode)) {
            free = Math.min(free, quantityOf(basket, matchedLines) / Math.max(1, trigger));
        }

        Map<String, Long> allocated = new LinkedHashMap<>();
        for (BasketLine line : giftLines) {
            if (free <= 0) {
                break;
            }
            long units = Math.min(free, line.quantity());
            allocated.put(line.lineId(), units * line.unitAmountMinor());
            free -= units;
        }
        return allocated;
    }

    /**
     * What a markup adds per line (ADR 0140). A fixed amount per unit, or a
     * percentage of the unit price rounded half-up to whole som per unit, so unit
     * price times quantity stays an integer.
     */
    private Benefit markupOf(Promotion promotion, Basket basket, Set<String> matchedLines) {
        Map<String, Long> perLine = new LinkedHashMap<>();
        for (Action action : promotion.actions()) {
            switch (action.type()) {
                case ITEM_PERCENTAGE_MARKUP -> {
                    long basisPoints = action.operands().requireLong("basisPoints");
                    for (BasketLine line : basket.linesIn(matchedLines)) {
                        perLine.merge(
                                line.lineId(),
                                percentageOf(line.unitAmountMinor(), basisPoints) * line.quantity(),
                                Long::sum);
                    }
                }
                case ITEM_FIXED_MARKUP -> {
                    long perUnit = action.operands().requireLong("amountMinor");
                    for (BasketLine line : basket.linesIn(matchedLines)) {
                        perLine.merge(line.lineId(), perUnit * line.quantity(), Long::sum);
                    }
                }
                default -> {
                    /* A discount action on a markup is refused by the validator. */
                }
            }
        }
        long total = perLine.values().stream().mapToLong(Long::longValue).sum();
        return new Benefit(Map.copyOf(perLine), 0, 0, total);
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
        keys.sort(Comparator.naturalOrder());
        long[] weights = new long[keys.size() + 2];
        for (int i = 0; i < keys.size(); i++) {
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

    private Outcome build(List<Candidate> chosen, Basket basket, List<TraceEntry> traceSoFar) {
        Map<String, Long> lineDiscounts = new LinkedHashMap<>();
        for (Candidate candidate : chosen) {
            candidate
                    .benefit()
                    .perLineMinor()
                    .forEach((lineId, amount) -> lineDiscounts.merge(lineId, amount, Long::sum));
        }
        long rawOrderDiscount =
                chosen.stream().mapToLong(c -> c.benefit().orderMinor()).sum();
        long rawDeliveryBenefit =
                chosen.stream().mapToLong(c -> c.benefit().deliveryMinor()).sum();

        // The order discount cannot exceed what is left after item discounts. Two
        // promotions in different stacking groups can each be legitimate and
        // together take a basket below zero, which ADR 0018 rejects.
        long afterItems = basket.goodsSubtotalMinor()
                - lineDiscounts.values().stream().mapToLong(Long::longValue).sum();
        long orderDiscount = Math.min(rawOrderDiscount, Math.max(0, afterItems));
        long deliveryBenefit = Math.min(rawDeliveryBenefit, basket.deliveryFeeMinor());

        // Each candidate's own orderMinor/deliveryMinor, scaled down to what the
        // clamp above actually left -- the same largest-remainder apportionment
        // capped() already uses for a single promotion's own maximumDiscountMinor
        // cap, applied here across promotions instead. Unscaled when nothing was
        // clamped, which is every quote with one promotion or with several that
        // never approach the basket's own ceiling.
        long[] orderShares = apportionAcrossCandidates(
                chosen, candidate -> candidate.benefit().orderMinor(), rawOrderDiscount, orderDiscount);
        long[] deliveryShares = apportionAcrossCandidates(
                chosen, candidate -> candidate.benefit().deliveryMinor(), rawDeliveryBenefit, deliveryBenefit);

        List<AppliedPromotion> applied = new ArrayList<>(chosen.size());
        Map<UUID, TraceEntry> trace = new LinkedHashMap<>();
        traceSoFar.forEach(entry -> trace.put(entry.promotionId(), entry));
        for (int i = 0; i < chosen.size(); i++) {
            Candidate candidate = chosen.get(i);
            Promotion promotion = candidate.promotion();
            long own = candidate.benefit().perLineTotal() + orderShares[i] + deliveryShares[i];
            applied.add(new AppliedPromotion(
                    promotion.promotionId(),
                    promotion.code(),
                    promotion.definitionVersion(),
                    promotion.scope(),
                    candidate.benefit().perLineMinor(),
                    orderShares[i],
                    deliveryShares[i],
                    promotion.loyaltyAccrual(),
                    promotion.loyaltyRedemption()));
            trace.put(
                    promotion.promotionId(),
                    new TraceEntry(promotion.promotionId(), promotion.code(), Verdict.APPLIED, null, List.of(), own));
        }

        return new Outcome(
                Map.copyOf(lineDiscounts),
                orderDiscount,
                deliveryBenefit,
                List.copyOf(applied),
                List.copyOf(trace.values()));
    }

    /**
     * Each candidate's own share of {@code raw}, scaled down to {@code capped}
     * only when the aggregate clamp actually reduced it — every candidate's raw
     * value verbatim otherwise, so the ordinary uncapped quote never pays for
     * {@link TaxCalculation#apportion}'s rounding.
     */
    private static long[] apportionAcrossCandidates(
            List<Candidate> chosen, ToLongFunction<Candidate> extractor, long raw, long capped) {
        long[] raws = chosen.stream().mapToLong(extractor).toArray();
        return raw == capped ? raws : TaxCalculation.apportion(capped, raws);
    }

    // ------------------------------------------------------------------ values

    /** One promotion that applies, and what it is worth. */
    private record Candidate(Promotion promotion, Benefit benefit) {}

    private record Benefit(Map<String, Long> perLineMinor, long orderMinor, long deliveryMinor, long totalMinor) {

        long perLineTotal() {
            return perLineMinor.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    /**
     * What stages 3 and 4 decided for one pool, and what it is worth.
     *
     * @param seen every candidate whose conditions held, winner or not
     * @param groupLosers loser id to the id of the group winner it lost to
     * @param valueMinor what the customer receives, after the line and order clamps
     */
    private record Combination(
            List<Candidate> chosen, List<Candidate> seen, Map<UUID, UUID> groupLosers, long valueMinor) {}

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
     * Everything outside the basket a condition may ask about (ADR 0140).
     *
     * <p>Each component is resolved before the engine runs and enters the context
     * hash; {@code PricingEngineContextHashTests} enumerates this record and fails
     * the build when a component is missing from the canonical string, so the next
     * condition cannot repeat the hole the previous evaluator had.
     *
     * <p>The local day and minute are resolved by the caller from the location's
     * IANA timezone at the service instant, not computed here: a "lunchtime"
     * promotion means lunchtime where the branch is, and this class may not read a
     * clock or a zone.
     *
     * @param serviceInstant the instant windows are judged at. On the cart path
     *        the clock; on an amendment the instant the order was placed under.
     *        Null means the instant the engine is handed
     * @param brandOrderPosition the position this order takes among the account's
     *        live orders at the brand (prior orders plus one), null for a guest
     * @param channelOrderPosition the same, among orders on this channel type
     * @param limitReachedPromotionIds limited promotions whose total or this
     *        customer's allowance is used up
     * @param notClaimedAtPlacementPromotionIds limited promotions an amended order
     *        does not already hold: the claim happens in checkout, never in an
     *        amendment
     */
    public record PromotionContext(
            String channel,
            @Nullable String channelType,
            UUID locationId,
            String fulfillmentMode,
            @Nullable String paymentMethodCode,
            @Nullable UUID deliveryZoneId,
            boolean firstOrder,
            @Nullable Integer brandOrderPosition,
            @Nullable Integer channelOrderPosition,
            Set<String> customerSegments,
            Set<UUID> presentedCouponPromotionIds,
            @Nullable Instant serviceInstant,
            int localDayOfWeek,
            int localMinuteOfDay,
            Set<UUID> limitReachedPromotionIds,
            Set<UUID> notClaimedAtPlacementPromotionIds) {

        public PromotionContext {
            customerSegments = customerSegments == null ? Set.of() : Set.copyOf(customerSegments);
            presentedCouponPromotionIds =
                    presentedCouponPromotionIds == null ? Set.of() : Set.copyOf(presentedCouponPromotionIds);
            limitReachedPromotionIds =
                    limitReachedPromotionIds == null ? Set.of() : Set.copyOf(limitReachedPromotionIds);
            notClaimedAtPlacementPromotionIds = notClaimedAtPlacementPromotionIds == null
                    ? Set.of()
                    : Set.copyOf(notClaimedAtPlacementPromotionIds);
        }

        /** The context as it was before ADR 0140: no channel type, payment method, zone, sequence or limits. */
        public PromotionContext(
                String channel,
                UUID locationId,
                String fulfillmentMode,
                boolean firstOrder,
                Set<String> customerSegments,
                Set<UUID> presentedCouponPromotionIds,
                int localDayOfWeek,
                int localMinuteOfDay) {
            this(
                    channel,
                    null,
                    locationId,
                    fulfillmentMode,
                    null,
                    null,
                    firstOrder,
                    null,
                    null,
                    customerSegments,
                    presentedCouponPromotionIds,
                    null,
                    localDayOfWeek,
                    localMinuteOfDay,
                    Set.of(),
                    Set.of());
        }
    }

    /** What stages 3 and 4 decided, for the engine to apply and record. */
    public record Outcome(
            Map<String, Long> lineDiscountsMinor,
            long orderDiscountMinor,
            long deliveryBenefitMinor,
            List<AppliedPromotion> applied,
            List<TraceEntry> trace) {

        public Outcome {
            lineDiscountsMinor = lineDiscountsMinor == null ? Map.of() : Map.copyOf(lineDiscountsMinor);
            applied = applied == null ? List.of() : List.copyOf(applied);
            trace = trace == null ? List.of() : List.copyOf(trace);
        }

        public Outcome(
                Map<String, Long> lineDiscountsMinor,
                long orderDiscountMinor,
                long deliveryBenefitMinor,
                List<AppliedPromotion> applied) {
            this(lineDiscountsMinor, orderDiscountMinor, deliveryBenefitMinor, applied, List.of());
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

    /** What stage 2b decided: the uplift per line, and which markups produced it. */
    public record MarkupOutcome(
            Map<String, Long> lineMarkupsMinor, List<AppliedMarkup> applied, List<TraceEntry> trace) {

        public MarkupOutcome {
            lineMarkupsMinor = lineMarkupsMinor == null ? Map.of() : Map.copyOf(lineMarkupsMinor);
            applied = applied == null ? List.of() : List.copyOf(applied);
            trace = trace == null ? List.of() : List.copyOf(trace);
        }

        public boolean isEmpty() {
            return applied.isEmpty();
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
            long deliveryMinor,
            Promotion.LoyaltyAccrual loyaltyAccrual,
            Promotion.LoyaltyRedemption loyaltyRedemption) {

        public AppliedPromotion(
                UUID promotionId,
                String code,
                int definitionVersion,
                Promotion.Scope scope,
                Map<String, Long> perLineMinor,
                long orderMinor,
                long deliveryMinor) {
            this(
                    promotionId,
                    code,
                    definitionVersion,
                    scope,
                    perLineMinor,
                    orderMinor,
                    deliveryMinor,
                    Promotion.LoyaltyAccrual.ACCRUE,
                    Promotion.LoyaltyRedemption.ALLOW);
        }

        /** What this promotion took off in total, across lines, the order and the delivery fee. */
        public long totalMinor() {
            return perLineMinor.values().stream().mapToLong(Long::longValue).sum() + orderMinor + deliveryMinor;
        }
    }

    /** One markup that made it onto the quote. */
    public record AppliedMarkup(
            UUID promotionId,
            String code,
            int definitionVersion,
            Map<String, Long> perLineMinor,
            Promotion.LoyaltyAccrual loyaltyAccrual,
            Promotion.LoyaltyRedemption loyaltyRedemption) {

        public long totalMinor() {
            return perLineMinor.values().stream().mapToLong(Long::longValue).sum();
        }
    }

    /** What happened to one promotion in one evaluation: applied, or the reason it was not. */
    public enum Verdict {
        APPLIED,
        CONDITION_FAILED,
        OUTSIDE_WINDOW,
        COUPON_NOT_PRESENTED,
        LIMIT_REACHED,
        NOT_CLAIMED_AT_PLACEMENT,
        LOST_TO,
        SUPPRESSED_BY_EXCLUSIVE,
        ZERO_BENEFIT,
        CURRENCY_MISMATCH
    }

    /**
     * One line of the decision trace (ADR 0140).
     *
     * @param conditionSequence the first condition that did not hold, for {@link Verdict#CONDITION_FAILED}
     * @param lostTo the promotions that won instead: the group winner, the
     *        exclusive promotion, or the whole combination
     * @param benefitMinor what the promotion gave, for {@link Verdict#APPLIED}
     */
    public record TraceEntry(
            UUID promotionId,
            String code,
            Verdict verdict,
            @Nullable Integer conditionSequence,
            List<UUID> lostTo,
            long benefitMinor) {

        public TraceEntry {
            lostTo = lostTo == null ? List.of() : List.copyOf(lostTo);
        }
    }
}
