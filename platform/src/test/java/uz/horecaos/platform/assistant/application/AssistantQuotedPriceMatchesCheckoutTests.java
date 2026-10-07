package uz.horecaos.platform.assistant.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
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
import uz.horecaos.platform.assistant.domain.MoneyFormat;
import uz.horecaos.platform.catalog.api.MenuAvailabilityLookup;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.CatalogPublicationService;
import uz.horecaos.platform.catalog.application.CatalogSnapshotLoader;
import uz.horecaos.platform.catalog.application.CatalogValidator;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery;
import uz.horecaos.platform.catalog.application.StorefrontMenuSearch;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore;
import uz.horecaos.platform.conversations.api.ConversationParticipant.Outcome;
import uz.horecaos.platform.conversations.api.ConversationParticipant.Replied;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.QuoteService;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcCatalogPricingContext;
import uz.horecaos.platform.pricing.infrastructure.catalog.PricingMenuPriceLookup;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore;
import uz.horecaos.platform.support.AuditTrail;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * ADR 0069's grounding test, at its hardest: "a quoted price equals the pricing
 * engine's answer for the same inputs."
 *
 * <p>Everything between the question and the number is real here -- the storefront
 * menu, {@code PricingMenuPriceLookup} resolving a price book exactly as {@code
 * QuoteService} does, the assistant's retrieval and its grounding check -- and the
 * same dish is then quoted through ADR 0018's {@code QuoteService} as a one-line
 * cart. The two numbers are compared, in a world with a brand price book, a
 * cheaper branch-specific price book, and a channel-scoped price book, so a
 * resolver that disagreed about <em>which</em> book applies -- the failure that
 * makes a menu and a receipt disagree -- fails here.
 *
 * <p>What a menu price is, and is not: the price of the item alone. The reply is
 * equal to a cart quote only for a plain line with no modifiers, promotions or
 * delivery -- which is precisely what the assistant is allowed to say, and why its
 * price fact says "the menu price of this item alone".
 */
class AssistantQuotedPriceMatchesCheckoutTests {

