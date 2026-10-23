package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.horecaos.platform.pricing.PromotionDbFixture.BRAND;
import static uz.horecaos.platform.pricing.PromotionDbFixture.TASHKENT_LUNCH;
import static uz.horecaos.platform.pricing.PromotionDbFixture.TENANT;
import static uz.horecaos.platform.pricing.PromotionDbFixture.action;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.pricing.application.CompositeProductsLookup;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.PromotionMetrics;
import uz.horecaos.platform.pricing.application.QuoteService;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.PromotionDefinition;
import uz.horecaos.platform.pricing.domain.Quote;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcCatalogPricingContext;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * ADR 0140's order-level markup (a service charge), and the key that keeps it dark: {@code
 * pricing.order_markup.enabled}.
 *
 * <p>The engine can price a service charge, and nobody can reach it until finance and counsel have answered
 * how a service line is taxed and carried on the fiscal receipt. These tests are what "dark" means: with the
 * key off the validator refuses to author one, and a definition that is already stored is never handed to the
 * engine, so no total moves; with the key on the charge reaches the stored quote, through the CHECKs V0670
 * widened.
 */
class OrderMarkupFlagTests {

    private static final String FLAG = "pricing.order_markup.enabled";
    private static final String MARKUP_APPROVAL = "pricing.promotion.approval.always_for_markup";

    private static TestDatabase.Handle db;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the order markup");
        db = TestDatabase.migrated();
    }

    @AfterAll
    static void stopDatabase() {
        if (db != null) {
            db.close();
        }
    }

    private static PromotionDefinition serviceCharge(String code, long basisPoints) {
        return new PromotionDefinition(
                code,
                "Service " + code,
                Promotion.Kind.MARKUP,
                Promotion.Scope.ORDER,
                "service",
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
                List.of(),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_MARKUP, "basisPoints", basisPoints)));
    }

    private static PromotionDbFixture fixture(boolean enabled) {
        return new PromotionDbFixture(db, TASHKENT_LUNCH, Map.of(FLAG, enabled, MARKUP_APPROVAL, false));
    }

    @Test
    @DisplayName("with the key off the rule checker refuses to author an order-level markup")
    void withTheKeyOffAnOrderMarkupCannotBeAuthored() {
        PromotionDbFixture fixture = fixture(false);

        var drafted = fixture.authoring.create(TENANT, BRAND, serviceCharge("SERVICE10", 1_000L));
        var validated = fixture.authoring.validate(TENANT, BRAND, drafted.id(), drafted.version());

        assertThat(validated.report().isValid()).isFalse();
        assertThat(validated.report().refusals())
                .extracting(issue -> issue.code())
                .contains("ORDER_MARKUP_NOT_AVAILABLE");
    }

    @Test
    @DisplayName("with the key on it validates, activates, and the quote carries a SERVICE_CHARGE line inside the fees")
    void withTheKeyOnTheChargeReachesTheStoredQuote() {
        PromotionDbFixture fixture = fixture(true);
        fixture.activate(serviceCharge("SERVICE10", 1_000L));

        Quote quote = fixture.quotes.quote(fixture.cart(Map.of(fixture.margheritaVariant, 2)));

        // 2 x 45 000 = 90 000 goods; 10% is 9 000. A pickup cart has no delivery fee.
        assertThat(quote.fees().minor()).isEqualTo(9_000L);
        assertThat(quote.total().minor()).isEqualTo(99_000L);
        assertThat(quote.total().minor())
                .isEqualTo(quote.subtotal().minor()
                        + quote.tax().minor()
                        + quote.fees().minor()
                        - quote.discount().minor());
        assertThat(fixture.jdbc
                        .sql("SELECT line_type FROM pricing.quote_lines WHERE quote_id = :id ORDER BY line_type")
                        .param("id", quote.quoteId())
                        .query(String.class)
                        .list())
                .as("stored through the widened CHECKs")
                .containsExactly("ITEM", "SERVICE_CHARGE");
        assertThat(fixture.jdbc
                        .sql("SELECT amount_minor FROM pricing.quote_adjustments "
                                + "WHERE quote_id = :id AND adjustment_type = 'ORDER_MARKUP'")
                        .param("id", quote.quoteId())
                        .query(Long.class)
                        .single())
                .isEqualTo(9_000L);
    }

    @Test
    @DisplayName("a definition stored while the key was on is never handed to the engine once it is off")
    void switchingTheKeyBackOffStopsAStoredChargeFromApplying() {
        PromotionDbFixture on = fixture(true);
        on.activate(serviceCharge("SERVICE10", 1_000L));
        QuoteService withTheKeyOff = new QuoteService(
                on.pricingStore,
                new PricingEngine(),
                new JdbcCatalogPricingContext(on.jdbc, "uz"),
                new JdbcSalesChannelStore(on.jdbc),
                new uz.horecaos.platform.fulfillment.api.DeliveryFeePort() {
                    @Override
                    public uz.horecaos.platform.fulfillment.api.ResolvedDeliveryCharge resolve(
                            uz.horecaos.platform.fulfillment.api.DeliveryFeeQuery query) {
                        throw new UnsupportedOperationException("a pickup cart resolves no delivery fee");
                    }

                    @Override
                    public uz.horecaos.platform.fulfillment.api.ResolvedDeliveryCharge preview(
                            uz.horecaos.platform.fulfillment.api.DeliveryFeeQuery query) {
                        throw new UnsupportedOperationException("a pickup cart resolves no delivery fee");
                    }
                },
                PromotionTestSupport.resolver(on.jdbc, on.promoCodeStore),
                on.clock,
                new FakeConfigurationResolver(Map.of(FLAG, false)),
                CompositeProductsLookup.none(),
                PromotionMetrics.none());
        assertThat(on.quotes
                        .quote(on.cart(Map.of(on.margheritaVariant, 2)))
                        .fees()
                        .minor())
                .as("sanity: the charge applies while the key is on")
                .isEqualTo(9_000L);

        Quote quote = withTheKeyOff.quote(on.cart(Map.of(on.margheritaVariant, 2)));

        assertThat(quote.fees().minor()).isZero();
        assertThat(quote.total().minor()).isEqualTo(90_000L);
        assertThat(quote.lines()).extracting(Quote.QuoteLine::type).doesNotContain(Quote.LineType.SERVICE_CHARGE);
    }
}
