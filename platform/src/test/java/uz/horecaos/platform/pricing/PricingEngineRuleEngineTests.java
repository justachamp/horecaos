package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeOutcome;
import uz.horecaos.platform.fulfillment.api.ResolvedDeliveryCharge;
import uz.horecaos.platform.pricing.application.MenuMembershipLookup;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.PricingEngine.PricingInputs;
import uz.horecaos.platform.pricing.application.PricingEngine.PromotionInputs;
import uz.horecaos.platform.pricing.application.PricingEngine.TaxMode;
import uz.horecaos.platform.pricing.application.PromotionEvaluator.PromotionContext;
import uz.horecaos.platform.pricing.application.PromotionEvaluator.Verdict;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.Promotion.Action;
import uz.horecaos.platform.pricing.domain.Promotion.Condition;
import uz.horecaos.platform.pricing.domain.Promotion.Operands;
import uz.horecaos.platform.pricing.domain.Quote;
import uz.horecaos.platform.pricing.domain.QuoteRequest;

/**
 * ADR 0140 through the whole pipeline: the worked example as a golden fixture for
 * {@code CALCULATION_VERSION} 3, the markup stage, the loyalty flags and the
 * decision trace the simulator renders.
 */
class PricingEngineRuleEngineTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID PUBLICATION = UUID.randomUUID();
    private static final UUID PRICE_BOOK = UUID.randomUUID();
    private static final UUID TAX_PROFILE = UUID.randomUUID();
    private static final UUID ZONE = UUID.randomUUID();
    private static final UUID TARIFF = UUID.randomUUID();
    private static final UUID MARGHERITA_VARIANT = UUID.randomUUID();
    private static final UUID MARGHERITA = UUID.randomUUID();
    private static final UUID COLA_VARIANT = UUID.randomUUID();
    private static final UUID COLA = UUID.randomUUID();
    private static final UUID PIZZA_CATEGORY = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-01T07:30:00Z");

    private final PricingEngine engine = new PricingEngine();

    /**
     * 2 x Margherita at 45 000 (Pizza) and 1 x Cola at 12 000, delivered, fee 15 000,
     * paid by Click: goods 102 000. Under version 3 the combination is worth
     * 10 000 + 4 600 + 15 000 and the typed code SAVE10 (10 200 alone) steps aside.
     *
     * <p>Tax is 12% inclusive on the discounted goods: 87 400 x 1 200 / 11 200 =
     * 9 364. Subtotal is gross of the discount: 102 000 - 9 364 = 92 636. The
     * delivery fee is 15 000 less the free-delivery benefit, so 0. The identity
     * the schema enforces holds: 92 636 + 9 364 + 0 - 14 600 = 87 400.
     */
    @Test
    @DisplayName("golden fixture: the worked example prices to 87 400 under calculation version 3")
    void theWorkedExamplePricesToTheDocumentedTotal() {
        List<Promotion> promotions = workedExamplePromotions();
        UUID saveId = promotions.get(4).promotionId();

        PricingEngine.Result result =
                engine.price(workedExampleRequest(), inputs(promotions, Set.of(saveId), "CLICK"), NOW);

        assertThat(result.discount().minor())
                .as("10 000 on the pizza line and 4 600 off the order")
                .isEqualTo(14_600L);
        assertThat(result.tax().minor()).isEqualTo(9_364L);
        assertThat(result.subtotal().minor()).isEqualTo(92_636L);
        assertThat(result.fees().minor())
                .as("the delivery fee is waived by the P4 benefit")
                .isZero();
        assertThat(result.total().minor()).isEqualTo(87_400L);
        assertThat(result.total().minor())
                .as("the identity ck_order_total_reconciles enforces")
                .isEqualTo(result.subtotal().minor()
                        + result.tax().minor()
                        + result.fees().minor()
                        - result.discount().minor());

        assertThat(result.adjustments())
                .filteredOn(adjustment -> "PROMOTION".equals(adjustment.sourceType()))
                .as("one adjustment per applied promotion, each naming its id and definition version")
                .allSatisfy(adjustment -> {
                    assertThat(adjustment.sourceId()).isNotNull();
                    assertThat(adjustment.sourceVersion()).isEqualTo(1);
                })
                .extracting(Quote.Adjustment::type)
                .containsExactlyInAnyOrder(
                        Quote.Adjustment.Type.ITEM_DISCOUNT,
                        Quote.Adjustment.Type.ORDER_DISCOUNT,
                        Quote.Adjustment.Type.DELIVERY_FEE_BENEFIT);

        assertThat(result.promotionTrace())
                .filteredOn(entry -> entry.promotionId().equals(saveId))
                .singleElement()
                .satisfies(entry -> assertThat(entry.verdict()).isEqualTo(Verdict.LOST_TO));
    }

    @Test
    @DisplayName("the same cart without the typed code prices identically: the code did not cost the customer anything")
    void typingASmallerCodeChangesNothing() {
        List<Promotion> promotions = workedExamplePromotions();
        UUID saveId = promotions.get(4).promotionId();

        PricingEngine.Result withCode =
                engine.price(workedExampleRequest(), inputs(promotions, Set.of(saveId), "CLICK"), NOW);
        PricingEngine.Result withoutCode =
                engine.price(workedExampleRequest(), inputs(promotions, Set.of(), "CLICK"), NOW);

        assertThat(withCode.total()).isEqualTo(withoutCode.total());
        assertThat(withCode.contextHash())
                .as("the presented code is still a term in the hash")
                .isNotEqualTo(withoutCode.contextHash());
    }

    @Test
    @DisplayName(
            "paying in cash drops the payment-method promotion, and the total goes up by exactly what it was worth")
    void switchingPaymentMethodChangesTheTotal() {
        List<Promotion> promotions = workedExamplePromotions();

        PricingEngine.Result click = engine.price(workedExampleRequest(), inputs(promotions, Set.of(), "CLICK"), NOW);
        PricingEngine.Result cash = engine.price(workedExampleRequest(), inputs(promotions, Set.of(), "CASH"), NOW);

        // P3 gave 5% of the reduced 92 000, 4 600. Cash loses it.
        assertThat(cash.discount().minor()).isEqualTo(click.discount().minor() - 4_600L);
        assertThat(cash.contextHash()).isNotEqualTo(click.contextHash());
    }

    // ------------------------------------------------------------------ markup

    @Test
    @DisplayName(
            "a markup is a positive adjustment before any discount, is part of the subtotal, and never touches discount")
    void anItemMarkupRaisesTheLineBeforeDiscounts() {
        Promotion surge = new Promotion(
                UUID.nameUUIDFromBytes("surge".getBytes()),
                TENANT,
                BRAND,
                "SURGE",
                Promotion.Kind.MARKUP,
                Promotion.Scope.ITEM,
                "markup",
                false,
                0,
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
                List.of(new Condition(1, Condition.Type.PRODUCT, ops("productIds", List.of(MARGHERITA.toString())))),
                List.of(new Action(1, Action.Type.ITEM_PERCENTAGE_MARKUP, ops("basisPoints", 1_000L))));
        Promotion tenOff = orderPromotion(
                "TENOFF", "order", false, ops("basisPoints", 1_000L), Action.Type.ORDER_PERCENTAGE_DISCOUNT);

        PricingEngine.Result result =
                engine.price(workedExampleRequest(), inputs(List.of(surge, tenOff), Set.of(), "CLICK"), NOW);

        // +10% of 45 000 = 4 500 per pizza, two pizzas: 9 000. Goods become 111 000.
        assertThat(result.adjustments())
                .filteredOn(adjustment -> adjustment.type() == Quote.Adjustment.Type.ITEM_MARKUP)
                .singleElement()
                .satisfies(adjustment -> {
                    assertThat(adjustment.amount().minor()).isEqualTo(9_000L);
                    assertThat(adjustment.lineId()).isEqualTo("pizza");
                    assertThat(adjustment.sourceId()).isEqualTo(surge.promotionId());
                });
        Quote.QuoteLine pizza = result.lines().stream()
                .filter(line -> "pizza".equals(line.lineId()))
                .findFirst()
                .orElseThrow();
        assertThat(pizza.unitAmount().minor())
                .as("the unit price carries the uplift")
                .isEqualTo(49_500L);
        assertThat(pizza.baseAmount().minor()).isEqualTo(99_000L);

        // 10% off the marked-up 111 000 = 11 100: the discount sees the marked-up price.
        assertThat(result.discount().minor()).isEqualTo(11_100L);
        assertThat(result.discount().minor())
                .as("a markup never makes the discount negative")
                .isNotNegative();
        assertThat(result.total().minor())
                .isEqualTo(result.subtotal().minor()
                        + result.tax().minor()
                        + result.fees().minor()
                        - result.discount().minor());
        assertThat(result.total().minor())
                .as("111 000 - 11 100 + the 15 000 fee")
                .isEqualTo(114_900L);
    }

    @Test
    @DisplayName("the loyalty flags take the most restrictive value across the applied promotions")
    void loyaltyFlagsAreTheMostRestrictive() {
        Promotion suppress =
                loyaltyPromotion("NOACCRUE", "a", Promotion.LoyaltyAccrual.SUPPRESS, Promotion.LoyaltyRedemption.ALLOW);
        Promotion block =
                loyaltyPromotion("NOSPEND", "b", Promotion.LoyaltyAccrual.ACCRUE, Promotion.LoyaltyRedemption.BLOCK);
        Promotion plain =
                orderPromotion("PLAIN", "c", false, ops("amountMinor", 500L), Action.Type.ORDER_FIXED_DISCOUNT);

        PricingEngine.Result plainOnly =
                engine.price(workedExampleRequest(), inputs(List.of(plain), Set.of(), "CLICK"), NOW);
        PricingEngine.Result both =
                engine.price(workedExampleRequest(), inputs(List.of(plain, suppress, block), Set.of(), "CLICK"), NOW);
        PricingEngine.Result suppressOnly =
                engine.price(workedExampleRequest(), inputs(List.of(plain, suppress), Set.of(), "CLICK"), NOW);

        assertThat(plainOnly.loyaltyAccrualAllowed()).isTrue();
        assertThat(plainOnly.loyaltyRedemptionAllowed()).isTrue();
        assertThat(suppressOnly.loyaltyAccrualAllowed()).isFalse();
        assertThat(suppressOnly.loyaltyRedemptionAllowed()).isTrue();
        assertThat(both.loyaltyAccrualAllowed()).isFalse();
        assertThat(both.loyaltyRedemptionAllowed()).isFalse();
    }

    @Test
    @DisplayName("a promotion that did not apply is not a flag: only applied promotions restrict loyalty")
    void aLosingPromotionDoesNotRestrictLoyalty() {
        Promotion strongPlain =
                orderPromotion("STRONG", "same", false, ops("amountMinor", 9_000L), Action.Type.ORDER_FIXED_DISCOUNT);
        Promotion weakSuppress = new Promotion(
                UUID.nameUUIDFromBytes("weakSuppress".getBytes()),
                TENANT,
                BRAND,
                "WEAK",
                Promotion.Kind.DISCOUNT,
                Promotion.Scope.ORDER,
                "same",
                false,
                0,
                false,
                null,
                "UZS",
                NOW.minusSeconds(3_600),
                null,
                1,
                null,
                null,
                Promotion.LoyaltyAccrual.SUPPRESS,
                Promotion.LoyaltyRedemption.BLOCK,
                List.of(),
                List.of(new Action(1, Action.Type.ORDER_FIXED_DISCOUNT, ops("amountMinor", 100L))));

        PricingEngine.Result result = engine.price(
                workedExampleRequest(), inputs(List.of(strongPlain, weakSuppress), Set.of(), "CLICK"), NOW);

        assertThat(result.loyaltyAccrualAllowed()).isTrue();
        assertThat(result.loyaltyRedemptionAllowed()).isTrue();
    }

    // ----------------------------------------------------------------- fixtures

    private List<Promotion> workedExamplePromotions() {
        Promotion p1 = promotion(
                "P1",
                Promotion.Scope.ITEM,
                "menu",
                false,
                10,
                new Action(1, Action.Type.ITEM_PERCENTAGE_DISCOUNT, ops("basisPoints", 1_000L)),
                new Condition(1, Condition.Type.CATEGORY, ops("categoryIds", List.of(PIZZA_CATEGORY.toString()))));
        Promotion p2 = promotion(
                "P2",
                Promotion.Scope.ITEM,
                "menu",
                false,
                20,
                new Action(1, Action.Type.ITEM_FIXED_DISCOUNT, ops("amountMinor", 5_000L)),
                new Condition(1, Condition.Type.CATEGORY, ops("categoryIds", List.of(PIZZA_CATEGORY.toString()))));
        Promotion p3 = promotion(
                "P3",
                Promotion.Scope.ORDER,
                "payment",
                false,
                0,
                new Action(1, Action.Type.ORDER_PERCENTAGE_DISCOUNT, ops("basisPoints", 500L)),
                new Condition(1, Condition.Type.PAYMENT_METHOD, ops("paymentMethodCodes", List.of("CLICK"))));
        Promotion p4 = promotion(
                "P4",
                Promotion.Scope.DELIVERY,
                "delivery",
                false,
                0,
                new Action(1, Action.Type.FREE_DELIVERY, Operands.empty()),
                new Condition(1, Condition.Type.SUBTOTAL_AT_LEAST, ops("amountMinor", 90_000L)));
        Promotion save10 = promotion(
                "SAVE10",
                Promotion.Scope.ORDER,
                "code",
                true,
                0,
                new Action(1, Action.Type.ORDER_PERCENTAGE_DISCOUNT, ops("basisPoints", 1_000L)));
        return List.of(p1, p2, p3, p4, save10);
    }

    private Promotion promotion(
            String code,
            Promotion.Scope scope,
            String group,
            boolean exclusive,
            int priority,
            Action action,
            Condition... conditions) {
        return new Promotion(
                UUID.nameUUIDFromBytes(code.getBytes()),
                TENANT,
                BRAND,
                code,
                scope,
                group,
                exclusive,
                priority,
                exclusive,
                null,
                "UZS",
                NOW.minusSeconds(3_600),
                null,
                1,
                List.of(conditions),
                List.of(action));
    }

    private Promotion orderPromotion(
            String code, String group, boolean exclusive, Operands operands, Action.Type type) {
        return promotion(code, Promotion.Scope.ORDER, group, exclusive, 0, new Action(1, type, operands));
    }

    private Promotion loyaltyPromotion(
            String code, String group, Promotion.LoyaltyAccrual accrual, Promotion.LoyaltyRedemption redemption) {
        return new Promotion(
                UUID.nameUUIDFromBytes(code.getBytes()),
                TENANT,
                BRAND,
                code,
                Promotion.Kind.DISCOUNT,
                Promotion.Scope.ORDER,
                group,
                false,
                0,
                false,
                null,
                "UZS",
                NOW.minusSeconds(3_600),
                null,
                1,
                null,
                null,
                accrual,
                redemption,
                List.of(),
                List.of(new Action(1, Action.Type.ORDER_FIXED_DISCOUNT, ops("amountMinor", 100L))));
    }

    private static QuoteRequest workedExampleRequest() {
        return new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                UUID.randomUUID(),
                "STOREFRONT",
                List.of(
                        new QuoteRequest.Line("pizza", MARGHERITA_VARIANT, 2, List.of()),
                        new QuoteRequest.Line("cola", COLA_VARIANT, 1, List.of())),
                null);
    }

    private PricingInputs inputs(List<Promotion> promotions, Set<UUID> presented, String paymentMethod) {
        PromotionContext context = new PromotionContext(
                "STOREFRONT",
                "WEB",
                LOCATION,
                "DELIVERY",
                paymentMethod,
                ZONE,
                false,
                null,
                null,
                Set.of(),
                presented,
                NOW,
                4,
                10 * 60 + 30,
                Set.of(),
                Set.of());
        return new PricingInputs(
                "UZS",
                PUBLICATION,
                PRICE_BOOK,
                1,
                TAX_PROFILE,
                1,
                1_200,
                TaxMode.INCLUSIVE,
                Map.of(MARGHERITA_VARIANT, 45_000L, COLA_VARIANT, 12_000L),
                Map.of(),
                Map.of(),
                new ResolvedDeliveryCharge(
                        DeliveryFeeOutcome.RESOLVED,
                        "UZS",
                        15_000L,
                        0L,
                        null,
                        null,
                        ZONE,
                        1,
                        TARIFF,
                        1,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of()),
                new PromotionInputs(
                        promotions,
                        context,
                        Map.of(
                                MARGHERITA_VARIANT,
                                new MenuMembershipLookup.Membership(MARGHERITA, Set.of(PIZZA_CATEGORY)),
                                COLA_VARIANT,
                                new MenuMembershipLookup.Membership(COLA, Set.of()))));
    }

    private static Operands ops(Object... pairs) {
        Map<String, Object> values = new java.util.HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put((String) pairs[i], pairs[i + 1]);
        }
        return new Operands(values);
    }
}
