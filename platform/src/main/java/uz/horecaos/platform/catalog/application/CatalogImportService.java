package uz.horecaos.platform.catalog.application;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.catalog.api.CatalogImportPricingPort;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Category;
import uz.horecaos.platform.catalog.domain.CatalogEntities.EntityType;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Product;
import uz.horecaos.platform.catalog.domain.CatalogEntities.Variant;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogImportStore;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogImportStore.ClaimedRun;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogImportStore.RunRow;
import uz.horecaos.platform.catalog.infrastructure.persistence.JdbcCatalogStore;
import uz.horecaos.platform.configuration.Ids;
import uz.horecaos.platform.web.api.ApiException;
import uz.horecaos.platform.web.api.ErrorCode;

/**
 * The async job surface row 4.5b's brief names as missing: submits a catalog
 * CSV/Excel import as a durable, pollable job over one brand catalog — the
 * same shape {@code CustomerImportService} already gives the generic
 * customer CSV import, and modelled on it directly.
 *
 * <p>{@link #submit} does the one thing that must happen on the request
 * thread — parsing the file, which costs no external call, to know {@code
 * rowsTotal} from the very first {@code GET} — then hands the file's own
 * content to {@code catalog.import_runs} for {@link CatalogImportRunWorker}
 * to claim and work. {@link #processNextQueuedRun} is that worker's own
 * call, public for the same reason {@code CustomerImportService}'s own is:
 * the worker should not have to reach into a scheduling concern that is not
 * this service's own.
 */
@Service
public class CatalogImportService {

    private static final Logger log = LoggerFactory.getLogger(CatalogImportService.class);

    /** A short, loggable code -- never the parser's or the row's own message, matching {@code CustomerImportService}'s reasoning. */
    private static final String FAILURE_REASON = "PROCESSING_FAILED";

    private final CatalogImportParser parser;
    private final CatalogImportRowService rowService;
    private final JdbcCatalogImportStore store;
    private final JdbcCatalogStore catalogStore;
    private final CatalogImportPricingPort pricing;
    private final Clock clock;
    private final String defaultLocale;

    public CatalogImportService(
            CatalogImportParser parser,
            CatalogImportRowService rowService,
            JdbcCatalogImportStore store,
            JdbcCatalogStore catalogStore,
            CatalogImportPricingPort pricing,
            Clock clock,
            @Value("${horecaos.catalog.default-locale:uz}") String defaultLocale) {
        this.parser = parser;
        this.rowService = rowService;
        this.store = store;
        this.catalogStore = catalogStore;
        this.pricing = pricing;
        this.clock = clock;
        this.defaultLocale = defaultLocale;
    }

