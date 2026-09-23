/**
 * Where Catalog authoring, publication and pricing live on the platform.
 *
 * **This is not the ADR 0031 `/api/v1/operations/**` prefix.** `CatalogAuthoringController`,
 * `CatalogQueryController`, `CatalogPublicationController` and `PriceAuthoringController` are
 * all mapped under `/api/v1/control-plane/tenants/{tenantId}/brands/{brandId}/**` and belong to
 * the `control-plane` OpenAPI surface group, not `operations` — confirmed by reading the
 * controllers directly and by grepping `api/openapi/v1/horecaos-api.operations.json`, which has
 * zero catalog/pricing paths. This mirrors exactly the situation `operations-paths.ts` already
 * documents for `OperationsOrderController`'s legacy prefix: the endpoint is correct and reachable
 * with the same bearer token and the same capability checks, it is simply mapped under a path this
 * console's own audience prefix does not own. `docs/frontend-information-architecture.md` Part 1's
 * "Governing principle" says control-plane administers *the platform*, never *the merchant's
 * business* — so a merchant's own menu authoring living there is a pre-existing architectural
 * mismatch this wave did not introduce and is out of scope to fix (it would mean re-plumbing every
 * existing control-plane consumer of these controllers, not just adding a route). This module is
 * the one place that knows about it, exactly like `operations-paths.ts`'s own `LEGACY_TENANT_PREFIX`
 * — when the day comes to remap these under `/api/v1/operations/**`, this file is what changes.
 */

const CONTROL_PLANE = '/api/v1/control-plane';

/** The two identifiers that scope every Catalog authoring call (ADR 0025, brand scope). */
export interface BrandScope {
  readonly tenantId: string;
  readonly brandId: string;
}

function tenantBrand(scope: BrandScope): string {
  return `/tenants/${encodeURIComponent(scope.tenantId)}/brands/${encodeURIComponent(scope.brandId)}`;
}

