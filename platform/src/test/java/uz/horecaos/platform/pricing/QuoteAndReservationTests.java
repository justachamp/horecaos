package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.inventory.api.TrackingMode;
import uz.horecaos.platform.inventory.application.InventoryService;
import uz.horecaos.platform.inventory.infrastructure.persistence.JdbcInventoryStore;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.QuoteService;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcCatalogPricingContext;
import uz.horecaos.platform.pricing.infrastructure.catalog.PricingVariantLookup;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * Stage three of the cutover: a cart can be priced and reserved (ADR 0017, 0018).
 *
 * <p>Runs against a real database because the properties under test are the ones
 * that only appear there: which price book wins, whether a hold can be taken
 * twice, and whether an expired quote can still be accepted.
 */
class QuoteAndReservationTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-21T12:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcPricingStore pricingStore;
    private JdbcInventoryStore inventoryStore;
    private QuoteService quotes;
    private JdbcSalesChannelStore channelStore;
    private InventoryService inventory;
    private MutableClock clock;

    private UUID burgerVariant;
    private UUID pizzaVariant;
    private UUID catalogId;
    private UUID kioskChannel;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for quote and reservation tests");
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
        jdbc.sql("TRUNCATE TABLE inventory.reservation_lines, inventory.reservations, "
                        + "inventory.movements, inventory.positions, inventory.stock_items CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, "
                        + "catalog.location_offerings, catalog.translations, catalog.catalog_products, "
                        + "catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = new MutableClock(NOW);
        pricingStore = new JdbcPricingStore(jdbc, JsonMapper.builder().build());
        inventoryStore = new JdbcInventoryStore(jdbc);
        inventory = new InventoryService(inventoryStore, event -> {}, clock);
        channelStore = new JdbcSalesChannelStore(jdbc);
        // ADR 0037. The real resolver, so a collected cart travels the production
        // path rather than a stand-in that could not refuse anything. It is never
        // consulted here: every cart in this suite is a collection, and a request
        // with no destination does not enter fee resolution at all.
        var deliveryFees = new uz.horecaos.platform.fulfillment.application.DeliveryFeeResolver(
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore(jdbc),
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryTariffStore(jdbc),
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryFeeResolutionStore(
                        jdbc, JsonMapper.builder().build()),
                (origin, destination, installationId) -> java.util.Optional.empty(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());

        quotes = new QuoteService(
                pricingStore,
                new PricingEngine(),
                new JdbcCatalogPricingContext(jdbc, "uz"),
                channelStore,
                deliveryFees,
                clock);

        seedTenancyAndCatalog();
        seedPricing();
    }

    @Test
    @DisplayName("a cart is priced at the menu price with VAT inside it")
    void aCartIsPricedInclusiveOfVat() {
        var quote = quotes.quote(cart(Map.of(burgerVariant, 2)));

        // Two burgers at 50,000 each. The customer pays 100,000, and the 12% VAT
        // is inside that rather than added to it.
        assertThat(quote.total().minor()).isEqualTo(100_000L);
        assertThat(quote.tax().minor()).isEqualTo(10_714L);
        assertThat(quote.subtotal().minor() + quote.tax().minor()).isEqualTo(100_000L);
        assertThat(quote.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("the line description is snapshotted, so a later rename does not rewrite history")
    void lineDescriptionsAreSnapshotted() {
        var quote = quotes.quote(cart(Map.of(burgerVariant, 1)));
        assertThat(quote.lines().getFirst().descriptionSnapshot()).isEqualTo("Qo'y burger");

        jdbc.sql("UPDATE catalog.translations SET name = 'Renamed' "
                        + "WHERE entity_type = 'PRODUCT' AND locale = 'uz'")
                .update();

        assertThat(jdbc.sql("SELECT description_snapshot FROM pricing.quote_lines")
                        .query(String.class)
                        .single())
                .as("a stored quote says what the customer was actually buying")
                .isEqualTo("Qo'y burger");
    }

    @Test
    @DisplayName("a location-scoped price book beats the brand's")
    void theMoreSpecificPriceBookWins() {
        UUID locationBook = seedPriceBook("LOCATION_MENU", 0);
        seedAssignment(locationBook, "LOCATION", LOCATION, 0);
        seedPrice(locationBook, "VARIANT", burgerVariant, 45_000L);

        // Specificity decides, not priority or row order. Otherwise a location's
        // own prices would apply or not depending on the query planner.
        assertThat(quotes.quote(cart(Map.of(burgerVariant, 1))).total().minor()).isEqualTo(45_000L);
    }

    @Test
    @DisplayName("a price book assigned to another channel does not price this order")
    void aChannelScopedPriceBookDoesNotLeakAcrossChannels() {
        UUID kioskBook = seedPriceBook("KIOSK_MENU", 100);
        seedAssignment(kioskBook, "CHANNEL", kioskChannel, 100);
        seedPrice(kioskBook, "VARIANT", burgerVariant, 90_000L);

        // The first version of the resolver matched any CHANNEL assignment
        // regardless of which channel it named, and ranked it above the brand
        // book — so a kiosk price book priced every storefront order at kiosk
        // prices. Now that ADR 0036 makes a channel an entity, the parameter is
        // bound and the storefront cart gets the brand book.
        assertThat(quotes.quote(cart(Map.of(burgerVariant, 1))).total().minor()).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("a channel-scoped price book prices its own channel")
    void aChannelScopedPriceBookPricesItsOwnChannel() {
        UUID kioskBook = seedPriceBook("KIOSK_MENU", 0);
        seedAssignment(kioskBook, "CHANNEL", kioskChannel, 0);
        seedPrice(kioskBook, "VARIANT", burgerVariant, 90_000L);
        seedPublication("KIOSK");

        // The premise of the test above: the assignment is not merely inert. If
        // excluding CHANNEL scope entirely were still the behaviour, this would
        // return the brand price and the pair of tests would both pass while the
        // feature did nothing.
        assertThat(quotes.quote(cart(Map.of(burgerVariant, 1), "KIOSK")).total().minor())
                .isEqualTo(90_000L);
    }

    @Test
    @DisplayName("a price plane resolves the other channel's assignments, without recursing")
    void aPricePlaneResolvesTheChannelItPointsAt() {
        UUID hallBook = seedPriceBook("HALL_MENU", 0);
        UUID hallChannel = seedChannel("HALL", "POS", null);
        seedAssignment(hallBook, "CHANNEL", hallChannel, 0);
        seedPrice(hallBook, "VARIANT", burgerVariant, 61_000L);

        // "For QR, take the hall's prices" is one column and not a duplicated
        // price book. The QR channel has no assignment of its own.
        UUID qrChannel = seedChannel("QR", "QR_TABLE", hallChannel);
        seedPublication("QR");

        assertThat(quotes.quote(cart(Map.of(burgerVariant, 1), "QR")).total().minor())
                .isEqualTo(61_000L);
        assertThat(jdbc.sql("SELECT count(*) FROM pricing.price_book_assignments "
                                + "WHERE scope_type = 'CHANNEL' AND scope_id = :qr")
                        .param("qr", qrChannel)
                        .query(Long.class)
                        .single())
                .as("the plane is followed, not copied: QR still has no assignment of its own")
                .isZero();
    }

    @Test
    @DisplayName("an item with no price refuses the quote rather than pricing it at zero")
    void anUnpricedItemRefusesTheQuote() {
        assertThat(catchThrowable(() -> quotes.quote(cart(Map.of(pizzaVariant, 1)))))
                .isInstanceOf(PricingEngine.UnpricedItemException.class);
    }

    @Test
    @DisplayName("a repeated quote request returns the first quote, not a second")
    void quotingIsIdempotent() {
        var request = new QuoteRequest(
                TENANT,
                BRAND,
                LOCATION,
                null,
                "STOREFRONT",
                List.of(new QuoteRequest.Line("a", burgerVariant, 1, List.of())),
                "cart-42");

        var first = quotes.quote(request);
        var second = quotes.quote(request);

        // Two quotes for one basket would mean two reservations and a customer
        // able to accept a price they were not shown.
        assertThat(second.quoteId()).isEqualTo(first.quoteId());
        assertThat(jdbc.sql("SELECT count(*) FROM pricing.quotes")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("an unchanged quote is accepted at exactly the price shown")
    void anUnchangedQuoteIsAccepted() {
        var quote = quotes.quote(cart(Map.of(burgerVariant, 1)));

        var acceptance = quotes.accept(TENANT, quote.quoteId(), quote.contextHash());

        assertThat(acceptance.outcome()).isEqualTo(QuoteService.Acceptance.Outcome.ACCEPTED);
        var total = Objects.requireNonNull(acceptance.total(), "an ACCEPTED outcome always carries a total");
        assertThat(total.minor()).isEqualTo(50_000L);
    }

    @Test
    @DisplayName("a quote whose context changed is refused with PRICE_CHANGED, never silently charged")
    void aChangedContextIsRefused() {
        var quote = quotes.quote(cart(Map.of(burgerVariant, 1)));

        var acceptance = quotes.accept(TENANT, quote.quoteId(), "a-different-hash");

        // Charging the difference silently is what customers experience as a
        // scam; a stable code lets the storefront re-quote and explain.
        assertThat(acceptance.outcome()).isEqualTo(QuoteService.Acceptance.Outcome.PRICE_CHANGED);
    }

    @Test
    @DisplayName("an expired quote cannot be accepted")
    void anExpiredQuoteIsRefused() {
        var quote = quotes.quote(cart(Map.of(burgerVariant, 1)));

        clock.advance(Duration.ofMinutes(16));

        assertThat(quotes.accept(TENANT, quote.quoteId(), quote.contextHash()).outcome())
                .isEqualTo(QuoteService.Acceptance.Outcome.EXPIRED);
    }

    @Test
    @DisplayName("a quote can only be accepted once")
    void aQuoteIsAcceptedOnlyOnce() {
        var quote = quotes.quote(cart(Map.of(burgerVariant, 1)));

        assertThat(quotes.accept(TENANT, quote.quoteId(), quote.contextHash()).outcome())
                .isEqualTo(QuoteService.Acceptance.Outcome.ACCEPTED);
        // The second attempt loses on the conditional update rather than paying
        // for a basket already committed.
        assertThat(quotes.accept(TENANT, quote.quoteId(), quote.contextHash()).outcome())
                .isEqualTo(QuoteService.Acceptance.Outcome.EXPIRED);
    }

    @Test
    @DisplayName("a sold-out dish blocks the reservation and says which one")
    void aSoldOutDishBlocksTheReservation() {
        UUID burgerStock = inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, burgerVariant, TrackingMode.BINARY);
        assertThat(burgerStock).isNotNull();

        inventory.setAvailability(TENANT, LOCATION, burgerVariant, false, "OUT_OF_INGREDIENTS", null);

        var result = inventory.reserveForQuote(TENANT, BRAND, LOCATION, UUID.randomUUID(), Map.of(burgerVariant, 1));

        assertThat(result.isHeld()).isFalse();
        // Naming the item is the difference between a customer who can fix their
        // basket and one who has to guess.
        assertThat(result.refusal().unavailableItems()).singleElement().satisfies(item -> {
            assertThat(item.variantId()).isEqualTo(burgerVariant);
            assertThat(item.reason()).isEqualTo("SOLD_OUT");
        });
    }

    @Test
    @DisplayName("a variant the location never listed is unavailable, not silently sellable")
    void anUnlistedVariantIsUnavailable() {
        var decision = inventory.checkAvailability(TENANT, LOCATION, java.util.Set.of(pizzaVariant));

        // Defaulting to available would let a kitchen receive orders for dishes
        // it does not make.
        assertThat(decision.available()).isFalse();
        assertThat(decision.unavailableItems().getFirst().reason()).isEqualTo("NOT_STOCKED_AT_LOCATION");
    }

    @Test
    @DisplayName("holding twice for one quote returns the same hold")
    void reservingTwiceIsIdempotent() {
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, burgerVariant, TrackingMode.BINARY);
        UUID quoteId = UUID.randomUUID();

        var first = inventory.reserveForQuote(TENANT, BRAND, LOCATION, quoteId, Map.of(burgerVariant, 1));
        var second = inventory.reserveForQuote(TENANT, BRAND, LOCATION, quoteId, Map.of(burgerVariant, 1));

        assertThat(second.reservationId()).isEqualTo(first.reservationId());
        assertThat(jdbc.sql("SELECT count(*) FROM inventory.reservations")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a released hold cannot then be committed")
    void aReleasedHoldCannotBeCommitted() {
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, burgerVariant, TrackingMode.BINARY);
        UUID quoteId = UUID.randomUUID();
        inventory.reserveForQuote(TENANT, BRAND, LOCATION, quoteId, Map.of(burgerVariant, 1));

        assertThat(inventory.release(TENANT, quoteId)).isTrue();
        // The status predicate is in the UPDATE, so a late commit cannot revive
        // stock that was already given back.
        assertThat(inventory.commit(TENANT, quoteId)).isFalse();
    }

    @Test
    @DisplayName("an abandoned hold expires and stops reserving stock")
    void abandonedHoldsExpire() {
        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, burgerVariant, TrackingMode.BINARY);
        UUID quoteId = UUID.randomUUID();
        inventory.reserveForQuote(TENANT, BRAND, LOCATION, quoteId, Map.of(burgerVariant, 1));

        clock.advance(Duration.ofMinutes(16));

        assertThat(inventory.expireStaleReservations()).isEqualTo(1);
        assertThat(inventory.commit(TENANT, quoteId))
                .as("an expired hold is not a hold")
                .isFalse();
    }

    @Test
    @DisplayName("every availability change leaves a movement explaining it")
    void availabilityChangesAreRecorded() {
        UUID stockItemId = inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, burgerVariant, TrackingMode.BINARY);

        inventory.setAvailability(TENANT, LOCATION, burgerVariant, false, "OUT_OF_INGREDIENTS", null);
        inventory.setAvailability(TENANT, LOCATION, burgerVariant, true, "RESTOCKED", null);

        // "Why was this sold out at 19:00" has an answer. A position column alone
        // would have none.
        assertThat(inventoryStore.movementCount(TENANT, stockItemId)).isEqualTo(2L);
        assertThat(jdbc.sql("SELECT reason_code FROM inventory.movements ORDER BY sequence_number")
                        .query(String.class)
                        .list())
                .containsExactly("OUT_OF_INGREDIENTS", "RESTOCKED");
    }

    @Test
    @DisplayName("repeating the same availability toggle does not double the ledger")
    void repeatedTogglesAreIdempotent() {
        UUID stockItemId = inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, burgerVariant, TrackingMode.BINARY);

        inventory.setAvailability(TENANT, LOCATION, burgerVariant, false, "OUT", null);
        inventory.setAvailability(TENANT, LOCATION, burgerVariant, false, "OUT", null);

        assertThat(inventoryStore.movementCount(TENANT, stockItemId)).isEqualTo(1L);
    }

    @Test
    @DisplayName("quantity tracking is refused rather than half-enforced")
    void quantityTrackingIsRefused() {
        // Accepting QUANTITY without enforcing it would let a location oversell
        // silently, which is worse than an error nobody can ignore.
        assertThat(catchThrowable(() ->
                        inventory.listVariantAtLocation(TENANT, BRAND, LOCATION, burgerVariant, TrackingMode.QUANTITY)))
                .isInstanceOf(InventoryService.UnsupportedTrackingModeException.class);
    }

    @Test
    @DisplayName("catalog publication now verifies prices for real")
    void catalogPricingValidationIsWired() {
        var lookup = new PricingVariantLookup(pricingStore, clock);

        assertThat(lookup.isWired())
                .as("the stand-in that made VARIANT_HAS_NO_ACTIVE_PRICE inert is gone")
                .isTrue();
        assertThat(lookup.pricedVariants(TENANT, BRAND, java.util.Set.of(burgerVariant, pizzaVariant)))
                .containsExactly(burgerVariant);
    }

    // ------------------------------------------------------------------ fixtures

    private QuoteRequest cart(Map<UUID, Integer> quantities) {
        return cart(quantities, "STOREFRONT");
    }

    private QuoteRequest cart(Map<UUID, Integer> quantities, String channel) {
        List<QuoteRequest.Line> lines = new java.util.ArrayList<>();
        int index = 0;
        for (var entry : quantities.entrySet()) {
            lines.add(new QuoteRequest.Line("line-" + index++, entry.getKey(), entry.getValue(), List.of()));
        }
        return new QuoteRequest(TENANT, BRAND, LOCATION, null, channel, lines, null);
    }

    private void seedTenancyAndCatalog() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'pricing-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
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

        // ADR 0036: publications reference a registered channel. Two here, because
        // the price-plane tests need a second channel to point at.
        seedChannel("STOREFRONT", "WEB", null);
        kioskChannel = seedChannel("KIOSK", "KIOSK", null);

        catalogId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.catalogs (id, tenant_id, brand_id, code, name, status)
                VALUES (:id, :tenantId, :brandId, 'MAIN', 'Main menu', 'ACTIVE')
                """)
                .param("id", catalogId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        burgerVariant = seedProduct("BURGER", "Qo'y burger");
        pizzaVariant = seedProduct("PIZZA", "Pizza");

        seedPublication("STOREFRONT");
    }

    private void seedPublication(String channel) {
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel,
                    status, content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, :channel, 'PUBLISHED', 'hash', now())
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .param("channel", channel)
                .update();
    }

    private UUID seedChannel(String code, String systemType, @Nullable UUID pricePlaneChannelId) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type,
                    display_name, status, price_plane_channel_id)
                VALUES (:id, :tenantId, :code, :systemType, :code, 'ACTIVE', :pricePlane)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("code", code)
                .param("systemType", systemType)
                .param("pricePlane", pricePlaneChannelId)
                .update();
        return id;
    }

    private UUID seedProduct(String code, String name) {
        UUID productId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", productId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("code", code)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, sku, status)
                VALUES (:id, :tenantId, :brandId, :productId, :sku, 'ACTIVE')
                """)
                .param("id", variantId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("productId", productId)
                .param("sku", "SKU-" + code)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.catalog_products (tenant_id, brand_id, catalog_id, product_id)
                VALUES (:tenantId, :brandId, :catalogId, :productId)
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .param("productId", productId)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.translations (tenant_id, brand_id, entity_type, entity_id,
                    locale, name)
                VALUES (:tenantId, :brandId, 'PRODUCT', :productId, 'uz', :name)
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("productId", productId)
                .param("name", name)
                .update();
        return variantId;
    }

    private void seedPricing() {
        UUID brandBook = seedPriceBook("BRAND_MENU", 0);
        seedAssignment(brandBook, "BRAND", null, 0);
        seedPrice(brandBook, "VARIANT", burgerVariant, 50_000L);

        jdbc.sql("""
                INSERT INTO pricing.tax_profiles (id, tenant_id, brand_id, jurisdiction_code,
                    mode, rate_basis_points, valid_from)
                VALUES (:id, :tenantId, :brandId, 'UZ', 'INCLUSIVE', 1200, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("from", java.time.OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC))
                .update();
    }

    private UUID seedPriceBook(String name, int priority) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO pricing.price_books (id, tenant_id, brand_id, name, currency, status,
                    valid_from, priority)
                VALUES (:id, :tenantId, :brandId, :name, 'UZS', 'ACTIVE', :from, :priority)
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("name", name)
                .param("priority", priority)
                .param("from", java.time.OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC))
                .update();
        return id;
    }

    private void seedAssignment(UUID priceBookId, String scopeType, @Nullable UUID scopeId, int priority) {
        jdbc.sql("""
                INSERT INTO pricing.price_book_assignments (id, tenant_id, brand_id, price_book_id,
                    scope_type, scope_id, valid_from, priority)
                VALUES (:id, :tenantId, :brandId, :priceBookId, :scopeType, :scopeId, :from, :priority)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("priceBookId", priceBookId)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("priority", priority)
                .param("from", java.time.OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC))
                .update();
    }

    private void seedPrice(UUID priceBookId, String type, UUID priceableId, long amountMinor) {
        jdbc.sql("""
                INSERT INTO pricing.prices (id, tenant_id, brand_id, price_book_id,
                    priceable_type, priceable_id, amount_minor, valid_from)
                VALUES (:id, :tenantId, :brandId, :priceBookId, :type, :priceableId, :amount, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("priceBookId", priceBookId)
                .param("type", type)
                .param("priceableId", priceableId)
                .param("amount", amountMinor)
                .param("from", java.time.OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC))
                .update();
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
