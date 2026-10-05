package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
import uz.horecaos.platform.catalog.application.CatalogAuthoringService.BulkClassifyMode;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService.BulkClassifyOutcome;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService.BulkClassifyStatus;
import uz.horecaos.platform.catalog.application.CatalogAuthoringService.ProductCreated;
import uz.horecaos.platform.catalog.application.CatalogQueryService;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService;
import uz.horecaos.platform.catalog.application.CompositeProductAuthoringService.NewComboGroup;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.CatalogEntities.PriceableNode;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCompositeCatalogStore;
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
                        + "catalog.combo_components, catalog.combo_groups, catalog.variant_modifier_groups, "
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
    @DisplayName("duplicating a combo copies its groups, their headings and their components onto the copy's "
            + "container, and leaves the original and every price alone (ADR 0136)")
    void duplicateProductCopiesTheComboStructure() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        ProductCreated lunch = authoring.createProduct(
                TENANT, BRAND, catalogId, "LUNCH", "Tushlik", null, LOCALE, "SKU-LUNCH", "PIECE", UNCLASSIFIED, ACTOR);
        ProductCreated burger = authoring.createProduct(
                TENANT, BRAND, catalogId, "BURGER", "Burger", null, LOCALE, "SKU-B", "PIECE", UNCLASSIFIED, ACTOR);
        ProductCreated cola = authoring.createProduct(
                TENANT, BRAND, catalogId, "COLA", "Kola", null, LOCALE, "SKU-C", "PIECE", UNCLASSIFIED, ACTOR);
        var composites = new CompositeProductAuthoringService(
                new JdbcCompositeCatalogStore(jdbc),
                store,
                new uz.horecaos.platform.audit.infrastructure.persistence.JdbcAuditRecorder(
                        jdbc, JsonMapper.builder().build()),
                Clock.systemUTC());
        var main = composites.createComboGroup(
                new NewComboGroup(TENANT, BRAND, lunch.defaultVariantId(), "MAIN", "Asosiy", LOCALE, 1, 2, false, 0),
                ACTOR_SUBJECT);
        var drink = composites.createComboGroup(
                new NewComboGroup(TENANT, BRAND, lunch.defaultVariantId(), "DRINK", "Ichimlik", LOCALE, 0, 1, true, 1),
                ACTOR_SUBJECT);
        authoring.translate(TENANT, BRAND, EntityType.COMBO_GROUP, main.id(), "ru", "Основное", null);
        composites.addComponent(TENANT, BRAND, main.id(), burger.defaultVariantId(), 2, 0, ACTOR_SUBJECT);
        composites.addComponent(TENANT, BRAND, drink.id(), cola.defaultVariantId(), 1, 0, ACTOR_SUBJECT);

        ProductCreated copy = authoring.duplicateProduct(TENANT, BRAND, lunch.productId(), ACTOR);

        var composite = store.composite();
        var copiedGroups = composite.comboGroupsForContainer(TENANT, BRAND, copy.defaultVariantId());
        assertThat(copiedGroups)
                .as("the copy is a combo: both groups, in the author's order, with the author's ranges")
                .extracting(g -> g.code() + ":" + g.minimumSelections() + "-" + g.maximumSelections() + ":"
                        + g.allowSameComponentMultipleTimes() + ":" + g.sortOrder())
                .containsExactly("MAIN:1-2:false:0", "DRINK:0-1:true:1");
        assertThat(copiedGroups)
                .extracting(g -> g.id())
                .as("new rows, not the original's")
                .doesNotContain(main.id(), drink.id());
        var copiedComponents = composite.componentsForGroups(
                TENANT, BRAND, copiedGroups.stream().map(g -> g.id()).toList());
        assertThat(copiedComponents)
                .extracting(c -> c.componentVariantId() + ":" + c.defaultQuantity())
                .containsExactlyInAnyOrder(burger.defaultVariantId() + ":2", cola.defaultVariantId() + ":1");
        assertThat(copiedComponents)
                .extracting(c -> c.id())
                .doesNotContainAnyElementsOf(
                        composite.componentsForGroups(TENANT, BRAND, List.of(main.id(), drink.id())).stream()
                                .map(c -> c.id())
                                .toList());
        assertThat(copiedComponents).allSatisfy(c -> assertThat(c.version()).isEqualTo(1));

        var headings = store.translations(TENANT, BRAND).stream()
                .filter(row -> row.entityType() == EntityType.COMBO_GROUP
                        && copiedGroups.get(0).id().equals(row.entityId()))
                .collect(java.util.stream.Collectors.toMap(
                        uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore.TranslationRow::locale,
                        uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore.TranslationRow::name));
        assertThat(headings)
                .as("the customer reads the heading in every language the author wrote it in")
                .containsEntry("uz", "Asosiy")
                .containsEntry("ru", "Основное");

        assertThat(composite.comboGroupsForContainer(TENANT, BRAND, lunch.defaultVariantId()))
                .as("the original keeps its own groups, untouched")
                .extracting(g -> g.id())
                .containsExactly(main.id(), drink.id());
        assertThat(composite
                        .componentsForGroups(TENANT, BRAND, List.of(main.id(), drink.id()))
                        .size())
                .isEqualTo(2);
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
        // Staff 9.3a: a field-level diff, not the old flat "previousStatus"/"status" pair.
        assertThat(jdbc.sql("""
                        SELECT change_document -> 'status' ->> 'before', change_document -> 'status' ->> 'after'
                          FROM audit.audit_events WHERE action_code = 'catalog.product.status_changed'
                        """)
                        .query((row, number) -> row.getString(1) + "->" + row.getString(2))
                        .single())
                .isEqualTo("ACTIVE->ARCHIVED");

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

    // ------------------------------------------- bulk classify: MERGE vs concurrency

    /**
     * A MERGE fills the value fields of a node; it must never write back the
     * constraint columns it read at the start of the batch. Another operator
     * marking the dish (which withdraws Payme from carts holding it) while a
     * backfill batch is in flight would otherwise be undone by the batch's
     * stale copy of the row.
     *
     * <p>The concurrent change is a real second connection: it updates the row
     * and holds its lock, the merge is started on another thread and left to run
     * into that lock, and only then does the first connection commit. That is
     * the interleaving a lost update needs, whatever order the merge reads and
     * writes in.
     */
    @Test
    @DisplayName("a MERGE that meets a concurrent marking and age-restriction change keeps that change, "
            + "and still fills the package code")
    void mergeKeepsAConstraintChangedConcurrently() throws Exception {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        ProductCreated wine = authoring.createProduct(
                TENANT,
                BRAND,
                catalogId,
                "WINE",
                "Sharob",
                null,
                LOCALE,
                "SKU-WINE",
                "PIECE",
                FiscalClassification.of("10101001001000000", null, 796, "Sharob"),
                ACTOR);
        UUID variantId = wine.defaultVariantId();
        List<BulkClassifyItem> items = List.of(new BulkClassifyItem(
                PriceableNode.variant(variantId), FiscalClassification.of(null, "1234567", null, null)));

        List<BulkClassifyOutcome> outcomes;
        try (Connection other = db.dataSource().getConnection()) {
            other.setAutoCommit(false);
            try (var statement = other.createStatement()) {
                statement.executeUpdate("""
                        UPDATE catalog.fiscal_classifications
                        SET marking_required = true, marking_scheme = 'DATA_MATRIX', excisable = true,
                            alcohol_by_volume_bp = 1200, age_restriction_years = 21
                        WHERE variant_id = '%s'
                        """.formatted(variantId));
            }
            CompletableFuture<List<BulkClassifyOutcome>> merge = CompletableFuture.supplyAsync(
                    () -> authoring.bulkClassify(TENANT, BRAND, items, BulkClassifyMode.MERGE, ACTOR, ACTOR_SUBJECT));
            awaitSomeoneWaitingOnARowLock(Duration.ofSeconds(5));
            other.commit();
            outcomes = merge.get(30, TimeUnit.SECONDS);
        }

        assertThat(outcomes)
                .singleElement()
                .satisfies(o -> assertThat(o.status()).isEqualTo(BulkClassifyStatus.CLASSIFIED));
        Map<String, Object> row = jdbc.sql("""
                SELECT mxik_code, package_code, marking_required, marking_scheme, excisable,
                       alcohol_by_volume_bp, age_restriction_years
                FROM catalog.fiscal_classifications WHERE variant_id = :id
                """).param("id", variantId).query().singleRow();
        assertThat(row.get("package_code")).as("the merge still fills the gap").isEqualTo("1234567");
        assertThat(row.get("mxik_code")).isEqualTo("10101001001000000");
        assertThat(row.get("marking_required"))
                .as("the concurrent marking survives")
                .isEqualTo(true);
        assertThat(row.get("marking_scheme")).isEqualTo("DATA_MATRIX");
        assertThat(row.get("excisable")).isEqualTo(true);
        assertThat(row.get("alcohol_by_volume_bp")).isEqualTo(1200);
        assertThat(row.get("age_restriction_years")).isEqualTo(21);
    }

    /**
     * A MERGE fills gaps. An item that supplies a different value for a field the
     * row already holds is reported {@code CONFLICT} and writes nothing for that
     * node, so a pasted column that landed one row too low cannot overwrite a
     * correct code, and the rest of the batch is still applied.
     */
    @Test
    @DisplayName("a MERGE never replaces a code the row already holds: a different one is a CONFLICT, "
            + "nothing is written for that node, and the rest of the batch is still applied")
    void mergeDoesNotReplaceAStoredCode() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        UUID held = authoring
                .createProduct(
                        TENANT,
                        BRAND,
                        catalogId,
                        "WINE",
                        "Sharob",
                        null,
                        LOCALE,
                        "SKU-WINE",
                        "PIECE",
                        FiscalClassification.of("10101001001000000", null, 796, "Sharob"),
                        ACTOR)
                .defaultVariantId();
        UUID bare = authoring
                .createProduct(
                        TENANT,
                        BRAND,
                        catalogId,
                        "TEA",
                        "Choy",
                        null,
                        LOCALE,
                        "SKU-TEA",
                        "PIECE",
                        FiscalClassification.unclassified(),
                        ACTOR)
                .defaultVariantId();
        Integer versionBefore = jdbc.sql("SELECT version FROM catalog.fiscal_classifications WHERE variant_id = :id")
                .param("id", held)
                .query(Integer.class)
                .single();

        List<BulkClassifyOutcome> outcomes = authoring.bulkClassify(
                TENANT,
                BRAND,
                List.of(
                        new BulkClassifyItem(
                                PriceableNode.variant(held),
                                FiscalClassification.of("20202002002000000", "1234567", null, null)),
                        new BulkClassifyItem(
                                PriceableNode.variant(bare),
                                FiscalClassification.of("20202002002000000", "1234567", null, null))),
                BulkClassifyMode.MERGE,
                ACTOR,
                ACTOR_SUBJECT);

        assertThat(outcomes)
                .extracting(BulkClassifyOutcome::status)
                .containsExactly(BulkClassifyStatus.CONFLICT, BulkClassifyStatus.CLASSIFIED);
        Map<String, Object> row = jdbc.sql("""
                SELECT mxik_code, package_code, version FROM catalog.fiscal_classifications WHERE variant_id = :id
                """).param("id", held).query().singleRow();
        assertThat(row.get("mxik_code")).as("the stored code survives").isEqualTo("10101001001000000");
        assertThat(row.get("package_code"))
                .as("nothing is written for a node that conflicts, not even its gap")
                .isNull();
        assertThat(row.get("version")).isEqualTo(versionBefore);
        assertThat(jdbc.sql("SELECT mxik_code FROM catalog.fiscal_classifications WHERE variant_id = :id")
                        .param("id", bare)
                        .query(String.class)
                        .single())
                .isEqualTo("20202002002000000");
    }

    /**
     * Two operators fill the same gap: the second one's merge runs into the first
     * one's uncommitted write, waits, and then must see the first one's code
     * rather than overwrite it.
     */
    @Test
    @DisplayName("a MERGE that meets a code another operator fills concurrently reports a CONFLICT "
            + "instead of overwriting it")
    void mergeDoesNotOverwriteACodeFilledConcurrently() throws Exception {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        UUID variantId = authoring
                .createProduct(
                        TENANT,
                        BRAND,
                        catalogId,
                        "WINE",
                        "Sharob",
                        null,
                        LOCALE,
                        "SKU-WINE",
                        "PIECE",
                        FiscalClassification.of(null, "1234567", 796, "Sharob"),
                        ACTOR)
                .defaultVariantId();
        List<BulkClassifyItem> items = List.of(new BulkClassifyItem(
                PriceableNode.variant(variantId), FiscalClassification.of("20202002002000000", null, null, null)));

        List<BulkClassifyOutcome> outcomes;
        try (Connection other = db.dataSource().getConnection()) {
            other.setAutoCommit(false);
            try (var statement = other.createStatement()) {
                statement.executeUpdate("""
                        UPDATE catalog.fiscal_classifications
                        SET mxik_code = '10101001001000000'
                        WHERE variant_id = '%s'
                        """.formatted(variantId));
            }
            CompletableFuture<List<BulkClassifyOutcome>> merge = CompletableFuture.supplyAsync(
                    () -> authoring.bulkClassify(TENANT, BRAND, items, BulkClassifyMode.MERGE, ACTOR, ACTOR_SUBJECT));
            awaitSomeoneWaitingOnARowLock(Duration.ofSeconds(5));
            other.commit();
            outcomes = merge.get(30, TimeUnit.SECONDS);
        }

        assertThat(outcomes)
                .singleElement()
                .satisfies(o -> assertThat(o.status()).isEqualTo(BulkClassifyStatus.CONFLICT));
        assertThat(jdbc.sql("SELECT mxik_code FROM catalog.fiscal_classifications WHERE variant_id = :id")
                        .param("id", variantId)
                        .query(String.class)
                        .single())
                .as("the code the other operator filled first stays")
                .isEqualTo("10101001001000000");
    }

    @Test
    @DisplayName("a MERGE that adds nothing to what the row holds is UNCHANGED and does not touch its version")
    void mergeThatAddsNothingIsUnchangedAndWritesNothing() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy menyu", LOCALE);
        ProductCreated plov = authoring.createProduct(
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
        UUID variantId = plov.defaultVariantId();
        Integer versionBefore = jdbc.sql("SELECT version FROM catalog.fiscal_classifications WHERE variant_id = :id")
                .param("id", variantId)
                .query(Integer.class)
                .single();

        List<BulkClassifyOutcome> outcomes = authoring.bulkClassify(
                TENANT,
                BRAND,
                List.of(new BulkClassifyItem(
                        PriceableNode.variant(variantId),
                        FiscalClassification.of("10101001001000000", "1", null, null))),
                BulkClassifyMode.MERGE,
                ACTOR,
                ACTOR_SUBJECT);

        assertThat(outcomes)
                .singleElement()
                .satisfies(o -> assertThat(o.status()).isEqualTo(BulkClassifyStatus.UNCHANGED));
        assertThat(jdbc.sql("SELECT version FROM catalog.fiscal_classifications WHERE variant_id = :id")
                        .param("id", variantId)
                        .query(Integer.class)
                        .single())
                .isEqualTo(versionBefore);
    }

    /** Blocks until some backend is waiting on a row lock, so the test knows the merge has reached its write. */
    private void awaitSomeoneWaitingOnARowLock(Duration atMost) throws InterruptedException, SQLException {
        long deadline = System.nanoTime() + atMost.toNanos();
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbc.sql("""
                    SELECT count(*)::int FROM pg_stat_activity
                    WHERE datname = current_database() AND wait_event_type = 'Lock'
                    """).query(Integer.class).single();
            if (waiting != null && waiting > 0) {
                return;
            }
            Thread.sleep(25);
        }
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
