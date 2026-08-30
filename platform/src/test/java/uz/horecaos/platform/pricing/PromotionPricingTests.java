package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
import uz.horecaos.platform.pricing.domain.Promotion.Operands;
import uz.horecaos.platform.pricing.domain.Quote;
import uz.horecaos.platform.pricing.domain.QuoteRequest;

/**
 * ADR 0018 stages 3 and 4, through {@link PricingEngine} rather than through the
 * evaluator alone.
 *
 * <p>{@code PromotionEvaluatorTests} proves which promotion is chosen. This
 * proves the chosen one actually reaches the total, the tax and the adjustment
 * record — which is a different claim, and the one a customer's receipt depends
 * on. The engine's existing tests all pass with stages 3 and 4 wired in and
 * would have passed with them wired in wrongly, because none of them has a
 * promotion in it.
 */
class PromotionPricingTests {

    private static final UUID PUBLICATION = UUID.randomUUID();
    private static final UUID PRICE_BOOK = UUID.randomUUID();
    private static final UUID TAX_PROFILE = UUID.randomUUID();
    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID OSH_VARIANT = UUID.randomUUID();
    private static final UUID OSH_PRODUCT = UUID.randomUUID();
    private static final UUID MAINS = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

    private final PricingEngine engine = new PricingEngine();

    @Test
    @DisplayName("a ten percent promotion comes off the total the customer pays")
    void aPromotionReducesTheTotal() {
        var undiscounted = engine.price(cart(1), inputs(null), NOW);
        var discounted = engine.price(cart(1), inputs(offers(tenPercentOff())), NOW);

        assertThat(undiscounted.total().minor()).isEqualTo(50_000L);
        assertThat(discounted.total().minor())
                .as("ten percent off fifty thousand")
                .isEqualTo(45_000L);
        assertThat(discounted.discount().minor())
                .as("and the discount is reported rather than left at zero")
                .isEqualTo(5_000L);
    }

    @Test
    @DisplayName("VAT is extracted from the discounted price, not the menu price")
    void taxFollowsTheDiscount() {
        var result = engine.price(cart(1), inputs(offers(tenPercentOff())), NOW);

        // 45 000 gross at 12% inclusive: 45000 x 1200 / 11200 = 4821.4..., so 4821.
        // Taxing the undiscounted 50 000 would give 5357 and charge the customer
        // VAT on money they never paid.
        assertThat(result.tax().minor()).isEqualTo(4_821L);
        assertThat(result.subtotal().minor()).isEqualTo(45_000L - 4_821L);
        assertThat(result.subtotal().minor() + result.tax().minor())
                .as("the identity a fiscal receipt has to satisfy")
                .isEqualTo(result.total().minor());
    }

    @Test
    @DisplayName("the discount is recorded as an adjustment naming its promotion")
    void theDiscountIsExplainable() {
        Promotion promotion = tenPercentOff();
        var result = engine.price(cart(1), inputs(offers(promotion)), NOW);

        var discounts = result.adjustments().stream()
                .filter(adjustment -> adjustment.type() == Quote.Adjustment.Type.ITEM_DISCOUNT)
                .toList();

        assertThat(discounts).hasSize(1);
        assertThat(discounts.get(0).sourceType()).isEqualTo("PROMOTION");
        assertThat(discounts.get(0).sourceId()).isEqualTo(promotion.promotionId());
        assertThat(discounts.get(0).sourceVersion())
                .as("the definition version that priced it, not whatever it says today")
                .isEqualTo(promotion.definitionVersion());
        assertThat(discounts.get(0).amount().minor())
                .as("recorded negative, like every other reduction")
                .isEqualTo(-5_000L);
    }

    @Test
    @DisplayName("a line's own amount carries its discount, and the line still reconciles")
    void theLineReconciles() {
        var result = engine.price(cart(1), inputs(offers(tenPercentOff())), NOW);

        Quote.QuoteLine line = result.lines().stream()
                .filter(candidate -> candidate.type() == Quote.LineType.ITEM)
                .findFirst()
                .orElseThrow();

        assertThat(line.baseAmount().minor())
                .as("the base is what it cost before the offer")
                .isEqualTo(50_000L);
        assertThat(line.finalAmount().minor())
                .as("the final is what it cost after")
                .isEqualTo(45_000L);
    }

