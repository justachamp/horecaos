package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.horecaos.platform.pricing.PromotionDbFixture.TASHKENT_LUNCH;
import static uz.horecaos.platform.pricing.PromotionDbFixture.action;
import static uz.horecaos.platform.pricing.PromotionDbFixture.definition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeOutcome;
import uz.horecaos.platform.fulfillment.api.DeliveryFeePort;
import uz.horecaos.platform.fulfillment.api.DeliveryFeeQuery;
import uz.horecaos.platform.fulfillment.api.PricingAuthority;
import uz.horecaos.platform.fulfillment.api.ResolvedDeliveryCharge;
import uz.horecaos.platform.pricing.application.CompositeProductsLookup;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.PromotionMetrics;
import uz.horecaos.platform.pricing.application.QuoteService;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.Quote;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcCatalogPricingContext;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.GeoPoint;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * Which subtotal a zone's free-delivery threshold and minimum basket are judged against
 * (ADR 0037, steps 7 and 8; ADR 0140 Context item 5 and its open input).
 *
 * <p>ADR 0037 decides it: both compare against the <em>post-discount</em> goods subtotal. The
 * engine already does, and the fee resolver was handed the pre-discount figure through
 * {@code DeliveryFeeQuery.goodsSubtotalMinor} that nothing in fulfillment reads. These
 * tests pin the basis through the real quote path, and the last one is the one that
 * failed first: a resolver that is told a goods subtotal is a resolver somebody can come to
 * rely on, and the figure it was told was the wrong one.
 */
class DeliveryFeeThresholdBasisTests {

    private static final long FEE = 15_000L;
    private static final long FREE_FROM = 80_000L;
    private static final long MINIMUM = 70_000L;

    private static TestDatabase.Handle db;

    private PromotionDbFixture fixture;
    private final List<DeliveryFeeQuery> asked = new ArrayList<>();

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the quote path");
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
        asked.clear();
    }

    private QuoteService quotesWithZone() {
        DeliveryFeePort zone = new DeliveryFeePort() {
            @Override
            public ResolvedDeliveryCharge resolve(DeliveryFeeQuery query) {
                asked.add(query);
                return charge();
            }

            @Override
            public ResolvedDeliveryCharge preview(DeliveryFeeQuery query) {
                return resolve(query);
            }
        };
        return new QuoteService(
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
    }

    private static ResolvedDeliveryCharge charge() {
        return new ResolvedDeliveryCharge(
                DeliveryFeeOutcome.RESOLVED,
                "UZS",
                FEE,
                0L,
                MINIMUM,
                FREE_FROM,
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

    private QuoteRequest deliveryCart(int margheritas) {
        return new QuoteRequest(
                PromotionDbFixture.TENANT,
                PromotionDbFixture.BRAND,
                PromotionDbFixture.LOCATION,
                null,
                "STOREFRONT",
                List.of(new QuoteRequest.Line("line-0", fixture.margheritaVariant, margheritas, List.of())),
                null,
                new QuoteRequest.Delivery(new GeoPoint(41.31, 69.27), PricingAuthority.HORECAOS));
    }

    private void twentyPercentOffTheOrder() {
        fixture.activate(definition(
                "TWENTY",
                Promotion.Scope.ORDER,
                "order",
                List.of(),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 2_000L))));
    }

    @Test
    @DisplayName("a basket that clears the free-delivery threshold only before its discount still pays the fee")
    void theFreeDeliveryThresholdIsJudgedAfterTheDiscount() {
        twentyPercentOffTheOrder();

        // 2 x 45 000 = 90 000 gross, 72 000 after 20% off. The threshold is 80 000.
        Quote quote = quotesWithZone().quote(deliveryCart(2));

        assertThat(quote.discount().minor()).isEqualTo(18_000L);
        assertThat(quote.fees().minor())
                .as("90 000 clears 80 000 and 72 000 does not: ADR 0037 judges the post-discount subtotal")
                .isEqualTo(FEE);
        assertThat(quote.adjustments())
                .noneMatch(adjustment -> "FREE_DELIVERY_THRESHOLD".equals(adjustment.descriptionCode()));
    }

    @Test
    @DisplayName("a basket that clears the free-delivery threshold after its discount is waived")
    void aBasketThatClearsTheThresholdAfterTheDiscountPaysNoFee() {
        twentyPercentOffTheOrder();

        // 3 x 45 000 = 135 000 gross, 108 000 after 20% off.
        Quote quote = quotesWithZone().quote(deliveryCart(3));

        assertThat(quote.fees().minor()).isZero();
        assertThat(quote.adjustments())
                .anyMatch(adjustment -> "FREE_DELIVERY_THRESHOLD".equals(adjustment.descriptionCode()));
    }

    @Test
    @DisplayName("the minimum basket is judged after the discount too: 72 000 clears 70 000, 54 000 does not")
    void theMinimumBasketIsJudgedAfterTheDiscount() {
        // One pizza, 45 000 gross: below the 70 000 minimum with or without the discount.
        Quote belowEither = quotesWithZone().quote(deliveryCart(1));
        assertThat(belowEither.deliveryShortfallMinor()).isEqualTo(MINIMUM - 45_000L);

        twentyPercentOffTheOrder();
        // 2 x 45 000 = 90 000 gross (clears 70 000), 72 000 after 20% off (still clears).
        Quote clears = quotesWithZone().quote(deliveryCart(2));
        assertThat(clears.deliveryShortfallMinor()).isNull();
    }

    @Test
    @DisplayName("the resolver is not handed a goods subtotal: the engine judges thresholds, and a figure the resolver"
            + " ignores must not be the pre-discount one")
    void theResolverIsNotHandedThePreDiscountSubtotal() {
        twentyPercentOffTheOrder();

        quotesWithZone().quote(deliveryCart(2));

        assertThat(asked).hasSize(1);
        assertThat(asked.get(0).goodsSubtotalMinor())
                .as("90 000 is the pre-discount goods subtotal; the threshold basis is the engine's 72 000")
                .isNotEqualTo(90_000L)
                .isZero();
    }
}
