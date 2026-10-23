package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.horecaos.platform.pricing.PromotionDbFixture.BRAND;
import static uz.horecaos.platform.pricing.PromotionDbFixture.CUSTOMER;
import static uz.horecaos.platform.pricing.PromotionDbFixture.LOCATION;
import static uz.horecaos.platform.pricing.PromotionDbFixture.OTHER_CUSTOMER;
import static uz.horecaos.platform.pricing.PromotionDbFixture.TASHKENT_LUNCH;
import static uz.horecaos.platform.pricing.PromotionDbFixture.TENANT;
import static uz.horecaos.platform.pricing.PromotionDbFixture.action;
import static uz.horecaos.platform.pricing.PromotionDbFixture.definition;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeOutcome;
import uz.horecaos.platform.fulfillment.api.DeliveryFeePort;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeQuery;
import uz.horecaos.platform.fulfillment.api.PricingAuthority;
import uz.horecaos.platform.fulfillment.api.ResolvedDeliveryCharge;
import uz.horecaos.platform.pricing.api.PromoCodeQueryPort.Eligibility.Reason;
import uz.horecaos.platform.pricing.api.PromoCodeRedemptionPort.RedemptionResult;
import uz.horecaos.platform.pricing.api.PromotionRedemptionSource.Redemption;
import uz.horecaos.platform.pricing.application.BenefitGrantEligibility;
import uz.horecaos.platform.pricing.application.BenefitGrantService;
import uz.horecaos.platform.pricing.application.BenefitGrantService.MintRequest;
import uz.horecaos.platform.pricing.application.BenefitGrantService.MintedGrant;
import uz.horecaos.platform.pricing.application.CompositeProductsLookup;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.PromoCodeAuthoringService.DiscountShape;
import uz.horecaos.platform.pricing.application.PromoCodeEligibilityService;
import uz.horecaos.platform.pricing.application.PromoCodeRedemptionService;
import uz.horecaos.platform.pricing.application.PromotionMetrics;
import uz.horecaos.platform.pricing.application.PromotionRedemptionService;
import uz.horecaos.platform.pricing.application.QuoteService;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.Quote;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcCatalogPricingContext;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcBenefitGrantStore;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * ADR 0140 (benefit grants as a redemption source), through the real quote path and the real checkout claim.
 *
 * <p>{@code BenefitGrantService#redeem} had no caller outside tests, so a code minted for an apology could not
 * be spent. These tests are the other half of {@code BenefitGrantTests}: not "the row can be claimed" but "a
 * customer types the code, the quote prices it, checkout spends it exactly once, and the day close sees it".
 * Margherita is 45 000 som, so two are 90 000.
 */
class BenefitGrantCheckoutTests {

    private static TestDatabase.Handle db;

    private PromotionDbFixture fixture;
    private JdbcBenefitGrantStore grantStore;
    private BenefitGrantService grants;
    private PromoCodeRedemptionService checkout;
    private final ActorRef actor = ActorRef.user(UUID.randomUUID().toString(), "Operator");

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the grant quote path");
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
        fixture.jdbc.sql("TRUNCATE TABLE pricing.benefit_grants").update();
        grantStore = new JdbcBenefitGrantStore(fixture.jdbc);
        grants = new BenefitGrantService(grantStore, fact -> {}, fixture.clock);
        checkout = new PromoCodeRedemptionService(fixture.promoCodeStore, fixture.clock, fixture.metrics, grants);
    }

    // ------------------------------------------------------------------ helpers

    private MintedGrant mint(UUID customer, DiscountShape shape, long value, long minBasket) {
        return grants.mint(
                TENANT,
                BRAND,
                new MintRequest(
                        customer,
                        shape,
                        value,
                        null,
                        shape == DiscountShape.FREE_DELIVERY ? null : "UZS",
                        minBasket,
                        "RECOVERY_CASE",
                        UUID.randomUUID(),
                        null,
                        fixture.clock.instant().plus(Duration.ofDays(30))),
                actor,
                "test");
    }

    private MintedGrant fifteenPercent(UUID customer) {
        return mint(customer, DiscountShape.PERCENTAGE_OFF_ORDER, 1_500L, 0L);
    }

    private QuoteRequest cart(@Nullable UUID account, int pizzas, @Nullable String code) {
        return new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                account,
                "STOREFRONT",
                List.of(new QuoteRequest.Line("line-0", fixture.margheritaVariant, pizzas, List.of())),
                null,
                null,
                code);
    }

    private static List<Quote.Adjustment> grantAdjustments(Quote quote) {
        return quote.adjustments().stream()
                .filter(adjustment -> PricingEngine.GRANT_SOURCE.equals(adjustment.sourceType()))
                .toList();
    }

    private String statusOf(UUID grantId) {
        return fixture.jdbc
                .sql("SELECT status FROM pricing.benefit_grants WHERE id = :id")
                .param("id", grantId)
                .query(String.class)
                .single();
    }

    // -------------------------------------------------------------------- quote

    @Test
    @DisplayName("a presented one-off code is priced into its own customer's quote as a GRANT, not as a promotion")
    void aPresentedGrantCodeIsPricedIntoTheCustomersQuote() {
        MintedGrant grant = fifteenPercent(CUSTOMER);

        Quote quote = fixture.quotes.quote(cart(CUSTOMER, 2, grant.plaintextCode()));

        assertThat(quote.discount().minor()).as("15% of 90 000").isEqualTo(13_500L);
        assertThat(quote.total().minor()).isEqualTo(76_500L);
        assertThat(grantAdjustments(quote)).singleElement().satisfies(adjustment -> {
            assertThat(adjustment.sourceId()).isEqualTo(grant.grantId());
            assertThat(adjustment.type()).isEqualTo(Quote.Adjustment.Type.ORDER_DISCOUNT);
            assertThat(adjustment.amount().minor()).isEqualTo(-13_500L);
        });
        assertThat(quote.adjustments())
                .as("there is no promotion row behind a grant, so no adjustment may claim there is")
                .noneMatch(adjustment -> PricingEngine.PROMOTION_SOURCE.equals(adjustment.sourceType())
                        && adjustment.type() == Quote.Adjustment.Type.ORDER_DISCOUNT);
    }

    @Test
    @DisplayName("a code is the bearer secret of one customer: another customer or a guest typing it gets no discount")
    void aGrantIsNotPricedForAnyoneElse() {
        MintedGrant grant = fifteenPercent(CUSTOMER);

        Quote someoneElse = fixture.quotes.quote(cart(OTHER_CUSTOMER, 2, grant.plaintextCode()));
        Quote aGuest = fixture.quotes.quote(cart(null, 2, grant.plaintextCode()));

        assertThat(someoneElse.discount().minor()).isZero();
        assertThat(aGuest.discount().minor()).isZero();
        assertThat(statusOf(grant.grantId())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("the cart is told why a code cannot be applied: not found for somebody else's, sign-in for a guest")
    void eligibilityNamesTheReason() {
        MintedGrant grant = fifteenPercent(CUSTOMER);
        var eligibility =
                new PromoCodeEligibilityService(fixture.promoCodeStore, new BenefitGrantEligibility(grantStore));
        Instant now = fixture.clock.instant();

        assertThat(eligibility
                        .check(TENANT, BRAND, grant.plaintextCode(), CUSTOMER, now)
                        .isEligible())
                .isTrue();
        assertThat(eligibility
                        .check(TENANT, BRAND, grant.plaintextCode(), CUSTOMER, now)
                        .grantId())
                .isEqualTo(grant.grantId());
        assertThat(eligibility
                        .check(TENANT, BRAND, grant.plaintextCode(), OTHER_CUSTOMER, now)
                        .reason())
                .as("somebody else's grant is answered as a code that is not there")
                .isEqualTo(Reason.CODE_NOT_FOUND);
        assertThat(eligibility
                        .check(TENANT, BRAND, grant.plaintextCode(), null, now)
                        .reason())
                .isEqualTo(Reason.CODE_REQUIRES_ACCOUNT);
        assertThat(eligibility.check(TENANT, BRAND, "NOSUCHCODE", CUSTOMER, now).reason())
                .isEqualTo(Reason.CODE_NOT_FOUND);
        assertThat(eligibility
                        .check(TENANT, UUID.randomUUID(), grant.plaintextCode(), CUSTOMER, now)
                        .reason())
                .as("a grant of another brand is not this brand's code")
                .isEqualTo(Reason.CODE_NOT_FOUND);
        assertThat(eligibility
                        .check(TENANT, BRAND, grant.plaintextCode(), CUSTOMER, now.plus(Duration.ofDays(31)))
                        .reason())
                .isEqualTo(Reason.CODE_EXPIRED);
    }

    @Test
    @DisplayName("a minimum basket the grant carries is a condition: a smaller basket prices no discount")
    void theMinimumBasketOfAGrantIsHonoured() {
        MintedGrant grant = mint(CUSTOMER, DiscountShape.PERCENTAGE_OFF_ORDER, 1_500L, 100_000L);

        Quote below = fixture.quotes.quote(cart(CUSTOMER, 2, grant.plaintextCode()));
        Quote above = fixture.quotes.quote(cart(CUSTOMER, 3, grant.plaintextCode()));

        assertThat(below.discount().minor()).isZero();
        assertThat(above.discount().minor()).as("15% of 135 000").isEqualTo(20_250L);
    }

    @Test
    @DisplayName("a grant is exclusive and comparative like a code: an automatic offer worth more beats it")
    void anAutomaticOfferWorthMoreBeatsTheGrant() {
        fixture.activate(definition(
                "TWENTY",
                Promotion.Scope.ORDER,
                "order",
                List.of(),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 2_000L))));
        MintedGrant grant = fifteenPercent(CUSTOMER);

        Quote quote = fixture.quotes.quote(cart(CUSTOMER, 2, grant.plaintextCode()));

        assertThat(quote.discount().minor())
                .as("20% of 90 000: the offer, not the 15% grant")
                .isEqualTo(18_000L);
        assertThat(grantAdjustments(quote)).isEmpty();
        var claim = grants.claimForQuote(
                TENANT, BRAND, quote.quoteId(), UUID.randomUUID(), CUSTOMER, fixture.clock.instant());
        assertThat(claim.outcome())
                .as("the grant did not price this quote, so checkout has nothing to claim and the code is kept")
                .isEqualTo(BenefitGrantService.Claim.Outcome.NONE);
        assertThat(statusOf(grant.grantId())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a grant worth more than the automatic offers wins alone, and the offers do not combine with it")
    void aGrantWorthMoreWinsAlone() {
        fixture.activate(definition(
                "TEN",
                Promotion.Scope.ORDER,
                "order",
                List.of(),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L))));
        MintedGrant grant = fifteenPercent(CUSTOMER);

        Quote quote = fixture.quotes.quote(cart(CUSTOMER, 2, grant.plaintextCode()));

        assertThat(quote.discount().minor()).as("15% of 90 000, not 25%").isEqualTo(13_500L);
        assertThat(grantAdjustments(quote)).hasSize(1);
        assertThat(quote.adjustments())
                .noneMatch(adjustment -> PricingEngine.PROMOTION_SOURCE.equals(adjustment.sourceType())
                        && adjustment.type() == Quote.Adjustment.Type.ORDER_DISCOUNT);
    }

    @Test
    @DisplayName("a free-delivery grant waives the fee the zone resolved, as a GRANT benefit")
    void aFreeDeliveryGrantWaivesTheFee() {
        MintedGrant grant = mint(CUSTOMER, DiscountShape.FREE_DELIVERY, 0L, 0L);
        DeliveryFeePort zone = new DeliveryFeePort() {
            @Override
            public ResolvedDeliveryCharge resolve(DeliveryFeeQuery query) {
                return new ResolvedDeliveryCharge(
                        DeliveryFeeOutcome.RESOLVED,
                        "UZS",
                        15_000L,
                        0L,
                        null,
                        null,
                        UUID.randomUUID(),
                        1,
                        UUID.randomUUID(),
                        1,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of());
            }

            @Override
            public ResolvedDeliveryCharge preview(DeliveryFeeQuery query) {
                return resolve(query);
            }
        };
        QuoteService quotes = new QuoteService(
                fixture.pricingStore,
                new PricingEngine(),
                new JdbcCatalogPricingContext(fixture.jdbc, "uz"),
                new JdbcSalesChannelStore(fixture.jdbc),
                zone,
                PromotionTestSupport.resolver(fixture.jdbc, fixture.promoCodeStore),
                fixture.clock,
                new FakeConfigurationResolver(Map.of()),
                CompositeProductsLookup.none(),
                PromotionMetrics.none());

        Quote quote = quotes.quote(new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                CUSTOMER,
                "STOREFRONT",
                List.of(new QuoteRequest.Line("line-0", fixture.margheritaVariant, 2, List.of())),
                null,
                new QuoteRequest.Delivery(new GeoPoint(41.31, 69.27), PricingAuthority.HORECAOS),
                grant.plaintextCode()));

        assertThat(quote.fees().minor()).isZero();
        assertThat(quote.adjustments()).anySatisfy(adjustment -> {
            assertThat(adjustment.type()).isEqualTo(Quote.Adjustment.Type.DELIVERY_FEE_BENEFIT);
            assertThat(adjustment.sourceType()).isEqualTo(PricingEngine.GRANT_SOURCE);
            assertThat(adjustment.sourceId()).isEqualTo(grant.grantId());
        });
    }

    // ----------------------------------------------------------------- checkout

    @Test
    @DisplayName("checkout spends the grant with a conditional update, records what it took off, and only once")
    void checkoutClaimsTheGrantOnce() {
        MintedGrant grant = fifteenPercent(CUSTOMER);
        Quote first = fixture.quotes.quote(cart(CUSTOMER, 2, grant.plaintextCode()));
        Quote second = fixture.quotes.quote(cart(CUSTOMER, 2, grant.plaintextCode()));
        UUID order = UUID.randomUUID();

        RedemptionResult won =
                checkout.reserveForQuote(TENANT, BRAND, first.quoteId(), order, CUSTOMER, fixture.clock.instant());
        RedemptionResult lost = checkout.reserveForQuote(
                TENANT, BRAND, second.quoteId(), UUID.randomUUID(), CUSTOMER, fixture.clock.instant());

        assertThat(won.result()).isEqualTo(RedemptionResult.Result.REDEEMED);
        assertThat(lost.result())
                .as("a second checkout was priced with the same code before the first spent it")
                .isEqualTo(RedemptionResult.Result.CODE_NOT_ELIGIBLE);
        assertThat(lost.isRefused()).isTrue();
        var row = fixture.jdbc
                .sql("SELECT status, order_id, quote_id, redeemed_discount_minor, redeemed_currency "
                        + "FROM pricing.benefit_grants WHERE id = :id")
                .param("id", grant.grantId())
                .query()
                .singleRow();
        assertThat(row.get("status")).isEqualTo("REDEEMED");
        assertThat(row.get("order_id")).isEqualTo(order);
        assertThat(row.get("quote_id")).isEqualTo(first.quoteId());
        assertThat(row.get("redeemed_discount_minor")).isEqualTo(13_500L);
        assertThat(String.valueOf(row.get("redeemed_currency")).trim()).isEqualTo("UZS");

        Quote after = fixture.quotes.quote(cart(CUSTOMER, 2, grant.plaintextCode()));
        assertThat(after.discount().minor())
                .as("a spent grant prices nothing for the next cart")
                .isZero();
        assertThat(new PromoCodeEligibilityService(fixture.promoCodeStore, new BenefitGrantEligibility(grantStore))
                        .check(TENANT, BRAND, grant.plaintextCode(), CUSTOMER, fixture.clock.instant())
                        .reason())
                .isEqualTo(Reason.CODE_ALREADY_USED);
    }

    @Test
    @DisplayName("a retried checkout finds its own claim and claims nothing twice")
    void aRetriedCheckoutIsIdempotent() {
        MintedGrant grant = fifteenPercent(CUSTOMER);
        Quote quote = fixture.quotes.quote(cart(CUSTOMER, 2, grant.plaintextCode()));
        UUID order = UUID.randomUUID();

        checkout.reserveForQuote(TENANT, BRAND, quote.quoteId(), order, CUSTOMER, fixture.clock.instant());
        RedemptionResult again =
                checkout.reserveForQuote(TENANT, BRAND, quote.quoteId(), order, CUSTOMER, fixture.clock.instant());

        assertThat(again.result()).isEqualTo(RedemptionResult.Result.REDEEMED);
        assertThat(statusOf(grant.grantId())).isEqualTo("REDEEMED");
    }

    @Test
    @DisplayName("a checkout that fails after claiming gives the grant back, with nothing of the claim left on it")
    void aFailedCheckoutReleasesTheGrant() {
        MintedGrant grant = fifteenPercent(CUSTOMER);
        Quote quote = fixture.quotes.quote(cart(CUSTOMER, 2, grant.plaintextCode()));
        checkout.reserveForQuote(TENANT, BRAND, quote.quoteId(), UUID.randomUUID(), CUSTOMER, fixture.clock.instant());
        assertThat(statusOf(grant.grantId())).isEqualTo("REDEEMED");

        boolean released = checkout.release(TENANT, quote.quoteId());

        assertThat(released).isTrue();
        var row = fixture.jdbc
                .sql("SELECT status, redeemed_at, order_id, quote_id, redeemed_discount_minor "
                        + "FROM pricing.benefit_grants WHERE id = :id")
                .param("id", grant.grantId())
                .query()
                .singleRow();
        assertThat(row.get("status")).isEqualTo("ACTIVE");
        assertThat(row.get("redeemed_at")).isNull();
        assertThat(row.get("order_id")).isNull();
        assertThat(row.get("quote_id")).isNull();
        assertThat(row.get("redeemed_discount_minor")).isNull();
        Quote retry = fixture.quotes.quote(cart(CUSTOMER, 2, grant.plaintextCode()));
        assertThat(retry.discount().minor()).as("the customer can try again").isEqualTo(13_500L);
    }

    @Test
    @DisplayName("two checkouts racing for one grant have exactly one winner")
    void twoCheckoutsRacingForOneGrantHaveOneWinner() throws Exception {
        MintedGrant grant = fifteenPercent(CUSTOMER);
        List<Quote> quotes = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            quotes.add(fixture.quotes.quote(cart(CUSTOMER, 2, grant.plaintextCode())));
        }
        ExecutorService pool = Executors.newFixedThreadPool(quotes.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<RedemptionResult>> results = new ArrayList<>();
            for (Quote quote : quotes) {
                Callable<RedemptionResult> attempt = () -> {
                    go.await();
                    return checkout.reserveForQuote(
                            TENANT, BRAND, quote.quoteId(), UUID.randomUUID(), CUSTOMER, fixture.clock.instant());
                };
                results.add(pool.submit(attempt));
            }
            go.countDown();
            long redeemed = 0;
            for (Future<RedemptionResult> result : results) {
                if (result.get(30, TimeUnit.SECONDS).result() == RedemptionResult.Result.REDEEMED) {
                    redeemed++;
                }
            }
            assertThat(redeemed).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(statusOf(grant.grantId())).isEqualTo("REDEEMED");
    }

    // ---------------------------------------------------------------- amendment

    @Test
    @DisplayName("an amendment keeps pricing the grant its order spent, and restates what it took off in place")
    void anAmendmentKeepsTheGrant() {
        MintedGrant grant = fifteenPercent(CUSTOMER);
        Quote placed = fixture.quotes.quote(cart(CUSTOMER, 2, grant.plaintextCode()));
        UUID order = UUID.randomUUID();
        checkout.reserveForQuote(TENANT, BRAND, placed.quoteId(), order, CUSTOMER, fixture.clock.instant());

        // The window closes and a line is added: the code is no longer presented, the order carries its own spend.
        fixture.clock.advanceBy(Duration.ofDays(40));
        QuoteRequest amendment = new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                CUSTOMER,
                "STOREFRONT",
                List.of(new QuoteRequest.Line("line-0", fixture.margheritaVariant, 3, List.of())),
                null,
                null,
                null,
                order,
                new QuoteRequest.Frame(null, null, null, placed.quoteId(), placed.createdAt()));
        Quote amended = fixture.quotes.quote(amendment);

        assertThat(amended.discount().minor())
                .as("15% of 135 000, after the grant's own window closed: the order still holds it")
                .isEqualTo(20_250L);
        assertThat(grantAdjustments(amended)).hasSize(1);

        boolean restated = checkout.restateForOrder(TENANT, order, amended.quoteId());

        assertThat(restated).isTrue();
        long recorded = fixture.jdbc
                .sql("SELECT redeemed_discount_minor FROM pricing.benefit_grants WHERE id = :id")
                .param("id", grant.grantId())
                .query(Long.class)
                .single();
        assertThat(recorded).isEqualTo(20_250L);
        assertThat(statusOf(grant.grantId()))
                .as("an amendment never spends a second time")
                .isEqualTo("REDEEMED");
    }

    // --------------------------------------------------------------- the report

    @Test
    @DisplayName("a spent grant reaches the redemption source as a GRANT row with no promotion, labelled by its origin")
    void aSpentGrantIsASourceOfTheRedemptionFact() {
        MintedGrant grant = fifteenPercent(CUSTOMER);
        Quote quote = fixture.quotes.quote(cart(CUSTOMER, 2, grant.plaintextCode()));
        UUID order = UUID.randomUUID();
        checkout.reserveForQuote(TENANT, BRAND, quote.quoteId(), order, CUSTOMER, fixture.clock.instant());
        var source = new PromotionRedemptionService(fixture.promotionStore, fixture.metrics, grantStore);

        List<Redemption> rows = source.redeemedBetween(
                TENANT,
                fixture.clock.instant().minus(Duration.ofHours(1)),
                fixture.clock.instant().plus(Duration.ofHours(1)));

        assertThat(rows).singleElement().satisfies(row -> {
            assertThat(row.kind()).isEqualTo(Redemption.Kind.GRANT);
            assertThat(row.grantId()).isEqualTo(grant.grantId());
            assertThat(row.redemptionId()).isEqualTo(grant.grantId());
            assertThat(row.promotionId()).isNull();
            assertThat(row.promotionCode())
                    .as("a label that names the origin and never the code word")
                    .isEqualTo("GRANT:RECOVERY_CASE")
                    .doesNotContain(grant.plaintextCode());
            assertThat(row.orderId()).isEqualTo(order);
            assertThat(row.discountMinor()).isEqualTo(13_500L);
            assertThat(row.currency()).isEqualTo("UZS");
        });
    }

    @Test
    @DisplayName("the claim carries the counters: a granted code is counted as a grant redemption, not a coupon")
    void theCounterNamesTheSource() {
        MintedGrant grant = fifteenPercent(CUSTOMER);
        Quote quote = fixture.quotes.quote(cart(CUSTOMER, 2, grant.plaintextCode()));

        checkout.reserveForQuote(TENANT, BRAND, quote.quoteId(), UUID.randomUUID(), CUSTOMER, fixture.clock.instant());

        assertThat(fixture.metrics
                        .registry()
                        .counter(PromotionMetrics.REDEMPTIONS, "source", "grant", "outcome", "claimed")
                        .count())
                .isEqualTo(1.0);
    }
}
