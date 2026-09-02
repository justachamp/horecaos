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

  /** Create a category in a catalog. */
  createCategory(scope: BrandScope, catalogId: string): string {
    return `${this.base(scope)}/catalogs/${encodeURIComponent(catalogId)}/categories`;
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

  /** catalog.md §4.6's read side: one location's sellable variants with current availability. */
  variantsAtLocation(scope: BrandScope, locationId: string): string {
    return `${this.base(scope)}/locations/${encodeURIComponent(locationId)}/variants`;
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
} as const;
