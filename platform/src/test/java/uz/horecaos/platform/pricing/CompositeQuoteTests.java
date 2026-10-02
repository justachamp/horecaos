package uz.horecaos.platform.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.DockerClientFactory;
import tools.jackson.databind.json.JsonMapper;
import uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService.AttachmentPolicy;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService.NewComboGroup;
import uz.horecaos.platform.catalog.domain.CompositeProducts.AttachmentOwnerType;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboComponent;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboGroup;
import uz.horecaos.platform.catalog.domain.CompositeProducts.Visibility;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCompositeCatalogStore;
import uz.horecaos.platform.iam.api.AuthenticatedActor;
import uz.horecaos.platform.pricing.api.CartPricingPort;
import uz.horecaos.platform.pricing.api.QuoteSnapshot;
import uz.horecaos.platform.pricing.application.PriceAuthoringService;
import uz.horecaos.platform.pricing.application.PriceableType;
import uz.horecaos.platform.pricing.application.PricingEngine;
import uz.horecaos.platform.pricing.application.PromoCodeEligibilityService;
import uz.horecaos.platform.pricing.application.QuoteService;
import uz.horecaos.platform.pricing.domain.QuoteRequest;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcCatalogPricingContext;
import uz.horecaos.platform.pricing.infrastructure.catalog.JdbcCompositeProductsLookup;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPromoCodeStore;
import uz.horecaos.platform.support.FakeConfigurationResolver;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.api.FulfillmentMode;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * ADR 0136 against a real database: authored combos, hidden groups and nested
 * modifiers reaching a stored quote.
 *
 * <p>The authoring is done through the authoring services, not seeded as rows, so the
 * chain under test is the one an operator runs: author a combo, price its
 * components in a price book, and a cart for the container becomes ordinary quote
 * lines that an order can copy. A fixture that wrote rows no endpoint can write would
 * prove the quote reads rows, not that anything produces them.
 */
class CompositeQuoteTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcPricingStore pricingStore;
    private QuoteService quotes;
    private PriceAuthoringService priceAuthoring;
    private CompositeProductAuthoringService composites;
    private JdbcCatalogStore catalogStore;
    private JdbcCompositeCatalogStore compositeStore;
    private MutableClock clock;

    private UUID catalogId;
    private UUID priceBook;

    private UUID lunch;
    private UUID burger;
    private UUID wrap;
    private UUID fries;
    private UUID cola;
    private UUID burgerProduct;

    private ComboGroup mainGroup;
    private ComboGroup drinkGroup;
    private ComboComponent burgerInLunch;
    private ComboComponent wrapInLunch;
    private ComboComponent colaInLunch;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for composite quote tests");
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
        jdbc.sql("TRUNCATE TABLE catalog.combo_components, catalog.combo_groups, "
                        + "catalog.product_modifier_groups, catalog.variant_modifier_groups, "
                        + "catalog.publication_items, catalog.publications, catalog.location_offerings, "
                        + "catalog.translations, catalog.catalog_products, catalog.modifier_options, "
                        + "catalog.modifier_groups, catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE audit.audit_events").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        clock = new MutableClock(NOW);
        var mapper = JsonMapper.builder().build();
        pricingStore = new JdbcPricingStore(jdbc, mapper);
        var channelStore = new JdbcSalesChannelStore(jdbc);
        catalogStore = new JdbcCatalogStore(jdbc, mapper);
        compositeStore = new JdbcCompositeCatalogStore(jdbc);
        var audit = new JdbcAuditRecorder(jdbc, mapper);
        var catalogContext = new JdbcCatalogPricingContext(jdbc, "uz");

        composites = new CompositeProductAuthoringService(compositeStore, catalogStore, audit, clock);
        priceAuthoring = new PriceAuthoringService(
                pricingStore,
                catalogContext,
                channelStore,
                clock,
                audit,
                event -> {},
                () -> new AuthenticatedActor("composite-quote-test", Set.of(), Map.of()));

        var deliveryFees = new uz.horecaos.platform.fulfillment.application.DeliveryFeeResolver(
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcServiceZoneStore(jdbc),
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryTariffStore(jdbc),
                new uz.horecaos.platform.fulfillment.infrastructure.persistence.JdbcDeliveryFeeResolutionStore(
                        jdbc, mapper),
                (origin, destination, installationId) -> java.util.Optional.empty(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        var promoCodeStore = new JdbcPromoCodeStore(jdbc, mapper);
        quotes = new QuoteService(
                pricingStore,
                new PricingEngine(),
                catalogContext,
                channelStore,
                deliveryFees,
                promoCodeStore,
                new PromoCodeEligibilityService(promoCodeStore),
                clock,
                new FakeConfigurationResolver(),
                new JdbcCompositeProductsLookup(jdbc));

        seedTenancyAndCatalog();
        authorTheLunchBox();
    }

    // -------------------------------------------------------------------- combos

    @Test
    @DisplayName("an authored and priced combo quotes as one ordinary stored line per component")
    void anAuthoredComboQuotesAsItsComponents() {
        var quote = quotes.quote(request(comboLine("lunch-1", 2, pick(burgerInLunch, 1), pick(colaInLunch, 1))));

        assertThat(quote.total().minor()).isEqualTo(2 * (30_000L + 3_000L));
        assertThat(quote.lines()).hasSize(2);

        QuoteSnapshot stored = quotes.quoteSnapshot(TENANT, quote.quoteId()).orElseThrow();
        assertThat(stored.lines())
                .extracting(QuoteSnapshot.Line::variantId)
                .as("persisted lines are the components, in group order -- there is no line for the container")
                .containsExactly(burger, cola);
        assertThat(stored.lines()).extracting(QuoteSnapshot.Line::lineKey).containsExactly("lunch-1~1", "lunch-1~2");
        assertThat(stored.lines())
                .as("the grouping key survives the round trip a checkout reads")
                .allSatisfy(line -> {
                    assertThat(line.comboSelectionId()).isNotNull();
                    assertThat(line.comboContainerVariantId()).isEqualTo(lunch);
                });
        assertThat(stored.lines().stream()
                        .map(QuoteSnapshot.Line::comboSelectionId)
                        .distinct())
                .as("one purchase, one selection")
                .hasSize(1);
        assertThat(stored.lines().stream()
                        .mapToLong(QuoteSnapshot.Line::finalAmountMinor)
                        .sum())
                .as("the quote total is the sum of ordinary lines, so ck_order_total_reconciles needs no change")
                .isEqualTo(stored.totalMinor());
        assertThat(stored.lines().stream()
                        .mapToLong(QuoteSnapshot.Line::taxAmountMinor)
                        .sum())
                .isEqualTo(stored.taxMinor());
        assertThat(jdbc.sql("SELECT count(*) FROM pricing.quote_lines WHERE source_variant_id = :lunch")
                        .param("lunch", lunch)
                        .query(Long.class)
                        .single())
                .as("the container is never a quote line")
                .isZero();
    }

    @Test
    @DisplayName("two combo lines in one cart carry two different selections")
    void eachComboLineIsItsOwnSelection() {
        var quote = quotes.quote(request(
                comboLine("a", 1, pick(burgerInLunch, 1), pick(colaInLunch, 1)),
                comboLine("b", 1, pick(wrapInLunch, 1), pick(colaInLunch, 1))));

        var selections = quote.lines().stream()
                .map(line -> line.comboSelectionId())
                .distinct()
                .toList();

        assertThat(selections).hasSize(2);
    }

    @Test
    @DisplayName("a repriced component changes the next quote and the hash; an issued quote keeps the price it showed")
    void aComponentPriceChangeIsCharged() {
        var before = quotes.quote(request(comboLine("l", 1, pick(burgerInLunch, 1), pick(colaInLunch, 1))));

        clock.advance(Duration.ofMinutes(1));
        priceAuthoring.setPrice(TENANT, BRAND, priceBook, PriceableType.COMBO_COMPONENT, burgerInLunch.id(), 34_000L);
        var after = quotes.quote(request(comboLine("l", 1, pick(burgerInLunch, 1), pick(colaInLunch, 1))));

        assertThat(before.total().minor()).isEqualTo(33_000L);
        assertThat(after.total().minor()).isEqualTo(37_000L);
        assertThat(after.contextHash()).isNotEqualTo(before.contextHash());
        assertThat(quotes.accept(TENANT, before.quoteId(), before.contextHash()).total())
                .as("the customer at the payment step pays what they were shown")
                .isNotNull()
                .satisfies(total -> assertThat(total.minor()).isEqualTo(33_000L));
    }

    @Test
    @DisplayName(
            "an unpriced component refuses the cart through the cart port with ITEM_NOT_PRICED, naming the pairing")
    void anUnpricedComponentIsRefusedWithItsCode() {
        jdbc.sql("DELETE FROM pricing.prices WHERE priceable_id = :id")
                .param("id", wrapInLunch.id())
                .update();

        Throwable refused = catchThrowable(
                () -> quotes.priceCart(command(item("l", lunch, 1, pick(wrapInLunch, 1), pick(colaInLunch, 1)))));

        assertThat(refused).isInstanceOf(CartPricingPort.PricingRefusedException.class);
        var refusal = (CartPricingPort.PricingRefusedException) refused;
        assertThat(refusal.code()).isEqualTo("ITEM_NOT_PRICED");
        assertThat(refusal.subjectId()).isEqualTo(wrapInLunch.id());
    }

    @Test
    @DisplayName("combo selection rules are refused through the cart port with their own codes")
    void selectionRulesAreRefusedWithTheirCodes() {
        assertCartRefused(item("l", lunch, 1), "COMBO_SELECTION_REQUIRED", lunch);
        assertCartRefused(item("l", lunch, 1, pick(burgerInLunch, 1)), "COMBO_GROUP_MINIMUM_NOT_MET", drinkGroup.id());
        assertCartRefused(
                item("l", lunch, 1, pick(burgerInLunch, 1), pick(wrapInLunch, 1), pick(colaInLunch, 1)),
                "COMBO_GROUP_MAXIMUM_EXCEEDED",
                mainGroup.id());
        assertCartRefused(
                item("l", lunch, 1, pick(burgerInLunch, 2), pick(colaInLunch, 1)),
                "COMBO_COMPONENT_NOT_REPEATABLE",
                burgerInLunch.id());
        assertCartRefused(item("l", burger, 1, pick(burgerInLunch, 1)), "COMBO_NOT_CONFIGURED", burger);
    }

    @Test
    @DisplayName("a component archived after the cart was built is no longer offered")
    void anArchivedComponentIsNotOffered() {
        composites.updateComponent(
                TENANT,
                BRAND,
                wrapInLunch.id(),
                wrapInLunch.version(),
                new CompositeProductAuthoringService.ComboComponentChanges(
                        1, 1, uz.horecaos.platform.catalog.domain.CatalogEntities.Status.ARCHIVED),
                "tester");

        assertCartRefused(
                item("l", lunch, 1, pick(wrapInLunch, 1), pick(colaInLunch, 1)),
                "COMBO_COMPONENT_NOT_OFFERED",
                wrapInLunch.id());
    }

    @Test
    @DisplayName("a component whose variant has been archived is no longer offered")
    void aComponentOfAnArchivedVariantIsNotOffered() {
        jdbc.sql("UPDATE catalog.variants SET status = 'ARCHIVED' WHERE id = :id")
                .param("id", wrap)
                .update();

        assertCartRefused(
                item("l", lunch, 1, pick(wrapInLunch, 1), pick(colaInLunch, 1)),
                "COMBO_COMPONENT_NOT_OFFERED",
                wrapInLunch.id());
    }

    @Test
    @DisplayName("another tenant's combo components are not offered to this tenant's cart")
    void anotherTenantsComponentsAreNotOffered() {
        UUID foreignComponent = foreignComboComponent();

        assertCartRefused(
                item("l", lunch, 1, pick(burgerInLunch, 1), pick(colaInLunch, 1), pick(foreignComponent, 1)),
                "COMBO_COMPONENT_NOT_OFFERED",
                foreignComponent);
    }

    @Test
    @DisplayName("a replayed idempotent quote returns the first one and mints no second selection")
    void aReplayedQuoteMintsNothingNew() {
        var first = quotes.quote(
                requestKeyed("combo-replay", comboLine("l", 1, pick(burgerInLunch, 1), pick(colaInLunch, 1))));
        var replay = quotes.quote(
                requestKeyed("combo-replay", comboLine("l", 1, pick(burgerInLunch, 1), pick(colaInLunch, 1))));

        assertThat(replay.quoteId()).isEqualTo(first.quoteId());
        assertThat(jdbc.sql("SELECT count(DISTINCT combo_selection_id) FROM pricing.quote_lines")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
        assertThat(jdbc.sql("SELECT count(*) FROM pricing.quotes")
                        .query(Long.class)
                        .single())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("the schema keeps a combo line's grouping key paired, and refuses one on a delivery-fee line")
    void theSchemaKeepsTheGroupingKeyWhole() {
        quotes.quote(request(comboLine("l", 1, pick(burgerInLunch, 1), pick(colaInLunch, 1))));

        assertThat(catchThrowable(() -> jdbc.sql("""
                                UPDATE pricing.quote_lines SET combo_selection_id = NULL
                                WHERE combo_container_variant_id IS NOT NULL
                                """).update()))
                .as("a selection without its container, or the reverse, describes nothing")
                .hasMessageContaining("ck_quote_line_combo_pair");
    }

    @Test
    @DisplayName("a stored combo line remembers the pairing and the quantities an amendment has to price again")
    void aStoredLineRemembersWhatAnAmendmentNeeds() {
        var quote = quotes.quote(request(comboLine("lunch-1", 3, pick(burgerInLunch, 1), pick(colaInLunch, 1))));

        QuoteSnapshot stored = quotes.quoteSnapshot(TENANT, quote.quoteId()).orElseThrow();

        assertThat(stored.lines())
                .extracting(QuoteSnapshot.Line::comboComponentId)
                .as("the pairing the line was priced from: the same cola costs a different amount in another combo")
                .containsExactly(burgerInLunch.id(), colaInLunch.id());
        assertThat(stored.lines())
                .extracting(QuoteSnapshot.Line::comboQuantity)
                .as("the combos the customer bought, which the units alone cannot give back")
                .containsOnly(3);
        assertThat(stored.lines())
                .extracting(QuoteSnapshot.Line::comboPickQuantity)
                .containsOnly(1);
        assertThat(stored.lines())
                .extracting(QuoteSnapshot.Line::cartLineKey)
                .as("an order reads the cart line's note and presets through the key without the position")
                .containsOnly("lunch-1");
        assertThat(catchThrowable(() -> jdbc.sql("""
                                UPDATE pricing.quote_lines SET combo_component_id = NULL
                                WHERE combo_container_variant_id IS NOT NULL
                                """).update()))
                .as("a grouped line without its pairing could never be repriced")
                .hasMessageContaining("ck_quote_line_combo_provenance");
    }

    @Test
    @DisplayName("a combo's component lines come back in the order they were priced, past the ninth")
    void componentLinesAreReadBackInPositionOrder() {
        UUID big = variant("BIG-LUNCH");
        ComboGroup group = composites.createComboGroup(
                new NewComboGroup(TENANT, BRAND, big, "MANY", "Many", "en", 0, 12, true, 0), "tester");
        List<ComboComponent> components = new java.util.ArrayList<>();
        for (int position = 0; position < 11; position++) {
            ComboComponent component = composites.addComponent(
                    TENANT, BRAND, group.id(), variant("PIECE-" + position), 1, position, "tester");
            priceAuthoring.setPrice(TENANT, BRAND, priceBook, PriceableType.COMBO_COMPONENT, component.id(), 1_000L);
            components.add(component);
        }
        PickSpec[] picks =
                components.stream().map(component -> pick(component, 1)).toArray(PickSpec[]::new);
        QuoteRequest.Line eleven = new QuoteRequest.Line(
                "big",
                big,
                1,
                List.of(),
                java.util.Arrays.stream(picks)
                        .map(spec -> new QuoteRequest.ComboPick(spec.componentId(), spec.quantity()))
                        .toList(),
                List.of());

        var quote = quotes.quote(request(eleven));

        QuoteSnapshot stored = quotes.quoteSnapshot(TENANT, quote.quoteId()).orElseThrow();
        assertThat(stored.lines())
                .extracting(QuoteSnapshot.Line::lineKey)
                .as("position 10 sorts as the number it is, not as the text '10' before '2'")
                .containsExactly(
                        "big~1", "big~2", "big~3", "big~4", "big~5", "big~6", "big~7", "big~8", "big~9", "big~10",
                        "big~11");
        assertThat(stored.lines())
                .extracting(QuoteSnapshot.Line::comboComponentId)
                .containsExactlyElementsOf(
                        components.stream().map(ComboComponent::id).toList());
    }

    @Test
    @DisplayName("the cart can ask whether a selection is allowed without pricing or storing anything")
    void aSelectionCanBeCheckedWithoutPricingIt() {
        CartPricingPort.SelectionCheck combo =
                quotes.checkSelection(TENANT, BRAND, item("l", lunch, 2, pick(wrapInLunch, 1), pick(colaInLunch, 1)));

        assertThat(combo.combo()).isTrue();
        assertThat(combo.soldVariantIds())
                .as("what the cart holds stock on and checks sale windows for: the components, never the container")
                .containsExactlyInAnyOrder(wrap, cola);

        CartPricingPort.SelectionCheck plain = quotes.checkSelection(TENANT, BRAND, item("b", burger, 1));
        assertThat(plain.combo()).isFalse();
        assertThat(plain.soldVariantIds()).containsExactly(burger);

        assertThat(jdbc.sql("SELECT count(*) FROM pricing.quotes")
                        .query(Long.class)
                        .single())
                .as("a check writes nothing")
                .isZero();

        CartPricingPort.PricingRefusedException refused = (CartPricingPort.PricingRefusedException) catchThrowable(
                () -> quotes.checkSelection(TENANT, BRAND, item("l", lunch, 1, pick(burgerInLunch, 1))));
        assertThat(refused.code())
                .as("the very rule pricing applies, so the cart and the quote cannot disagree")
                .isEqualTo("COMBO_GROUP_MINIMUM_NOT_MET");
        assertThat(refused.subjectId()).isEqualTo(drinkGroup.id());
    }

    @Test
    @DisplayName("an ordinary cart is unchanged: no combo columns, no hidden adjustment, the same lines as before")
    void anOrdinaryCartIsUntouched() {
        var quote = quotes.quote(request(line("b", burger, 2)));

        assertThat(quote.total().minor()).isEqualTo(60_000L);
        assertThat(quote.lines()).singleElement().satisfies(line -> {
            assertThat(line.lineId()).isEqualTo("b");
            assertThat(line.comboSelectionId()).isNull();
            assertThat(line.comboContainerVariantId()).isNull();
        });
        assertThat(quote.adjustments())
                .extracting(adjustment -> adjustment.descriptionCode())
                .doesNotContain("HIDDEN_MODIFIER", "COMBO_COMPONENT_PRICE", "NESTED_MODIFIERS");
    }

    // ----------------------------------------------------------- hidden groups

    @Test
    @DisplayName("a hidden delivery box is charged on DELIVERY and on nothing else")
    void aHiddenBoxIsChargedOnTheModesItNames() {
        UUID box = hiddenBox("DELIVERY-BOX", 1_500L);
        composites.setAttachmentPolicy(
                TENANT,
                BRAND,
                AttachmentOwnerType.PRODUCT,
                burgerProduct,
                box,
                1,
                new AttachmentPolicy(Visibility.HIDDEN_AUTO_SELECT, Set.of(FulfillmentMode.DELIVERY), null, null, null),
                "tester");

        var delivery = quotes.quote(modeRequest(FulfillmentMode.DELIVERY, line("b", burger, 2)));
        var pickup = quotes.quote(modeRequest(FulfillmentMode.PICKUP, line("b", burger, 2)));
        var dineIn = quotes.quote(modeRequest(FulfillmentMode.DINE_IN, line("b", burger, 2)));

        assertThat(delivery.total().minor())
                .as("a delivery box always reaches the receipt of a DELIVERY order")
                .isEqualTo(2 * (30_000L + 1_500L));
        assertThat(pickup.total().minor()).isEqualTo(60_000L);
        assertThat(dineIn.total().minor()).isEqualTo(60_000L);
        assertThat(delivery.adjustments())
                .filteredOn(adjustment -> "HIDDEN_MODIFIER".equals(adjustment.descriptionCode()))
                .singleElement()
                .satisfies(adjustment -> {
                    assertThat(adjustment.sourceId()).isNotNull();
                    assertThat(adjustment.amount().minor()).isEqualTo(3_000L);
                });
        assertThat(delivery.contextHash())
                .as("a mode that adds a charge is a different quote")
                .isNotEqualTo(pickup.contextHash());
        assertThat(pickup.contextHash()).isEqualTo(dineIn.contextHash());
    }

    @Test
    @DisplayName("a hidden group with no modes applies on every mode")
    void aHiddenGroupWithNoModesAppliesEverywhere() {
        UUID box = hiddenBox("ALWAYS-BOX", 500L);
        composites.setAttachmentPolicy(
                TENANT,
                BRAND,
                AttachmentOwnerType.PRODUCT,
                burgerProduct,
                box,
                1,
                new AttachmentPolicy(Visibility.HIDDEN_AUTO_SELECT, null, null, null, null),
                "tester");

        for (FulfillmentMode mode : FulfillmentMode.values()) {
            assertThat(quotes.quote(modeRequest(mode, line("b", burger, 1)))
                            .total()
                            .minor())
                    .as(mode.name())
                    .isEqualTo(30_500L);
        }
    }

    @Test
    @DisplayName("the delivery box rides on a combo's components, not on the container")
    void theBoxFollowsTheComponents() {
        UUID box = hiddenBox("DELIVERY-BOX", 1_500L);
        composites.setAttachmentPolicy(
                TENANT,
                BRAND,
                AttachmentOwnerType.PRODUCT,
                burgerProduct,
                box,
                1,
                new AttachmentPolicy(Visibility.HIDDEN_AUTO_SELECT, Set.of(FulfillmentMode.DELIVERY), null, null, null),
                "tester");

        var quote = quotes.quote(
                modeRequest(FulfillmentMode.DELIVERY, comboLine("l", 1, pick(burgerInLunch, 1), pick(colaInLunch, 1))));

        assertThat(quote.lines())
                .extracting(line -> line.unitAmount().minor())
                .as("the burger carries its box; the cola, whose product has no hidden group, does not")
                .containsExactly(31_500L, 3_000L);
    }

    @Test
    @DisplayName("a variant that attaches the group visibly switches the product's hidden charge off")
    void aVariantLevelAttachmentWinsOverTheProducts() {
        UUID box = hiddenBox("DELIVERY-BOX", 1_500L);
        composites.setAttachmentPolicy(
                TENANT,
                BRAND,
                AttachmentOwnerType.PRODUCT,
                burgerProduct,
                box,
                1,
                new AttachmentPolicy(Visibility.HIDDEN_AUTO_SELECT, null, null, null, null),
                "tester");
        assertThat(quotes.quote(modeRequest(FulfillmentMode.DELIVERY, line("b", burger, 1)))
                        .total()
                        .minor())
                .isEqualTo(31_500L);

        composites.attachModifierGroupToVariant(TENANT, BRAND, burger, box, 0, "tester");

        assertThat(quotes.quote(modeRequest(FulfillmentMode.DELIVERY, line("b", burger, 1)))
                        .total()
                        .minor())
                .as("the variant-level attachment is visible, so no hidden charge applies to this variant")
                .isEqualTo(30_000L);
    }

    @Test
    @DisplayName("a hidden group that is not exactly one required option refuses the quote instead of guessing")
    void anAmbiguousHiddenGroupRefusesTheQuote() {
        UUID box = hiddenBox("DELIVERY-BOX", 1_500L);
        composites.setAttachmentPolicy(
                TENANT,
                BRAND,
                AttachmentOwnerType.PRODUCT,
                burgerProduct,
                box,
                1,
                new AttachmentPolicy(Visibility.HIDDEN_AUTO_SELECT, null, null, null, null),
                "tester");
        // Authoring moved on after publication: a second option appears in the group.
        UUID second = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.modifier_options (id, tenant_id, brand_id, modifier_group_id, code, status)
                VALUES (:id, :tenantId, :brandId, :groupId, 'BIG-BOX', 'ACTIVE')
                """)
                .param("id", second)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("groupId", box)
                .update();

        Throwable refused = catchThrowable(() -> quotes.priceCart(new CartPricingPort.PricingCommand(
                TENANT,
                BRAND,
                LOCATION,
                null,
                "STOREFRONT",
                List.of(new CartPricingPort.PricingCommand.Item("b", burger, 1, List.of())),
                "key-ambiguous",
                null,
                null,
                null,
                FulfillmentMode.DELIVERY)));

        assertThat(refused).isInstanceOf(CartPricingPort.PricingRefusedException.class);
        assertThat(((CartPricingPort.PricingRefusedException) refused).code())
                .isEqualTo("HIDDEN_MODIFIER_GROUP_AMBIGUOUS");
        assertThat(((CartPricingPort.PricingRefusedException) refused).subjectId())
                .isEqualTo(box);
    }

    @Test
    @DisplayName("another tenant's hidden attachments never apply to this tenant's cart")
    void aForeignHiddenGroupDoesNotLeak() {
        // A hidden group on a product with the same id cannot exist in another tenant,
        // but a hidden attachment in another brand of another tenant must not be read:
        // the lookup carries the tenant and the brand.
        UUID foreignVariant = foreignHiddenVariant();

        var quote = quotes.quote(modeRequest(FulfillmentMode.DELIVERY, line("b", burger, 1)));

        assertThat(quote.total().minor()).isEqualTo(30_000L);
        assertThat(foreignVariant).isNotNull();
    }

    // ----------------------------------------------------------------- nesting

    @Test
    @DisplayName("an option that links a variant offers that variant's own groups, one level deep")
    void aNestedSelectionIsPricedAndBounded() {
        UUID sideMeal = variant("SIDE-MEAL");
        UUID dips = group("DIPS", false, 0, 1);
        UUID ketchup = option(dips, "KETCHUP", null, 500L);
        UUID mayo = option(dips, "MAYO", null, 700L);
        UUID spicyGroup = group("SPICE", true, 1, 1);
        UUID hot = option(spicyGroup, "HOT", null, 0L);
        composites.attachModifierGroupToVariant(TENANT, BRAND, sideMeal, dips, 0, "tester");

        UUID sides = group("SIDES", false, 0, 1);
        UUID addSide = option(sides, "ADD-SIDE", sideMeal, 10_000L);
        attachToProduct(burgerProduct, sides);

        var priced = quotes.quote(request(new QuoteRequest.Line(
                "b",
                burger,
                2,
                List.of(addSide),
                List.of(),
                List.of(new QuoteRequest.NestedModifier(addSide, ketchup)))));
        assertThat(priced.total().minor())
                .as("2 x (30,000 + 10,000 for the side + 500 for the dip)")
                .isEqualTo(81_000L);

        // Two dips from a group that allows one.
        assertThat(cartRefusalCode(new CartPricingPort.PricingCommand.Item(
                        "b",
                        burger,
                        1,
                        List.of(addSide),
                        List.of(),
                        List.of(
                                new CartPricingPort.PricingCommand.NestedModifier(addSide, ketchup),
                                new CartPricingPort.PricingCommand.NestedModifier(addSide, mayo)))))
                .isEqualTo("MODIFIER_GROUP_MAXIMUM_EXCEEDED");
        // A dip that is not on the linked variant.
        assertThat(cartRefusalCode(new CartPricingPort.PricingCommand.Item(
                        "b",
                        burger,
                        1,
                        List.of(addSide),
                        List.of(),
                        List.of(new CartPricingPort.PricingCommand.NestedModifier(addSide, hot)))))
                .isEqualTo("MODIFIER_NESTED_OPTION_NOT_OFFERED");

        // The variant now requires a spice level: unanswered, the cart is refused even
        // though the customer sent no nested selection at all.
        composites.attachModifierGroupToVariant(TENANT, BRAND, sideMeal, spicyGroup, 1, "tester");
        assertThat(cartRefusalCode(new CartPricingPort.PricingCommand.Item(
                        "b", burger, 1, List.of(addSide), List.of(), List.of())))
                .isEqualTo("MODIFIER_GROUP_MINIMUM_NOT_MET");
    }

    @Test
    @DisplayName("a third level is refused instead of being priced as if it were the second")
    void aThirdLevelIsRefused() {
        UUID sideMeal = variant("SIDE-MEAL");
        UUID dips = group("DIPS", false, 0, 1);
        UUID ketchup = option(dips, "KETCHUP", null, 500L);
        UUID deeperGroup = group("SPICE", false, 0, 1);
        UUID hot = option(deeperGroup, "HOT", null, 100L);
        composites.attachModifierGroupToVariant(TENANT, BRAND, sideMeal, dips, 0, "tester");
        UUID sides = group("SIDES", false, 0, 1);
        UUID addSide = option(sides, "ADD-SIDE", sideMeal, 10_000L);
        attachToProduct(burgerProduct, sides);

        String code = cartRefusalCode(new CartPricingPort.PricingCommand.Item(
                "b",
                burger,
                1,
                List.of(addSide),
                List.of(),
                List.of(
                        new CartPricingPort.PricingCommand.NestedModifier(addSide, ketchup),
                        new CartPricingPort.PricingCommand.NestedModifier(ketchup, hot))));

        assertThat(code).isEqualTo("MODIFIER_NESTING_DEPTH_EXCEEDED");
    }

    // ---------------------------------------------------------------- fixtures

    private void assertCartRefused(CartPricingPort.PricingCommand.Item item, String code, UUID subject) {
        Throwable refused = catchThrowable(() -> quotes.priceCart(command(item)));

        assertThat(refused).isInstanceOf(CartPricingPort.PricingRefusedException.class);
        var refusal = (CartPricingPort.PricingRefusedException) refused;
        assertThat(refusal.code()).isEqualTo(code);
        assertThat(refusal.subjectId()).isEqualTo(subject);
    }

    private String cartRefusalCode(CartPricingPort.PricingCommand.Item item) {
        Throwable refused = catchThrowable(() -> quotes.priceCart(command(item)));
        assertThat(refused).isInstanceOf(CartPricingPort.PricingRefusedException.class);
        return ((CartPricingPort.PricingRefusedException) refused).code();
    }

    private CartPricingPort.PricingCommand command(CartPricingPort.PricingCommand.Item item) {
        return new CartPricingPort.PricingCommand(
                TENANT, BRAND, LOCATION, null, "STOREFRONT", List.of(item), "key-" + UUID.randomUUID());
    }

    private CartPricingPort.PricingCommand.Item item(String key, UUID variant, int quantity, PickSpec... picks) {
        return new CartPricingPort.PricingCommand.Item(
                key,
                variant,
                quantity,
                List.of(),
                java.util.Arrays.stream(picks)
                        .map(pick -> new CartPricingPort.PricingCommand.ComboPick(pick.componentId(), pick.quantity()))
                        .toList(),
                List.of());
    }

    private record PickSpec(UUID componentId, int quantity) {}

    private static PickSpec pick(ComboComponent component, int quantity) {
        return new PickSpec(component.id(), quantity);
    }

    private static PickSpec pick(UUID componentId, int quantity) {
        return new PickSpec(componentId, quantity);
    }

    private QuoteRequest.Line comboLine(String id, int quantity, PickSpec... picks) {
        return new QuoteRequest.Line(
                id,
                lunch,
                quantity,
                List.of(),
                java.util.Arrays.stream(picks)
                        .map(spec -> new QuoteRequest.ComboPick(spec.componentId(), spec.quantity()))
                        .toList(),
                List.of());
    }

    private static QuoteRequest.Line line(String id, UUID variant, int quantity) {
        return new QuoteRequest.Line(id, variant, quantity, List.of());
    }

    private QuoteRequest request(QuoteRequest.Line... lines) {
        return new QuoteRequest(TENANT, BRAND, LOCATION, null, "STOREFRONT", List.of(lines), null);
    }

    private QuoteRequest requestKeyed(String key, QuoteRequest.Line... lines) {
        return new QuoteRequest(TENANT, BRAND, LOCATION, null, "STOREFRONT", List.of(lines), key);
    }

    private QuoteRequest modeRequest(FulfillmentMode mode, QuoteRequest.Line... lines) {
        return new QuoteRequest(
                TENANT, BRAND, LOCATION, null, "STOREFRONT", List.of(lines), null, null, null, null, mode);
    }

    /** A hidden-capable group of one required option, priced in the live book. */
    private UUID hiddenBox(String code, long price) {
        UUID group = group(code, true, 1, 1);
        UUID option = option(group, code + "-OPTION", null, price);
        assertThat(option).isNotNull();
        attachToProduct(burgerProduct, group);
        return group;
    }

    private void attachToProduct(UUID productId, UUID groupId) {
        catalogStore.attachModifierGroupToProduct(TENANT, BRAND, productId, groupId, 0);
    }

    private UUID group(String code, boolean required, int minimum, int maximum) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.modifier_groups (id, tenant_id, brand_id, code, is_required,
                    minimum_selections, maximum_selections, status)
                VALUES (:id, :tenantId, :brandId, :code, :required, :minimum, :maximum, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("code", code)
                .param("required", required)
                .param("minimum", minimum)
                .param("maximum", maximum)
                .update();
        return id;
    }

    private UUID option(UUID group, String code, @Nullable UUID linkedVariant, long price) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.modifier_options (id, tenant_id, brand_id, modifier_group_id, code,
                    linked_variant_id, status)
                VALUES (:id, :tenantId, :brandId, :groupId, :code, :linked, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("groupId", group)
                .param("code", code)
                .param("linked", linkedVariant)
                .update();
        priceAuthoring.setPrice(TENANT, BRAND, priceBook, PriceableType.MODIFIER_OPTION, id, price);
        return id;
    }

    /** A combo component belonging to another tenant, to prove a pick cannot reach across. */
    private UUID foreignComboComponent() {
        UUID foreignProduct = UUID.randomUUID();
        UUID foreignContainer = UUID.randomUUID();
        UUID foreignDish = UUID.randomUUID();
        UUID dishProduct = UUID.randomUUID();
        seedForeignBrand();
        insertProduct(foreignProduct, "F-LUNCH", OTHER_TENANT, OTHER_BRAND);
        insertVariant(foreignContainer, foreignProduct, OTHER_TENANT, OTHER_BRAND);
        insertProduct(dishProduct, "F-DISH", OTHER_TENANT, OTHER_BRAND);
        insertVariant(foreignDish, dishProduct, OTHER_TENANT, OTHER_BRAND);
        UUID group = UUID.randomUUID();
        UUID component = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.combo_groups (id, tenant_id, brand_id, container_variant_id, code)
                VALUES (:id, :tenantId, :brandId, :container, 'F')
                """)
                .param("id", group)
                .param("tenantId", OTHER_TENANT)
                .param("brandId", OTHER_BRAND)
                .param("container", foreignContainer)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.combo_components (id, tenant_id, brand_id, combo_group_id, component_variant_id)
                VALUES (:id, :tenantId, :brandId, :group, :variant)
                """)
                .param("id", component)
                .param("tenantId", OTHER_TENANT)
                .param("brandId", OTHER_BRAND)
                .param("group", group)
                .param("variant", foreignDish)
                .update();
        return component;
    }

    /** A hidden always-on attachment in another tenant, on a variant nobody here sells. */
    private UUID foreignHiddenVariant() {
        seedForeignBrand();
        UUID product = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        insertProduct(product, "F-HIDDEN", OTHER_TENANT, OTHER_BRAND);
        insertVariant(variantId, product, OTHER_TENANT, OTHER_BRAND);
        UUID group = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO catalog.modifier_groups (id, tenant_id, brand_id, code, is_required,
                    minimum_selections, maximum_selections, status)
                VALUES (:id, :tenantId, :brandId, 'F-BOX', true, 1, 1, 'ACTIVE')
                """)
                .param("id", group)
                .param("tenantId", OTHER_TENANT)
                .param("brandId", OTHER_BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.product_modifier_groups (tenant_id, brand_id, product_id, modifier_group_id,
                    visibility)
                VALUES (:tenantId, :brandId, :product, :group, 'HIDDEN_AUTO_SELECT')
                """)
                .param("tenantId", OTHER_TENANT)
                .param("brandId", OTHER_BRAND)
                .param("product", product)
                .param("group", group)
                .update();
        return variantId;
    }

    private boolean foreignBrandSeeded;

    private void seedForeignBrand() {
        if (foreignBrandSeeded) {
            return;
        }
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'composite-other', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", OTHER_TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'OTHER', 'other', 'Brand', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("tenantId", OTHER_TENANT).update();
        foreignBrandSeeded = true;
    }

    private void authorTheLunchBox() {
        UUID lunchProduct = UUID.randomUUID();
        lunch = UUID.randomUUID();
        insertProduct(lunchProduct, "LUNCH-BOX", TENANT, BRAND);
        insertVariant(lunch, lunchProduct, TENANT, BRAND);
        linkToCatalog(lunchProduct);

        burgerProduct = UUID.randomUUID();
        burger = UUID.randomUUID();
        insertProduct(burgerProduct, "BURGER", TENANT, BRAND);
        insertVariant(burger, burgerProduct, TENANT, BRAND);
        linkToCatalog(burgerProduct);
        wrap = dish("WRAP");
        fries = dish("FRIES");
        cola = dish("COLA");

        mainGroup = composites.createComboGroup(
                new NewComboGroup(TENANT, BRAND, lunch, "MAIN", "Choose a main", "en", 1, 1, false, 0), "tester");
        ComboGroup sideGroup = composites.createComboGroup(
                new NewComboGroup(TENANT, BRAND, lunch, "SIDE", "Add sides", "en", 0, 2, true, 1), "tester");
        drinkGroup = composites.createComboGroup(
                new NewComboGroup(TENANT, BRAND, lunch, "DRINK", "Choose a drink", "en", 1, 1, false, 2), "tester");

        burgerInLunch = composites.addComponent(TENANT, BRAND, mainGroup.id(), burger, 1, 0, "tester");
        wrapInLunch = composites.addComponent(TENANT, BRAND, mainGroup.id(), wrap, 1, 1, "tester");
        ComboComponent friesInLunch = composites.addComponent(TENANT, BRAND, sideGroup.id(), fries, 1, 0, "tester");
        colaInLunch = composites.addComponent(TENANT, BRAND, drinkGroup.id(), cola, 1, 0, "tester");

        priceAuthoring.setPrice(TENANT, BRAND, priceBook, PriceableType.VARIANT, burger, 30_000L);
        priceAuthoring.setPrice(TENANT, BRAND, priceBook, PriceableType.COMBO_COMPONENT, burgerInLunch.id(), 30_000L);
        priceAuthoring.setPrice(TENANT, BRAND, priceBook, PriceableType.COMBO_COMPONENT, wrapInLunch.id(), 28_000L);
        priceAuthoring.setPrice(TENANT, BRAND, priceBook, PriceableType.COMBO_COMPONENT, friesInLunch.id(), 8_000L);
        priceAuthoring.setPrice(TENANT, BRAND, priceBook, PriceableType.COMBO_COMPONENT, colaInLunch.id(), 3_000L);
    }

    private UUID dish(String code) {
        UUID product = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        insertProduct(product, code, TENANT, BRAND);
        insertVariant(variantId, product, TENANT, BRAND);
        linkToCatalog(product);
        return variantId;
    }

    private UUID variant(String code) {
        return dish(code);
    }

    private void linkToCatalog(UUID productId) {
        jdbc.sql("""
                INSERT INTO catalog.catalog_products (tenant_id, brand_id, catalog_id, product_id)
                VALUES (:tenantId, :brandId, :catalogId, :productId)
                """)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .param("productId", productId)
                .update();
    }

    private void insertProduct(UUID id, String code, UUID tenantId, UUID brandId) {
        jdbc.sql("""
                INSERT INTO catalog.products (id, tenant_id, brand_id, code, status)
                VALUES (:id, :tenantId, :brandId, :code, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("code", code)
                .update();
        jdbc.sql("""
                INSERT INTO catalog.translations (tenant_id, brand_id, entity_type, entity_id, locale, name)
                VALUES (:tenantId, :brandId, 'PRODUCT', :id, 'uz', :name)
                """)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("id", id)
                .param("name", code)
                .update();
    }

    private void insertVariant(UUID id, UUID productId, UUID tenantId, UUID brandId) {
        jdbc.sql("""
                INSERT INTO catalog.variants (id, tenant_id, brand_id, product_id, is_default, status)
                VALUES (:id, :tenantId, :brandId, :productId, true, 'ACTIVE')
                """)
                .param("id", id)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("productId", productId)
                .update();
    }

    private void seedTenancyAndCatalog() {
        foreignBrandSeeded = false;
        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency,
                    default_timezone, status, version)
                VALUES (:id, 'composite-quote', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (id, tenant_id, brand_id, code, slug, display_name,
                    timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'main-01', 'Main', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'STOREFRONT', 'ACTIVE')
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
        jdbc.sql("""
                INSERT INTO catalog.publications (id, tenant_id, brand_id, catalog_id, channel, status,
                    content_hash, activated_at)
                VALUES (:id, :tenantId, :brandId, :catalogId, 'STOREFRONT', 'PUBLISHED', 'hash', now())
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("catalogId", catalogId)
                .update();

        priceBook = UUID.randomUUID();
        var from = java.time.OffsetDateTime.ofInstant(NOW.minus(Duration.ofDays(1)), ZoneOffset.UTC);
        jdbc.sql("""
                INSERT INTO pricing.price_books (id, tenant_id, brand_id, name, currency, status, valid_from, priority)
                VALUES (:id, :tenantId, :brandId, 'BRAND_MENU', 'UZS', 'ACTIVE', :from, 0)
                """)
                .param("id", priceBook)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("from", from)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.price_book_assignments (id, tenant_id, brand_id, price_book_id,
                    scope_type, scope_id, valid_from, priority)
                VALUES (:id, :tenantId, :brandId, :book, 'BRAND', NULL, :from, 0)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("book", priceBook)
                .param("from", from)
                .update();
        jdbc.sql("""
                INSERT INTO pricing.tax_profiles (id, tenant_id, brand_id, jurisdiction_code, mode,
                    rate_basis_points, valid_from)
                VALUES (:id, :tenantId, :brandId, 'UZ', 'INCLUSIVE', 1200, :from)
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("from", from)
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
