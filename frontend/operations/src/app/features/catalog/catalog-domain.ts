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
}

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

/** `MediaRelationView`. */
export interface MediaRelation {
  readonly mediaAssetId: string;
  readonly role: string;
  readonly sortOrder: number;
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

/** `VariantAvailabilityResponse` — catalog.md §4.2 tab 6 / §4.6's read side. */
export interface VariantAvailabilityRow {
  readonly variantId: string;
  readonly productName: string;
  readonly category?: string | null;
  readonly available: boolean;
  readonly trackingMode?: 'BINARY' | 'UNTRACKED' | 'QUANTITY' | null;
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

/** `AttachMediaRequest`. */
export interface AttachMediaRequest {
  readonly role: string;
  readonly sortOrder: number;
}

/** `IdResponse`. */
export interface IdResponse {
  readonly id: string;
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
