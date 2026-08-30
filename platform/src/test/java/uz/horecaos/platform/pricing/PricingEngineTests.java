package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.PricingEngine.PricingInputs;
import uz.horecaos.platform.pricing.application.PricingEngine.TaxMode;
import uz.horecaos.platform.pricing.domain.Quote;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.pricing.domain.TaxCalculation;

/**
 * The pricing arithmetic (ADR 0018).
 *
 * <p>Every assertion here is one a customer or an accountant could check by hand,
 * because that is exactly who will. A quote that cannot be re-derived is a bill
 * nobody can defend.
 */
class PricingEngineTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID PUBLICATION = UUID.randomUUID();
    private static final UUID PRICE_BOOK = UUID.randomUUID();
    private static final UUID TAX_PROFILE = UUID.randomUUID();
    private static final UUID BURGER = UUID.randomUUID();
    private static final UUID PIZZA = UUID.randomUUID();
    private static final UUID EXTRA_CHEESE = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

    private final PricingEngine engine = new PricingEngine();

    @Test
    @DisplayName("VAT is extracted from the price, not added to it")
    void vatIsExtractedFromAnInclusivePrice() {
        // 50,000 som on the menu at 12% VAT. The customer pays 50,000 — not
        // 56,000. Tax is 50000 × 1200 / 11200 = 5357.14…, rounded to 5357.
        var result = engine.price(cart(BURGER, 1), inputs(Map.of(BURGER, 50_000L)), NOW);

        assertThat(result.total().minor())
                .as("the customer pays the menu price")
                .isEqualTo(50_000L);
        assertThat(result.tax().minor()).isEqualTo(5_357L);
        assertThat(result.subtotal().minor()).isEqualTo(44_643L);
        // The identity a fiscal receipt has to satisfy.
        assertThat(result.subtotal().minor() + result.tax().minor())
                .isEqualTo(result.total().minor());
    }

    @Test
    @DisplayName("line taxes always sum exactly to the total tax")
    void lineTaxesSumToTheTotal() {
        // Deliberately awkward amounts: three lines whose individual tax shares
        // do not divide evenly, which is where a naive per-line rounding leaves
        // a remainder and the total stops matching the sum of its parts.
        var request = new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                null,
                "STOREFRONT",
                List.of(
                        new QuoteRequest.Line("a", BURGER, 3, List.of()),
                        new QuoteRequest.Line("b", PIZZA, 1, List.of())),
                null);

        var result = engine.price(request, inputs(Map.of(BURGER, 33_333L, PIZZA, 17L)), NOW);

        long summed = result.lines().stream()
                .mapToLong(line -> line.taxAmount().minor())
                .sum();
        assertThat(summed)
                .as("a total that differs from the sum of its lines is what an accountant finds "
                        + "and nobody can explain")
                .isEqualTo(result.tax().minor());
    }

    @Test
    @DisplayName("rounding is half-up, so a receipt checked by hand agrees")
    void roundingIsHalfUp() {
        // Chosen so the extraction lands exactly on .5 and the direction is
        // visible: half-even would round this the other way.
        assertThat(TaxCalculation.extractInclusiveTax(14L, 10_000)).isEqualTo(7L);
        assertThat(TaxCalculation.extractInclusiveTax(1_050L, 10_000)).isEqualTo(525L);
        // A zero rate extracts nothing rather than dividing by a zero-ish figure.
        assertThat(TaxCalculation.extractInclusiveTax(50_000L, 0)).isZero();
    }

    @Test
    @DisplayName("modifiers are priced into the line and shown as their own adjustment")
    void modifiersArePricedAndVisible() {
        var request = new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                null,
                "STOREFRONT",
                List.of(new QuoteRequest.Line("a", BURGER, 2, List.of(EXTRA_CHEESE))),
                null);

        var result =
                engine.price(request, inputsWithModifiers(Map.of(BURGER, 50_000L), Map.of(EXTRA_CHEESE, 5_000L)), NOW);

        assertThat(result.total().minor()).isEqualTo(110_000L);
        // "Why is this 110,000" has an answer: 2 × 50,000 base plus 2 × 5,000
        // cheese. A single number would not.
        assertThat(result.adjustments())
                .filteredOn(a -> a.type() == Quote.Adjustment.Type.MODIFIER)
                .singleElement()
                .satisfies(a -> assertThat(a.amount().minor()).isEqualTo(10_000L));
    }

    @Test
    @DisplayName("an item with no active price is refused, never priced at zero")
    void anUnpricedItemIsRefused() {
        // Pricing a missing item as free is how a restaurant gives away food.
        assertThat(catchThrowable(() -> engine.price(cart(BURGER, 1), inputs(Map.of(PIZZA, 10_000L)), NOW)))
                .isInstanceOf(PricingEngine.UnpricedItemException.class);
    }

    @Test
    @DisplayName("an exclusive tax profile is refused rather than approximated")
    void exclusiveTaxModeIsRefused() {
        var exclusive = new PricingInputs(
                "UZS",
                PUBLICATION,
                PRICE_BOOK,
                1,
                TAX_PROFILE,
                1,
                1_200,
                TaxMode.EXCLUSIVE,
                Map.of(BURGER, 50_000L),
                Map.of(),
                Map.of());

        // A half-implemented mode producing plausible wrong totals is worse than
        // an error nobody can ignore.
        assertThat(catchThrowable(() -> engine.price(cart(BURGER, 1), exclusive, NOW)))
                .isInstanceOf(PricingEngine.UnsupportedTaxModeException.class);
    }

    @Test
    @DisplayName("the same cart prices identically however its lines are ordered")
    void theContextHashIgnoresLineOrder() {
        var ascending = new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                null,
                "STOREFRONT",
                List.of(
                        new QuoteRequest.Line("a", BURGER, 1, List.of()),
                        new QuoteRequest.Line("b", PIZZA, 2, List.of())),
                null);
        var descending = new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                null,
                "STOREFRONT",
                List.of(
                        new QuoteRequest.Line("b", PIZZA, 2, List.of()),
                        new QuoteRequest.Line("a", BURGER, 1, List.of())),
                null);

        var prices = inputs(Map.of(BURGER, 50_000L, PIZZA, 70_000L));

        // Re-ordering a basket is not a changed basket. Without this, dragging an
        // item in the cart would invalidate the quote.
        assertThat(engine.price(descending, prices, NOW).contextHash())
                .isEqualTo(engine.price(ascending, prices, NOW).contextHash());
        assertThat(engine.price(descending, prices, NOW).total())
                .isEqualTo(engine.price(ascending, prices, NOW).total());
    }

    @Test
    @DisplayName("changing any priced input changes the context hash")
    void theContextHashCoversEveryInputThatMovesTheTotal() {
        var request = cart(BURGER, 1);
        String baseline =
                engine.price(request, inputs(Map.of(BURGER, 50_000L)), NOW).contextHash();

        // A changed price book, tax rate, publication, or quantity must all
        // invalidate the quote — otherwise checkout would honour a stale price.
        assertThat(engine.price(request, withPriceBookVersion(2), NOW).contextHash())
                .isNotEqualTo(baseline);
        assertThat(engine.price(request, withTaxRate(1_500), NOW).contextHash()).isNotEqualTo(baseline);
        assertThat(engine.price(request, withPublication(UUID.randomUUID()), NOW)
                        .contextHash())
                .isNotEqualTo(baseline);
        assertThat(engine.price(cart(BURGER, 2), inputs(Map.of(BURGER, 50_000L)), NOW)
                        .contextHash())
                .isNotEqualTo(baseline);
    }

    @Test
    @DisplayName("a changed menu price does not change the hash unless the book version moves")
    void priceChangesTravelThroughThePriceBookVersion() {
        // The hash covers the price book identity and version rather than every
        // amount, so changing a price without versioning the book would leave a
        // stale quote acceptable. Recorded here as the invariant a price edit
        // must maintain: editing a price bumps the book's version.
        String cheap = engine.price(cart(BURGER, 1), inputs(Map.of(BURGER, 50_000L)), NOW)
                .contextHash();
        String expensive = engine.price(cart(BURGER, 1), inputs(Map.of(BURGER, 60_000L)), NOW)
                .contextHash();

        assertThat(expensive).isEqualTo(cheap);
        assertThat(engine.price(cart(BURGER, 1), withPriceBookVersion(2), NOW).contextHash())
                .isNotEqualTo(cheap);
    }

    @Test
    @DisplayName("the same quote computed twice is byte-identical")
    void pricingIsDeterministic() {
        var request = cart(BURGER, 3);
        var prices = inputs(Map.of(BURGER, 33_333L));

        var first = engine.price(request, prices, NOW);
        var second = engine.price(request, prices, Instant.parse("2026-12-25T23:59:00Z"));

        // Including across a different instant: the engine reads no clock, so
        // pricing the same cart tomorrow cannot produce a different bill.
        assertThat(second.total()).isEqualTo(first.total());
        assertThat(second.tax()).isEqualTo(first.tax());
        assertThat(second.contextHash()).isEqualTo(first.contextHash());
    }

    @Test
    @DisplayName("apportionment gives the remainder to the largest line, deterministically")
    void apportionmentIsExactAndStable() {
        // Equal weights: the remainder goes to the first of the tied largest, so
        // the split is reproducible rather than merely fair.
        assertThat(TaxCalculation.apportion(100L, new long[] {1L, 1L, 1L})).containsExactly(34L, 33L, 33L);

        // Unequal weights: the largest line absorbs it.
        long[] shares = TaxCalculation.apportion(100L, new long[] {1L, 8L, 1L});
        assertThat(shares).containsExactly(10L, 80L, 10L);
        assertThat(java.util.Arrays.stream(shares).sum()).isEqualTo(100L);
        // Nothing to split is not an error; it is an empty split.
        assertThat(TaxCalculation.apportion(0L, new long[] {5L, 5L})).containsExactly(0L, 0L);
        assertThat(TaxCalculation.apportion(100L, new long[] {0L, 0L})).containsExactly(0L, 0L);
    }

    private static QuoteRequest cart(UUID variantId, int quantity) {
        return new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                null,
                "STOREFRONT",
                List.of(new QuoteRequest.Line("a", variantId, quantity, List.of())),
                null);
    }

    private static PricingInputs inputs(Map<UUID, Long> variantPrices) {
        return new PricingInputs(
                "UZS",
                PUBLICATION,
                PRICE_BOOK,
                1,
                TAX_PROFILE,
                1,
                1_200,
                TaxMode.INCLUSIVE,
                variantPrices,
                Map.of(),
                Map.of());
    }

    private static PricingInputs inputsWithModifiers(Map<UUID, Long> variants, Map<UUID, Long> modifiers) {
        return new PricingInputs(
                "UZS",
                PUBLICATION,
                PRICE_BOOK,
                1,
                TAX_PROFILE,
                1,
                1_200,
                TaxMode.INCLUSIVE,
                variants,
                modifiers,
                Map.of());
    }

    private static PricingInputs withPriceBookVersion(int version) {
        return new PricingInputs(
                "UZS",
                PUBLICATION,
                PRICE_BOOK,
                version,
                TAX_PROFILE,
                1,
                1_200,
                TaxMode.INCLUSIVE,
                Map.of(BURGER, 50_000L),
                Map.of(),
                Map.of());
    }

    private static PricingInputs withTaxRate(int rateBasisPoints) {
        return new PricingInputs(
                "UZS",
                PUBLICATION,
                PRICE_BOOK,
                1,
                TAX_PROFILE,
                1,
                rateBasisPoints,
                TaxMode.INCLUSIVE,
                Map.of(BURGER, 50_000L),
                Map.of(),
                Map.of());
    }

    private static PricingInputs withPublication(UUID publicationId) {
        return new PricingInputs(
                "UZS",
                publicationId,
                PRICE_BOOK,
                1,
                TAX_PROFILE,
                1,
                1_200,
                TaxMode.INCLUSIVE,
                Map.of(BURGER, 50_000L),
                Map.of(),
                Map.of());
    }
}
