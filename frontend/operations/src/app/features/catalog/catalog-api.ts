import { Injectable, inject } from '@angular/core';
import { Observable, firstValueFrom, map } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { BrandScope, catalogPaths } from '../../core/api/catalog-paths';
import { command } from '../../core/api/idempotency';
import {
  AddModifierOptionRequest,
  AddVariantRequest,
  AttachMediaRequest,
  AttachRecommendationRequest,
  BulkClassifyItem,
  BulkClassifyResult,
  BulkOfferingStatusRequest,
  BulkOfferingStatusResult,
  CatalogEntityType,
  CatalogStatus,
  CatalogSummary,
  CategorySummary,
  CreateCatalogRequest,
  CreateCategoryRequest,
  CreateModifierGroupRequest,
  CreateProductRequest,
  DraftPreview,
  FiscalClassification,
  FiscalCoverageSummary,
  IdResponse,
  ItemSaleScheduleBody,
  ModifierGroupDetail,
  ModifierGroupSummary,
  MxikReferenceRow,
  ProductCreated,
  ProductDetail,
  ProductListStatus,
  ProductSummary,
  PublicationHistoryEntry,
  PublicationResult,
  RecommendationItem,
  RecommendationList,
  SetOfferingRequest,
  SortOrderRequest,
  StopInAllBranchesResult,
  TranslateRequest,
  UpdateCategoryRequest,
  UpdateVariantRequest,
  ValidationReport,
  VariantAvailabilityRow,
} from './catalog-domain';
import { CursorState, Page, firstPage, nextPage } from '../../core/api/page';

/**
 * `GET/POST/PUT .../catalog/**` — `CatalogAuthoringController` and
 * `CatalogQueryController` (draft authoring + reads) and
 * `CatalogPublicationController` (validate/publish/rollback).
 *
 * All three controllers live under the `control-plane` prefix — see
 * `catalog-paths.ts`'s own doc for why this console still calls it directly.
 * Every write here is a draft edit; nothing is visible to a customer until
 * {@link publish} — catalog.md §0's authoring-vs-availability discipline
 * (ADR 0016). The one write that is *not* draft-scoped is
 * {@link setOffering}: it takes effect immediately, deliberately, and its own
 * Javadoc on the server says so.
 */
/** {@link CatalogApi.listProducts}'s server-side filters — the products list's search box and status tabs. */
export interface ProductListFilters {
  readonly query?: string;
  readonly status?: ProductListStatus;
}

@Injectable({ providedIn: 'root' })
export class CatalogApi {
  private readonly api = inject(ApiClient);

  // ---------------------------------------------------------- reads

  listCatalogs(scope: BrandScope): Observable<readonly CatalogSummary[]> {
    return unwrap(this.api.get<readonly CatalogSummary[]>(catalogPaths.catalogs(scope)));
  }

  listCategories(scope: BrandScope, catalogId: string): Observable<readonly CategorySummary[]> {
    return unwrap(
      this.api.get<readonly CategorySummary[]>(catalogPaths.categories(scope, catalogId)),
    );
  }

  /**
   * `query`/`status` are applied server-side (`CatalogQueryController`), not
   * over whatever page happens to be loaded — the whole reason this row
   * exists: on a 1000+ item catalogue, filtering a page already in hand
   * cannot find a dish sitting past it.
   */
  listProducts(
    scope: BrandScope,
    catalogId: string,
    page: CursorState,
    filters: ProductListFilters = {},
  ): Observable<Page<ProductSummary>> {
    return this.api.page<ProductSummary>(catalogPaths.products(scope, catalogId), page, {
      query: filters.query || undefined,
      status: filters.status || undefined,
    });
  }

  productDetail(scope: BrandScope, productId: string): Observable<ProductDetail> {
    return unwrap(this.api.get<ProductDetail>(catalogPaths.product(scope, productId)));
  }

  listModifierGroups(scope: BrandScope): Observable<readonly ModifierGroupSummary[]> {
    return unwrap(
      this.api.get<readonly ModifierGroupSummary[]>(catalogPaths.modifierGroups(scope)),
    );
  }

  modifierGroupDetail(scope: BrandScope, groupId: string): Observable<ModifierGroupDetail> {
    return unwrap(this.api.get<ModifierGroupDetail>(catalogPaths.modifierGroup(scope, groupId)));
  }

