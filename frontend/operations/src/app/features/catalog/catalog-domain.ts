/**
 * Catalog wire types, hand-mirrored from the Java records they represent —
 * same house convention `orders/order-detail.ts` documents: no generated
 * OpenAPI client is consumed here, because the `control-plane` group's
 * generated file folds many controllers into one document and this console
 * needs exactly the fields its own screens read, named exactly as the
 * controller sends them.
 *
 * Sources: `catalog.web.CatalogAuthoringController`, `catalog.web.CatalogQueryController`,
 * `catalog.web.CatalogPublicationController`, `pricing.web.PriceAuthoringController`
 * (`platform/src/main/java/uz/horecaos/platform/{catalog,pricing}/web/*.java`).
 */

import { Locale } from '../../core/i18n/i18n';

/**
 * The console's {@link Locale} (`ru`/`uz-Latn`/`en`, ADR 0035, BCP 47 with a
 * script subtag) is not the catalog locale convention on the wire — `catalog.
 * translations.locale` is free text and `CatalogSnapshotLoader`'s own default
 * is `uz`, not `uz-Latn` (see its `@Value("${horecaos.catalog.default-locale:uz}")`).
 * Every call that sends a locale to a catalog endpoint goes through this, so
 * the two vocabularies cannot drift apart at a call site.
 */
export function toCatalogLocale(locale: Locale): string {
  return locale === 'uz-Latn' ? 'uz' : locale;
}

// ------------------------------------------------------------ shared

/** `EntityType` — the six catalog entities that carry a `catalog.translations` row. */
export type CatalogEntityType =
  'CATALOG' | 'CATEGORY' | 'PRODUCT' | 'VARIANT' | 'MODIFIER_GROUP' | 'MODIFIER_OPTION';

/** `Status` — `catalog.products`/`variants`/`categories`/`modifier_groups`. */
export type CatalogStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED';

/** `OfferingStatus` — `catalog.location_offerings.status`. */
export type OfferingStatus = 'AVAILABLE' | 'UNAVAILABLE' | 'HIDDEN';

/** One locale's name/description on an entity. Keyed by locale in every response that carries it. */
export interface LocalizedFields {
  readonly name: string;
  readonly description?: string | null;
}

/** `FiscalClassificationView`/`FiscalClassificationRequest` (ADR 0038). */
export interface FiscalClassification {
  readonly mxikCode?: string | null;
  readonly packageCode?: string | null;
  readonly fiscalUnitCode?: number | null;
  readonly fiscalName?: string | null;
  readonly barcode?: string | null;
  readonly markingRequired: boolean;
  readonly markingScheme?: 'NONE' | 'DATA_MATRIX' | null;
  readonly excisable: boolean;
  readonly alcoholByVolumeBp?: number | null;
  readonly ageRestrictionYears?: number | null;
}

/** An empty classification — nothing set. Distinguishes "cleared" from "not yet loaded". */
export const UNCLASSIFIED: FiscalClassification = {
  markingRequired: false,
  excisable: false,
};

// ------------------------------------------------------------ CatalogQueryController

/** `CatalogSummaryResponse`. */
export interface CatalogSummary {
  readonly catalogId: string;
  readonly code: string;
  readonly name: string;
  readonly status: CatalogStatus;
}

/** `CategorySummaryResponse`. */
export interface CategorySummary {
  readonly categoryId: string;
  readonly parentCategoryId?: string | null;
  readonly code: string;
  readonly name: string;
  readonly description?: string | null;
  readonly sortOrder: number;
  readonly status: CatalogStatus;
  readonly productCount: number;
}

/** `ProductSummaryResponse` — one row of 4.1 Products. */
export interface ProductSummary {
  readonly productId: string;
  readonly code: string;
  readonly status: CatalogStatus;
  readonly name: string;
  readonly variantCount: number;
  readonly categoryNames: readonly string[];
  readonly hasMxik: boolean;
  readonly version: number;
  /** Computed, not stored — `CatalogQueryController.shareSlugOf`. A public, URL-safe handle for the row's copy-share-link action. */
  readonly shareSlug: string;
}

/**
 * `CatalogQueryController.ProductListStatus` — the products list's server-side
 * status tabs. `NO_MXIK` is product-level (none of the product's variants
 * carry an ИКПУ/MXIK) and is not the node-level count the fiscal workbench's
 * {@link FiscalCoverageSummary} answers — the two must never be shown as the
 * same number.
 */
