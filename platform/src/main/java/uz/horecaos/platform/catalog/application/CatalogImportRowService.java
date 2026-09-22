package uz.horecaos.platform.catalog.application;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.catalog.api.CatalogImportPricingPort;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Category;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Product;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Status;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Variant;
import uz.horecaos.platform.catalog.domain.FiscalClassification;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.media.api.MediaAssetId;
import uz.horecaos.platform.media.api.MediaAssetIngestion;

/**
 * One row of a catalog CSV/Excel import, planned and — on {@code apply} —
 * written, in its own transaction (row 4.5b).
 *
 * <p>Its own {@code @Service} bean rather than a method on {@link
 * CatalogImportService}, for the identical reason {@code
 * CustomerCsvImportRowService}'s own doc gives: {@code @Transactional} only
 * takes effect through Spring's proxy when the caller invokes a different
 * bean, and the run's own orchestrator loops over every row without letting
 * one row's failure roll back the rows already committed before it.
 *
 * <p><b>One row authors one product and exactly that product's default
 * variant.</b> A product's other variants (sizes, portions) are out of scope
 * for this wave's row shape — {@link CatalogImportRow} carries a single
 * {@code variant_sku}, not a list — the same "products, variants, prices,
 * categories, availability" the brief names, narrowed to what a flat CSV row
 * can say about one product without inventing a second identity column this
 * wave has no template design for. A multi-variant product must still be
 * authored, or completed, in the product editor.
 *
 * <p><b>Every field is compared, not merely re-applied</b>, which is what
 * makes {@code SKIPPED} a real, testable outcome rather than a name for
 * "updated but nothing to report": a second import of an unchanged file
 * resolves every row to {@code SKIPPED} and writes nothing, because {@link
 * #diff} finds nothing to write — the property {@code changed} exists to
 * answer.
 *
 * <p><b>The image fetch happens only on {@code apply}, never on a dry
 * run.</b> Fetching is a real network call and — on success — a real,
 * permanently stored {@code media.assets} row; performing either during a
 * preview that promises to write nothing would break that promise. A dry
 * run instead treats a syntactically non-blank {@code image_url} as evidence
 * the row would change, without proving the fetch would succeed; {@link
 * CatalogImportRowErrorReason#IMAGE_FETCH_FAILED} can therefore only appear
 * in an {@code apply} run's report, never a dry run's.
 */
@Service
public class CatalogImportRowService {

    private static final String IMAGE_ROLE = "PRIMARY";

    private final JdbcCatalogStore store;
    private final CatalogAuthoringService authoring;
    private final CatalogQueryService query;
    private final CatalogImportPricingPort pricing;
    private final MediaAssetIngestion media;
    private final String defaultLocale;

    public CatalogImportRowService(
            JdbcCatalogStore store,
            CatalogAuthoringService authoring,
            CatalogQueryService query,
            CatalogImportPricingPort pricing,
            MediaAssetIngestion media,
            @Value("${horecaos.catalog.default-locale:uz}") String defaultLocale) {
        this.store = store;
        this.authoring = authoring;
        this.query = query;
        this.pricing = pricing;
        this.media = media;
        this.defaultLocale = defaultLocale;
    }

    @Transactional
    public CatalogImportRowOutcome process(
            UUID tenantId, UUID brandId, UUID catalogId, CatalogImportRow row, boolean dryRun, @Nullable UUID actorId) {

        ParsedFields parsed = ParsedFields.parse(row);
        if (parsed instanceof ParsedFields.Invalid invalid) {
            return CatalogImportRowOutcome.error(invalid.reason());
        }
        ParsedFields.Ok fields = (ParsedFields.Ok) parsed;

        Optional<Product> existing = store.productByCode(tenantId, brandId, fields.productCode());
        if (existing.isEmpty()) {
            return create(tenantId, brandId, catalogId, fields, dryRun, actorId);
        }
        return update(tenantId, brandId, catalogId, existing.get(), fields, dryRun, actorId);
    }