    @Test
    @DisplayName("suspending a promotion invalidates a quote in flight")
    void thePromotionIsInTheContextHash() {
        String withOffer =
                engine.price(cart(1), inputs(offers(tenPercentOff())), NOW).contextHash();
        String withoutOffer = engine.price(cart(1), inputs(null), NOW).contextHash();

        assertThat(withOffer)
                .as("otherwise a promotion pulled mid-checkout leaves the quote valid " + "and the total wrong")
                .isNotEqualTo(withoutOffer);
    }

    @Test
    @DisplayName("re-authoring a promotion invalidates a quote in flight")
    void theDefinitionVersionIsInTheContextHash() {
        Promotion first = tenPercentOff();
        Promotion rewritten = new Promotion(
                first.promotionId(),
                first.tenantId(),
                first.brandId(),
                first.code(),
                first.scope(),
                first.stackingGroup(),
                false,
                0,
                false,
                null,
                "UZS",
                first.validFrom(),
                null,
                2,
                first.conditions(),
                first.actions());

        assertThat(engine.price(cart(1), inputs(offers(first)), NOW).contextHash())
                .isNotEqualTo(
                        engine.price(cart(1), inputs(offers(rewritten)), NOW).contextHash());
    }

    @Test
    @DisplayName("a cart with no promotions prices exactly as it did before")
    void nothingChangesWhenNothingIsOnOffer() {
        var withNullInputs = engine.price(cart(2), inputs(null), NOW);
        var withEmptyList = engine.price(cart(2), inputs(new PromotionInputs(List.of(), context(), membership())), NOW);

        assertThat(withNullInputs.total().minor()).isEqualTo(100_000L);
        assertThat(withEmptyList.total().minor()).isEqualTo(100_000L);
        assertThat(withNullInputs.discount().minor()).isZero();
        assertThat(withNullInputs.adjustments())
                .as("no promotion, no promotion adjustment")
                .noneMatch(adjustment -> "PROMOTION".equals(adjustment.sourceType()));
    }

    @Test
    @DisplayName("a promotion matching no line leaves the total alone")
    void anUnmatchedPromotionChangesNothing() {
        Promotion elsewhere = new Promotion(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "OTHER",
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
                List.of(new Promotion.Condition(
                        1,
                        Promotion.Condition.Type.PRODUCT,
                        new Operands(
                                Map.of("productIds", List.of(UUID.randomUUID().toString()))))),
                List.of(new Action(
                        1, Action.Type.ITEM_PERCENTAGE_DISCOUNT, new Operands(Map.of("basisPoints", 5_000L)))));

        assertThat(engine.price(cart(1), inputs(offers(elsewhere)), NOW).total().minor())
                .isEqualTo(50_000L);
    }

    // ------------------------------------------------------------------ fixtures

    private static QuoteRequest cart(int quantity) {
        return new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                null,
                "STOREFRONT",
                List.of(new QuoteRequest.Line("line-1", OSH_VARIANT, quantity, List.of())),
                null);
    }

    private static PricingInputs inputs(PromotionInputs promotions) {
        return new PricingInputs(
                "UZS",
                PUBLICATION,
                PRICE_BOOK,
                1,
                TAX_PROFILE,
                1,
                1_200,
                TaxMode.INCLUSIVE,
                Map.of(OSH_VARIANT, 50_000L),
                Map.of(),
                Map.of(),
                null,
                promotions);
    }

    private static PromotionInputs offers(Promotion... promotions) {
        return new PromotionInputs(List.of(promotions), context(), membership());
    }

    private static PromotionEvaluator.PromotionContext context() {
        return new PromotionEvaluator.PromotionContext(
                "STOREFRONT", LOCATION, "PICKUP", false, Set.of(), Set.of(), 5, 12 * 60);
    }

    private static Map<UUID, MenuMembershipLookup.Membership> membership() {
        return Map.of(OSH_VARIANT, new MenuMembershipLookup.Membership(OSH_PRODUCT, Set.of(MAINS)));
    }

    private static Promotion tenPercentOff() {
        return new Promotion(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "TEN",
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
                List.of(new Promotion.Condition(
                        1,
                        Promotion.Condition.Type.PRODUCT,
                        new Operands(Map.of("productIds", List.of(OSH_PRODUCT.toString()))))),
                List.of(new Action(
                        1, Action.Type.ITEM_PERCENTAGE_DISCOUNT, new Operands(Map.of("basisPoints", 1_000L)))));
    }
}
