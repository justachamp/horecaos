package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.horecaos.platform.pricing.PromotionDbFixture.BRAND;
import static uz.horecaos.platform.pricing.PromotionDbFixture.CUSTOMER;
import static uz.horecaos.platform.pricing.PromotionDbFixture.LOCATION;
import static uz.horecaos.platform.pricing.PromotionDbFixture.OTHER_TENANT;
import static uz.horecaos.platform.pricing.PromotionDbFixture.TASHKENT_LUNCH;
import static uz.horecaos.platform.pricing.PromotionDbFixture.TENANT;
import static uz.horecaos.platform.pricing.PromotionDbFixture.action;
import static uz.horecaos.platform.pricing.PromotionDbFixture.condition;
import static uz.horecaos.platform.pricing.PromotionDbFixture.definition;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.pricing.api.AppliedPromotions;
import uz.horecaos.platform.pricing.api.AppliedPromotions.Applied;
import uz.horecaos.platform.pricing.api.AppliedPromotions.CouponOutcome;
import uz.horecaos.platform.pricing.api.AppliedPromotions.Effect;
import uz.horecaos.platform.pricing.api.AppliedPromotions.Source;
import uz.horecaos.platform.pricing.application.AppliedPromotionService;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService.DiscountShape;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService.PromoCodeDraft;
import uz.horecaos.platform.pricing.application.PromoCodeEligibilityService;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.PromotionDefinition;
import uz.horecaos.platform.pricing.domain.Quote;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0140's storefront half, over a real database: what a customer is told about the
 * promotions behind a priced quote, and what became of a code they typed.
 *
 * <p>The service reads the quote's own evidence, so every case prices a real quote
 * through {@link uz.horecaos.platform.pricing.application.QuoteService} first and then
 * asks about it by id, which is how the storefront controller does.
 */
class AppliedPromotionTests {

    private static TestDatabase.Handle db;

    private PromotionDbFixture fixture;
    private PromoCodeAuthoringService codes;
    private AppliedPromotionService applied;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the applied promotions");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    @BeforeEach
    void setUp() {
        fixture = new PromotionDbFixture(db, TASHKENT_LUNCH);
        codes = new PromoCodeAuthoringService(fixture.promoCodeStore, fixture.clock);
        applied = new AppliedPromotionService(
                fixture.pricingStore,
                fixture.promoCodeStore,
                new PromoCodeEligibilityService(fixture.promoCodeStore),
                fixture.clock);
    }

    // ------------------------------------------------------------ automatic offers

    @Test
    @DisplayName("an automatic discount is one AUTOMATIC discount entry worth exactly the quote's discount")
    void anAutomaticDiscountIsListedAndMatchesTheQuoteDiscount() {
        automaticOrderDiscount("AUTO20", 2_000L);

        Quote quote = price(null);
        AppliedPromotions result = applied.describe(TENANT, quote.quoteId(), null);

        assertThat(quote.discount().minor()).isEqualTo(18_000L);
        assertThat(result.applied()).containsExactly(new Applied(Source.AUTOMATIC, Effect.DISCOUNT, 18_000L));
        assertThat(result.couponOutcome()).as("the cart carries no code").isNull();
    }

    @Test
    @DisplayName("two automatic discounts in different groups are one entry: the customer reads the sum")
    void twoOffersOfTheSameKindAreOneEntry() {
        automaticOrderDiscount("AUTO10", 1_000L);
        fixture.activate(definition(
                "OTHERGROUP",
                Promotion.Scope.ORDER,
                "second-group",
                List.of(),
                List.of(action(1, Promotion.Action.Type.ORDER_FIXED_DISCOUNT, "amountMinor", 5_000L))));

        Quote quote = price(null);
        AppliedPromotions result = applied.describe(TENANT, quote.quoteId(), null);

        assertThat(result.applied()).hasSize(1);
        assertThat(result.applied().get(0).amountMinor())
                .isEqualTo(quote.discount().minor());
        assertThat(quote.discount().minor()).isEqualTo(14_000L);
    }