export const catalogPaths = {
  base(scope: BrandScope): string {
    return `${CONTROL_PLANE}${tenantBrand(scope)}/catalog`;
  },

  /** The brand's catalogs. */
  catalogs(scope: BrandScope): string {
    return `${this.base(scope)}/catalogs`;
  },

  /** A catalog's category tree, flat (client builds the tree from `parentCategoryId`). */
  categories(scope: BrandScope, catalogId: string): string {
    return `${this.base(scope)}/catalogs/${encodeURIComponent(catalogId)}/categories`;
  },

  /** A catalog's products, cursor-paginated. Same path for `POST` (create). */
  products(scope: BrandScope, catalogId: string): string {
    return `${this.base(scope)}/catalogs/${encodeURIComponent(catalogId)}/products`;
  },

  /** One product, full detail. */
  product(scope: BrandScope, productId: string): string {
    return `${this.base(scope)}/products/${encodeURIComponent(productId)}`;
  },

  /** Add a further variant to an existing product. */
  variants(scope: BrandScope, productId: string): string {
    return `${this.product(scope, productId)}/variants`;
  },

  /** Correct an existing variant's SKU, unit, status and default flag. */
  variant(scope: BrandScope, productId: string, variantId: string): string {
    return `${this.variants(scope, productId)}/${encodeURIComponent(variantId)}`;
  },

  /** Change a product's own status — read-only text until this wave. */
  productStatus(scope: BrandScope, productId: string): string {
    return `${this.product(scope, productId)}/status`;
  },

  /** Remove a product from a catalog (`DELETE`). Same path {@link products} uses for list/create. */
  catalogProduct(scope: BrandScope, catalogId: string, productId: string): string {
    return `${this.products(scope, catalogId)}/${encodeURIComponent(productId)}`;
  },

  /** Create a category in a catalog. */
  createCategory(scope: BrandScope, catalogId: string): string {
    return `${this.base(scope)}/catalogs/${encodeURIComponent(catalogId)}/categories`;
  },

  /** Reparent, rename the code of, or re-sort an existing category. Same path also carries `/archive`. */
  category(scope: BrandScope, catalogId: string, categoryId: string): string {
    return `${this.createCategory(scope, catalogId)}/${encodeURIComponent(categoryId)}`;
  },

  /** Archive a category. Never a hard delete. */
  archiveCategory(scope: BrandScope, catalogId: string, categoryId: string): string {
    return `${this.category(scope, catalogId, categoryId)}/archive`;
  },

  /** Place (or move) a product within a category, with its sort order. */
  categoryProduct(scope: BrandScope, categoryId: string, productId: string): string {
    return `${this.base(scope)}/categories/${encodeURIComponent(categoryId)}/products/${encodeURIComponent(productId)}`;
  },

  /** The brand's modifier-group library (shared across products, not catalog-scoped). */
  modifierGroups(scope: BrandScope): string {
    return `${this.base(scope)}/modifier-groups`;
  },

  /** One modifier group, with its options. */
  modifierGroup(scope: BrandScope, groupId: string): string {
    return `${this.modifierGroups(scope)}/${encodeURIComponent(groupId)}`;
  },

  /** Add an option to a modifier group. */
  modifierOptions(scope: BrandScope, groupId: string): string {
    return `${this.modifierGroup(scope, groupId)}/options`;
  },

  /** Attach (or re-sort) a modifier group on a product. */
  productModifierGroup(scope: BrandScope, productId: string, groupId: string): string {
    return `${this.product(scope, productId)}/modifier-groups/${encodeURIComponent(groupId)}`;
  },

  /** Set one entity's name/description in one locale (upsert). */
  translations(scope: BrandScope): string {
    return `${this.base(scope)}/translations`;
  },

  /**
   * The ИКПУ/MXIK reference, tenant alias (IA 4.2e) — the same read
   * `FiscalReferenceController` serves PLATFORM-scoped, behind CATALOG_READ
   * at BRAND scope instead so a tenant operator can call it. Query params
   * `query` (required, 2+ characters) and `limit`.
   */
  mxikReference(scope: BrandScope): string {
    return `${this.base(scope)}/fiscal-reference/mxik`;
  },

  /** ИКПУ/MXIK and packaging for a variant. */
  variantFiscalClassification(scope: BrandScope, variantId: string): string {
    return `${this.base(scope)}/variants/${encodeURIComponent(variantId)}/fiscal-classification`;
  },

  /** ИКПУ/MXIK and packaging for a modifier option. */
  modifierOptionFiscalClassification(scope: BrandScope, optionId: string): string {
    return `${this.base(scope)}/modifier-options/${encodeURIComponent(optionId)}/fiscal-classification`;
  },

  /** Attach an already-uploaded, finalized media asset to a catalog entity. */
  media(scope: BrandScope, entityType: string, entityId: string, assetId: string): string {
    return `${this.base(scope)}/media/${entityType}/${encodeURIComponent(entityId)}/${encodeURIComponent(assetId)}`;
  },

  /** Whether a variant may be sold at one location, and its fulfilment modes. Location-scoped write. */
  locationOffering(scope: BrandScope, variantId: string, locationId: string): string {
    return `${this.base(scope)}/variants/${encodeURIComponent(variantId)}/location-offerings/${encodeURIComponent(locationId)}`;
  },

  /** catalog.md §4.6's read side / §4.5's Layer A matrix: one location's variants with current availability. */
  variantsAtLocation(scope: BrandScope, locationId: string): string {
    return `${this.base(scope)}/locations/${encodeURIComponent(locationId)}/variants`;
  },

  /** catalog.md §4.5's bulk stop/unstop. */
  bulkOfferingStatus(scope: BrandScope, locationId: string): string {
    return `${this.variantsAtLocation(scope, locationId)}/bulk-offering-status`;
  },

  /** The stop list's own tab badges (gap map row 2.5, wave P16) — exact over the whole catalog, following the same `search` filter. */
  variantAvailabilityCounts(scope: BrandScope, locationId: string): string {
    return `${this.variantsAtLocation(scope, locationId)}/availability-counts`;
  },

  /** ADR 0036 Layer B (wave P45): whether a variant is offered on one sales channel — separate from price_on_channel. */
  channelOffering(scope: BrandScope, channelId: string, variantId: string): string {
    return `${this.base(scope)}/channels/${encodeURIComponent(channelId)}/exclusions/variants/${encodeURIComponent(variantId)}`;
  },

  /** The mass-enable/mass-disable gesture (gap map row 4.4b) — up to 200 variants at once. */
  bulkChannelOffering(scope: BrandScope, channelId: string): string {
    return `${this.base(scope)}/channels/${encodeURIComponent(channelId)}/exclusions/bulk`;
  },

  /** Which variants are currently hidden from one channel at one location. Query param `locationId`. */
  channelExclusions(scope: BrandScope, channelId: string): string {
    return `${this.base(scope)}/channels/${encodeURIComponent(channelId)}/exclusions`;
  },

  /** The catalog's live validation report — blockers and warnings, never a side effect. */
  validation(scope: BrandScope, catalogId: string): string {
    return `${this.base(scope)}/catalogs/${encodeURIComponent(catalogId)}/validation`;
  },

  /** Snapshot, validate, and — if clean — publish to a channel. 200 even when rejected. */
  publications(scope: BrandScope, catalogId: string): string {
    return `${this.base(scope)}/catalogs/${encodeURIComponent(catalogId)}/publications`;
  },

  /** Roll back to an earlier (non-rejected) publication. */
  publicationActivate(scope: BrandScope, publicationId: string): string {
    return `${this.base(scope)}/publications/${encodeURIComponent(publicationId)}/activate`;
  },

  /** Every publication the brand has produced, newest first (IA 4.6, Region 3). Query param `limit`. */
  publicationHistory(scope: BrandScope): string {
    return `${this.base(scope)}/publications`;
  },

  /**
   * The published menu for one location (`StorefrontCatalogController`,
   * ADR 0016) — unauthenticated by design, so it is reachable with the same
   * bearer token as everything else here without a capability check of its
   * own. Not on {@link CONTROL_PLANE}: this is the one path in this module
   * that lives on `/api/v1/storefront/**`, because it is the customer-facing
   * publication, not an authoring surface. The New order screen (wave P13,
   * orders.md §5.5) reads it for prices and modifiers a location's sellable-
   * variants list ({@link variantsAtLocation}) does not carry. Query params
   * `locale` and `channel` (required — the tenant's operator channel code).
   */
  storefrontMenu(scope: BrandScope, locationId: string): string {
    return `/api/v1/storefront${tenantBrand(scope)}/locations/${encodeURIComponent(locationId)}/menu`;
  },

  /**
   * `DeliveryFeeController.quote` (ADR 0037) — the same unauthenticated
   * `/api/v1/storefront/**` preview `ui-cart.service.ts` already calls for the
   * storefront's own delivery cart, reused here for row 1.3's New order
   * composer rather than a second delivery-fee client. `POST` since
   * 2026-09-21 (audit follow-up (b)): `lat`, `lon`, `currency` and
   * `subtotalMinor` travel in the JSON body, never as query params — a query
   * string would put the customer's coordinate on the wire (ADR 0029). See
   * {@link NewOrderApi.deliveryFeeQuote} for why this needs a real
   * coordinate — a saved address with no pin has nothing to ask this about.
   */
  deliveryFee(scope: BrandScope, locationId: string): string {
    return `/api/v1/storefront${tenantBrand(scope)}/locations/${encodeURIComponent(locationId)}/delivery-fee`;
  },

  // -------------------------------------------------- P21 row actions and the fiscal workbench

  /** Duplicates a product — its variants, translations, catalog/category placement, modifier groups and media. */
  duplicateProduct(scope: BrandScope, productId: string): string {
    return `${this.product(scope, productId)}/duplicate`;
  },

  /** Stops a product in every branch that currently offers it. */
  stopInAllBranches(scope: BrandScope, productId: string): string {
    return `${this.product(scope, productId)}/stop-in-all-branches`;
  },

  /** The fiscal workbench's bulk fill — many nodes classified in one call. */
  bulkFiscalClassification(scope: BrandScope): string {
    return `${this.base(scope)}/fiscal-classifications/bulk`;
  },

  /** The fiscal workbench's "N of M priceable nodes unclassified" coverage read and worklist. */
  fiscalCoverage(scope: BrandScope): string {
    return `${this.base(scope)}/fiscal-coverage`;
  },

  /** The content hash the draft would publish as right now, without writing anything. */
  draftPreview(scope: BrandScope, catalogId: string): string {
    return `${this.base(scope)}/catalogs/${encodeURIComponent(catalogId)}/draft-preview`;
  },

  // -------------------------------------------------- P47: per-item sale schedule and cross-sell

  /** Row 4.2g: one variant's weekly sale windows at one location. Same path for `GET` and `PUT` (whole-set replace). */
  itemSaleSchedule(scope: BrandScope, variantId: string, locationId: string): string {
    return `${this.base(scope)}/variants/${encodeURIComponent(variantId)}/location-offerings/${encodeURIComponent(locationId)}/sale-schedule`;
  },

  /** Row 4.2h: a product's recommendations. `GET` (unfiltered, management) and `POST` (attach/reorder). */
  recommendations(scope: BrandScope, productId: string): string {
    return `${this.base(scope)}/products/${encodeURIComponent(productId)}/recommendations`;
  },

  /** Row 4.2h: IA 4.2's own filter -- active + in-menu + not-stopped -- resolved at one location. Query param `locationId`. */
  effectiveRecommendations(scope: BrandScope, productId: string): string {
    return `${this.recommendations(scope, productId)}/effective`;
  },

  /** Detach one recommendation. */
  recommendation(scope: BrandScope, productId: string, targetVariantId: string): string {
    return `${this.recommendations(scope, productId)}/${encodeURIComponent(targetVariantId)}`;
  },

  // -------------------------------------------------- row 4.5b: CSV/Excel import and export

  /** The empty CSV import template, `text/csv` (`CatalogImportController#template`). */
  importTemplate(scope: BrandScope): string {
    return `${this.base(scope)}/imports/template`;
  },

  /** The brand's catalog filled into the same template, `text/csv`. Query param `catalogId`. */
  importExport(scope: BrandScope): string {
    return `${this.base(scope)}/export`;
  },

  /** Queue a run (`POST`, query param `dryRun`) / this brand's run history (`GET`). */
  imports(scope: BrandScope): string {
    return `${this.base(scope)}/imports`;
  },

  /** One import run's status and progress. */
  importRun(scope: BrandScope, runId: string): string {
    return `${this.imports(scope)}/${encodeURIComponent(runId)}`;
  },

  /** One import run's per-row report. */
  importRunRows(scope: BrandScope, runId: string): string {
    return `${this.importRun(scope, runId)}/rows`;
  },
} as const;