  /**
   * The ИКПУ/MXIK reference typeahead (IA 4.2e) — empty when the official
   * list has never been imported, not an error; `q-combobox`'s `search`
   * output is the caller.
   */
  searchMxikReference(
    scope: BrandScope,
    query: string,
    limit = 20,
  ): Observable<readonly MxikReferenceRow[]> {
    return unwrap(
      this.api.get<{ items: readonly MxikReferenceRow[] }>(catalogPaths.mxikReference(scope), {
        params: { query, limit },
      }),
    ).pipe(map((page) => page.items));
  }

  /**
   * catalog.md §4.6's read side / §4.2 tab 6 / §4.5's Layer A matrix: one
   * location's variants with current availability.
   *
   * `CatalogAuthoringController.variantsAtLocation` answers a cursor
   * `Page<VariantAvailabilityResponse>` (ADR 0031), not a bare array — this
   * used to call the non-paged `api.get` and unwrap it as if it were one,
   * which produced a `{ items, nextCursor }` object wherever the caller
   * expected `readonly VariantAvailabilityRow[]`. Nothing caught it because
   * every spec stubbed this method wholesale with a plain array; against
   * the real endpoint, `menus-page.ts`'s `@for` over that object throws
   * (see its own regression spec).
   *
   * @param filters `search` (product name or SKU) and `status`
   *                (`AVAILABLE`/`UNAVAILABLE`/`HIDDEN`/`NOT_ADDED`), both
   *                optional — `resetOnFilterChange` on the caller's own
   *                `CursorState` before a filter change, or a cursor minted
   *                under the old filter set is sent with the new one.
   */
  variantsAtLocation(
    scope: BrandScope,
    locationId: string,
    page: CursorState,
    filters: { readonly search?: string; readonly status?: string } = {},
  ): Observable<Page<VariantAvailabilityRow>> {
    return this.api.page<VariantAvailabilityRow>(
      catalogPaths.variantsAtLocation(scope, locationId),
      page,
      filters.search || filters.status
        ? {
            ...(filters.search ? { search: filters.search } : {}),
            ...(filters.status ? { status: filters.status } : {}),
          }
        : {},
    );
  }

  /** catalog.md §4.5's bulk stop/unstop — sets many variants' offering status at one location in one call. */
  bulkSetOfferingStatus(
    scope: BrandScope,
    locationId: string,
    request: BulkOfferingStatusRequest,
  ): Observable<BulkOfferingStatusResult> {
    return this.api.post<BulkOfferingStatusRequest, BulkOfferingStatusResult>(
      catalogPaths.bulkOfferingStatus(scope, locationId),
      command(request),
    );
  }

  // ---------------------------------------------------------- authoring writes

  createCatalog(scope: BrandScope, request: CreateCatalogRequest): Observable<IdResponse> {
    return this.api.post<CreateCatalogRequest, IdResponse>(
      catalogPaths.catalogs(scope),
      command(request),
    );
  }

  createProduct(
    scope: BrandScope,
    catalogId: string,
    request: CreateProductRequest,
  ): Observable<ProductCreated> {
    return this.api.post<CreateProductRequest, ProductCreated>(
      catalogPaths.products(scope, catalogId),
      command(request),
    );
  }

  addVariant(
    scope: BrandScope,
    productId: string,
    request: AddVariantRequest,
  ): Observable<IdResponse> {
    return this.api.post<AddVariantRequest, IdResponse>(
      catalogPaths.variants(scope, productId),
      command(request),
    );
  }

  /** Corrects an existing variant's SKU, unit, status, and optionally makes it the default. */
  updateVariant(
    scope: BrandScope,
    productId: string,
    variantId: string,
    request: UpdateVariantRequest,
  ): Observable<void> {
    return this.api.put<UpdateVariantRequest, void>(
      catalogPaths.variant(scope, productId, variantId),
      command(request),
    );
  }

  /** The undo {@link placeInCategory} never had. Idempotent. */
  removeProductFromCategory(
    scope: BrandScope,
    categoryId: string,
    productId: string,
  ): Observable<void> {
    return this.api
      .send<null, void>(
        'DELETE',
        catalogPaths.categoryProduct(scope, categoryId, productId),
        command(null),
      )
      .pipe(map(() => undefined));
  }