export type ProductListStatus = 'ACTIVE' | 'DRAFT' | 'ARCHIVED' | 'NO_MXIK';

/** `ProductDetailResponse` — 4.2 Product editor's whole load. */
export interface ProductDetail {
  readonly productId: string;
  readonly code: string;
  readonly status: CatalogStatus;
  readonly version: number;
  readonly translations: Readonly<Record<string, LocalizedFields>>;
  readonly catalogIds: readonly string[];
  readonly categoryIds: readonly string[];
  readonly variants: readonly VariantDetail[];
  readonly modifierGroups: readonly AttachedModifierGroup[];
  readonly media: readonly MediaRelation[];
}

/** `VariantDetail`. */
export interface VariantDetail {
  readonly variantId: string;
  readonly sku?: string | null;
  readonly unitCode: string;
  readonly isDefault: boolean;
  readonly sortOrder: number;
  readonly status: CatalogStatus;
  readonly version: number;
  readonly translations: Readonly<Record<string, LocalizedFields>>;
  readonly fiscal?: FiscalClassification | null;
}

/** `AttachedModifierGroupView`. */
export interface AttachedModifierGroup {
  readonly groupId: string;
  readonly sortOrder: number;
}

/** The universal channel: no per-aggregator override, every channel without one of its own falls back to this. */
export const ALL_CHANNELS = 'ALL';

/** `MediaRelationView`. */
export interface MediaRelation {
  readonly mediaAssetId: string;
  readonly role: string;
  readonly sortOrder: number;
  /** {@link ALL_CHANNELS} or a `tenant.sales_channels.code` override (IA 4.2f). */
  readonly channelCode: string;
}

/** `ModifierGroupSummaryResponse` — one row of the group library. */
export interface ModifierGroupSummary {
  readonly groupId: string;
  readonly code: string;
  readonly name: string;
  readonly required: boolean;
  readonly minimumSelections: number;
  readonly maximumSelections: number;
  readonly allowSameOptionMultipleTimes: boolean;
  readonly optionCount: number;
  readonly status: CatalogStatus;
}

/** `ModifierGroupDetailResponse`. */
export interface ModifierGroupDetail {
  readonly groupId: string;
  readonly code: string;
  readonly required: boolean;
  readonly minimumSelections: number;
  readonly maximumSelections: number;
  readonly allowSameOptionMultipleTimes: boolean;
  readonly translations: Readonly<Record<string, LocalizedFields>>;
  readonly options: readonly ModifierOption[];
}

/** `ModifierOptionView`. */
export interface ModifierOption {
  readonly optionId: string;
  readonly code: string;
  readonly translations: Readonly<Record<string, LocalizedFields>>;
  readonly linkedVariantId?: string | null;
  readonly maximumQuantity: number;
  readonly sortOrder: number;
  readonly status: CatalogStatus;
  readonly fiscal?: FiscalClassification | null;
}

/**
 * `offeringStatus` on {@link VariantAvailabilityRow}, plus the one value
 * `catalog.location_offerings.status` itself never carries: `null` on the
 * wire ("no offering row at all here") arrives as `undefined` through this
 * console's own JSON handling, so screens compare against `undefined`
 * directly rather than importing a fourth string for it.
 */
export type LocationOfferingStatus = 'AVAILABLE' | 'UNAVAILABLE' | 'HIDDEN';

/** `VariantAvailabilityResponse` — catalog.md §4.2 tab 6 / §4.5's Layer A matrix / §4.6's read side. */
export interface VariantAvailabilityRow {
  readonly variantId: string;
  readonly productName: string;
  readonly category?: string | null;
  /** The inventory 86 flag — whether this variant can be sold right now. Independent of {@link offeringStatus}. */
  readonly available: boolean;
  readonly trackingMode?: 'BINARY' | 'UNTRACKED' | 'QUANTITY' | null;
  /**
   * `catalog.location_offerings.status` at this location, or `undefined` when
   * no offering row exists here at all — "never added", distinct from every
   * real status including `HIDDEN`. See `menus-page.ts`'s own doc for why this
   * distinction is the row `4.4` this matrix exists to fix.
   */
  readonly offeringStatus?: LocationOfferingStatus | null;
  /** Empty when {@link offeringStatus} is absent. */
  readonly fulfillmentModes?: readonly string[];
}

// ------------------------------------------------------------ CatalogAuthoringController (writes)

