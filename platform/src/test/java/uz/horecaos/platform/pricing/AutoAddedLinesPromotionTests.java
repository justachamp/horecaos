package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.pricing.application.MenuMembershipLookup;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.PricingEngine.PricingInputs;
import uz.horecaos.platform.pricing.application.PricingEngine.PromotionInputs;
import uz.horecaos.platform.pricing.application.PricingEngine.TaxMode;
import uz.horecaos.platform.pricing.application.PromotionEvaluator;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.Promotion.Action;
import uz.horecaos.platform.pricing.domain.Promotion.Condition;
import uz.horecaos.platform.pricing.domain.Promotion.Operands;
import uz.horecaos.platform.pricing.domain.QuoteRequest;

/**
 * ADR 0158, open input 2: pricing is told each line's origin, and a line an auto-add rule put in the basket
 * never counts toward a promotion that names products, variants or categories, so a bag cannot tip a "three
 * for two". It stays an ordinary line for every money condition and for a promotion that names no item.
 *
 * <p>Each case is run twice, once with the bag as a rule's line and once as the same line chosen by the
 * customer, so the assertion is the difference the origin makes and not an accident of the fixture.
 */
class AutoAddedLinesPromotionTests {

    private static final UUID PUBLICATION = UUID.randomUUID();
    private static final UUID PRICE_BOOK = UUID.randomUUID();
    private static final UUID TAX_PROFILE = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID BURGER_VARIANT = UUID.randomUUID();
    private static final UUID BURGER = UUID.randomUUID();
    private static final UUID BAG_VARIANT = UUID.randomUUID();
    private static final UUID BAG = UUID.randomUUID();
    private static final UUID MAINS = UUID.randomUUID();
    private static final UUID DRINKS = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-12T12:00:00Z");

    private final PricingEngine engine = new PricingEngine();

    @Test
    @DisplayName("a bag cannot be the third unit of a 'three of the mains' promotion: it counts only when the "
            + "customer chose it")
    void aBagCannotTipAnItemTargetedMinimum() {
        Promotion threeMains = promotion(
                "THREE-MAINS",
                Promotion.Scope.ITEM,
                new Action(1, Action.Type.ITEM_PERCENTAGE_DISCOUNT, new Operands(Map.of("basisPoints", 1_000L))),
                new Condition(1, Condition.Type.CATEGORY, new Operands(Map.of("categoryIds", ids(MAINS)))),
                new Condition(2, Condition.Type.QUANTITY_AT_LEAST, new Operands(Map.of("quantity", 3L))));

        var chosen = engine.price(cart(2, line(BAG_VARIANT, 1, false)), inputs(offers(threeMains)), NOW);
        var injected = engine.price(cart(2, line(BAG_VARIANT, 1, true)), inputs(offers(threeMains)), NOW);

        assertThat(chosen.discount().minor())
                .as("control: the same bag chosen by the customer is the third unit, and 10% of 101 000 is off")
                .isEqualTo(10_100L);
        assertThat(injected.discount().minor())
                .as("two burgers are two, whatever a rule added beside them")
                .isZero();
        assertThat(injected.total().minor()).isEqualTo(101_000L);
    }

    @Test
    @DisplayName("an item promotion that excludes a category still leaves a rule's line out of what it discounts")
    void aRulesLineIsNotDiscountedByAnExcludePromotion() {
        Promotion everythingButDrinks = promotion(
                "NO-DRINKS",
                Promotion.Scope.ITEM,
                new Action(1, Action.Type.ITEM_PERCENTAGE_DISCOUNT, new Operands(Map.of("basisPoints", 1_000L))),
                new Condition(
                        1, Condition.Type.CATEGORY, new Operands(Map.of("categoryIds", ids(DRINKS), "exclude", true))));

        var chosen = engine.price(cart(1, line(BAG_VARIANT, 1, false)), inputs(offers(everythingButDrinks)), NOW);
        var injected = engine.price(cart(1, line(BAG_VARIANT, 1, true)), inputs(offers(everythingButDrinks)), NOW);

        assertThat(chosen.discount().minor())
                .as("control: 10% of the burger and of the bag the customer chose")
                .isEqualTo(5_100L);
        assertThat(injected.discount().minor())
                .as("names a category, so the rule's line is not one of the lines it discounts")
                .isEqualTo(5_000L);
    }