  /** Removes a product from a catalog. Idempotent — the product itself is untouched. */
  removeProductFromCatalog(
    scope: BrandScope,
    catalogId: string,
    productId: string,
  ): Observable<void> {
    return this.api
      .send<null, void>(
        'DELETE',
        catalogPaths.catalogProduct(scope, catalogId, productId),
        command(null),
      )
      .pipe(map(() => undefined));
  }

  createCategory(
    scope: BrandScope,
    catalogId: string,
    request: CreateCategoryRequest,
  ): Observable<IdResponse> {
    return this.api.post<CreateCategoryRequest, IdResponse>(
      catalogPaths.createCategory(scope, catalogId),
      command(request),
    );
  }

  /**
   * Reparents, renames the code of, or re-sorts an existing category —
   * categories were write-once before this (catalog.md §4.3). Name and
   * description still go through {@link setTranslation}. A reparent that
   * would make the category its own ancestor answers a
   * `findingCode: 'CATEGORY_TREE_HAS_CYCLE'` property on the problem
   * response, the same code a blocked publication renders.
   */
  updateCategory(
    scope: BrandScope,
    catalogId: string,
    categoryId: string,
    request: UpdateCategoryRequest,
  ): Observable<void> {
    return this.api.put<UpdateCategoryRequest, void>(
      catalogPaths.category(scope, catalogId, categoryId),
      command(request),
    );
  }

  /** Never a hard delete — a product already placed in this category keeps its row. */
  archiveCategory(scope: BrandScope, catalogId: string, categoryId: string): Observable<void> {
    return this.api.post<undefined, void>(
      catalogPaths.archiveCategory(scope, catalogId, categoryId),
      command(undefined),
    );
  }

  placeInCategory(
    scope: BrandScope,
    categoryId: string,
    productId: string,
    sortOrder: number,
  ): Observable<void> {
    return this.api.put<SortOrderRequest, void>(
      catalogPaths.categoryProduct(scope, categoryId, productId),
      command({ sortOrder }),
    );
  }

  createModifierGroup(
    scope: BrandScope,
    request: CreateModifierGroupRequest,
  ): Observable<IdResponse> {
    return this.api.post<CreateModifierGroupRequest, IdResponse>(
      catalogPaths.modifierGroups(scope),
      command(request),
    );
  }

  addModifierOption(
    scope: BrandScope,
    groupId: string,
    request: AddModifierOptionRequest,
  ): Observable<IdResponse> {
    return this.api.post<AddModifierOptionRequest, IdResponse>(
      catalogPaths.modifierOptions(scope, groupId),
      command(request),
    );
  }

  attachModifierGroup(
    scope: BrandScope,
    productId: string,
    groupId: string,
    sortOrder: number,
  ): Observable<void> {
    return this.api.put<SortOrderRequest, void>(
      catalogPaths.productModifierGroup(scope, productId, groupId),
      command({ sortOrder }),
    );
  }

  setTranslation(scope: BrandScope, request: TranslateRequest): Observable<void> {
    return this.api.put<TranslateRequest, void>(catalogPaths.translations(scope), command(request));
  }

  classifyVariant(
    scope: BrandScope,
    variantId: string,
    fiscal: FiscalClassification,
  ): Observable<void> {
    return this.api.put<FiscalClassification, void>(
      catalogPaths.variantFiscalClassification(scope, variantId),
      command(fiscal),
    );
  }

  classifyModifierOption(
    scope: BrandScope,
    optionId: string,
    fiscal: FiscalClassification,
  ): Observable<void> {
    return this.api.put<FiscalClassification, void>(
      catalogPaths.modifierOptionFiscalClassification(scope, optionId),
      command(fiscal),
    );
  }

  attachMedia(
    scope: BrandScope,
    entityType: CatalogEntityType,
    entityId: string,
    assetId: string,
    request: AttachMediaRequest,
  ): Observable<void> {
    return this.api.put<AttachMediaRequest, void>(
      catalogPaths.media(scope, entityType, entityId, assetId),
      command(request),
    );
  }

  /**
   * Detaches a media asset — the undo {@link attachMedia} never had, at any
   * layer. Idempotent: detaching a relation that is already gone still
   * resolves.
   */
  detachMedia(
    scope: BrandScope,
    entityType: CatalogEntityType,
    entityId: string,
    assetId: string,
    role: string,
    channel?: string | null,
  ): Observable<void> {
    return this.api
      .send<null, void>(
        'DELETE',
        catalogPaths.media(scope, entityType, entityId, assetId),
        command(null),
        {
          params: { role, channel: channel ?? undefined },
        },
      )
      .pipe(map(() => undefined));
  }

