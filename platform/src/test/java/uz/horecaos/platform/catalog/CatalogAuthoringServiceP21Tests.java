package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
import uz.horecaos.platform.catalog.application.CatalogAuthoringService.BulkClassifyItem;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService.BulkClassifyOutcome;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService.BulkClassifyStatus;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService.ProductCreated;
import uz.horecaos.platform.catalog.application.CatalogQueryService;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.CatalogEntities.PriceableNode;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;

/**
 * P21's row actions and the fiscal workbench's bulk classify —
 * {@link CatalogAuthoringService#duplicateProduct}, {@link
 * CatalogAuthoringService#setProductStatus}, {@link
 * CatalogAuthoringService#stopInAllBranches} and {@link
 * CatalogAuthoringService#bulkClassify}.
 *
 * <p>Same fixture shape as {@link CatalogQueryServiceTests}: every test
 * builds through the real authoring path against a migrated schema, so the
 * property under test is the write and the join, not a fixture agreeing with
 * itself.
 */
class CatalogAuthoringServiceP21Tests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
    private static final String LOCALE = "uz";
    private static final UUID ACTOR = UUID.randomUUID();
    private static final String ACTOR_SUBJECT = "operator-1";
    private static final FiscalClassification UNCLASSIFIED = FiscalClassification.unclassified();

    private static TestDatabase.Handle db;

    private JdbcClient jdbc;
    private JdbcCatalogStore store;
    private CatalogAuthoringService authoring;
    private CatalogQueryService query;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(), "Docker is required for the catalog tests");
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
                        + "catalog.location_offerings, catalog.translations, catalog.variants, catalog.products, "
                        + "catalog.fees, catalog.catalogs CASCADE")
                .update();
        jdbc.sql("TRUNCATE TABLE media.assets CASCADE").update();
        jdbc.sql("TRUNCATE TABLE tenant.locations, tenant.tenants CASCADE").update();

        jdbc.sql("""
                INSERT INTO tenant.tenants (id, slug, legal_name, display_name, default_currency, default_timezone, status, version)
                VALUES (:id, 'p21-authoring-tenant', 'Legal', 'Display', 'UZS', 'Asia/Tashkent', 'ACTIVE', 0)
                """).param("id", TENANT).update();
        jdbc.sql("""
                INSERT INTO tenant.brands (id, tenant_id, code, slug, display_name, status, version)
                VALUES (:id, :tenantId, 'MAIN', 'main', 'Brand', 'ACTIVE', 0)
                """).param("id", BRAND).param("tenantId", TENANT).update();

        store = new JdbcCatalogStore(jdbc, JsonMapper.builder().build());
        CommercialDefaults.Wired commercial = CommercialDefaults.wire(jdbc, Clock.systemUTC());
        authoring = new CatalogAuthoringService(
                store,
                new uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder(
                        jdbc, JsonMapper.builder().build()),
                commercial.entitlements(),
                commercial.usage(),
                Clock.systemUTC());
        query = new CatalogQueryService(store, LOCALE);
    }

    // ------------------------------------------------------------ duplicate

    @Test
    @DisplayName("duplicating a product copies its variants (with fiscal data), translations, "
            + "categories, catalogs, modifier groups and media, under a fresh id and code")
    void duplicateProductCopiesEverything() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        UUID hot = authoring.createCategory(TENANT, BRAND, catalogId, null, "HOT", "Issiq", LOCALE, 1);
        UUID cold = authoring.createCategory(TENANT, BRAND, catalogId, null, "COLD", "Sovuq", LOCALE, 2);
        ProductCreated plov = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "PLOV",
                "Osh",
                "Qo'y go'shti bilan",
                LOCALE,
                "SKU-PLOV",
                "PIECE",
                FiscalClassification.of("10101001001000000", "1", 796, "Osh"),
                ACTOR);
        authoring.translate(TENANT, BRAND, EntityType.PRODUCT, plov.productId(), "ru", "Плов", "С бараниной");
        authoring.placeProductInCategory(TENANT, BRAND, hot, plov.productId(), 0);
        authoring.placeProductInCategory(TENANT, BRAND, cold, plov.productId(), 0);
        UUID largeVariant = authoring.addVariant(
                TENANT, BRAND, plov.productId(), "SKU-PLOV-L", "PIECE", "Katta", LOCALE, 1, UNCLASSIFIED, ACTOR);
        UUID groupId =
                authoring.createModifierGroup(TENANT, BRAND, "EXTRAS", "Qo'shimchalar", LOCALE, false, 0, 3, false);
        authoring.attachModifierGroup(TENANT, BRAND, plov.productId(), groupId, 2);
        UUID productAsset = seedMediaAsset();
        authoring.attachMedia(
                TENANT, BRAND, EntityType.PRODUCT, plov.productId(), new MediaAssetId(productAsset), "PRIMARY", 0);

        ProductCreated duplicate = authoring.duplicateProduct(TENANT, BRAND, plov.productId(), ACTOR);

        assertThat(duplicate.productId()).isNotEqualTo(plov.productId());

        CatalogQueryService.ProductDetail detail = query.productDetail(TENANT, BRAND, duplicate.productId());
        assertThat(detail.code()).isNotEqualTo("PLOV").startsWith("PLOV-");
        assertThat(detail.status()).isEqualTo("ACTIVE");
        assertThat(detail.translations()).containsOnlyKeys("uz", "ru");
        assertThat(Objects.requireNonNull(detail.translations().get("uz")).name())
                .isEqualTo("Osh");
        assertThat(Objects.requireNonNull(detail.translations().get("ru")).name())
                .isEqualTo("Плов");
        assertThat(detail.catalogIds()).containsExactly(catalogId);
        assertThat(detail.categoryIds()).containsExactlyInAnyOrder(hot, cold);
        assertThat(detail.variants()).hasSize(2);
        assertThat(detail.variants())
                .noneMatch(v -> v.variantId().equals(plov.defaultVariantId())
                        || v.variantId().equals(largeVariant));
        assertThat(detail.variants())
                .filteredOn(CatalogQueryService.VariantDetail::isDefault)
                .singleElement()
                .satisfies(v -> {
                    assertThat(v.variantId()).isEqualTo(duplicate.defaultVariantId());
                    assertThat(v.fiscal()).isNotNull();
                    assertThat(java.util.Objects.requireNonNull(v.fiscal()).mxikCode())
                            .isEqualTo("10101001001000000");
                });
        assertThat(detail.variants())
                .filteredOn(v -> !v.isDefault())
                .singleElement()
                .satisfies(
                        v -> assertThat(Objects.requireNonNull(v.translations().get("uz"))
                                        .name())
                                .isEqualTo("Katta"));
        assertThat(detail.modifierGroups()).containsExactly(new CatalogQueryService.AttachedModifierGroup(groupId, 2));
        assertThat(detail.media()).singleElement().satisfies(m -> {
            assertThat(m.mediaAssetId()).isEqualTo(productAsset);
            assertThat(m.role()).isEqualTo("PRIMARY");
        });

        // The original is untouched.
        assertThat(query.productDetail(TENANT, BRAND, plov.productId()).variants())
                .hasSize(2);
    }

    @Test
    @DisplayName("a duplicate starts at the source product's own status — archiving the source "
            + "does not make an archived duplicate spring back to sellable")
    void duplicateProductPreservesSourceStatus() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        ProductCreated plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.setProductStatus(TENANT, BRAND, plov.productId(), Status.ARCHIVED, ACTOR_SUBJECT);

        ProductCreated duplicate = authoring.duplicateProduct(TENANT, BRAND, plov.productId(), ACTOR);

        assertThat(query.productDetail(TENANT, BRAND, duplicate.productId()).status())
                .isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("duplicating a product this brand does not have is refused, not a silent no-op")
    void duplicateProductOfUnknownProductThrows() {
        assertThatThrownBy(() -> authoring.duplicateProduct(TENANT, BRAND, UUID.randomUUID(), ACTOR))
                .isInstanceOf(CatalogAuthoringService.UnknownProductException.class);
    }

    // -------------------------------------------------------------- status

    @Test
    @DisplayName("changing a product's status writes it, and setting the same status again is a no-op")
    void setProductStatusChangesStatusAndNoOpsWhenUnchanged() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        ProductCreated plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);

        authoring.setProductStatus(TENANT, BRAND, plov.productId(), Status.ARCHIVED, ACTOR_SUBJECT);
        assertThat(store.productById(TENANT, BRAND, plov.productId())
                        .orElseThrow()
                        .status())
                .isEqualTo(Status.ARCHIVED);

        // Idempotent: setting the status a product already has does not throw
        // and does not need a second reason to exist.
        authoring.setProductStatus(TENANT, BRAND, plov.productId(), Status.ARCHIVED, ACTOR_SUBJECT);
        assertThat(store.productById(TENANT, BRAND, plov.productId())
                        .orElseThrow()
                        .status())
                .isEqualTo(Status.ARCHIVED);

        authoring.setProductStatus(TENANT, BRAND, plov.productId(), Status.ACTIVE, ACTOR_SUBJECT);
        assertThat(store.productById(TENANT, BRAND, plov.productId())
                        .orElseThrow()
                        .status())
                .isEqualTo(Status.ACTIVE);
    }

    @Test
    @DisplayName("changing the status of a product this brand does not have is refused")
    void setProductStatusOfUnknownProductThrows() {
        assertThatThrownBy(() ->
                        authoring.setProductStatus(TENANT, BRAND, UUID.randomUUID(), Status.ARCHIVED, ACTOR_SUBJECT))
                .isInstanceOf(CatalogAuthoringService.UnknownProductException.class);
    }

    // ------------------------------------------------------ stop everywhere

    @Test
    @DisplayName("stop-in-all-branches flips every AVAILABLE offering across every variant of the "
            + "product, leaves an already-UNAVAILABLE row and another product's offering untouched")
    void stopInAllBranchesStopsOnlyAvailableOfferingsAndLeavesOtherProductsAlone() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        ProductCreated plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        UUID secondVariant = authoring.addVariant(
                TENANT, BRAND, plov.productId(), "SKU-PLOV-L", "PIECE", "Katta", LOCALE, 1, UNCLASSIFIED, ACTOR);
        ProductCreated other = authoring.createProduct(
                TENANT, BRAND, catalogId, "SALAD", "Salat", null, LOCALE, "SKU-SALAD", "PIECE", UNCLASSIFIED, ACTOR);

        UUID locationOne = seedLocation("STOPL1");
        UUID locationTwo = seedLocation("STOPL2");
        store.upsertOffering(
                TENANT, BRAND, locationOne, plov.defaultVariantId(), OfferingStatus.AVAILABLE, "DELIVERY,PICKUP");
        store.upsertOffering(
                TENANT, BRAND, locationTwo, plov.defaultVariantId(), OfferingStatus.UNAVAILABLE, "DELIVERY,PICKUP");
        store.upsertOffering(TENANT, BRAND, locationOne, secondVariant, OfferingStatus.AVAILABLE, "DELIVERY,PICKUP");
        store.upsertOffering(
                TENANT, BRAND, locationOne, other.defaultVariantId(), OfferingStatus.AVAILABLE, "DELIVERY,PICKUP");

        int changed = authoring.stopInAllBranches(TENANT, BRAND, plov.productId(), ACTOR_SUBJECT);

        assertThat(changed).isEqualTo(2);
        List<uz.horecaos.platform.catalog.domain.CatalogEntities.LocationOffering> offerings =
                store.offeringsForBrand(TENANT, BRAND);
        assertThat(offerings)
                .filteredOn(o -> o.variantId().equals(plov.defaultVariantId())
                        && o.locationId().equals(locationOne))
                .singleElement()
                .satisfies(o -> assertThat(o.status()).isEqualTo(OfferingStatus.UNAVAILABLE));
        assertThat(offerings)
                .filteredOn(o -> o.variantId().equals(plov.defaultVariantId())
                        && o.locationId().equals(locationTwo))
                .singleElement()
                .as("already UNAVAILABLE — not re-asserted, not touched a second time")
                .satisfies(o -> assertThat(o.status()).isEqualTo(OfferingStatus.UNAVAILABLE));
        assertThat(offerings)
                .filteredOn(o -> o.variantId().equals(secondVariant))
                .singleElement()
                .satisfies(o -> assertThat(o.status()).isEqualTo(OfferingStatus.UNAVAILABLE));
        assertThat(offerings)
                .filteredOn(o -> o.variantId().equals(other.defaultVariantId()))
                .singleElement()
                .as("another product's offering is never touched by this product's stop")
                .satisfies(o -> assertThat(o.status()).isEqualTo(OfferingStatus.AVAILABLE));
    }

    @Test
    @DisplayName("stopping a product this brand does not have everywhere is refused")
    void stopInAllBranchesOfUnknownProductThrows() {
        assertThatThrownBy(() -> authoring.stopInAllBranches(TENANT, BRAND, UUID.randomUUID(), ACTOR_SUBJECT))
                .isInstanceOf(CatalogAuthoringService.UnknownProductException.class);
    }

    // ----------------------------------------------------------- bulk classify

    @Test
    @DisplayName("bulk classify writes a VARIANT, a MODIFIER_OPTION and a FEE node in one call, "
            + "each reported CLASSIFIED")
    void bulkClassifyClassifiesAcrossAllThreeNodeTypes() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        ProductCreated plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        UUID groupId =
                authoring.createModifierGroup(TENANT, BRAND, "EXTRAS", "Qo'shimchalar", LOCALE, false, 0, 3, false);
        UUID optionId = authoring.addModifierOption(
                TENANT, BRAND, groupId, "CHEESE", "Pishloq", LOCALE, null, 1, 1, UNCLASSIFIED, ACTOR);
        UUID feeId = store.ensureFee(TENANT, BRAND, "DELIVERY");

        FiscalClassification fiscal = FiscalClassification.of("10101001001000000", "1", 796, "Osh");
        List<BulkClassifyOutcome> outcomes = authoring.bulkClassify(
                TENANT,
                BRAND,
                List.of(
                        new BulkClassifyItem(PriceableNode.variant(plov.defaultVariantId()), fiscal),
                        new BulkClassifyItem(PriceableNode.modifierOption(optionId), fiscal),
                        new BulkClassifyItem(PriceableNode.fee(feeId), fiscal)),
                ACTOR);

        assertThat(outcomes)
                .hasSize(3)
                .allSatisfy(o -> assertThat(o.status()).isEqualTo(BulkClassifyStatus.CLASSIFIED));

        var classifications = store.classificationsForBrand(TENANT, BRAND);
        assertThat(Objects.requireNonNull(classifications.get(plov.defaultVariantId()))
                        .mxikCode())
                .isEqualTo("10101001001000000");
        assertThat(Objects.requireNonNull(classifications.get(optionId)).mxikCode())
                .isEqualTo("10101001001000000");
        assertThat(Objects.requireNonNull(classifications.get(feeId)).mxikCode())
                .isEqualTo("10101001001000000");
    }

    @Test
    @DisplayName("one unknown node id in the batch is reported NOT_FOUND without failing the rest of it")
    void bulkClassifyReportsNotFoundWithoutFailingTheRestOfTheBatch() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        ProductCreated plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        UUID bogusVariantId = UUID.randomUUID();
        FiscalClassification fiscal = FiscalClassification.of("10101001001000000", "1", 796, "Osh");

        List<BulkClassifyOutcome> outcomes = authoring.bulkClassify(
                TENANT,
                BRAND,
                List.of(
                        new BulkClassifyItem(PriceableNode.variant(plov.defaultVariantId()), fiscal),
                        new BulkClassifyItem(PriceableNode.variant(bogusVariantId), fiscal)),
                ACTOR);

        assertThat(outcomes)
                .filteredOn(o -> o.node().id().equals(plov.defaultVariantId()))
                .singleElement()
                .satisfies(o -> assertThat(o.status()).isEqualTo(BulkClassifyStatus.CLASSIFIED));
        assertThat(outcomes)
                .filteredOn(o -> o.node().id().equals(bogusVariantId))
                .singleElement()
                .satisfies(o -> assertThat(o.status()).isEqualTo(BulkClassifyStatus.NOT_FOUND));
        assertThat(Objects.requireNonNull(
                                store.classificationsForBrand(TENANT, BRAND).get(plov.defaultVariantId()))
                        .mxikCode())
                .as("the valid item in the batch is still written even though its neighbour failed")
                .isEqualTo("10101001001000000");
    }

    @Test
    @DisplayName("an empty classification in the batch is reported SKIPPED_EMPTY and writes nothing")
    void bulkClassifySkipsAnEmptyClassificationWithoutWritingARow() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        ProductCreated plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);

        List<BulkClassifyOutcome> outcomes = authoring.bulkClassify(
                TENANT,
                BRAND,
                List.of(new BulkClassifyItem(PriceableNode.variant(plov.defaultVariantId()), UNCLASSIFIED)),
                ACTOR);

        assertThat(outcomes)
                .singleElement()
                .satisfies(o -> assertThat(o.status()).isEqualTo(BulkClassifyStatus.SKIPPED_EMPTY));
        assertThat(store.classificationsForBrand(TENANT, BRAND)).doesNotContainKey(plov.defaultVariantId());
    }

    @Test
    @DisplayName(
            "bulk classify is idempotent — calling it twice with the same items leaves the same rows in the same state")
    void bulkClassifyIsIdempotent() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        ProductCreated plov = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        FiscalClassification fiscal = FiscalClassification.of("10101001001000000", "1", 796, "Osh");
        List<BulkClassifyItem> items =
                List.of(new BulkClassifyItem(PriceableNode.variant(plov.defaultVariantId()), fiscal));

        List<BulkClassifyOutcome> first = authoring.bulkClassify(TENANT, BRAND, items, ACTOR);
        List<BulkClassifyOutcome> second = authoring.bulkClassify(TENANT, BRAND, items, ACTOR);

        assertThat(first).hasSize(1).allSatisfy(o -> assertThat(o.status()).isEqualTo(BulkClassifyStatus.CLASSIFIED));
        assertThat(second).hasSize(1).allSatisfy(o -> assertThat(o.status()).isEqualTo(BulkClassifyStatus.CLASSIFIED));
        assertThat(store.classificationsForBrand(TENANT, BRAND)).hasSize(1);
        assertThat(Objects.requireNonNull(
                                store.classificationsForBrand(TENANT, BRAND).get(plov.defaultVariantId()))
                        .mxikCode())
                .isEqualTo("10101001001000000");
    }

    // --------------------------------------------------------------- fixtures

    private UUID seedMediaAsset() {
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
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("objectKey", "tenants/" + TENANT + "/media/" + assetId)
                .update();
        return assetId;
    }

    private UUID seedLocation(String code) {
        UUID locationId = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO tenant.locations (
                    id, tenant_id, brand_id, code, slug, display_name, timezone, status, version)
                VALUES (:id, :tenantId, :brandId, :code, :slug, :code, 'Asia/Tashkent', 'ACTIVE', 0)
                """)
                .param("id", locationId)
                .param("tenantId", TENANT)
                .param("brandId", BRAND)
                .param("code", code)
                .param("slug", code.toLowerCase(Locale.ROOT))
                .update();
        return locationId;
    }
}