    private CatalogImportRowOutcome create(
            UUID tenantId,
            UUID brandId,
            UUID catalogId,
            ParsedFields.Ok fields,
            boolean dryRun,
            @Nullable UUID actorId) {

        if (dryRun) {
            // Nothing was actually created to name -- the identical rule
            // CustomerCsvImportRowOutcome's own doc states for CREATED_CUSTOMER
            // on a dry run.
            return CatalogImportRowOutcome.created(null, null);
        }

        // SKU uniqueness is checked before any write, so a collision is a
        // deliberate row-level ERROR rather than a caught constraint
        // violation that would fail the whole run -- see this class's own doc
        // on why a foreseeable business condition never reaches that path.
        String sku = fields.sku();
        if (sku != null && variantIdBySku(tenantId, brandId, sku).isPresent()) {
            return CatalogImportRowOutcome.error(CatalogImportRowErrorReason.DUPLICATE_SKU);
        }

        MediaAssetId imageAsset = null;
        String imageUrl = fields.imageUrl();
        if (imageUrl != null) {
            Optional<MediaAssetId> fetched = fetchImage(tenantId, brandId, imageUrl, actorId);
            if (fetched.isEmpty()) {
                return CatalogImportRowOutcome.error(CatalogImportRowErrorReason.IMAGE_FETCH_FAILED);
            }
            imageAsset = fetched.get();
        }

        CatalogAuthoringService.ProductCreated created = authoring.createProduct(
                tenantId,
                brandId,
                catalogId,
                fields.productCode(),
                fields.productName(),
                fields.description(),
                defaultLocale,
                sku,
                fields.unitCode(),
                FiscalClassification.unclassified(),
                actorId);

        if (fields.status() != Status.ACTIVE) {
            authoring.setProductStatus(tenantId, brandId, created.productId(), fields.status());
        }
        String categoryCode = fields.categoryCode();
        if (categoryCode != null) {
            UUID categoryId = categoryFor(tenantId, brandId, catalogId, categoryCode, fields.categoryName());
            authoring.placeProductInCategory(tenantId, brandId, categoryId, created.productId(), 0);
        }
        if (fields.priceAmountMinor() != null) {
            setPriceOrThrow(tenantId, brandId, created.defaultVariantId(), fields);
        }
        if (imageAsset != null) {
            authoring.attachMedia(
                    tenantId, brandId, EntityType.PRODUCT, created.productId(), imageAsset, IMAGE_ROLE, 0);
        }

        return CatalogImportRowOutcome.created(created.productId(), created.defaultVariantId());
    }

    private CatalogImportRowOutcome update(
            UUID tenantId,
            UUID brandId,
            UUID catalogId,
            Product product,
            ParsedFields.Ok fields,
            boolean dryRun,
            @Nullable UUID actorId) {

        List<Variant> variants = store.variantsForProduct(tenantId, brandId, product.id());
        Variant defaultVariant = variants.stream()
                .filter(Variant::isDefault)
                .findFirst()
                .orElseThrow(
                        () -> new IllegalStateException("Product %s has no default variant".formatted(product.id())));

        Diff diff = diff(tenantId, brandId, catalogId, product, defaultVariant, fields);
        String imageUrl = fields.imageUrl();
        boolean hasImage = imageUrl != null;

        if (dryRun) {
            return diff.changed() || hasImage
                    ? CatalogImportRowOutcome.updated(product.id(), defaultVariant.id())
                    : CatalogImportRowOutcome.skipped(product.id(), defaultVariant.id());
        }

        if (!diff.changed() && !hasImage) {
            return CatalogImportRowOutcome.skipped(product.id(), defaultVariant.id());
        }

        // SKU uniqueness against every OTHER product's variant, before any write.
        String sku = fields.sku();
        if (diff.skuChanged() && sku != null) {
            Optional<UUID> skuOwner = variantIdBySku(tenantId, brandId, sku);
            if (skuOwner.isPresent() && !skuOwner.get().equals(defaultVariant.id())) {
                return CatalogImportRowOutcome.error(CatalogImportRowErrorReason.DUPLICATE_SKU);
            }
        }

        MediaAssetId imageAsset = null;
        if (imageUrl != null) {
            Optional<MediaAssetId> fetched = fetchImage(tenantId, brandId, imageUrl, actorId);
            if (fetched.isEmpty()) {
                return CatalogImportRowOutcome.error(CatalogImportRowErrorReason.IMAGE_FETCH_FAILED);
            }
            imageAsset = fetched.get();
        }

        if (diff.nameOrDescriptionChanged()) {
            authoring.translate(
                    tenantId,
                    brandId,
                    EntityType.PRODUCT,
                    product.id(),
                    defaultLocale,
                    fields.productName(),
                    fields.description());
        }
        if (diff.skuChanged() || diff.unitChanged() || diff.variantStatusChanged()) {
            authoring.updateVariant(
                    tenantId, brandId, product.id(), defaultVariant.id(), sku, fields.unitCode(), fields.status());
        }
        if (diff.productStatusChanged()) {
            authoring.setProductStatus(tenantId, brandId, product.id(), fields.status());
        }
        String categoryCode = fields.categoryCode();
        if (diff.categoryChanged() && categoryCode != null) {
            UUID categoryId = categoryFor(tenantId, brandId, catalogId, categoryCode, fields.categoryName());
            authoring.placeProductInCategory(tenantId, brandId, categoryId, product.id(), 0);
        }
        if (diff.priceChanged() && fields.priceAmountMinor() != null) {
            setPriceOrThrow(tenantId, brandId, defaultVariant.id(), fields);
        }
        if (imageAsset != null) {
            for (var relation :
                    query.productDetail(tenantId, brandId, product.id()).media()) {
                if (IMAGE_ROLE.equals(relation.role())) {
                    authoring.detachMedia(
                            tenantId,
                            brandId,
                            EntityType.PRODUCT,
                            product.id(),
                            new MediaAssetId(relation.mediaAssetId()),
                            IMAGE_ROLE,
                            relation.channelCode());
                }
            }
            authoring.attachMedia(tenantId, brandId, EntityType.PRODUCT, product.id(), imageAsset, IMAGE_ROLE, 0);
        }

        return CatalogImportRowOutcome.updated(product.id(), defaultVariant.id());
    }

