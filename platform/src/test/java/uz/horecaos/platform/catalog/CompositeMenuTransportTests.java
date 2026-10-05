package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
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
import uz.horecaos.platform.catalog.api.MenuAvailabilityLookup;
import uz.horecaos.platform.catalog.api.MenuAvailabilityLookup.VariantAvailability;
import uz.horecaos.platform.catalog.api.MenuPriceLookup;
import uz.horecaos.platform.catalog.api.VariantPricingLookup;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.CatalogPublicationService;
import uz.horecaos.platform.catalog.application.CatalogSnapshotLoader;
import uz.horecaos.platform.catalog.application.CatalogValidator;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService.AttachmentPolicy;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService.NewComboGroup;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuComboGroup;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuProduct;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.CompositeProducts.AttachmentOwnerType;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboComponent;
import uz.horecaos.platform.catalog.domain.CompositeProducts.ComboGroup;
import uz.horecaos.platform.catalog.domain.CompositeProducts.Visibility;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.domain.PublicationStatus;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCommentPresetStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCompositeCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcMenuStore;
import uz.horecaos.platform.catalog.infrastructure.tenancy.JdbcCatalogTenantContext;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * What a channel is told about a combo and about a product's own use of a modifier group
 * (ADR 0136's publication section), through the real loader, the real publication and the
 * real storefront read.
 *
 * <p>The choice screen a customer sees is built from this alone, with no reach back into
 * authoring: so what is published, what is read live (prices, orderability), and what is
 * deliberately not written (a price) are each pinned here.
 */
class CompositeMenuTransportTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final String LOCALE = "uz";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final FiscalClassification UNCLASSIFIED = FiscalClassification.unclassified();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCatalogStore store;
    private CatalogAuthoringService authoring;
    private CompositeProductAuthoringService composites;
    private CatalogPublicationService publication;
    private StorefrontCatalogQuery storefront;
    private LiveInputs live;
    private UUID catalogId;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for composite menu transport tests");
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
        jdbc.sql("TRUNCATE TABLE catalog.publication_items, catalog.publications, "
                        + "catalog.location_offerings, catalog.media_relations, catalog.translations, "
                        + "catalog.combo_components, catalog.combo_groups, "
                        + "catalog.product_modifier_groups, catalog.variant_modifier_groups, "
                        + "catalog.category_products, catalog.catalog_products, catalog.modifier_options, "
                        + "catalog.modifier_groups, catalog.categories, catalog.fiscal_classifications, "
                        + "catalog.fees, catalog.branch_menu_bindings, catalog.menu_items, catalog.menus, "
                        + "catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE media.assets CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenancy();

        var mapper = JsonMapper.builder().build();
        store = new JdbcCatalogStore(jdbc, mapper);
        var audit = new JdbcAuditRecorder(jdbc, mapper);
        CommercialDefaults.Wired commercial = CommercialDefaults.wire(jdbc, Clock.systemUTC());
        authoring = new CatalogAuthoringService(
                store, audit, commercial.entitlements(), commercial.usage(), Clock.systemUTC());
        composites = new CompositeProductAuthoringService(
                new JdbcCompositeCatalogStore(jdbc), store, audit, Clock.systemUTC());

        live = new LiveInputs();
        publication = new CatalogPublicationService(
                store,
                new CatalogValidator(),
                new CatalogSnapshotLoader(store, (tenantId, assets) -> true, live, LOCALE),
                new JdbcSalesChannelStore(jdbc),
                Clock.fixed(Instant.parse("2026-10-01T10:00:00Z"), ZoneOffset.UTC));
        storefront = new StorefrontCatalogQuery(
                store,
                live,
                live,
                new JdbcMenuStore(jdbc),
                new JdbcCatalogTenantContext(jdbc),
                Clock.systemUTC(),
                new JdbcCommentPresetStore(jdbc));
        catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
    }

    // ------------------------------------------------------------- publication

    @Test
    @DisplayName("a published combo carries its group, its components and their wording, and no price")
    void aComboIsPublishedAsAGroupWithItsComponents() {
        var combo = comboOfTwo();

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(result.status()).isEqualTo(PublicationStatus.PUBLISHED);
        var groupItems = store.publicationItems(result.publicationId(), EntityType.COMBO_GROUP);
        assertThat(groupItems).hasSize(1);
        var content = groupItems.get(0).content();
        assertThat(groupItems.get(0).entityId()).isEqualTo(combo.group().id());
        assertThat(content)
                .containsEntry("containerVariantId", combo.container().toString())
                .containsEntry("minimumSelections", 1)
                .containsEntry("maximumSelections", 2)
                .containsEntry("allowSameComponentMultipleTimes", false);
        var components = listOfMaps(content.get("components"));
        assertThat(components)
                .extracting(component -> component.get("componentId"))
                .containsExactly(
                        combo.burgerPairing().id().toString(),
                        combo.colaPairing().id().toString());
        assertThat(components.get(0).get("variantId"))
                .isEqualTo(combo.burgerVariant().toString());
        assertThat(components.get(0).toString())
                .as("the dish is named in every locale it has, so a channel never reaches back for it")
                .contains("Burger");
        assertThat(content.toString().toLowerCase())
                .as("a price is read when the menu is served, as every other price is")
                .doesNotContain("amount");
    }

    @Test
    @DisplayName("the container's product lists its groups; a product with no combo says nothing at all")
    void aProductListsItsComboGroupsOnlyWhenItHasSome() {
        var combo = comboOfTwo();

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        var products = store.publicationItems(result.publicationId(), EntityType.PRODUCT);
        var container = products.stream()
                .filter(item -> item.entityId().equals(combo.containerProduct()))
                .findFirst()
                .orElseThrow();
        var plain = products.stream()
                .filter(item -> item.entityId().equals(combo.burgerProduct()))
                .findFirst()
                .orElseThrow();
        assertThat(container.content().get("comboGroupIds"))
                .isEqualTo(List.of(combo.group().id().toString()));
        assertThat(plain.content())
                .as("content for a product that is no combo is exactly what it always was")
                .doesNotContainKeys("comboGroupIds", "modifierGroupPolicies");
    }

    @Test
    @DisplayName("an archived group and an archived component are not published")
    void anArchivedGroupOrComponentIsNotPublished() {
        var combo = comboOfTwo();
        composites.updateComponent(
                TENANT,
                BRAND,
                combo.colaPairing().id(),
                combo.colaPairing().version(),
                new CompositeProductAuthoringService.ComboComponentChanges(
                        1, 1, uz.horecaos.platform.catalog.domain.CatalogEntities.Status.ARCHIVED),
                "tester");

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        var content = store.publicationItems(result.publicationId(), EntityType.COMBO_GROUP)
                .get(0)
                .content();
        var components = listOfMaps(content.get("components"));
        assertThat(components).hasSize(1);
        assertThat(components.get(0).get("componentId"))
                .isEqualTo(combo.burgerPairing().id().toString());
    }

    @Test
    @DisplayName("a product's override is published as the effective rule and the shared group is untouched")
    void anOverrideIsPublishedAsTheEffectiveRule() {
        var burger = authoring.createProduct(
                TENANT, BRAND, catalogId, "BURGER", "Burger", null, LOCALE, "SKU-B", "PIECE", UNCLASSIFIED, ACTOR);
        var wrap = authoring.createProduct(
                TENANT, BRAND, catalogId, "WRAP", "Wrap", null, LOCALE, "SKU-W", "PIECE", UNCLASSIFIED, ACTOR);
        UUID sauces = authoring.createModifierGroup(TENANT, BRAND, "SAUCES", "Sauces", LOCALE, false, 0, 3, false);
        authoring.addModifierOption(TENANT, BRAND, sauces, "MAYO", "Mayo", LOCALE, null, 1, 0, UNCLASSIFIED, ACTOR);
        authoring.addModifierOption(TENANT, BRAND, sauces, "BBQ", "BBQ", LOCALE, null, 1, 1, UNCLASSIFIED, ACTOR);
        authoring.attachModifierGroup(TENANT, BRAND, burger.productId(), sauces, 0);
        authoring.attachModifierGroup(TENANT, BRAND, wrap.productId(), sauces, 0);
        composites.setAttachmentPolicy(
                TENANT,
                BRAND,
                AttachmentOwnerType.PRODUCT,
                burger.productId(),
                sauces,
                1,
                new AttachmentPolicy(Visibility.VISIBLE, null, true, 1, 2),
                "tester");

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        var products = store.publicationItems(result.publicationId(), EntityType.PRODUCT);
        var burgerContent = products.stream()
                .filter(item -> item.entityId().equals(burger.productId()))
                .findFirst()
                .orElseThrow()
                .content();
        var wrapContent = products.stream()
                .filter(item -> item.entityId().equals(wrap.productId()))
                .findFirst()
                .orElseThrow()
                .content();
        assertThat(burgerContent.get("modifierGroupPolicies"))
                .isEqualTo(List.of(Map.of(
                        "groupId",
                        sauces.toString(),
                        "required",
                        true,
                        "minimumSelections",
                        1,
                        "maximumSelections",
                        2)));
        assertThat(wrapContent)
                .as("a second product attaching the same group is unaffected")
                .doesNotContainKey("modifierGroupPolicies");
        var group = store.publicationItems(result.publicationId(), EntityType.MODIFIER_GROUP).stream()
                .filter(item -> item.entityId().equals(sauces))
                .findFirst()
                .orElseThrow()
                .content();
        assertThat(group)
                .containsEntry("required", false)
                .containsEntry("minimumSelections", 0)
                .containsEntry("maximumSelections", 3);
    }

    @Test
    @DisplayName("an option is published with its name in every locale it has, and not at all when nobody named it")
    void anOptionCarriesItsNameWhenItHasOne() {
        var burger = authoring.createProduct(
                TENANT, BRAND, catalogId, "BURGER", "Burger", null, LOCALE, "SKU-B", "PIECE", UNCLASSIFIED, ACTOR);
        UUID sauces = authoring.createModifierGroup(TENANT, BRAND, "SAUCES", "Sauces", LOCALE, false, 0, 3, false);
        UUID mayo = authoring.addModifierOption(
                TENANT, BRAND, sauces, "MAYO", "Mayonez", LOCALE, null, 1, 0, UNCLASSIFIED, ACTOR);
        authoring.attachModifierGroup(TENANT, BRAND, burger.productId(), sauces, 0);
        jdbc.sql("DELETE FROM catalog.translations WHERE entity_type = 'MODIFIER_OPTION' AND entity_id <> :id")
                .param("id", mayo)
                .update();
        UUID unnamed = authoring.addModifierOption(
                TENANT, BRAND, sauces, "BBQ", "BBQ", LOCALE, null, 1, 1, UNCLASSIFIED, ACTOR);
        jdbc.sql("DELETE FROM catalog.translations WHERE entity_type = 'MODIFIER_OPTION' AND entity_id = :id")
                .param("id", unnamed)
                .update();

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        var options = listOfMaps(store.publicationItems(result.publicationId(), EntityType.MODIFIER_GROUP)
                .get(0)
                .content()
                .get("options"));
        var named = options.stream()
                .filter(option -> mayo.toString().equals(option.get("optionId")))
                .findFirst()
                .orElseThrow();
        var nameless = options.stream()
                .filter(option -> unnamed.toString().equals(option.get("optionId")))
                .findFirst()
                .orElseThrow();
        assertThat(named.toString()).contains("Mayonez");
        assertThat(nameless).doesNotContainKey("names");
    }

    @Test
    @DisplayName("a variant's own groups and the rules it states for them are published with the variant, "
            + "and a group only a variant carries is published at all")
    void aVariantsOwnGroupsAndRulesArePublishedWithIt() {
        var fries = authoring.createProduct(
                TENANT, BRAND, catalogId, "FRIES", "Fries", null, LOCALE, "SKU-F", "PIECE", UNCLASSIFIED, ACTOR);
        UUID large = authoring.addVariant(
                TENANT, BRAND, fries.productId(), "SKU-F-L", "PIECE", "Large", LOCALE, 1, UNCLASSIFIED, ACTOR);
        UUID dips = authoring.createModifierGroup(TENANT, BRAND, "DIPS", "Dips", LOCALE, false, 0, 2, false);
        authoring.addModifierOption(TENANT, BRAND, dips, "KETCHUP", "Ketchup", LOCALE, null, 1, 0, UNCLASSIFIED, ACTOR);
        UUID extras = authoring.createModifierGroup(TENANT, BRAND, "EXTRAS", "Extras", LOCALE, false, 0, 1, false);
        authoring.addModifierOption(TENANT, BRAND, extras, "CHEESE", "Cheese", LOCALE, null, 1, 0, UNCLASSIFIED, ACTOR);
        authoring.attachModifierGroup(TENANT, BRAND, fries.productId(), dips, 0);
        composites.attachModifierGroupToVariant(TENANT, BRAND, large, dips, 0, "tester");
        composites.setAttachmentPolicy(
                TENANT,
                BRAND,
                AttachmentOwnerType.VARIANT,
                large,
                dips,
                1,
                new AttachmentPolicy(Visibility.VISIBLE, null, true, 1, 1),
                "tester");
        composites.attachModifierGroupToVariant(TENANT, BRAND, large, extras, 1, "tester");

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(result.status()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(store.publicationItems(result.publicationId(), EntityType.MODIFIER_GROUP))
                .extracting(item -> item.entityId())
                .as("the extras are attached to no product, and are in the menu all the same")
                .containsExactlyInAnyOrder(dips, extras);
        var friesItem = store.publicationItems(result.publicationId(), EntityType.PRODUCT).stream()
                .filter(item -> item.entityId().equals(fries.productId()))
                .findFirst()
                .orElseThrow();
        assertThat(friesItem.content().get("modifierGroupIds"))
                .as("the product still lists only what it attaches")
                .isEqualTo(List.of(dips.toString()));
        var variants = listOfMaps(friesItem.content().get("variants"));
        var largeEntry = variants.stream()
                .filter(entry -> large.toString().equals(entry.get("variantId")))
                .findFirst()
                .orElseThrow();
        var smallEntry = variants.stream()
                .filter(entry -> fries.defaultVariantId().toString().equals(entry.get("variantId")))
                .findFirst()
                .orElseThrow();
        assertThat(largeEntry.get("modifierGroupIds")).isEqualTo(List.of(dips.toString(), extras.toString()));
        assertThat(largeEntry.get("modifierGroupPolicies"))
                .as(
                        "the rule the large size holds the customer to for each of its groups, the override laid over the group's own")
                .isEqualTo(List.of(
                        Map.of(
                                "groupId",
                                dips.toString(),
                                "required",
                                true,
                                "minimumSelections",
                                1,
                                "maximumSelections",
                                1),
                        Map.of(
                                "groupId",
                                extras.toString(),
                                "required",
                                false,
                                "minimumSelections",
                                0,
                                "maximumSelections",
                                1)));
        assertThat(smallEntry)
                .as("a variant that adds and overrides nothing publishes exactly what it always did")
                .doesNotContainKeys("modifierGroupIds", "modifierGroupPolicies");
    }

    @Test
    @DisplayName("an option that links a variant carrying groups is published with the choices it opens, "
            + "and the groups of that variant are published for it")
    void aNestedChoiceIsPublishedUnderItsParentOption() {
        var side = authoring.createProduct(
                TENANT, BRAND, catalogId, "SIDE", "Side", null, LOCALE, "SKU-S", "PIECE", UNCLASSIFIED, ACTOR);
        var sauce = authoring.createProduct(
                TENANT, BRAND, catalogId, "SAUCE", "Sauce", null, LOCALE, "SKU-SAUCE", "PIECE", UNCLASSIFIED, ACTOR);
        UUID heat = authoring.createModifierGroup(TENANT, BRAND, "HEAT", "Heat", LOCALE, true, 1, 1, false);
        authoring.addModifierOption(TENANT, BRAND, heat, "MILD", "Mild", LOCALE, null, 1, 0, UNCLASSIFIED, ACTOR);
        authoring.addModifierOption(TENANT, BRAND, heat, "HOT", "Hot", LOCALE, null, 1, 1, UNCLASSIFIED, ACTOR);
        composites.attachModifierGroupToVariant(TENANT, BRAND, sauce.defaultVariantId(), heat, 0, "tester");
        UUID sauces = authoring.createModifierGroup(TENANT, BRAND, "SAUCES", "Sauces", LOCALE, false, 0, 1, false);
        UUID chili = authoring.addModifierOption(
                TENANT, BRAND, sauces, "CHILI", "Chili", LOCALE, sauce.defaultVariantId(), 1, 0, UNCLASSIFIED, ACTOR);
        UUID ketchup = authoring.addModifierOption(
                TENANT, BRAND, sauces, "KETCHUP", "Ketchup", LOCALE, null, 1, 1, UNCLASSIFIED, ACTOR);
        authoring.attachModifierGroup(TENANT, BRAND, side.productId(), sauces, 0);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, side.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("PICKUP"));

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(result.status())
                .as("the menu publishes: %s", result.report().blockers())
                .isEqualTo(PublicationStatus.PUBLISHED);
        var groups = store.publicationItems(result.publicationId(), EntityType.MODIFIER_GROUP);
        assertThat(groups)
                .extracting(item -> item.entityId())
                .as("the heat choice is the sauce variant's, offered through the option and listed on no side")
                .containsExactlyInAnyOrder(sauces, heat);
        var options = listOfMaps(groups.stream()
                .filter(item -> item.entityId().equals(sauces))
                .findFirst()
                .orElseThrow()
                .content()
                .get("options"));
        var chiliEntry = options.stream()
                .filter(option -> chili.toString().equals(option.get("optionId")))
                .findFirst()
                .orElseThrow();
        var ketchupEntry = options.stream()
                .filter(option -> ketchup.toString().equals(option.get("optionId")))
                .findFirst()
                .orElseThrow();
        assertThat(chiliEntry.get("nestedGroups"))
                .isEqualTo(List.of(Map.of(
                        "groupId", heat.toString(), "required", true, "minimumSelections", 1, "maximumSelections", 1)));
        assertThat(ketchupEntry)
                .as("an option that links nothing publishes as it always did")
                .doesNotContainKey("nestedGroups");

        // The menu a storefront reads carries it too: the chooser can be drawn from the publication alone.
        var menu = storefront
                .menuFor(TENANT, BRAND, LOCATION, LOCALE, "STOREFRONT")
                .orElseThrow();
        var chiliOption = menu.modifierGroups().stream()
                .filter(group -> group.modifierGroupId().equals(sauces))
                .flatMap(group -> group.options().stream())
                .filter(option -> option.optionId().equals(chili))
                .findFirst()
                .orElseThrow();
        assertThat(chiliOption.nestedGroups()).singleElement().satisfies(policy -> {
            assertThat(policy.modifierGroupId()).isEqualTo(heat);
            assertThat(policy.required()).isTrue();
            assertThat(policy.minimumSelections()).isEqualTo(1);
        });
        assertThat(menu.modifierGroups())
                .extracting(StorefrontCatalogQuery.MenuModifierGroup::modifierGroupId)
                .contains(heat);
    }

    @Test
    @DisplayName("a group attached to a combo's container is not offered: a combo line takes no modifiers of its own")
    void aGroupOnAComboContainerIsNotPublished() {
        var combo = comboOfTwo();
        UUID extras = authoring.createModifierGroup(TENANT, BRAND, "EXTRAS", "Extras", LOCALE, false, 0, 1, false);
        authoring.addModifierOption(TENANT, BRAND, extras, "CHEESE", "Cheese", LOCALE, null, 1, 0, UNCLASSIFIED, ACTOR);
        composites.attachModifierGroupToVariant(TENANT, BRAND, combo.container(), extras, 0, "tester");

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(result.status()).isEqualTo(PublicationStatus.PUBLISHED);
        assertThat(store.publicationItems(result.publicationId(), EntityType.MODIFIER_GROUP))
                .as("nothing offers it, so nothing publishes it")
                .isEmpty();
        var container = store.publicationItems(result.publicationId(), EntityType.PRODUCT).stream()
                .filter(item -> item.entityId().equals(combo.containerProduct()))
                .findFirst()
                .orElseThrow();
        assertThat(listOfMaps(container.content().get("variants")).get(0))
                .doesNotContainKeys("modifierGroupIds", "modifierGroupPolicies");
    }

    @Test
    @DisplayName("the menu serves a variant's own groups and rules on the variant, and none on a variant with none")
    void theMenuServesAVariantsOwnGroups() {
        var fries = authoring.createProduct(
                TENANT, BRAND, catalogId, "FRIES", "Fries", null, LOCALE, "SKU-F", "PIECE", UNCLASSIFIED, ACTOR);
        UUID large = authoring.addVariant(
                TENANT, BRAND, fries.productId(), "SKU-F-L", "PIECE", "Large", LOCALE, 1, UNCLASSIFIED, ACTOR);
        UUID extras = authoring.createModifierGroup(TENANT, BRAND, "EXTRAS", "Extras", LOCALE, false, 0, 1, false);
        authoring.addModifierOption(TENANT, BRAND, extras, "CHEESE", "Cheese", LOCALE, null, 1, 0, UNCLASSIFIED, ACTOR);
        composites.attachModifierGroupToVariant(TENANT, BRAND, large, extras, 0, "tester");
        for (UUID variant : List.of(fries.defaultVariantId(), large)) {
            authoring.setOffering(TENANT, BRAND, LOCATION, variant, OfferingStatus.AVAILABLE, List.of("PICKUP"));
        }
        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        var menu = storefront
                .menuFor(TENANT, BRAND, LOCATION, LOCALE, "STOREFRONT")
                .orElseThrow();

        var product = menu.products().get(0);
        var largeVariant = product.variants().stream()
                .filter(variant -> variant.variantId().equals(large))
                .findFirst()
                .orElseThrow();
        var smallVariant = product.variants().stream()
                .filter(variant -> variant.variantId().equals(fries.defaultVariantId()))
                .findFirst()
                .orElseThrow();
        assertThat(product.modifierGroupIds())
                .as("the product's own list is the product's")
                .isEmpty();
        assertThat(largeVariant.modifierGroupIds()).containsExactly(extras);
        assertThat(largeVariant.modifierGroupPolicies()).singleElement().satisfies(policy -> {
            assertThat(policy.modifierGroupId()).isEqualTo(extras);
            assertThat(policy.required()).isFalse();
            assertThat(policy.maximumSelections()).isEqualTo(1);
        });
        assertThat(smallVariant.modifierGroupIds()).isEmpty();
        assertThat(smallVariant.modifierGroupPolicies()).isEmpty();
    }

    // ---------------------------------------------------------------- the menu

    @Test
    @DisplayName("the menu serves the combo's groups with each component's live price and its orderability")
    void theMenuServesTheComboWithLivePrices() {
        var combo = comboOfTwo();
        offer(combo);
        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        live.componentPrices.put(combo.burgerPairing().id(), 0L);
        live.componentPrices.put(combo.colaPairing().id(), 3_000L);

        var menu = storefront
                .menuFor(TENANT, BRAND, LOCATION, LOCALE, "STOREFRONT")
                .orElseThrow();

        assertThat(menu.comboGroups()).hasSize(1);
        MenuComboGroup group = menu.comboGroups().get(0);
        assertThat(group.containerVariantId()).isEqualTo(combo.container());
        assertThat(group.name()).isEqualTo("Choose");
        assertThat(group.minimumSelections()).isEqualTo(1);
        assertThat(group.maximumSelections()).isEqualTo(2);
        assertThat(group.components())
                .extracting(StorefrontCatalogQuery.MenuComboComponent::name)
                .containsExactly("Burger", "Cola");
        assertThat(group.components())
                .extracting(StorefrontCatalogQuery.MenuComboComponent::amountMinor)
                .as("zero is a real price (free with the box); a missing one is null and never read as free")
                .containsExactly(0L, 3_000L);
        assertThat(group.components()).allMatch(StorefrontCatalogQuery.MenuComboComponent::orderable);
        MenuProduct container = menu.products().stream()
                .filter(product -> product.productId().equals(combo.containerProduct()))
                .findFirst()
                .orElseThrow();
        assertThat(container.comboGroupIds()).containsExactly(group.comboGroupId());
        assertThat(container.variants().get(0).amountMinor())
                .as("the container has no price of its own")
                .isNull();
    }

    @Test
    @DisplayName("a component the location does not offer is absent, one it has run out of is shown sold out")
    void aComponentFollowsTheLocationsOfferings() {
        var combo = comboOfTwo();
        offer(combo);
        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        live.soldOut.add(combo.colaVariant());
        jdbc.sql("DELETE FROM catalog.location_offerings WHERE variant_id = :id")
                .param("id", combo.burgerVariant())
                .update();

        var menu = storefront
                .menuFor(TENANT, BRAND, LOCATION, LOCALE, "STOREFRONT")
                .orElseThrow();

        var components = menu.comboGroups().get(0).components();
        assertThat(components)
                .extracting(StorefrontCatalogQuery.MenuComboComponent::variantId)
                .containsExactly(combo.colaVariant());
        assertThat(components.get(0).orderable()).isFalse();
    }

    @Test
    @DisplayName("a combo whose group can no longer reach its minimum is shown as sold out, never hidden")
    void aComboThatCannotBeFilledIsSoldOut() {
        var combo = comboOfTwo();
        offer(combo);
        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        live.soldOut.add(combo.burgerVariant());
        live.soldOut.add(combo.colaVariant());

        var menu = storefront
                .menuFor(TENANT, BRAND, LOCATION, LOCALE, "STOREFRONT")
                .orElseThrow();

        MenuProduct container = menu.products().stream()
                .filter(product -> product.productId().equals(combo.containerProduct()))
                .findFirst()
                .orElseThrow();
        assertThat(container.variants().get(0).orderable()).isFalse();
    }

    @Test
    @DisplayName("a brand with no combo is served an empty list, so a client can rely on the field")
    void aMenuWithNoComboHasAnEmptyList() {
        var burger = authoring.createProduct(
                TENANT, BRAND, catalogId, "BURGER", "Burger", null, LOCALE, "SKU-B", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, burger.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("PICKUP"));
        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        var menu = storefront
                .menuFor(TENANT, BRAND, LOCATION, LOCALE, "STOREFRONT")
                .orElseThrow();

        assertThat(menu.comboGroups()).isEmpty();
        assertThat(menu.products().get(0).comboGroupIds()).isEmpty();
        assertThat(menu.products().get(0).modifierGroupPolicies()).isEmpty();
    }

    @Test
    @DisplayName("the menu serves a product's override as the effective rule for that product only")
    void theMenuServesTheOverride() {
        var burger = authoring.createProduct(
                TENANT, BRAND, catalogId, "BURGER", "Burger", null, LOCALE, "SKU-B", "PIECE", UNCLASSIFIED, ACTOR);
        UUID sauces = authoring.createModifierGroup(TENANT, BRAND, "SAUCES", "Sauces", LOCALE, false, 0, 3, false);
        authoring.addModifierOption(TENANT, BRAND, sauces, "MAYO", "Mayo", LOCALE, null, 1, 0, UNCLASSIFIED, ACTOR);
        authoring.attachModifierGroup(TENANT, BRAND, burger.productId(), sauces, 0);
        composites.setAttachmentPolicy(
                TENANT,
                BRAND,
                AttachmentOwnerType.PRODUCT,
                burger.productId(),
                sauces,
                1,
                new AttachmentPolicy(Visibility.VISIBLE, null, true, 1, 1),
                "tester");
        authoring.setOffering(
                TENANT, BRAND, LOCATION, burger.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("PICKUP"));
        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        var menu = storefront
                .menuFor(TENANT, BRAND, LOCATION, LOCALE, "STOREFRONT")
                .orElseThrow();

        var policy = menu.products().get(0).modifierGroupPolicies();
        assertThat(policy).hasSize(1);
        assertThat(policy.get(0).modifierGroupId()).isEqualTo(sauces);
        assertThat(policy.get(0).required()).isTrue();
        assertThat(policy.get(0).minimumSelections()).isEqualTo(1);
        assertThat(policy.get(0).maximumSelections()).isEqualTo(1);
        assertThat(menu.modifierGroups().get(0).options().get(0).name())
                .as("an option is named for the customer rather than shown by its authoring code")
                .isEqualTo("Mayo");
    }

    // ---------------------------------------------------------------- fixtures

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> listOfMaps(@org.jspecify.annotations.Nullable Object raw) {
        return (List<Map<String, Object>>) java.util.Objects.requireNonNull(raw);
    }

    private record Combo(
            UUID container,
            UUID containerProduct,
            ComboGroup group,
            ComboComponent burgerPairing,
            ComboComponent colaPairing,
            UUID burgerVariant,
            UUID colaVariant,
            UUID burgerProduct) {}

    private Combo comboOfTwo() {
        var lunch = authoring.createProduct(
                TENANT, BRAND, catalogId, "LUNCH", "Lunch", null, LOCALE, "SKU-L", "PIECE", UNCLASSIFIED, ACTOR);
        var burger = authoring.createProduct(
                TENANT, BRAND, catalogId, "BURGER", "Burger", null, LOCALE, "SKU-B", "PIECE", UNCLASSIFIED, ACTOR);
        var cola = authoring.createProduct(
                TENANT, BRAND, catalogId, "COLA", "Cola", null, LOCALE, "SKU-C", "PIECE", UNCLASSIFIED, ACTOR);

        ComboGroup group = composites.createComboGroup(
                new NewComboGroup(TENANT, BRAND, lunch.defaultVariantId(), "MAIN", "Choose", LOCALE, 1, 2, false, 0),
                "tester");
        ComboComponent burgerPairing =
                composites.addComponent(TENANT, BRAND, group.id(), burger.defaultVariantId(), 1, 0, "tester");
        ComboComponent colaPairing =
                composites.addComponent(TENANT, BRAND, group.id(), cola.defaultVariantId(), 1, 1, "tester");
        live.pricedComponents.add(burgerPairing.id());
        live.pricedComponents.add(colaPairing.id());
        return new Combo(
                lunch.defaultVariantId(),
                lunch.productId(),
                group,
                burgerPairing,
                colaPairing,
                burger.defaultVariantId(),
                cola.defaultVariantId(),
                burger.productId());
    }

    private void offer(Combo combo) {
        for (UUID variant : List.of(combo.container(), combo.burgerVariant(), combo.colaVariant())) {
            authoring.setOffering(TENANT, BRAND, LOCATION, variant, OfferingStatus.AVAILABLE, List.of("PICKUP"));
        }
    }

    private void insertTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'composite-menu', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, 'STOREFRONT', 'WEB', 'STOREFRONT', 'ACTIVE')
                """).param("id", UUID.randomUUID()).param("tenantId", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.locations (
                    id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'LOC', 'loc', 'Location', 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();
    }

    /**
     * Pricing and inventory as the live inputs a menu read folds in: what is priced (for the
     * publication gate) and what a price book and a stock count say right now.
     */
    private static final class LiveInputs implements VariantPricingLookup, MenuPriceLookup, MenuAvailabilityLookup {

        final Set<UUID> pricedComponents = new HashSet<>();
        final Map<UUID, Long> componentPrices = new java.util.HashMap<>();
        final Set<UUID> soldOut = new HashSet<>();

        @Override
        public Set<UUID> pricedVariants(UUID tenantId, UUID brandId, Set<UUID> variantIds) {
            // Every dish is priced; the container is skipped by the validator, which is the rule
            // under test, and a component is priced only when a test says so.
            return variantIds;
        }

        @Override
        public Set<UUID> pricedComboComponents(UUID tenantId, UUID brandId, Set<UUID> componentIds) {
            Set<UUID> priced = new HashSet<>(componentIds);
            priced.retainAll(pricedComponents);
            return priced;
        }

        @Override
        public Optional<MenuPrices> pricesFor(
                UUID tenantId,
                UUID brandId,
                UUID locationId,
                String channelCode,
                Set<UUID> variantIds,
                Set<UUID> modifierOptionIds) {
            return pricesFor(tenantId, brandId, locationId, channelCode, variantIds, modifierOptionIds, Set.of());
        }

        @Override
        public Optional<MenuPrices> pricesFor(
                UUID tenantId,
                UUID brandId,
                UUID locationId,
                String channelCode,
                Set<UUID> variantIds,
                Set<UUID> modifierOptionIds,
                Set<UUID> comboComponentIds) {
            Map<UUID, Long> asked = new java.util.HashMap<>();
            comboComponentIds.forEach(id -> {
                if (componentPrices.containsKey(id)) {
                    asked.put(id, componentPrices.get(id));
                }
            });
            return Optional.of(new MenuPrices("UZS", Map.of(), Map.of(), asked));
        }

        @Override
        public Map<UUID, VariantAvailability> availabilityFor(
                UUID tenantId, UUID brandId, UUID locationId, String channelCode, Set<UUID> variantIds) {
            Map<UUID, VariantAvailability> decisions = new java.util.HashMap<>();
            for (UUID variantId : variantIds) {
                if (soldOut.contains(variantId)) {
                    decisions.put(variantId, new VariantAvailability(false, null));
                }
            }
            return decisions;
        }
    }
}