    @Test
    @DisplayName("an item markup is a SURCHARGE entry and never part of the discount")
    void aMarkupIsASurcharge() {
        var markupConfiguration = new PromotionDbFixture(
                db, TASHKENT_LUNCH, Map.of("pricing.promotion.approval.always_for_markup", false));
        markupConfiguration.activate(new PromotionDefinition(
                "SURGE",
                "Surge",
                Promotion.Kind.MARKUP,
                Promotion.Scope.ITEM,
                "markup",
                false,
                0,
                false,
                null,
                "UZS",
                null,
                null,
                null,
                null,
                Promotion.LoyaltyAccrual.ACCRUE,
                Promotion.LoyaltyRedemption.ALLOW,
                List.of(condition(
                        1,
                        Promotion.Condition.Type.PRODUCT,
                        "productIds",
                        List.of(markupConfiguration.margheritaProduct.toString()))),
                List.of(action(1, Promotion.Action.Type.ITEM_PERCENTAGE_MARKUP, "basisPoints", 1_000L))));
        Quote quote = markupConfiguration.quotes.quote(
                markupConfiguration.cart(Map.of(markupConfiguration.margheritaVariant, 2)));

        var service = new AppliedPromotionService(
                markupConfiguration.pricingStore,
                markupConfiguration.promoCodeStore,
                new PromoCodeEligibilityService(markupConfiguration.promoCodeStore),
                markupConfiguration.clock);
        AppliedPromotions result = service.describe(TENANT, quote.quoteId(), null);

        assertThat(result.applied()).containsExactly(new Applied(Source.AUTOMATIC, Effect.SURCHARGE, 9_000L));
    }

    // ------------------------------------------------------------ the typed code

    @Test
    @DisplayName("a code that wins is a PROMO_CODE entry and the outcome says APPLIED")
    void aWinningCodeIsListedAsTheCustomersOwn() {
        automaticOrderDiscount("AUTO20", 2_000L);
        activateCode("BIG30", 3_000L, 0L);

        Quote quote = price("BIG30");
        AppliedPromotions result = applied.describe(TENANT, quote.quoteId(), "BIG30");

        assertThat(result.applied())
                .as("the exclusive code beat the offer, so the offer is not on the price")
                .containsExactly(new Applied(Source.PROMO_CODE, Effect.DISCOUNT, 27_000L));
        assertThat(result.couponOutcome()).isEqualTo(CouponOutcome.APPLIED);
    }

    @Test
    @DisplayName("a smaller code does not remove a better offer, and the outcome says the offers are better")
    void aSmallerCodeLeavesTheBetterOfferAndSaysWhy() {
        automaticOrderDiscount("AUTO20", 2_000L);
        activateCode("SMALL5", 500L, 0L);

        Quote quote = price("SMALL5");
        AppliedPromotions result = applied.describe(TENANT, quote.quoteId(), "SMALL5");

        assertThat(quote.discount().minor())
                .as("the 20% offer, not the 5% code")
                .isEqualTo(18_000L);
        assertThat(result.applied()).containsExactly(new Applied(Source.AUTOMATIC, Effect.DISCOUNT, 18_000L));
        assertThat(result.couponOutcome()).isEqualTo(CouponOutcome.OFFERS_ARE_BETTER);
    }

    @Test
    @DisplayName("a code whose own condition fails is not applicable, even while an offer applies: not a lost contest")
    void aFailedConditionIsNotALostComparison() {
        automaticOrderDiscount("AUTO20", 2_000L);
        activateCode("BIGBASKET", 3_000L, 500_000L);

        Quote quote = price("BIGBASKET");
        AppliedPromotions result = applied.describe(TENANT, quote.quoteId(), "BIGBASKET");

        assertThat(result.applied()).containsExactly(new Applied(Source.AUTOMATIC, Effect.DISCOUNT, 18_000L));
        assertThat(result.couponOutcome())
                .as("the engine recorded CONDITION_FAILED for it; inferring from the adjustments alone"
                        + " would have said the offers are better")
                .isEqualTo(CouponOutcome.NOT_APPLICABLE);
    }

    @Test
    @DisplayName("a quote priced before verdicts were recorded falls back to the adjustments")
    void aQuoteWithoutARecordedVerdictFallsBackToTheAdjustments() {
        automaticOrderDiscount("AUTO20", 2_000L);
        activateCode("BIGBASKET", 3_000L, 500_000L);
        Quote quote = price("BIGBASKET");
        fixture.jdbc
                .sql("UPDATE pricing.quotes SET calculation_document = calculation_document - 'couponVerdicts' "
                        + "WHERE id = :id")
                .param("id", quote.quoteId())
                .update();

        AppliedPromotions result = applied.describe(TENANT, quote.quoteId(), "BIGBASKET");

        assertThat(result.couponOutcome()).isEqualTo(CouponOutcome.OFFERS_ARE_BETTER);
    }

