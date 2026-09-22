package uz.horecaos.platform.catalog.application;

/**
 * Why one row of a catalog CSV/Excel import was not imported (row 4.5b).
 *
 * <p>A fixed, short vocabulary rather than a free-text message — the same
 * reason {@code CustomerCsvImportRejectReason} gives for its own: an operator
 * reads this in the run's per-row report and decides whether to fix the file
 * and re-run, and a code is worth more than a sentence that differs on every
 * row. Never row content — every value here describes what the row's shape or
 * the platform's own state prevented, never what the row said.
 */
public enum CatalogImportRowErrorReason {

    /** {@code product_code} is blank. It is this import's row identity; nothing can proceed without it. */
    MISSING_PRODUCT_CODE,

    /** {@code product_name} is blank. Every row states the product's name, whether creating or updating. */
    MISSING_PRODUCT_NAME,

    /** {@code status} is present but is not one of {@code DRAFT}/{@code ACTIVE}/{@code ARCHIVED}. */
    INVALID_STATUS,

    /**
     * {@code price_amount_minor} and {@code price_currency} disagree about whether
     * this row carries a price — one is present without the other — or one of
     * them does not parse: a non-integer or negative amount, or a currency code
     * that is not three letters.
     */
    INVALID_PRICE,

    /**
     * Pricing refused the write permanently — most often the brand's catalog-import
     * price book already exists in a different currency than this row's.
     */
    PRICE_REFUSED,

    /**
     * {@code variant_sku} is already used by a different product's variant.
     * {@code catalog.variants} carries a brand-unique index on {@code sku}
     * (V0016's {@code uq_variant_sku}) — checked here before any write, so
     * the collision is a deliberate row-level error rather than a database
     * constraint failure that would fail the whole run.
     */
    DUPLICATE_SKU,

    /**
     * {@code image_url} did not resolve to an acceptable image — refused by size,
     * by type, by an unreachable host, or by a disallowed URL scheme. Only ever
     * produced on {@code apply}: a dry run never performs the fetch, so it cannot
     * discover this.
     */
    IMAGE_FETCH_FAILED
}
