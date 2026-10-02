package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.PricingEngine.PricingInputs;
import uz.horecaos.platform.pricing.application.PricingEngine.TaxMode;
import uz.horecaos.platform.pricing.application.PromotionEvaluator;
import uz.horecaos.platform.pricing.domain.CatchweightFacts;
import uz.horecaos.platform.pricing.domain.CatchweightPricing;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.Quote;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.web.api.Quantities;

/**
 * Decimal portions and catchweight pricing (ADR 0137), on the pure engine.
 *
 * <p>Every figure below is one a cashier could check with a calculator, because that is
 * who will: 14,999 som per 100 g, 1,234 g on the scale, 185,088 som. The rounding is
 * pinned at the single place ADR 0137 puts it -- once, at the end of the line -- and the
 * neighbouring wrong answers (per-gram, per-quantum) are asserted to differ, so an engine
 * that rounded earlier could not satisfy this class.
 *
 * <p>The golden test at the top is the one that has to stay green for as long as the
 * platform exists: an order of whole portions prices to the same som and hashes to the same
 * context as it did when {@code quantity} was an integer, so no quote in flight and no
 * order already placed changes meaning.
 */
class DecimalAndCatchweightPricingTests {

    private static final UUID TENANT = UUID.fromString("018f9f30-5000-7000-8000-0000000000a1");
    private static final UUID BRAND = UUID.fromString("018f9f30-5000-7000-8000-0000000000b1");
    private static final UUID LOCATION = UUID.fromString("018f9f30-5000-7000-8000-0000000000c1");
    private static final UUID PUBLICATION = UUID.fromString("018f9f30-5000-7000-8000-0000000000d1");
    private static final UUID PRICE_BOOK = UUID.fromString("018f9f30-5000-7000-8000-0000000000e1");
    private static final UUID TAX_PROFILE = UUID.fromString("018f9f30-5000-7000-8000-0000000000f1");
    private static final UUID PLOV = UUID.fromString("018f9f30-5000-7000-8000-000000000101");
    private static final UUID CAKE = UUID.fromString("018f9f30-5000-7000-8000-000000000102");
    private static final UUID SODA = UUID.fromString("018f9f30-5000-7000-8000-000000000103");
    private static final UUID EXTRA = UUID.fromString("018f9f30-5000-7000-8000-000000000201");
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    /** 100 g is the quantum; a cake is estimated at 1,200 g. */
    private static final CatchweightFacts CAKE_BY_WEIGHT = new CatchweightFacts(100, 1_200);

    private final PricingEngine engine = new PricingEngine();

    // ------------------------------------------------------------------ the golden

    @Test
    @DisplayName("an order of whole portions prices and hashes exactly as it did when quantity was an integer")
    void wholePortionsAreByteIdenticalToTheIntegerEra() {
        var request = new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                null,
                "STOREFRONT",
                List.of(
                        new QuoteRequest.Line("a", PLOV, 3, List.of(EXTRA)),
                        new QuoteRequest.Line("b", SODA, 1, List.of())),
                null);
        var inputs = inputs(Map.of(PLOV, 33_333L, SODA, 17L), Map.of(EXTRA, 5_000L), Map.of());

        var result = engine.price(request, inputs, NOW);

        // 3 x (33,333 + 5,000) + 17 = 115,016, VAT 12% inclusive.
        assertThat(result.total().minor()).isEqualTo(115_016L);
        assertThat(result.tax().minor()).isEqualTo(12_323L);
        assertThat(result.subtotal().minor()).isEqualTo(102_693L);
        assertThat(result.lines())
                .extracting(line -> line.quantity().toPlainString())
                .containsExactly("3", "1");

