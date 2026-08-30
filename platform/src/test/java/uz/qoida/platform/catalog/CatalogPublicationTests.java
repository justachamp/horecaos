package uz.qoida.platform.catalog;

import javax.sql.DataSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.DockerClientFactory;

import tools.jackson.databind.json.JsonMapper;

import uz.qoida.platform.support.TestDatabase;
import uz.qoida.platform.catalog.application.CatalogAuthoringService;
import uz.qoida.platform.catalog.application.CatalogPublicationService;
import uz.qoida.platform.catalog.application.CatalogSnapshotLoader;
import uz.qoida.platform.catalog.application.CatalogValidator;
import uz.qoida.platform.catalog.application.StorefrontCatalogQuery;
import uz.qoida.platform.catalog.api.FiscalVatRate;
import uz.qoida.platform.catalog.api.VariantPricingLookup;
import uz.qoida.platform.catalog.application.CatalogFiscalFacts;
import uz.qoida.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.qoida.platform.catalog.domain.CatalogEntities.PriceableNode;
import uz.qoida.platform.catalog.domain.FiscalClassification;
import uz.qoida.platform.catalog.domain.FiscalClassification.MarkingScheme;
import uz.qoida.platform.catalog.domain.PublicationStatus;
import uz.qoida.platform.catalog.domain.ValidationFinding;
import uz.qoida.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.qoida.platform.tenancy.infrastructure.persistence.JdbcSalesChannelStore;
import uz.qoida.platform.media.api.MediaAssetId;
import uz.qoida.platform.media.api.MediaAvailability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A menu is authored, published, and seen (ADR 0016).
 *
 * <p>The properties under test are the ones the separation of authoring from
 * publication exists to provide: a draft edit cannot reach a live menu, a broken
 * draft cannot be published at all, and a location's availability is applied on
 * top of the snapshot rather than baked into it.
 */
class CatalogPublicationTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();
    private static final UUID LOCATION = UUID.randomUUID();
    private static final String CHANNEL = "STOREFRONT";
    private static final String LOCALE = "uz";

    /** Whoever chose the codes. ADR 0038 records it on every classification. */
    private static final UUID ACTOR = UUID.randomUUID();

    /**
     * What most of these tests author. ADR 0038's classification rules are
     * reported and not enforced until its coverage tooling exists, so an
     * unclassified menu still publishes and the tests below that are about
     * something else stay about something else.
     */
    private static final FiscalClassification UNCLASSIFIED = FiscalClassification.unclassified();

    /** A complete classification: the four fields both providers require. */
    private static final FiscalClassification CLASSIFIED =
            FiscalClassification.of("10202001001000000", "1512315", 1, "Burger, dona");

    private static TestDatabase.Handle db;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    private JdbcClient jdbc;
    private JdbcCatalogStore store;
    private CatalogAuthoringService authoring;
    private CatalogPublicationService publication;
    private StorefrontCatalogQuery storefront;
    private MutableMediaAvailability media;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for catalog publication tests");
        db = TestDatabase.migrated();
        jdbcUrl = db.jdbcUrl();
        username = db.username();
        password = db.password();
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
                + "catalog.products, catalog.catalogs CASCADE").update();
        // V0065 gives catalog.media_relations a foreign key to media.assets, so the
        // assets these tests attach are real rows and have to be cleared with the
        // relations that point at them.
        jdbc.sql("TRUNCATE TABLE media.assets CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();
        // Real tenancy rows. V0018 gives location_offerings a foreign key to
        // tenant.locations, so a fabricated location id no longer inserts — which
        // was the gap: nothing stopped an offering naming a location that did not
        // exist, or one belonging to another brand.
        insertTenancy();

        store = new JdbcCatalogStore(jdbc, JsonMapper.builder().build());
        media = new MutableMediaAvailability();
        authoring = new CatalogAuthoringService(store);

        CatalogSnapshotLoader loader = new CatalogSnapshotLoader(store, media, allPriced(), LOCALE);
        publication = new CatalogPublicationService(store, new CatalogValidator(), loader,
                new JdbcSalesChannelStore(jdbc),
                Clock.fixed(Instant.parse("2026-08-21T10:00:00Z"), ZoneOffset.UTC));
        // No price book in these fixtures, so the lookup answers empty and the menu
        // renders unpriced. That is the honest shape for a catalog test: what a
        // dish costs is ADR 0018's question and is asserted in the pricing suite.
        storefront = new StorefrontCatalogQuery(store,
                (tenantId, brandId, locationId, channel, variantIds, optionIds) -> Optional.empty());
    }

    @Test
    @DisplayName("a menu is authored, published, and seen at a location")
    void aMenuCanBePublishedAndSeen() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(TENANT, BRAND, catalogId, "BURGER",
                "Qo'y burger", "Uy pishirilgan non bilan", LOCALE, "SKU-BURGER", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(TENANT, BRAND, LOCATION, burger.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("DELIVERY", "PICKUP"));

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        assertThat(result.status()).isEqualTo(PublicationStatus.PUBLISHED);

        var menu = storefront.menuFor(TENANT, BRAND, LOCATION, LOCALE, CHANNEL).orElseThrow();
        assertThat(menu.products()).singleElement().satisfies(product -> {
            // The name comes out of the snapshot, not the authoring table.
            assertThat(product.name()).isEqualTo("Qo'y burger");
            assertThat(product.description()).isEqualTo("Uy pishirilgan non bilan");
            assertThat(product.variants()).singleElement()
                    .satisfies(variant -> assertThat(variant.orderable()).isTrue());
        });
    }

    @Test
    @DisplayName("a published category carries the products it holds")
    void categoryMembershipReachesTheStorefront() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        UUID hot = authoring.createCategory(TENANT, BRAND, catalogId, null, "HOT", "Issiq", LOCALE, 1);

        var plov = authoring.createProduct(TENANT, BRAND, catalogId, "PLOV",
                "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        var soup = authoring.createProduct(TENANT, BRAND, catalogId, "SHURPA",
                "Shurpa", null, LOCALE, "SKU-SHURPA", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.placeProductInCategory(TENANT, BRAND, hot, plov.productId(), 2);
        authoring.placeProductInCategory(TENANT, BRAND, hot, soup.productId(), 1);

        authoring.setOffering(TENANT, BRAND, LOCATION, plov.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        authoring.setOffering(TENANT, BRAND, LOCATION, soup.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("DELIVERY"));

        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        var menu = storefront.menuFor(TENANT, BRAND, LOCATION, LOCALE, CHANNEL).orElseThrow();
        assertThat(menu.categories()).singleElement().satisfies(category -> {
            assertThat(category.name()).isEqualTo("Issiq");
            // The category's own order, not the order products were created in.
            assertThat(category.productIds())
                    .containsExactly(soup.productId(), plov.productId());
        });
    }

    @Test
    @DisplayName("a category loses the products this location does not serve")
    void categoryMembershipFollowsTheOffering() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        UUID hot = authoring.createCategory(TENANT, BRAND, catalogId, null, "HOT", "Issiq", LOCALE, 1);
        UUID cold = authoring.createCategory(TENANT, BRAND, catalogId, null, "COLD", "Sovuq", LOCALE, 2);

        var plov = authoring.createProduct(TENANT, BRAND, catalogId, "PLOV",
                "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        var salad = authoring.createProduct(TENANT, BRAND, catalogId, "SALAD",
                "Achchiq-chuchuk", null, LOCALE, "SKU-SALAD", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.placeProductInCategory(TENANT, BRAND, hot, plov.productId(), 1);
        authoring.placeProductInCategory(TENANT, BRAND, cold, salad.productId(), 1);

        // Only the hot dish is sold here. The salad is published in the menu and
        // offered nowhere at this location.
        authoring.setOffering(TENANT, BRAND, LOCATION, plov.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        authoring.setOffering(TENANT, BRAND, LOCATION, salad.defaultVariantId(),
                OfferingStatus.HIDDEN, List.of("DELIVERY"));

        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        var menu = storefront.menuFor(TENANT, BRAND, LOCATION, LOCALE, CHANNEL).orElseThrow();
        // The empty category is gone rather than shown as an empty shelf, and
        // no category names a product the customer cannot reach.
        assertThat(menu.categories()).singleElement()
                .satisfies(category -> assertThat(category.productIds())
                        .containsExactly(plov.productId()));

        Set<UUID> served = menu.products().stream()
                .map(StorefrontCatalogQuery.MenuProduct::productId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        assertThat(menu.categories().stream().flatMap(category -> category.productIds().stream()))
                .allSatisfy(productId -> assertThat(served).contains(productId));
    }

    @Test
    @DisplayName("a published product carries the modifier groups it offers")
    void modifierGroupsReachTheProductThatOffersThem() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var plov = authoring.createProduct(TENANT, BRAND, catalogId, "PLOV",
                "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        var tea = authoring.createProduct(TENANT, BRAND, catalogId, "TEA",
                "Choy", null, LOCALE, "SKU-TEA", "PIECE", UNCLASSIFIED, ACTOR);

        UUID extras = authoring.createModifierGroup(TENANT, BRAND, "EXTRAS", "Qo'shimchalar",
                LOCALE, false, 0, 2, true);
        authoring.addModifierOption(TENANT, BRAND, extras, "KAZY", "Qazi", LOCALE,
                null, 3, 1, UNCLASSIFIED, ACTOR);
        authoring.attachModifierGroup(TENANT, BRAND, plov.productId(), extras, 1);

        authoring.setOffering(TENANT, BRAND, LOCATION, plov.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        authoring.setOffering(TENANT, BRAND, LOCATION, tea.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("DELIVERY"));

        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        var menu = storefront.menuFor(TENANT, BRAND, LOCATION, LOCALE, CHANNEL).orElseThrow();

        // The link is per product. A group attached to one dish must not appear
        // on another, or the customer is offered meat with their tea.
        assertThat(menu.products())
                .filteredOn(product -> product.productId().equals(plov.productId()))
                .singleElement()
                .satisfies(product -> assertThat(product.modifierGroupIds()).containsExactly(extras));
        assertThat(menu.products())
                .filteredOn(product -> product.productId().equals(tea.productId()))
                .singleElement()
                .satisfies(product -> assertThat(product.modifierGroupIds()).isEmpty());

        // Without this a client cannot honour maximumQuantity and has to pin
        // every option to one.
        assertThat(menu.modifierGroups()).singleElement().satisfies(group -> {
            assertThat(group.allowSameOptionMultipleTimes()).isTrue();
            assertThat(group.options()).singleElement()
                    .satisfies(option -> assertThat(option.maximumQuantity()).isEqualTo(3));
        });
    }

    @Test
    @DisplayName("a draft edit after publication does not change the live menu")
    void draftEditsCannotReachALiveMenu() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(TENANT, BRAND, catalogId, "BURGER",
                "Original name", null, LOCALE, "SKU-1", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(TENANT, BRAND, LOCATION, burger.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        // An operator mid-service renames a dish and adds an unpublishable one.
        authoring.translate(TENANT, BRAND,
                uz.qoida.platform.catalog.domain.CatalogEntities.EntityType.PRODUCT,
                burger.productId(), LOCALE, "Renamed in draft", null);
        authoring.createProduct(TENANT, BRAND, catalogId, "PIZZA",
                "Not published yet", null, LOCALE, "SKU-2", "PIECE", UNCLASSIFIED, ACTOR);

        var menu = storefront.menuFor(TENANT, BRAND, LOCATION, LOCALE, CHANNEL).orElseThrow();

        // This is the whole reason publication is a copy rather than a pointer.
        assertThat(menu.products()).singleElement()
                .satisfies(product -> assertThat(product.name()).isEqualTo("Original name"));
    }

    @Test
    @DisplayName("a product with no active variant blocks publication")
    void productWithoutVariantBlocksPublication() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        UUID orphanId = UUID.randomUUID();
        store.insertProduct(orphanId, TENANT, BRAND, "ORPHAN",
                uz.qoida.platform.catalog.domain.CatalogEntities.Status.ACTIVE);
        store.addProductToCatalog(TENANT, BRAND, catalogId, orphanId, 0);
        store.upsertTranslation(TENANT, BRAND,
                uz.qoida.platform.catalog.domain.CatalogEntities.EntityType.PRODUCT,
                orphanId, LOCALE, "Orphan", null);

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(result.status()).isEqualTo(PublicationStatus.REJECTED);
        assertThat(result.report().blockers())
                .extracting(ValidationFinding::code)
                .contains("PRODUCT_HAS_NO_ACTIVE_VARIANT");
        // A rejection still leaves nothing live, and leaves the report behind.
        assertThat(storefront.menuFor(TENANT, BRAND, LOCATION, LOCALE, CHANNEL)).isEmpty();
    }

    @Test
    @DisplayName("a missing name in the default locale blocks publication")
    void missingTranslationBlocksPublication() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var product = authoring.createProduct(TENANT, BRAND, catalogId, "NAMELESS",
                "Only in Russian", null, "ru", "SKU-3", "PIECE", UNCLASSIFIED, ACTOR);
        assertThat(product.productId()).isNotNull();

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        // Without this rule the storefront shows the customer "NAMELESS".
        assertThat(result.report().blockers())
                .extracting(ValidationFinding::code)
                .contains("MISSING_TRANSLATION");
    }

    @Test
    @DisplayName("a media asset that is not yet verified blocks publication")
    void unverifiedMediaBlocksPublication() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(TENANT, BRAND, catalogId, "BURGER",
                "Burger", null, LOCALE, "SKU-4", "PIECE", UNCLASSIFIED, ACTOR);

        authoring.setOffering(TENANT, BRAND, LOCATION, burger.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("DELIVERY"));

        // Real rows in media.assets. Before V0065 these were two fabricated uuids
        // and the insert succeeded, which is exactly what the foreign key exists
        // to stop: a menu could name an asset that had never been uploaded.
        MediaAssetId verified = aMediaAsset(TENANT);
        MediaAssetId pending = aMediaAsset(TENANT);
        authoring.attachMedia(TENANT, BRAND,
                uz.qoida.platform.catalog.domain.CatalogEntities.EntityType.PRODUCT,
                burger.productId(), verified, "PRIMARY", 0);

        // A verified asset alone must publish cleanly. Without this half the test
        // would pass even if *any* attached media blocked publication, which is a
        // different and much worse rule than the one being claimed.
        media.displayable = Set.of(verified);
        assertThat(publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null).status())
                .isEqualTo(PublicationStatus.PUBLISHED);

        authoring.attachMedia(TENANT, BRAND,
                uz.qoida.platform.catalog.domain.CatalogEntities.EntityType.PRODUCT,
                burger.productId(), pending, "GALLERY", 1);

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        // The publication is immutable, so it cannot quietly heal when the
        // upload finishes — it would stay a live menu of broken images.
        assertThat(result.report().blockers())
                .extracting(ValidationFinding::code)
                .contains("MEDIA_NOT_AVAILABLE");
    }

    @Test
    @DisplayName("a media reference to another tenant's asset is refused by the database")
    void crossTenantMediaReferenceIsRefusedByTheDatabase() {
        UUID otherTenant = UUID.randomUUID();
        MediaAssetId theirs = aMediaAsset(otherTenant);

        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(TENANT, BRAND, catalogId, "BURGER",
                "Burger", null, LOCALE, "SKU-9", "PIECE", UNCLASSIFIED, ACTOR);

        // Refused by PostgreSQL, not by a service that remembered to check. The
        // composite key is (media_asset_id, tenant_id) against V0058's
        // uq_media_assets_tenant_scoped, so this tenant naming another tenant's
        // asset cannot resolve — there is no path through the application that
        // can write this row, including one nobody has written yet.
        assertThat(catchThrowable(() -> authoring.attachMedia(TENANT, BRAND,
                uz.qoida.platform.catalog.domain.CatalogEntities.EntityType.PRODUCT,
                burger.productId(), theirs, "PRIMARY", 0)))
                .isInstanceOf(DataIntegrityViolationException.class);

        // An asset id that names nothing at all is refused the same way, which is
        // the other half: a relation to a deleted or invented asset is a broken
        // image on a live menu.
        assertThat(catchThrowable(() -> authoring.attachMedia(TENANT, BRAND,
                uz.qoida.platform.catalog.domain.CatalogEntities.EntityType.PRODUCT,
                burger.productId(), MediaAssetId.generate(), "GALLERY", 1)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.sql("SELECT count(*) FROM catalog.media_relations")
                .query(Long.class).single()).isZero();
    }

    @Test
    @DisplayName("a modifier group demanding more selections than it offers blocks publication")
    void unsatisfiableModifierGroupBlocksPublication() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(TENANT, BRAND, catalogId, "BURGER",
                "Burger", null, LOCALE, "SKU-5", "PIECE", UNCLASSIFIED, ACTOR);

        // "Choose 2 sauces" with one sauce on the list. The database check
        // constraint allows this: minimum <= maximum holds, and it has no way to
        // know how many options exist.
        UUID group = authoring.createModifierGroup(TENANT, BRAND, "SAUCES", "Sauces",
                LOCALE, true, 2, 3, false);
        authoring.addModifierOption(TENANT, BRAND, group, "KETCHUP", "Ketchup", LOCALE,
                null, 1, 0, UNCLASSIFIED, ACTOR);
        authoring.attachModifierGroup(TENANT, BRAND, burger.productId(), group, 0);

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(result.report().blockers())
                .extracting(ValidationFinding::code)
                .contains("MODIFIER_GROUP_MINIMUM_UNSATISFIABLE");
    }

    @Test
    @DisplayName("a location that does not offer a variant does not see it")
    void locationOfferingsFilterTheMenu() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(TENANT, BRAND, catalogId, "BURGER",
                "Burger", null, LOCALE, "SKU-6", "PIECE", UNCLASSIFIED, ACTOR);
        var pizza = authoring.createProduct(TENANT, BRAND, catalogId, "PIZZA",
                "Pizza", null, LOCALE, "SKU-7", "PIECE", UNCLASSIFIED, ACTOR);

        authoring.setOffering(TENANT, BRAND, LOCATION, burger.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        authoring.setOffering(TENANT, BRAND, LOCATION, pizza.defaultVariantId(),
                OfferingStatus.UNAVAILABLE, List.of("DELIVERY"));

        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        var menu = storefront.menuFor(TENANT, BRAND, LOCATION, LOCALE, CHANNEL).orElseThrow();

        assertThat(menu.products()).hasSize(2);
        assertThat(menu.products())
                .filteredOn(product -> product.code().equals("PIZZA"))
                .singleElement()
                .satisfies(product -> assertThat(product.variants().getFirst().orderable())
                        // Sold out is shown, not hidden: a customer needs to know
                        // the restaurant has it and is out, not that it never existed.
                        .isFalse());
    }

    @Test
    @DisplayName("marking a dish sold out takes effect without republishing")
    void offeringChangesDoNotNeedARepublish() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(TENANT, BRAND, catalogId, "BURGER",
                "Burger", null, LOCALE, "SKU-8", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(TENANT, BRAND, LOCATION, burger.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        var published = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        authoring.setOffering(TENANT, BRAND, LOCATION, burger.defaultVariantId(),
                OfferingStatus.UNAVAILABLE, List.of("DELIVERY"));

        var menu = storefront.menuFor(TENANT, BRAND, LOCATION, LOCALE, CHANNEL).orElseThrow();

        // Same publication, different availability. Forcing this through a
        // republish would mean re-validating a whole menu to hide one item.
        assertThat(menu.publicationId()).isEqualTo(published.publicationId());
        assertThat(menu.products().getFirst().variants().getFirst().orderable()).isFalse();
    }

    @Test
    @DisplayName("publishing again retires the previous publication, leaving exactly one live")
    void republishingRetiresThePrevious() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(TENANT, BRAND, catalogId, "BURGER",
                "Burger", null, LOCALE, "SKU-9", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(TENANT, BRAND, LOCATION, burger.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("DELIVERY"));

        var first = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        var second = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(first.publicationId()).isNotEqualTo(second.publicationId());
        // The partial unique index would have failed the insert otherwise, which
        // is exactly the protection: two live menus must be impossible, not rare.
        assertThat(jdbc.sql("SELECT count(*) FROM catalog.publications WHERE status = 'PUBLISHED'")
                .query(Long.class).single()).isEqualTo(1L);
        assertThat(storefront.menuFor(TENANT, BRAND, LOCATION, LOCALE, CHANNEL).orElseThrow().publicationId())
                .isEqualTo(second.publicationId());
    }

    @Test
    @DisplayName("an unchanged catalog republishes to the same content hash")
    void contentHashIsStableForUnchangedContent() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(TENANT, BRAND, catalogId, "BURGER",
                "Burger", null, LOCALE, "SKU-10", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(TENANT, BRAND, LOCATION, burger.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("DELIVERY"));

        var first = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        var second = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        // Without a stable hash every republish looks like a change and
        // invalidates every downstream cache for nothing.
        assertThat(second.contentHash()).isEqualTo(first.contentHash());
    }

    @Test
    @DisplayName("the content hash does not depend on how the content maps were built")
    void contentHashIgnoresMapIterationOrder() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(TENANT, BRAND, catalogId, "BURGER",
                "Burger", null, LOCALE, "SKU-13", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(TENANT, BRAND, LOCATION, burger.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("DELIVERY"));

        CatalogSnapshotLoader loader = new CatalogSnapshotLoader(store, media, allPriced(), LOCALE);
        var snapshot = loader.load(TENANT, BRAND, catalogId);
        var items = loader.toPublicationItems(snapshot);

        // The same content with every map rebuilt in reverse key order. The
        // original implementation hashed Map.toString() over maps built with
        // Map.of(), whose iteration order is randomised per JVM by an internal
        // salt — so the same unchanged menu hashed differently after a restart,
        // and contentHashIsStableForUnchangedContent could not see it because
        // both of its hashes came from one process.
        var reordered = items.stream()
                .map(item -> new uz.qoida.platform.catalog.domain.CatalogEntities.PublicationItem(
                        item.entityType(), item.entityId(), item.entityVersion(),
                        reverseKeyOrder(item.content())))
                .toList();

        assertThat(CatalogPublicationService.contentHashOf(reordered))
                .isEqualTo(CatalogPublicationService.contentHashOf(items));
    }

    @Test
    @DisplayName("an absent SKU is omitted rather than published as the string \"null\"")
    void absentValuesAreOmittedFromThePublishedSnapshot() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(TENANT, BRAND, catalogId, "NOSKU",
                "No SKU", null, LOCALE, null, "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(TENANT, BRAND, LOCATION, burger.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("DELIVERY"));

        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        String stored = jdbc.sql("SELECT immutable_content_json::text FROM catalog.publication_items "
                        + "WHERE entity_type = 'PRODUCT'")
                .query(String.class).single();

        // "null" as a string reaches the customer app looking like a real value,
        // and publication items are insert-only, so it cannot be corrected
        // without republishing the whole menu.
        assertThat(stored).doesNotContain("\"null\"");
        assertThat(storefront.menuFor(TENANT, BRAND, LOCATION, LOCALE, CHANNEL).orElseThrow()
                .products().getFirst().variants().getFirst().sku())
                .isNotEqualTo("null");
    }

    @Test
    @DisplayName("a rollback uses the publication's own channel, not one the caller names")
    void rollbackUsesThePublicationsChannel() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(TENANT, BRAND, catalogId, "BURGER",
                "Burger", null, LOCALE, "SKU-14", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(TENANT, BRAND, LOCATION, burger.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("DELIVERY"));

        var storefrontPublication = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        var kioskPublication = publication.publish(TENANT, BRAND, catalogId, "KIOSK", null);

        // Rolling back to the kiosk publication must touch the kiosk channel only.
        // While rollback took a channel parameter, a caller could retire the
        // storefront's live menu and activate a kiosk snapshot in its place —
        // leaving customers with no menu and the kiosk with two.
        publication.rollbackTo(TENANT, BRAND, kioskPublication.publicationId());

        assertThat(publication.activePublicationId(TENANT, BRAND, "STOREFRONT"))
                .contains(storefrontPublication.publicationId());
        assertThat(publication.activePublicationId(TENANT, BRAND, "KIOSK"))
                .contains(kioskPublication.publicationId());
    }

    private static java.util.Map<String, Object> reverseKeyOrder(java.util.Map<String, Object> content) {
        java.util.Map<String, Object> reversed = new java.util.LinkedHashMap<>();
        content.keySet().stream()
                .sorted(java.util.Comparator.reverseOrder())
                .forEach(key -> {
                    Object value = content.get(key);
                    reversed.put(key, value instanceof java.util.Map<?, ?> nested
                            ? reverseKeyOrder(castMap(nested))
                            : value);
                });
        return reversed;
    }

    @SuppressWarnings("unchecked")
    private static java.util.Map<String, Object> castMap(java.util.Map<?, ?> map) {
        return (java.util.Map<String, Object>) map;
    }

    @Test
    @DisplayName("rolling back to a rejected publication is refused")
    void cannotRollBackToARejectedPublication() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        UUID orphanId = UUID.randomUUID();
        store.insertProduct(orphanId, TENANT, BRAND, "ORPHAN",
                uz.qoida.platform.catalog.domain.CatalogEntities.Status.ACTIVE);
        store.addProductToCatalog(TENANT, BRAND, catalogId, orphanId, 0);
        store.upsertTranslation(TENANT, BRAND,
                uz.qoida.platform.catalog.domain.CatalogEntities.EntityType.PRODUCT,
                orphanId, LOCALE, "Orphan", null);

        var rejected = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(catchThrowable(() ->
                publication.rollbackTo(TENANT, BRAND, rejected.publicationId())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("rejected");
    }

    @Test
    @DisplayName("a brand cannot publish another brand's catalog")
    void catalogsAreBrandIsolated() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(TENANT, BRAND, catalogId, "BURGER",
                "Burger", null, LOCALE, "SKU-11", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(TENANT, BRAND, LOCATION, burger.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("DELIVERY"));
        publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        // Publishing another brand's catalog is refused outright. The composite
        // foreign key makes it impossible regardless; the service checks first so
        // the caller gets a sentence rather than a constraint name.
        assertThat(catchThrowable(() ->
                publication.publish(TENANT, OTHER_BRAND, catalogId, "STOREFRONT", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong to brand");

        // And the other brand has no live menu of its own to leak into.
        assertThat(storefront.menuFor(TENANT, OTHER_BRAND, LOCATION, LOCALE, CHANNEL)).isEmpty();
    }

    @Test
    @DisplayName("every report says so while pricing validation is not wired")
    void unwiredPricingIsReportedOnEveryPublication() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(TENANT, BRAND, catalogId, "BURGER",
                "Burger", null, LOCALE, "SKU-12", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(TENANT, BRAND, LOCATION, burger.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("DELIVERY"));

        CatalogSnapshotLoader unwiredLoader = new CatalogSnapshotLoader(store, media, unwired(), LOCALE);
        var unwiredPublication = new CatalogPublicationService(store, new CatalogValidator(),
                unwiredLoader, new JdbcSalesChannelStore(jdbc),
                Clock.fixed(Instant.parse("2026-08-21T10:00:00Z"), ZoneOffset.UTC));

        var result = unwiredPublication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        // The gap is visible on every report rather than only in a startup log,
        // so nobody reads "published successfully" without also reading that one
        // of its checks did not run.
        assertThat(result.report().findings())
                .extracting(ValidationFinding::code)
                .contains("PRICING_VALIDATION_NOT_WIRED");
        assertThat(result.status()).isEqualTo(PublicationStatus.PUBLISHED);
    }

    @Test
    @DisplayName("an unclassified node is reported with what exactly is missing, and still publishes")
    void missingFiscalClassificationWarnsWithoutBlocking() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(TENANT, BRAND, catalogId, "BURGER",
                "Burger", null, LOCALE, "SKU-20", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setOffering(TENANT, BRAND, LOCATION, burger.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("DELIVERY"));

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(result.report().findings())
                .filteredOn(finding -> finding.code().equals("FISCAL_CLASSIFICATION_MISSING"))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.severity()).isEqualTo(ValidationFinding.Severity.WARNING);
                    // The entity path, or an operator with four hundred dishes
                    // cannot act on it.
                    assertThat(finding.entityCode()).isEqualTo("SKU-20");
                    // And all four fields, not just the ИКПУ. Click cannot build
                    // a line without a unit code and a 63-character name, and
                    // both providers mark the package code required, so reporting
                    // only the ИКПУ understates the gap by half.
                    assertThat(finding.detail())
                            .contains("ИКПУ/MXIK")
                            .contains("package code")
                            .contains("fiscal unit code")
                            .contains("fiscal name");
                });

        assertThat(result.report().findings())
                .extracting(ValidationFinding::code)
                .contains("FISCAL_CLASSIFICATION_NOT_ENFORCED");

        // Reported, not enforced: ADR 0038 turns this into a blocker at rollout
        // stage 3, once the bulk tooling and the reference import exist.
        assertThat(result.report().blockers())
                .extracting(ValidationFinding::code)
                .doesNotContain("FISCAL_CLASSIFICATION_MISSING");
        assertThat(result.status()).isEqualTo(PublicationStatus.PUBLISHED);
    }

    @Test
    @DisplayName("a half-classified node names only the fields it still needs")
    void aPartialClassificationReportsOnlyWhatIsMissing() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(TENANT, BRAND, catalogId, "BURGER",
                "Burger", null, LOCALE, "SKU-23", "PIECE",
                // An operator with the code but not yet the packaging. The row is
                // stored half-filled on purpose: a type or a column that refused
                // it would refuse saving progress, and ADR 0038's rollout has to
                // be able to turn the rule on one brand at a time.
                FiscalClassification.of("10202001001000000", null, 1, "Burger, dona"), ACTOR);
        authoring.setOffering(TENANT, BRAND, LOCATION, burger.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("DELIVERY"));

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(result.report().findings())
                .filteredOn(finding -> finding.code().equals("FISCAL_CLASSIFICATION_MISSING"))
                .singleElement()
                .satisfies(finding -> assertThat(finding.detail())
                        .contains("package code")
                        .doesNotContain("ИКПУ/MXIK")
                        .doesNotContain("fiscal unit code")
                        .doesNotContain("fiscal name"));
    }

    @Test
    @DisplayName("a fully classified menu reports no fiscal gap, and the codes reach the snapshot")
    void aCompleteClassificationIsNotReported() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var pizza = authoring.createProduct(TENANT, BRAND, catalogId, "PIZZA",
                "Pizza", null, LOCALE, "SKU-SMALL", "PIECE",
                FiscalClassification.of("10202001001000000", "1512315", 1, "Pitsa kichik, dona"),
                ACTOR);
        // Every size is its own receipt line with its own unit and its own
        // 63-character fiscal name, so nothing is inherited from a sibling.
        UUID familySize = authoring.addVariant(TENANT, BRAND, pizza.productId(), "SKU-FAMILY",
                "PIECE", "Katta", LOCALE, 1,
                FiscalClassification.of("10202001002000000", "1512315", 1, "Pitsa katta, dona"),
                ACTOR);

        // Pickup only, so the delivery fee is not a line this brand can produce
        // and its absence is not a gap.
        authoring.setOffering(TENANT, BRAND, LOCATION, pizza.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("PICKUP"));
        authoring.setOffering(TENANT, BRAND, LOCATION, familySize,
                OfferingStatus.AVAILABLE, List.of("PICKUP"));

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(result.report().findings())
                .extracting(ValidationFinding::code)
                .doesNotContain("FISCAL_CLASSIFICATION_MISSING",
                        "FISCAL_CLASSIFICATION_NOT_ENFORCED",
                        "FISCAL_DELIVERY_FEE_UNCLASSIFIED");
        assertThat(result.status()).isEqualTo(PublicationStatus.PUBLISHED);

        // The snapshot carries the resolved classification per variant. A partner
        // adapter reading a published menu has no access to the authoring tables,
        // which are mutable and may have moved on.
        String published = jdbc.sql("SELECT immutable_content_json::text "
                        + "FROM catalog.publication_items WHERE entity_type = 'PRODUCT'")
                .query(String.class).single();
        assertThat(published)
                .contains("10202001001000000")
                .contains("10202001002000000")
                .contains("Pitsa katta, dona")
                .contains("fiscalUnitCode");
    }

    @Test
    @DisplayName("a modifier option is classified in its own right, or by the variant it links to")
    void modifierOptionsAreClassifiedInTheirOwnRight() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var coffee = authoring.createProduct(TENANT, BRAND, catalogId, "COFFEE",
                "Kofe", null, LOCALE, "SKU-COFFEE", "PIECE",
                FiscalClassification.of("10301001001000000", "1512399", 1, "Kofe, dona"), ACTOR);
        var shot = authoring.createProduct(TENANT, BRAND, catalogId, "SHOT",
                "Qo'shimcha shot", null, LOCALE, "SKU-SHOT", "PIECE",
                FiscalClassification.of("10301001002000000", "1512399", 1, "Shot, dona"), ACTOR);
        authoring.setOffering(TENANT, BRAND, LOCATION, coffee.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("PICKUP"));
        authoring.setOffering(TENANT, BRAND, LOCATION, shot.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("PICKUP"));

        UUID extras = authoring.createModifierGroup(TENANT, BRAND, "EXTRAS", "Qo'shimchalar",
                LOCALE, false, 0, 2, false);
        // Classified by linking to something already classified. Classifying it
        // again would put one physical good on a receipt under two codes that can
        // be corrected independently.
        authoring.addModifierOption(TENANT, BRAND, extras, "EXTRA_SHOT", "Shot", LOCALE,
                shot.defaultVariantId(), 1, 0, UNCLASSIFIED, ACTOR);
        // Classified in its own right; a syrup is not a sellable variant.
        authoring.addModifierOption(TENANT, BRAND, extras, "SYRUP", "Sirop", LOCALE,
                null, 1, 1,
                FiscalClassification.of("10301001003000000", "1512399", 1, "Sirop, dona"), ACTOR);
        // Classified nowhere. A modifier reaches the receipt as its own line, so
        // this one genuinely has no code to print.
        authoring.addModifierOption(TENANT, BRAND, extras, "NO_SUGAR", "Shakarsiz", LOCALE,
                null, 1, 2, UNCLASSIFIED, ACTOR);
        authoring.attachModifierGroup(TENANT, BRAND, coffee.productId(), extras, 0);

        var result = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);

        assertThat(result.report().findings())
                .filteredOn(finding -> finding.code().equals("FISCAL_CLASSIFICATION_MISSING"))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.entityCode()).isEqualTo("NO_SUGAR");
                    assertThat(finding.entityType())
                            .isEqualTo(uz.qoida.platform.catalog.domain.CatalogEntities
                                    .EntityType.MODIFIER_OPTION);
                });
        assertThat(result.status()).isEqualTo(PublicationStatus.PUBLISHED);

        String publishedGroup = jdbc.sql("SELECT immutable_content_json::text "
                        + "FROM catalog.publication_items WHERE entity_type = 'MODIFIER_GROUP'")
                .query(String.class).single();
        // The linked option's classification is resolved into the snapshot rather
        // than left for a downstream consumer to chase back to a variant row.
        assertThat(publishedGroup).contains("10301001002000000").contains("10301001003000000");
        // And the unclassified one is omitted rather than published as "null",
        // which would reach a fiscal adapter looking like a real code.
        assertThat(publishedGroup).doesNotContain("\"null\"");
    }

    @Test
    @DisplayName("a brand that delivers is told its delivery fee has no classification")
    void anUnclassifiedDeliveryFeeIsReported() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(TENANT, BRAND, catalogId, "BURGER",
                "Burger", null, LOCALE, "SKU-24", "PIECE", CLASSIFIED, ACTOR);
        authoring.setOffering(TENANT, BRAND, LOCATION, burger.defaultVariantId(),
                OfferingStatus.AVAILABLE, List.of("DELIVERY"));

        // The delivery charge reaches a receipt as an ordinary item line, never
        // through Payme's shipping block, which carries no code, no package code
        // and no VAT percent. Before V0028 it had nowhere to carry one at all.
        var before = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        assertThat(before.report().findings())
                .filteredOn(finding -> finding.code().equals("FISCAL_DELIVERY_FEE_UNCLASSIFIED"))
                .singleElement()
                .satisfies(finding -> assertThat(finding.entityCode()).isEqualTo("DELIVERY"));

        authoring.classifyFee(TENANT, BRAND, "DELIVERY",
                FiscalClassification.of("10995001001000000", "1512399", 1, "Yetkazib berish"),
                ACTOR);

        var after = publication.publish(TENANT, BRAND, catalogId, "STOREFRONT", null);
        assertThat(after.report().findings())
                .extracting(ValidationFinding::code)
                .doesNotContain("FISCAL_DELIVERY_FEE_UNCLASSIFIED");
    }

    @Test
    @DisplayName("marking is a fact the catalog states and payments can read")
    void markingReachesPaymentsThroughThePort() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var water = authoring.createProduct(TENANT, BRAND, catalogId, "WATER",
                "Suv", null, LOCALE, "SKU-WATER", "PIECE", CLASSIFIED, ACTOR);
        var vodka = authoring.createProduct(TENANT, BRAND, catalogId, "VODKA",
                "Aroq", null, LOCALE, "SKU-VODKA", "PIECE",
                new FiscalClassification("10101001001000000", "1512315", 3, "Aroq 0.5l, shisha",
                        null, true, MarkingScheme.DATA_MATRIX, true, 4000, 21),
                ACTOR);
        assertThat(catalogId).isNotNull();

        var facts = new CatalogFiscalFacts(store);
        Set<UUID> cart = Set.of(water.defaultVariantId(), vodka.defaultVariantId());

        // Payme's detail.items[] has no marking field of any kind, so this cart
        // cannot be paid through Payme. The rule lives here rather than in the
        // adapter because an adapter learns about the cart after the customer has
        // already chosen how to pay: refusing then is a failed checkout instead of
        // a button that was never offered.
        assertThat(facts.markedNodes(TENANT, BRAND, cart))
                .containsExactly(vodka.defaultVariantId());
        assertThat(facts.requiresMarkingCapablePayment(TENANT, BRAND, cart)).isTrue();

        // And a cart without it is unaffected: the constraint costs the customer
        // a payment method, so it must not apply to carts that do not need it.
        assertThat(facts.requiresMarkingCapablePayment(TENANT, BRAND,
                Set.of(water.defaultVariantId()))).isFalse();
    }

    @Test
    @DisplayName("a modifier linked to a marked variant inherits the marking, not the default")
    void markingInheritsTowardsTheStricterValue() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var vodka = authoring.createProduct(TENANT, BRAND, catalogId, "VODKA",
                "Aroq", null, LOCALE, "SKU-V2", "PIECE",
                new FiscalClassification("10101001001000000", "1512315", 3, "Aroq 0.5l, shisha",
                        null, true, MarkingScheme.DATA_MATRIX, true, 4000, 21),
                ACTOR);
        var mixer = authoring.createProduct(TENANT, BRAND, catalogId, "MIXER",
                "Tonik", null, LOCALE, "SKU-TONIC", "PIECE", CLASSIFIED, ACTOR);

        UUID extras = authoring.createModifierGroup(TENANT, BRAND, "SHOTS", "Shotlar",
                LOCALE, false, 0, 1, false);
        UUID linked = authoring.addModifierOption(TENANT, BRAND, extras, "ADD_VODKA", "Aroq",
                LOCALE, vodka.defaultVariantId(), 1, 0, UNCLASSIFIED, ACTOR);
        authoring.attachModifierGroup(TENANT, BRAND, mixer.productId(), extras, 0);

        var snapshot = new CatalogSnapshotLoader(store, media, allPriced(), LOCALE)
                .load(TENANT, BRAND, catalogId);
        var option = snapshot.optionsByGroup().get(extras).stream()
                .filter(candidate -> candidate.id().equals(linked))
                .findFirst().orElseThrow();

        // The modifier row says nothing about marking, and a boolean has no
        // "unset". Inheriting the looser value would say an unmarked bottle is
        // being sold, which is the direction that costs a tenant a fine rather
        // than a payment method.
        var effective = snapshot.effectiveClassification(option);
        assertThat(effective.markingRequired()).isTrue();
        assertThat(effective.markingScheme()).isEqualTo(MarkingScheme.DATA_MATRIX);
        assertThat(effective.ageRestrictionYears()).isEqualTo(21);
        assertThat(effective.excisable()).isTrue();
    }

    @Test
    @DisplayName("a fiscal name over Click's 63-character cap is refused, not truncated")
    void anOverlongFiscalNameIsRefused() {
        String tooLong = "Qo'zichoq go'shtidan tayyorlangan uy pishirilgan non bilan burger, dona";
        assertThat(tooLong.length()).isGreaterThan(63);

        // Truncating it would print a receipt line nobody can reconcile against
        // a menu, and nothing would say so — the column would simply reject or
        // the wire would silently cut it.
        assertThat(catchThrowable(() ->
                FiscalClassification.of("10202001001000000", "1512315", 1, tooLong)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("63");
    }

    @Test
    @DisplayName("a VAT rate that is not a whole percent is refused by the database and in code")
    void aVatRateThatCannotBeStatedIsRefused() {
        // Click's VATPercent and Payme's vat_percent are integer percents. 12.5%
        // has no representation on either wire, so it cannot be a tax profile at
        // all — refused where it is configured rather than rounded onto a legal
        // document or discovered by an adapter mid-checkout.
        assertThat(catchThrowable(() -> jdbc.sql("""
                INSERT INTO pricing.tax_profiles (
                    id, tenant_id, brand_id, jurisdiction_code, mode, rate_basis_points, valid_from)
                VALUES (:id, :tenantId, :brandId, 'UZ', 'INCLUSIVE', 1250, now())
                """)
                .param("id", UUID.randomUUID()).param("tenantId", TENANT).param("brandId", BRAND)
                .update()))
                .hasMessageContaining("ck_tax_rate_whole_percent");

        // A whole percent still passes, or the constraint would be refusing every
        // rate rather than the ones that cannot be stated.
        jdbc.sql("""
                INSERT INTO pricing.tax_profiles (
                    id, tenant_id, brand_id, jurisdiction_code, mode, rate_basis_points, valid_from)
                VALUES (:id, :tenantId, :brandId, 'UZ', 'INCLUSIVE', 1200, now())
                """)
                .param("id", UUID.randomUUID()).param("tenantId", TENANT).param("brandId", BRAND)
                .update();

        assertThat(FiscalVatRate.wholePercentOf(1200)).isEqualTo(12);
        assertThat(FiscalVatRate.isExpressible(1250)).isFalse();
        assertThat(catchThrowable(() -> FiscalVatRate.wholePercentOf(1250)))
                .isInstanceOf(FiscalVatRate.UnrepresentableVatRate.class)
                .hasMessageContaining("12.50 percent");
    }

    @Test
    @DisplayName("one node cannot hold two classifications, and reclassifying corrects the first")
    void classificationIsOnePerNode() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(TENANT, BRAND, catalogId, "BURGER",
                "Burger", null, LOCALE, "SKU-25", "PIECE", CLASSIFIED, ACTOR);

        authoring.classify(TENANT, BRAND, PriceableNode.variant(burger.defaultVariantId()),
                FiscalClassification.of("10202001009000000", "1512315", 1, "Burger, dona"), ACTOR);

        // A correction, not a second row. Two classifications for one node would
        // make the code on a receipt depend on row order.
        assertThat(jdbc.sql("SELECT count(*) FROM catalog.fiscal_classifications "
                        + "WHERE variant_id = :id")
                .param("id", burger.defaultVariantId()).query(Long.class).single())
                .isEqualTo(1L);
        assertThat(jdbc.sql("SELECT mxik_code FROM catalog.fiscal_classifications "
                        + "WHERE variant_id = :id")
                .param("id", burger.defaultVariantId()).query(String.class).single())
                .isEqualTo("10202001009000000");
        // The derived pair is what the unique index is built on, so it has to be
        // populated rather than left for a caller to keep in step.
        assertThat(jdbc.sql("SELECT priceable_type FROM catalog.fiscal_classifications "
                        + "WHERE variant_id = :id")
                .param("id", burger.defaultVariantId()).query(String.class).single())
                .isEqualTo("VARIANT");
    }

    @Test
    @DisplayName("the database refuses a blank code written around the domain type")
    void theDatabaseRefusesABlankClassificationToo() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Main menu", LOCALE);
        var burger = authoring.createProduct(TENANT, BRAND, catalogId, "BURGER",
                "Burger", null, LOCALE, "SKU-22", "PIECE", CLASSIFIED, ACTOR);

        // The record normalises blank to null; this is the backstop for a POS
        // import or a migration writing the column directly. Stored as-is, an
        // empty string satisfies "the column is set" while classifying nothing,
        // and a coverage report built on IS NOT NULL would call the brand
        // complete while its receipts went out unclassified.
        assertThat(catchThrowable(() -> jdbc.sql(
                "UPDATE catalog.fiscal_classifications SET mxik_code = '' WHERE variant_id = :id")
                .param("id", burger.defaultVariantId()).update()))
                .hasMessageContaining("ck_fiscal_classification_codes_not_blank");

        // And a marking scheme that disagrees with the requirement, which would
        // be a marked good whose codes nobody will ever capture.
        assertThat(catchThrowable(() -> jdbc.sql(
                "UPDATE catalog.fiscal_classifications SET marking_required = true "
                        + "WHERE variant_id = :id")
                .param("id", burger.defaultVariantId()).update()))
                .hasMessageContaining("ck_fiscal_classification_marking_agrees");
    }

    private void insertTenancy() {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone,
                    status, version)
                VALUES (:id, 'catalog-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'OTHER', 'other', 'Brand', 'ACTIVE', 0)
                """).param("id", OTHER_BRAND).param("tenantId", TENANT).update();

        jdbc.sql("""
                INSERT INTO tenant.locations (
                    id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, 'MAIN01', 'main-01', 'Main',
                        'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", LOCATION).param("tenantId", TENANT).param("brandId", BRAND)
                .update();

        // ADR 0036: catalog.publications.channel is now a foreign key to the
        // tenant's channel registry. In production V0020 backfills STOREFRONT and
        // StorefrontChannelSeeder creates it for new tenants; a fixture inserting
        // tenancy rows by hand has to register the channels it publishes to.
        insertChannel("STOREFRONT", "WEB");
        insertChannel("KIOSK", "KIOSK");
    }

    private void insertChannel(String code, String systemType) {
        jdbc.sql("""
                INSERT INTO tenant.sales_channels (
                    id, tenant_id, code, system_type, display_name, status)
                VALUES (:id, :tenantId, :code, :systemType, :code, 'ACTIVE')
                """)
                .param("id", UUID.randomUUID()).param("tenantId", TENANT)
                .param("code", code).param("systemType", systemType)
                .update();
    }

    private static VariantPricingLookup allPriced() {
        return (tenantId, brandId, variantIds) -> variantIds;
    }

    private static VariantPricingLookup unwired() {
        return new VariantPricingLookup() {
            @Override
            public Set<UUID> pricedVariants(UUID tenantId, UUID brandId, Set<UUID> variantIds) {
                return variantIds;
            }

            @Override
            public boolean isWired() {
                return false;
            }
        };
    }

    /**
     * Controls which assets count as verified.
     *
     * <p>Media has its own lifecycle tests against real MinIO; what matters here
     * is only how catalog reacts to an asset that is not yet displayable.
     */
    /**
     * A verified asset of this tenant, inserted the way finalize leaves one.
     *
     * <p>{@code AVAILABLE} needs its three verified columns filled — V0015's
     * check constraint says an asset a storefront may show is one that was read
     * back from the object store — so this is a whole row rather than a stub.
     */
    private MediaAssetId aMediaAsset(UUID tenantId) {
        MediaAssetId assetId = MediaAssetId.generate();
        jdbc.sql("""
                INSERT INTO media.assets (
                    asset_id, tenant_id, owner_scope, owner_id, object_key, bucket,
                    status, visibility, declared_content_type, declared_size_bytes,
                    verified_content_type, verified_size_bytes, verified_checksum_sha256,
                    width_px, height_px)
                VALUES (
                    :assetId, :tenantId, 'BRAND', :ownerId, :objectKey, 'qoida-media-test',
                    'AVAILABLE', 'PUBLIC', 'image/jpeg', 2048,
                    'image/jpeg', 2048, repeat('a', 64), 640, 480)
                """)
                .param("assetId", assetId.value())
                .param("tenantId", tenantId)
                .param("ownerId", BRAND)
                .param("objectKey", "%s/brand/%s/%s".formatted(tenantId, BRAND, assetId.value()))
                .update();
        return assetId;
    }

    private static final class MutableMediaAvailability implements MediaAvailability {

        private Set<MediaAssetId> displayable = Set.of();

        @Override
        public boolean allDisplayable(UUID tenantId, Set<MediaAssetId> assetIds) {
            return displayable.containsAll(assetIds);
        }
    }
}