  /**
   * Deliberately outside the publication cycle (catalog.md §0 rule 1): takes
   * effect immediately. Location-scoped in the capability, not the URL —
   * `OFFERING_MANAGE` is checked at the location the offering names.
   */
  setOffering(
    scope: BrandScope,
    variantId: string,
    locationId: string,
    request: SetOfferingRequest,
  ): Observable<void> {
    return this.api.put<SetOfferingRequest, void>(
      catalogPaths.locationOffering(scope, variantId, locationId),
      command(request),
    );
  }

  // ---------------------------------------------------------- P21 row actions and the fiscal workbench

  /** Duplicates a product with its variants, translations, catalog/category placement, modifier groups and media. */
  duplicateProduct(scope: BrandScope, productId: string): Observable<ProductCreated> {
    return this.api.post<undefined, ProductCreated>(
      catalogPaths.duplicateProduct(scope, productId),
      command(undefined),
    );
  }

  /** Archive/restore — the only mutation `Product.status` has ever had. */
  setProductStatus(scope: BrandScope, productId: string, status: CatalogStatus): Observable<void> {
    return this.api.put<{ status: CatalogStatus }, void>(
      catalogPaths.productStatus(scope, productId),
      command({ status }),
    );
  }

  /** Stops a product in every branch that currently offers it. */
  stopInAllBranches(scope: BrandScope, productId: string): Observable<StopInAllBranchesResult> {
    return this.api.post<undefined, StopInAllBranchesResult>(
      catalogPaths.stopInAllBranches(scope, productId),
      command(undefined),
    );
  }

  /** The fiscal workbench's bulk fill — many priceable nodes classified in one call. */
  bulkClassify(
    scope: BrandScope,
    items: readonly BulkClassifyItem[],
  ): Observable<BulkClassifyResult> {
    return this.api.put<{ items: readonly BulkClassifyItem[] }, BulkClassifyResult>(
      catalogPaths.bulkFiscalClassification(scope),
      command({ items }),
    );
  }

  /** "N of M priceable nodes unclassified" and the unclassified worklist (MODIFIER_OPTION and FEE nodes included). */
  fiscalCoverage(scope: BrandScope): Observable<FiscalCoverageSummary> {
    return unwrap(this.api.get<FiscalCoverageSummary>(catalogPaths.fiscalCoverage(scope)));
  }

  // ---------------------------------------------------------- publication

  /** No side effect — the read counterpart of {@link publish}, for the product editor's live rail. */
  validate(scope: BrandScope, catalogId: string): Observable<ValidationReport> {
    return unwrap(this.api.get<ValidationReport>(catalogPaths.validation(scope, catalogId)));
  }

  /**
   * Snapshot → validate → (if clean) retire the outgoing publication and
   * activate this one. **Answers HTTP 200 even when validation blocks it** —
   * a considered "no" is a completed request, per the server's own doc; the
   * caller renders `result.status === 'REJECTED'` as a result panel, never a
   * thrown error.
   */
  publish(scope: BrandScope, catalogId: string, channel: string): Observable<PublicationResult> {
    return this.api.post<undefined, PublicationResult>(
      catalogPaths.publications(scope, catalogId),
      command(undefined),
      {
        params: { channel },
      },
    );
  }

  /** Republishes an earlier snapshot; never edits history. Refused server-side on a REJECTED target. */
  rollback(scope: BrandScope, publicationId: string): Observable<PublicationResult> {
    return this.api.post<undefined, PublicationResult>(
      catalogPaths.publicationActivate(scope, publicationId),
      command(undefined),
    );
  }

  /** IA 4.6 Region 3 — every publication the brand has produced, newest first. */
  listPublicationHistory(scope: BrandScope): Observable<readonly PublicationHistoryEntry[]> {
    return unwrap(
      this.api.get<readonly PublicationHistoryEntry[]>(catalogPaths.publicationHistory(scope)),
    );
  }