/** `CreateCatalogRequest`. */
export interface CreateCatalogRequest {
  readonly code: string;
  readonly name: string;
  readonly locale: string;
}

/** `CreateProductRequest`. */
export interface CreateProductRequest {
  readonly code: string;
  readonly name: string;
  readonly description?: string | null;
  readonly locale: string;
  readonly sku?: string | null;
  readonly unitCode?: string | null;
  readonly fiscal?: FiscalClassification | null;
}

/** `ProductResponse` — a product and its default variant, created together. */
export interface ProductCreated {
  readonly productId: string;
  readonly defaultVariantId: string;
}

/** `AddVariantRequest`. */
export interface AddVariantRequest {
  readonly sku?: string | null;
  readonly unitCode?: string | null;
  readonly name?: string | null;
  readonly locale: string;
  readonly sortOrder: number;
  readonly fiscal?: FiscalClassification | null;
}

/** `CreateCategoryRequest`. */
export interface CreateCategoryRequest {
  readonly parentCategoryId?: string | null;
  readonly code: string;
  readonly name: string;
  readonly locale: string;
  readonly sortOrder: number;
}

/** `CreateModifierGroupRequest`. */
export interface CreateModifierGroupRequest {
  readonly code: string;
  readonly name: string;
  readonly locale: string;
  readonly required: boolean;
  readonly minimumSelections: number;
  readonly maximumSelections: number;
  readonly allowSameOptionMultipleTimes: boolean;
}

/** `AddModifierOptionRequest`. */
export interface AddModifierOptionRequest {
  readonly code: string;
  readonly name: string;
  readonly locale: string;
  readonly linkedVariantId?: string | null;
  readonly maximumQuantity: number;
  readonly sortOrder: number;
  readonly fiscal?: FiscalClassification | null;
}

/** `TranslateRequest` — upsert on `(entityType, entityId, locale)`. */
export interface TranslateRequest {
  readonly entityType: CatalogEntityType;
  readonly entityId: string;
  readonly locale: string;
  readonly name: string;
  readonly description?: string | null;
}

/** `SortOrderRequest`. */
export interface SortOrderRequest {
  readonly sortOrder: number;
}

/** `SetOfferingRequest`. */
export interface SetOfferingRequest {
  readonly status: OfferingStatus;
  readonly fulfillmentModes: readonly string[];
}

/** `BulkOfferingStatusRequest` — catalog.md §4.5's bulk stop/unstop. */
export interface BulkOfferingStatusRequest {
  readonly variantIds: readonly string[];
  readonly status: OfferingStatus;
}

/** `BulkOfferingStatusResponse`. */
export interface BulkOfferingStatusResult {
  readonly updatedCount: number;
}

/** `UpdateCategoryRequest` — parentCategoryId, code and sortOrder only; name/description stay `TranslateRequest`'s. */
export interface UpdateCategoryRequest {
  readonly parentCategoryId?: string | null;
  readonly code: string;
  readonly sortOrder: number;
}

/** `AttachMediaRequest`. */
export interface AttachMediaRequest {
  readonly role: string;
  readonly sortOrder: number;
  /** Omitted (or null) attaches {@link ALL_CHANNELS}; a channel code overrides for that channel alone (IA 4.2f). */
  readonly channel?: string | null;
}

/** `UpdateVariantRequest` — catalog.md §4.2 tab 2, otherwise read-only apart from the price input. */
export interface UpdateVariantRequest {
  readonly sku?: string | null;
  readonly unitCode: string;
  readonly isDefault: boolean;
  readonly status: CatalogStatus;
}

/** `SetProductStatusRequest`. */
export interface SetProductStatusRequest {
  readonly status: CatalogStatus;
}

/** `JdbcCatalogStore.MxikReferenceRow` — one ИКПУ/MXIK reference row (IA 4.2e). */
export interface MxikReferenceRow {
  readonly code: string;
  readonly parentCode?: string | null;
  readonly labelRu: string;
  readonly labelUz: string;
  readonly labelEn?: string | null;
  readonly defaultPackageCodes: readonly string[];
  readonly validFrom: string;
  readonly validUntil?: string | null;
}

/** `IdResponse`. */
export interface IdResponse {
  readonly id: string;
}

// ------------------------------------------------------------ P21 row actions and the fiscal workbench

/** `PriceableType` — the three things a fiscal classification can target (ADR 0038). */
export type PriceableType = 'VARIANT' | 'MODIFIER_OPTION' | 'FEE';