    @Test
    @DisplayName("a promotion that names no item counts a rule's line like any other: a bag is an ordinary line "
            + "for every money and quantity condition")
    void aPromotionWithNoItemPredicateCountsTheLine() {
        Promotion anyThree = promotion(
                "ANY-THREE",
                Promotion.Scope.ORDER,
                new Action(1, Action.Type.ORDER_PERCENTAGE_DISCOUNT, new Operands(Map.of("basisPoints", 1_000L))),
                new Condition(1, Condition.Type.QUANTITY_AT_LEAST, new Operands(Map.of("quantity", 3L))));

        var chosen = engine.price(cart(2, line(BAG_VARIANT, 1, false)), inputs(offers(anyThree)), NOW);
        var injected = engine.price(cart(2, line(BAG_VARIANT, 1, true)), inputs(offers(anyThree)), NOW);

        assertThat(injected.discount().minor())
                .as("no item is named, so the bag is the third unit, exactly as when the customer chose it")
                .isEqualTo(chosen.discount().minor())
                .isEqualTo(10_100L);
    }

    @Test
    @DisplayName("a minimum order amount counts a rule's line: the bag is money like any other line")
    void aRulesLineCountsTowardAMoneyCondition() {
        Promotion over101 = promotion(
                "OVER-101",
                Promotion.Scope.ORDER,
                new Action(1, Action.Type.ORDER_PERCENTAGE_DISCOUNT, new Operands(Map.of("basisPoints", 1_000L))),
                new Condition(1, Condition.Type.SUBTOTAL_AT_LEAST, new Operands(Map.of("amountMinor", 101_000L))));

        var injected = engine.price(cart(2, line(BAG_VARIANT, 1, true)), inputs(offers(over101)), NOW);

        assertThat(injected.discount().minor())
                .as("100 000 of burgers is short of 101 000 until the 1 000 bag is counted")
                .isEqualTo(10_100L);
    }

    @Test
    @DisplayName("the origin is in the context hash: a quote priced with the bag as the customer's is not "
            + "accepted for the bag as a rule's")
    void theOriginIsInTheContextHash() {
        String chosen = engine.price(cart(2, line(BAG_VARIANT, 1, false)), inputs(null), NOW)
                .contextHash();
        String injected = engine.price(cart(2, line(BAG_VARIANT, 1, true)), inputs(null), NOW)
                .contextHash();
        String again = engine.price(cart(2, line(BAG_VARIANT, 1, true)), inputs(null), NOW)
                .contextHash();

        assertThat(injected).isNotEqualTo(chosen).isEqualTo(again);
    }

    // ------------------------------------------------------------------ fixtures

    private static QuoteRequest.Line line(UUID variant, int quantity, boolean autoAdded) {
        return new QuoteRequest.Line(
                // The same id either way: only the origin differs, so a difference in the hash is the origin's.
                "aa:" + variant,
                variant,
                BigDecimal.valueOf(quantity),
                List.of(),
                List.of(),
                List.of(),
                null,
                autoAdded);
    }

    private static QuoteRequest cart(int burgers, QuoteRequest.Line bag) {
        return new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                null,
                "STOREFRONT",
                List.of(new QuoteRequest.Line("burger", BURGER_VARIANT, burgers, List.of()), bag),
                null);
    }

    private static PricingInputs inputs(@Nullable PromotionInputs promotions) {
        return new PricingInputs(
                "UZS",
                PUBLICATION,
                PRICE_BOOK,
                1,
                TAX_PROFILE,
                1,
                1_200,
                TaxMode.INCLUSIVE,
                Map.of(BURGER_VARIANT, 50_000L, BAG_VARIANT, 1_000L),
                Map.of(),
                Map.of(),
                null,
                promotions);
    }

    private static PromotionInputs offers(Promotion... promotions) {
        return new PromotionInputs(
                List.of(promotions),
                new PromotionEvaluator.PromotionContext(
                        "STOREFRONT", LOCATION, "PICKUP", false, Set.of(), Set.of(), 5, 12 * 60),
                Map.of(
                        BURGER_VARIANT, new MenuMembershipLookup.Membership(BURGER, Set.of(MAINS)),
                        BAG_VARIANT, new MenuMembershipLookup.Membership(BAG, Set.of(MAINS))));
    }

    private static List<String> ids(UUID... ids) {
        return java.util.Arrays.stream(ids).map(UUID::toString).toList();
    }

    private static Promotion promotion(String code, Promotion.Scope scope, Action action, Condition... conditions) {
        return new Promotion(
                UUID.nameUUIDFromBytes(code.getBytes()),
                TENANT,
                BRAND,
                code,
                scope,
                code,
                false,
                0,
                false,
                null,
                "UZS",
                NOW.minusSeconds(60),
                null,
                1,
                List.of(conditions),
                List.of(action));
    }
}
