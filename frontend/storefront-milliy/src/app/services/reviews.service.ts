import { Injectable, inject } from '@angular/core';

import { ApiClient } from '../core/api/api-client';
import { APP_CONFIG } from '../core/config/app-config';
import { newIdempotencyKey } from '../core/api/idempotency';
import type { Page } from '../core/api/page';

/**
 * ADR 0072's sibling: ADR 0071's customer reviews, `Reviews` on
 * `StorefrontReviewController`.
 *
 * Built directly on this contract -- there is no legacy review surface to
 * replace, this is the first storefront client to consume it. Three rules
 * from the OpenAPI description shape everything here:
 *
 * - **One review per order, ever.** `POST .../orders/{orderId}/review` is
 *   refused with `RESOURCE_NOT_FOUND` when the order is not the caller's own,
 *   `UNPROCESSABLE_STATE` when it has not reached `COMPLETED` yet, and
 *   `RESOURCE_CONFLICT` when it already has one. There is no edit or
 *   withdraw endpoint (ADR 0071's own open input), so a submitted review is
 *   permanent from this client's point of view.
 * - **The rating is a single 1-5 integer plus an optional comment.** The
 *   design's three-way food/service/delivery breakdown has no field on
 *   `SubmitReviewRequest` -- see `ProfileComponent`'s own doc comment for
 *   where that gap is surfaced to a customer instead of invented.
 * - **`myReviews` is cursor-paginated** (ADR 0031) and answers newest first.
 */
@Injectable({ providedIn: 'root' })
export class ReviewsService {
  private readonly api = inject(ApiClient);
  private readonly config = inject(APP_CONFIG);

  private get brandPath(): string {
    return `/storefront/tenants/${this.config.tenantId}/brands/${this.config.brandId}`;
  }

  /**
   * The caller's own reviews, newest first.
   *
   * One page only: a screen deciding whether *a particular* order still needs
   * rating reads a handful of the most recent reviews, not a full history.
   */
  async myReviews(limit = 20): Promise<Page<ReviewResponse>> {
    return this.api.list<ReviewResponse>(`${this.brandPath}/reviews`, { limit });
  }

  /**
   * Rates a completed order, once.
   *
   * @param comment sent only when non-empty -- an empty string is not the same
   *        request as "no comment" to a server that stores whatever it is given.
   */
  async submit(orderId: string, rating: number, comment?: string): Promise<ReviewResponse> {
    const trimmed = comment?.trim();
    return this.api.mutate<ReviewResponse>('POST', `${this.brandPath}/orders/${orderId}/review`, {
      body: { rating, comment: trimmed ? trimmed : undefined },
      idempotencyKey: newIdempotencyKey(),
    });
  }
}

/** `StorefrontReviewController.ReviewResponse`, transcribed from the OpenAPI schema. */
export interface ReviewResponse {
  readonly id: string;
  readonly orderId: string;
  readonly rating: number;
  readonly comment: string | null;
  readonly submittedAt: string;
}
