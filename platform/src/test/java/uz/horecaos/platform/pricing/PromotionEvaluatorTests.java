package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.pricing.application.PromotionEvaluator;
import uz.horecaos.platform.pricing.application.PromotionEvaluator.Basket;
import uz.horecaos.platform.pricing.application.PromotionEvaluator.BasketLine;
import uz.horecaos.platform.pricing.application.PromotionEvaluator.Outcome;
import uz.horecaos.platform.pricing.application.PromotionEvaluator.PromotionContext;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.Promotion.Action;
import uz.horecaos.platform.pricing.domain.Promotion.Condition;
import uz.horecaos.platform.pricing.domain.Promotion.Operands;

/**
 * ADR 0018 stages 3 and 4 (promotions).
 *
 * <p>The stacking rule decides real money, so most of what is below is about
 * which promotion is <em>not</em> applied. A test that only checks a discount
 * appeared would pass just as happily against an engine that applied every
 * matching promotion at once, which is the specific outcome this design refuses.
 */
class PromotionEvaluatorTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID OSH = UUID.randomUUID();
    private static final UUID OSH_VARIANT = UUID.randomUUID();
    private static final UUID SOMSA = UUID.randomUUID();
    private static final UUID SOMSA_VARIANT = UUID.randomUUID();
    private static final UUID MAINS = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

    private final PromotionEvaluator evaluator = new PromotionEvaluator();

    @Test
    @DisplayName("a ten percent item discount comes off the matching line only")
    void itemPercentageAppliesToMatchingLinesOnly() {
        Outcome outcome = evaluator.evaluate(
                List.of(itemPercentOffProduct("OSH10", "SEASONAL", OSH, 1_000)), basketWithBoth(), context(), NOW);

        assertThat(outcome.lineDiscountsMinor())
                .as("only the osh line is discounted, and by exactly a tenth")
                .containsExactly(Map.entry("line-osh", 4_000L));
        assertThat(outcome.lineDiscountsMinor())
                .as("the somsa line is untouched")
                .doesNotContainKey("line-somsa");
    }

    @Test
    @DisplayName("within one stacking group only the better promotion applies")
    void bestOneWinsWithinAStackingGroup() {
        Promotion weaker = itemPercentOffProduct("OSH10", "SEASONAL", OSH, 1_000);
        Promotion stronger = itemPercentOffProduct("OSH25", "SEASONAL", OSH, 2_500);

        Outcome outcome = evaluator.evaluate(List.of(weaker, stronger), basketWithBoth(), context(), NOW);

        assertThat(outcome.applied()).as("one promotion applies, not both").hasSize(1);
        assertThat(outcome.applied().get(0).code()).isEqualTo("OSH25");
        assertThat(outcome.lineDiscountsMinor().get("line-osh"))
                .as("25% of 40 000, and not 35% of it")
                .isEqualTo(10_000L);
    }

    @Test
    @DisplayName("promotions in different stacking groups combine")
    void differentStackingGroupsCombine() {
        Promotion seasonal = itemPercentOffProduct("OSH10", "SEASONAL", OSH, 1_000);
        Promotion loyalty = itemPercentOffProduct("LOYAL5", "LOYALTY", OSH, 500);

        Outcome outcome = evaluator.evaluate(List.of(seasonal, loyalty), basketWithBoth(), context(), NOW);

        assertThat(outcome.applied()).hasSize(2);
        assertThat(outcome.lineDiscountsMinor().get("line-osh"))
                .as("4 000 from one group plus 2 000 from the other")
                .isEqualTo(6_000L);
    }

    @Test
    @DisplayName("an exclusive promotion suppresses every other, even a better one")
    void exclusiveSuppressesEverythingElse() {
        Promotion exclusive = new Promotion(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                "LAUNCH",
                Promotion.Scope.ITEM,
                "LAUNCH",
                true,
                0,
                false,
                null,
                "UZS",
                NOW.minusSeconds(60),
                null,
                1,
                List.of(productIs(OSH)),
                List.of(percentAction(1_000)));
        Promotion betterButNotExclusive = itemPercentOffProduct("OSH50", "SEASONAL", OSH, 5_000);

        Outcome outcome =
                evaluator.evaluate(List.of(exclusive, betterButNotExclusive), basketWithBoth(), context(), NOW);

        assertThat(outcome.applied())
                .as("exclusivity beats a larger discount in another group")
                .extracting(PromotionEvaluator.AppliedPromotion::code)
                .containsExactly("LAUNCH");
        assertThat(outcome.lineDiscountsMinor().get("line-osh")).isEqualTo(4_000L);
    }

    @Test
    @DisplayName("an order discount is computed after item discounts, not before")
    void orderDiscountRunsOnTheReducedSubtotal() {
        Promotion item = itemPercentOffProduct("OSH50", "SEASONAL", OSH, 5_000);
        Promotion order = orderPercent("ALL10", "ORDERWIDE", 1_000);

        Outcome outcome = evaluator.evaluate(List.of(item, order), basketWithBoth(), context(), NOW);

        // 60 000 basket, 20 000 off the osh line, so the order discount is a
        // tenth of 40 000 and not a tenth of 60 000.
        assertThat(outcome.lineDiscountsMinor().get("line-osh")).isEqualTo(20_000L);
        assertThat(outcome.orderDiscountMinor())
                .as("10% of what stage 3 left, which is 4 000 rather than 6 000")
                .isEqualTo(4_000L);
    }

    @Test
    @DisplayName("a subtotal threshold is tested against the reduced subtotal too")
    void thresholdSeesTheReducedSubtotal() {
        Promotion item = itemPercentOffProduct("OSH50", "SEASONAL", OSH, 5_000);
        Promotion needsFifty = new Promotion(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                "OVER50",
                Promotion.Scope.ORDER,
                "ORDERWIDE",
                false,
                0,
                false,
                null,
                "UZS",
                NOW.minusSeconds(60),
                null,
                1,
                List.of(new Condition(
                        1, Condition.Type.SUBTOTAL_AT_LEAST, new Operands(Map.of("amountMinor", 50_000L)))),
                List.of(new Action(1, Action.Type.ORDER_FIXED_DISCOUNT, new Operands(Map.of("amountMinor", 5_000L)))));

        Outcome outcome = evaluator.evaluate(List.of(item, needsFifty), basketWithBoth(), context(), NOW);

        // The basket starts at 60 000 and would qualify; after 20 000 comes off
        // it is 40 000 and no longer does.
        assertThat(outcome.orderDiscountMinor())
                .as("a basket that fell under the threshold does not get the offer")
                .isZero();
        assertThat(outcome.applied())
                .extracting(PromotionEvaluator.AppliedPromotion::code)
                .containsExactly("OSH50");
    }

    @Test
    @DisplayName("a coupon promotion does not apply unless its code was presented")
    void couponPromotionsNeedTheirCode() {
        Promotion coupon = new Promotion(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                "SUMMER20",
                Promotion.Scope.ORDER,
                "COUPON",
                false,
                0,
                true,
                null,
                "UZS",
                NOW.minusSeconds(60),
                null,
                1,
                List.of(),
                List.of(new Action(
                        1, Action.Type.ORDER_PERCENTAGE_DISCOUNT, new Operands(Map.of("basisPoints", 2_000L)))));

        Outcome withoutCode = evaluator.evaluate(List.of(coupon), basketWithBoth(), context(), NOW);
        assertThat(withoutCode.applied())
                .as("an unpresented coupon is not a discount every customer receives")
                .isEmpty();

        PromotionContext presented = new PromotionContext(
                "STOREFRONT", LOCATION, "DELIVERY", false, Set.of(), Set.of(coupon.promotionId()), 5, 12 * 60);
        Outcome withCode = evaluator.evaluate(List.of(coupon), basketWithBoth(), presented, NOW);
        assertThat(withCode.orderDiscountMinor()).isEqualTo(12_000L);
    }

    @Test
    @DisplayName("a promotion outside its window does not apply")
    void aPromotionOutsideItsWindowDoesNotApply() {
        Promotion expired = new Promotion(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                "OLD",
                Promotion.Scope.ITEM,
                "SEASONAL",
                false,
                0,
                false,
                null,
                "UZS",
                NOW.minusSeconds(7200),
                NOW.minusSeconds(3600),
                1,
                List.of(productIs(OSH)),
                List.of(percentAction(5_000)));

        assertThat(evaluator
                        .evaluate(List.of(expired), basketWithBoth(), context(), NOW)
                        .applied())
                .isEmpty();
    }

    @Test
    @DisplayName("a maximum discount caps the benefit and the parts still sum to it")
    void theCapHoldsAndThePartsReconcile() {
        Promotion capped = new Promotion(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                "CAPPED",
                Promotion.Scope.ITEM,
                "SEASONAL",
                false,
                0,
                false,
                3_000L,
                "UZS",
                NOW.minusSeconds(60),
                null,
                1,
                List.of(),
                List.of(percentAction(5_000)));

        Outcome outcome = evaluator.evaluate(List.of(capped), basketWithBoth(), context(), NOW);

        // Half of the whole 60 000 basket is 30 000; the cap is 3 000.
        long summed = outcome.lineDiscountsMinor().values().stream()
                .mapToLong(Long::longValue)
                .sum();
        assertThat(summed)
                .as("the recorded parts sum to the cap, so the quote reconciles")
                .isEqualTo(3_000L);
    }

    @Test
    @DisplayName("the cap is compared before selection, so a capped giant loses to a flat offer")
    void selectionComparesWhatTheCustomerActuallyReceives() {
        Promotion cappedGiant = new Promotion(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                "BIGBUTCAPPED",
                Promotion.Scope.ITEM,
                "SEASONAL",
                false,
                0,
                false,
                5_000L,
                "UZS",
                NOW.minusSeconds(60),
                null,
                1,
                List.of(productIs(OSH)),
                List.of(percentAction(9_000)));
        Promotion flat = new Promotion(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                "FLAT10K",
                Promotion.Scope.ITEM,
                "SEASONAL",
                false,
                0,
                false,
                null,
                "UZS",
                NOW.minusSeconds(60),
                null,
                1,
                List.of(productIs(OSH)),
                List.of(new Action(1, Action.Type.ITEM_FIXED_DISCOUNT, new Operands(Map.of("amountMinor", 10_000L)))));

        Outcome outcome = evaluator.evaluate(List.of(cappedGiant, flat), basketWithBoth(), context(), NOW);

        assertThat(outcome.applied())
                .as("36 000 capped to 5 000 is worth less than a flat 10 000")
                .extracting(PromotionEvaluator.AppliedPromotion::code)
                .containsExactly("FLAT10K");
    }

    @Test
    @DisplayName("a fixed discount larger than the line cannot take it below zero")
    void aFixedDiscountCannotGoNegative() {
        Promotion huge = new Promotion(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                "HUGE",
                Promotion.Scope.ITEM,
                "SEASONAL",
                false,
                0,
                false,
                null,
                "UZS",
                NOW.minusSeconds(60),
                null,
                1,
                List.of(productIs(SOMSA)),
                List.of(new Action(1, Action.Type.ITEM_FIXED_DISCOUNT, new Operands(Map.of("amountMinor", 999_999L)))));

        Outcome outcome = evaluator.evaluate(List.of(huge), basketWithBoth(), context(), NOW);

        assertThat(outcome.lineDiscountsMinor().get("line-somsa"))
                .as("the discount stops at the value of the line")
                .isEqualTo(20_000L);
    }

    @Test
    @DisplayName("two order promotions in different groups cannot take the basket below zero")
    void combinedOrderDiscountsAreCappedAtTheBasket() {
        Promotion first = orderPercent("HALF1", "GROUP_A", 5_000);
        Promotion second = orderPercent("HALF2", "GROUP_B", 8_000);

        Outcome outcome = evaluator.evaluate(List.of(first, second), basketWithBoth(), context(), NOW);

        assertThat(outcome.orderDiscountMinor())
                .as("50% plus 80% is 130%, and the basket is only worth 60 000")
                .isEqualTo(60_000L);
    }

    @Test
    @DisplayName("a time-of-day window that wraps past midnight includes the small hours")
    void aWrappingWindowIncludesTheSmallHours() {
        Promotion lateNight = new Promotion(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                "NIGHT",
                Promotion.Scope.ORDER,
                "NIGHT",
                false,
                0,
                false,
                null,
                "UZS",
                NOW.minusSeconds(60),
                null,
                1,
                List.of(new Condition(
                        1,
                        Condition.Type.TIME_OF_DAY,
                        new Operands(Map.of("fromMinuteOfDay", 22 * 60, "toMinuteOfDay", 2 * 60)))),
                List.of(new Action(1, Action.Type.ORDER_FIXED_DISCOUNT, new Operands(Map.of("amountMinor", 5_000L)))));

        PromotionContext oneInTheMorning =
                new PromotionContext("STOREFRONT", LOCATION, "DELIVERY", false, Set.of(), Set.of(), 5, 60);
        assertThat(evaluator
                        .evaluate(List.of(lateNight), basketWithBoth(), oneInTheMorning, NOW)
                        .orderDiscountMinor())
                .as("01:00 falls inside a 22:00-02:00 window")
                .isEqualTo(5_000L);

        PromotionContext midday =
                new PromotionContext("STOREFRONT", LOCATION, "DELIVERY", false, Set.of(), Set.of(), 5, 12 * 60);
        assertThat(evaluator
                        .evaluate(List.of(lateNight), basketWithBoth(), midday, NOW)
                        .orderDiscountMinor())
                .as("midday does not")
                .isZero();
    }

    @Test
    @DisplayName("two promotions worth the same amount resolve the same way every time")
    void tiesAreBrokenDeterministically() {
        Promotion left = itemPercentOffProduct("A", "SEASONAL", OSH, 1_000);
        Promotion right = itemPercentOffProduct("B", "SEASONAL", OSH, 1_000);

        String forwards = evaluator
                .evaluate(List.of(left, right), basketWithBoth(), context(), NOW)
                .applied()
                .get(0)
                .code();
        String backwards = evaluator
                .evaluate(List.of(right, left), basketWithBoth(), context(), NOW)
                .applied()
                .get(0)
                .code();

        assertThat(forwards).as("row order must never decide a discount").isEqualTo(backwards);
    }

    @Test
    @DisplayName("a promotion naming a product the cart does not contain does not apply")
    void anUnmatchedProductDoesNotApply() {
        Promotion other = itemPercentOffProduct("OTHER", "SEASONAL", UUID.randomUUID(), 5_000);

        assertThat(evaluator
                        .evaluate(List.of(other), basketWithBoth(), context(), NOW)
                        .applied())
                .isEmpty();
    }

    @Test
    @DisplayName("free delivery is worth the fee and never more")
    void freeDeliveryIsCappedAtTheFee() {
        Promotion free = new Promotion(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                "FREEDEL",
                Promotion.Scope.DELIVERY,
                "DELIVERY",
                false,
                0,
                false,
                null,
                "UZS",
                NOW.minusSeconds(60),
                null,
                1,
                List.of(),
                List.of(new Action(1, Action.Type.FREE_DELIVERY, Operands.empty())));

        Outcome outcome = evaluator.evaluate(List.of(free), basketWithBoth(), context(), NOW);

        assertThat(outcome.deliveryBenefitMinor()).isEqualTo(10_000L);
        assertThat(outcome.lineDiscountsMinor())
                .as("a delivery benefit is not a discount on the goods")
                .isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    /** 40 000 of osh and 20 000 of somsa, with a 10 000 delivery fee. */
    private Basket basketWithBoth() {
        return new Basket(
                "UZS",
                List.of(
                        new BasketLine("line-osh", OSH_VARIANT, OSH, Set.of(MAINS), 1, 40_000L, 40_000L),
                        new BasketLine("line-somsa", SOMSA_VARIANT, SOMSA, Set.of(MAINS), 2, 10_000L, 20_000L)),
                60_000L,
                10_000L);
    }

    private PromotionContext context() {
        return new PromotionContext("STOREFRONT", LOCATION, "DELIVERY", false, Set.of(), Set.of(), 5, 12 * 60);
    }

    private Promotion itemPercentOffProduct(String code, String group, UUID productId, int basisPoints) {
        return new Promotion(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                code,
                Promotion.Scope.ITEM,
                group,
                false,
                0,
                false,
                null,
                "UZS",
                NOW.minusSeconds(60),
                null,
                1,
                List.of(productIs(productId)),
                List.of(percentAction(basisPoints)));
    }

    private Promotion orderPercent(String code, String group, int basisPoints) {
        return new Promotion(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                code,
                Promotion.Scope.ORDER,
                group,
                false,
                0,
                false,
                null,
                "UZS",
                NOW.minusSeconds(60),
                null,
                1,
                List.of(),
                List.of(new Action(1, Action.Type.ORDER_PERCENTAGE_DISCOUNT, new Operands(Map.of("basisPoints", (long)
                        basisPoints)))));
    }

    private Condition productIs(UUID productId) {
        return new Condition(
                1, Condition.Type.PRODUCT, new Operands(Map.of("productIds", List.of(productId.toString()))));
    }

    private Action percentAction(int basisPoints) {
        return new Action(
                1, Action.Type.ITEM_PERCENTAGE_DISCOUNT, new Operands(Map.of("basisPoints", (long) basisPoints)));
    }
}
