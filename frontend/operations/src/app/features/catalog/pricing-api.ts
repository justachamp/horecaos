import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { ApiClient } from '../../core/api/api-client';
import { BrandScope, pricingPaths } from '../../core/api/catalog-paths';
import { command } from '../../core/api/idempotency';
import {
  CreatePriceBookRequest,
  PriceBookAssignmentRequest,
  PriceBookSummary,
  PriceRequest,
  ResolvedPrices,
} from './catalog-domain';

/**
 * `GET/PUT .../pricing/**` — `PriceAuthoringController` (ADR 0018). Same
 * `control-plane` prefix note as `CatalogApi`; see `catalog-paths.ts`.
 *
 * catalog.md §4.2 tab 2's "Базовая цена" cell writes through
 * {@link setVariantPrice}: a new price row, never an UPDATE — `ux_price_current`
 * is what makes "the current price" a well-defined question, and a caller that
 * mutated a row in place would be racing that constraint instead of using it.
 */
@Injectable({ providedIn: 'root' })
export class PricingApi {
  private readonly api = inject(ApiClient);

  listPriceBooks(scope: BrandScope): Observable<readonly PriceBookSummary[]> {
    return this.api
      .get<readonly PriceBookSummary[]>(pricingPaths.priceBooks(scope))
      .pipe(map((result) => result.value));
  }

  /**
   * Resolves the price book that applies at a location (and, optionally, a
   * channel — channel outranks location) and returns current amounts for the
   * requested variants. A brand with no price book yet is a real state: the
   * response's `priceBookId` is null and every caller must render that as
   * "нет цены", not as an error.
   */
  resolvedVariantPrices(
    scope: BrandScope,
    locationId: string,
    variantIds: readonly string[],
    channelId?: string,
  ): Observable<ResolvedPrices> {
    return this.api
      .get<ResolvedPrices>(pricingPaths.resolvedPrices(scope), {
        params: {
          locationId,
          priceableType: 'VARIANT',
          ids: variantIds,
          ...(channelId ? { channelId } : {}),
        },
      })
      .pipe(map((result) => result.value));
  }

  setVariantPrice(
    scope: BrandScope,
    priceBookId: string,
    variantId: string,
    amountMinor: number,
  ): Observable<PriceBookSummary> {
    return this.api.put<PriceRequest, PriceBookSummary>(
      pricingPaths.variantPrice(scope, priceBookId, variantId),
      command({ amountMinor }),
    );
  }

  /** IA 4.8a — a draft book, priced by nothing until it is activated. */
  createPriceBook(
    scope: BrandScope,
    request: CreatePriceBookRequest,
  ): Observable<PriceBookSummary> {
    return this.api.post<CreatePriceBookRequest, PriceBookSummary>(
      pricingPaths.priceBooks(scope),
      command(request),
    );
  }

  /** The fallback every location and channel resolves to when nothing more specific applies. */
  assignToBrand(
    scope: BrandScope,
    priceBookId: string,
    request: PriceBookAssignmentRequest,
  ): Observable<PriceBookSummary> {
    return this.api.put<PriceBookAssignmentRequest, PriceBookSummary>(
      pricingPaths.assignBrand(scope, priceBookId),
      command(request),
    );
  }

  assignToLocation(
    scope: BrandScope,
    priceBookId: string,
    locationId: string,
    request: PriceBookAssignmentRequest,
  ): Observable<PriceBookSummary> {
    return this.api.put<PriceBookAssignmentRequest, PriceBookSummary>(
      pricingPaths.assignLocation(scope, priceBookId, locationId),
      command(request),
    );
  }

  assignToChannel(
    scope: BrandScope,
    priceBookId: string,
    channelId: string,
    request: PriceBookAssignmentRequest,
  ): Observable<PriceBookSummary> {
    return this.api.put<PriceBookAssignmentRequest, PriceBookSummary>(
      pricingPaths.assignChannel(scope, priceBookId, channelId),
      command(request),
    );
  }

  /** Puts a draft book in front of customers. `expectedVersion` becomes `If-Match`. */
  activate(
    scope: BrandScope,
    priceBookId: string,
    expectedVersion: number,
  ): Observable<PriceBookSummary> {
    return this.api.post<undefined, PriceBookSummary>(
      pricingPaths.activation(scope, priceBookId),
      command(undefined),
      { expectedVersion },
    );
  }
}
