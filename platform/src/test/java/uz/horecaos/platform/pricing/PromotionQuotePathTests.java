package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static uz.horecaos.platform.pricing.PromotionDbFixture.BRAND;
import static uz.horecaos.platform.pricing.PromotionDbFixture.CUSTOMER;
import static uz.horecaos.platform.pricing.PromotionDbFixture.LOCATION;
import static uz.horecaos.platform.pricing.PromotionDbFixture.OTHER_BRAND;
import static uz.horecaos.platform.pricing.PromotionDbFixture.OTHER_CUSTOMER;
import static uz.horecaos.platform.pricing.PromotionDbFixture.OTHER_TENANT;
import static uz.horecaos.platform.pricing.PromotionDbFixture.OTHER_TENANT_BRAND;
import static uz.horecaos.platform.pricing.PromotionDbFixture.TASHKENT_LUNCH;
import static uz.horecaos.platform.pricing.PromotionDbFixture.TENANT;
import static uz.horecaos.platform.pricing.PromotionDbFixture.action;
import static uz.horecaos.platform.pricing.PromotionDbFixture.condition;
import static uz.horecaos.platform.pricing.PromotionDbFixture.definition;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import uz.horecaos.platform.pricing.application.PromotionEvaluator.Verdict;
import uz.horecaos.platform.pricing.application.PromotionSimulationService;
import uz.horecaos.platform.pricing.domain.Promotion;
import uz.horecaos.platform.pricing.domain.PromotionDefinition;
import uz.horecaos.platform.pricing.domain.Quote;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.support.TestDatabase;

/**
 * ADR 0140 through the real quote path: the Context items that were inert or wrong
 * because the resolver fed the evaluator neutral values.
 *
 * <p>Each of these is a test the record says must exist before an automatic
 * promotion can reach a customer. The evaluator tests cannot see them, because
 * they hand the evaluator a context the real resolver never built: product and
 * category conditions that could not match, a "lunch" window judged in UTC, a
 * dine-in cart priced as pickup, a first-order customer who never was one.
 */
class PromotionQuotePathTests {

    private static TestDatabase.Handle db;