        // The context hash is assembled here from the documented canonical form, the one
        // PricingEngine#contextHash wrote when quantity was an int. If the widening changed a
        // single byte of it -- "x3" becoming "x3.000", say -- every quote in flight at deploy
        // time would fail checkout with PRICE_CHANGED.
        String canonical = "v=3|tenant=" + TENANT + "|brand=" + BRAND + "|location=" + LOCATION
                + "|channel=STOREFRONT|customer=null|publication=" + PUBLICATION
                + "|priceBook=" + PRICE_BOOK + ":1|tax=" + TAX_PROFILE + ":1:1200:INCLUSIVE|currency=UZS"
                + "|delivery=none|promotions=none"
                + "|line=a:" + PLOV + "x3+" + EXTRA
                + "|line=b:" + SODA + "x1";
        assertThat(result.contextHash()).isEqualTo(sha256(canonical));
    }

    @Test
    @DisplayName("3 and 3.000 are one quantity: equal records, equal hashes")
    void aWholeQuantityIsTheSameWhateverItsScale() {
        var plain = new QuoteRequest.Line("a", PLOV, new BigDecimal("3"), List.of());
        var scaled = new QuoteRequest.Line("a", PLOV, new BigDecimal("3.000"), List.of());

        assertThat(scaled).isEqualTo(plain);
        assertThat(Quantities.normalise(new BigDecimal("20")).toPlainString())
                .as("20 stays 20: a bare stripTrailingZeros would make it 2E+1")
                .isEqualTo("20");
        assertThat(engine.price(cartOf(scaled), inputs(Map.of(PLOV, 40_000L)), NOW)
                        .contextHash())
                .isEqualTo(engine.price(cartOf(plain), inputs(Map.of(PLOV, 40_000L)), NOW)
                        .contextHash());
    }

    // ------------------------------------------------------------ decimal portions

    @Test
    @DisplayName("half a portion costs half the price, a portion and a half costs one and a half")
    void halfPortionsArePricedProportionally() {
        assertThat(total(PLOV, "0.5", 40_000L)).isEqualTo(20_000L);
        assertThat(total(PLOV, "1.5", 40_000L)).isEqualTo(60_000L);
        assertThat(total(PLOV, "0.25", 40_000L)).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("a decimal line is rounded once to a whole som, half up")
    void aDecimalLineRoundsOnceHalfUp() {
        // 33,333 x 0.5 = 16,666.5 -> 16,667. Half-even would give 16,666.
        assertThat(total(PLOV, "0.5", 33_333L)).isEqualTo(16_667L);
        // 1 som x 0.4 = 0.4 -> 0: the price of a sliver can round to nothing, and that is fine
        // arithmetic rather than a defect; whether such a portion may be ordered is the
        // variant's portion step, not the engine's business.
        assertThat(total(PLOV, "0.4", 1L)).isZero();
        assertThat(total(PLOV, "0.5", 1L)).isEqualTo(1L);
    }

    @Test
    @DisplayName("modifiers scale with the portion, and base plus modifiers add up to the line")
    void modifiersFollowTheQuantity() {
        var request = cartOf(new QuoteRequest.Line("a", PLOV, new BigDecimal("0.5"), List.of(EXTRA)));

        var result = engine.price(request, inputs(Map.of(PLOV, 40_000L), Map.of(EXTRA, 5_000L), Map.of()), NOW);

        assertThat(result.total().minor()).isEqualTo(22_500L);
        assertThat(result.adjustments())
                .filteredOn(a -> a.type() == Quote.Adjustment.Type.BASE_PRICE)
                .singleElement()
                .satisfies(a -> assertThat(a.amount().minor()).isEqualTo(20_000L));
        assertThat(result.adjustments())
                .filteredOn(a -> a.type() == Quote.Adjustment.Type.MODIFIER)
                .singleElement()
                .satisfies(a -> assertThat(a.amount().minor()).isEqualTo(2_500L));
        assertThat(result.lines().getFirst().unitAmount().minor())
                .as("the unit price is the price of one whole portion with its modifiers")
                .isEqualTo(45_000L);
    }

    @Test
    @DisplayName("line taxes still sum exactly to the total tax with decimal quantities")
    void lineTaxesStillSumWithDecimals() {
        var request = new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                null,
                "STOREFRONT",
                List.of(
                        new QuoteRequest.Line("a", PLOV, new BigDecimal("0.5"), List.of()),
                        new QuoteRequest.Line("b", CAKE, new BigDecimal("1.5"), List.of()),
                        new QuoteRequest.Line("c", SODA, 3, List.of())),
                null);

        var result = engine.price(request, inputs(Map.of(PLOV, 33_333L, CAKE, 17_777L, SODA, 4_999L)), NOW);

        assertThat(result.lines().stream().mapToLong(l -> l.taxAmount().minor()).sum())
                .isEqualTo(result.tax().minor());
        assertThat(result.lines().stream()
                        .mapToLong(l -> l.finalAmount().minor())
                        .sum())
                .as("inclusive: the lines add up to what the customer pays")
                .isEqualTo(result.total().minor());
    }

    @Test
    @DisplayName("a per-unit discount scales with a fractional quantity, and never exceeds the line")
    void aPerUnitDiscountScalesWithTheQuantity() {
        var fixedOff = itemPromotion(PLOV, Promotion.Action.Type.ITEM_FIXED_DISCOUNT, Map.of("amountMinor", 4_000L));
        var request = cartOf(new QuoteRequest.Line("a", PLOV, new BigDecimal("0.5"), List.of()));

        var result = engine.price(request, inputsWithPromotions(Map.of(PLOV, 40_000L), List.of(fixedOff)), NOW);

        // 4,000 off per unit, half a unit: 2,000 off a 20,000 line.
        assertThat(result.discount().minor()).isEqualTo(2_000L);
        assertThat(result.total().minor()).isEqualTo(18_000L);
    }

    // ------------------------------------------------------------------ catchweight

    @Test
    @DisplayName("a catchweight line is quoted at its nominal weight, and says so")
    void aCatchweightQuoteIsProvisional() {
        // 15,000 som per 100 g; the cake is estimated at 1,200 g: 12 quanta, 180,000 som.
        var result = engine.price(cartOf(cake("1", null)), cakeInputs(15_000L), NOW);

        assertThat(result.total().minor()).isEqualTo(180_000L);
        Quote.QuoteLine line = result.lines().getFirst();
        Quote.Catchweight catchweight = Objects.requireNonNull(line.catchweight());
        assertThat(line.unitAmount().minor()).isEqualTo(180_000L);
        assertThat(catchweight.quantumGrams()).isEqualTo(100);
        assertThat(catchweight.nominalGramsPerUnit()).isEqualTo(1_200);
        assertThat(catchweight.pricePerQuantumMinor()).isEqualTo(15_000L);
        assertThat(catchweight.reconciled()).isFalse();
    }

    @Test
    @DisplayName("once weighed, the line is priced at the weight on the scale")
    void aWeighedLineIsPricedAtItsActualWeight() {
        var result = engine.price(cartOf(cake("1", 1_340)), cakeInputs(15_000L), NOW);

        // 15,000 x 1,340 / 100 = 201,000.
        assertThat(result.total().minor()).isEqualTo(201_000L);
        Quote.QuoteLine line = result.lines().getFirst();
        Quote.Catchweight catchweight = Objects.requireNonNull(line.catchweight());
        assertThat(catchweight.reconciled()).isTrue();
        assertThat(catchweight.actualWeightGrams()).isEqualTo(1_340);
        assertThat(line.unitAmount().minor())
                .as(
                        "the unit price stays the provisional one: the line says what was quoted as well as what was charged")
                .isEqualTo(180_000L);
    }

    @Test
    @DisplayName("the weight rounds once, at the end: 14,999 per 100 g for 1,234 g is 185,088, not 185,100")
    void catchweightRoundsOnceAtTheEnd() {
        // 14,999 x 1,234 / 100 = 185,087.66 -> 185,088.
        assertThat(CatchweightPricing.priceOf(14_999L, 100, 1_234L)).isEqualTo(185_088L);
        var result = engine.price(cartOf(cake("1", 1_234)), cakeInputs(14_999L), NOW);
        assertThat(result.total().minor()).isEqualTo(185_088L);

        // ADR 0137 rejected pricing per gram: 14,999/100 = 149.99 rounds to 150 a gram, and
        // 150 x 1,234 = 185,100 -- twelve som more, from a rounding that happened too early.
        assertThat(150L * 1_234L).isNotEqualTo(185_088L);
        // And rounding the quantum count first (12.34 -> 12) loses a third of a quantum.
        assertThat(14_999L * 12L).isNotEqualTo(185_088L);
    }

    @Test
    @DisplayName("the half-som boundary rounds up")
    void catchweightRoundsHalfUp() {
        // 5 som per 100 g, 150 g = 7.5 -> 8. Half-even would give 8 too; 5 x 130 / 100 = 6.5 -> 7
        // is the case half-even gets wrong (it rounds to the even 6).
        assertThat(CatchweightPricing.priceOf(5L, 100, 150L)).isEqualTo(8L);
        assertThat(CatchweightPricing.priceOf(5L, 100, 130L)).isEqualTo(7L);
    }

    @Test
    @DisplayName("several units are weighed once together: three cakes, 3,500 g on the scale")
    void severalUnitsAreWeighedTogether() {
        // Provisional: 3 x 1,200 g = 3,600 g = 540,000.
        assertThat(engine.price(cartOf(cake("3", null)), cakeInputs(15_000L), NOW)
                        .total()
                        .minor())
                .isEqualTo(540_000L);
        // Weighed: the whole line's 3,500 g = 525,000.
        assertThat(engine.price(cartOf(cake("3", 3_500)), cakeInputs(15_000L), NOW)
                        .total()
                        .minor())
                .isEqualTo(525_000L);
    }

    @Test
    @DisplayName("a modifier is per unit, never per weight")
    void modifiersAreNotWeighed() {
        var request = cartOf(new QuoteRequest.Line("a", CAKE, new BigDecimal("2"), List.of(EXTRA), 2_600));

        var result = engine.price(
                request,
                new PricingInputs(
                        "UZS",
                        PUBLICATION,
                        PRICE_BOOK,
                        1,
                        TAX_PROFILE,
                        1,
                        1_200,
                        TaxMode.INCLUSIVE,
                        Map.of(CAKE, 15_000L),
                        Map.of(EXTRA, 5_000L),
                        Map.of(),
                        null,
                        null,
                        Map.of(CAKE, CAKE_BY_WEIGHT)),
                NOW);

        // 15,000 x 2,600 / 100 = 390,000, plus 2 x 5,000 for the modifier.
        assertThat(result.total().minor()).isEqualTo(400_000L);
    }

    @Test
    @DisplayName("a percentage promotion follows the weighed amount, not the quoted one")
    void aPercentageDiscountFollowsTheWeight() {
        var tenPercent =
                itemPromotion(CAKE, Promotion.Action.Type.ITEM_PERCENTAGE_DISCOUNT, Map.of("basisPoints", 1_000L));
        var weighed = engine.price(
                cartOf(cake("1", 1_340)),
                new PricingInputs(
                        "UZS",
                        PUBLICATION,
                        PRICE_BOOK,
                        1,
                        TAX_PROFILE,
                        1,
                        1_200,
                        TaxMode.INCLUSIVE,
                        Map.of(CAKE, 15_000L),
                        Map.of(),
                        Map.of(),
                        null,
                        promotions(List.of(tenPercent)),
                        Map.of(CAKE, CAKE_BY_WEIGHT)),
                NOW);

        // 10% off 201,000.
        assertThat(weighed.discount().minor()).isEqualTo(20_100L);
        assertThat(weighed.total().minor()).isEqualTo(180_900L);
    }

    @Test
    @DisplayName("taxes of a weighed order still sum exactly to the total tax")
    void taxesOfAWeighedOrderStillSum() {
        var request = new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                null,
                "STOREFRONT",
                List.of(cake("1", 1_337), new QuoteRequest.Line("b", SODA, 3, List.of())),
                null);

        var result = engine.price(
                request,
                new PricingInputs(
                        "UZS",
                        PUBLICATION,
                        PRICE_BOOK,
                        1,
                        TAX_PROFILE,
                        1,
                        1_200,
                        TaxMode.INCLUSIVE,
                        Map.of(CAKE, 14_999L, SODA, 4_999L),
                        Map.of(),
                        Map.of(),
                        null,
                        null,
                        Map.of(CAKE, CAKE_BY_WEIGHT)),
                NOW);

        assertThat(result.lines().stream().mapToLong(l -> l.taxAmount().minor()).sum())
                .isEqualTo(result.tax().minor());
        assertThat(result.subtotal().minor() + result.tax().minor())
                .isEqualTo(result.total().minor());
    }

    @Test
    @DisplayName("a weight against a variant that is not sold by weight is refused, not ignored")
    void aWeightOnAFixedPriceVariantIsRefused() {
        var request = cartOf(new QuoteRequest.Line("a", SODA, BigDecimal.ONE, List.of(), 330));

        Throwable refused = catchThrowable(() -> engine.price(request, inputs(Map.of(SODA, 5_000L)), NOW));

        assertThat(refused)
                .isInstanceOfSatisfying(
                        PricingEngine.NotCatchweightException.class,
                        e -> assertThat(e.variantId()).isEqualTo(SODA));
    }

    @Test
    @DisplayName("capturing a weight changes the context hash; the same weight hashes the same")
    void theWeightEntersTheContextHash() {
        var provisional = engine.price(cartOf(cake("1", null)), cakeInputs(15_000L), NOW);
        var weighed = engine.price(cartOf(cake("1", 1_340)), cakeInputs(15_000L), NOW);
        var weighedAgain = engine.price(cartOf(cake("1", 1_340)), cakeInputs(15_000L), NOW);
        var weighedDifferently = engine.price(cartOf(cake("1", 1_341)), cakeInputs(15_000L), NOW);

        assertThat(weighed.contextHash()).isNotEqualTo(provisional.contextHash());
        assertThat(weighedAgain.contextHash()).isEqualTo(weighed.contextHash());
        assertThat(weighedDifferently.contextHash()).isNotEqualTo(weighed.contextHash());
    }

    @Test
    @DisplayName("a catchweight variant carries positive facts, and a quote line rejects a non-positive weight")
    void theFactsAreValidated() {
        assertThat(catchThrowable(() -> new CatchweightFacts(0, 1_200))).isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> new CatchweightFacts(100, 0))).isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> new QuoteRequest.Line("a", CAKE, BigDecimal.ONE, List.of(), 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(catchThrowable(() -> new QuoteRequest.Line("a", CAKE, BigDecimal.ZERO, List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ------------------------------------------------------------------ helpers

    private static QuoteRequest.Line cake(String quantity, @Nullable Integer actualWeightGrams) {
        return new QuoteRequest.Line("a", CAKE, new BigDecimal(quantity), List.of(), actualWeightGrams);
    }

    private static QuoteRequest cartOf(QuoteRequest.Line line) {
        return new QuoteRequest(TENANT, BRAND, LOCATION, null, "STOREFRONT", List.of(line), null);
    }

    private long total(UUID variant, String quantity, long price) {
        return engine.price(
                        cartOf(new QuoteRequest.Line("a", variant, new BigDecimal(quantity), List.of())),
                        inputs(Map.of(variant, price)),
                        NOW)
                .total()
                .minor();
    }

    private static PricingInputs inputs(Map<UUID, Long> prices) {
        return inputs(prices, Map.of(), Map.of());
    }

    private static PricingInputs inputs(
            Map<UUID, Long> prices, Map<UUID, Long> modifiers, Map<UUID, CatchweightFacts> catchweight) {
        return new PricingInputs(
                "UZS",
                PUBLICATION,
                PRICE_BOOK,
                1,
                TAX_PROFILE,
                1,
                1_200,
                TaxMode.INCLUSIVE,
                prices,
                modifiers,
                Map.of(),
                null,
                null,
                catchweight);
    }

    private static PricingInputs cakeInputs(long pricePerQuantum) {
        return inputs(Map.of(CAKE, pricePerQuantum), Map.of(), Map.of(CAKE, CAKE_BY_WEIGHT));
    }

    private static PricingInputs inputsWithPromotions(Map<UUID, Long> prices, List<Promotion> promotions) {
        return new PricingInputs(
                "UZS",
                PUBLICATION,
                PRICE_BOOK,
                1,
                TAX_PROFILE,
                1,
                1_200,
                TaxMode.INCLUSIVE,
                prices,
                Map.of(),
                Map.of(),
                null,
                promotions(promotions),
                Map.of());
    }

    private static PricingEngine.PromotionInputs promotions(List<Promotion> promotions) {
        return new PricingEngine.PromotionInputs(
                promotions,
                new PromotionEvaluator.PromotionContext(
                        "STOREFRONT", LOCATION, "PICKUP", false, java.util.Set.of(), java.util.Set.of(), 3, 720),
                Map.of());
    }

    /** An unconditional-but-for-the-variant, item-scoped promotion in force at {@link #NOW}. */
    private static Promotion itemPromotion(UUID variantId, Promotion.Action.Type action, Map<String, Object> operands) {
        return new Promotion(
                UUID.randomUUID(),
                TENANT,
                BRAND,
                "ITEM-OFF",
                Promotion.Scope.ITEM,
                "SEASONAL",
                false,
                0,
                false,
                null,
                "UZS",
                NOW.minusSeconds(3_600),
                null,
                1,
                List.of(new Promotion.Condition(
                        1,
                        Promotion.Condition.Type.VARIANT,
                        new Promotion.Operands(Map.of("variantIds", List.of(variantId.toString()))))),
                List.of(new Promotion.Action(1, action, new Promotion.Operands(operands))));
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
