import { Injectable, inject } from '@angular/core';
import { firstValueFrom } from 'rxjs';

import { ApiClient, QueryParams } from '../../../core/api/api-client';
import { BrandScope } from '../../../core/api/catalog-paths';
import { CursorState, Page } from '../../../core/api/page';
import { reviewPaths } from '../../../core/api/reviews-paths';

/** Mirrors `OperationsReviewController.ReviewResponse`. */
export interface ReviewRow {
  readonly id: string;
  readonly orderId: string;
  readonly locationId: string;
  readonly customerAccountId: string;
  readonly rating: number;
  readonly comment: string | null;
  readonly submittedAt: string;
}

/** Mirrors `OperationsReviewController.SummaryResponse`. */
export interface ReviewSummary {
  readonly reviewCount: number;
  readonly averageRating: number;
}

export interface ReviewFilters {
  readonly locationId?: string;
  readonly minRating?: number;
  readonly maxRating?: number;
  readonly submittedFrom?: string;
  readonly submittedTo?: string;
}

function toQueryParams(filters: ReviewFilters): QueryParams {
  return {
    locationId: filters.locationId,
    minRating: filters.minRating,
    maxRating: filters.maxRating,
    submittedFrom: filters.submittedFrom,
    submittedTo: filters.submittedTo,
  };
}

/**
 * §5.4 Reviews: a brand's own order reviews, filtered (ADR 0071). Read-only —
 * there is no write call here because there is nothing an operator writes; a
 * review is customer-authored and immutable once submitted.
 */
@Injectable({ providedIn: 'root' })
export class ReviewsApi {
  private readonly api = inject(ApiClient);

  async list(
    scope: BrandScope,
    state: CursorState,
    filters: ReviewFilters,
  ): Promise<Page<ReviewRow>> {
    return firstValueFrom(
      this.api.page<ReviewRow>(reviewPaths.list(scope), state, toQueryParams(filters)),
    );
  }

  async summary(
    scope: BrandScope,
    filters: Pick<ReviewFilters, 'locationId' | 'submittedFrom' | 'submittedTo'>,
  ): Promise<ReviewSummary> {
    const result = await firstValueFrom(
      this.api.get<ReviewSummary>(reviewPaths.summary(scope), { params: toQueryParams(filters) }),
    );
    return result.value;
  }
}
