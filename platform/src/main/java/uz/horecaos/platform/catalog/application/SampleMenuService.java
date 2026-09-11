package uz.horecaos.platform.catalog.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.catalog.api.SampleMenuPort;
import uz.horecaos.platform.catalog.application.SampleMenuContent.SampleCategory;
import uz.horecaos.platform.catalog.application.SampleMenuContent.SampleProduct;
import uz.horecaos.platform.catalog.application.SampleMenuContent.SampleText;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Category;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.OfferingStatus;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Product;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Variant;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.domain.PublicationStatus;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;

/**
 * Builds and publishes the onboarding sample menu (ADR 0099).
 *
 * <p>Nothing here is new authoring machinery: every write goes through {@link
 * CatalogAuthoringService} and {@link CatalogPublicationService}, the same two
 * services the operations console calls, so a sample menu is exactly the menu a
 * person would have typed and is subject to the same validation.
 *
 * <p>Idempotency is by code, not by a remembered id. Every sample entity has a
 * {@code SAMPLE-} code that is unique within the brand by the schema's own
 * constraints ({@code uq_catalog_code}, {@code uq_product_code}, {@code
 * uq_variant_sku}), so a retried step reads what is there and creates only what
 * is missing. That is what makes a step that dies halfway — after the catalog,
 * before the tenth product — safe to run again rather than a source of
 * duplicates.
 *
 * <p>Deliberately not classified fiscally (ADR 0038). A sample dish has no real
 * ИКПУ/MXIK, and inventing one would put a wrong code on a receipt the moment a
 * tenant sold from the sample; {@code FiscalClassification.unclassified()}
 * writes no row at all, which is how the coverage report already reads "nobody
 * has classified this".
 */
@Service
public class SampleMenuService implements SampleMenuPort {

    private static final Logger log = LoggerFactory.getLogger(SampleMenuService.class);

    /** Pickup and delivery: the sample must be orderable however the location sells. */
    private static final List<String> FULFILLMENT_MODES = List.of("PICKUP", "DELIVERY");

    private final JdbcCatalogStore store;
    private final CatalogAuthoringService authoring;
    private final CatalogPublicationService publications;

    /**
     * The locale {@link CatalogValidator} requires a name in, read from the same
     * property {@code CatalogSnapshotLoader} and {@code CatalogQueryService}
     * read. Authoring the sample in any other locale would publish-reject it
     * with {@code MISSING_TRANSLATION}.
     */
    private final String defaultLocale;

