package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import uz.horecaos.platform.catalog.api.MenuPriceLookup;
import uz.horecaos.platform.catalog.api.VariantPricingLookup;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.CatalogPublicationService;
import uz.horecaos.platform.catalog.application.CatalogSnapshotLoader;
import uz.horecaos.platform.catalog.application.CatalogValidator;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuProduct;
import uz.horecaos.platform.catalog.application.StorefrontCatalogQuery.MenuVariant;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.CatalogEntities.PublicationItem;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.domain.PublicationStatus;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.media.api.MediaAvailability;
import uz.horecaos.platform.support.TestDatabase;
import uz.horecaos.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;

/**
 * What a customer is actually shown (ADR 0016).
 *
 * <p>{@code CatalogPublicationTests} proves the authoring-to-storefront pipeline
 * end to end; this class is narrower and meaner about {@link StorefrontCatalogQuery}
 * itself — the rules named in its own class comment and in the ADR's "Location
 * offerings are read live, deliberately" section, none of which had a test of
 * their own: a channel serving only its own publication (the class comment names
 * a real hardcoded-{@code 'STOREFRONT'} bug this guards against), the three-way
 * distinction between no offering row, {@code HIDDEN}, and {@code UNAVAILABLE},
 * a product dropped entirely rather than shown empty, a category tree's
 * drop/keep rule, an unpriced variant reporting null rather than free, and a
 * retired publication's content staying byte-for-byte what it was.
 */
class StorefrontCatalogQueryTests {

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
    private CatalogPublicationService publication;
    private StorefrontCatalogQuery storefront;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for catalog storefront tests");
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
                        + "catalog.product_modifier_groups, catalog.variant_modifier_groups, "
                        + "catalog.category_products, catalog.catalog_products, catalog.modifier_options, "
                        + "catalog.modifier_groups, catalog.categories, catalog.fiscal_classifications, "
                        + "catalog.fees, catalog.variants, "
                        + "catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE media.assets CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        insertTenancy();

        store = new JdbcCatalogStore(jdbc, JsonMapper.builder().build());
        MediaAvailability alwaysDisplayable = (tenantId, assetIds) -> true;
        authoring = new CatalogAuthoringService(
                store,
                new uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder(
                        jdbc, JsonMapper.builder().build()),
                Clock.systemUTC());