export const pricingPaths = {
  base(scope: BrandScope): string {
    return `${CONTROL_PLANE}${tenantBrand(scope)}/pricing`;
  },

  /** The brand's price books. */
  priceBooks(scope: BrandScope): string {
    return `${this.base(scope)}/price-books`;
  },

  /** One price book (its status, currency and version — the `If-Match` value activation needs). */
  priceBook(scope: BrandScope, priceBookId: string): string {
    return `${this.priceBooks(scope)}/${encodeURIComponent(priceBookId)}`;
  },

  /** Resolves the applicable book for a location/channel and returns current amounts. */
  resolvedPrices(scope: BrandScope): string {
    return `${this.priceBooks(scope)}/resolved/prices`;
  },

  /** Sets what a variant costs in a given book. */
  variantPrice(scope: BrandScope, priceBookId: string, variantId: string): string {
    return `${this.priceBook(scope, priceBookId)}/variant-prices/${encodeURIComponent(variantId)}`;
  },

  /** Applies a price book to the whole brand — the fallback every location/channel resolves to. */
  assignBrand(scope: BrandScope, priceBookId: string): string {
    return `${this.priceBook(scope, priceBookId)}/assignments/brand`;
  },

  /** Applies a price book to one branch, beating the brand-wide book there. */
  assignLocation(scope: BrandScope, priceBookId: string, locationId: string): string {
    return `${this.priceBook(scope, priceBookId)}/assignments/locations/${encodeURIComponent(locationId)}`;
  },

  /** Applies a price book to one sales channel, outranking a branch assignment. */
  assignChannel(scope: BrandScope, priceBookId: string, channelId: string): string {
    return `${this.priceBook(scope, priceBookId)}/assignments/channels/${encodeURIComponent(channelId)}`;
  },

  /** Puts a draft book in front of customers. Mutation: `If-Match` with the book's version. */
  activation(scope: BrandScope, priceBookId: string): string {
    return `${this.priceBook(scope, priceBookId)}/activation`;
  },

  /** Changes many prices in one call against one book, with a `dryRun` preview (row 4.8b). */
  bulkApply(scope: BrandScope, priceBookId: string): string {
    return `${this.priceBook(scope, priceBookId)}/prices/bulk-apply`;
  },

  /** The brand's VAT rate for a jurisdiction. `PUT`-only — there is no read side yet. */
  taxProfile(scope: BrandScope, jurisdictionCode: string): string {
    return `${this.base(scope)}/tax-profiles/${encodeURIComponent(jurisdictionCode)}`;
  },
} as const;
