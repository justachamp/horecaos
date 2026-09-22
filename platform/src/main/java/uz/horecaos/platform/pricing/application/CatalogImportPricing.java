package uz.horecaos.platform.pricing.application;

import java.time.Clock;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import uz.horecaos.platform.audit.api.ActorRef;
import uz.horecaos.platform.pricing.api.CatalogImportPricingPort;
import uz.horecaos.platform.pricing.infrastructure.persistence.JdbcPricingStore;

/**
 * The {@code pricing.api} face of the catalog CSV/Excel import (row 4.5b).
 *
 * <p>A translation layer over {@link PriceAuthoringService}, the same shape
 * {@link SampleMenuPricing} already takes for onboarding: it calls the
 * service an operator's own console request calls, so a price this import
 * writes carries the same guards — the currency-mismatch refusal, the
 * ties-with-a-live-book refusal, the activation compare-and-set — as one an
 * operator typed by hand.
 *
 * <p>Priority 0, matching both {@code SampleMenuPricing}'s own book and
 * {@code PriceAuthoringController}'s own default when an operator does not
 * specify one — see this class's own port doc for why that tie is an
 * accepted, pre-existing risk rather than one this class introduces.
 */
@Component
public class CatalogImportPricing implements CatalogImportPricingPort {

    private static final Logger log = LoggerFactory.getLogger(CatalogImportPricing.class);

    private static final int CATALOG_IMPORT_PRIORITY = 0;

    private final PriceAuthoringService authoring;
    private final JdbcPricingStore store;
    private final Clock clock;

    public CatalogImportPricing(PriceAuthoringService authoring, JdbcPricingStore store, Clock clock) {
        this.authoring = authoring;
        this.store = store;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void setVariantPrice(UUID tenantId, UUID brandId, UUID variantId, long amountMinor, String currency) {
        String normalizedCurrency = currency.toUpperCase(Locale.ROOT);
        try {
            Optional<JdbcPricingStore.PriceBookSummaryRow> existing = book(tenantId, brandId);
            UUID priceBookId;
            if (existing.isPresent()) {
                JdbcPricingStore.PriceBookSummaryRow row = existing.get();
                if (!row.currency().equals(normalizedCurrency)) {
                    throw new PriceRefusedException(
                            "The catalog import price book for this brand is already in %s, not %s"
                                    .formatted(row.currency(), normalizedCurrency),
                            null);
                }
                priceBookId = row.id();
            } else {
                priceBookId = createBook(tenantId, brandId, normalizedCurrency);
            }

            authoring.setPrice(tenantId, brandId, priceBookId, PriceableType.VARIANT, variantId, amountMinor);

            PriceAuthoringService.PriceBook book = authoring.require(tenantId, brandId, priceBookId);
            if (book.status() == PriceAuthoringService.Status.DRAFT) {
                authoring.activate(
                        tenantId, brandId, priceBookId, book.version(), ActorRef.systemJob("catalog-import"));
            }
        } catch (PriceAuthoringService.PriceBookLifecycleException refusal) {
            throw new PriceRefusedException(
                    Objects.requireNonNullElse(refusal.getMessage(), "Pricing refused the catalog import price book"),
                    refusal);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> currentPrice(UUID tenantId, UUID brandId, UUID variantId) {
        Optional<JdbcPricingStore.PriceBookSummaryRow> existing = book(tenantId, brandId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.pricesFor(existing.get().id(), "VARIANT", Set.of(variantId), clock.instant())
                .get(variantId));
    }

    private Optional<JdbcPricingStore.PriceBookSummaryRow> book(UUID tenantId, UUID brandId) {
        return store.priceBooksForBrand(tenantId, brandId).stream()
                .filter(row -> CATALOG_IMPORT_PRICE_BOOK_NAME.equals(row.name()))
                .filter(row -> !PriceAuthoringService.Status.ARCHIVED.name().equals(row.status()))
                .findFirst();
    }

    private UUID createBook(UUID tenantId, UUID brandId, String currency) {
        PriceAuthoringService.PriceBook book = authoring.create(
                tenantId,
                brandId,
                new PriceAuthoringService.NewPriceBook(
                        CATALOG_IMPORT_PRICE_BOOK_NAME, currency, null, null, CATALOG_IMPORT_PRIORITY));
        authoring.assign(
                tenantId,
                brandId,
                book.id(),
                PriceAuthoringService.AssignmentScope.BRAND,
                null,
                new PriceAuthoringService.Assignment(CATALOG_IMPORT_PRIORITY, null, null));
        log.info("Created catalog import price book {} for brand {} in {}", book.id(), brandId, currency);
        return book.id();
    }
}