        CatalogSnapshotLoader loader = new CatalogSnapshotLoader(store, alwaysDisplayable, allPriced(), LOCALE);
        publication = new CatalogPublicationService(
                store,
                new CatalogValidator(),
                loader,
                new JdbcSalesChannelStore(jdbc),
                Clock.fixed(Instant.parse("2026-08-21T10:00:00Z"), ZoneOffset.UTC));
        storefront = new StorefrontCatalogQuery(
                store, (tenantId, brandId, locationId, channel, variantIds, optionIds) -> Optional.empty());
    }

    @Test
    @DisplayName("a channel is served only its own publication, never another channel's")
    void menuForServesTheCallersOwnChannelNotAnother() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(
                TENANT, BRAND, catalogId, "BURGER", "Burger", null, LOCALE, "SKU-B", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, burger.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        var storefrontPublication = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        // A dish added and offered only afterwards, published to KIOSK alone. If
        // menuFor ever again reads the publication for a literal "STOREFRONT"
        // instead of the caller's own channel — the exact regression its class
        // comment names — the kiosk request below would come back with the
        // storefront's publication and never see this dish.
        var kioskOnly = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "KIOSK_ONLY",
                "Kiosk maxsus",
                null,
                LOCALE,
                "SKU-K",
                "PIECE",
                UNCLASSIFIED,
                ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, kioskOnly.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("PICKUP"));
        var kioskPublication = publication.publish(TENANT, BRAND, catalogId, "KIOSK", null);

        var storefrontMenu = storefront
                .menuFor(TENANT, BRAND, LOCATION, LOCALE, "STOREFRONT")
                .orElseThrow();
        var kioskMenu =
                storefront.menuFor(TENANT, BRAND, LOCATION, LOCALE, "KIOSK").orElseThrow();

        assertThat(storefrontMenu.publicationId()).isEqualTo(storefrontPublication.publicationId());
        assertThat(kioskMenu.publicationId()).isEqualTo(kioskPublication.publicationId());
        assertThat(storefrontMenu.products()).extracting(MenuProduct::code).doesNotContain("KIOSK_ONLY");
        assertThat(kioskMenu.products()).extracting(MenuProduct::code).contains("KIOSK_ONLY", "BURGER");
    }

    @Test
    @DisplayName("no offering row is absent exactly like HIDDEN; UNAVAILABLE is present and not orderable")
    void theThreeOfferingAnswersAreDistinct() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var pizza = authoring.createProduct(
                TENANT, BRAND, catalogId, "PIZZA", "Pizza", null, LOCALE, "SKU-DEFAULT", "PIECE", UNCLASSIFIED, ACTOR);
        UUID neverOffered = authoring.addVariant(
                TENANT,
                BRAND,
                pizza.productId(),
                "SKU-NEVER",
                "PIECE",
                "Never offered",
                LOCALE,
                1,
                UNCLASSIFIED,
                ACTOR);
        UUID hidden = authoring.addVariant(
                TENANT, BRAND, pizza.productId(), "SKU-HIDDEN", "PIECE", "Hidden", LOCALE, 2, UNCLASSIFIED, ACTOR);
        UUID soldOut = authoring.addVariant(
                TENANT, BRAND, pizza.productId(), "SKU-OUT", "PIECE", "Sold out", LOCALE, 3, UNCLASSIFIED, ACTOR);

        authoring.setOffering(
                TENANT, BRAND, LOCATION, pizza.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        // neverOffered deliberately gets no call to setOffering at all.
        authoring.setOffering(TENANT, BRAND, LOCATION, hidden, OfferingStatus.HIDDEN, List.of("DELIVERY"));
        authoring.setOffering(TENANT, BRAND, LOCATION, soldOut, OfferingStatus.UNAVAILABLE, List.of("DELIVERY"));

        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        var menu = storefront
                .menuFor(TENANT, BRAND, LOCATION, LOCALE, "STOREFRONT")
                .orElseThrow();

        var variantsById = menu.products().stream()
                .filter(product -> product.productId().equals(pizza.productId()))
                .findFirst()
                .orElseThrow()
                .variants()
                .stream()
                .collect(java.util.stream.Collectors.toMap(MenuVariant::variantId, variant -> variant));

        // Absent entirely — a variant with no row is not distinguishable from a
        // HIDDEN one to the customer, and both must be missing here.
        assertThat(variantsById).doesNotContainKey(neverOffered);
        assertThat(variantsById).doesNotContainKey(hidden);
        // Present, but "we're out of that" rather than absent.
        assertThat(java.util.Objects.requireNonNull(variantsById.get(soldOut)).orderable())
                .isFalse();
        assertThat(java.util.Objects.requireNonNull(variantsById.get(pizza.defaultVariantId()))
                        .orderable())
                .isTrue();
    }

    @Test
    @DisplayName("a product whose every variant is unoffered is dropped entirely, not merely thinned")
    void productWithEveryVariantUnofferedIsDroppedEntirely() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        UUID hot = authoring.createCategory(TENANT, BRAND, catalogId, null, "HOT", "Issiq", LOCALE, 1);

        var soup = authoring.createProduct(
                TENANT, BRAND, catalogId, "SHURPA", "Shurpa", null, LOCALE, "SKU-SOUP", "PIECE", UNCLASSIFIED, ACTOR);
        var driedOut = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "STALE",
                "Bayot osh",
                null,
                LOCALE,
                "SKU-STALE-1",
                "PIECE",
                UNCLASSIFIED,
                ACTOR);
        UUID staleSecondVariant = authoring.addVariant(
                TENANT, BRAND, driedOut.productId(), "SKU-STALE-2", "PIECE", "Katta", LOCALE, 1, UNCLASSIFIED, ACTOR);

        authoring.placeProductInCategory(TENANT, BRAND, hot, soup.productId(), 1);
        authoring.placeProductInCategory(TENANT, BRAND, hot, driedOut.productId(), 2);

        authoring.setOffering(
                TENANT, BRAND, LOCATION, soup.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        // driedOut's default variant gets no offering row at all, and its second
        // variant is explicitly HIDDEN -- every variant is unoffered here.
        authoring.setOffering(TENANT, BRAND, LOCATION, staleSecondVariant, OfferingStatus.HIDDEN, List.of("DELIVERY"));

        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        var menu = storefront
                .menuFor(TENANT, BRAND, LOCATION, LOCALE, "STOREFRONT")
                .orElseThrow();

        assertThat(menu.products()).extracting(MenuProduct::productId).containsExactly(soup.productId());
        // And its id must not survive inside the category it was authored into,
        // or a customer taps a name with nothing behind it.
        assertThat(menu.categories())
                .singleElement()
                .satisfies(category -> assertThat(category.productIds()).containsExactly(soup.productId()));
    }

    @Test
    @DisplayName("an empty leaf category is dropped; a parent holding only child categories is kept")
    void categoryDropRuleDistinguishesEmptyLeavesFromParentsOfChildren() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        UUID root = authoring.createCategory(TENANT, BRAND, catalogId, null, "MENU", "Menyu", LOCALE, 0);
        UUID hot = authoring.createCategory(TENANT, BRAND, catalogId, root, "HOT", "Issiq", LOCALE, 1);
        // A leaf with nothing in it and no children of its own.
        UUID empty = authoring.createCategory(TENANT, BRAND, catalogId, null, "EMPTY", "Bosh", LOCALE, 2);

        var plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.placeProductInCategory(TENANT, BRAND, hot, plov.productId(), 1);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, plov.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));

        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        var menu = storefront
                .menuFor(TENANT, BRAND, LOCATION, LOCALE, "STOREFRONT")
                .orElseThrow();

        // "root" holds no products of its own -- it holds "hot" -- and must
        // survive; "empty" holds nothing at all, not even a child, and must not.
        assertThat(menu.categories())
                .extracting(StorefrontCatalogQuery.MenuCategory::categoryId)
                .containsExactlyInAnyOrder(root, hot);
        assertThat(menu.categories())
                .filteredOn(category -> category.categoryId().equals(root))
                .singleElement()
                .satisfies(category -> assertThat(category.productIds()).isEmpty());
    }

    @Test
    @DisplayName("a variant with no active price reports a null amount, never zero")
    void anUnpricedVariantIsNullNeverZero() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(
                TENANT, BRAND, catalogId, "BURGER", "Burger", null, LOCALE, "SKU-B2", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, burger.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        // A price book that resolves for this location and channel -- carrying a
        // real currency -- but has no entry at all for this variant. A lookup
        // returning 0L via getOrDefault instead of null via get would sell this
        // dish for free without anyone choosing to.
        UUID somePricedVariantElsewhere = UUID.randomUUID();
        StorefrontCatalogQuery pricedStorefront = new StorefrontCatalogQuery(
                store,
                (tenantId, brandId, locationId, channel, variantIds, optionIds) -> Optional.of(
                        new MenuPriceLookup.MenuPrices("UZS", Map.of(somePricedVariantElsewhere, 15_000L), Map.of())));

        var menu = pricedStorefront
                .menuFor(TENANT, BRAND, LOCATION, LOCALE, "STOREFRONT")
                .orElseThrow();

        assertThat(menu.currency()).isEqualTo("UZS");
        assertThat(menu.products())
                .singleElement()
                .satisfies(product -> assertThat(product.variants())
                        .singleElement()
                        .satisfies(variant -> assertThat(variant.amountMinor()).isNull()));
    }

    @Test
    @DisplayName("a retired publication's stored content does not change")
    void retiredPublicationContentIsUnchanged() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "BURGER",
                "Original name",
                null,
                LOCALE,
                "SKU-B3",
                "PIECE",
                UNCLASSIFIED,
                ACTOR);
        authoring.setOffering(
                TENANT, BRAND, LOCATION, burger.defaultVariantId(), OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        var first = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        List<PublicationItem> beforeRetirement = store.publicationItems(first.publicationId(), EntityType.PRODUCT);
        assertThat(beforeRetirement).isNotEmpty();

        // A draft edit and a republish, which retires the first publication.
        authoring.translate(
                TENANT, BRAND, EntityType.PRODUCT, burger.productId(), LOCALE, "Renamed after retirement", null);
        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        List<PublicationItem> afterRetirement = store.publicationItems(first.publicationId(), EntityType.PRODUCT);

        // Byte-for-byte the same rows: a retired publication is not a live
        // document that happens to be off, it is an immutable one.
        assertThat(afterRetirement).isEqualTo(beforeRetirement);
        assertThat(store.findPublication(TENANT, BRAND, first.publicationId())
                        .orElseThrow()
                        .status())
                .isEqualTo(PublicationStatus.RETIRED);
    }

    private void insertTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone,
                    status, version)
                VALUES (:id, 'catalog-storefront-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.locations (
                    id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'main-01', 'Main',
                        'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .update();

        insertChannel("STOREFRONT", "WEB");
        insertChannel("KIOSK", "KIOSK");
    }

    private void insertChannel(String code, String systemType) {
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (
                    id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, :code, :systemType, :code, 'ACTIVE')
                """)
                .param("id", UUID.randomUUID())
                .param("tenantId", TENANT)
                .param("code", code)
                .param("systemType", systemType)
                .update();
    }

    private static VariantPricingLookup allPriced() {
        return (tenantId, brandId, variantIds) -> variantIds;
    }
}