    /** Parses and queues a run; throws if the document itself is not readable as CSV. */
    public UUID submit(
            UUID tenantId,
            UUID brandId,
            UUID catalogId,
            boolean dryRun,
            String sourceFileName,
            String content,
            String importedBySubject) {
        List<CatalogImportRow> parsedRows;
        try {
            parsedRows = parser.parse(content);
        } catch (CatalogImportParser.CatalogImportFormatException malformed) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, malformed.getMessage());
        }

        UUID runId = Ids.newId();
        Instant now = clock.instant();
        store.insertQueuedRun(
                runId,
                tenantId,
                brandId,
                catalogId,
                dryRun,
                sourceFileName,
                content,
                importedBySubject,
                parsedRows.size(),
                now);
        return runId;
    }

    /**
     * Claims and fully works one queued run, if one exists.
     *
     * <p>Never throws for a single run's own failure — a malformed re-parse
     * settles that run as {@code FAILED} and this method returns normally,
     * the same rule {@code CustomerImportService.processNextQueuedRun}'s own
     * doc states. A single row's own foreseeable failure (a bad price, a
     * refused image, a SKU collision) is not this: it is {@link
     * CatalogImportRowService}'s own {@code ERROR} outcome, already handled
     * before it ever reaches this method. What can still throw here, besides
     * the claim itself, is {@link
     * CatalogImportRowService.RowPriceRefusedException} — pricing's own
     * permanent refusal, caught below and turned into the row's {@code
     * PRICE_REFUSED} error rather than failing every row still to come.
     *
     * @return true if a run was claimed (whether it then completed or failed)
     */
    public boolean processNextQueuedRun() {
        Optional<ClaimedRun> claimed = store.claimNextQueuedRun(clock.instant());
        if (claimed.isEmpty()) {
            return false;
        }
        ClaimedRun run = claimed.get();
        try {
            List<CatalogImportRow> rows = parser.parse(run.content());
            @Nullable UUID actorId = actorIdOf(run.importedByPrincipalId());
            for (CatalogImportRow row : rows) {
                CatalogImportRowOutcome outcome;
                try {
                    outcome = rowService.process(
                            run.tenantId(), run.brandId(), run.catalogId(), row, run.dryRun(), actorId);
                } catch (CatalogImportRowService.RowPriceRefusedException priceRefused) {
                    outcome = CatalogImportRowOutcome.error(CatalogImportRowErrorReason.PRICE_REFUSED);
                }
                store.insertRow(
                        run.tenantId(),
                        run.id(),
                        row.rowNumber(),
                        outcome.type().name(),
                        outcome.productId(),
                        outcome.variantId(),
                        outcome.errorReason() == null
                                ? null
                                : outcome.errorReason().name(),
                        clock.instant());
                store.advanceProgress(run.tenantId(), run.id(), outcome.type().name());
            }
            store.completeRun(run.tenantId(), run.id(), run.dryRun(), clock.instant());
        } catch (RuntimeException failure) {
            log.error("Catalog import run {} failed while processing", run.id(), failure);
            store.failRun(run.tenantId(), run.id(), FAILURE_REASON, clock.instant());
        }
        return true;
    }

    public RunRow status(UUID tenantId, UUID runId) {
        return store.run(tenantId, runId)
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND, "No such import run"));
    }

    public List<RunRow> history(UUID tenantId, UUID brandId, int limit) {
        return store.runsForBrand(tenantId, brandId, limit);
    }

    public List<JdbcCatalogImportStore.ImportRowView> rows(UUID tenantId, UUID runId, int limit, int offset) {
        return store.rows(tenantId, runId, limit, offset);
    }

    /** The empty template a merchant downloads and fills. */
    public String template() {
        return parser.template();
    }

    /**
     * The brand's catalog, filled into the same template {@link #template}
     * hands out.
     *
     * <p>Only what one row of this import can say about a product: its
     * default variant's SKU and unit, its default-locale name and
     * description, its first category, and its price in this import's own
     * catalog-import price book — never a price from a book the tenant
     * authored separately through the Price List page, matching {@link
     * CatalogImportPricingPort}'s own documented scope limitation. A brand
     * that has never run this import therefore exports every price column
     * blank, which is honest: this import has never written one.
     */
    @Transactional(readOnly = true)
    public String export(UUID tenantId, UUID brandId, UUID catalogId) {
        List<Product> products = catalogStore.productsInCatalog(tenantId, brandId, catalogId);
        if (products.isEmpty()) {
            return parser.template();
        }

        Map<UUID, List<Variant>> variantsByProduct =
                catalogStore.variantsInCatalog(tenantId, brandId, catalogId).stream()
                        .collect(Collectors.groupingBy(Variant::productId));

        Map<UUID, Category> categoriesById = catalogStore.categoriesInCatalog(tenantId, brandId, catalogId).stream()
                .collect(Collectors.toMap(Category::id, category -> category));
        Map<UUID, UUID> categoryIdByProduct = new LinkedHashMap<>();
        catalogStore
                .productIdsByCategory(tenantId, brandId, catalogId)
                .forEach((categoryId, productIds) ->
                        productIds.forEach(productId -> categoryIdByProduct.putIfAbsent(productId, categoryId)));

        Map<UUID, String> productNames = new LinkedHashMap<>();
        Map<UUID, String> productDescriptions = new LinkedHashMap<>();
        Map<UUID, String> categoryNames = new LinkedHashMap<>();
        for (JdbcCatalogStore.TranslationRow translation : catalogStore.translations(tenantId, brandId)) {
            if (!defaultLocale.equals(translation.locale())) {
                continue;
            }
            if (translation.entityType() == EntityType.PRODUCT) {
                productNames.put(translation.entityId(), translation.name());
                productDescriptions.put(translation.entityId(), translation.description());
            } else if (translation.entityType() == EntityType.CATEGORY) {
                categoryNames.put(translation.entityId(), translation.name());
            }
        }

        List<CatalogImportParser.CatalogExportRow> rows = new ArrayList<>();
        for (Product product : products) {
            Variant defaultVariant = variantsByProduct.getOrDefault(product.id(), List.of()).stream()
                    .filter(Variant::isDefault)
                    .findFirst()
                    .orElse(null);
            if (defaultVariant == null) {
                // Every product created through CatalogAuthoringService has one;
                // skipped rather than thrown for a row this import did not
                // create (a product authored some other way, mid-edit).
                continue;
            }
            UUID categoryId = categoryIdByProduct.get(product.id());
            Category category = categoryId == null ? null : categoriesById.get(categoryId);
            Optional<CatalogImportPricingPort.PricedAmount> price =
                    pricing.currentPrice(tenantId, brandId, defaultVariant.id());

            rows.add(new CatalogImportParser.CatalogExportRow(
                    product.code(),
                    category == null ? null : category.code(),
                    category == null ? null : categoryNames.getOrDefault(category.id(), category.code()),
                    productNames.getOrDefault(product.id(), product.code()),
                    productDescriptions.get(product.id()),
                    defaultVariant.sku(),
                    defaultVariant.unitCode(),
                    price.map(CatalogImportPricingPort.PricedAmount::amountMinor)
                            .orElse(null),
                    price.map(CatalogImportPricingPort.PricedAmount::currency).orElse(null),
                    product.status().name()));
        }
        return parser.export(rows);
    }

    private static @Nullable UUID actorIdOf(String importedByPrincipalId) {
        // Best-effort: a Keycloak subject is usually a UUID, but never
        // guaranteed to be one -- the same "attribution only, never a refusal"
        // shape MediaAsset.createdBy already documents for the identical
        // parse, and null here is what that shape means: recorded when
        // possible, never invented.
        try {
            return UUID.fromString(importedByPrincipalId);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
