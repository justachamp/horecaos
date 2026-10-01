package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.pricing.application.PromotionEvaluator;
import uz.horecaos.platform.pricing.application.PromotionEvaluator.AppliedPromotion;
import uz.horecaos.platform.pricing.application.PromotionEvaluator.Basket;
import uz.horecaos.platform.pricing.application.PromotionEvaluator.BasketLine;
import uz.horecaos.platform.pricing.application.PromotionEvaluator.Outcome;
import uz.horecaos.platform.pricing.application.PromotionEvaluator.PromotionContext;
import uz.horecaos.platform.pricing.application.PromotionEvaluator.TraceEntry;
import uz.horecaos.platform.pricing.application.PromotionEvaluator.Verdict;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.Promotion.Action;
import uz.horecaos.platform.pricing.domain.Promotion.Condition;
import uz.horecaos.platform.pricing.domain.Promotion.Operands;

/**
 * ADR 0140: the rule engine's evaluator.
 *
 * <p>The record names seven things the previous evaluator got wrong or left inert
 * and requires each as a test before an automatic promotion can reach a customer.
 * The ones about the evaluator itself are here: comparative exclusivity, the
 * per-line clamp, the currency guard and a gift bounded per promotion (the last
 * on the evaluator directly, because the simulator-parity test shares the code
 * under test and cannot see the leak). The vocabulary the record adds, markups and
 * the property tests over generated baskets are here too.
 */
class PromotionEvaluatorRuleEngineTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID ZONE = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-01T07:30:00Z");

    private static final UUID MARGHERITA = UUID.randomUUID();
    private static final UUID MARGHERITA_VARIANT = UUID.randomUUID();
    private static final UUID COLA = UUID.randomUUID();
    private static final UUID COLA_VARIANT = UUID.randomUUID();
    private static final UUID PIZZA_CATEGORY = UUID.randomUUID();
    private static final UUID MENU_ROOT = UUID.randomUUID();

    private final PromotionEvaluator evaluator = new PromotionEvaluator();

    // ------------------------------------------------- the worked example (A)

    /**
     * ADR 0140's worked example, to the som: 2 x Margherita at 45 000 (Pizza), 1 x
     * Cola 12 000, delivery 15 000, goods 102 000. P1 and P2 share a group and P2
     * wins (10 000 against 9 000); P3 is 5% of the reduced 92 000; P4 clears its
     * 90 000 threshold on the reduced subtotal. SAVE10 alone is worth 10 200.
     */
    private List<Promotion> workedExample() {
        Promotion p1 =
                rule("P1", Promotion.Scope.ITEM, "menu", false, 10, itemPercent(1_000), category(PIZZA_CATEGORY));
        Promotion p2 = rule("P2", Promotion.Scope.ITEM, "menu", false, 20, itemFixed(5_000), category(PIZZA_CATEGORY));
        Promotion p3 = rule(
                "P3",
                Promotion.Scope.ORDER,
                "payment",
                false,
                0,
                new Action(1, Action.Type.ORDER_PERCENTAGE_DISCOUNT, bp(500)),
                new Condition(1, Condition.Type.PAYMENT_METHOD, ops("paymentMethodCodes", List.of("CLICK"))));
        Promotion p4 = rule(
                "P4",
                Promotion.Scope.DELIVERY,
                "delivery",
                false,
                0,
                new Action(1, Action.Type.FREE_DELIVERY, Operands.empty()),
                new Condition(1, Condition.Type.SUBTOTAL_AT_LEAST, ops("amountMinor", 90_000L)));
        Promotion save10 = rule(
                "SAVE10",
                Promotion.Scope.ORDER,
                "code",
                true,
                0,
                new Action(1, Action.Type.ORDER_PERCENTAGE_DISCOUNT, bp(1_000)));
        return List.of(p1, p2, p3, p4, save10);
    }

    private Basket workedExampleBasket() {
        return new Basket(
                "UZS",
                List.of(
                        new BasketLine(
                                "pizza", MARGHERITA_VARIANT, MARGHERITA, Set.of(PIZZA_CATEGORY), 2, 45_000L, 90_000L),
                        new BasketLine("cola", COLA_VARIANT, COLA, Set.of(), 1, 12_000L, 12_000L)),
                102_000L,
                15_000L);
    }

    private PromotionContext clickContext(Set<UUID> presented) {
        return new PromotionContext(
                "STOREFRONT",
                "WEB",
                LOCATION,
                "DELIVERY",
                "CLICK",
                ZONE,
                false,
                null,
                null,
                Set.of(),
                presented,
                null,
                3,
                12 * 60 + 30,
                Set.of(),
                Set.of());
    }

    @Test
    @DisplayName("worked example: the automatic combination is worth 29 600 and a smaller code steps aside")
    void aSmallerExclusiveCodeStepsAsideForABetterCombination() {
        List<Promotion> promotions = workedExample();
        UUID saveId = promotions.get(4).promotionId();

        Outcome outcome = evaluator.evaluate(promotions, workedExampleBasket(), clickContext(Set.of(saveId)), NOW);

        assertThat(outcome.applied())
                .as("P1 lost to P2 in group menu, so P2, P3 and P4 apply and SAVE10 does not")
                .extracting(AppliedPromotion::code)
                .containsExactlyInAnyOrder("P2", "P3", "P4");
        assertThat(outcome.lineDiscountsMinor()).containsExactly(Map.entry("pizza", 10_000L));
        assertThat(outcome.orderDiscountMinor())
                .as("5% of the 92 000 stage 3 left, not of 102 000")
                .isEqualTo(4_600L);
        assertThat(outcome.deliveryBenefitMinor())
                .as("the 90 000 threshold is met by the reduced subtotal")
                .isEqualTo(15_000L);
        assertThat(outcome.lineDiscountsMinor().getOrDefault("pizza", 0L)
                        + outcome.orderDiscountMinor()
                        + outcome.deliveryBenefitMinor())
                .isEqualTo(29_600L);

        TraceEntry save = traceOf(outcome, saveId);
        assertThat(save.verdict()).isEqualTo(Verdict.LOST_TO);
        assertThat(save.lostTo())
                .as("the trace names the whole combination it lost to")
                .containsExactlyInAnyOrder(
                        promotions.get(1).promotionId(),
                        promotions.get(2).promotionId(),
                        promotions.get(3).promotionId());
        assertThat(traceOf(outcome, promotions.get(0).promotionId()).verdict()).isEqualTo(Verdict.LOST_TO);
    }

    @Test
    @DisplayName("an exclusive code that is worth more than the automatic combination applies alone")
    void aLargerExclusiveCodeSuppressesTheAutomaticOffers() {
        List<Promotion> promotions = new ArrayList<>(workedExample());
        // 50% off the order: 51 000 alone, against the combination's 29 600.
        Promotion big = rule(
                "BIG50",
                Promotion.Scope.ORDER,
                "code",
                true,
                0,
                new Action(1, Action.Type.ORDER_PERCENTAGE_DISCOUNT, bp(5_000)));
        promotions.set(4, big);

        Outcome outcome =
                evaluator.evaluate(promotions, workedExampleBasket(), clickContext(Set.of(big.promotionId())), NOW);

        assertThat(outcome.applied()).extracting(AppliedPromotion::code).containsExactly("BIG50");
        assertThat(outcome.orderDiscountMinor()).isEqualTo(51_000L);
        assertThat(traceOf(outcome, promotions.get(1).promotionId()).verdict())
                .as("the automatic offers are reported as suppressed, not as lost on merit")
                .isEqualTo(Verdict.SUPPRESSED_BY_EXCLUSIVE);
    }

    @Test
    @DisplayName("a tie goes to the exclusive promotion, so behaviour is unchanged when it is at least as good")
    void aTieGoesToTheExclusivePromotion() {
        // Automatic: 10% off the order = 10 200. Exclusive: a flat 10 200.
        Promotion automatic = rule(
                "AUTO10",
                Promotion.Scope.ORDER,
                "order",
                false,
                0,
                new Action(1, Action.Type.ORDER_PERCENTAGE_DISCOUNT, bp(1_000)));
        Promotion exclusive = rule(
                "FLAT",
                Promotion.Scope.ORDER,
                "code",
                true,
                0,
                new Action(1, Action.Type.ORDER_FIXED_DISCOUNT, ops("amountMinor", 10_200L)));

        Outcome outcome = evaluator.evaluate(
                List.of(automatic, exclusive),
                workedExampleBasket(),
                clickContext(Set.of(exclusive.promotionId())),
                NOW);

        assertThat(outcome.applied()).extracting(AppliedPromotion::code).containsExactly("FLAT");
    }

    // ------------------------------------------------------------ per-line clamp

    @Test
    @DisplayName("two item discounts in different groups clamp at the line's gross")
    void stackedItemDiscountsNeverTakeALineBelowZero() {
        // 60% and 60% on one 40 000 line, in two groups: 48 000 would be 120%.
        Promotion first = rule("A60", Promotion.Scope.ITEM, "g1", false, 0, itemPercent(6_000), product(MARGHERITA));
        Promotion second = rule("B60", Promotion.Scope.ITEM, "g2", false, 0, itemPercent(6_000), product(MARGHERITA));
        Basket basket = new Basket(
                "UZS",
                List.of(new BasketLine("pizza", MARGHERITA_VARIANT, MARGHERITA, Set.of(), 1, 40_000L, 40_000L)),
                40_000L,
                0L);

        Outcome outcome = evaluator.evaluate(List.of(first, second), basket, clickContext(Set.of()), NOW);

        assertThat(outcome.lineDiscountsMinor().get("pizza"))
                .as("the line gives up what it costs and no more")
                .isEqualTo(40_000L);
        long shares = outcome.applied().stream()
                .mapToLong(applied -> applied.perLineMinor().getOrDefault("pizza", 0L))
                .sum();
        assertThat(shares)
                .as("the per-promotion adjustments still sum to the clamped discount")
                .isEqualTo(40_000L);
        assertThat(outcome.applied()).hasSize(2);
    }

    // ---------------------------------------------------------- currency guard

    @Test
    @DisplayName("a promotion in another currency is skipped and the trace says why")
    void aCurrencyMismatchSkipsThePromotion() {
        Promotion dollars = new Promotion(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                "USD10",
                Promotion.Scope.ORDER,
                "order",
                false,
                0,
                false,
                null,
                "USD",
                NOW.minusSeconds(60),
                null,
                1,
                List.of(),
                List.of(new Action(1, Action.Type.ORDER_FIXED_DISCOUNT, ops("amountMinor", 5_000L))));

        Outcome outcome = evaluator.evaluate(List.of(dollars), workedExampleBasket(), clickContext(Set.of()), NOW);

        assertThat(outcome.applied()).isEmpty();
        assertThat(traceOf(outcome, dollars.promotionId()).verdict()).isEqualTo(Verdict.CURRENCY_MISMATCH);
    }

    // -------------------------------------------------------------- FREE_ITEM

    private Promotion freeCola(int quantity, int triggerQuantity, String mode) {
        Map<String, Object> values = new java.util.HashMap<>();
        values.put("variantIds", List.of(COLA_VARIANT.toString()));
        values.put("quantity", (long) quantity);
        values.put("triggerQuantity", (long) triggerQuantity);
        values.put("mode", mode);
        return rule(
                "FREECOLA",
                Promotion.Scope.ITEM,
                "gift",
                false,
                0,
                new Action(1, Action.Type.FREE_ITEM, new Operands(values)),
                category(PIZZA_CATEGORY));
    }

    private Basket pizzaAndColas(int pizzas, int... colaLines) {
        List<BasketLine> lines = new ArrayList<>();
        lines.add(new BasketLine(
                "pizza", MARGHERITA_VARIANT, MARGHERITA, Set.of(PIZZA_CATEGORY), pizzas, 45_000L, 45_000L * pizzas));
        long goods = 45_000L * pizzas;
        for (int i = 0; i < colaLines.length; i++) {
            lines.add(new BasketLine(
                    "cola-" + i, COLA_VARIANT, COLA, Set.of(), colaLines[i], 12_000L, 12_000L * colaLines[i]));
            goods += 12_000L * colaLines[i];
        }
        return new Basket("UZS", lines, goods, 0L);
    }

    @Test
    @DisplayName("a gift split across two lines is still one gift")
    void aGiftSplitAcrossLinesIsOneGift() {
        // The cart holds the Cola on two lines (with lemon, without), which it
        // keeps as separate lines. The previous evaluator applied the bound to
        // each line and gave two free Colas for a rule that says one.
        Outcome outcome = evaluator.evaluate(
                List.of(freeCola(1, 1, "ONCE")), pizzaAndColas(1, 1, 1), clickContext(Set.of()), NOW);

        assertThat(outcome.lineDiscountsMinor().values().stream()
                        .mapToLong(Long::longValue)
                        .sum())
                .as("one free Cola, however the cart splits the variant")
                .isEqualTo(12_000L);
    }

    @Test
    @DisplayName("a gift split across three lines is still one gift")
    void aGiftSplitAcrossThreeLinesIsOneGift() {
        Outcome outcome = evaluator.evaluate(
                List.of(freeCola(1, 1, "ONCE")), pizzaAndColas(1, 1, 1, 1), clickContext(Set.of()), NOW);

        assertThat(outcome.totalDiscountMinor()).isEqualTo(12_000L);
    }

    @Test
    @DisplayName("PER_MULTIPLE: five pizzas, trigger 2, quantity 3 gives two free on one line or on five")
    void perMultipleIsPerPromotionNotPerLine() {
        Promotion promotion = freeCola(3, 2, "PER_MULTIPLE");

        // Five pizzas on one line, three Colas on one line.
        Outcome oneLine = evaluator.evaluate(List.of(promotion), pizzaAndColas(5, 3), clickContext(Set.of()), NOW);
        // The same five pizzas on five lines, the same three Colas on three lines.
        List<BasketLine> lines = new ArrayList<>();
        long goods = 0;
        for (int i = 0; i < 5; i++) {
            lines.add(new BasketLine(
                    "pizza-" + i, MARGHERITA_VARIANT, MARGHERITA, Set.of(PIZZA_CATEGORY), 1, 45_000L, 45_000L));
            goods += 45_000L;
        }
        for (int i = 0; i < 3; i++) {
            lines.add(new BasketLine("cola-" + i, COLA_VARIANT, COLA, Set.of(), 1, 12_000L, 12_000L));
            goods += 12_000L;
        }
        Outcome fiveLines = evaluator.evaluate(
                List.of(promotion), new Basket("UZS", lines, goods, 0L), clickContext(Set.of()), NOW);

        assertThat(oneLine.totalDiscountMinor())
                .as("floor(5 / 2) = 2 free Colas")
                .isEqualTo(24_000L);
        assertThat(fiveLines.totalDiscountMinor()).isEqualTo(24_000L);
    }

    @Test
    @DisplayName("a gift bound larger than the Colas in the cart stops at the cart")
    void theGiftNeverExceedsWhatTheCartHolds() {
        Outcome outcome =
                evaluator.evaluate(List.of(freeCola(10, 1, "ONCE")), pizzaAndColas(1, 2), clickContext(Set.of()), NOW);

        assertThat(outcome.totalDiscountMinor())
                .as("two Colas in the cart, so two free, not ten")
                .isEqualTo(24_000L);
    }

    @Test
    @DisplayName("a gift is allocated to the dearest unit first, whatever order the lines arrive in")
    void giftAllocationIsIndependentOfLineOrder() {
        UUID dear = UUID.randomUUID();
        Promotion promotion = rule(
                "FREEANY",
                Promotion.Scope.ITEM,
                "gift",
                false,
                0,
                new Action(
                        1,
                        Action.Type.FREE_ITEM,
                        new Operands(Map.of(
                                "variantIds", List.of(COLA_VARIANT.toString(), dear.toString()), "quantity", 1L))),
                category(PIZZA_CATEGORY));
        BasketLine pizza =
                new BasketLine("pizza", MARGHERITA_VARIANT, MARGHERITA, Set.of(PIZZA_CATEGORY), 1, 45_000L, 45_000L);
        BasketLine cola = new BasketLine("cola", COLA_VARIANT, COLA, Set.of(), 1, 12_000L, 12_000L);
        BasketLine wine = new BasketLine("wine", dear, UUID.randomUUID(), Set.of(), 1, 90_000L, 90_000L);

        List<BasketLine> lines = new ArrayList<>(List.of(pizza, cola, wine));
        Set<String> seen = new java.util.HashSet<>();
        for (int round = 0; round < 6; round++) {
            Collections.shuffle(lines, new Random(round));
            Outcome outcome = evaluator.evaluate(
                    List.of(promotion), new Basket("UZS", lines, 147_000L, 0L), clickContext(Set.of()), NOW);
            seen.add(outcome.lineDiscountsMinor().toString());
            assertThat(outcome.lineDiscountsMinor())
                    .as("the single gift goes to the dearest unit in the cart")
                    .containsExactly(Map.entry("wine", 90_000L));
        }
        assertThat(seen).as("every ordering produced the same result").hasSize(1);
    }

    // -------------------------------------------------------- new conditions

    @Test
    @DisplayName("PAYMENT_METHOD matches the selected method and never an unselected one")
    void paymentMethodCondition() {
        Promotion click = rule(
                "CLICK5",
                Promotion.Scope.ORDER,
                "pay",
                false,
                0,
                new Action(1, Action.Type.ORDER_PERCENTAGE_DISCOUNT, bp(500)),
                new Condition(1, Condition.Type.PAYMENT_METHOD, ops("paymentMethodCodes", List.of("CLICK"))));

        assertThat(evaluator
                        .evaluate(List.of(click), workedExampleBasket(), clickContext(Set.of()), NOW)
                        .orderDiscountMinor())
                .isEqualTo(5_100L);

        PromotionContext cash = new PromotionContext(
                "STOREFRONT",
                "WEB",
                LOCATION,
                "DELIVERY",
                "CASH",
                ZONE,
                false,
                null,
                null,
                Set.of(),
                Set.of(),
                null,
                3,
                750,
                Set.of(),
                Set.of());
        assertThat(evaluator
                        .evaluate(List.of(click), workedExampleBasket(), cash, NOW)
                        .applied())
                .isEmpty();

        PromotionContext unselected = new PromotionContext(
                "STOREFRONT",
                "WEB",
                LOCATION,
                "DELIVERY",
                null,
                ZONE,
                false,
                null,
                null,
                Set.of(),
                Set.of(),
                null,
                3,
                750,
                Set.of(),
                Set.of());
        Outcome none = evaluator.evaluate(List.of(click), workedExampleBasket(), unselected, NOW);
        assertThat(none.applied()).as("an unselected method never matches").isEmpty();
        assertThat(traceOf(none, click.promotionId()).conditionSequence()).isEqualTo(1);
    }

    @Test
    @DisplayName("CHANNEL_TYPE and DELIVERY_ZONE read the context and never match a missing value")
    void channelTypeAndZoneConditions() {
        Promotion app = rule(
                "APP",
                Promotion.Scope.ORDER,
                "a",
                false,
                0,
                new Action(1, Action.Type.ORDER_FIXED_DISCOUNT, ops("amountMinor", 1_000L)),
                new Condition(1, Condition.Type.CHANNEL_TYPE, ops("channelTypes", List.of("IOS", "ANDROID"))));
        Promotion zone = rule(
                "ZONE",
                Promotion.Scope.ORDER,
                "z",
                false,
                0,
                new Action(1, Action.Type.ORDER_FIXED_DISCOUNT, ops("amountMinor", 2_000L)),
                new Condition(1, Condition.Type.DELIVERY_ZONE, ops("zoneIds", List.of(ZONE.toString()))));

        PromotionContext ios = new PromotionContext(
                "STOREFRONT",
                "IOS",
                LOCATION,
                "DELIVERY",
                null,
                ZONE,
                false,
                null,
                null,
                Set.of(),
                Set.of(),
                null,
                3,
                750,
                Set.of(),
                Set.of());
        PromotionContext web = new PromotionContext(
                "STOREFRONT",
                "WEB",
                LOCATION,
                "PICKUP",
                null,
                null,
                false,
                null,
                null,
                Set.of(),
                Set.of(),
                null,
                3,
                750,
                Set.of(),
                Set.of());

        assertThat(evaluator
                        .evaluate(List.of(app, zone), workedExampleBasket(), ios, NOW)
                        .orderDiscountMinor())
                .as("groups a and z differ, so both apply")
                .isEqualTo(3_000L);
        assertThat(evaluator
                        .evaluate(List.of(app, zone), workedExampleBasket(), web, NOW)
                        .applied())
                .as("WEB is not an app channel and a pickup order has no zone")
                .isEmpty();
    }

    @Test
    @DisplayName("ORDER_SEQUENCE: first, Nth and every Nth, by brand or by channel, and a guest never matches")
    void orderSequenceCondition() {
        Promotion first = sequence("FIRST", "FIRST", 1, "BRAND");
        Promotion third = sequence("THIRD", "NTH", 3, "BRAND");
        Promotion everyFifth = sequence("FIFTH", "EVERY_NTH", 5, "BRAND");
        Promotion channelFirst = sequence("CHFIRST", "FIRST", 1, "CHANNEL");

        for (int position : new int[] {1, 2, 3, 5, 10}) {
            Set<String> applied = codesApplied(position, position == 1 ? 1 : 4, first, third, everyFifth, channelFirst);
            assertThat(applied.contains("FIRST")).as("FIRST at %d", position).isEqualTo(position == 1);
            assertThat(applied.contains("THIRD")).as("NTH 3 at %d", position).isEqualTo(position == 3);
            assertThat(applied.contains("FIFTH"))
                    .as("EVERY_NTH 5 at %d", position)
                    .isEqualTo(position % 5 == 0);
        }
        assertThat(codesApplied(7, 1, channelFirst))
                .as("the channel position is independent of the brand position")
                .containsExactly("CHFIRST");

        PromotionContext guest = new PromotionContext(
                "STOREFRONT",
                "WEB",
                LOCATION,
                "DELIVERY",
                null,
                null,
                false,
                null,
                null,
                Set.of(),
                Set.of(),
                null,
                3,
                750,
                Set.of(),
                Set.of());
        assertThat(evaluator
                        .evaluate(List.of(first, channelFirst), workedExampleBasket(), guest, NOW)
                        .applied())
                .as("a guest has no history to count")
                .isEmpty();
    }

    private Set<String> codesApplied(int brandPosition, int channelPosition, Promotion... promotions) {
        PromotionContext context = new PromotionContext(
                "STOREFRONT",
                "WEB",
                LOCATION,
                "DELIVERY",
                null,
                null,
                brandPosition == 1,
                brandPosition,
                channelPosition,
                Set.of(),
                Set.of(),
                null,
                3,
                750,
                Set.of(),
                Set.of());
        // Each promotion in its own group, so they combine and none hides another.
        List<Promotion> grouped = new ArrayList<>();
        for (Promotion promotion : promotions) {
            grouped.add(promotion);
        }
        Set<String> codes = new java.util.HashSet<>();
        evaluator
                .evaluate(grouped, workedExampleBasket(), context, NOW)
                .applied()
                .forEach(a -> codes.add(a.code()));
        return codes;
    }

    private Promotion sequence(String code, String mode, int n, String basis) {
        return rule(
                code,
                Promotion.Scope.ORDER,
                "seq-" + code,
                false,
                0,
                new Action(1, Action.Type.ORDER_FIXED_DISCOUNT, ops("amountMinor", 1_000L)),
                new Condition(1, Condition.Type.ORDER_SEQUENCE, ops("mode", mode, "n", (long) n, "basis", basis)));
    }

    @Test
    @DisplayName("an exclude operand is a set difference, and QUANTITY_AT_LEAST can read 'equal'")
    void excludeAndExactQuantity() {
        Promotion allButCola = rule(
                "NOCOLA",
                Promotion.Scope.ITEM,
                "x",
                false,
                0,
                itemPercent(1_000),
                new Condition(1, Condition.Type.PRODUCT, ops("productIds", List.of(COLA.toString()), "exclude", true)));

        Outcome outcome = evaluator.evaluate(List.of(allButCola), workedExampleBasket(), clickContext(Set.of()), NOW);
        assertThat(outcome.lineDiscountsMinor())
                .as("the Cola is excluded, so only the pizza line is discounted")
                .containsExactly(Map.entry("pizza", 9_000L));

        Promotion exactlyTwo = rule(
                "TWO",
                Promotion.Scope.ITEM,
                "y",
                false,
                0,
                itemPercent(1_000),
                product(MARGHERITA),
                new Condition(2, Condition.Type.QUANTITY_AT_LEAST, ops("quantity", 2L, "exact", true)));
        assertThat(evaluator
                        .evaluate(List.of(exactlyTwo), workedExampleBasket(), clickContext(Set.of()), NOW)
                        .applied())
                .as("two pizzas is exactly two")
                .hasSize(1);
        assertThat(evaluator
                        .evaluate(List.of(exactlyTwo), pizzaAndColas(3), clickContext(Set.of()), NOW)
                        .applied())
                .as("three pizzas is at least two and not exactly two")
                .isEmpty();
    }

    @Test
    @DisplayName("windows are judged at the service instant when the context carries one")
    void aWindowReadsTheServiceInstantNotTheClock() {
        Promotion lunch = rule(
                "LUNCH",
                Promotion.Scope.ORDER,
                "lunch",
                false,
                0,
                new Action(1, Action.Type.ORDER_PERCENTAGE_DISCOUNT, bp(1_000)));
        Promotion shortLived = new Promotion(
                lunch.promotionId(),
                TENANT,
                BRAND,
                "LUNCH",
                Promotion.Scope.ORDER,
                "lunch",
                false,
                0,
                false,
                null,
                "UZS",
                NOW.minusSeconds(3_600),
                NOW.plusSeconds(1_800),
                1,
                List.of(),
                lunch.actions());

        PromotionContext placedInsideTheWindow = new PromotionContext(
                "STOREFRONT",
                "WEB",
                LOCATION,
                "PICKUP",
                null,
                null,
                false,
                null,
                null,
                Set.of(),
                Set.of(),
                NOW,
                3,
                750,
                Set.of(),
                Set.of());
        Instant editInstant = NOW.plusSeconds(6 * 3_600);

        assertThat(evaluator
                        .evaluate(List.of(shortLived), workedExampleBasket(), placedInsideTheWindow, editInstant)
                        .applied())
                .as("an amendment judges the window at the instant the order was placed")
                .hasSize(1);
        assertThat(evaluator
                        .evaluate(List.of(shortLived), workedExampleBasket(), clickContext(Set.of()), editInstant)
                        .applied())
                .as("with no service instant the clock decides, and the window has closed")
                .isEmpty();
    }

    @Test
    @DisplayName("a limited promotion at its limit, or one an amendment never claimed, is reported and skipped")
    void limitsAndUnclaimedPromotionsAreTraced() {
        Promotion limited = rule(
                "FIRST100",
                Promotion.Scope.ORDER,
                "limit",
                false,
                0,
                new Action(1, Action.Type.ORDER_FIXED_DISCOUNT, ops("amountMinor", 1_000L)));
        Promotion unclaimed = rule(
                "NEVERCLAIMED",
                Promotion.Scope.ORDER,
                "unclaimed",
                false,
                0,
                new Action(1, Action.Type.ORDER_FIXED_DISCOUNT, ops("amountMinor", 1_000L)));
        PromotionContext context = new PromotionContext(
                "STOREFRONT",
                "WEB",
                LOCATION,
                "PICKUP",
                null,
                null,
                false,
                null,
                null,
                Set.of(),
                Set.of(),
                null,
                3,
                750,
                Set.of(limited.promotionId()),
                Set.of(unclaimed.promotionId()));

        Outcome outcome = evaluator.evaluate(List.of(limited, unclaimed), workedExampleBasket(), context, NOW);

        assertThat(outcome.applied()).isEmpty();
        assertThat(traceOf(outcome, limited.promotionId()).verdict()).isEqualTo(Verdict.LIMIT_REACHED);
        assertThat(traceOf(outcome, unclaimed.promotionId()).verdict()).isEqualTo(Verdict.NOT_CLAIMED_AT_PLACEMENT);
    }

    @Test
    @DisplayName("an item and an order promotion in one group do not contest each other")
    void itemAndOrderPromotionsNeverShareAContest() {
        Promotion item =
                rule("ITEM", Promotion.Scope.ITEM, "shared", false, 0, itemPercent(5_000), product(MARGHERITA));
        Promotion order = rule(
                "ORDER",
                Promotion.Scope.ORDER,
                "shared",
                false,
                0,
                new Action(1, Action.Type.ORDER_PERCENTAGE_DISCOUNT, bp(1_000)));

        Outcome outcome = evaluator.evaluate(List.of(item, order), workedExampleBasket(), clickContext(Set.of()), NOW);

        // The validator refuses this authoring; the evaluator must at least not
        // run the cross-scope contest the previous one papered over.
        assertThat(outcome.applied()).extracting(AppliedPromotion::code).containsExactlyInAnyOrder("ITEM", "ORDER");
    }

    // ----------------------------------------------------------------- markups

    @Test
    @DisplayName("a percentage item markup rounds half-up to whole som per unit, so unit price times quantity is whole")
    void itemMarkupRoundsPerUnit() {
        Promotion markup = markup(
                "SURGE", "m1", 0, new Action(1, Action.Type.ITEM_PERCENTAGE_MARKUP, bp(1_250)), product(MARGHERITA));
        // 12.5% of 45 001 = 5 625.125 -> 5 625 per unit; 2 units -> 11 250.
        Basket basket = new Basket(
                "UZS",
                List.of(new BasketLine("pizza", MARGHERITA_VARIANT, MARGHERITA, Set.of(), 2, 45_001L, 90_002L)),
                90_002L,
                0L);

        PromotionEvaluator.MarkupOutcome outcome =
                evaluator.evaluateMarkups(List.of(markup), basket, clickContext(Set.of()), NOW);

        assertThat(outcome.lineMarkupsMinor()).containsExactly(Map.entry("pizza", 11_250L));
    }

    @Test
    @DisplayName("within a markup group the higher priority wins, never the larger uplift; groups add")
    void markupGroupsPickByPriorityAndAddAcrossGroups() {
        Promotion small =
                markup("SMALL", "same", 10, new Action(1, Action.Type.ITEM_FIXED_MARKUP, ops("amountMinor", 1_000L)));
        Promotion large =
                markup("LARGE", "same", 5, new Action(1, Action.Type.ITEM_FIXED_MARKUP, ops("amountMinor", 5_000L)));
        Promotion other =
                markup("OTHER", "different", 0, new Action(1, Action.Type.ITEM_FIXED_MARKUP, ops("amountMinor", 300L)));
        Basket basket = workedExampleBasket();

        PromotionEvaluator.MarkupOutcome outcome =
                evaluator.evaluateMarkups(List.of(large, small, other), basket, clickContext(Set.of()), NOW);

        assertThat(outcome.applied())
                .extracting(PromotionEvaluator.AppliedMarkup::code)
                .containsExactlyInAnyOrder("SMALL", "OTHER");
        // 3 units in the cart: (1 000 + 300) each.
        assertThat(outcome.lineMarkupsMinor().get("pizza")).isEqualTo(2_600L);
        assertThat(outcome.lineMarkupsMinor().get("cola")).isEqualTo(1_300L);
        assertThat(outcome.trace().stream()
                        .filter(entry -> entry.verdict() == Verdict.LOST_TO)
                        .count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("an exclusive discount never suppresses a markup, and a markup is never compared with a discount")
    void exclusivityLeavesMarkupsAlone() {
        Promotion markup =
                markup("SURGE", "m", 0, new Action(1, Action.Type.ITEM_FIXED_MARKUP, ops("amountMinor", 500L)));
        Promotion exclusive = rule(
                "CODE",
                Promotion.Scope.ORDER,
                "code",
                true,
                0,
                new Action(1, Action.Type.ORDER_PERCENTAGE_DISCOUNT, bp(1_000)));

        PromotionContext context = clickContext(Set.of(exclusive.promotionId()));
        Outcome discounts = evaluator.evaluate(List.of(markup, exclusive), workedExampleBasket(), context, NOW);
        PromotionEvaluator.MarkupOutcome markups =
                evaluator.evaluateMarkups(List.of(markup, exclusive), workedExampleBasket(), context, NOW);

        assertThat(discounts.applied()).extracting(AppliedPromotion::code).containsExactly("CODE");
        assertThat(markups.applied())
                .extracting(PromotionEvaluator.AppliedMarkup::code)
                .containsExactly("SURGE");
    }

    // -------------------------------------------------------------- properties

    @Test
    @DisplayName("over generated carts the result ignores promotion order and never goes negative")
    void generatedCartsAreDeterministicAndNonNegative() {
        Random random = new Random(20261001);
        for (int round = 0; round < 250; round++) {
            Basket basket = randomBasket(random);
            List<Promotion> promotions = randomPromotions(random, basket);
            Set<UUID> presented = new java.util.HashSet<>();
            for (Promotion promotion : promotions) {
                if (promotion.requiresCoupon()) {
                    presented.add(promotion.promotionId());
                }
            }
            PromotionContext context = clickContext(presented);

            Outcome reference = evaluator.evaluate(promotions, basket, context, NOW);
            for (int shuffle = 0; shuffle < 4; shuffle++) {
                List<Promotion> shuffled = new ArrayList<>(promotions);
                Collections.shuffle(shuffled, new Random(round * 31L + shuffle));
                Outcome again = evaluator.evaluate(shuffled, basket, context, NOW);
                assertThat(again.lineDiscountsMinor())
                        .as("round %d lines", round)
                        .isEqualTo(reference.lineDiscountsMinor());
                assertThat(again.orderDiscountMinor())
                        .as("round %d order", round)
                        .isEqualTo(reference.orderDiscountMinor());
                assertThat(again.deliveryBenefitMinor())
                        .as("round %d delivery", round)
                        .isEqualTo(reference.deliveryBenefitMinor());
                assertThat(again.applied().stream()
                                .map(AppliedPromotion::promotionId)
                                .sorted()
                                .toList())
                        .as("round %d applied set", round)
                        .isEqualTo(reference.applied().stream()
                                .map(AppliedPromotion::promotionId)
                                .sorted()
                                .toList());
            }

            // No line, subtotal or fee goes negative.
            for (BasketLine line : basket.lines()) {
                assertThat(reference.lineDiscountsMinor().getOrDefault(line.lineId(), 0L))
                        .as("round %d line %s", round, line.lineId())
                        .isBetween(0L, line.lineGrossMinor());
            }
            long afterItems = basket.goodsSubtotalMinor()
                    - reference.lineDiscountsMinor().values().stream()
                            .mapToLong(Long::longValue)
                            .sum();
            assertThat(reference.orderDiscountMinor())
                    .as("round %d order", round)
                    .isBetween(0L, Math.max(0, afterItems));
            assertThat(reference.deliveryBenefitMinor())
                    .as("round %d delivery", round)
                    .isBetween(0L, basket.deliveryFeeMinor());

            // The per-promotion adjustments sum to the totals exactly.
            long itemShares = reference.applied().stream()
                    .flatMap(applied -> applied.perLineMinor().values().stream())
                    .mapToLong(Long::longValue)
                    .sum();
            assertThat(itemShares)
                    .as("round %d item shares", round)
                    .isEqualTo(reference.lineDiscountsMinor().values().stream()
                            .mapToLong(Long::longValue)
                            .sum());
            assertThat(reference.applied().stream()
                            .mapToLong(AppliedPromotion::orderMinor)
                            .sum())
                    .as("round %d order shares", round)
                    .isEqualTo(reference.orderDiscountMinor());
            assertThat(reference.applied().stream()
                            .mapToLong(AppliedPromotion::deliveryMinor)
                            .sum())
                    .as("round %d delivery shares", round)
                    .isEqualTo(reference.deliveryBenefitMinor());
        }
    }

    @Test
    @DisplayName(
            "the free units a FREE_ITEM promotion gives never exceed its quantity however the cart splits the gift")
    void generatedGiftSplitsNeverExceedTheBound() {
        Random random = new Random(7);
        for (int round = 0; round < 200; round++) {
            int bound = 1 + random.nextInt(4);
            int trigger = 1 + random.nextInt(3);
            String mode = random.nextBoolean() ? "ONCE" : "PER_MULTIPLE";
            int pizzas = 1 + random.nextInt(8);
            int[] colaLines = new int[1 + random.nextInt(5)];
            for (int i = 0; i < colaLines.length; i++) {
                colaLines[i] = 1 + random.nextInt(3);
            }
            Outcome outcome = evaluator.evaluate(
                    List.of(freeCola(bound, trigger, mode)),
                    pizzaAndColas(pizzas, colaLines),
                    clickContext(Set.of()),
                    NOW);
            long freeUnits = outcome.totalDiscountMinor() / 12_000L;
            int colaUnits = java.util.Arrays.stream(colaLines).sum();
            long expected = Math.min(bound, colaUnits);
            if ("PER_MULTIPLE".equals(mode)) {
                expected = Math.min(expected, pizzas / trigger);
            }
            assertThat(freeUnits)
                    .as("round %d %s", round, mode)
                    .isEqualTo(expected)
                    .isLessThanOrEqualTo(bound);
        }
    }

    private Basket randomBasket(Random random) {
        int lineCount = 1 + random.nextInt(4);
        List<BasketLine> lines = new ArrayList<>();
        long goods = 0;
        for (int i = 0; i < lineCount; i++) {
            int quantity = 1 + random.nextInt(4);
            long unit = 1_000L * (1 + random.nextInt(60));
            boolean pizza = random.nextBoolean();
            lines.add(new BasketLine(
                    "line-" + i,
                    pizza ? MARGHERITA_VARIANT : UUID.nameUUIDFromBytes(("v" + i).getBytes()),
                    pizza ? MARGHERITA : COLA,
                    pizza ? Set.of(PIZZA_CATEGORY, MENU_ROOT) : Set.of(MENU_ROOT),
                    quantity,
                    unit,
                    unit * quantity));
            goods += unit * quantity;
        }
        return new Basket("UZS", lines, goods, 1_000L * random.nextInt(20));
    }

    private List<Promotion> randomPromotions(Random random, Basket basket) {
        List<Promotion> promotions = new ArrayList<>();
        int count = 1 + random.nextInt(6);
        for (int i = 0; i < count; i++) {
            boolean exclusive = random.nextInt(5) == 0;
            int kind = random.nextInt(5);
            String group = "g" + random.nextInt(3) + (kind <= 1 ? "-item" : "-order");
            int priority = random.nextInt(4);
            Promotion promotion =
                    switch (kind) {
                        case 0 ->
                            rule(
                                    "i" + i,
                                    Promotion.Scope.ITEM,
                                    group,
                                    exclusive,
                                    priority,
                                    itemPercent(500 * (1 + random.nextInt(19))),
                                    category(MENU_ROOT));
                        case 1 ->
                            rule(
                                    "f" + i,
                                    Promotion.Scope.ITEM,
                                    group,
                                    exclusive,
                                    priority,
                                    itemFixed(1_000L * (1 + random.nextInt(40))),
                                    category(MENU_ROOT));
                        case 2 ->
                            rule(
                                    "o" + i,
                                    Promotion.Scope.ORDER,
                                    group,
                                    exclusive,
                                    priority,
                                    new Action(
                                            1,
                                            Action.Type.ORDER_PERCENTAGE_DISCOUNT,
                                            bp(500 * (1 + random.nextInt(19)))));
                        case 3 ->
                            rule(
                                    "x" + i,
                                    Promotion.Scope.ORDER,
                                    group,
                                    exclusive,
                                    priority,
                                    new Action(
                                            1,
                                            Action.Type.ORDER_FIXED_DISCOUNT,
                                            ops("amountMinor", 1_000L * (1 + random.nextInt(80)))));
                        default ->
                            rule(
                                    "d" + i,
                                    Promotion.Scope.DELIVERY,
                                    group,
                                    exclusive,
                                    priority,
                                    new Action(1, Action.Type.FREE_DELIVERY, Operands.empty()));
                    };
            promotions.add(random.nextInt(6) == 0 ? asCoupon(promotion) : promotion);
        }
        return promotions;
    }

    private Promotion asCoupon(Promotion promotion) {
        return new Promotion(
                promotion.promotionId(),
                promotion.tenantId(),
                promotion.brandId(),
                promotion.code(),
                promotion.scope(),
                promotion.stackingGroup(),
                true,
                promotion.priority(),
                true,
                promotion.maximumDiscountMinor(),
                promotion.currency(),
                promotion.validFrom(),
                promotion.validUntil(),
                promotion.definitionVersion(),
                promotion.conditions(),
                promotion.actions());
    }

    // --------------------------------------------------------------- fixtures

    private static TraceEntry traceOf(Outcome outcome, UUID promotionId) {
        return outcome.trace().stream()
                .filter(entry -> entry.promotionId().equals(promotionId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no trace entry for " + promotionId));
    }

    private Promotion rule(
            String code,
            Promotion.Scope scope,
            String group,
            boolean exclusive,
            int priority,
            Action action,
            Condition... conditions) {
        return new Promotion(
                UUID.nameUUIDFromBytes((code + scope + group).getBytes()),
                TENANT,
                BRAND,
                code,
                scope,
                group,
                exclusive,
                priority,
                exclusive && "code".equals(group),
                null,
                "UZS",
                NOW.minusSeconds(3_600),
                null,
                1,
                List.of(conditions),
                List.of(action));
    }

    private Promotion markup(String code, String group, int priority, Action action, Condition... conditions) {
        return new Promotion(
                UUID.nameUUIDFromBytes((code + group).getBytes()),
                TENANT,
                BRAND,
                code,
                Promotion.Kind.MARKUP,
                Promotion.Scope.ITEM,
                group,
                false,
                priority,
                false,
                null,
                "UZS",
                NOW.minusSeconds(3_600),
                null,
                1,
                null,
                null,
                Promotion.LoyaltyAccrual.ACCRUE,
                Promotion.LoyaltyRedemption.ALLOW,
                List.of(conditions),
                List.of(action));
    }

    private static Operands bp(long basisPoints) {
        return ops("basisPoints", basisPoints);
    }

    private static Operands ops(Object... pairs) {
        Map<String, Object> values = new java.util.HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }
        return new Operands(values);
    }

    private static Action itemPercent(long basisPoints) {
        return new Action(1, Action.Type.ITEM_PERCENTAGE_DISCOUNT, bp(basisPoints));
    }

    private static Action itemFixed(long amountMinor) {
        return new Action(1, Action.Type.ITEM_FIXED_DISCOUNT, ops("amountMinor", amountMinor));
    }

    private static Condition product(UUID productId) {
        return new Condition(1, Condition.Type.PRODUCT, ops("productIds", List.of(productId.toString())));
    }

    private static Condition category(UUID categoryId) {
        return new Condition(1, Condition.Type.CATEGORY, ops("categoryIds", List.of(categoryId.toString())));
    }
}
