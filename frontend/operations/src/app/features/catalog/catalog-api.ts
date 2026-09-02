import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { BrandScope, catalogPaths } from '../../core/api/catalog-paths';
import { command } from '../../core/api/idempotency';
import {
  AddModifierOptionRequest,
  AddVariantRequest,
  AttachMediaRequest,
  CatalogEntityType,
  CatalogSummary,
  CategorySummary,
  CreateCatalogRequest,
  CreateCategoryRequest,
  CreateModifierGroupRequest,
  CreateProductRequest,
  FiscalClassification,
  IdResponse,
  ModifierGroupDetail,
  ModifierGroupSummary,
  ProductCreated,
  ProductDetail,
  ProductSummary,
  PublicationResult,
  SetOfferingRequest,
  SortOrderRequest,
  TranslateRequest,
  ValidationReport,
  VariantAvailabilityRow,
} from './catalog-domain';
import { CursorState, Page } from '../../core/api/page';

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

  listProducts(
    scope: BrandScope,
    catalogId: string,
    page: CursorState,
  ): Observable<Page<ProductSummary>> {
    return this.api.page<ProductSummary>(catalogPaths.products(scope, catalogId), page);
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

  /** catalog.md §4.6's read side / §4.2 tab 6: one location's sellable variants with current availability. */
  variantsAtLocation(
    scope: BrandScope,
    locationId: string,
  ): Observable<readonly VariantAvailabilityRow[]> {
    return unwrap(
      this.api.get<readonly VariantAvailabilityRow[]>(
        catalogPaths.variantsAtLocation(scope, locationId),
      ),
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
}

/** `ApiClient.get` returns the value with its `ETag` version; these reads have no aggregate to version. */
function unwrap<T>(versioned: Observable<{ value: T }>): Observable<T> {
  return versioned.pipe(map((result) => result.value));
}
