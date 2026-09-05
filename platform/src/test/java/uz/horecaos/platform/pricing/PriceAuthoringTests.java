package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.pricing.application.CatalogPricingContext;
import uz.horecaos.platform.pricing.application.PriceAuthoringService;
import uz.horecaos.platform.pricing.application.PriceAuthoringService.AssignmentScope;
import uz.horecaos.platform.pricing.application.PriceQueryService;
import uz.horecaos.platform.pricing.application.PriceableType;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.PricingEngine.TaxMode;
import uz.horecaos.platform.pricing.application.PromoCodeEligibilityService;
import uz.horecaos.platform.pricing.application.QuoteService;
import uz.horecaos.platform.pricing.domain.Quote;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcCatalogPricingContext;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * Price authoring, end to end (ADR 0018).
 *
 * <p>Every price in this suite is written by the production authoring path and
 * none by a fixture, which is the whole point: until this existed a brand could
 * only be priced by somebody with a database client, so no cart in the platform
 * priced without one.
 *
 * <p>Runs against a real database because the properties under test only appear
 * there — which of two concurrent activations wins, whether superseding a price
 * survives {@code ux_price_current}, and whether a quote taken afterwards
 * actually resolves the book that was activated.
 */
class PriceAuthoringTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-24T09:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcPricingStore pricingStore;
    private PriceAuthoringService authoring;
    private PriceQueryService query;
    private QuoteService quotes;
    private MutableClock clock;

    private UUID catalogId;
    private UUID burgerVariant;
    private UUID cheeseOption;
    private UUID otherBrandVariant;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for price authoring tests");
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
        DataSource dataSource = db.dataSource();
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("TRUNCATE TABLE pricing.quote_adjustments, pricing.quote_lines, pricing.quotes, "
                        + "pricing.prices, pricing.price_book_assignments, pricing.price_books, "
                        + "pricing.tax_profiles CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, "
                        + "catalog.location_offerings, catalog.translations, catalog.catalog_products, "
                        + "catalog.modifier_options, catalog.modifier_groups, "
                        + "catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = new MutableClock(NOW);
        pricingStore = new JdbcPricingStore(jdbc, JsonMapper.builder().build());
        JdbcSalesChannelStore channelStore = new JdbcSalesChannelStore(jdbc);
        CatalogPricingContext catalog = new JdbcCatalogPricingContext(jdbc, "uz");

        authoring = new PriceAuthoringService(pricingStore, catalog, channelStore, clock);
        query = new PriceQueryService(pricingStore, channelStore, clock);

        // The real resolver, so a cart travels the production path. Never
        // consulted: every cart here is a collection, and a request with no
        // destination does not enter fee resolution at all.
        var deliveryFees = new uz.horecaos.platform.fulfillment.application.DeliveryFeeResolver(
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore(jdbc),
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryTariffStore(jdbc),
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryFeeResolutionStore(
                        jdbc, JsonMapper.builder().build()),
                (origin, destination, installationId) -> java.util.Optional.empty(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        var promoCodeStore = new JdbcPromoCodeStore(jdbc, JsonMapper.builder().build());
        quotes = new QuoteService(
                pricingStore,
                new PricingEngine(),
                catalog,
                channelStore,
                deliveryFees,
                promoCodeStore,
                new PromoCodeEligibilityService(promoCodeStore),
                clock);

        seedTenancyAndCatalog();
    }

    @Test
    @DisplayName("an operator can price a brand from nothing and a cart then prices")
    void aBrandPricedFromNothingCanTakeAnOrder() {
        // Exactly what a new brand's first day looks like, and what nothing in
        // production code could do before: no price book, no price, no tax profile.
        var drafted = authoring.create(TENANT, BRAND, newBook("Main menu", 0));
        assertThat(drafted.status()).isEqualTo(PriceAuthoringService.Status.DRAFT);

        authoring.assign(TENANT, BRAND, drafted.id(), AssignmentScope.BRAND, null, assignment(0));
        var priced = authoring.setPrice(TENANT, BRAND, drafted.id(), PriceableType.VARIANT, burgerVariant, 50_000L);
        authoring.setTaxProfile(TENANT, BRAND, "UZ", TaxMode.INCLUSIVE, 1200);

        assertThat(catchThrowable(() -> quotes.quote(cart(Map.of(burgerVariant, 1)))))
                .as("a draft prices nothing; the book must be activated first")
                .isInstanceOf(QuoteService.NoPriceBookException.class);

        var activated = authoring.activate(TENANT, BRAND, drafted.id(), priced.version());
        assertThat(activated.status()).isEqualTo(PriceAuthoringService.Status.ACTIVE);

        var quote = quotes.quote(cart(Map.of(burgerVariant, 2)));

        // Two burgers at 50,000 som each. The customer pays 100,000, and the 12%
        // VAT is inside that rather than added to it.
        assertThat(quote.total().minor()).isEqualTo(100_000L);
        assertThat(quote.tax().minor()).isEqualTo(10_714L);
        assertThat(quote.subtotal().minor() + quote.tax().minor()).isEqualTo(100_000L);
        assertThat(quote.lines())
                .singleElement()
                .satisfies(line -> assertThat(line.descriptionSnapshot()).isEqualTo("Qo'y burger"));
    }

    @Test
    @DisplayName("a modifier priced through authoring reaches the cart's total")
    void anAuthoredModifierPriceIsCharged() {
        UUID book = liveBrandBook(50_000L);
        authoring.setPrice(TENANT, BRAND, book, PriceableType.MODIFIER_OPTION, cheeseOption, 7_000L);

        var quote = quotes.quote(cartWithModifier(burgerVariant, cheeseOption));

        assertThat(quote.total().minor()).isEqualTo(57_000L);
        assertThat(quote.adjustments()).anySatisfy(adjustment -> {
            assertThat(adjustment.type()).isEqualTo(Quote.Adjustment.Type.MODIFIER);
            assertThat(adjustment.amount().minor()).isEqualTo(7_000L);
        });
    }

    @Test
    @DisplayName("a price change moves the book's version, so a new quote hashes differently")
    void aPriceChangeChangesTheContextHash() {
        UUID book = liveBrandBook(50_000L);
        var before = quotes.quote(cart(Map.of(burgerVariant, 1)));

        clock.advance(Duration.ofMinutes(1));
        authoring.setPrice(TENANT, BRAND, book, PriceableType.VARIANT, burgerVariant, 60_000L);
        var after = quotes.quote(cart(Map.of(burgerVariant, 1)));

        // The hash pins the price book by id and version and never by the amounts
        // themselves. A price edited underneath a version that stood still would
        // leave these two quotes hashing identically with different totals, and
        // the hash's whole promise is that it covers every input the total
        // depends on.
        assertThat(before.total().minor()).isEqualTo(50_000L);
        assertThat(after.total().minor()).isEqualTo(60_000L);
        assertThat(after.contextHash()).isNotEqualTo(before.contextHash());
    }

    @Test
    @DisplayName("a quote already issued is honoured at the price it was shown")
    void aPriceChangeDoesNotReachIntoAnIssuedQuote() {
        UUID book = liveBrandBook(50_000L);
        var issued = quotes.quote(cart(Map.of(burgerVariant, 1)));

        clock.advance(Duration.ofMinutes(1));
        authoring.setPrice(TENANT, BRAND, book, PriceableType.VARIANT, burgerVariant, 90_000L);

        var acceptance = quotes.accept(TENANT, issued.quoteId(), issued.contextHash());

        // ADR 0018's promise, and the reason activation deliberately leaves live
        // quotes alone: a customer at the payment step pays what they were shown,
        // for the fifteen minutes the quote lasts.
        assertThat(acceptance.outcome()).isEqualTo(QuoteService.Acceptance.Outcome.ACCEPTED);
        var total = Objects.requireNonNull(acceptance.total(), "an ACCEPTED outcome always carries a total");
        assertThat(total.minor()).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("changing a price keeps the old one, closed")
    void changingAPriceSupersedesRatherThanOverwrites() {
        UUID book = liveBrandBook(50_000L);

        clock.advance(Duration.ofMinutes(5));
        authoring.setPrice(TENANT, BRAND, book, PriceableType.VARIANT, burgerVariant, 60_000L);

        // The closed row is the only evidence of what yesterday's quote was priced
        // from; an UPDATE in place would destroy it.
        assertThat(jdbc.sql("SELECT amount_minor FROM pricing.prices ORDER BY valid_from")
                        .query(Long.class)
                        .list())
                .containsExactly(50_000L, 60_000L);
        assertThat(jdbc.sql("SELECT count(*) FROM pricing.prices WHERE valid_until IS NULL")
                        .query(Long.class)
                        .single())
                .as("ux_price_current allows exactly one open row per priceable")
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a price corrected before it was ever in force is amended, not superseded")
    void aPriceNotYetInForceIsAmendedInPlace() {
        UUID book = liveBrandBook(50_000L);
        authoring.setPrice(TENANT, BRAND, book, PriceableType.VARIANT, burgerVariant, 55_000L);

        // Same instant, so the first row was in force for nobody. Closing it would
        // violate ck_price_window, which requires the close to come strictly after
        // the open, and there is no history worth keeping.
        assertThat(jdbc.sql("SELECT amount_minor FROM pricing.prices")
                        .query(Long.class)
                        .list())
                .containsExactly(55_000L);
    }

    @Test
    @DisplayName("two operators activating the same draft: only one wins")
    void concurrentActivationsSettleOnce() {
        var drafted = authoring.create(TENANT, BRAND, newBook("Main menu", 0));
        authoring.assign(TENANT, BRAND, drafted.id(), AssignmentScope.BRAND, null, assignment(0));
        var ready = authoring.setPrice(TENANT, BRAND, drafted.id(), PriceableType.VARIANT, burgerVariant, 50_000L);

        // Both read the same version and both press activate. The conditional
        // update is what settles it, not the status check in the service: at the
        // store level neither caller has looked at the row in between, which is
        // exactly the race two browser tabs produce.
        assertThat(pricingStore.activatePriceBook(TENANT, BRAND, drafted.id(), ready.version(), NOW))
                .isTrue();
        assertThat(pricingStore.activatePriceBook(TENANT, BRAND, drafted.id(), ready.version(), NOW))
                .isFalse();

        assertThat(jdbc.sql("SELECT version FROM pricing.price_books WHERE id = :id")
                        .param("id", drafted.id())
                        .query(Integer.class)
                        .single())
                .isEqualTo(ready.version() + 1);
    }

    @Test
    @DisplayName("activating against a version somebody else moved is refused")
    void activatingWithAStaleVersionLoses() {
        var drafted = authoring.create(TENANT, BRAND, newBook("Main menu", 0));
        authoring.assign(TENANT, BRAND, drafted.id(), AssignmentScope.BRAND, null, assignment(0));
        var stale = authoring.setPrice(TENANT, BRAND, drafted.id(), PriceableType.VARIANT, burgerVariant, 50_000L);

        // A colleague corrects a price between the read and the activation. The
        // activator is no longer approving the book they looked at.
        clock.advance(Duration.ofMinutes(1));
        authoring.setPrice(TENANT, BRAND, drafted.id(), PriceableType.VARIANT, burgerVariant, 80_000L);

        assertThat(catchThrowable(() -> authoring.activate(TENANT, BRAND, drafted.id(), stale.version())))
                .isInstanceOf(OptimisticLockingFailureException.class);
        assertThat(authoring.require(TENANT, BRAND, drafted.id()).status())
                .isEqualTo(PriceAuthoringService.Status.DRAFT);
    }

    @Test
    @DisplayName("a price book that prices nothing cannot be activated")
    void anEmptyPriceBookCannotBeActivated() {
        var drafted = authoring.create(TENANT, BRAND, newBook("Empty", 0));
        authoring.assign(TENANT, BRAND, drafted.id(), AssignmentScope.BRAND, null, assignment(0));
        var current = authoring.require(TENANT, BRAND, drafted.id());

        // It would win resolution for its scope and then refuse every cart under
        // it with ITEM_NOT_PRICED, which is worse than having no book at all.
        assertThat(catchThrowable(() -> authoring.activate(TENANT, BRAND, drafted.id(), current.version())))
                .isInstanceOf(PriceAuthoringService.PriceBookLifecycleException.class);
    }

    @Test
    @DisplayName("two books tied for the same scope at the same priority are refused")
    void anExactlyTiedPriceBookIsRefusedAtActivation() {
        liveBrandBook(50_000L);

        var second = authoring.create(TENANT, BRAND, newBook("Also main", 0));
        authoring.assign(TENANT, BRAND, second.id(), AssignmentScope.BRAND, null, assignment(0));
        var ready = authoring.setPrice(TENANT, BRAND, second.id(), PriceableType.VARIANT, burgerVariant, 70_000L);

        // Resolution would still settle this — by an id nobody chose. An operator
        // getting a price they did not pick, with nothing anywhere saying why, is
        // worse than a refusal naming the fix.
        assertThat(catchThrowable(() -> authoring.activate(TENANT, BRAND, second.id(), ready.version())))
                .isInstanceOf(PriceAuthoringService.PriceBookLifecycleException.class);
    }

    @Test
    @DisplayName("a higher-priority book overrides the base one and is not a tie")
    void aHigherPriorityBookActivatesAndWins() {
        liveBrandBook(50_000L);

        var promotion = authoring.create(TENANT, BRAND, newBook("Ramadan", 10));
        authoring.assign(TENANT, BRAND, promotion.id(), AssignmentScope.BRAND, null, assignment(0));
        var ready = authoring.setPrice(TENANT, BRAND, promotion.id(), PriceableType.VARIANT, burgerVariant, 40_000L);
        authoring.activate(TENANT, BRAND, promotion.id(), ready.version());

        // The tie check refuses ambiguity, not overlap: priority is the sanctioned
        // way to put one book in front of another.
        assertThat(quotes.quote(cart(Map.of(burgerVariant, 1))).total().minor()).isEqualTo(40_000L);
    }

    @Test
    @DisplayName("a book assigned to one branch prices that branch")
    void aLocationAssignedBookPricesThatBranch() {
        liveBrandBook(50_000L);

        var branchBook = authoring.create(TENANT, BRAND, newBook("Main street", 0));
        authoring.assign(TENANT, BRAND, branchBook.id(), AssignmentScope.LOCATION, LOCATION, assignment(0));
        var ready = authoring.setPrice(TENANT, BRAND, branchBook.id(), PriceableType.VARIANT, burgerVariant, 45_000L);
        authoring.activate(TENANT, BRAND, branchBook.id(), ready.version());

        assertThat(quotes.quote(cart(Map.of(burgerVariant, 1))).total().minor()).isEqualTo(45_000L);
    }

    @Test
    @DisplayName("assigning the same book to the same scope twice does not duplicate the row")
    void repeatedAssignmentIsIdempotent() {
        var drafted = authoring.create(TENANT, BRAND, newBook("Main menu", 0));
        authoring.assign(TENANT, BRAND, drafted.id(), AssignmentScope.BRAND, null, assignment(0));
        authoring.assign(TENANT, BRAND, drafted.id(), AssignmentScope.BRAND, null, assignment(5));

        assertThat(jdbc.sql("SELECT priority FROM pricing.price_book_assignments")
                        .query(Integer.class)
                        .list())
                .as("a second row saying the same thing only ties against itself")
                .containsExactly(5);
    }

    @Test
    @DisplayName("a price cannot be written for another brand's variant")
    void aPriceCannotBeWrittenForAnotherBrandsVariant() {
        var drafted = authoring.create(TENANT, BRAND, newBook("Main menu", 0));

        // priceable_id carries no foreign key — it points at two different tables
        // depending on the row — so without this check the price would be written
        // and simply never match anything.
        assertThat(catchThrowable(() -> authoring.setPrice(
                        TENANT, BRAND, drafted.id(), PriceableType.VARIANT, otherBrandVariant, 50_000L)))
                .isInstanceOf(PriceAuthoringService.UnknownPriceableException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM pricing.prices")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("a channel assignment naming no real channel is refused")
    void anAssignmentToAnUnknownChannelIsRefused() {
        var drafted = authoring.create(TENANT, BRAND, newBook("Aggregator", 0));

        assertThat(catchThrowable(() -> authoring.assign(
                        TENANT, BRAND, drafted.id(), AssignmentScope.CHANNEL, UUID.randomUUID(), assignment(0))))
                .isInstanceOf(PriceAuthoringService.UnknownAssignmentScopeException.class);
    }

    @Test
    @DisplayName("an exclusive tax profile is refused rather than stored")
    void anExclusiveTaxProfileIsRefused() {
        // The engine implements inclusive VAT only. Storing this would look saved
        // and then fail every quote the brand takes.
        assertThat(catchThrowable(() -> authoring.setTaxProfile(TENANT, BRAND, "UZ", TaxMode.EXCLUSIVE, 1200)))
                .isInstanceOf(PricingEngine.UnsupportedTaxModeException.class);
        assertThat(jdbc.sql("SELECT count(*) FROM pricing.tax_profiles")
                        .query(Long.class)
                        .single())
                .isZero();
    }

    @Test
    @DisplayName("a changed VAT rate supersedes the old one and leaves the menu price alone")
    void aChangedVatRateSupersedesTheOldProfile() {
        liveBrandBook(50_000L);
        assertThat(quotes.quote(cart(Map.of(burgerVariant, 1))).tax().minor()).isEqualTo(5_357L);

        clock.advance(Duration.ofMinutes(5));
        authoring.setTaxProfile(TENANT, BRAND, "UZ", TaxMode.INCLUSIVE, 0);

        var quote = quotes.quote(cart(Map.of(burgerVariant, 1)));

        // Tax is extracted from the menu price, never added to it, so a rate
        // change moves what the merchant remits and not what the customer pays.
        assertThat(quote.total().minor()).isEqualTo(50_000L);
        assertThat(quote.tax().minor()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM pricing.tax_profiles WHERE valid_until IS NULL")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM pricing.tax_profiles")
                        .query(Long.class)
                        .single())
                .isEqualTo(2L);
    }

    @Test
    @DisplayName("the price book list is ranked by priority then name, and stays within its own brand")
    void listPriceBooksRanksByPriorityThenNameAndStaysBrandScoped() {
        var base = authoring.create(TENANT, BRAND, newBook("Main menu", 0));
        var promo = authoring.create(TENANT, BRAND, newBook("Ramadan", 10));
        authoring.create(TENANT, OTHER_BRAND, newBook("Someone else's book", 99));

        List<PriceQueryService.PriceBookSummary> books = query.priceBooks(TENANT, BRAND);

        assertThat(books)
                .extracting(PriceQueryService.PriceBookSummary::priceBookId)
                .containsExactly(promo.id(), base.id());
        assertThat(books)
                .filteredOn(b -> b.priceBookId().equals(base.id()))
                .singleElement()
                .satisfies(b -> {
                    assertThat(b.name()).isEqualTo("Main menu");
                    assertThat(b.currency()).isEqualTo("UZS");
                    assertThat(b.status()).isEqualTo("DRAFT");
                    assertThat(b.priority()).isZero();
                });
    }

    @Test
    @DisplayName("resolving prices reads the active book's amounts for the requested variants")
    void resolvePricesReadsAmountsFromTheResolvedBook() {
        UUID book = liveBrandBook(50_000L);
        authoring.setPrice(TENANT, BRAND, book, PriceableType.MODIFIER_OPTION, cheeseOption, 7_000L);

        var variantResult =
                query.resolvePrices(TENANT, BRAND, LOCATION, null, PriceableType.VARIANT, Set.of(burgerVariant));
        assertThat(variantResult.priceBookId()).isEqualTo(book);
        assertThat(variantResult.currency()).isEqualTo("UZS");
        assertThat(variantResult.amountsMinor()).containsEntry(burgerVariant, 50_000L);

        var modifierResult =
                query.resolvePrices(TENANT, BRAND, LOCATION, null, PriceableType.MODIFIER_OPTION, Set.of(cheeseOption));
        assertThat(modifierResult.amountsMinor()).containsEntry(cheeseOption, 7_000L);
    }

    @Test
    @DisplayName("a brand with no price book yet resolves to an empty, displayable result rather than an error")
    void resolvePricesIsEmptyWhenNoBookResolves() {
        var resolved = query.resolvePrices(TENANT, BRAND, LOCATION, null, PriceableType.VARIANT, Set.of(burgerVariant));

        assertThat(resolved.priceBookId()).isNull();
        assertThat(resolved.currency()).isNull();
        assertThat(resolved.amountsMinor()).isEmpty();
    }

    @Test
    @DisplayName("resolving prices for another brand never sees this brand's book")
    void resolvePricesStaysBrandScoped() {
        liveBrandBook(50_000L);

        var resolved = query.resolvePrices(
                TENANT, OTHER_BRAND, LOCATION, null, PriceableType.VARIANT, Set.of(otherBrandVariant));

        assertThat(resolved.priceBookId()).isNull();
        assertThat(resolved.amountsMinor()).isEmpty();
    }

    // ------------------------------------------------------------------ fixtures

    /** A brand-wide active book with one priced burger, which most tests start from. */
    private UUID liveBrandBook(long burgerAmountMinor) {
        var drafted = authoring.create(TENANT, BRAND, newBook("Main menu", 0));
        authoring.assign(TENANT, BRAND, drafted.id(), AssignmentScope.BRAND, null, assignment(0));
        var ready = authoring.setPrice(
                TENANT, BRAND, drafted.id(), PriceableType.VARIANT, burgerVariant, burgerAmountMinor);
        authoring.activate(TENANT, BRAND, drafted.id(), ready.version());
        authoring.setTaxProfile(TENANT, BRAND, "UZ", TaxMode.INCLUSIVE, 1200);
        return drafted.id();
    }

    private static PriceAuthoringService.NewPriceBook newBook(String name, int priority) {
        return new PriceAuthoringService.NewPriceBook(name, "UZS", null, null, priority);
    }

    private static PriceAuthoringService.Assignment assignment(int priority) {
        return new PriceAuthoringService.Assignment(priority, null, null);
    }

    private QuoteRequest cart(Map<UUID, Integer> quantities) {
        List<QuoteRequest.Line> lines = new java.util.ArrayList<>();
        int index = 0;
        for (var entry : quantities.entrySet()) {
            lines.add(new QuoteRequest.Line("line-" + index++, entry.getKey(), entry.getValue(), List.of()));
        }
        return new QuoteRequest(TENANT, BRAND, LOCATION, null, "STOREFRONT", lines, null);
    }

    private QuoteRequest cartWithModifier(UUID variantId, UUID optionId) {
        return new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                null,
                "STOREFRONT",
                List.of(new QuoteRequest.Line("line-0", variantId, 1, List.of(optionId))),
                null);
    }

    private void seedTenancyAndCatalog() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'authoring-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent',
                        'ACTIVE', 0)
                """).param("id", TENANT).update();
        seedBrand(BRAND, "MAIN", "main");
        seedBrand(OTHER_BRAND, "OTHER", "other");
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'main-01', 'Main',
                        'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type,
                    display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'Storefront', 'ACTIVE')
                """).param("id", UUID.randomUUID()).param("tenantId", TENANT).update();

        catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        burgerVariant = seedProduct(BRAND, catalogId, "BURGER", "Qo'y burger");
        otherBrandVariant = seedProduct(OTHER_BRAND, null, "BURGER", "Another brand's burger");
        cheeseOption = seedModifierOption(BRAND, "EXTRA_CHEESE");

        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED',
                        'hash', now())
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();
    }

    private void seedBrand(UUID brandId, String code, String slug) {
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, :code, 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", TENANT)
                .param("code", code)
                .param("slug", slug)
                .update();
    }

    private UUID seedProduct(UUID brandId, @Nullable UUID catalog, String code, String name) {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", productId)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("code", code)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku, status)
                VALUES (:id, :tenantId, :brandId, :productId, :sku, 'ACTIVE')
                """)
                .param("id", variantId)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("productId", productId)
                .param("sku", "SKU-" + code)
                .update();
        if (catalog != null) {
            jdbc.sql("""
                    INSERT INTO catalog.catalog_products (tenant_id, brand_id, catalog_id, product_id)
                    VALUES (:tenantId, :brandId, :catalogId, :productId)
                    """)
                    .param("tenantId", TENANT)
                    .param("brandId", brandId)
                    .param("catalogId", catalog)
                    .param("productId", productId)
                    .update();
        }
        jdbc.sql("""
                INSERT INTO catalog.translations (tenant_id, brand_id, entity_type, entity_id,
                    locale, name)
                VALUES (:tenantId, :brandId, 'PRODUCT', :productId, 'uz', :name)
                """)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("productId", productId)
                .param("name", name)
                .update();
        return variantId;
    }

    private UUID seedModifierOption(UUID brandId, String code) {
        UUID groupId = UUID.randomUUID();
        UUID optionId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.modifier_groups (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", groupId)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("code", "EXTRAS")
                .update();
        jdbc.sql("""
                INSERT INTO catalog.modifier_options (id, tenant_id, brand_id, modifier_group_id,
                    code, status)
                VALUES (:id, :tenantId, :brandId, :groupId, :code, 'ACTIVE')
                """)
                .param("id", optionId)
                .param("tenantId", TENANT)
                .param("brandId", brandId)
                .param("groupId", groupId)
                .param("code", code)
                .update();
        return optionId;
    }

    /** Lets a test move time forward without sleeping. */
    private static final class MutableClock extends java.time.Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration amount) {
            now = now.plus(amount);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public java.time.Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
