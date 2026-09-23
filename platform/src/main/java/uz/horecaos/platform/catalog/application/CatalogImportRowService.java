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
 * answer. {@code image_url} is the one field {@code diff} itself does not
 * cover (its comparison needs the freshly fetched bytes, not just the row —
 * see the next paragraph): {@link #update} folds {@link
 * #imageDiffersFromCurrentPrimary}'s own answer into the same "anything to
 * write?" decision, so a row whose only column is an unchanged photo still
 * resolves to {@code SKIPPED} rather than reporting {@code UPDATED} on every
 * re-run.
 *
 * <p><b>The image fetch happens only on {@code apply}, never on a dry
 * run.</b> Fetching is a real network call and — on success — a real,
 * permanently stored {@code media.assets} row; performing either during a
 * preview that promises to write nothing would break that promise. A dry
 * run instead treats a syntactically non-blank {@code image_url} as evidence
 * the row would change, without proving the fetch would succeed; {@link
 * CatalogImportRowErrorReason#IMAGE_FETCH_FAILED} can therefore only appear
 * in an {@code apply} run's report, never a dry run's. An {@code apply} run
 * still cannot avoid the fetch itself for an unchanged image (this class
 * does not persist the URL a previous import used), so re-running an
 * image-bearing CSV is not free of outbound calls the way an unchanged
 * price or name row is — only of the duplicate {@code media.assets} row and
 * the misleading {@code UPDATED} report the fetch used to cost on top of
 * that.
 *
 * <p><b>Blank vs. explicit-clear, decided per column.</b> {@code status},
 * {@code unit_code} and {@code variant_sku} follow one rule on an {@code
 * update}: <i>a blank cell means "the CSV says nothing about this field",
 * never "clear it"</i> — the same rule {@code category_code} and {@code
 * price_amount_minor}/{@code price_currency} already followed. A corrective
 * re-import that only fills {@code product_code}/{@code product_name}/{@code
 * price_amount_minor} to fix a price must never re-activate an archived
 * product, reset its unit to the {@code PIECE} default, or null out its SKU
 * — {@link ParsedFields.Ok#status} and {@link ParsedFields.Ok#unitCode} are
 * therefore {@code null}, not defaulted, when the column is blank, and
 * {@link #diff} only flags a field as changed when the row actually stated a
 * value. <b>There is no way to explicitly clear a SKU (or reset the unit
 * code, or blank the status) through this CSV</b> — a blank cell can only
 * ever mean "unchanged" here, on {@code update}. Doing so is the product
 * editor's job, not this importer's; a merchant who needs to remove a SKU
 * clears it there. On {@code create}, by contrast, there is no existing row
 * for "unchanged" to mean anything against, so a blank {@code status}/{@code
 * unit_code} falls back to the ordinary new-product defaults ({@code ACTIVE}
 * / {@code PIECE}) exactly as before.
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

        // A blank status/unit_code cell means "leave unchanged" on an
        // update (see the guards in diff()/update() below), but there is no
        // existing row to leave unchanged when creating one -- a blank cell
        // here instead falls back to the ordinary new-product defaults.
        Status status = fields.status() == null ? Status.ACTIVE : fields.status();
        String unitCode = fields.unitCode() == null ? "PIECE" : fields.unitCode();

        CatalogAuthoringService.ProductCreated created = authoring.createProduct(
                tenantId,
                brandId,
                catalogId,
                fields.productCode(),
                fields.productName(),
                fields.description(),
                defaultLocale,
                sku,
                unitCode,
                FiscalClassification.unclassified(),
                actorId);

        if (status != Status.ACTIVE) {
            authoring.setProductStatus(tenantId, brandId, created.productId(), status);
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

        if (dryRun) {
            // A syntactically non-blank image_url is only ever evidence a
            // dry run can offer -- fetching (and so proving whether it is
            // actually the same file already attached) never happens here;
            // see this class's own class-level doc.
            return diff.changed() || imageUrl != null
                    ? CatalogImportRowOutcome.updated(product.id(), defaultVariant.id())
                    : CatalogImportRowOutcome.skipped(product.id(), defaultVariant.id());
        }

        if (!diff.changed() && imageUrl == null) {
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

        // The fetch itself cannot be skipped without persisting the URL a
        // previous import used (out of scope for this wave) -- but once
        // fetched, the freshly ingested asset's own verified checksum tells
        // whether it is actually the same file as the one already attached,
        // so an unchanged image_url still resolves this row to SKIPPED
        // (below) instead of always reporting UPDATED and growing
        // media.assets with an unreferenced duplicate on every re-run.
        MediaAssetId imageAsset = null;
        boolean imageChanged = false;
        if (imageUrl != null) {
            Optional<MediaAssetId> fetched = fetchImage(tenantId, brandId, imageUrl, actorId);
            if (fetched.isEmpty()) {
                return CatalogImportRowOutcome.error(CatalogImportRowErrorReason.IMAGE_FETCH_FAILED);
            }
            imageAsset = fetched.get();
            imageChanged = imageDiffersFromCurrentPrimary(tenantId, brandId, product.id(), imageAsset);
        }

        if (!diff.changed() && !imageChanged) {
            return CatalogImportRowOutcome.skipped(product.id(), defaultVariant.id());
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
            // JdbcCatalogStore#updateVariant sets sku/unit_code/status
            // unconditionally in one UPDATE, so a field the diff did not
            // flag (because the CSV cell was blank) must still be re-sent
            // as the variant's current value -- not fields.xxx(), which is
            // null/absent for exactly that "leave it alone" case.
            String effectiveSku = diff.skuChanged() ? sku : defaultVariant.sku();
            String effectiveUnitCode = fields.unitCode() != null ? fields.unitCode() : defaultVariant.unitCode();
            Status effectiveVariantStatus = fields.status() != null ? fields.status() : defaultVariant.status();
            authoring.updateVariant(
                    tenantId,
                    brandId,
                    product.id(),
                    defaultVariant.id(),
                    effectiveSku,
                    effectiveUnitCode,
                    effectiveVariantStatus);
        }
        if (diff.productStatusChanged()) {
            // fields.status() is guaranteed non-null here: productStatusChanged
            // can only be true when the CSV actually stated a status (see diff()).
            authoring.setProductStatus(tenantId, brandId, product.id(), Objects.requireNonNull(fields.status()));
        }
        String categoryCode = fields.categoryCode();
        if (diff.categoryChanged() && categoryCode != null) {
            UUID categoryId = categoryFor(tenantId, brandId, catalogId, categoryCode, fields.categoryName());
            authoring.placeProductInCategory(tenantId, brandId, categoryId, product.id(), 0);
        }
        if (diff.priceChanged() && fields.priceAmountMinor() != null) {
            setPriceOrThrow(tenantId, brandId, defaultVariant.id(), fields);
        }
        if (imageChanged) {
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
            authoring.attachMedia(
                    tenantId,
                    brandId,
                    EntityType.PRODUCT,
                    product.id(),
                    Objects.requireNonNull(imageAsset),
                    IMAGE_ROLE,
                    0);
        }

        return CatalogImportRowOutcome.updated(product.id(), defaultVariant.id());
    }

    /**
     * True when {@code candidate} is not the same content as the product's
     * currently attached {@code PRIMARY} media (or nothing is attached yet).
     * Compares the two assets' own verified checksums, never the URL or the
     * asset id -- {@code candidate} is always a freshly minted {@link
     * MediaAssetId} (ingestion never deduplicates), so two imports of the
     * same file are never the same id even though they are the same image.
     */
    private boolean imageDiffersFromCurrentPrimary(
            UUID tenantId, UUID brandId, UUID productId, MediaAssetId candidate) {
        Optional<String> candidateChecksum = media.checksumOf(tenantId, candidate);
        for (var relation : query.productDetail(tenantId, brandId, productId).media()) {
            if (IMAGE_ROLE.equals(relation.role())) {
                Optional<String> currentChecksum =
                        media.checksumOf(tenantId, new MediaAssetId(relation.mediaAssetId()));
                return currentChecksum.isEmpty() || !currentChecksum.equals(candidateChecksum);
            }
        }
        return true;
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

        // A blank sku/unit_code/status column parses to null (ParsedFields
        // never substitutes a default the way it used to) and means "the
        // CSV said nothing about this field", not "clear it" -- so each
        // guard below requires the column to have actually carried a value
        // before comparing it against the stored row. There is no way to
        // explicitly clear a SKU through this importer's CSV; that is a
        // deliberate limitation (see this class's own class-level doc) and
        // goes through the product editor instead.
        boolean skuChanged = fields.sku() != null && !fields.sku().equals(defaultVariant.sku());
        boolean unitChanged = fields.unitCode() != null && !fields.unitCode().equals(defaultVariant.unitCode());
        boolean variantStatusChanged = fields.status() != null && fields.status() != defaultVariant.status();
        boolean productStatusChanged = fields.status() != null && fields.status() != product.status();

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

        /**
         * @param unitCode null when the CSV cell was blank -- "leave
         *                 unchanged" on an update, "default to PIECE" on a
         *                 create; see {@link #update} and {@link #create}.
         *                 Never null once past {@link #parse}'s validation
         *                 for a field with no blank-means-something-else
         *                 case, the way {@code productCode}/{@code
         *                 productName} never are.
         * @param status   null when the CSV cell was blank, with the
         *                 identical "leave unchanged on update, default to
         *                 ACTIVE on create" meaning {@code unitCode} has.
         */
        record Ok(
                String productCode,
                String productName,
                @Nullable String description,
                @Nullable String sku,
                @Nullable String unitCode,
                @Nullable Long priceAmountMinor,
                @Nullable String currency,
                @Nullable Status status,
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

            // null (not a default) when the column is blank -- see the Ok
            // record's own doc for why: a blank cell means something
            // different on create (default to ACTIVE) than on update
            // (leave the existing status alone), and only the caller that
            // already knows which of those two cases it is in can resolve
            // that, not this row-shape-only parse step.
            Status status = null;
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
                    unitCode == null ? null : unitCode.toUpperCase(Locale.ROOT),
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
