package uz.horecaos.platform.catalog.api;

import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Prices a catalog CSV/Excel import (row 4.5b), one variant at a time.
 *
 * <p>It lives in catalog's public interface because pricing implements it,
 * the same way round as {@link MenuPriceLookup} and {@link
 * VariantPricingLookup} — see {@link MenuPriceLookup}'s own doc for why: the
 * consumer declaring the contract is what keeps the dependency pointing one
 * way, so catalog never learns what a price book is, and {@code
 * CatalogImportRowService} (the caller) never imports anything from {@code
 * pricing}.
 *
 * <p>Narrow by design, the same shape {@code SampleMenuPricingPort} already
 * takes for onboarding: a caller outside {@code pricing} cannot reach {@code
 * PriceAuthoringService} at all under Spring Modulith. Unlike the sample
 * menu's port, this one writes and reads a single variant per call rather
 * than a batch, because {@code CatalogImportRowService} processes one CSV row
 * per transaction — the same reason {@code CustomerCsvImportRowService} is
 * its own bean, see that class's own doc — and a row that fails to price must
 * not roll back a price a previous row in the same file already committed.
 *
 * <p>Every price this port writes lands in one dedicated price book per
 * brand, {@value #CATALOG_IMPORT_PRICE_BOOK_NAME}, created on first use and
 * kept active. This is a deliberate scope limitation, not an oversight: a
 * full import of "the" price a variant should carry would mean choosing among
 * the brand's price books, resolving priority against whatever the operator
 * has already authored by hand, and re-running publication — decisions ADR
 * 0018 leaves to a person, not a file. What this port gives a re-imported
 * file is the property that matters for a dry run to be honest: {@link
 * #currentPrice} reads back exactly what {@link #setVariantPrice} last wrote
 * to the same book, so a second import of an unchanged file diffs as
 * unchanged rather than silently comparing against a different book's price.
 */
public interface CatalogImportPricingPort {

    /** The book name every catalog import's prices land in, and what a re-run finds it by. */
    String CATALOG_IMPORT_PRICE_BOOK_NAME = "Catalog import prices";

    /**
     * Sets one variant's price in the brand's catalog-import price book,
     * creating and activating that book on first use.
     *
     * @throws PriceRefusedException the book already exists in a different
     *     currency than {@code currency}, or pricing refuses the write for a
     *     reason a retry cannot fix (the same permanent-refusal shape {@code
     *     SampleMenuPricingPort} documents for its own book)
     */
    void setVariantPrice(UUID tenantId, UUID brandId, UUID variantId, long amountMinor, String currency);

    /**
     * The variant's current price in the brand's own catalog-import book, if
     * it has one there — never a price from any other book, including one the
     * tenant authored by hand or one a different price plane resolves to.
     */
    Optional<PricedAmount> currentPrice(UUID tenantId, UUID brandId, UUID variantId);

    /** An amount in the book's own currency — the book has exactly one currency, so every price it holds carries the same one. */
    record PricedAmount(long amountMinor, String currency) {}

    /** Pricing refused the write permanently; the caller must record this row as an error rather than retry it. */
    class PriceRefusedException extends RuntimeException {
        public PriceRefusedException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}
