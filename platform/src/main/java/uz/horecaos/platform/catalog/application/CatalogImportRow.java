package uz.horecaos.platform.catalog.application;

import org.jspecify.annotations.Nullable;

/**
 * One parsed line of a catalog CSV/Excel import (row 4.5b), before anything
 * about the brand's own catalog has been consulted.
 *
 * <p>Every field is the raw trimmed cell, or {@code null} for a blank one —
 * deliberately undigested, the same split {@code CustomerCsvImportRow} draws
 * against {@code CustomerCsvImportRowService}: parsing a document is a
 * different failure mode from deciding what a value means once the brand's
 * own products, categories and prices are in view, and {@link
 * CatalogImportRowService} is where {@code price_amount_minor} becomes a
 * {@code long} or {@code status} becomes a {@link
 * uz.horecaos.platform.catalog.domain.CatalogEntities.Status}.
 *
 * @param rowNumber          1-based, matching the row's position in the
 *                           source file — what the report and {@code
 *                           catalog.import_run_rows} both key on
 * @param productCode        this row's identity: an existing {@code
 *                           catalog.products.code} is an update, a new one is
 *                           a create
 * @param categoryCode       optional; created in the run's own catalog on
 *                           first use, matching {@code category_name} for its
 *                           display name
 * @param categoryName       the category's name if {@code categoryCode} names
 *                           one not yet in this catalog; ignored when the
 *                           category already exists
 * @param productName        the product's name in the brand's default locale
 * @param productDescription optional
 * @param variantSku         optional; corrects the product's default
 *                           variant's SKU. This import authors one variant
 *                           per row — see {@link CatalogImportRowService}'s
 *                           own doc for why a product's other variants are
 *                           out of scope
 * @param unitCode           optional, defaults to {@code PIECE}
 * @param priceAmountMinor   optional integer minor units, as text; present
 *                           only together with {@link #priceCurrency}
 * @param priceCurrency      optional ISO currency code, as text; present only
 *                           together with {@link #priceAmountMinor}
 * @param status             optional: {@code DRAFT}/{@code ACTIVE}/{@code
 *                           ARCHIVED}, defaults to {@code ACTIVE}
 * @param imageUrl           optional; fetched server-side on {@code apply}
 *                           only — see {@link CatalogImportRowService}'s own
 *                           doc for why a dry run never performs the fetch
 */
public record CatalogImportRow(
        int rowNumber,
        @Nullable String productCode,
        @Nullable String categoryCode,
        @Nullable String categoryName,
        @Nullable String productName,
        @Nullable String productDescription,
        @Nullable String variantSku,
        @Nullable String unitCode,
        @Nullable String priceAmountMinor,
        @Nullable String priceCurrency,
        @Nullable String status,
        @Nullable String imageUrl) {}
