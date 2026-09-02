package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
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
import uz.horecaos.platform.catalog.application.CatalogAuthoringService;
import uz.horecaos.platform.catalog.application.CatalogQueryService;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.support.TestDatabase;

/**
 * {@code CatalogQueryController}'s read side, over the same tables {@code
 * CatalogAuthoringService} writes.
 *
 * <p>Before this class there was no HTTP way to read back a catalog, a
 * category tree, a products list, or a product's own detail — only to write
 * one. Every test here builds through the real authoring path, the way
 * {@link CatalogAvailabilityReadModelTests} builds through it for the 86
 * screen, so the property under test is the join and the tenant scope, not a
 * fixture agreeing with itself.
 */
class CatalogQueryServiceTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final UUID OTHER_TENANT = UUID.randomUUID();
    private static final UUID OTHER_BRAND = UUID.randomUUID();

    private static final String LOCALE = "uz";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final FiscalClassification UNCLASSIFIED = FiscalClassification.unclassified();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCatalogStore store;
    private CatalogAuthoringService authoring;
    private CatalogQueryService query;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the catalog query tests");
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
        jdbc.sql("TRUNCATE TABLE catalog.media_relations, catalog.fiscal_classifications, "
                        + "catalog.product_modifier_groups, catalog.modifier_options, catalog.modifier_groups, "
                        + "catalog.category_products, catalog.categories, catalog.catalog_products, "
                        + "catalog.translations, catalog.variants, catalog.products, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE media.assets CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.tenants CASCADE").update();

        insertTenantAndBrand(TENANT, BRAND, "query-tenant", "MAIN");
        insertTenantAndBrand(OTHER_TENANT, OTHER_BRAND, "query-other-tenant", "OTHER-MAIN");

        store = new JdbcCatalogStore(jdbc, JsonMapper.builder().build());
        authoring = new CatalogAuthoringService(
                store,
                new uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder(
                        jdbc, JsonMapper.builder().build()),
                Clock.systemUTC());
        query = new CatalogQueryService(store, LOCALE);
    }

    // -------------------------------------------------------------- catalogs

    @Test
    @DisplayName("catalogs are named in the default locale, falling back to code when untranslated in it")
    void catalogsResolveNameOrFallBackToCode() {
        authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        // Named only in Russian: the configured default locale is uz, so this
        // one has no uz row to resolve and must fall back to its code.
        authoring.createCatalog(TENANT, BRAND, "SEASONAL", "Sezonniy", "ru");

        List<CatalogQueryService.CatalogSummary> catalogs = query.catalogs(TENANT, BRAND);

        assertThat(catalogs).hasSize(2);
        assertThat(catalogs)
                .filteredOn(c -> c.code().equals("MAIN"))
                .singleElement()
                .satisfies(c -> assertThat(c.name()).isEqualTo("Asosiy menyu"));
        assertThat(catalogs)
                .filteredOn(c -> c.code().equals("SEASONAL"))
                .singleElement()
                .satisfies(c -> assertThat(c.name()).isEqualTo("SEASONAL"));
    }

    @Test
    @DisplayName("another tenant's catalogs never appear")
    void catalogsAreTenantIsolated() {
        authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        authoring.createCatalog(OTHER_TENANT, OTHER_BRAND, "MAIN", "Boshqa menyu", LOCALE);

        assertThat(query.catalogs(TENANT, BRAND))
                .extracting(CatalogQueryService.CatalogSummary::code)
                .containsExactly("MAIN");
        assertThat(query.catalogs(OTHER_TENANT, OTHER_BRAND))
                .extracting(CatalogQueryService.CatalogSummary::name)
                .containsExactly("Boshqa menyu");
    }

    // ------------------------------------------------------------ categories

    @Test
    @DisplayName("categories are flat, with each one's parent id and product count")
    void categoriesCarryParentAndProductCount() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        UUID hot = authoring.createCategory(TENANT, BRAND, catalogId, null, "HOT", "Issiq", LOCALE, 1);
        UUID soup = authoring.createCategory(TENANT, BRAND, catalogId, hot, "SOUP", "Sho'rva", LOCALE, 1);
        var plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        var lagman = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "LAGMAN",
                "Lag'mon",
                null,
                LOCALE,
                "SKU-LAGMAN",
                "PIECE",
                UNCLASSIFIED,
                ACTOR);
        authoring.placeProductInCategory(TENANT, BRAND, hot, plov.productId(), 1);
        authoring.placeProductInCategory(TENANT, BRAND, hot, lagman.productId(), 2);

        List<CatalogQueryService.CategorySummary> categories = query.categories(TENANT, BRAND, catalogId);

        assertThat(categories)
                .filteredOn(c -> c.categoryId().equals(hot))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.parentCategoryId()).isNull();
                    assertThat(c.name()).isEqualTo("Issiq");
                    assertThat(c.productCount()).isEqualTo(2);
                });
        assertThat(categories)
                .filteredOn(c -> c.categoryId().equals(soup))
                .singleElement()
                .satisfies(c -> {
                    assertThat(c.parentCategoryId()).isEqualTo(hot);
                    assertThat(c.productCount()).isZero();
                });
    }

    @Test
    @DisplayName("another tenant's categories never appear, even naming the real catalog id")
    void categoriesAreTenantIsolated() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        authoring.createCategory(TENANT, BRAND, catalogId, null, "HOT", "Issiq", LOCALE, 1);

        assertThat(query.categories(OTHER_TENANT, OTHER_BRAND, catalogId)).isEmpty();
    }

    // -------------------------------------------------------------- products

    @Test
    @DisplayName("a page shorter than the limit is the end; a full page's cursor continues from the last row")
    void productsPaginationBoundary() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        for (int i = 0; i < 3; i++) {
            authoring.createProduct(
                    TENANT,
                    BRAND,
                    catalogId,
                    "P" + i,
                    "Product " + i,
                    null,
                    LOCALE,
                    "SKU-" + i,
                    "PIECE",
                    UNCLASSIFIED,
                    ACTOR);
        }

        int pageSize = 2;
        List<CatalogQueryService.ProductSummary> firstPage = query.products(TENANT, BRAND, catalogId, null, pageSize);
        assertThat(firstPage).hasSize(2);
        // The controller's own short-page-is-the-end rule (mirroring
        // variantsAtLocation): a full page carries a cursor.
        String firstCursor = firstPage.size() < pageSize
                ? null
                : firstPage.get(firstPage.size() - 1).productId().toString();
        assertThat(firstCursor).isNotNull();

        List<CatalogQueryService.ProductSummary> secondPage =
                query.products(TENANT, BRAND, catalogId, UUID.fromString(firstCursor), pageSize);
        assertThat(secondPage).hasSize(1);
        String secondCursor = secondPage.size() < pageSize
                ? null
                : secondPage.get(secondPage.size() - 1).productId().toString();
        assertThat(secondCursor).as("a short page ends the collection").isNull();

        assertThat(firstPage)
                .extracting(CatalogQueryService.ProductSummary::productId)
                .doesNotContainAnyElementsOf(secondPage.stream()
                        .map(CatalogQueryService.ProductSummary::productId)
                        .toList());
    }

    @Test
    @DisplayName("a product summary reports its variant count, category names, and whether any variant carries an MXIK")
    void productSummaryComposesVariantsCategoriesAndFiscalCoverage() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        UUID hot = authoring.createCategory(TENANT, BRAND, catalogId, null, "HOT", "Issiq", LOCALE, 1);
        UUID cold = authoring.createCategory(TENANT, BRAND, catalogId, null, "COLD", "Sovuq", LOCALE, 2);
        var plov = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "PLOV",
                "Osh",
                null,
                LOCALE,
                "SKU-PLOV",
                "PIECE",
                FiscalClassification.of("10101001001000000", "1", 796, "Osh"),
                ACTOR);
        authoring.addVariant(
                TENANT, BRAND, plov.productId(), "SKU-PLOV-L", "PIECE", "Katta", LOCALE, 1, UNCLASSIFIED, ACTOR);
        authoring.placeProductInCategory(TENANT, BRAND, hot, plov.productId(), 1);
        authoring.placeProductInCategory(TENANT, BRAND, cold, plov.productId(), 1);

        var undressed = authoring.createProduct(
                TENANT, BRAND, catalogId, "SALAD", "Salat", null, LOCALE, "SKU-SALAD", "PIECE", UNCLASSIFIED, ACTOR);

        List<CatalogQueryService.ProductSummary> page = query.products(TENANT, BRAND, catalogId, null, 50);

        assertThat(page)
                .filteredOn(p -> p.productId().equals(plov.productId()))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.variantCount()).isEqualTo(2);
                    assertThat(p.categoryNames()).containsExactlyInAnyOrder("Issiq", "Sovuq");
                    assertThat(p.hasMxik()).isTrue();
                    assertThat(p.name()).isEqualTo("Osh");
                });
        assertThat(page)
                .filteredOn(p -> p.productId().equals(undressed.productId()))
                .singleElement()
                .satisfies(p -> {
                    assertThat(p.hasMxik()).isFalse();
                    assertThat(p.categoryNames()).isEmpty();
                });
    }

    @Test
    @DisplayName("another tenant's products never appear on a page")
    void productsAreTenantIsolated() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        authoring.createProduct(
                TENANT, BRAND, catalogId, "OURS", "Ours", null, LOCALE, "SKU-OURS", "PIECE", UNCLASSIFIED, ACTOR);

        assertThat(query.products(OTHER_TENANT, OTHER_BRAND, catalogId, null, 50))
                .isEmpty();
    }

    // ---------------------------------------------------------- product detail

    @Test
    @DisplayName(
            "a product's detail carries every locale, every variant with its own fiscal data, its groups and media")
    void productDetailComposesEverything() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        UUID hot = authoring.createCategory(TENANT, BRAND, catalogId, null, "HOT", "Issiq", LOCALE, 1);
        var plov = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "PLOV",
                "Osh",
                "Qo'y go'shti bilan",
                LOCALE,
                "SKU-PLOV",
                "PIECE",
                UNCLASSIFIED,
                ACTOR);
        authoring.translate(TENANT, BRAND, EntityType.PRODUCT, plov.productId(), "ru", "Плов", "С бараниной");
        authoring.placeProductInCategory(TENANT, BRAND, hot, plov.productId(), 1);

        UUID secondVariant = authoring.addVariant(
                TENANT,
                BRAND,
                plov.productId(),
                "SKU-PLOV-L",
                "PIECE",
                "Katta",
                LOCALE,
                1,
                FiscalClassification.of("10101001001000000", "1", 796, "Osh katta"),
                ACTOR);

        UUID groupId =
                authoring.createModifierGroup(TENANT, BRAND, "EXTRAS", "Qo'shimchalar", LOCALE, false, 0, 3, false);
        authoring.attachModifierGroup(TENANT, BRAND, plov.productId(), groupId, 2);

        UUID productAsset = seedMediaAsset(TENANT, BRAND);
        UUID variantAsset = seedMediaAsset(TENANT, BRAND);
        authoring.attachMedia(
                TENANT, BRAND, EntityType.PRODUCT, plov.productId(), new MediaAssetId(productAsset), "PRIMARY", 0);
        authoring.attachMedia(
                TENANT, BRAND, EntityType.VARIANT, secondVariant, new MediaAssetId(variantAsset), "GALLERY", 1);

        CatalogQueryService.ProductDetail detail = query.productDetail(TENANT, BRAND, plov.productId());

        assertThat(detail.productId()).isEqualTo(plov.productId());
        assertThat(detail.code()).isEqualTo("PLOV");
        assertThat(detail.translations()).containsOnlyKeys("uz", "ru");
        assertThat(localized(detail.translations(), "uz").name()).isEqualTo("Osh");
        assertThat(localized(detail.translations(), "ru").name()).isEqualTo("Плов");
        assertThat(localized(detail.translations(), "ru").description()).isEqualTo("С бараниной");
        assertThat(detail.catalogIds()).containsExactly(catalogId);
        assertThat(detail.categoryIds()).containsExactly(hot);

        assertThat(detail.variants()).hasSize(2);
        assertThat(detail.variants())
                .filteredOn(CatalogQueryService.VariantDetail::isDefault)
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.variantId()).isEqualTo(plov.defaultVariantId());
                    assertThat(v.sku()).isEqualTo("SKU-PLOV");
                    assertThat(v.fiscal()).isNull();
                });
        assertThat(detail.variants())
                .filteredOn(v -> v.variantId().equals(secondVariant))
                .singleElement()
                .satisfies(v -> {
                    assertThat(localized(v.translations(), "uz").name()).isEqualTo("Katta");
                    assertThat(v.fiscal()).isNotNull();
                    assertThat(Objects.requireNonNull(v.fiscal()).mxikCode()).isEqualTo("10101001001000000");
                });

        assertThat(detail.modifierGroups()).containsExactly(new CatalogQueryService.AttachedModifierGroup(groupId, 2));

        assertThat(detail.media()).hasSize(2);
        assertThat(detail.media()).anySatisfy(m -> {
            assertThat(m.mediaAssetId()).isEqualTo(productAsset);
            assertThat(m.role()).isEqualTo("PRIMARY");
        });
        assertThat(detail.media()).anySatisfy(m -> {
            assertThat(m.mediaAssetId()).isEqualTo(variantAsset);
            assertThat(m.role()).isEqualTo("GALLERY");
        });
    }

    @Test
    @DisplayName("a product id that does not exist, and one belonging to another tenant, are both refused")
    void productDetailRefusesUnknownAndCrossTenantIds() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        var plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);

        assertThatThrownBy(() -> query.productDetail(TENANT, BRAND, UUID.randomUUID()))
                .isInstanceOf(CatalogQueryService.UnknownProductException.class);
        assertThatThrownBy(() -> query.productDetail(OTHER_TENANT, OTHER_BRAND, plov.productId()))
                .isInstanceOf(CatalogQueryService.UnknownProductException.class);
    }

    // ------------------------------------------------------------ modifier groups

    @Test
    @DisplayName("the group library lists every brand group, including one attached to no product at all")
    void modifierGroupLibraryIncludesUnattachedGroups() {
        UUID attached =
                authoring.createModifierGroup(TENANT, BRAND, "EXTRAS", "Qo'shimchalar", LOCALE, false, 0, 3, false);
        UUID unattached = authoring.createModifierGroup(TENANT, BRAND, "SPARE", "Zaxira", LOCALE, false, 0, 1, false);
        authoring.addModifierOption(
                TENANT, BRAND, attached, "CHEESE", "Pishloq", LOCALE, null, 1, 1, UNCLASSIFIED, ACTOR);
        authoring.addModifierOption(TENANT, BRAND, attached, "SAUCE", "Sous", LOCALE, null, 1, 2, UNCLASSIFIED, ACTOR);

        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        var plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.attachModifierGroup(TENANT, BRAND, plov.productId(), attached, 1);
        // `unattached` is deliberately never attached to any product — proof
        // that modifierGroupsForBrand does not, unlike modifierGroupsInCatalog,
        // join through product attachment.

        List<CatalogQueryService.ModifierGroupSummary> groups = query.modifierGroups(TENANT, BRAND);

        assertThat(groups)
                .extracting(CatalogQueryService.ModifierGroupSummary::groupId)
                .containsExactlyInAnyOrder(attached, unattached);
        assertThat(groups)
                .filteredOn(g -> g.groupId().equals(attached))
                .singleElement()
                .satisfies(g -> assertThat(g.optionCount()).isEqualTo(2));
        assertThat(groups)
                .filteredOn(g -> g.groupId().equals(unattached))
                .singleElement()
                .satisfies(g -> assertThat(g.optionCount()).isZero());
    }

    @Test
    @DisplayName("another tenant's modifier groups never appear")
    void modifierGroupsAreTenantIsolated() {
        authoring.createModifierGroup(TENANT, BRAND, "EXTRAS", "Qo'shimchalar", LOCALE, false, 0, 3, false);
        authoring.createModifierGroup(OTHER_TENANT, OTHER_BRAND, "EXTRAS", "Boshqa", LOCALE, false, 0, 3, false);

        assertThat(query.modifierGroups(TENANT, BRAND))
                .extracting(CatalogQueryService.ModifierGroupSummary::name)
                .containsExactly("Qo'shimchalar");
    }

    @Test
    @DisplayName("a group's detail carries every option, each with its own locales and fiscal data")
    void modifierGroupDetailComposesOptions() {
        UUID groupId =
                authoring.createModifierGroup(TENANT, BRAND, "EXTRAS", "Qo'shimchalar", LOCALE, true, 1, 2, false);
        UUID cheese = authoring.addModifierOption(
                TENANT,
                BRAND,
                groupId,
                "CHEESE",
                "Pishloq",
                LOCALE,
                null,
                1,
                1,
                FiscalClassification.of("20202002002000000", "2", 796, "Pishloq"),
                ACTOR);
        authoring.translate(TENANT, BRAND, EntityType.MODIFIER_OPTION, cheese, "ru", "Сыр", null);
        UUID sauce = authoring.addModifierOption(
                TENANT, BRAND, groupId, "SAUCE", "Sous", LOCALE, null, 1, 2, UNCLASSIFIED, ACTOR);

        CatalogQueryService.ModifierGroupDetail detail = query.modifierGroupDetail(TENANT, BRAND, groupId);

        assertThat(detail.groupId()).isEqualTo(groupId);
        assertThat(detail.required()).isTrue();
        assertThat(localized(detail.translations(), "uz").name()).isEqualTo("Qo'shimchalar");
        assertThat(detail.options()).hasSize(2);
        assertThat(detail.options())
                .filteredOn(o -> o.optionId().equals(cheese))
                .singleElement()
                .satisfies(o -> {
                    assertThat(o.translations()).containsOnlyKeys("uz", "ru");
                    assertThat(localized(o.translations(), "ru").name()).isEqualTo("Сыр");
                    assertThat(o.fiscal()).isNotNull();
                    assertThat(Objects.requireNonNull(o.fiscal()).mxikCode()).isEqualTo("20202002002000000");
                });
        assertThat(detail.options())
                .filteredOn(o -> o.optionId().equals(sauce))
                .singleElement()
                .satisfies(o -> assertThat(o.fiscal()).isNull());
    }

    @Test
    @DisplayName("a group id that does not exist, and one belonging to another tenant, are both refused")
    void modifierGroupDetailRefusesUnknownAndCrossTenantIds() {
        UUID groupId =
                authoring.createModifierGroup(TENANT, BRAND, "EXTRAS", "Qo'shimchalar", LOCALE, false, 0, 1, false);

        assertThatThrownBy(() -> query.modifierGroupDetail(TENANT, BRAND, UUID.randomUUID()))
                .isInstanceOf(CatalogQueryService.UnknownModifierGroupException.class);
        assertThatThrownBy(() -> query.modifierGroupDetail(OTHER_TENANT, OTHER_BRAND, groupId))
                .isInstanceOf(CatalogQueryService.UnknownModifierGroupException.class);
    }

    // ----------------------------------------------------------------- media

    @Test
    @DisplayName("attaching media writes the relation with the given role and sort order")
    void attachMediaWritesTheRelation() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        var plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        UUID assetId = seedMediaAsset(TENANT, BRAND);

        authoring.attachMedia(
                TENANT, BRAND, EntityType.PRODUCT, plov.productId(), new MediaAssetId(assetId), "PRIMARY", 3);

        assertThat(store.mediaRelationsForEntities(TENANT, BRAND, Set.of(plov.productId())))
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.mediaAssetId()).isEqualTo(assetId);
                    assertThat(row.role()).isEqualTo("PRIMARY");
                    assertThat(row.sortOrder()).isEqualTo(3);
                });
    }

    @Test
    @DisplayName("attaching the same asset, entity and role twice updates the row rather than duplicating it")
    void attachMediaIsIdempotentOnEntityAssetAndRole() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        var plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        UUID assetId = seedMediaAsset(TENANT, BRAND);

        authoring.attachMedia(
                TENANT, BRAND, EntityType.PRODUCT, plov.productId(), new MediaAssetId(assetId), "PRIMARY", 0);
        authoring.attachMedia(
                TENANT, BRAND, EntityType.PRODUCT, plov.productId(), new MediaAssetId(assetId), "PRIMARY", 9);

        List<JdbcCatalogStore.MediaRelationRow> rows =
                store.mediaRelationsForEntities(TENANT, BRAND, Set.of(plov.productId()));
        assertThat(rows)
                .as("the ON CONFLICT target is (entity_type, entity_id, media_asset_id, role)")
                .singleElement()
                .satisfies(row -> assertThat(row.sortOrder()).isEqualTo(9));
    }

    // --------------------------------------------------------------- fixtures

    /**
     * The translation for one locale, asserted present first — {@code
     * containsOnlyKeys}/{@code containsKey} above each call is what actually
     * proves the locale is there; this only turns that proof into a non-null
     * reference for NullAway, since {@code Map.get} stays {@code @Nullable} to
     * it regardless of what an earlier assertion established.
     */
    private static CatalogQueryService.LocalizedFields localized(
            Map<String, CatalogQueryService.LocalizedFields> translations, String locale) {
        return Objects.requireNonNull(translations.get(locale), () -> "no translation for " + locale);
    }

    private UUID seedMediaAsset(UUID tenantId, UUID brandId) {
        UUID assetId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO media.assets (
                    asset_id, tenant_id, owner_scope, owner_id, object_key, bucket, status, visibility,
                    declared_content_type, declared_size_bytes,
                    verified_content_type, verified_size_bytes, verified_checksum_sha256)
                VALUES (
                    :assetId, :tenantId, 'BRAND', :brandId, :objectKey, 'catalog-media', 'AVAILABLE', 'PUBLIC',
                    'image/jpeg', 1024,
                    'image/jpeg', 1024, 'deadbeef')
                """)
                .param("assetId", assetId)
                .param("tenantId", tenantId)
                .param("brandId", brandId)
                .param("objectKey", "tenants/" + tenantId + "/media/" + assetId)
                .update();
        return assetId;
    }

    private void insertTenantAndBrand(UUID tenantId, UUID brandId, String tenantSlug, String brandCode) {
        jdbc.sql("""
                INSERT INTO tenant.tenants (
                    id, slug, legal_name, display_name, default_currency, default_timezone,
                    status, version)
                VALUES (:id, :slug, 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", tenantId).param("slug", tenantSlug).update();

        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, :code, :slug, 'Brand', 'ACTIVE', 0)
                """)
                .param("id", brandId)
                .param("tenantId", tenantId)
                .param("code", brandCode)
                .param("slug", brandCode.toLowerCase(Locale.ROOT))
                .update();
    }
}