    /** Every field this row states, compared against the brand's current catalog. Never writes anything. */
    private Diff diff(
            UUID tenantId,
            UUID brandId,
            UUID catalogId,
            Product product,
            Variant defaultVariant,
            ParsedFields.Ok fields) {

        var translations = query.productDetail(tenantId, brandId, product.id()).translations();
        var current = translations.get(defaultLocale);
        boolean nameOrDescriptionChanged = current == null
                || !fields.productName().equals(current.name())
                || !Objects.equals(fields.description(), current.description());

        boolean skuChanged = !Objects.equals(fields.sku(), defaultVariant.sku());
        boolean unitChanged = !fields.unitCode().equals(defaultVariant.unitCode());
        boolean variantStatusChanged = fields.status() != defaultVariant.status();
        boolean productStatusChanged = fields.status() != product.status();

        boolean categoryChanged = false;
        String categoryCode = fields.categoryCode();
        if (categoryCode != null) {
            Optional<Category> category = store.categoryByCode(tenantId, brandId, catalogId, categoryCode);
            categoryChanged = category.isEmpty()
                    || !store.productInCategory(
                            tenantId, brandId, category.get().id(), product.id());
        }

        boolean priceChanged;
        Long priceAmountMinor = fields.priceAmountMinor();
        if (priceAmountMinor == null) {
            priceChanged = false;
        } else {
            Optional<CatalogImportPricingPort.PricedAmount> currentPrice =
                    pricing.currentPrice(tenantId, brandId, defaultVariant.id());
            priceChanged = currentPrice.isEmpty()
                    || currentPrice.get().amountMinor() != priceAmountMinor
                    || !currentPrice.get().currency().equals(fields.currency());
        }

        return new Diff(
                nameOrDescriptionChanged,
                skuChanged,
                unitChanged,
                variantStatusChanged,
                productStatusChanged,
                categoryChanged,
                priceChanged);
    }

    private UUID categoryFor(
            UUID tenantId, UUID brandId, UUID catalogId, String categoryCode, @Nullable String categoryName) {
        return store.categoryByCode(tenantId, brandId, catalogId, categoryCode)
                .map(Category::id)
                .orElseGet(() -> authoring.createCategory(
                        tenantId,
                        brandId,
                        catalogId,
                        null,
                        categoryCode,
                        categoryName == null ? categoryCode : categoryName,
                        defaultLocale,
                        0));
    }

    private void setPriceOrThrow(UUID tenantId, UUID brandId, UUID variantId, ParsedFields.Ok fields) {
        try {
            pricing.setVariantPrice(
                    tenantId,
                    brandId,
                    variantId,
                    Objects.requireNonNull(fields.priceAmountMinor()),
                    Objects.requireNonNull(fields.currency()));
        } catch (CatalogImportPricingPort.PriceRefusedException refused) {
            throw new RowPriceRefusedException(refused);
        }
    }

    private Optional<MediaAssetId> fetchImage(UUID tenantId, UUID brandId, String imageUrl, @Nullable UUID actorId) {
        URI uri;
        try {
            uri = new URI(imageUrl);
        } catch (URISyntaxException malformed) {
            return Optional.empty();
        }
        MediaAssetIngestion.IngestOutcome outcome =
                media.ingestFromUrl(tenantId, MediaAssetIngestion.OwnerScope.BRAND, brandId, true, uri, actorId);
        return outcome.accepted() ? Optional.ofNullable(outcome.assetId()) : Optional.empty();
    }