    public SampleMenuService(
            JdbcCatalogStore store,
            CatalogAuthoringService authoring,
            CatalogPublicationService publications,
            @Value("${horecaos.catalog.default-locale:uz}") String defaultLocale) {
        this.store = store;
        this.authoring = authoring;
        this.publications = publications;
        this.defaultLocale = defaultLocale;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> sampleCatalogId(UUID tenantId, UUID brandId) {
        return store.catalogsForBrand(tenantId, brandId).stream()
                .filter(row -> SAMPLE_CATALOG_CODE.equals(row.code()))
                .map(JdbcCatalogStore.CatalogRow::id)
                .findFirst();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> publishedCatalogId(UUID tenantId, UUID brandId, String channel) {
        return store.findActivePublicationId(tenantId, brandId, channel)
                .flatMap(publicationId -> store.findPublication(tenantId, brandId, publicationId))
                .map(JdbcCatalogStore.PublicationRow::catalogId);
    }

    @Override
    @Transactional
    public SampleMenu installSample(UUID tenantId, UUID brandId, List<UUID> locationIds) {
        String locale = defaultLocale;
        Optional<UUID> existing = sampleCatalogId(tenantId, brandId);
        boolean created = existing.isEmpty();
        UUID catalogId = existing.orElseGet(() -> authoring.createCatalog(
                tenantId, brandId, SAMPLE_CATALOG_CODE, SampleMenuContent.catalogName(locale), locale));

        Map<String, UUID> categories = ensureCategories(tenantId, brandId, catalogId, locale);
        Map<String, UUID> variantsBySku = ensureProducts(tenantId, brandId, catalogId, locale, categories);

        // Re-asserted on every attempt rather than only on the attempt that
        // created the product: a location added to the brand between two runs
        // must start offering the sample too, and upsertOffering is a natural-key
        // upsert, so re-asserting costs one statement and cannot duplicate.
        for (UUID locationId : locationIds) {
            for (UUID variantId : variantsBySku.values()) {
                authoring.setOffering(
                        tenantId, brandId, locationId, variantId, OfferingStatus.AVAILABLE, FULFILLMENT_MODES);
            }
        }

        List<SampleVariant> variants = new ArrayList<>();
        for (SampleProduct product : SampleMenuContent.PRODUCTS) {
            UUID variantId = variantsBySku.get(product.sku());
            if (variantId != null) {
                variants.add(new SampleVariant(variantId, product.sku(), product.amountMinor()));
            }
        }

        log.info(
                "Sample menu {} for brand {}: {} products, {} categories, {} location(s), created={}",
                catalogId,
                brandId,
                variants.size(),
                categories.size(),
                locationIds.size(),
                created);
        return new SampleMenu(
                catalogId, SAMPLE_CATALOG_CODE, categories.size(), variants.size(), List.copyOf(variants), created);
    }

    @Override
    @Transactional
    public SamplePublication publishSample(UUID tenantId, UUID brandId, UUID catalogId, String channel) {
        Optional<UUID> live = store.findActivePublicationId(tenantId, brandId, channel);
        if (live.isPresent()) {
            // Already published, by this step's own previous attempt or by a
            // person. Republishing identical content would retire a good
            // publication and change the ETag every customer is holding.
            return new SamplePublication(live.get(), false, List.of());
        }

        CatalogPublicationService.PublicationResult result =
                publications.publish(tenantId, brandId, catalogId, channel, null);

        if (result.status() != PublicationStatus.PUBLISHED) {
            // Codes only. A blocker's own detail names entities and, for a
            // translation blocker, the menu text itself; this string ends up in
            // an onboarding step's last_error.
            List<String> blockers = result.report().blockers().stream()
                    .map(finding -> finding.code())
                    .distinct()
                    .toList();
            return new SamplePublication(result.publicationId(), false, blockers);
        }
        return new SamplePublication(result.publicationId(), true, List.of());
    }

    /** Every sample category, created where missing, keyed by code. */
    private Map<String, UUID> ensureCategories(UUID tenantId, UUID brandId, UUID catalogId, String locale) {
        Map<String, UUID> byCode = new HashMap<>();
        for (Category category : store.categoriesInCatalog(tenantId, brandId, catalogId)) {
            byCode.put(category.code(), category.id());
        }

        for (SampleCategory category : SampleMenuContent.CATEGORIES) {
            if (byCode.containsKey(category.code())) {
                continue;
            }
            UUID categoryId = authoring.createCategory(
                    tenantId,
                    brandId,
                    catalogId,
                    null,
                    category.code(),
                    category.name(locale),
                    locale,
                    category.sortOrder());
            translateOtherLocales(tenantId, brandId, EntityType.CATEGORY, categoryId, locale, category);
            byCode.put(category.code(), categoryId);
        }
        return byCode;
    }

    /** Every sample product with its default variant, created where missing, keyed by SKU. */
    private Map<String, UUID> ensureProducts(
            UUID tenantId, UUID brandId, UUID catalogId, String locale, Map<String, UUID> categories) {

        Map<String, UUID> productIdByCode = new HashMap<>();
        for (Product product : store.productsInCatalog(tenantId, brandId, catalogId)) {
            productIdByCode.put(product.code(), product.id());
        }
        Map<String, UUID> variantIdBySku = new HashMap<>();
        for (Variant variant : store.variantsInCatalog(tenantId, brandId, catalogId)) {
            if (variant.sku() != null) {
                variantIdBySku.put(variant.sku(), variant.id());
            }
        }

        for (SampleProduct product : SampleMenuContent.PRODUCTS) {
            if (variantIdBySku.containsKey(product.sku()) && productIdByCode.containsKey(product.code())) {
                continue;
            }
            SampleText text = product.text(locale);
            CatalogAuthoringService.ProductCreated created = authoring.createProduct(
                    tenantId,
                    brandId,
                    catalogId,
                    product.code(),
                    text.name(),
                    text.description(),
                    locale,
                    product.sku(),
                    product.unitCode(),
                    FiscalClassification.unclassified(),
                    null);
            translateOtherLocales(tenantId, brandId, EntityType.PRODUCT, created.productId(), locale, product);

            UUID categoryId = categories.get(product.categoryCode());
            if (categoryId != null) {
                authoring.placeProductInCategory(
                        tenantId, brandId, categoryId, created.productId(), product.sortOrder());
            }
            productIdByCode.put(product.code(), created.productId());
            variantIdBySku.put(product.sku(), created.defaultVariantId());
        }
        return variantIdBySku;
    }

    /**
     * Writes the sample's other two locales.
     *
     * <p>The authoring locale is already written by {@code createCategory} and
     * {@code createProduct}; a storefront asked for another one falls back to a
     * code without these.
     */
    private void translateOtherLocales(
            UUID tenantId, UUID brandId, EntityType type, UUID entityId, String authoringLocale, Object entity) {

        for (String locale : SampleMenuContent.locales()) {
            if (locale.equals(authoringLocale)) {
                continue;
            }
            if (entity instanceof SampleCategory category) {
                authoring.translate(tenantId, brandId, type, entityId, locale, category.name(locale), null);
            } else if (entity instanceof SampleProduct product) {
                SampleText text = Objects.requireNonNull(product.text(locale));
                authoring.translate(tenantId, brandId, type, entityId, locale, text.name(), text.description());
            }
        }
    }
}
