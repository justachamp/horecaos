package uz.horecaos.platform.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.Locale;
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
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Variant;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.support.CommercialDefaults;
import uz.horecaos.platform.support.TestDatabase;

/**
 * Wave P22's own additions to catalog authoring — the three defects the gap
 * map named on this screen: the variants tab could not be edited beyond the
 * price input, a product's status was read-only text, and there was no
 * {@code @DeleteMapping} anywhere in the catalog package. Plus the backend
 * half of {@code 4.2f}: a channel dimension on {@code catalog.media_relations}
 * and the detach path that finally lets a wrong upload be undone.
 *
 * <p>Same fixture shape as {@link CatalogQueryServiceTests}, built through the
 * real authoring path rather than seeded rows, so the property under test is
 * the actual write, not a fixture agreeing with itself.
 */
class CatalogEditorExtensionsTests {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID BRAND = UUID.randomUUID();
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
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for the catalog editor extension tests");
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
        insertTenantAndBrand(TENANT, BRAND, "editor-ext-tenant", "MAIN");

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

    // -------------------------------------------------------------- product status

    @Test
    @DisplayName("a product's status can be changed — read-only text until this wave")
    void productStatusCanBeChanged() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy", LOCALE);
        var product = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);

        boolean changed = authoring.setProductStatus(TENANT, BRAND, product.productId(), Status.ARCHIVED);

        assertThat(changed).isTrue();
        assertThat(query.productDetail(TENANT, BRAND, product.productId()).status())
                .isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("changing the status of a product this brand does not have reports false rather than throwing")
    void productStatusRefusesUnknownProduct() {
        assertThat(authoring.setProductStatus(TENANT, BRAND, UUID.randomUUID(), Status.ACTIVE))
                .isFalse();
    }

    // -------------------------------------------------------------- variants tab

    @Test
    @DisplayName("a variant's sku, unit and status can all be corrected — the tab was read-only apart from price")
    void variantCanBeUpdated() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy", LOCALE);
        var product = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);

        boolean updated =
                authoring.updateVariant(TENANT, BRAND, product.defaultVariantId(), "SKU-PLOV-2", "KG", Status.ARCHIVED);

        assertThat(updated).isTrue();
        Variant variant = store.variantsForProduct(TENANT, BRAND, product.productId()).stream()
                .filter(v -> v.id().equals(product.defaultVariantId()))
                .findFirst()
                .orElseThrow();
        assertThat(variant.sku()).isEqualTo("SKU-PLOV-2");
        assertThat(variant.unitCode()).isEqualTo("KG");
        assertThat(variant.status()).isEqualTo(Status.ARCHIVED);
    }

    @Test
    @DisplayName("promoting a second variant to default demotes the first — ux_variant_single_default holds")
    void settingADefaultVariantDemotesTheOldOne() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy", LOCALE);
        var product = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        UUID secondVariant = authoring.addVariant(
                TENANT, BRAND, product.productId(), "SKU-PLOV-L", "PIECE", "Katta", LOCALE, 1, UNCLASSIFIED, ACTOR);

        boolean promoted = authoring.setDefaultVariant(TENANT, BRAND, product.productId(), secondVariant);

        assertThat(promoted).isTrue();
        var variants = store.variantsForProduct(TENANT, BRAND, product.productId());
        assertThat(variants)
                .filteredOn(v -> v.id().equals(secondVariant))
                .singleElement()
                .satisfies(v -> assertThat(v.isDefault()).isTrue());
        assertThat(variants)
                .filteredOn(v -> v.id().equals(product.defaultVariantId()))
                .singleElement()
                .satisfies(v -> assertThat(v.isDefault()).isFalse());
    }

    // -------------------------------------------------------------- membership removal

    @Test
    @DisplayName("a product can be removed from a category — no @DeleteMapping existed anywhere in the package")
    void productCanBeRemovedFromCategory() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy", LOCALE);
        UUID categoryId = authoring.createCategory(TENANT, BRAND, catalogId, null, "HOT", "Issiq", LOCALE, 0);
        var product = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        authoring.placeProductInCategory(TENANT, BRAND, categoryId, product.productId(), 0);

        boolean removed = authoring.removeProductFromCategory(TENANT, BRAND, categoryId, product.productId());

        assertThat(removed).isTrue();
        assertThat(query.productDetail(TENANT, BRAND, product.productId()).categoryIds())
                .isEmpty();
        // Idempotent: removing it again finds nothing, and does not throw.
        assertThat(authoring.removeProductFromCategory(TENANT, BRAND, categoryId, product.productId()))
                .isFalse();
    }

    @Test
    @DisplayName("a product can be removed from a catalog, leaving the product and its variants untouched")
    void productCanBeRemovedFromCatalog() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy", LOCALE);
        var product = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);

        boolean removed = authoring.removeProductFromCatalog(TENANT, BRAND, catalogId, product.productId());

        assertThat(removed).isTrue();
        assertThat(query.productDetail(TENANT, BRAND, product.productId()).catalogIds())
                .isEmpty();
        // The product itself survives — only the membership went.
        assertThat(store.productById(TENANT, BRAND, product.productId())).isPresent();
    }

    // -------------------------------------------------------------- media: channel dimension + detach

    @Test
    @DisplayName(
            "attaching the same asset and role under two channels keeps both — the channel dimension IA 4.2f needs")
    void mediaChannelDimensionKeepsBothOverrides() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy", LOCALE);
        var product = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        UUID asset = seedMediaAsset(TENANT, BRAND);

        authoring.attachMedia(
                TENANT, BRAND, EntityType.PRODUCT, product.productId(), new MediaAssetId(asset), "PRIMARY", 0);
        authoring.attachMedia(
                TENANT,
                BRAND,
                EntityType.PRODUCT,
                product.productId(),
                new MediaAssetId(asset),
                "PRIMARY",
                0,
                "YANDEX_EATS");

        var media = query.productDetail(TENANT, BRAND, product.productId()).media();
        assertThat(media).hasSize(2);
        assertThat(media)
                .extracting(CatalogQueryService.MediaRelation::channelCode)
                .containsExactlyInAnyOrder("ALL", "YANDEX_EATS");
    }

    @Test
    @DisplayName("detach removes exactly the one relation it names, leaving a same-asset override on another channel")
    void detachRemovesExactlyOneRelation() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy", LOCALE);
        var product = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        UUID asset = seedMediaAsset(TENANT, BRAND);
        authoring.attachMedia(
                TENANT, BRAND, EntityType.PRODUCT, product.productId(), new MediaAssetId(asset), "PRIMARY", 0);
        authoring.attachMedia(
                TENANT,
                BRAND,
                EntityType.PRODUCT,
                product.productId(),
                new MediaAssetId(asset),
                "PRIMARY",
                0,
                "YANDEX_EATS");

        boolean detached = authoring.detachMedia(
                TENANT, BRAND, EntityType.PRODUCT, product.productId(), new MediaAssetId(asset), "PRIMARY", "ALL");

        assertThat(detached).isTrue();
        var media = query.productDetail(TENANT, BRAND, product.productId()).media();
        assertThat(media)
                .singleElement()
                .satisfies(m -> assertThat(m.channelCode()).isEqualTo("YANDEX_EATS"));
    }

    @Test
    @DisplayName(
            "detaching a relation that is already gone reports false rather than throwing — a wrong upload was previously undoable nowhere")
    void detachIsIdempotent() {
        UUID catalogId = authoring.createCatalog(TENANT, BRAND, "MAIN", "Asosiy", LOCALE);
        var product = authoring.createProduct(
                TENANT, BRAND, catalogId, "PLOV", "Osh", null, LOCALE, "SKU-PLOV", "PIECE", UNCLASSIFIED, ACTOR);
        UUID asset = seedMediaAsset(TENANT, BRAND);

        assertThat(authoring.detachMedia(
                        TENANT,
                        BRAND,
                        EntityType.PRODUCT,
                        product.productId(),
                        new MediaAssetId(asset),
                        "PRIMARY",
                        "ALL"))
                .isFalse();
    }

    // -------------------------------------------------------------- fixture helpers

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