    private Optional<UUID> variantIdBySku(UUID tenantId, UUID brandId, String sku) {
        // No dedicated indexed lookup exists for this narrow a check yet
        // (row 4.5b is its first caller); catalog.variants carries a
        // brand-unique index on sku (uq_variant_sku), so this is a bounded,
        // constant-time-in-practice existence check rather than a scan a
        // caller should reach for anywhere else.
        return store.variantBySku(tenantId, brandId, sku).map(Variant::id);
    }

    /**
     * Row-shaped fields, typed and validated — or the single reason none of
     * that could happen. A sealed pair rather than one record with nullable
     * fields plus a nullable error, so {@link Ok}'s fields that are always
     * present once parsing succeeds ({@code productCode}, {@code
     * productName}) are typed as such rather than merely documented as such.
     */
    private sealed interface ParsedFields {

        record Ok(
                String productCode,
                String productName,
                @Nullable String description,
                @Nullable String sku,
                String unitCode,
                @Nullable Long priceAmountMinor,
                @Nullable String currency,
                Status status,
                @Nullable String categoryCode,
                @Nullable String categoryName,
                @Nullable String imageUrl)
                implements ParsedFields {}

        record Invalid(CatalogImportRowErrorReason reason) implements ParsedFields {}

        static ParsedFields parse(CatalogImportRow row) {
            String productCode = blank(row.productCode());
            if (productCode == null) {
                return new Invalid(CatalogImportRowErrorReason.MISSING_PRODUCT_CODE);
            }
            String productName = blank(row.productName());
            if (productName == null) {
                return new Invalid(CatalogImportRowErrorReason.MISSING_PRODUCT_NAME);
            }

            Status status = Status.ACTIVE;
            String rawStatus = blank(row.status());
            if (rawStatus != null) {
                try {
                    status = Status.valueOf(rawStatus.toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException notAStatus) {
                    return new Invalid(CatalogImportRowErrorReason.INVALID_STATUS);
                }
            }

            String rawAmount = blank(row.priceAmountMinor());
            String rawCurrency = blank(row.priceCurrency());
            if ((rawAmount == null) != (rawCurrency == null)) {
                return new Invalid(CatalogImportRowErrorReason.INVALID_PRICE);
            }
            Long amount = null;
            String currency = null;
            if (rawAmount != null) {
                try {
                    amount = Long.parseLong(rawAmount);
                } catch (NumberFormatException notANumber) {
                    return new Invalid(CatalogImportRowErrorReason.INVALID_PRICE);
                }
                if (amount < 0) {
                    return new Invalid(CatalogImportRowErrorReason.INVALID_PRICE);
                }
                currency = rawCurrency.toUpperCase(Locale.ROOT);
                if (!currency.matches("[A-Z]{3}")) {
                    return new Invalid(CatalogImportRowErrorReason.INVALID_PRICE);
                }
            }

            String unitCode = blank(row.unitCode());

            return new Ok(
                    productCode,
                    productName,
                    blank(row.productDescription()),
                    blank(row.variantSku()),
                    unitCode == null ? "PIECE" : unitCode.toUpperCase(Locale.ROOT),
                    amount,
                    currency,
                    status,
                    blank(row.categoryCode()),
                    blank(row.categoryName()),
                    blank(row.imageUrl()));
        }

        private static @Nullable String blank(@Nullable String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.strip();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }

    private record Diff(
            boolean nameOrDescriptionChanged,
            boolean skuChanged,
            boolean unitChanged,
            boolean variantStatusChanged,
            boolean productStatusChanged,
            boolean categoryChanged,
            boolean priceChanged) {

        boolean changed() {
            return nameOrDescriptionChanged
                    || skuChanged
                    || unitChanged
                    || variantStatusChanged
                    || productStatusChanged
                    || categoryChanged
                    || priceChanged;
        }
    }

    /** Pricing refused a row's price permanently -- caught by {@link CatalogImportService} and reported as {@link CatalogImportRowErrorReason#PRICE_REFUSED}. */
    static final class RowPriceRefusedException extends RuntimeException {
        RowPriceRefusedException(CatalogImportPricingPort.PriceRefusedException cause) {
            super(cause.getMessage(), cause);
        }
    }
}