/** `StopInAllBranchesResponse`. */
export interface StopInAllBranchesResult {
  readonly locationsChanged: number;
}

/** `BulkClassifyItemRequest`. */
export interface BulkClassifyItem {
  readonly nodeType: PriceableType;
  readonly nodeId: string;
  readonly fiscal?: FiscalClassification | null;
}

/** `BulkClassifyOutcomeResponse.status`. */
export type BulkClassifyOutcomeStatus = 'CLASSIFIED' | 'SKIPPED_EMPTY' | 'NOT_FOUND';

/** `BulkClassifyOutcomeResponse`. */
export interface BulkClassifyOutcome {
  readonly nodeType: PriceableType;
  readonly nodeId: string;
  readonly status: BulkClassifyOutcomeStatus;
}

/** `BulkClassifyResponse`. */
export interface BulkClassifyResult {
  readonly outcomes: readonly BulkClassifyOutcome[];
}

/**
 * `CatalogQueryController.FiscalCoverageNodeResponse` — one still-unclassified
 * priceable node in the fiscal workbench's worklist. Node-level, and
 * deliberately not the products list's product-level `NO_MXIK` tab figure.
 */
export interface FiscalCoverageNode {
  readonly nodeType: PriceableType;
  readonly nodeId: string;
  readonly name?: string | null;
  readonly categoryName?: string | null;
  readonly locationCount: number;
}

/** `CatalogQueryController.FiscalCoverageResponse` — "N of M priceable nodes unclassified". */
export interface FiscalCoverageSummary {
  readonly totalNodes: number;
  readonly unclassifiedCount: number;
  readonly nodes: readonly FiscalCoverageNode[];
}

// ------------------------------------------------------------ CatalogPublicationController

/** `FindingView` — one line of a validation report. */
export interface ValidationFinding {
  readonly severity: 'BLOCKER' | 'WARNING';
  readonly code: string;
  readonly entityType?: CatalogEntityType | null;
  readonly entityId?: string | null;
  readonly entityCode?: string | null;
  readonly detail?: string | null;
}

/** `ValidationResponse`. */
export interface ValidationReport {
  readonly publishable: boolean;
  readonly findings: readonly ValidationFinding[];
}

/** `PublicationStatus`. */
export type PublicationStatus = 'VALIDATING' | 'READY' | 'REJECTED' | 'PUBLISHED' | 'RETIRED';

/** `PublicationResponse`. A publish call answers 200 even when rejected — this is the whole body. */
export interface PublicationResult {
  readonly publicationId: string;
  readonly status: PublicationStatus;
  readonly contentHash: string;
  readonly validation: ValidationReport;
}

// ------------------------------------------------------------ pricing.web.PriceAuthoringController

/** `PriceBookSummaryResponse`. */
export interface PriceBookSummary {
  readonly priceBookId: string;
  readonly name: string;
  readonly currency: string;
  readonly status: 'DRAFT' | 'ACTIVE' | 'RETIRED';
  readonly priority: number;
  readonly validFrom: string;
  readonly validUntil?: string | null;
  readonly version: number;
}

/** `ResolvedPricesResponse` — empty (null book) is a real state: no price book resolved yet. */
export interface ResolvedPrices {
  readonly priceBookId?: string | null;
  readonly currency?: string | null;
  readonly amountsMinor: Readonly<Record<string, number>>;
}

/** `PriceRequest`. */
export interface PriceRequest {
  readonly amountMinor: number;
}

/** `CreatePriceBookRequest`. */
export interface CreatePriceBookRequest {
  readonly name: string;
  readonly currency: string;
  readonly validFrom?: string | null;
  readonly validUntil?: string | null;
  readonly priority?: number;
}

/** `AssignmentRequest`. */
export interface PriceBookAssignmentRequest {
  readonly priority?: number;
  readonly validFrom?: string | null;
  readonly validUntil?: string | null;
}

// ------------------------------------------------------------ CatalogPublicationController (history)

/** `PublicationHistoryResponse`, IA 4.6 Region 3. */
export interface PublicationHistoryEntry {
  readonly publicationId: string;
  readonly channel: string;
  readonly status: PublicationStatus;
  readonly contentHash: string;
  readonly createdBy?: string | null;
  readonly createdAt: string;
  readonly activatedAt?: string | null;
  readonly retiredAt?: string | null;
  readonly itemCount: number;
}