    @Test
    @DisplayName(
            "a valid code with nothing applying is not applicable, and a code with no offers beside it says so too")
    void aCodeThatDoesNotApplyAndNoOffers() {
        activateCode("BIGBASKET", 3_000L, 500_000L);

        Quote quote = price("BIGBASKET");
        AppliedPromotions result = applied.describe(TENANT, quote.quoteId(), "BIGBASKET");

        assertThat(result.applied()).isEmpty();
        assertThat(result.couponOutcome()).isEqualTo(CouponOutcome.NOT_APPLICABLE);
    }

    @Test
    @DisplayName("a code retired after the cart was priced is not valid any more")
    void aRetiredCodeIsNotValid() {
        var drafted = activateCode("GONE10", 1_000L, 0L);
        Quote quote = price("GONE10");
        codes.retire(TENANT, BRAND, drafted.couponId());

        AppliedPromotions result = applied.describe(TENANT, quote.quoteId(), "GONE10");

        assertThat(result.couponOutcome()).isEqualTo(CouponOutcome.NOT_VALID);
    }

    @Test
    @DisplayName("an unknown code is not valid, and the code itself is never part of the answer")
    void anUnknownCodeIsNotValid() {
        automaticOrderDiscount("AUTO20", 2_000L);
        Quote quote = price(null);

        AppliedPromotions result = applied.describe(TENANT, quote.quoteId(), "NOSUCHCODE");

        assertThat(result.couponOutcome()).isEqualTo(CouponOutcome.NOT_VALID);
        assertThat(result.toString()).doesNotContain("NOSUCHCODE");
    }

    // ------------------------------------------------------------ isolation

    @Test
    @DisplayName("a quote that does not exist, or belongs to another tenant, describes nothing")
    void anotherTenantsQuoteDescribesNothing() {
        automaticOrderDiscount("AUTO20", 2_000L);
        Quote quote = price(null);

        assertThat(applied.describe(OTHER_TENANT, quote.quoteId(), null)).isEqualTo(AppliedPromotions.none());
        assertThat(applied.describe(TENANT, UUID.randomUUID(), null)).isEqualTo(AppliedPromotions.none());
    }

    @Test
    @DisplayName("the answer names no promotion: no id, code or operator name appears in it")
    void theAnswerNamesNoPromotion() {
        automaticOrderDiscount("INTERNAL_MARGIN_TEST", 2_000L);
        Quote quote = price(null);

        String rendered = applied.describe(TENANT, quote.quoteId(), null).toString();

        assertThat(rendered).doesNotContain("INTERNAL_MARGIN_TEST").doesNotContain("Promotion ");
    }

    // ------------------------------------------------------------------ helpers

    private void automaticOrderDiscount(String code, long basisPoints) {
        fixture.activate(definition(
                code,
                Promotion.Scope.ORDER,
                "auto-" + code,
                List.of(),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", basisPoints))));
    }

    private JdbcPromoCodeStore.PromoCodeAuthoringRow activateCode(String code, long basisPoints, long minBasketMinor) {
        var drafted = codes.draft(
                TENANT,
                BRAND,
                new PromoCodeDraft(
                        "Code " + code,
                        code,
                        DiscountShape.PERCENTAGE_OFF_ORDER,
                        basisPoints,
                        null,
                        "UZS",
                        minBasketMinor,
                        List.of(),
                        List.of(),
                        null,
                        100,
                        null,
                        null));
        codes.activate(TENANT, BRAND, drafted.couponId());
        return drafted;
    }

    /** Two Margheritas, 90 000 som, priced for the signed-in customer carrying {@code code}. */
    private Quote price(@Nullable String code) {
        return fixture.quotes.quote(new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                CUSTOMER,
                "STOREFRONT",
                List.of(new QuoteRequest.Line("line-0", fixture.margheritaVariant, 2, List.of())),
                null,
                null,
                code));
    }
}