  /**
   * IA 4.6 — the content hash the draft would publish as right now, without
   * publishing. A channel card compares this against its own last published
   * hash (from {@link listPublicationHistory}) to render "Черновик
   * отличается" versus "Актуально" before an operator commits to publishing.
   */
  draftPreview(scope: BrandScope, catalogId: string): Observable<DraftPreview> {
    return unwrap(this.api.get<DraftPreview>(catalogPaths.draftPreview(scope, catalogId)));
  }

  // ---------------------------------------------------------- P47: per-item sale schedule (4.2g)

  itemSaleSchedule(
    scope: BrandScope,
    variantId: string,
    locationId: string,
  ): Observable<ItemSaleScheduleBody> {
    return unwrap(
      this.api.get<ItemSaleScheduleBody>(
        catalogPaths.itemSaleSchedule(scope, variantId, locationId),
      ),
    );
  }

  /** Whole-set replace, matching `q-schedule-grid`'s own output — a save is always exactly what the grid shows. */
  replaceItemSaleSchedule(
    scope: BrandScope,
    variantId: string,
    locationId: string,
    body: ItemSaleScheduleBody,
  ): Observable<ItemSaleScheduleBody> {
    return this.api.put<ItemSaleScheduleBody, ItemSaleScheduleBody>(
      catalogPaths.itemSaleSchedule(scope, variantId, locationId),
      command(body),
    );
  }

  // ---------------------------------------------------------- P47: cross-sell / recommendations (4.2h)

  /** Every recommendation attached to this product, unfiltered — the editor's own management list. */
  listRecommendations(scope: BrandScope, productId: string): Observable<RecommendationList> {
    return unwrap(this.api.get<RecommendationList>(catalogPaths.recommendations(scope, productId)));
  }

  /**
   * IA 4.2's own filter — active + in-menu + not-stopped — resolved server-side
   * at one location. A target stopped today and un-stopped tomorrow reappears
   * here on its own; nothing is pruned from {@link listRecommendations}'s set.
   */
  effectiveRecommendations(
    scope: BrandScope,
    productId: string,
    locationId: string,
  ): Observable<RecommendationList> {
    return unwrap(
      this.api.get<RecommendationList>(catalogPaths.effectiveRecommendations(scope, productId), {
        params: { locationId },
      }),
    );
  }

  /** Attaches a target variant, or re-sorts it if already attached — the same call. */
  attachRecommendation(
    scope: BrandScope,
    productId: string,
    request: AttachRecommendationRequest,
  ): Observable<RecommendationItem> {
    return this.api.post<AttachRecommendationRequest, RecommendationItem>(
      catalogPaths.recommendations(scope, productId),
      command(request),
    );
  }

  /** Idempotent — detaching a target that is already gone still resolves. */
  detachRecommendation(
    scope: BrandScope,
    productId: string,
    targetVariantId: string,
  ): Observable<void> {
    return this.api
      .send<null, void>(
        'DELETE',
        catalogPaths.recommendation(scope, productId, targetVariantId),
        command(null),
      )
      .pipe(map(() => undefined));
  }
}

/** `ApiClient.get` returns the value with its `ETag` version; these reads have no aggregate to version. */
function unwrap<T>(versioned: Observable<{ value: T }>): Observable<T> {
  return versioned.pipe(map((result) => result.value));
}

/** A generous cap, not a real limit — one location's sellable menu is not thousands of rows. */
const MAX_VARIANT_PAGES = 20;

/**
 * Pages through {@link CatalogApi.variantsAtLocation} until the cursor is
 * exhausted, for the two screens that want the *whole* location matrix
 * rather than a paginated UI of their own (`menus-page`, and
 * `product-editor-page`'s availability tab, which filters the same read
 * down to one product's variants). Centralised here so the cursor loop is
 * written once, not once per caller.
 */
export async function fetchAllVariantsAtLocation(
  api: CatalogApi,
  scope: BrandScope,
  locationId: string,
  filters: { readonly search?: string; readonly status?: string } = {},
): Promise<readonly VariantAvailabilityRow[]> {
  const rows: VariantAvailabilityRow[] = [];
  let state: CursorState = firstPage(200);
  for (let fetched = 0; fetched < MAX_VARIANT_PAGES; fetched++) {
    const page = await firstValueFrom(api.variantsAtLocation(scope, locationId, state, filters));
    rows.push(...page.items);
    const next = nextPage(state, page);
    if (!next) {
      break;
    }
    state = next;
  }
  return rows;
}