    private PromotionDbFixture fixture;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the promotion quote path");
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
    }

    // ------------------------------------------------- item 1: membership wired

    @Test
    @DisplayName("a category promotion matches a product in a child category, through the real quote path")
    void aCategoryConditionMatchesThroughTheQuotePath() {
        fixture.activate(definition(
                "PIZZA10",
                Promotion.Scope.ITEM,
                "menu",
                List.of(condition(
                        1,
                        Promotion.Condition.Type.CATEGORY,
                        "categoryIds",
                        List.of(fixture.pizzaCategory.toString()))),
                List.of(action(1, Promotion.Action.Type.ITEM_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L))));

        Quote pizzaCart = fixture.quotes.quote(fixture.cart(Map.of(fixture.margheritaVariant, 2)));
        Quote drinkCart = fixture.quotes.quote(fixture.cart(Map.of(fixture.colaVariant, 2)));

        assertThat(pizzaCart.discount().minor())
                .as("Margherita sits in Classic, which sits under Pizza: the ancestor matches")
                .isEqualTo(9_000L);
        assertThat(pizzaCart.total().minor()).isEqualTo(81_000L);
        assertThat(drinkCart.discount().minor())
                .as("Cola is in Drinks, not under Pizza")
                .isZero();
    }

    @Test
    @DisplayName("a product and a variant condition match, and an exclude operand removes the line")
    void productVariantAndExcludeConditionsMatch() {
        fixture.activate(definition(
                "NOCOLA",
                Promotion.Scope.ITEM,
                "g1",
                List.of(condition(
                        1,
                        Promotion.Condition.Type.PRODUCT,
                        "productIds",
                        List.of(fixture.colaProduct.toString()),
                        "exclude",
                        true)),
                List.of(action(1, Promotion.Action.Type.ITEM_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L))));

        Quote quote = fixture.quotes.quote(fixture.cart(Map.of(fixture.margheritaVariant, 1, fixture.colaVariant, 1)));

        assertThat(quote.discount().minor())
                .as("10% of the pizza only: the Cola is excluded")
                .isEqualTo(4_500L);
    }

    // --------------------------------------------- item 2: timezone, mode, first order

    @Test
    @DisplayName("a Tashkent lunch window fires at local lunchtime and not at UTC lunchtime")
    void aLunchWindowIsJudgedInTheLocationsTimezone() {
        fixture.activate(definition(
                "LUNCH",
                Promotion.Scope.ORDER,
                "lunch",
                List.of(condition(
                        1,
                        Promotion.Condition.Type.TIME_OF_DAY,
                        "fromMinuteOfDay",
                        12 * 60L,
                        "toMinuteOfDay",
                        15 * 60L)),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L))));

        // 07:30Z is 12:30 in Tashkent (UTC+5). The old resolver derived the minute from UTC (450), so
        // this lunch rule fired five hours early and missed the real lunch hour entirely.
        fixture.clock.set(Instant.parse("2026-10-01T07:30:00Z"));
        assertThat(fixture.quotes
                        .quote(fixture.cart(Map.of(fixture.margheritaVariant, 2)))
                        .discount()
                        .minor())
                .as("12:30 local")
                .isEqualTo(9_000L);

        // 12:30Z is 17:30 in Tashkent: UTC "lunchtime", local teatime.
        fixture.clock.set(Instant.parse("2026-10-01T12:30:00Z"));
        assertThat(fixture.quotes
                        .quote(fixture.cart(Map.of(fixture.margheritaVariant, 2)))
                        .discount()
                        .minor())
                .as("12:30 UTC is 17:30 local, outside the window")
                .isZero();
    }

    @Test
    @DisplayName("a weekday condition reads the local weekday")
    void aWeekdayIsTheLocalWeekday() {
        // 2026-10-01 is a Thursday in Tashkent; 22:30Z is already Friday 03:30 there.
        fixture.activate(definition(
                "THURSDAYS",
                Promotion.Scope.ORDER,
                "days",
                List.of(condition(1, Promotion.Condition.Type.DAY_OF_WEEK, "daysOfWeek", List.of(4))),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L))));

        fixture.clock.set(Instant.parse("2026-10-01T07:30:00Z"));
        assertThat(fixture.quotes
                        .quote(fixture.cart(Map.of(fixture.margheritaVariant, 1)))
                        .discount()
                        .minor())
                .isEqualTo(4_500L);

        fixture.clock.set(Instant.parse("2026-10-01T22:30:00Z"));
        assertThat(fixture.quotes
                        .quote(fixture.cart(Map.of(fixture.margheritaVariant, 1)))
                        .discount()
                        .minor())
                .as("22:30Z is already Friday in Tashkent")
                .isZero();
    }

    @Test
    @DisplayName("a dine-in cart is priced as dine-in, not as pickup")
    void aDineInCartIsNotPricedAsPickup() {
        fixture.activate(definition(
                "TABLE5",
                Promotion.Scope.ORDER,
                "table",
                List.of(condition(
                        1, Promotion.Condition.Type.FULFILLMENT_MODE, "fulfillmentModes", List.of("DINE_IN"))),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 500L))));

        QuoteRequest.Frame dineIn = new QuoteRequest.Frame(null, null, "DINE_IN", null, null);
        Quote atTable =
                fixture.quotes.quote(fixture.cartFor(null, "STOREFRONT", Map.of(fixture.margheritaVariant, 2), dineIn));
        Quote noFrame = fixture.quotes.quote(fixture.cart(Map.of(fixture.margheritaVariant, 2)));

        assertThat(atTable.discount().minor())
                .as("5% of 90 000, because the cart said DINE_IN")
                .isEqualTo(4_500L);
        assertThat(noFrame.discount().minor())
                .as("without the cart's mode the quote falls back to PICKUP, as it always did")
                .isZero();
    }

    @Test
    @DisplayName("FIRST_ORDER is true for a customer with no earlier order, false after, and never for a guest")
    void firstOrderIsResolvedFromRealHistory() {
        fixture.activate(definition(
                "WELCOME",
                Promotion.Scope.ORDER,
                "first",
                List.of(condition(1, Promotion.Condition.Type.FIRST_ORDER)),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L))));

        fixture.brandOrders.set(0);
        assertThat(priceFor(CUSTOMER).discount().minor())
                .as("a brand-new customer")
                .isEqualTo(9_000L);
        assertThat(priceFor(null).discount().minor())
                .as("a guest has no history to count")
                .isZero();

        fixture.brandOrders.set(2);
        assertThat(priceFor(CUSTOMER).discount().minor())
                .as("two earlier orders")
                .isZero();
    }

    @Test
    @DisplayName("an ORDER_SEQUENCE NTH condition matches the position this order takes")
    void theNthOrderIsResolvedFromRealHistory() {
        var nth = definition(
                "THIRD",
                Promotion.Scope.ORDER,
                "third",
                List.of(condition(
                        1, Promotion.Condition.Type.ORDER_SEQUENCE, "mode", "NTH", "n", 3L, "basis", "BRAND")),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L)));
        // A FIRST or NTH promotion must declare a per-customer limit of one, or the validator refuses it.
        fixture.activate(new PromotionDefinition(
                nth.code(),
                nth.name(),
                nth.kind(),
                nth.scope(),
                nth.stackingGroup(),
                false,
                0,
                false,
                null,
                "UZS",
                null,
                null,
                null,
                1,
                Promotion.LoyaltyAccrual.ACCRUE,
                Promotion.LoyaltyRedemption.ALLOW,
                nth.conditions(),
                nth.actions()));

        fixture.brandOrders.set(2);
        assertThat(priceFor(CUSTOMER).discount().minor()).as("the third order").isEqualTo(9_000L);
        fixture.brandOrders.set(3);
        assertThat(priceFor(CUSTOMER).discount().minor())
                .as("the fourth is not the third")
                .isZero();
    }

    @Test
    @DisplayName("CUSTOMER_SEGMENT reads the audiences the account belongs to")
    void segmentsComeFromTheAudiencePort() {
        UUID audience = UUID.randomUUID();
        fixture.audiences.put(CUSTOMER, Set.of(audience.toString()));
        fixture.activate(definition(
                "VIPS",
                Promotion.Scope.ORDER,
                "vip",
                List.of(condition(
                        1, Promotion.Condition.Type.CUSTOMER_SEGMENT, "segments", List.of(audience.toString()))),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L))));

        assertThat(priceFor(CUSTOMER).discount().minor()).isEqualTo(9_000L);
        assertThat(priceFor(OTHER_CUSTOMER).discount().minor())
                .as("not in the audience")
                .isZero();
    }

    @Test
    @DisplayName("a CHANNEL_TYPE condition reads the channel's system type, so all app orders need no code list")
    void channelTypeReadsTheSystemType() {
        fixture.activate(definition(
                "APPONLY",
                Promotion.Scope.ORDER,
                "app",
                List.of(condition(1, Promotion.Condition.Type.CHANNEL_TYPE, "channelTypes", List.of("IOS", "ANDROID"))),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L))));

        Quote app = fixture.quotes.quote(fixture.cartFor(null, "APP", Map.of(fixture.margheritaVariant, 2), null));
        Quote web = fixture.quotes.quote(fixture.cart(Map.of(fixture.margheritaVariant, 2)));

        assertThat(app.discount().minor()).isEqualTo(9_000L);
        assertThat(web.discount().minor()).isZero();
    }

    // ------------------------------------------------------- evidence and hash

    @Test
    @DisplayName(
            "a quote records the promotion inputs it was priced with, and the promotions that applied at their versions")
    void aQuoteRecordsItsPromotionInputs() {
        var promotion = fixture.activate(definition(
                "CLICK5",
                Promotion.Scope.ORDER,
                "pay",
                List.of(condition(1, Promotion.Condition.Type.PAYMENT_METHOD, "paymentMethodCodes", List.of("CLICK"))),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 500L))));

        QuoteRequest.Frame click = new QuoteRequest.Frame(null, "CLICK", "PICKUP", null, null);
        Quote quote =
                fixture.quotes.quote(fixture.cartFor(null, "STOREFRONT", Map.of(fixture.margheritaVariant, 2), click));

        Map<String, Object> inputs = fixture.pricingStore
                .findPromotionInputs(TENANT, quote.quoteId())
                .orElseThrow();
        assertThat(inputs)
                .containsEntry("serviceInstant", TASHKENT_LUNCH.toString())
                .containsEntry("fulfillmentMode", "PICKUP")
                .containsEntry("channelType", "WEB")
                .containsEntry("paymentMethodCode", "CLICK")
                .containsEntry("timeZone", "Asia/Tashkent");
        assertThat(String.valueOf(inputs.get("appliedPromotions")))
                .contains(promotion.id().toString())
                .contains("definitionVersion=" + promotion.definitionVersion());

        Quote cash = fixture.quotes.quote(fixture.cartFor(
                null,
                "STOREFRONT",
                Map.of(fixture.margheritaVariant, 2),
                new QuoteRequest.Frame(null, "CASH", "PICKUP", null, null)));
        assertThat(cash.discount().minor())
                .as("cash does not earn the Click discount")
                .isZero();
        assertThat(cash.contextHash()).isNotEqualTo(quote.contextHash());
        assertThat(quote.discount().minor()).isEqualTo(4_500L);
    }

    // ------------------------------------------------------------- simulator

    @Test
    @DisplayName(
            "the simulator and a real quote agree on totals, adjustments and hash, and the simulator writes nothing")
    void theSimulatorAgreesWithARealQuoteAndWritesNothing() {
        fixture.activate(definition(
                "P1",
                Promotion.Scope.ITEM,
                "menu",
                List.of(condition(
                        1,
                        Promotion.Condition.Type.CATEGORY,
                        "categoryIds",
                        List.of(fixture.pizzaCategory.toString()))),
                List.of(action(1, Promotion.Action.Type.ITEM_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L))));
        fixture.activate(definition(
                "P2",
                Promotion.Scope.ITEM,
                "menu",
                List.of(condition(
                        1,
                        Promotion.Condition.Type.CATEGORY,
                        "categoryIds",
                        List.of(fixture.pizzaCategory.toString()))),
                List.of(action(1, Promotion.Action.Type.ITEM_FIXED_DISCOUNT, "amountMinor", 5_000L))));
        fixture.activate(definition(
                "P3",
                Promotion.Scope.ORDER,
                "pay",
                List.of(condition(1, Promotion.Condition.Type.PAYMENT_METHOD, "paymentMethodCodes", List.of("CLICK"))),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 500L))));

        long quotesBefore = count("pricing.quotes");
        var simulated = fixture.simulation.simulate(
                TENANT,
                BRAND,
                new PromotionSimulationService.Request(
                        LOCATION,
                        "STOREFRONT",
                        null,
                        "CLICK",
                        null,
                        List.of(
                                new PromotionSimulationService.Line("line-0", fixture.margheritaVariant, 2, List.of()),
                                new PromotionSimulationService.Line("line-1", fixture.colaVariant, 1, List.of())),
                        null,
                        null,
                        null,
                        null,
                        Map.of()));
        assertThat(count("pricing.quotes")).as("a simulation stores no quote").isEqualTo(quotesBefore);
        assertThat(count("pricing.promotion_redemptions")).isZero();

        Quote real = fixture.quotes.quote(fixture.cartFor(
                null,
                "STOREFRONT",
                orderedMap(fixture.margheritaVariant, 2, fixture.colaVariant, 1),
                new QuoteRequest.Frame(null, "CLICK", null, null, null)));

        assertThat(simulated.result().total()).isEqualTo(real.total());
        assertThat(simulated.result().discount()).isEqualTo(real.discount());
        assertThat(simulated.result().tax()).isEqualTo(real.tax());
        assertThat(simulated.result().adjustments()).isEqualTo(real.adjustments());
        assertThat(simulated.result().contextHash())
                .as("the same inputs hash the same, because there is one path and not two")
                .isEqualTo(real.contextHash());
        assertThat(simulated.result().promotionTrace())
                .extracting(entry -> entry.code() + ":" + entry.verdict())
                .contains("P1:LOST_TO", "P2:APPLIED", "P3:APPLIED");
    }

    @Test
    @DisplayName("the simulator tries an unsaved candidate and replays a recorded definition version")
    void theSimulatorTriesACandidateAndReplaysAVersion() {
        var live = fixture.activate(definition(
                "TEN",
                Promotion.Scope.ORDER,
                "order",
                List.of(),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L))));

        var request = new PromotionSimulationService.Request(
                LOCATION,
                "STOREFRONT",
                null,
                null,
                null,
                List.of(new PromotionSimulationService.Line("line-0", fixture.margheritaVariant, 2, List.of())),
                null,
                null,
                null,
                definition(
                        "TWENTY",
                        Promotion.Scope.ORDER,
                        "candidate",
                        List.of(),
                        List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 2_000L))),
                Map.of());
        var withCandidate = fixture.simulation.simulate(TENANT, BRAND, request);
        assertThat(withCandidate.result().discount().minor())
                .as("the candidate's 20% and the live 10% are in different groups, so they combine: 30%")
                .isEqualTo(27_000L);

        // Edit the live promotion: suspend, replace, validate, activate -> definition version 2.
        authoringEdit(live.id(), 3_000L);
        var replay = new PromotionSimulationService.Request(
                LOCATION,
                "STOREFRONT",
                null,
                null,
                null,
                request.lines(),
                null,
                null,
                null,
                null,
                Map.of(live.id(), live.definitionVersion()));
        var current = fixture.simulation.simulate(
                TENANT,
                BRAND,
                new PromotionSimulationService.Request(
                        LOCATION, "STOREFRONT", null, null, null, request.lines(), null, null, null, null, Map.of()));
        var replayed = fixture.simulation.simulate(TENANT, BRAND, replay);

        assertThat(current.result().discount().minor())
                .as("the edited 30% rule")
                .isEqualTo(27_000L);
        assertThat(replayed.result().discount().minor())
                .as("version 1 replayed: the 10% the old quotes were priced under")
                .isEqualTo(9_000L);
    }

    // ------------------------------------------------------- limits and markup

    @Test
    @DisplayName("a limited promotion at its limit does not apply and the trace says why")
    void aLimitedPromotionAtItsLimitIsTraced() {
        var limited = fixture.activate(new PromotionDefinition(
                "FIRST100",
                "First hundred",
                Promotion.Kind.DISCOUNT,
                Promotion.Scope.ORDER,
                "limit",
                false,
                0,
                false,
                null,
                "UZS",
                null,
                null,
                100,
                null,
                Promotion.LoyaltyAccrual.ACCRUE,
                Promotion.LoyaltyRedemption.ALLOW,
                List.of(),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 1_000L))));

        assertThat(fixture.quotes
                        .quote(fixture.cart(Map.of(fixture.margheritaVariant, 2)))
                        .discount()
                        .minor())
                .as("a slot is left")
                .isEqualTo(9_000L);

        fixture.jdbc
                .sql("UPDATE pricing.promotions SET consumed_count = 100 WHERE id = :id")
                .param("id", limited.id())
                .update();

        Quote quote = fixture.quotes.quote(fixture.cart(Map.of(fixture.margheritaVariant, 2)));
        assertThat(quote.discount().minor()).as("the hundredth slot is gone").isZero();
        var trace = fixture.simulation
                .simulate(
                        TENANT,
                        BRAND,
                        new PromotionSimulationService.Request(
                                LOCATION,
                                "STOREFRONT",
                                null,
                                null,
                                null,
                                List.of(new PromotionSimulationService.Line(
                                        "line-0", fixture.margheritaVariant, 2, List.of())),
                                null,
                                null,
                                null,
                                null,
                                Map.of()))
                .result()
                .promotionTrace();
        assertThat(trace)
                .filteredOn(entry -> entry.promotionId().equals(limited.id()))
                .singleElement()
                .satisfies(entry -> assertThat(entry.verdict()).isEqualTo(Verdict.LIMIT_REACHED));
    }

    @Test
    @DisplayName("an item markup raises the stored quote, and the ITEM_MARKUP adjustment survives the database check")
    void anItemMarkupIsStoredOnTheQuote() {
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

        assertThat(quote.total().minor()).as("2 x (45 000 + 4 500)").isEqualTo(99_000L);
        assertThat(quote.discount().minor()).isZero();
        var snapshot = markupConfiguration
                .pricingStore
                .findQuoteSnapshot(TENANT, quote.quoteId())
                .orElseThrow();
        assertThat(snapshot.adjustments()).anySatisfy(adjustment -> {
            assertThat(adjustment.adjustmentType()).isEqualTo("ITEM_MARKUP");
            assertThat(adjustment.amountMinor()).isEqualTo(9_000L);
        });
    }

    // ------------------------------------------------------- tenant isolation

    @Test
    @DisplayName(
            "a promotion of another brand or another tenant never prices this brand's cart, and never resolves for it")
    void promotionsNeverResolveAcrossABrandOrATenant() {
        insertActiveOrderPromotion(TENANT, OTHER_BRAND, "OTHERBRAND");
        insertActiveOrderPromotion(OTHER_TENANT, OTHER_TENANT_BRAND, "OTHERTENANT");
        var mine = fixture.activate(definition(
                "MINE",
                Promotion.Scope.ORDER,
                "mine",
                List.of(),
                List.of(action(1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", 100L))));

        Quote quote = fixture.quotes.quote(fixture.cart(Map.of(fixture.margheritaVariant, 2)));

        assertThat(quote.discount().minor())
                .as("only the brand's own 1%, not the other brand's or the other tenant's 50%")
                .isEqualTo(900L);
        UUID foreign = fixture.jdbc
                .sql("SELECT id FROM pricing.promotions WHERE code = 'OTHERBRAND'")
                .query(UUID.class)
                .single();
        assertThat(fixture.promotionStore.find(TENANT, BRAND, foreign)).isEmpty();
        assertThat(fixture.promotionStore.find(TENANT, OTHER_BRAND, foreign)).isPresent();
        assertThat(fixture.promotionStore.find(OTHER_TENANT, BRAND, mine.id())).isEmpty();
        assertThat(fixture.promotionStore.list(TENANT, BRAND))
                .extracting(row -> row.definition().code())
                .containsExactly("MINE");
    }

    // ---------------------------------------------------------------- helpers

    private Quote priceFor(@Nullable UUID account) {
        return fixture.quotes.quote(fixture.cartFor(account, "STOREFRONT", Map.of(fixture.margheritaVariant, 2), null));
    }

    private void authoringEdit(UUID id, long basisPoints) {
        var row = fixture.promotionStore.find(TENANT, BRAND, id).orElseThrow();
        var suspended = fixture.authoring.suspend(TENANT, BRAND, id, row.version());
        var edited = fixture.authoring.update(
                TENANT,
                BRAND,
                id,
                suspended.version(),
                definition(
                        row.definition().code(),
                        Promotion.Scope.ORDER,
                        "order",
                        List.of(),
                        List.of(action(
                                1, Promotion.Action.Type.ORDER_PERCENTAGE_DISCOUNT, "basisPoints", basisPoints))));
        var validated = fixture.authoring.validate(TENANT, BRAND, id, edited.version());
        fixture.authoring.activate(TENANT, BRAND, id, validated.promotion().version(), "edit");
    }

    private void insertActiveOrderPromotion(UUID tenant, UUID brand, String code) {
        UUID id = UUID.randomUUID();
        fixture.jdbc
                .sql("""
                INSERT INTO pricing.promotions (id, tenant_id, brand_id, code, name, scope, status, stacking_group,
                    exclusive, priority, requires_coupon, currency, valid_from, definition_version, version,
                    validated_at, activated_at)
                VALUES (:id, :tenantId, :brandId, :code, :code, 'ORDER', 'ACTIVE', 'g', false, 0, false, 'UZS',
                    now() - interval '1 day', 1, 1, now(), now())
                """)
                .param("id", id)
                .param("tenantId", tenant)
                .param("brandId", brand)
                .param("code", code)
                .update();
        fixture.jdbc
                .sql("""
                INSERT INTO pricing.promotion_actions (promotion_id, sequence, tenant_id, brand_id, action_type, attributes_json)
                VALUES (:id, 1, :tenantId, :brandId, 'ORDER_PERCENTAGE_DISCOUNT', '{"basisPoints": 5000}'::jsonb)
                """)
                .param("id", id)
                .param("tenantId", tenant)
                .param("brandId", brand)
                .update();
    }

    private long count(String table) {
        return fixture.jdbc
                .sql("SELECT count(*) FROM " + table)
                .query(Long.class)
                .single();
    }

    private static Map<UUID, Integer> orderedMap(UUID first, int firstQuantity, UUID second, int secondQuantity) {
        Map<UUID, Integer> map = new java.util.LinkedHashMap<>();
        map.put(first, firstQuantity);
        map.put(second, secondQuantity);
        return map;
    }
}