    private static final Instant NOW = Instant.parse("2026-10-07T09:00:00Z");
    private static final String LOCALE = "uz";
    private static final FiscalClassification UNCLASSIFIED = FiscalClassification.unclassified();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private AssistantFixture fx;
    private JdbcPricingStore pricingStore;
    private QuoteService quotes;
    private AssistantTurnService service;
    private UUID variant;
    private UUID brandBook;
    private UUID kioskChannel;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker is required for this test");
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
        jdbc = JdbcClient.create(db.dataSource());
        jdbc.sql("TRUNCATE TABLE pricing.quote_adjustments, pricing.quote_lines, pricing.quotes, "
                        + "pricing.prices, pricing.price_book_assignments, pricing.price_books, "
                        + "pricing.tax_profiles CASCADE")
                .update();
        jdbc.sql(
                        "TRUNCATE TABLE assistant.turns, assistant.knowledge_entry_versions, assistant.knowledge_entries CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, "
                        + "catalog.location_offerings, catalog.media_relations, catalog.translations, "
                        + "catalog.product_modifier_groups, catalog.variant_modifier_groups, "
                        + "catalog.category_products, catalog.catalog_products, catalog.modifier_options, "
                        + "catalog.modifier_groups, catalog.categories, catalog.fiscal_classifications, "
                        + "catalog.fees, catalog.branch_menu_bindings, catalog.menu_items, catalog.menus, "
                        + "catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE media.assets CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        AuditTrail.clear(jdbc);

        fx = new AssistantFixture(jdbc, NOW);
        fx.seedTenancy();
        fx.branches.only(AssistantFixture.branch(fx.chilonzor, "Chilonzor", "Bunyodkor 12", "Chilonzor", null));
        seedChannel("STOREFRONT", "WEB");
        kioskChannel = seedChannel("KIOSK", "KIOSK");

        JsonMapper mapper = JsonMapper.builder().build();
        JdbcCatalogStore catalogStore = new JdbcCatalogStore(jdbc, mapper);
        CommercialDefaults.Wired commercial = CommercialDefaults.wire(jdbc, Clock.systemUTC());
        CatalogAuthoringService authoring = new CatalogAuthoringService(
                catalogStore,
                new uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder(jdbc, mapper),
                commercial.entitlements(),
                commercial.usage(),
                Clock.systemUTC());
        CatalogPublicationService publication = new CatalogPublicationService(
                catalogStore,
                new CatalogValidator(),
                new CatalogSnapshotLoader(
                        catalogStore,
                        (tenantId, assetIds) -> true,
                        (tenantId, brandId, variantIds) -> variantIds,
                        LOCALE),
                new JdbcSalesChannelStore(jdbc),
                Clock.fixed(Instant.parse("2026-08-21T10:00:00Z"), ZoneOffset.UTC),
                AuditTrail.discarding());
        UUID catalogId = authoring.createCatalog(fx.tenantId, fx.brandId, "MAIN", "Main menu", LOCALE);
        var dish = authoring.createProduct(
                fx.tenantId,
                fx.brandId,
                catalogId,
                "PLOV",
                "Плов",
                null,
                LOCALE,
                "SKU-PLOV",
                "PIECE",
                UNCLASSIFIED,
                UUID.randomUUID());
        variant = dish.defaultVariantId();
        authoring.setOffering(
                fx.tenantId,
                fx.brandId,
                fx.chilonzor,
                variant,
                OfferingStatus.AVAILABLE,
                List.of("DELIVERY", "PICKUP"));
        publication.publish(fx.tenantId, fx.brandId, catalogId, "STOREFRONT", null);
        publication.publish(fx.tenantId, fx.brandId, catalogId, "KIOSK", null);

        pricingStore = new JdbcPricingStore(jdbc, mapper);
        JdbcSalesChannelStore channelStore = new JdbcSalesChannelStore(jdbc);
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        MenuAvailabilityLookup everythingAvailable =
                (tenantId, brandId, locationId, channel, variantIds) -> java.util.Map.of();
        StorefrontCatalogQuery storefront = new StorefrontCatalogQuery(
                catalogStore,
                new PricingMenuPriceLookup(pricingStore, channelStore, clock),
                everythingAvailable,
                new JdbcMenuStore(jdbc),
                new uz.horecaos.platform.catalog.infrastructure.tenancy.JdbcCatalogTenantContext(jdbc),
                clock,
                new JdbcCommentPresetStore(jdbc));
        fx.menuPort = new StorefrontMenuSearch(storefront);
        service = fx.rebuild();

        var deliveryFees = new uz.horecaos.platform.fulfillment.application.DeliveryFeeResolver(
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore(jdbc),
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryTariffStore(jdbc),
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryFeeResolutionStore(
                        jdbc, mapper),
                (origin, destination, installationId) -> Optional.empty(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        quotes = new QuoteService(
                pricingStore,
                new PricingEngine(),
                new JdbcCatalogPricingContext(jdbc, LOCALE),
                channelStore,
                deliveryFees,
                uz.horecaos.platform.pricing.PromotionTestSupport.resolver(jdbc, new JdbcPromoCodeStore(jdbc, mapper)),
                clock,
                new FakeConfigurationResolver());

        jdbc.sql("""
                INSERT INTO pricing.tax_profiles (id, tenant_id, brand_id, jurisdiction_code, mode, rate_basis_points, valid_from)
                VALUES (:id, :tenantId, :brandId, 'UZ', 'INCLUSIVE', 1200, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", fx.tenantId)
                .param("brandId", fx.brandId)
                .param("from", OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC))
                .update();
        brandBook = seedPriceBook("BRAND_MENU", 0);
        seedAssignment(brandBook, "BRAND", null, 0);
        seedPrice(brandBook, 50_000L);
    }

    /** What the checkout path would charge for one plain portion on this channel at the Chilonzor branch. */
    private long quotedTotal(String channel) {
        return quotes.quote(new QuoteRequest(
                        fx.tenantId,
                        fx.brandId,
                        fx.chilonzor,
                        null,
                        channel,
                        List.of(new QuoteRequest.Line("line-0", variant, 1, List.of())),
                        null))
                .total()
                .minor();
    }

    private String assistantSays(String question) {
        Outcome outcome = service.offer(fx.ask(UUID.randomUUID(), question));
        assertThat(outcome).isInstanceOf(Replied.class);
        return ((Replied) outcome).text();
    }

    private String formatted(long minor) {
        return MoneyFormat.format(minor, "UZS", "en").orElseThrow();
    }

    @Test
    @DisplayName(
            "the number the assistant states is the number the checkout quote charges for the same dish, channel and branch")
    void theAssistantStatesTheQuotedPrice() {
        long charged = quotedTotal("STOREFRONT");

        assertThat(charged).isEqualTo(50_000L);
        assertThat(assistantSays("How much is the plov?")).contains(formatted(charged));
        assertThat(fx.model.lastRequest().facts().getFirst().attributes())
                .containsEntry("price", formatted(charged))
                .containsEntry("branch", "Chilonzor");
    }

    @Test
    @DisplayName("a price book assigned to this branch outranks the brand's, in the quote and in the answer alike")
    void aBranchPriceBookIsFollowedByBoth() {
        UUID branchBook = seedPriceBook("BRANCH_MENU", 10);
        seedAssignment(branchBook, "LOCATION", fx.chilonzor, 10);
        seedPrice(branchBook, 45_000L);

        long charged = quotedTotal("STOREFRONT");

        assertThat(charged).isEqualTo(45_000L);
        assertThat(assistantSays("How much is the plov?"))
                .contains(formatted(charged))
                .doesNotContain("50 000");
    }

    @Test
    @DisplayName(
            "a channel's own price book prices that channel and no other, and the assistant quotes the channel it is told to")
    void aChannelsPriceBookIsFollowedByBoth() {
        UUID kioskBook = seedPriceBook("KIOSK_MENU", 100);
        seedAssignment(kioskBook, "CHANNEL", kioskChannel, 100);
        seedPrice(kioskBook, 90_000L);

        assertThat(quotedTotal("KIOSK")).isEqualTo(90_000L);
        assertThat(quotedTotal("STOREFRONT")).isEqualTo(50_000L);
        assertThat(assistantSays("How much is the plov?"))
                .as("by default the assistant quotes the storefront, the channel the bot's carts are built on")
                .contains("50 000 UZS")
                .doesNotContain("90 000");

        fx.configuration.put("assistant.price_channel_code", "KIOSK");
        service = fx.rebuild();
        assertThat(assistantSays("How much is the plov, please? (kiosk)")).contains("90 000 UZS");
    }

    @Test
    @DisplayName("when the price changes the next answer follows it, and the cache never serves the old number")
    void aChangedPriceIsFollowed() {
        assertThat(assistantSays("How much is the plov?")).contains("50 000");
        jdbc.sql("UPDATE pricing.prices SET amount_minor = 55000 WHERE price_book_id = :book")
                .param("book", brandBook)
                .update();

        assertThat(quotedTotal("STOREFRONT")).isEqualTo(55_000L);
        assertThat(assistantSays("How much is the plov?")).contains("55 000").doesNotContain("50 000");
    }

    @Test
    @DisplayName("a dish with no price in any book is not quoted at all, and is never called free")
    void anUnpricedDishIsNotQuoted() {
        jdbc.sql("DELETE FROM pricing.prices WHERE price_book_id = :book")
                .param("book", brandBook)
                .update();

        String said = assistantSays("How much is the plov?");

        assertThat(said)
                .contains("no price is published")
                .doesNotContainPattern("\\d{2,3} \\d{3}")
                .doesNotContain("free");
        assertThat(fx.ledger().getLast().get("facts")).asString().doesNotContain("amountMinor");
    }

    // ----------------------------------------------------------------- fixtures

    private UUID seedChannel(String code, String systemType) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, :code, :systemType, :code, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", fx.tenantId)
                .param("code", code)
                .param("systemType", systemType)
                .update();
        return id;
    }

    private UUID seedPriceBook(String name, int priority) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO pricing.price_books (id, tenant_id, brand_id, name, currency, status, valid_from, priority)
                VALUES (:id, :tenantId, :brandId, :name, 'UZS', 'ACTIVE', :from, :priority)
                """)
                .param("id", id)
                .param("tenantId", fx.tenantId)
                .param("brandId", fx.brandId)
                .param("name", name)
                .param("priority", priority)
                .param("from", OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC))
                .update();
        return id;
    }

    private void seedAssignment(UUID priceBookId, String scopeType, @Nullable UUID scopeId, int priority) {
        jdbc.sql("""
                INSERT INTO pricing.price_book_assignments (id, tenant_id, brand_id, price_book_id, scope_type, scope_id, valid_from, priority)
                VALUES (:id, :tenantId, :brandId, :priceBookId, :scopeType, :scopeId, :from, :priority)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", fx.tenantId)
                .param("brandId", fx.brandId)
                .param("priceBookId", priceBookId)
                .param("scopeType", scopeType)
                .param("scopeId", scopeId)
                .param("priority", priority)
                .param("from", OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC))
                .update();
    }

    private void seedPrice(UUID priceBookId, long amountMinor) {
        jdbc.sql("""
                INSERT INTO pricing.prices (id, tenant_id, brand_id, price_book_id, priceable_type, priceable_id, amount_minor, valid_from)
                VALUES (:id, :tenantId, :brandId, :priceBookId, 'VARIANT', :variant, :amount, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", fx.tenantId)
                .param("brandId", fx.brandId)
                .param("priceBookId", priceBookId)
                .param("variant", variant)
                .param("amount", amountMinor)
                .param("from", OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC))
                .update();
    }
}
